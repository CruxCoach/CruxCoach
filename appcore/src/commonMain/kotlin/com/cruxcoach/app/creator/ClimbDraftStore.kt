package com.cruxcoach.app.creator

import com.cruxcoach.app.community.CommunityClimbBounds
import com.cruxcoach.app.community.CommunityEventTime
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.util.toHex
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.data.repository.LocalClimbDraft
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.community.ClimbBounds
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.FramesHash
import com.cruxcoach.domain.community.encodeFrames

/**
 * Storage half of the climb editor — the port of Android's
 * `ClimbCreatorRepository` minus its Kilter leg (that needs the Kilter account
 * work another change owns) and minus its Kind-0 display-name lookup (iOS has
 * no profile cache yet, so `setter_username` stays NULL and browse falls back to
 * the `npub:` stub exactly as it does for a user without a profile on Android).
 *
 * Saving is local and synchronous; publishing lives in
 * [com.cruxcoach.app.community.CommunityPublisher].
 */
class ClimbDraftStore(
    private val boardRepository: BoardRepository,
    private val hashing: Hashing,
    private val clock: WallClock,
    private val pubkeyProvider: () -> String?,
) {
    /**
     * Persists [state] as a new local draft and returns its uuid, or null when
     * the draft has no angle — every persistence path on Android `require`s one,
     * and the editor blocks the button on the matching validation issue.
     */
    fun saveDraft(state: ClimbEditorState, layoutId: Long): String? {
        if (state.angle == null) return null
        val uuid = newClimbUuid()
        writeDraft(uuid, state, layoutId)
        return uuid
    }

    /** Re-saves an already-loaded draft in place (same uuid; the schema upserts). */
    fun updateDraft(uuid: String, state: ClimbEditorState, layoutId: Long): Boolean {
        if (state.angle == null) return false
        writeDraft(uuid, state, layoutId)
        return true
    }

    fun deleteDraft(uuid: String) = boardRepository.deleteLocalClimb(uuid)

    /** Active signer, or null while the identity is still initialising. */
    fun ownPubkey(): String? = pubkeyProvider()

    fun drafts(boardBrand: String): List<CommunityClimbRow> =
        boardRepository.getDraftClimbs(pubkeyProvider(), boardBrand)

    /**
     * An existing climb on the same layout and board with the same canonical
     * frames hash, so the editor can warn before a duplicate is published.
     */
    fun findDuplicate(state: ClimbEditorState, layoutId: Long): CommunityClimbRow? {
        val hash = FramesHash.of(state.encodeFrames(), layoutId)
        return boardRepository.findClimbByFramesHash(hash, layoutId, state.boardBrand)
    }

    /**
     * Bounding box of the selected holds in this board's placement coordinates.
     *
     * Null for MoonBoard (its hold ids are not Aurora placement ids and low ids
     * would collide with real Kilter placements) and whenever the placement table
     * is not loaded — a NULL `edge_*` row means "fits every size" downstream,
     * which is the pre-existing behaviour for hold-less rows.
     */
    fun computeBounds(state: ClimbEditorState): ClimbBounds? =
        CommunityClimbBounds.of(boardRepository, state)

    /**
     * 32 lowercase hex characters — the same canonical shape Android writes
     * (`UUID.randomUUID().toString().replace("-","").lowercase()`), which the
     * ingest path's route-safe uuid check also accepts.
     */
    fun newClimbUuid(): String = hashing.randomBytes(16).toHex()

    private fun writeDraft(uuid: String, state: ClimbEditorState, layoutId: Long) {
        val angle = state.angle ?: return
        val frames = state.encodeFrames()
        val moveCount = BoardClimbParser
            .estimateMoveCount(BoardClimbParser.parseFrames(frames))
            .toLong()
        boardRepository.insertLocalDraft(
            draft = LocalClimbDraft(
                uuid = uuid,
                name = state.name,
                description = state.description,
                framesText = frames,
                framesHash = FramesHash.of(frames, layoutId),
                createdAt = CommunityEventTime.isoFromEpochSeconds(clock.epochSeconds()),
                createdByPubkey = pubkeyProvider(),
                moveCount = moveCount,
                setterUsername = null,
            ),
            layoutId = layoutId,
            angle = angle.toLong(),
            // Defaulting at write time closes every persistence path at once, the
            // way Android does it after the null-grade rows the UI-side seed let through.
            setterGradeId = state.setterGradeId ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
            bounds = computeBounds(state),
            // The active board's real brand: layout ids alone cannot tell the
            // Aurora-family boards apart from Kilter.
            boardBrand = state.boardBrand,
        )
    }
}
