package com.cruxcoach.android.boardcell

import org.junit.Assert.*
import org.junit.Test

/**
 * The way back from one edit, for the device that made it.
 *
 * The property that matters is not "an inverse exists" but "applying the
 * inverse lands on the state the edit started from" — on a list several people
 * are writing to, an undo that puts a climb back at the *end* instead of where
 * it was is a second edit dressed up as a correction. Every case here asserts
 * the round trip rather than the shape of the operations.
 */
class BoardPlaylistUndoTest {

    @Test
    fun `committed mutation offers undo`() {
        val ack = BoardCommandAck(
            commandId = "command",
            status = BoardCommandStatus.COMMITTED,
            cellId = BoardCellId("cell"),
            epoch = 1,
            controllerTerm = 1,
        )

        assertTrue(ack.changedPlaylist)
    }

    @Test
    fun `committed idempotent no-op does not offer undo`() {
        val ack = BoardCommandAck(
            commandId = "command",
            status = BoardCommandStatus.COMMITTED,
            cellId = BoardCellId("cell"),
            epoch = 1,
            controllerTerm = 1,
            detail = BoardCommandAck.DETAIL_ALREADY_IN_REQUESTED_STATE,
        )

        assertFalse(ack.changedPlaylist)
    }

    private fun entry(id: String, climb: String = "climb-$id", angle: Int = 40, rest: Int = 0) =
        BoardPlaylistEntry(id, climb, angle, rest)

    /** [selected] is the cursor; [confirmed] is a board write that landed. */
    private fun playlist(
        vararg entries: BoardPlaylistEntry,
        selected: String? = null,
        confirmed: String? = null,
    ) = BoardPlaylistPolicy.normalize(BoardPlaylistState(
        sessionId = 7, entries = entries.toList(),
        selectedEntryId = selected ?: entries.firstOrNull()?.entryId,
        currentEntryId = confirmed))

    private fun roundTrip(before: BoardPlaylistState, vararg ops: BoardPlaylistOp) {
        val after = BoardPlaylistPolicy.apply(before, ops.toList())
        val inverse = BoardPlaylistUndo.inverseOf(before, ops.toList())
        assertTrue("expected an inverse for ${ops.toList()}", inverse.isNotEmpty())
        assertEquals(before, BoardPlaylistPolicy.apply(after, inverse))
    }

    @Test fun `undoing an add removes exactly the occurrence it added`() {
        val before = playlist(entry("e1"), entry("e2"))

        roundTrip(before, BoardPlaylistOp.Add("e3", "new", 40))
    }

    @Test fun `undoing a remove puts the climb back where it was, not at the end`() {
        val before = playlist(entry("e1"), entry("e2", rest = 120), entry("e3"))

        roundTrip(before, BoardPlaylistOp.Remove("e2"))
    }

    @Test fun `undoing a remove of the first entry puts it back at the head`() {
        val before = playlist(entry("e1"), entry("e2"))

        roundTrip(before, BoardPlaylistOp.Remove("e1"))
    }

    /**
     * Removing the current entry moves the group on. Bringing the climb back
     * without bringing the selection back would leave everybody looking at the
     * wrong problem on a list that looks right.
     */
    @Test fun `undoing a remove of the current entry restores the selection too`() {
        val before = playlist(entry("e1"), entry("e2"), entry("e3"), selected = "e2")

        roundTrip(before, BoardPlaylistOp.Remove("e2"))

        val after = BoardPlaylistPolicy.apply(before, listOf(BoardPlaylistOp.Remove("e2")))
        assertEquals("e3", after.selectedEntryId)
    }

    @Test fun `undoing a move puts the entry back between the same neighbours`() {
        val before = playlist(entry("e1"), entry("e2"), entry("e3"), entry("e4"))

        roundTrip(before, BoardPlaylistOp.Move("e3", BoardPlaylistAnchor.Head))
        roundTrip(before, BoardPlaylistOp.Move("e1", BoardPlaylistAnchor.Tail))
        roundTrip(before, BoardPlaylistOp.Move("e2", BoardPlaylistAnchor.After("e3")))
    }

    @Test fun `undoing a selection change returns the group to what it was on`() {
        val before = playlist(entry("e1"), entry("e2"), entry("e3"), selected = "e1")

        roundTrip(before, BoardPlaylistOp.SetSelection("e3"))
    }

    @Test fun `undoing a rest change restores the old plan`() {
        val before = playlist(entry("e1", rest = 60), entry("e2"))

        roundTrip(before, BoardPlaylistOp.SetRest("e1", 240))
    }

