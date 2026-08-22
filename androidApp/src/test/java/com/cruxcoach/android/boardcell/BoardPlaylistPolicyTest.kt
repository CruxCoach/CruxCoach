package com.cruxcoach.android.boardcell

import org.junit.Assert.*
import org.junit.Test

/**
 * The shared playlist's rules as one pure function: what an operation means,
 * what two people doing incompatible things at once resolves to, and why every
 * operation can be replayed without changing the answer.
 */
class BoardPlaylistPolicyTest {

    /** 2026-08-17T12:00:00Z. */
    private val now = 1_786_968_000_000L

    private fun entry(id: String, climb: String = "climb-$id", angle: Int = 40, rest: Int = 0) =
        BoardPlaylistEntry(id, climb, angle, rest)

    private fun playlist(vararg entries: BoardPlaylistEntry, current: String? = null) =
        BoardPlaylistPolicy.normalize(BoardPlaylistState(
            sessionId = 7, entries = entries.toList(),
            currentEntryId = current ?: entries.firstOrNull()?.entryId))

    private fun command(
        vararg ops: BoardPlaylistOp,
        commandId: String = "command-0001",
        revision: Long = 0,
        clearGeneration: Long = 0,
    ) = BoardPlaylistCommand(commandId, revision, clearGeneration, ops.toList())

    private fun resolve(
        state: BoardPlaylistState,
        command: BoardPlaylistCommand,
        sender: String = "member-a",
        isController: Boolean = false,
    ) = BoardPlaylistPolicy.resolve(state, sender, command, now, isController)

    private fun commit(
        state: BoardPlaylistState,
        vararg ops: BoardPlaylistOp,
        sender: String = "member-a",
        isController: Boolean = false,
    ): BoardPlaylistState {
        val outcome = resolve(state, command(*ops, clearGeneration = state.clearGeneration),
            sender, isController)
        assertTrue("expected a commit, got $outcome",
            outcome is BoardPlaylistPolicy.Outcome.Commit)
        return (outcome as BoardPlaylistPolicy.Outcome.Commit).playlist
    }

    // ===== Occurrences are addressable =====

    @Test fun `the same climb may occur repeatedly and each occurrence keeps its own id`() {
        val state = commit(playlist(),
            BoardPlaylistOp.Add("e1", "zombie-hands", 40),
            BoardPlaylistOp.Add("e2", "zombie-hands", 40),
            BoardPlaylistOp.Add("e3", "zombie-hands", 40))

        assertEquals(listOf("e1", "e2", "e3"), state.entries.map { it.entryId })
        assertEquals(3, state.entries.count { it.climbUuid == "zombie-hands" })
        assertEquals("e1", state.currentEntryId)
    }

    @Test fun `removing one occurrence of a repeated climb leaves the others alone`() {
        val base = playlist(entry("e1", "z"), entry("e2", "z"), entry("e3", "z"))

        val state = commit(base, BoardPlaylistOp.Remove("e2"))

        assertEquals(listOf("e1", "e3"), state.entries.map { it.entryId })
    }

