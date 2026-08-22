package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant, as a test suite of its own:
 *
 *     a new current  =>  the physical transport for exactly that occurrence
 *                        succeeded
 *
 * It reads as one sentence and it has one enemy: a field that also has to
 * serve as the group's cursor. Everything below is a change to the *shape* of
 * the list — adding, removing, stepping, clearing, restoring — and none of
 * them may leave a confirmed current behind, because none of them writes to a
 * wall.
 */
class BoardPlaylistCurrentContractTest {

    /** 2026-08-17T12:00:00Z — inside the epoch range a window must fall in. */
    private val now = 1_786_968_000_000L

    private fun entry(id: String, climb: String = "climb-$id") =
        BoardPlaylistEntry(entryId = id, climbUuid = climb, angle = 40)

    private fun listOfThree() = BoardPlaylistPolicy.apply(
        BoardPlaylistState(sessionId = 7),
        listOf(
            BoardPlaylistOp.Add("e1", "climb-a", 40),
            BoardPlaylistOp.Add("e2", "climb-b", 40),
            BoardPlaylistOp.Add("e3", "climb-c", 40),
        ),
    )

    @Test
    fun `the first climb added is selected and is not on the board`() {
        val state = BoardPlaylistPolicy.apply(
            BoardPlaylistState(sessionId = 7),
            listOf(BoardPlaylistOp.Add("e1", "climb-a", 40)),
        )

        assertEquals("e1", state.selectedEntryId)
        assertNull("adding a climb has never written to a board", state.currentEntryId)
    }

    @Test
    fun `stepping through the list never claims the board`() {
        var state = listOfThree()

        state = BoardPlaylistPolicy.apply(state, BoardPlaylistOps.next(state))
        assertEquals("e2", state.selectedEntryId)
        assertNull(state.currentEntryId)

        state = BoardPlaylistPolicy.apply(state, BoardPlaylistOps.previous(state))
        assertEquals("e1", state.selectedEntryId)
        assertNull(state.currentEntryId)
    }

    @Test
    fun `pointing at an entry never claims the board`() {
        val state = BoardPlaylistPolicy.apply(
            listOfThree(), listOf(BoardPlaylistOp.SetSelection("e3")),
        )

        assertEquals("e3", state.selectedEntryId)
        assertNull(state.currentEntryId)
    }

    /**
     * Pointing the group at an occurrence is a selection, from anybody.
     *
     * The ViewModel's `select()` emitted `SetCurrent` — the operation that
     * means "the board is confirmed to be showing this". On the controller
     * that moved the confirmed current with no projection behind it; from a
     * member it was a command the controller-only policy refuses outright, so
     * the member could not select at all.
     */
    @Test
    fun `a member may point the group at an occurrence`() {
        val before = listOfThree()

        val outcome = BoardPlaylistPolicy.resolve(
            current = before,
            senderId = "member-npub",
            command = BoardPlaylistCommand(
                commandId = "command-select-01",
                basePlaylistRevision = 0,
                baseClearGeneration = 0,
                ops = listOf(BoardPlaylistOp.SetSelection("e3")),
            ),
            nowEpochMs = now,
            senderIsController = false,
        )

        assertFalse("a member may select", outcome is BoardPlaylistPolicy.Outcome.Reject)
        val after = BoardPlaylistPolicy.apply(before, listOf(BoardPlaylistOp.SetSelection("e3")))
        assertEquals("e3", after.selectedEntryId)
        assertNull("and it claims nothing about the wall", after.currentEntryId)
    }

