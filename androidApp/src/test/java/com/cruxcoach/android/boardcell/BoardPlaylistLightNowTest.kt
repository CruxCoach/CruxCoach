package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "On the board now", as an operation on the shared list.
 *
 * The owner's rule: a climb opened from a playlist entry lights *that* entry;
 * a climb opened from anywhere else becomes a new occurrence directly after
 * the current one and takes over as current. Repeats are legitimate — a 4x4 is
 * four of them — so nothing is ever quietly reused, searched for or moved.
 *
 * And it happens in two phases. Adding the occurrence is what a tap means; the
 * group being *on* it is what the wall says afterwards. Collapsing the two —
 * which is what this did before — let the current point at a climb that never
 * reached the board, with the one that is actually lit no longer named
 * anywhere.
 */
class BoardPlaylistLightNowTest {

    private fun entry(id: String, climb: String, angle: Int = 40) =
        BoardPlaylistEntry(entryId = id, climbUuid = climb, angle = angle)

    private fun playlist(vararg entries: BoardPlaylistEntry, current: String? = null) =
        BoardPlaylistState(
            entries = entries.toList(),
            currentEntryId = current ?: entries.firstOrNull()?.entryId,
        )

    /** Phase one, then the wall answered yes. */
    private fun lit(state: BoardPlaylistState, plan: BoardPlaylistOps.LightNow): BoardPlaylistState {
        val added = BoardPlaylistPolicy.apply(state, plan.ops)
        return BoardPlaylistPolicy.apply(added, BoardPlaylistOps.confirmLit(added, plan.entryId))
    }

    /** Phase one, then the wall did not take it. */
    private fun failed(state: BoardPlaylistState, plan: BoardPlaylistOps.LightNow): BoardPlaylistState {
        val added = BoardPlaylistPolicy.apply(state, plan.ops)
        return BoardPlaylistPolicy.apply(added, BoardPlaylistOps.recordLightFailure(added, plan.entryId))
    }