    @Test fun `two people removing the same duplicate cannot delete two entries`() {
        val base = playlist(entry("e1", "z"), entry("e2", "z"), entry("e3", "z"))

        val once = commit(base, BoardPlaylistOp.Remove("e2"))
        // The second person composed their command against the same base and
        // meant the same occurrence. Serialized after the first, it is a no-op
        // rather than taking "the second z", which is now a different climb.
        val outcome = resolve(once, command(BoardPlaylistOp.Remove("e2"),
            commandId = "command-0002", revision = 1), sender = "member-b")

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Accepted)
        assertEquals(listOf("e1", "e3"), once.entries.map { it.entryId })
    }

    @Test fun `two people removing different duplicates both take effect`() {
        val base = playlist(entry("e1", "z"), entry("e2", "z"), entry("e3", "z"))

        val first = commit(base, BoardPlaylistOp.Remove("e1"))
        val second = commit(first, BoardPlaylistOp.Remove("e3"), sender = "member-b")

        assertEquals(listOf("e2"), second.entries.map { it.entryId })
    }

    // ===== Idempotency =====

    @Test fun `adding the same entry twice adds it once`() {
        val once = commit(playlist(), BoardPlaylistOp.Add("e1", "a", 40))

        val outcome = resolve(once, command(BoardPlaylistOp.Add("e1", "a", 40),
            commandId = "command-0002", revision = 1))

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Accepted)
        assertEquals(1, once.entries.size)
    }

    @Test fun `removing something already gone is accepted rather than rejected`() {
        val outcome = resolve(playlist(entry("e1")), command(BoardPlaylistOp.Remove("gone")))

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Accepted)
    }

    @Test fun `replaying a whole batch of operations changes nothing the second time`() {
        val ops = listOf(
            BoardPlaylistOp.Add("e1", "a", 40),
            BoardPlaylistOp.Add("e2", "b", 40),
            BoardPlaylistOp.SetCurrent("e2"),
            BoardPlaylistOp.SetRest("e1", 90),
        )

        val once = BoardPlaylistPolicy.apply(playlist(), ops)
        val twice = BoardPlaylistPolicy.apply(once, ops)

        assertEquals(once, twice)
    }

    // ===== Positioning and conflict handling =====

    @Test fun `an add anchored after a removed entry lands at the end`() {
        val base = playlist(entry("e1"), entry("e2"))
        val without = commit(base, BoardPlaylistOp.Remove("e1"))

        val state = commit(without,
            BoardPlaylistOp.Add("e3", "c", 40, anchor = BoardPlaylistAnchor.After("e1")))

        assertEquals(listOf("e2", "e3"), state.entries.map { it.entryId })
    }

    @Test fun `a move whose anchor disappeared leaves the entry where it is`() {
        val base = playlist(entry("e1"), entry("e2"), entry("e3"))
        val without = commit(base, BoardPlaylistOp.Remove("e2"))

        val outcome = resolve(without, command(
            BoardPlaylistOp.Move("e3", BoardPlaylistAnchor.After("e2")),
            commandId = "command-0002", revision = 1))

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Accepted)
        assertEquals(listOf("e1", "e3"), without.entries.map { it.entryId })
    }

    @Test fun `a move keeps the current entry and the running rest on their own occurrence`() {
        val base = BoardPlaylistPolicy.apply(playlist(entry("e1"), entry("e2"), entry("e3")),
            listOf(BoardPlaylistOp.SetCurrent("e3")))
        val resting = BoardPlaylistPolicy.apply(base, listOf(
            BoardPlaylistOp.StartRest("e3", 120, 1, now, now + 120_000L)))

        val state = commit(resting, BoardPlaylistOp.Move("e3", BoardPlaylistAnchor.Head))

        assertEquals(listOf("e3", "e1", "e2"), state.entries.map { it.entryId })
        assertEquals("e3", state.currentEntryId)
        assertEquals("e3", state.activeRest?.nextEntryId)
    }

    @Test fun `removing the current entry moves the group to what took its place`() {
        val base = BoardPlaylistPolicy.apply(playlist(entry("e1"), entry("e2"), entry("e3")),
            listOf(BoardPlaylistOp.SetCurrent("e2")))

        val state = commit(base, BoardPlaylistOp.Remove("e2"))

        assertEquals("e3", state.currentEntryId)
        assertEquals(1, state.currentIndex)
    }

    @Test fun `removing the last entry leaves nothing current`() {
        val state = commit(playlist(entry("e1")), BoardPlaylistOp.Remove("e1"))

        assertTrue(state.entries.isEmpty())
        assertNull(state.currentEntryId)
    }

    @Test fun `removing the entry a rest is waiting on ends the rest`() {
        val base = BoardPlaylistPolicy.apply(playlist(entry("e1"), entry("e2")), listOf(
            BoardPlaylistOp.SetCurrent("e2"),
            BoardPlaylistOp.StartRest("e2", 120, 1, now, now + 120_000L)))

        val state = commit(base, BoardPlaylistOp.Remove("e2"))

        assertNull(state.activeRest)
    }

    @Test fun `pointing at an entry that has gone changes nothing`() {
        val outcome = resolve(playlist(entry("e1")), command(BoardPlaylistOp.SetCurrent("gone")))

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Accepted)
    }

    // ===== Advance arms the planned rest atomically =====

    @Test fun `next advances and arms the rest of the entry being left in one command`() {
        val base = playlist(entry("e1", rest = 90), entry("e2"))

        val ops = BoardPlaylistOps.next(base)
        val state = commit(base, *ops.toTypedArray())

        assertEquals("e2", state.currentEntryId)
        assertEquals(90, state.activeRest?.totalSeconds)
        assertEquals("e2", state.activeRest?.nextEntryId)
        assertEquals(now + 90_000L, state.activeRest?.endsAtEpochMs)
    }

    @Test fun `the controller stamps the rest window and a sender cannot`() {
        val base = playlist(entry("e1", rest = 60), entry("e2"))

        val state = commit(base, BoardPlaylistOp.StartRest("e2", 60,
            generation = 999, startedAtEpochMs = 1, endsAtEpochMs = 2))

        assertEquals(1L, state.activeRest?.generation)
        assertEquals(now, state.activeRest?.startedAtEpochMs)
        assertEquals(now + 60_000L, state.activeRest?.endsAtEpochMs)
    }

    @Test fun `jumping to another entry cancels a running rest`() {
        val base = BoardPlaylistPolicy.apply(playlist(entry("e1"), entry("e2")), listOf(
            BoardPlaylistOp.StartRest("e2", 60, 1, now, now + 60_000L)))

        val state = commit(base, BoardPlaylistOp.SetCurrent("e2"))

        assertNull(state.activeRest)
    }

    @Test fun `a controller whose clock is outside the believable window arms no rest`() {
        val base = playlist(entry("e1"), entry("e2"))

        val outcome = BoardPlaylistPolicy.resolve(base, "member-a",
            command(BoardPlaylistOp.StartRest("e2", 60)), nowEpochMs = 1_000L,
            senderIsController = false)

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Accepted)
    }

    // ===== Clear generation =====

    @Test fun `a command cannot claim a clear generation ahead of canonical state`() {
        val outcome = BoardPlaylistPolicy.resolve(
            BoardPlaylistState(sessionId = 7),
            "member",
            BoardPlaylistCommand("generation-ahead", 0, 1,
                listOf(BoardPlaylistOp.Add("e1", "a", 40))),
            now,
            senderIsController = false,
        )

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Reject)
    }

    @Test fun `clearing empties the playlist and bumps its generation`() {
        val base = playlist(entry("e1"), entry("e2"))

        val state = commit(base, BoardPlaylistOp.Clear())

        assertTrue(state.entries.isEmpty())
        assertNull(state.currentEntryId)
        assertEquals(1L, state.clearGeneration)
        assertEquals(7, state.sessionId)
    }

    @Test fun `an edit composed before the clear is refused rather than half applied`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        val outcome = resolve(cleared, command(BoardPlaylistOp.Add("e9", "late", 40),
            commandId = "command-0002", revision = 1, clearGeneration = 0))

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Reject)
        assertTrue((outcome as BoardPlaylistPolicy.Outcome.Reject).reason.contains("cleared"))
    }

    @Test fun `an edit composed after the clear is applied normally`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        val state = commit(cleared, BoardPlaylistOp.Add("e9", "fresh", 40))

        assertEquals(listOf("e9"), state.entries.map { it.entryId })
        assertEquals(1L, state.clearGeneration)
    }

    @Test fun `a retried clear does not clear a second time`() {
        val base = playlist(entry("e1"))
        val cleared = commit(base, BoardPlaylistOp.Clear())
        val refilled = commit(cleared, BoardPlaylistOp.Add("e2", "b", 40))

        // The retry carries the generation it was stamped with, which the
        // playlist has already reached.
        val replayed = BoardPlaylistPolicy.apply(refilled, listOf(BoardPlaylistOp.Clear(1)))

        assertEquals(listOf("e2"), replayed.entries.map { it.entryId })
    }

    // ===== Authority =====

    @Test fun `only the controller may report the physical send`() {
        val base = playlist(entry("e1"))
        val pending = BoardPlaylistPendingProjection("e1", "climb-e1", 40,
            BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED)

        val refused = resolve(base, command(BoardPlaylistOp.SetPendingProjection(pending)))
        val accepted = resolve(base, command(BoardPlaylistOp.SetPendingProjection(pending),
            commandId = "command-0002"), sender = "controller", isController = true)

        assertTrue(refused is BoardPlaylistPolicy.Outcome.Reject)
        assertTrue(accepted is BoardPlaylistPolicy.Outcome.Commit)
    }

    /**
     * A marker on an occurrence that is not the current one is the normal case:
     * a failed send leaves the confirmed current alone, so what did not reach
     * the wall is by definition something else. Normalisation used to delete
     * exactly that, which is why the failure had to be hidden behind a current
     * that lied.
     */
    @Test fun `a pending send may name an occurrence that is not current`() {
        val base = playlist(entry("e1"), entry("e2"))

        val state = BoardPlaylistPolicy.apply(base, listOf(
            BoardPlaylistOp.SetPendingProjection(BoardPlaylistPendingProjection(
                "e2", "climb-e2", 40, BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE))))

        assertEquals("e2", state.pendingProjection?.entryId)
        assertEquals("e1", state.currentEntryId)
    }

    @Test fun `a pending send naming no occurrence at all is dropped`() {
        val base = playlist(entry("e1"), entry("e2"))

        val state = BoardPlaylistPolicy.apply(base, listOf(
            BoardPlaylistOp.SetPendingProjection(BoardPlaylistPendingProjection(
                "gone", "climb-e2", 40, BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE))))

        assertNull(state.pendingProjection)
    }

    /** Still bound to its own occurrence's climb and angle, or it is stale. */
    @Test fun `a pending send that disagrees with its occurrence is dropped`() {
        val base = playlist(entry("e1"), entry("e2"))

        val state = BoardPlaylistPolicy.apply(base, listOf(
            BoardPlaylistOp.SetPendingProjection(BoardPlaylistPendingProjection(
                "e2", "some-other-climb", 40,
                BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE))))

        assertNull(state.pendingProjection)
    }

    // ===== Bounds =====

    @Test fun `the playlist never grows past its bound`() {
        val full = BoardPlaylistPolicy.apply(playlist(), (1..BoardPlaylistPolicy.MAX_ENTRIES)
            .map { BoardPlaylistOp.Add("e$it", "climb", 40) })

        val state = BoardPlaylistPolicy.apply(full, listOf(BoardPlaylistOp.Add("overflow", "x", 40)))

        assertEquals(BoardPlaylistPolicy.MAX_ENTRIES, state.entries.size)
        assertNull(state.entry("overflow"))
    }

    @Test fun `a command carrying more operations than the bound is refused`() {
        val ops = (1..BoardPlaylistPolicy.MAX_OPS_PER_COMMAND + 1)
            .map { BoardPlaylistOp.Add("e$it", "climb", 40) }

        val outcome = resolve(playlist(), BoardPlaylistCommand("command-0001", 0, 0, ops))

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Reject)
    }

    @Test fun `normalization drops duplicate ids and clamps rests`() {
        val raw = BoardPlaylistState(
            sessionId = 1,
            entries = listOf(
                BoardPlaylistEntry("e1", "a", 40, -5),
                BoardPlaylistEntry("e1", "b", 40, 10),
                BoardPlaylistEntry("e2", "c", 40, 99_999),
                BoardPlaylistEntry("", "d", 40, 0),
            ),
            currentEntryId = "missing",
        )

        val state = BoardPlaylistPolicy.normalize(raw)

        assertEquals(listOf("e1", "e2"), state.entries.map { it.entryId })
        assertEquals(0, state.entries[0].restAfterSeconds)
        assertEquals(BoardPlaylistPolicy.MAX_REST_SECONDS, state.entries[1].restAfterSeconds)
        assertEquals("e1", state.currentEntryId)
    }

    @Test fun `an empty command is accepted without moving anything`() {
        val outcome = resolve(playlist(entry("e1")), BoardPlaylistCommand("command-0001", 0, 0))

        assertTrue(outcome is BoardPlaylistPolicy.Outcome.Accepted)
    }

    // ===== Index-based UI intent becomes occurrence-addressed =====

    @Test fun `index builders resolve against the list they were read from`() {
        val base = playlist(entry("e1"), entry("e2"), entry("e3"))

        assertEquals(listOf(BoardPlaylistOp.Remove("e2")), BoardPlaylistOps.removeAt(base, 1))
        assertEquals(listOf(BoardPlaylistOp.SetCurrent("e3")), BoardPlaylistOps.setCurrentAt(base, 2))
        assertEquals(listOf(BoardPlaylistOp.SetRest("e1", 30)), BoardPlaylistOps.setRestAt(base, 0, 30))
        assertEquals(emptyList<BoardPlaylistOp>(), BoardPlaylistOps.removeAt(base, 9))
    }

    @Test fun `a move to the front is anchored at the head and a move down at a neighbour`() {
        val base = playlist(entry("e1"), entry("e2"), entry("e3"))

        assertEquals(listOf(BoardPlaylistOp.Move("e3", BoardPlaylistAnchor.Head)),
            BoardPlaylistOps.moveAt(base, 2, 0))
        assertEquals(listOf(BoardPlaylistOp.Move("e1", BoardPlaylistAnchor.After("e3"))),
            BoardPlaylistOps.moveAt(base, 0, 2))

        val moved = BoardPlaylistPolicy.apply(base, BoardPlaylistOps.moveAt(base, 0, 2))
        assertEquals(listOf("e2", "e3", "e1"), moved.entries.map { it.entryId })
    }

    /**
     * A long import is split across commands, so what each entry is anchored
     * to has to survive somebody else's add landing between two chunks.
     */
    @Test fun `a bulk add keeps its order and stays contiguous when it is split`() {
        var next = 0
        val ops = BoardPlaylistOps.addAll(
            (1..5).map { Triple("climb-$it", 40, 0) }) { "e${next++}" }

        assertEquals(BoardPlaylistAnchor.Tail, (ops.first() as BoardPlaylistOp.Add).anchor)
        assertEquals(BoardPlaylistAnchor.After("e3"), (ops.last() as BoardPlaylistOp.Add).anchor)

        val chunks = ops.chunked(2)
        // Somebody else adds a climb between the first and the second chunk.
        val interleaved = chunks.foldIndexed(playlist()) { index, state, chunk ->
            val next = BoardPlaylistPolicy.apply(state, chunk)
            if (index == 0) BoardPlaylistPolicy.apply(next,
                listOf(BoardPlaylistOp.Add("other", "somebody-else", 40)))
            else next
        }

        // The import stays in its own order and in one run, rather than being
        // split around the entry that arrived in the middle of it.
        assertEquals(listOf("e0", "e1", "e2", "e3", "e4", "other"),
            interleaved.entries.map { it.entryId })
    }

    /**
     * Chunks that arrive out of order are the pathological case. Order within
     * a chunk still holds and nothing is lost or duplicated — the anchor
     * fallback is a deterministic tail insert, not a guess.
     */
    @Test fun `chunks that arrive out of order still land exactly once each`() {
        var next = 0
        val chunks = BoardPlaylistOps.addAll(
            (1..5).map { Triple("climb-$it", 40, 0) }) { "e${next++}" }.chunked(2)

        val state = chunks.reversed().fold(playlist()) { current, chunk ->
            BoardPlaylistPolicy.apply(current, chunk)
        }

        assertEquals(5, state.entries.size)
        assertEquals(5, state.entries.map { it.entryId }.toSet().size)
        assertTrue(state.entries.map { it.entryId }.containsAll(listOf("e0", "e1", "e2", "e3", "e4")))
        assertTrue(state.indexOf("e0") < state.indexOf("e1"))
        assertTrue(state.indexOf("e2") < state.indexOf("e3"))
    }

    /**
     * Topping a problem first go ends the work on it. The queued repeats go,
     * and the pause that separated two attempts becomes the pause before a
     * different problem.
     */
    @Test fun `dropping the repeats of a climb also carries over their rest`() {
        val base = playlist(
            entry("e1", "hard", rest = 20),
            entry("e2", "hard", rest = 20),
            entry("e3", "hard", rest = 300),
            entry("e4", "other", rest = 0))

        val state = commit(base, *BoardPlaylistOps.dropRepeatsAfter(base, 0).toTypedArray())

        assertEquals(listOf("e1", "e4"), state.entries.map { it.entryId })
        assertEquals(300, state.entries[0].restAfterSeconds)
    }

    @Test fun `a climb with no queued repeats produces no operations`() {
        val base = playlist(entry("e1", "a"), entry("e2", "b"))

        assertEquals(emptyList<BoardPlaylistOp>(), BoardPlaylistOps.dropRepeatsAfter(base, 0))
        assertEquals(emptyList<BoardPlaylistOp>(), BoardPlaylistOps.dropRepeatsAfter(base, 9))
    }

    @Test fun `advancing past the last entry produces no operations`() {
        val base = BoardPlaylistPolicy.apply(playlist(entry("e1"), entry("e2")),
            listOf(BoardPlaylistOp.SetCurrent("e2")))

        assertEquals(emptyList<BoardPlaylistOp>(), BoardPlaylistOps.next(base))
        assertEquals(listOf(BoardPlaylistOp.SetCurrent("e1")), BoardPlaylistOps.previous(base))
    }
}