    /** The confirmed current stays the controller's to state. */
    @Test
    fun `a member may not claim the board is showing an occurrence`() {
        val outcome = BoardPlaylistPolicy.resolve(
            current = listOfThree(),
            senderId = "member-npub",
            command = BoardPlaylistCommand(
                commandId = "command-claim-01",
                basePlaylistRevision = 0,
                baseClearGeneration = 0,
                ops = listOf(BoardPlaylistOp.SetCurrent("e3")),
            ),
            nowEpochMs = now,
            senderIsController = false,
        )

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Reject)
    }

    /** The one operation that may set it, and only after the wall answered. */
    @Test
    fun `only a landed write confirms an occurrence`() {
        val before = listOfThree()

        val landed = BoardPlaylistPolicy.apply(before, BoardPlaylistOps.confirmLit(before, "e2"))

        assertEquals("e2", landed.currentEntryId)
        assertEquals("and the group is taken there with it", "e2", landed.selectedEntryId)
    }

    @Test
    fun `a failed write confirms nothing`() {
        val before = listOfThree()

        val failed = BoardPlaylistPolicy.apply(
            before, BoardPlaylistOps.recordLightFailure(before, "e2"),
        )

        assertNull(failed.currentEntryId)
        assertEquals("e1", failed.selectedEntryId)
        assertEquals("e2", failed.pendingProjection?.entryId)
    }

    // ── Changing the shape of the list ────────────────────────────────────

    @Test
    fun `removing the confirmed occurrence leaves nothing confirmed`() {
        val lit = BoardPlaylistPolicy.apply(
            listOfThree(), BoardPlaylistOps.confirmLit(listOfThree(), "e2"),
        )

        val after = BoardPlaylistPolicy.apply(lit, listOf(BoardPlaylistOp.Remove("e2")))

        assertNull("its neighbour was never sent", after.currentEntryId)
        assertEquals("e3", after.selectedEntryId)
    }

    @Test
    fun `removing some other occurrence leaves the confirmed one alone`() {
        val lit = BoardPlaylistPolicy.apply(
            listOfThree(), BoardPlaylistOps.confirmLit(listOfThree(), "e2"),
        )

        val after = BoardPlaylistPolicy.apply(lit, listOf(BoardPlaylistOp.Remove("e3")))

        assertEquals("e2", after.currentEntryId)
    }

    @Test
    fun `clearing the list confirms nothing`() {
        val lit = BoardPlaylistPolicy.apply(
            listOfThree(), BoardPlaylistOps.confirmLit(listOfThree(), "e2"),
        )

        val cleared = BoardPlaylistPolicy.apply(
            lit, listOf(BoardPlaylistOp.Clear(1, now, now + BoardPlaylistPolicy.RESTORE_WINDOW_MS)),
        )

        assertNull(cleared.currentEntryId)
        assertNull(cleared.selectedEntryId)
    }

    /** Taking a clear back re-adds occurrences; it does not project one. */
    @Test
    fun `restoring a clear brings back the cursor and not the board`() {
        val lit = BoardPlaylistPolicy.apply(
            listOfThree(), BoardPlaylistOps.confirmLit(listOfThree(), "e2"),
        )
        val cleared = BoardPlaylistPolicy.apply(
            lit, listOf(BoardPlaylistOp.Clear(1, now, now + BoardPlaylistPolicy.RESTORE_WINDOW_MS)),
        )

        val restored = BoardPlaylistPolicy.apply(cleared, listOf(BoardPlaylistOp.RestoreClear(1)))

        assertEquals(listOf("e1", "e2", "e3"), restored.entries.map { it.entryId })
        assertEquals("e2", restored.selectedEntryId)
        assertNull("nothing was written to bring the list back", restored.currentEntryId)
    }

    // ── Two occurrences of the same route ────────────────────────────────
    //
    // Everything below is a question the content cannot answer: "which one".

    /** A pending failure belongs to an occurrence, not to a route. */
    @Test
    fun `the success of one occurrence does not clear another's failure`() {
        val twice = BoardPlaylistPolicy.apply(
            BoardPlaylistState(sessionId = 7),
            listOf(
                BoardPlaylistOp.Add("e1", "climb-x", 40),
                BoardPlaylistOp.Add("e2", "climb-x", 40),
            ),
        )
        val failedFirst = BoardPlaylistPolicy.apply(
            twice, BoardPlaylistOps.recordLightFailure(twice, "e1"),
        )
        assertEquals("e1", failedFirst.pendingProjection?.entryId)

        // The second occurrence of the identical route lands.
        val afterSecond = BoardCellReplica.reduce(
            BoardCellSnapshot(
                BoardCellId("cell"), PhysicalBoardId("board"), epoch = 1, sequence = 1,
                controllerId = "controller", lineageId = "lineage",
                members = setOf("controller"), playlist = failedFirst,
            ).withComputedHash(),
            BoardCellEvent.ProjectCommitted(
                BoardProjection("climb-x", 40), "command-0001", entryId = "e2",
            ),
            2,
        )

        assertEquals(
            "the first occurrence's failure is still its own",
            "e1", afterSecond.playlist.pendingProjection?.entryId,
        )
    }

    /** And the occurrence that did land is the one that gets confirmed. */
    @Test
    fun `confirming names the occurrence, not the route`() {
        val twice = BoardPlaylistPolicy.apply(
            BoardPlaylistState(sessionId = 7),
            listOf(
                BoardPlaylistOp.Add("e1", "climb-x", 40),
                BoardPlaylistOp.Add("e2", "climb-x", 40),
            ),
        )

        val lit = BoardPlaylistPolicy.apply(twice, BoardPlaylistOps.confirmLit(twice, "e2"))

        assertEquals("e2", lit.currentEntryId)
        assertEquals("e2", lit.selectedEntryId)
    }

    /** A commit that belongs to no occurrence clears nobody's failure. */
    @Test
    fun `an external write does not clear a pending failure`() {
        val one = BoardPlaylistPolicy.apply(
            BoardPlaylistState(sessionId = 7), listOf(BoardPlaylistOp.Add("e1", "climb-x", 40)),
        )
        val failed = BoardPlaylistPolicy.apply(one, BoardPlaylistOps.recordLightFailure(one, "e1"))

        val after = BoardCellReplica.reduce(
            BoardCellSnapshot(
                BoardCellId("cell"), PhysicalBoardId("board"), epoch = 1, sequence = 1,
                controllerId = "controller", lineageId = "lineage",
                members = setOf("controller"), playlist = failed,
            ).withComputedHash(),
            BoardCellEvent.ProjectCommitted(BoardProjection("climb-x", 40), "command-0002"),
            2,
        )

        assertEquals("e1", after.playlist.pendingProjection?.entryId)
    }

    /** Normalisation is where the invented current used to come from. */
    @Test
    fun `normalisation gives the cursor a fallback and the board none`() {
        val raw = BoardPlaylistState(
            sessionId = 7,
            entries = listOf(entry("e1"), entry("e2")),
            selectedEntryId = null,
            currentEntryId = null,
        )

        val state = BoardPlaylistPolicy.normalize(raw)

        assertEquals("e1", state.selectedEntryId)
        assertNull(state.currentEntryId)
    }

    @Test
    fun `normalisation drops a confirmed current whose occurrence is gone`() {
        val raw = BoardPlaylistState(
            sessionId = 7,
            entries = listOf(entry("e1")),
            selectedEntryId = "e1",
            currentEntryId = "gone",
        )

        assertNull(BoardPlaylistPolicy.normalize(raw).currentEntryId)
    }

    /**
     * A snapshot from a build that had one field for both carries its cursor
     * in `currentEntryId`. Adopting it is a pure function of the state, so
     * every replica derives the identical selection from the identical bytes.
     */
    @Test
    fun `a pre-split snapshot keeps its cursor`() {
        val legacy = BoardPlaylistState(
            sessionId = 7,
            entries = listOf(entry("e1"), entry("e2")),
            selectedEntryId = null,
            currentEntryId = "e2",
        )

        val state = BoardPlaylistPolicy.normalize(legacy)

        assertEquals("e2", state.selectedEntryId)
        assertEquals("what it claimed about the board is left as it was", "e2", state.currentEntryId)
    }
}
