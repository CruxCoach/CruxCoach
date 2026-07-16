package com.cruxcoach.android.community

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.data.retryingOnTransientSqliteLock
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
import com.cruxcoach.domain.board.KilterGradeMapper
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
    /** Same suspend-during-board-sync gate as CommunityClimbSubscriber.
     *  insertLocalDraft() is a SQLite write transaction (commit + fsync);
     *  while the bulk board importer holds the writer-lock for its 97-
     *  chunk INSERT-OR-IGNORE storm, an editor save throws
     *  SQLiteDatabaseLockedException (SQLITE_BUSY) within milliseconds
     *  even under WAL. Suspend (don't fail) until isSyncing flips false
     *  so the user's tap on "Veröffentlichen" reliably succeeds without
     *  asking them to understand sync timing. */
    private val boardSyncManager: com.cruxcoach.android.data.BoardSyncManager,
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
        awaitBoardSyncQuiescent()

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
        retryingOnTransientSqliteLock(
            onRetry = { attempt, max ->
                android.util.Log.w("ClimbCreator", "event=draft_db_lock_retry attempt=$attempt/$max")
            },
        ) {
            boardRepository.insertLocalDraft(
                draft = draft,
                layoutId = layoutId,
                angle = angle.toLong(),
                // Fall back to the slider's visible default when editor state
                // never carried a grade. Pre-fix the UI seeded this with a
                // LaunchedEffect, but the seed lost a race against the VM's
                // _state.update calls in loadDraft / seedFromEdit which
                // emitted setterGradeId=null after the seed, causing
                // climb_stats.difficulty_average to be persisted as NULL —
                // surfaced as "?" in the browser even though the editor
                // showed V5. Defaulting at write time closes every
                // persistence path (autosave, fork-and-edit, future tooling)
                // in one place rather than per-call-site.
                setterGradeId = state.setterGradeId ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
                bounds = bounds,
                // Persist the active board's real brand so the draft stays visible
                // in this board's drafts drawer — layout-id alone can't tell the
                // Aurora-family boards apart from Kilter.
                boardBrand = state.boardBrand,
            )
        }
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
        awaitBoardSyncQuiescent()
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
        retryingOnTransientSqliteLock(
            onRetry = { attempt, max ->
                android.util.Log.w("ClimbCreator", "event=draft_db_lock_retry attempt=$attempt/$max")
            },
        ) {
            boardRepository.insertLocalDraft(
                draft = draft,
                layoutId = layoutId,
                angle = angle.toLong(),
                // See saveDraft for why the default lives here, not in the UI.
                setterGradeId = state.setterGradeId ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
                bounds = bounds,
                boardBrand = state.boardBrand,
            )
        }
    }

    /**
     * Resolve placement coords for the editor's selected holds and reduce
     * to a [ClimbBounds]. Skipped (returns null) when no placement records
     * are loaded yet — happens only in tests / cold-boot races. NULL
     * edge_* on the persisted row matches pre-Plan-2 behaviour, so callers
     * cope.
     */
    /**
     * Resolve the user's Kind-0 display_name from the LOCAL cache only —
     * never hits relays. Returns null on miss; the publish path
     * (CommunityClimbPublisher / upsertCommunityClimb) re-resolves
     * setter_username when the row goes out, so the local draft just
     * needs a sensible placeholder until then.
     *
     * Pre-fix this called nostrProfileManager.getProfile(pubkey) which
     * falls back to fetchProfileFromRelays on cache miss with a 10 s
     * timeout — saveDraft would then block for 10 s on every first
     * save when the user hadn't published a Kind-0 profile, the
     * "draft saved" snackbar fired only after the timeout, and the
     * drafts-list refresh in the same coroutine looked stuck.
     */
    private fun resolveOwnSetterUsername(pubkey: String?): String? {
        if (pubkey.isNullOrBlank()) return null
        val profile = runCatching { nostrProfileManager.getProfileFromCache(pubkey) }.getOrNull()
        return profile?.displayName?.takeIf { it.isNotBlank() }
    }

    /** Suspend until the bulk board importer releases the writer-lock.
     *  Without this the editor's `insertLocalDraft` transaction races
     *  against ~97 chunk INSERT-OR-IGNORE batches and throws SQLITE_BUSY
     *  within a few ms — surfacing as a "Publish failed" snackbar that
     *  the user has no way to reason about. Fast-paths to no-op when
     *  no sync is in flight (the common case). */
    private suspend fun awaitBoardSyncQuiescent() {
        if (!boardSyncManager.state.value.isSyncing) return
        android.util.Log.d("ClimbCreator", "awaiting board-sync to finish before draft write")
        boardSyncManager.state.first { !it.isSyncing }
        android.util.Log.d("ClimbCreator", "board-sync done, proceeding with draft write")
    }

    private fun computeBounds(state: ClimbEditorState): ClimbBounds? {
        // MoonBoard hold-ids aren't Aurora placement-ids (and small ids could
        // collide with low Kilter placement-ids), so resolving coordinates
        // here would produce a bogus bounds. Skip — null is handled
        // gracefully everywhere a bounds is consumed.
        if (state.boardBrand == com.cruxcoach.domain.board.BoardBrand.MOONBOARD.wireValue) return null
        val ids = state.selectedHolds.keys
        if (ids.isEmpty()) return null
        // Resolve coordinates from THIS board's placement table — placement-ids
        // overlap across boards (layout_id=1 alone is five brands), so the
        // default Kilter scope would derive edge_* from Kilter coordinates for
        // an Aurora-family draft and persist a physically wrong bbox.
        val all = boardRepository.getAllPlacements(state.boardBrand)
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
        return boardRepository.findClimbByFramesHash(hash, layoutId, state.boardBrand)
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
        // The editor's active brand — set in ClimbEditorViewModel.loadBoardData
        // from userPreferences.boardBrand (NOT fromLayoutId, which can't tell
        // Aurora boards from Kilter). Threaded into publish() so the climb
        // lands on the right back-compat L-namespace + board_brand tag.
        val boardBrand = com.cruxcoach.domain.board.BoardBrand.fromWire(state.boardBrand)
        val result = publisher.publish(
            uuid = uuid,
            layoutId = layoutId,
            boardBrand = boardBrand,
            state = state,
            sizeLabel = sizeLabel,
            isEdit = isEdit,
            autoNote = autoNote,
        )
        return PublishOutcome(
            uuid = uuid,
            nudgeToConnectKilter = result.nudgeToConnectKilter,
            kilterOutcome = result.kilterOutcome,
            autoNotePublished = result.autoNotePublished,
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
        /** Optional Kind-1 auto-note outcome. See
         *  [CommunityClimbPublisher.Result.autoNotePublished]. */
        val autoNotePublished: Boolean? = null,
    )

    private fun nowIso(): String {
        // 2026-04-27T17:00:00Z — keep it timezone-agnostic so we don't
        // collide with `BoardSession.startedAt` which may use the same
        // helper. Pure platform call — millis precision is fine for human
        // display, and the Nostr-event side carries its own epoch field.
        return java.time.Instant.now().toString()
    }
}
