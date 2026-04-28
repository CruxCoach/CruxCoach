package com.cruxcoach.android.community

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.LocalClimbDraft
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

        val uuid = UUID.randomUUID().toString().replace("-", "").uppercase()
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
        )
        val angle = state.angle ?: error("angle required")
        boardRepository.insertLocalDraft(
            draft = draft,
            layoutId = layoutId,
            angle = angle.toLong(),
            setterGradeId = state.setterGradeId,
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
        )
        val angle = state.angle ?: error("angle required")
        boardRepository.insertLocalDraft(
            draft = draft,
            layoutId = layoutId,
            angle = angle.toLong(),
            setterGradeId = state.setterGradeId,
        )
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
    ): PublishOutcome {
        val uuid = if (existingUuid != null) {
            updateDraft(existingUuid, state)
            existingUuid
        } else {
            saveDraft(state)
        }
        val layoutId = userPreferences.boardLayoutId.first().toLong()
        val result = publisher.publish(uuid = uuid, layoutId = layoutId, state = state, sizeLabel = sizeLabel)
        return PublishOutcome(
            uuid = uuid,
            nudgeToConnectKilter = result.nudgeToConnectKilter,
        )
    }

    data class PublishOutcome(
        val uuid: String,
        val nudgeToConnectKilter: Boolean,
    )

    private fun nowIso(): String {
        // 2026-04-27T17:00:00Z — keep it timezone-agnostic so we don't
        // collide with `BoardSession.startedAt` which may use the same
        // helper. Pure platform call — millis precision is fine for human
        // display, and the Nostr-event side carries its own epoch field.
        return java.time.Instant.now().toString()
    }
}
