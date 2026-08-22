package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An occurrence somebody else removes while this device has it open.
 *
 * The interesting part is the duplicate: the same climb is on the list twice,
 * so "does my entry still exist" cannot be answered by looking for the climb —
 * only by looking for the id. Answering it the loose way would have this
 * screen quietly adopt somebody else's occurrence of the same problem.
 */
class BoardPlaylistRemoteRemoveTest {

    private fun entry(id: String, climb: String, angle: Int = 40) =
        BoardPlaylistEntry(entryId = id, climbUuid = climb, angle = angle)

    /** The same climb twice, and the screen was opened from the second one. */
    private fun playlist() = BoardPlaylistState(
        entries = listOf(
            entry("e1", "climb-a"),
            entry("e2", "climb-x"),
            entry("e3", "climb-x"),
        ),
        selectedEntryId = "e1",
        currentEntryId = "e1",
    )

    @Test
    fun `removing the opened occurrence is visible even with a twin on the list`() {
        val after = BoardPlaylistPolicy.apply(playlist(), listOf(BoardPlaylistOp.Remove("e3")))

        assertNull("the opened occurrence is gone", after.entry("e3"))
        assertTrue("its twin is untouched", after.entry("e2") != null)
    }

    /**
     * The board tap afterwards must not revive the deleted id. Generic detail
     * reuses the remaining occurrence instead of inflating the playlist.
     */
    @Test
    fun `a board tap after a remote remove reuses the remaining occurrence`() {
        val after = BoardPlaylistPolicy.apply(playlist(), listOf(BoardPlaylistOp.Remove("e3")))

        // What the detail screen does once it has observed the removal: the
        // stale entry id is not passed on.
        val plan = BoardPlaylistOps.lightNow(after, "climb-x", 40, fromEntryId = null) { "new-1" }
        val lit = BoardPlaylistOps.commitProjection(after, plan.entryId, "climb-x", 40,
            plan.materializeEntry, plan.placeAfterCurrent)

        assertEquals("e2", plan.entryId)
        assertEquals(listOf("e1", "e2"), lit.entries.map { it.entryId })
    }

    /**
     * And the failure mode this replaces: passing the removed id on anyway.
     * `lightNow` falls back rather than resurrecting it — the id is gone, so
     * there is nothing to light — but the screen had already spent the time
     * between the removal and the tap claiming to be a view of it.
     */
    @Test
    fun `a stale entry id can never resurrect the removed occurrence`() {
        val after = BoardPlaylistPolicy.apply(playlist(), listOf(BoardPlaylistOp.Remove("e3")))

        val plan = BoardPlaylistOps.lightNow(after, "climb-x", 40, fromEntryId = "e3") { "new-1" }
        val lit = BoardPlaylistOps.commitProjection(after, plan.entryId, "climb-x", 40,
            plan.materializeEntry, plan.placeAfterCurrent)

        assertEquals("e2", plan.entryId)
        assertNull(lit.entry("e3"))
        assertEquals(2, lit.entries.size)
    }

    /** Removing an occurrence takes any failure marker for it along. */
    @Test
    fun `removing the occurrence a failed send named clears the marker`() {
        val marked = BoardPlaylistPolicy.apply(
            playlist(),
            BoardPlaylistOps.recordLightFailure(playlist(), "e3"),
        )
        assertEquals("e3", marked.pendingProjection?.entryId)

        val after = BoardPlaylistPolicy.apply(marked, listOf(BoardPlaylistOp.Remove("e3")))

        assertNull(after.pendingProjection)
        assertEquals("e1", after.selectedEntryId)
    }
}
