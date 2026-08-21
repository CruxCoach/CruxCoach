package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "On the board now", as an operation on the shared list.
 *
 * The owner's rule: a climb opened from a playlist entry lights *that* entry;
 * a climb opened from anywhere else becomes a new occurrence directly after
 * the current one and takes over as current. Repeats are legitimate — a 4x4 is
 * four of them — so nothing is ever quietly reused, searched for or moved.
 */
class BoardPlaylistLightNowTest {

    private fun entry(id: String, climb: String, angle: Int = 40) =
        BoardPlaylistEntry(entryId = id, climbUuid = climb, angle = angle)

    private fun playlist(vararg entries: BoardPlaylistEntry, current: String? = null) =
        BoardPlaylistState(
            entries = entries.toList(),
            currentEntryId = current ?: entries.firstOrNull()?.entryId,
        )

    @Test
    fun `opened from an entry, that entry becomes current and nothing is added`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-b"),
            current = "e1",
        )

        val plan = BoardPlaylistOps.lightNow(state, "climb-b", 40, fromEntryId = "e2")

        assertEquals("e2", plan.entryId)
        assertEquals(listOf(BoardPlaylistOp.SetCurrent("e2")), plan.ops)
    }

    @Test
    fun `lighting the entry already current changes nothing at all`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")

        val plan = BoardPlaylistOps.lightNow(state, "climb-a", 40, fromEntryId = "e1")

        assertEquals("e1", plan.entryId)
        assertEquals(emptyList<BoardPlaylistOp>(), plan.ops)
    }

    @Test
    fun `a climb from outside the list is added directly after the current one`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-b"),
            entry("e3", "climb-c"),
            current = "e2",
        )

        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 25) { "new-1" }

        assertEquals("new-1", plan.entryId)
        assertEquals(
            listOf(
                BoardPlaylistOp.Add(
                    "new-1", "climb-x", 25,
                    anchor = BoardPlaylistAnchor.After("e2"),
                ),
                BoardPlaylistOp.SetCurrent("new-1"),
            ),
            plan.ops,
        )
        // And it really lands where it says it does.
        val after = BoardPlaylistPolicy.apply(state, plan.ops)
        assertEquals(
            listOf("e1", "e2", "new-1", "e3"),
            after.entries.map { it.entryId },
        )
        assertEquals("new-1", after.currentEntryId)
    }

    /**
     * The rule that keeps somebody else's fourth go where they put it: an
     * existing occurrence of the same climb is never adopted, moved or
     * reused, however tempting the match looks.
     */
    @Test
    fun `an existing occurrence of the same climb is never reused`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-x"),
            entry("e3", "climb-b"),
            current = "e1",
        )

        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "new-1" }

        assertNotEquals("e2", plan.entryId)
        val after = BoardPlaylistPolicy.apply(state, plan.ops)
        assertEquals(
            listOf("e1", "new-1", "e2", "e3"),
            after.entries.map { it.entryId },
        )
        assertTrue(
            "the older occurrence keeps its place",
            after.entries.count { it.climbUuid == "climb-x" } == 2,
        )
    }

    @Test
    fun `an empty list takes the first climb as its only occurrence`() {
        val plan = BoardPlaylistOps.lightNow(BoardPlaylistState(), "climb-a", 40) { "new-1" }

        val after = BoardPlaylistPolicy.apply(BoardPlaylistState(), plan.ops)
        assertEquals(listOf("new-1"), after.entries.map { it.entryId })
        assertEquals("new-1", after.currentEntryId)
    }

    /**
     * A stale entry id — the occurrence was removed while the climb page was
     * open — falls back to the "from outside" branch rather than doing nothing.
     */
    @Test
    fun `an entry id that no longer exists mints a new occurrence`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")

        val plan = BoardPlaylistOps.lightNow(state, "climb-b", 40, fromEntryId = "gone") { "new-1" }

        assertEquals("new-1", plan.entryId)
        val after = BoardPlaylistPolicy.apply(state, plan.ops)
        assertEquals(listOf("e1", "new-1"), after.entries.map { it.entryId })
        assertEquals("new-1", after.currentEntryId)
    }
}