    @Test
    fun `opened from an entry, that entry becomes current and nothing is added`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-b"),
            current = "e1",
        )

        val plan = BoardPlaylistOps.lightNow(state, "climb-b", 40, fromEntryId = "e2")

        assertEquals("e2", plan.entryId)
        assertEquals("the list is already right; only the wall is not", emptyList<BoardPlaylistOp>(), plan.ops)
        assertEquals("e2", lit(state, plan).currentEntryId)
    }

    @Test
    fun `lighting the entry already current changes nothing at all`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")

        val plan = BoardPlaylistOps.lightNow(state, "climb-a", 40, fromEntryId = "e1")

        assertEquals("e1", plan.entryId)
        assertEquals(emptyList<BoardPlaylistOp>(), plan.ops)
        assertEquals(emptyList<BoardPlaylistOp>(), BoardPlaylistOps.confirmLit(state, "e1"))
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
            ),
            plan.ops,
        )
        // And it really lands where it says it does.
        val after = lit(state, plan)
        assertEquals(
            listOf("e1", "e2", "new-1", "e3"),
            after.entries.map { it.entryId },
        )
        assertEquals("new-1", after.currentEntryId)
    }

    // ── The transaction ───────────────────────────────────────────────────

    /**
     * The split-brain this exists to prevent: a current that says the group is
     * on a climb, with nothing anywhere saying the wall never took it.
     *
     * Adding the occurrence and moving to it are still what the tap meant, so
     * both happen — but only once the wall has answered, and the failure
     * travels in the same command as the move, so no replica can see one
     * without the other.
     */
    @Test
    fun `a write that does not land moves the group with the reason attached`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-b"),
            current = "e1",
        )

        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "new-1" }

        // Phase one alone never moves anybody.
        assertEquals("e1", BoardPlaylistPolicy.apply(state, plan.ops).currentEntryId)

        val after = failed(state, plan)
        assertEquals("new-1", after.currentEntryId)
        assertEquals(
            BoardPlaylistPendingProjection(
                "new-1", "climb-x", 40,
                BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED,
            ),
            after.pendingProjection,
        )
    }

    /** Nothing at all is claimed until the wall has answered, either way. */
    @Test
    fun `phase one adds the occurrence and claims nothing`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")

        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "new-1" }
        val added = BoardPlaylistPolicy.apply(state, plan.ops)

        assertEquals(listOf("e1", "new-1"), added.entries.map { it.entryId })
        assertEquals("e1", added.currentEntryId)
        assertNull(added.pendingProjection)
    }

    /** Nothing to confirm and nothing to blame: an occurrence nobody added. */
    @Test
    fun `neither phase two acts on an occurrence that is not there`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")

        assertEquals(emptyList<BoardPlaylistOp>(), BoardPlaylistOps.confirmLit(state, "gone"))
        assertEquals(emptyList<BoardPlaylistOp>(), BoardPlaylistOps.recordLightFailure(state, "gone"))
    }

    /**
     * The controller's half of somebody else's light-now. The member's add and
     * its projection request are two messages and the mesh does not promise an
     * order, so the controller materialises the occurrence under the id the
     * member already chose.
     */
    @Test
    fun `the controller completes a light-now whose add has not arrived yet`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")

        val ops = BoardPlaylistOps.completeLightNow(state, "m-1", "climb-x", 40, landed = true)
        val after = BoardPlaylistPolicy.apply(state, ops)

        assertEquals(listOf("e1", "m-1"), after.entries.map { it.entryId })
        assertEquals("m-1", after.currentEntryId)
    }

    /** And the member's own add, arriving late, merges into it rather than doubling it. */
    @Test
    fun `a late add for the same occurrence adds nothing`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")
        val settled = BoardPlaylistPolicy.apply(
            state,
            BoardPlaylistOps.completeLightNow(state, "m-1", "climb-x", 40, landed = true),
        )

        val late = BoardPlaylistPolicy.apply(
            settled,
            listOf(BoardPlaylistOp.Add("m-1", "climb-x", 40, anchor = BoardPlaylistAnchor.After("e1"))),
        )

        assertEquals(listOf("e1", "m-1"), late.entries.map { it.entryId })
        assertEquals(1, late.entries.count { it.climbUuid == "climb-x" })
    }

    @Test
    fun `a controller-completed light-now that did not land says so`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")

        val after = BoardPlaylistPolicy.apply(
            state,
            BoardPlaylistOps.completeLightNow(state, "m-1", "climb-x", 40, landed = false),
        )

        assertEquals("m-1", after.currentEntryId)
        assertEquals("m-1", after.pendingProjection?.entryId)
        assertEquals(
            BoardPlaylistProjectionPendingReason.BOARD_WRITE_FAILED,
            after.pendingProjection?.reason,
        )
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
        val after = lit(state, plan)
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

        val after = lit(BoardPlaylistState(), plan)
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
        val after = lit(state, plan)
        assertEquals(listOf("e1", "new-1"), after.entries.map { it.entryId })
        assertEquals("new-1", after.currentEntryId)
    }

    // ── The same climb, twice, with two identities ────────────────────────
    //
    // The case the whole entry-id contract exists for: a 4x4 is the same climb
    // four times, and tapping the third one has to mean the third one.

    @Test
    fun `two occurrences of one climb are lit individually`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-x"),
            entry("e3", "climb-b"),
            entry("e4", "climb-x"),
            current = "e1",
        )

        val second = BoardPlaylistOps.lightNow(state, "climb-x", 40, fromEntryId = "e4")

        assertEquals("e4", second.entryId)
        val after = lit(state, second)
        assertEquals("e4", after.currentEntryId)
        assertEquals(
            "lighting one occurrence must not disturb the other",
            listOf("e1", "e2", "e3", "e4"),
            after.entries.map { it.entryId },
        )
    }

    @Test
    fun `lighting the same occurrence repeatedly changes neither length nor order`() {
        var state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-x"),
            entry("e3", "climb-x"),
            current = "e1",
        )
        val before = state.entries.map { it.entryId }

        repeat(5) {
            val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40, fromEntryId = "e3")
            assertEquals("e3", plan.entryId)
            state = lit(state, plan)
        }

        assertEquals(before, state.entries.map { it.entryId })
        assertEquals("e3", state.currentEntryId)
    }

    /**
     * Alternating between the list's lamp and the climb page's must be the same
     * operation, not two that fight over the order.
     */
    @Test
    fun `lighting from list and from detail are the same operation`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-x"),
            current = "e1",
        )

        val fromList = BoardPlaylistOps.lightNow(state, "climb-x", 40, fromEntryId = "e2")
        val fromDetail = BoardPlaylistOps.lightNow(state, "climb-x", 40, fromEntryId = "e2")

        assertEquals(fromList.entryId, fromDetail.entryId)
        assertEquals(fromList.ops, fromDetail.ops)
    }

    /**
     * The counterpart: a climb genuinely from outside the list still mints one,
     * even when an occurrence of the same climb is already on it.
     */
    @Test
    fun `a climb opened from the browser mints one even with a twin on the list`() {
        val state = playlist(
            entry("e1", "climb-a"),
            entry("e2", "climb-x"),
            current = "e1",
        )

        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40, fromEntryId = null) { "new-1" }

        assertEquals("new-1", plan.entryId)
        val after = lit(state, plan)
        assertEquals(listOf("e1", "new-1", "e2"), after.entries.map { it.entryId })
    }

    /** A retry after a failure lands on the occurrence that already exists. */
    @Test
    fun `retrying a failed light-now does not add a second occurrence`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")
        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "new-1" }
        val afterFailure = failed(state, plan)

        val retry = BoardPlaylistOps.lightNow(afterFailure, "climb-x", 40, fromEntryId = "new-1")
        val afterRetry = lit(afterFailure, retry)

        assertEquals(listOf("e1", "new-1"), afterRetry.entries.map { it.entryId })
        assertEquals("new-1", afterRetry.currentEntryId)
        assertNull("a landed retry clears the failure", afterRetry.pendingProjection)
    }
}
