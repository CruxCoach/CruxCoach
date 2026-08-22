package com.cruxcoach.android.boardcell

import org.junit.Assert.*
import org.junit.Test

/**
 * Emptying the board's list, and the thirty seconds in which anybody can take
 * that back.
 *
 * The clear is the one edit on a shared list that nobody can reconstruct by
 * hand, and it is open to every member — which is exactly the pair of facts
 * that makes a canonical way back necessary rather than nice. These tests pin
 * the parts that have to hold on every replica at once: the window is stamped
 * by the controller so everyone counts the same one down, the restore is
 * idempotent and generation-scoped so it can never resurrect an older list,
 * and climbs queued after the clear survive it.
 */
class BoardPlaylistClearRestoreTest {

    /** 2026-08-17T12:00:00Z. */
    private val now = 1_786_968_000_000L

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
        nowEpochMs: Long = now,
    ) = BoardPlaylistPolicy.resolve(state, sender, command, nowEpochMs, isController)

    private fun commit(
        state: BoardPlaylistState,
        vararg ops: BoardPlaylistOp,
        sender: String = "member-a",
        isController: Boolean = false,
        nowEpochMs: Long = now,
    ): BoardPlaylistState {
        val outcome = resolve(state, command(*ops, clearGeneration = state.clearGeneration),
            sender, isController, nowEpochMs)
        assertTrue("expected a commit, got $outcome",
            outcome is BoardPlaylistPolicy.Outcome.Commit)
        return (outcome as BoardPlaylistPolicy.Outcome.Commit).playlist
    }

    // ===== The offer =====

    @Test fun `clearing keeps what it emptied and stamps the window it stands for`() {
        val base = playlist(entry("e1"), entry("e2", rest = 90), selected = "e2")

        val cleared = commit(base, BoardPlaylistOp.Clear())

        assertTrue(cleared.entries.isEmpty())
        val undo = present(cleared.lastClear)
        assertEquals(1L, undo.generation)
        assertEquals(listOf("e1", "e2"), undo.entries.map { it.entryId })
        assertEquals("e2", undo.selectedEntryId)
        assertEquals(90, undo.entries.last().restAfterSeconds)
        assertEquals(now, undo.clearedAtEpochMs)
        assertEquals(now + BoardPlaylistPolicy.RESTORE_WINDOW_MS, undo.restorableUntilEpochMs)
    }

    @Test fun `the countdown comes from the stamped window, not from when a device noticed`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())
        val undo = present(cleared.lastClear)

        // A phone that receives the snapshot ten seconds late sees twenty left,
        // not thirty — which is the whole reason the deadline travels rather
        // than the duration.
        assertEquals(20, undo.remainingSeconds(now + 10_000))
        assertEquals(0, undo.remainingSeconds(now + BoardPlaylistPolicy.RESTORE_WINDOW_MS))
        assertTrue(undo.hasExpired(now + BoardPlaylistPolicy.RESTORE_WINDOW_MS))
    }

    @Test fun `clearing a list that is already empty offers nothing to take back`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        val again = commit(cleared, BoardPlaylistOp.Clear())

        assertEquals(2L, again.clearGeneration)
        assertNull(again.lastClear)
    }

    // ===== Taking it back =====

    @Test fun `a restore puts the list back in front of what was added after the clear`() {
        val base = playlist(entry("e1"), entry("e2"))
        val cleared = commit(base, BoardPlaylistOp.Clear())
        val refilled = commit(cleared, BoardPlaylistOp.Add("e9", "added-after", 40))

        val restored = commit(refilled,
            BoardPlaylistOp.RestoreClear(present(refilled.lastClear).generation))

        // Both halves survive, in the order that makes sense: the list that
        // came back, then the climb somebody queued while it was gone.
        assertEquals(listOf("e1", "e2", "e9"), restored.entries.map { it.entryId })
        assertNull(restored.lastClear)
        assertEquals(1L, restored.clearGeneration)
    }

    @Test fun `a restore puts the group back on the entry it was on`() {
        val base = playlist(entry("e1"), entry("e2"), entry("e3"), selected = "e2")
        val cleared = commit(base, BoardPlaylistOp.Clear())

        val restored = commit(cleared, BoardPlaylistOp.RestoreClear(1))

        assertEquals("e2", restored.selectedEntryId)
    }

    @Test fun `any member may restore, not only whoever cleared`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear(), sender = "member-a")

        val restored = commit(cleared, BoardPlaylistOp.RestoreClear(1), sender = "member-b")

        assertEquals(listOf("e1"), restored.entries.map { it.entryId })
    }

    @Test fun `a replayed restore changes nothing the second time`() {
        val cleared = commit(playlist(entry("e1"), entry("e2")), BoardPlaylistOp.Clear())
        val restored = commit(cleared, BoardPlaylistOp.RestoreClear(1))

        val replayed = BoardPlaylistPolicy.apply(restored, listOf(BoardPlaylistOp.RestoreClear(1)))

        assertEquals(listOf("e1", "e2"), replayed.entries.map { it.entryId })
        assertEquals(restored, replayed)
    }

    @Test fun `a restore naming an older clear cannot resurrect that list`() {
        val first = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())
        val refilled = commit(first, BoardPlaylistOp.Add("e2", "second-list", 40))
        val second = commit(refilled, BoardPlaylistOp.Clear())

        // Generation 1's list is gone for good; only the offer the playlist is
        // actually carrying can be taken.
        val stale = BoardPlaylistPolicy.apply(second, listOf(BoardPlaylistOp.RestoreClear(1)))

        assertTrue(stale.entries.isEmpty())
        assertEquals(2L, stale.clearGeneration)
        assertEquals(listOf("e2"), present(stale.lastClear).entries.map { it.entryId })
    }

    /**
     * The clear-generation guard exists to drop edits that were in flight when
     * somebody emptied the list. A restore is the one edit whose entire point
     * is to name the generation the list has already moved to, so it has to be
     * exempt — otherwise the button could never be pressed at all.
     */
    @Test fun `a restore composed against the pre-clear generation is still accepted`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        val outcome = resolve(cleared, command(BoardPlaylistOp.RestoreClear(1),
            commandId = "command-0002", revision = 1, clearGeneration = 0))

        assertTrue("expected a commit, got $outcome",
            outcome is BoardPlaylistPolicy.Outcome.Commit)
    }

    @Test fun `a restore that would overflow keeps what was queued since the clear`() {
        val big = playlist(*(1..BoardPlaylistPolicy.MAX_ENTRIES)
            .map { entry("e$it") }.toTypedArray())
        val cleared = commit(big, BoardPlaylistOp.Clear())
        var refilled = cleared
        repeat(4) { index ->
            refilled = commit(refilled, BoardPlaylistOp.Add("late$index", "late", 40))
        }

        val restored = commit(refilled, BoardPlaylistOp.RestoreClear(1))

        assertEquals(BoardPlaylistPolicy.MAX_ENTRIES, restored.entries.size)
        // The four climbs the group asked for after the clear all survive; the
        // tail of the list it had already thrown away is what gives way.
        assertEquals(listOf("late0", "late1", "late2", "late3"),
            restored.entries.takeLast(4).map { it.entryId })
        assertNull(restored.lastClear)
    }

    @Test fun `a stale edit riding along with a restore is still refused`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        val outcome = resolve(cleared, command(
            BoardPlaylistOp.RestoreClear(1),
            BoardPlaylistOp.Add("e9", "in-flight-before-the-clear", 40),
            commandId = "command-0002", revision = 1, clearGeneration = 0))

        assertTrue("expected a reject, got $outcome",
            outcome is BoardPlaylistPolicy.Outcome.Reject)
    }

    // ===== When the offer runs out =====

    @Test fun `a restore after the window is refused rather than quietly acknowledged`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        val outcome = resolve(
            cleared,
            command(BoardPlaylistOp.RestoreClear(1), commandId = "command-0002", revision = 1,
                clearGeneration = 1),
            nowEpochMs = now + BoardPlaylistPolicy.RESTORE_WINDOW_MS + 1,
        )

        // Acknowledging a command that did nothing would leave whoever pressed
        // it staring at an empty list they believe they just brought back.
        assertTrue("expected a reject, got $outcome",
            outcome is BoardPlaylistPolicy.Outcome.Reject)
        assertTrue((outcome as BoardPlaylistPolicy.Outcome.Reject).reason.contains("window"))
    }

    @Test fun `the controller retires a lapsed offer on the next commit`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        // Any ordinary edit after the window will do: the emptied list must not
        // ride along in every snapshot until the next clear replaces it.
        val later = commit(cleared, BoardPlaylistOp.Add("e5", "later", 40),
            nowEpochMs = now + BoardPlaylistPolicy.RESTORE_WINDOW_MS + 1)

        assertNull(later.lastClear)
        assertEquals(listOf("e5"), later.entries.map { it.entryId })
    }

    @Test fun `a member may not retire the offer itself`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())

        val refused = resolve(cleared, command(BoardPlaylistOp.ExpireClearUndo(1),
            commandId = "command-0002", revision = 1, clearGeneration = 1))

        assertTrue(refused is BoardPlaylistPolicy.Outcome.Reject)
        present(cleared.lastClear)
    }

    // ===== Normalization =====

    @Test fun `post-clear additions trim the restore buffer to one snapshot budget`() {
        val original = (0 until BoardPlaylistPolicy.MAX_ENTRIES).map { entry("old$it") }
        val cleared = commit(playlist(*original.toTypedArray()), BoardPlaylistOp.Clear())
        val additions = (0 until 12).map { BoardPlaylistOp.Add("new$it", "later$it", 40) }

        val refilled = BoardPlaylistPolicy.apply(cleared, additions)

        assertEquals(BoardPlaylistPolicy.MAX_ENTRIES,
            refilled.entries.size + present(refilled.lastClear).entries.size)
        assertEquals(12, refilled.entries.size)
    }

    @Test fun `an offer that does not belong to the current clear is dropped`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())
        val undo = present(cleared.lastClear)

        val forged = BoardPlaylistPolicy.normalize(
            cleared.copy(lastClear = undo.copy(generation = 99)))

        assertNull(forged.lastClear)
    }

    @Test fun `an offer whose window is not really a window is dropped`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())
        val undo = present(cleared.lastClear)

        // A "thirty second" offer that stands until 2099 is exactly what
        // checking only the far end used to permit.
        val forged = BoardPlaylistPolicy.normalize(
            cleared.copy(lastClear = undo.copy(restorableUntilEpochMs = now + 80L * 365 * 86_400_000)))

        assertNull(forged.lastClear)
    }

    // ===== The durable state hash across the upgrade =====

    /**
     * The schema moved from V8 to V9 to carry the offer. A snapshot the
     * previous build wrote has no offer in it, so its own bytes must still
     * verify — otherwise every device would throw its cell away on upgrade and
     * rebuild it from the mesh for no reason.
     */
    @Test fun `a pre-restore snapshot still verifies under its own schema`() {
        val snapshot = BoardCellSnapshot(
            cellId = BoardCellId("cell-upgrade"),
            physicalBoardId = PhysicalBoardId("board-upgrade"),
            epoch = 1, sequence = 4, controllerId = "controller", lineageId = "lineage",
            members = setOf("controller", "member-a"),
            // Shaped as a pre-restore build really wrote one: a single value
            // in `currentEntryId` and nothing in the fields added since.
            playlist = playlist(entry("e1"), entry("e2")).copy(
                selectedEntryId = null, currentEntryId = "e1",
            ),
            playlistRevision = 3,
        )
        val v8 = snapshot.copy(stateHash = BoardCellHash.computeLegacyV8(snapshot))

        assertTrue(v8.hasValidHash())
        assertNotEquals(v8.stateHash, BoardCellHash.compute(snapshot))
    }

    @Test fun `a snapshot carrying an offer is only valid under the current schema`() {
        val cleared = commit(playlist(entry("e1")), BoardPlaylistOp.Clear())
        val snapshot = BoardCellSnapshot(
            cellId = BoardCellId("cell-upgrade"),
            physicalBoardId = PhysicalBoardId("board-upgrade"),
            epoch = 1, sequence = 5, controllerId = "controller", lineageId = "lineage",
            members = setOf("controller"),
            playlist = cleared,
            playlistRevision = 4,
        )

        // The legacy schema cannot express the offer, so accepting its hash for
        // a snapshot that carries one would mean two replicas agreeing on a
        // hash while disagreeing about the list.
        assertFalse(snapshot.copy(stateHash = BoardCellHash.computeLegacyV8(snapshot))
            .hasValidHash())
        assertTrue(snapshot.withComputedHash().hasValidHash())
    }

    /** The offer, or a failure that says so — never a null-pointer stack. */
    private fun <T> present(value: T?): T {
        assertNotNull("expected a value", value)
        return value!!
    }
}
