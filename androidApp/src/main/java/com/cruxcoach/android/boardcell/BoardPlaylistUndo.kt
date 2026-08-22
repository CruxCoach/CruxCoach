package com.cruxcoach.android.boardcell

/**
 * What "undo" means for one particular edit.
 *
 * Kept separate from the operation itself so the UI can say what it is
 * offering to take back — "removed", "moved", "added" — instead of one
 * anonymous Undo that could mean any of them. Which matters more here than in
 * a single-user list: several people are editing at once, and an offer that
 * does not name what it reverses is an offer nobody can safely accept.
 */
enum class BoardPlaylistEditKind { ADD, REMOVE, MOVE, SELECT, REST, CLEAR_REPEATS }

/**
 * One edit this device made, together with the way back.
 *
 * [inverse] is composed against the state as it was *before* the edit, which
 * is what lets a remove come back to the position it was removed from rather
 * than to the end of a list that has moved on.
 */
data class BoardPlaylistEdit(
    val kind: BoardPlaylistEditKind,
    val ops: List<BoardPlaylistOp>,
    val inverse: List<BoardPlaylistOp>,
) {
    val canUndo: Boolean get() = inverse.isNotEmpty()
}

/**
 * Turns an edit into the edit that takes it back.
 *
 * Deliberately offered only to the device that made the change. A canonical,
 * everybody-sees-it undo is the right shape for exactly one operation — the
 * clear, which is the one nobody can reconstruct by hand and which is why
 * [BoardPlaylistOp.RestoreClear] exists. For the rest, an undo that any member
 * could press would be a second way to edit somebody else's change without
 * ever having seen it, on a list that the whole group is editing at once. A
 * remove you did not make is just a remove; you add the climb back.
 *
 * Every inverse is expressed in the same occurrence-addressed operations as
 * the edit itself, so it travels the ordinary command path, conflicts the
 * ordinary way, and is idempotent for the same reasons: undoing an add that
 * somebody else has already removed changes nothing rather than failing.
 */
object BoardPlaylistUndo {

    /**
     * The inverse of [ops] against [before].
     *
     * Returns an empty list when there is nothing meaningful to take back —
     * which the caller reads as "offer no undo" rather than as an error.
     * Operations are inverted in reverse order, because that is the order in
     * which their effects have to be unwound.
     */
    fun inverseOf(
        before: BoardPlaylistState,
        ops: List<BoardPlaylistOp>,
    ): List<BoardPlaylistOp> {
        if (ops.isEmpty()) return emptyList()
        // Each inverse is composed against the state its operation saw, so a
        // batch — "advance and arm the rest", "drop the repeats and re-time
        // what is left" — unwinds to exactly where it started.
        var state = BoardPlaylistPolicy.normalize(before)
        val stack = ArrayList<Pair<BoardPlaylistOp, BoardPlaylistState>>(ops.size)
        for (op in ops) {
            stack += op to state
            state = BoardPlaylistPolicy.apply(state, listOf(op))
        }
        val inverse = ArrayList<BoardPlaylistOp>(ops.size)
        for ((op, seen) in stack.asReversed()) {
            inverse += inverseOfOne(seen, op) ?: return emptyList()
        }
        return inverse
    }

    /** Null means "this operation has no honest inverse" — see [inverseOf]. */
    private fun inverseOfOne(
        before: BoardPlaylistState,
        op: BoardPlaylistOp,
    ): List<BoardPlaylistOp>? = when (op) {
        is BoardPlaylistOp.Add ->
            // Removing an occurrence that was never added is a no-op, so this
            // stays correct even if the add lost a race and never landed.
            if (before.entry(op.entryId) != null) emptyList() else listOf(
                BoardPlaylistOp.Remove(op.entryId))

        is BoardPlaylistOp.Remove -> before.entry(op.entryId)?.let { entry ->
            listOf(
                BoardPlaylistOp.Add(entry.entryId, entry.climbUuid, entry.angle,
                    entry.restAfterSeconds, anchorOf(before, entry.entryId)),
            ) + if (before.selectedEntryId == entry.entryId) {
                // The removal moved the group on. Putting the entry back
                // without putting the selection back would leave everybody
                // looking at the wrong climb with the right list.
                listOf(BoardPlaylistOp.SetSelection(entry.entryId))
            } else emptyList()
        } ?: emptyList()

        is BoardPlaylistOp.Move ->
            if (before.entry(op.entryId) == null) emptyList()
            else listOf(BoardPlaylistOp.Move(op.entryId, anchorOf(before, op.entryId)))

        is BoardPlaylistOp.SetSelection -> if (before.activeRest != null) {
            // Selection cancels the running clock. That elapsed clock cannot
            // be reconstructed honestly later, so a partial undo is withheld.
            null
        } else before.selectedEntryId
            ?.takeIf { it != op.entryId }
            ?.let { listOf(BoardPlaylistOp.SetSelection(it)) }
            .orEmpty()

        // The confirmed current is not an edit anybody made; it is the record
        // of a board write that succeeded. There is no honest inverse — undoing
        // it would claim the wall went back to something it did not. The same
        // goes for the record of a guest write this controller admitted.
        is BoardPlaylistOp.SetCurrent -> null
        is BoardPlaylistOp.RecordRelayOperation -> null

        is BoardPlaylistOp.SetRest -> before.entry(op.entryId)
            ?.let { listOf(BoardPlaylistOp.SetRest(op.entryId, it.restAfterSeconds)) }
            .orEmpty()

        // A rest is a clock, not a list edit: by the time anybody reads an
        // undo offer the pause has already been running, and "unskip" is not
        // a state the group can be put back into.
        is BoardPlaylistOp.StartRest, BoardPlaylistOp.EndRest -> null

        // The clear has its own canonical, everybody-sees-it way back.
        is BoardPlaylistOp.Clear, is BoardPlaylistOp.RestoreClear,
        is BoardPlaylistOp.ExpireClearUndo -> null

        // A statement about the physical board, not about the list.
        is BoardPlaylistOp.SetPendingProjection -> null
    }

    /** Where an occurrence sits right now, as something a move can aim at. */
    private fun anchorOf(state: BoardPlaylistState, entryId: String): BoardPlaylistAnchor {
        val index = state.indexOf(entryId)
        if (index <= 0) return BoardPlaylistAnchor.Head
        return BoardPlaylistAnchor.After(state.entries[index - 1].entryId)
    }
}
