package com.cruxcoach.android.community

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.LocalClimbDraft
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.FramesHash
import com.cruxcoach.domain.community.encodeFrames
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Glue between the editor [androidx.lifecycle.ViewModel] and the storage
 * + Nostr layers. Splits saving (always synchronous, always succeeds
 * locally) from publishing (async, may fail with no relay accepted).
 */
@Singleton
class ClimbCreatorRepository @Inject constructor(
    private val boardRepository: BoardRepository,
    private val publisher: CommunityClimbPublisher,
    private val userPreferences: UserPreferences,
    private val nostrSigner: NostrSigner,
    private val nostrProfileManager: NostrProfileManager,
) {
    /**
     * Persist editor state as a local draft. Generates a fresh UUIDv4 +
     * canonical frames_hash. Returns the draft uuid for downstream
     * publish / browse.
     *
     * `setterGradeId` is also written to `climb_stats.display_difficulty`
     * so the climb appears in the browse VIEW with a sensible grade.
     */
    suspend fun saveDraft(state: ClimbEditorState): String {
        require(state.angle != null) { "angle is required when saving draft" }

        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
        val layoutId = userPreferences.boardLayoutId.first().toLong()

        // Lowercase canonical form (see climbs.uuid comment in Board.sq
        // + 7.sqm). Pre-7.sqm this generated upper-case which left
        // already-published cruxcoach climbs in upper case in the DB
        // until 7.sqm normalized them; new drafts go straight to lower.
        val uuid = UUID.randomUUID().toString().replace("-", "").lowercase()
        val frames = state.encodeFrames()
        val moveCount = BoardClimbParser
            .estimateMoveCount(BoardClimbParser.parseFrames(frames))
            .toLong()
        val draft = LocalClimbDraft(
            uuid = uuid,
            name = state.name,
            description = state.description,
            framesText = frames,
            framesHash = FramesHash.of(frames, layoutId),
            createdAt = nowIso(),
            createdByPubkey = pubkey,
            moveCount = moveCount,
            setterUsername = resolveOwnSetterUsername(pubkey),
        )
        val angle = state.angle ?: error("angle required")
        val bounds = computeBounds(state)
        boardRepository.insertLocalDraft(
            draft = draft,
            layoutId = layoutId,
            angle = angle.toLong(),
            setterGradeId = state.setterGradeId,
            bounds = bounds,
        )
        return uuid
    }

    /**
     * Re-save an already-loaded draft in place. Same uuid as the loaded
     * row; INSERT OR REPLACE in the schema handles the upsert. Used by
     * the editor when the user opens a draft from the drawer + saves
     * after edits.
     */
    suspend fun updateDraft(uuid: String, state: ClimbEditorState) {
        require(state.angle != null) { "angle is required when updating draft" }
        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
        val layoutId = userPreferences.boardLayoutId.first().toLong()

        val frames = state.encodeFrames()
        val moveCount = BoardClimbParser
            .estimateMoveCount(BoardClimbParser.parseFrames(frames))
            .toLong()
        val draft = LocalClimbDraft(
            uuid = uuid,
            name = state.name,
            description = state.description,
            framesText = frames,
            framesHash = FramesHash.of(frames, layoutId),
            createdAt = nowIso(),
            createdByPubkey = pubkey,
            moveCount = moveCount,
            setterUsername = resolveOwnSetterUsername(pubkey),
        )
        val angle = state.angle ?: error("angle required")
        val bounds = computeBounds(state)
        boardRepository.insertLocalDraft(
            draft = draft,
            layoutId = layoutId,
            angle = angle.toLong(),
            setterGradeId = state.setterGradeId,
            bounds = bounds,
        )
    }

    /**
     * Resolve placement coords for the editor's selected holds and reduce
     * to a [ClimbBounds]. Skipped (returns null) when no placement records
     * are loaded yet — happens only in tests / cold-boot races. NULL
     * edge_* on the persisted row matches pre-Plan-2 behaviour, so callers
     * cope.
     */
    /**
     * Resolve the user's Kind-0 display_name (cached if present, else
     * fetched once). Returns null when no profile exists — Browse falls
     * back to `npub:<short>`. Failures are swallowed: a missing profile
     * is the empty case, not an error.
     */
    private suspend fun resolveOwnSetterUsername(pubkey: String?): String? {
        if (pubkey.isNullOrBlank()) return null
        val profile = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
        return profile?.displayName?.takeIf { it.isNotBlank() }
    }

    private fun computeBounds(state: ClimbEditorState): ClimbBounds? {
        val ids = state.selectedHolds.keys
        if (ids.isEmpty()) return null
        val all = runCatching { boardRepository.getAllPlacements() }.getOrNull().orEmpty()
        if (all.isEmpty()) return null
        val coords = all.asSequence()
            .filter { it.placementId.toInt() in ids }
            .map { it.x.toInt() to it.y.toInt() }
            .toList()
        return ClimbBounds.fromCoords(coords)
    }

    /**
     * Look up an existing climb in the local DB by canonical frames_hash.
     * If a climb on the same layout already shares this hash, the editor
     * surfaces a warning before the user publishes a duplicate.
     */
    suspend fun findDuplicate(state: ClimbEditorState): com.cruxcoach.data.repository.CommunityClimbRow? {
        val layoutId = userPreferences.boardLayoutId.first().toLong()
        val frames = state.encodeFrames()
        val hash = FramesHash.of(frames, layoutId)
        return boardRepository.findClimbByFramesHash(hash, layoutId)
    }

    /**
     * Save a draft + publish to Nostr (mandatory). Best-effort Kilter
     * push happens inline; the returned [PublishOutcome] tells the
     * editor whether to nudge the user toward connecting their Kilter
     * account when the climb went out only over Nostr.
     *
     * When [existingUuid] is non-null, the row is re-saved in place and
     * the same uuid is re-used for the Nostr d-tag. The relay treats this
     * as a replaceable update of the original (Kind 30078, NIP-78).
     */
    suspend fun saveAndPublish(
        state: ClimbEditorState,
        sizeLabel: String,
        existingUuid: String? = null,
        autoNote: CommunityClimbPublisher.AutoNoteSpec? = null,
    ): PublishOutcome {
        val isEdit = existingUuid != null
        val uuid = if (isEdit) {
            updateDraft(existingUuid!!, state)
            existingUuid
        } else {
            saveDraft(state)
        }
        val layoutId = userPreferences.boardLayoutId.first().toLong()
        val result = publisher.publish(
            uuid = uuid,
            layoutId = layoutId,
            state = state,
            sizeLabel = sizeLabel,
            isEdit = isEdit,
            autoNote = autoNote,
        )
        return PublishOutcome(
            uuid = uuid,
            nudgeToConnectKilter = result.nudgeToConnectKilter,
            kilterOutcome = result.kilterOutcome,
        )
    }

    data class PublishOutcome(
        val uuid: String,
        val nudgeToConnectKilter: Boolean,
        /** Editor-visible Kilter publish outcome. Null = Kilter wasn't
         *  attempted (publish disabled, no token, orchestration threw).
         *  See [CommunityClimbPublisher.Result.kilterOutcome] for the
         *  semantics. */
        val kilterOutcome: com.cruxcoach.android.data.kilter.KilterClimbPublisher.Outcome? = null,
    )

    private fun nowIso(): String {
        // 2026-04-27T17:00:00Z — keep it timezone-agnostic so we don't
        // collide with `BoardSession.startedAt` which may use the same
        // helper. Pure platform call — millis precision is fine for human
        // display, and the Nostr-event side carries its own epoch field.
        return java.time.Instant.now().toString()
    }
}