    /**
     * A batch commits together, so it has to come back together — and in the
     * reverse order, because each inverse is composed against the state its
     * own operation saw.
     */
    @Test fun `undoing a batch unwinds the whole batch`() {
        val before = playlist(entry("e1"), entry("e2"), entry("e3"), selected = "e1")

        roundTrip(
            before,
            BoardPlaylistOp.Remove("e2"),
            BoardPlaylistOp.Add("e9", "replacement", 40, 0, BoardPlaylistAnchor.After("e1")),
            BoardPlaylistOp.SetSelection("e9"),
        )
    }

    @Test fun `dropping the queued repeats of a climb comes back whole`() {
        val before = playlist(
            entry("e1", climb = "project", rest = 60),
            entry("e2", climb = "project", rest = 60),
            entry("e3", climb = "project", rest = 180),
            entry("e4", climb = "other"),
        )

        roundTrip(before, *BoardPlaylistOps.dropRepeatsAfter(before, 0).toTypedArray())
    }

    // ===== Where there is deliberately no way back =====

    @Test fun `a clear offers no local undo — it has a canonical one`() {
        val before = playlist(entry("e1"))

        assertTrue(BoardPlaylistUndo.inverseOf(before,
            listOf(BoardPlaylistOp.Clear(1))).isEmpty())
        assertTrue(BoardPlaylistUndo.inverseOf(before,
            listOf(BoardPlaylistOp.RestoreClear(1))).isEmpty())
    }

    @Test fun `a rest that is already running is not something to unskip`() {
        val before = playlist(entry("e1"), entry("e2"))

        assertTrue(BoardPlaylistUndo.inverseOf(before,
            listOf(BoardPlaylistOp.StartRest("e2", 60, 1, 1L, 61_000L))).isEmpty())
        assertTrue(BoardPlaylistUndo.inverseOf(before,
            listOf(BoardPlaylistOp.EndRest)).isEmpty())
    }

    @Test fun `selection while a rest is running offers no partial undo`() {
        val before = playlist(entry("e1"), entry("e2"), selected = "e2").copy(
            activeRest = BoardPlaylistRest(
                totalSeconds = 60,
                generation = 1,
                nextEntryId = "e2",
                startedAtEpochMs = BoardPlaylistInstant.MIN_EPOCH_MS,
                endsAtEpochMs = BoardPlaylistInstant.MIN_EPOCH_MS + 60_000L,
            ),
        )

        assertTrue(BoardPlaylistUndo.inverseOf(
            before,
            listOf(BoardPlaylistOp.SetSelection("e1")),
        ).isEmpty())
    }

    @Test fun `advance that starts a rest offers no misleading selection undo`() {
        val before = playlist(entry("e1", rest = 60), entry("e2"), selected = "e1")

        assertTrue(BoardPlaylistUndo.inverseOf(before, listOf(
            BoardPlaylistOp.SetSelection("e2"),
            BoardPlaylistOp.StartRest(
                "e2",
                60,
                1,
                BoardPlaylistInstant.MIN_EPOCH_MS,
                BoardPlaylistInstant.MIN_EPOCH_MS + 60_000L,
            ),
        )).isEmpty())
    }

    @Test fun `the physical send state is not a list edit and has no undo`() {
        val before = playlist(entry("e1"))

        assertTrue(BoardPlaylistUndo.inverseOf(before, listOf(
            BoardPlaylistOp.SetPendingProjection(
                BoardPlaylistPendingProjection("e1", "climb-e1", 40,
                    BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)),
        )).isEmpty())
    }

    /**
     * An operation that lost its race changed nothing, so there is nothing to
     * take back — and the undo must not invent a change of its own.
     */
    @Test fun `an edit that no longer applies offers nothing`() {
        val before = playlist(entry("e1"))

        assertTrue(BoardPlaylistUndo.inverseOf(before,
            listOf(BoardPlaylistOp.Remove("gone"))).isEmpty())
        assertTrue(BoardPlaylistUndo.inverseOf(before,
            listOf(BoardPlaylistOp.Move("gone", BoardPlaylistAnchor.Head))).isEmpty())
        assertTrue(BoardPlaylistUndo.inverseOf(before,
            listOf(BoardPlaylistOp.SetSelection("e1"))).isEmpty())
    }

    @Test fun `an undo is itself idempotent when somebody else got there first`() {
        val before = playlist(entry("e1"), entry("e2"))
        val ops = listOf(BoardPlaylistOp.Remove("e2"))
        val inverse = BoardPlaylistUndo.inverseOf(before, ops)
        val after = BoardPlaylistPolicy.apply(before, ops)

        val once = BoardPlaylistPolicy.apply(after, inverse)
        val twice = BoardPlaylistPolicy.apply(once, inverse)

        assertEquals(once, twice)
    }
}
