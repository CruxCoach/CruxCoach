package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPlaylistLightNowTest {
    private fun entry(id: String, climb: String, angle: Int = 40) =
        BoardPlaylistEntry(entryId = id, climbUuid = climb, angle = angle)

    private fun playlist(
        vararg entries: BoardPlaylistEntry,
        selected: String? = null,
        current: String? = null,
    ) = BoardPlaylistState(
        entries = entries.toList(),
        selectedEntryId = selected ?: entries.firstOrNull()?.entryId,
        currentEntryId = current,
    )

    private fun commit(state: BoardPlaylistState, plan: BoardPlaylistOps.LightNow) =
        BoardPlaylistOps.commitProjection(
            state, plan.entryId,
            state.entry(plan.entryId)?.climbUuid ?: "climb-x",
            state.entry(plan.entryId)?.angle ?: 40,
            plan.materializeEntry, plan.placeAfterCurrent,
        )

    @Test fun `entry addressed navigation reuses that exact occurrence`() {
        val state = playlist(entry("e1", "climb-x"), entry("e2", "climb-x"), current = "e1")
        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40, fromEntryId = "e2")
        assertEquals("e2", plan.entryId)
        assertFalse(plan.materializeEntry)
        assertFalse(plan.placeAfterCurrent)
        assertFalse(plan.requiresMoveConfirmation)
    }

    @Test fun `generic detail reuses occurrence already current`() {
        val state = playlist(entry("e1", "climb-x"), entry("e2", "climb-a"), current = "e1")
        val plan = BoardPlaylistOps.lightNow(state, "CLIMB-X", 40) { "new" }
        assertEquals("e1", plan.entryId)
        assertFalse(plan.materializeEntry)
        assertFalse(plan.requiresMoveConfirmation)
        assertEquals(2, commit(state, plan).entries.size)
    }

    @Test fun `generic detail reuses next occurrence without confirmation`() {
        val state = playlist(entry("e1", "climb-a"), entry("e2", "climb-x"),
            entry("e3", "climb-b"), current = "e1")
        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "new" }
        assertEquals("e2", plan.entryId)
        assertFalse(plan.materializeEntry)
        assertFalse(plan.requiresMoveConfirmation)
        assertEquals("e2", commit(state, plan).currentEntryId)
    }

    @Test fun `generic detail occurrence elsewhere requires confirmation and moves after old current`() {
        val state = playlist(entry("e1", "climb-a"), entry("e2", "climb-b"),
            entry("e3", "climb-x"), current = "e1")
        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "new" }
        val after = commit(state, plan)
        assertEquals("e3", plan.entryId)
        assertTrue(plan.requiresMoveConfirmation)
        assertTrue(plan.placeAfterCurrent)
        assertEquals(listOf("e1", "e3", "e2"), after.entries.map { it.entryId })
        assertEquals("e3", after.currentEntryId)
    }

    @Test fun `generic detail only mints when climb and angle are absent`() {
        val state = playlist(entry("e1", "climb-a"), entry("e2", "climb-x", 30), current = "e1")
        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "minted" }
        val after = BoardPlaylistOps.commitProjection(state, plan.entryId, "climb-x", 40,
            plan.materializeEntry, plan.placeAfterCurrent)
        assertEquals("minted", plan.entryId)
        assertTrue(plan.materializeEntry)
        assertFalse(plan.requiresMoveConfirmation)
        assertEquals(listOf("e1", "minted", "e2"), after.entries.map { it.entryId })
        assertEquals("minted", after.currentEntryId)
    }

    @Test fun `repeating a generic detail commit does not inflate playlist`() {
        val original = playlist(entry("e1", "climb-a"), current = "e1")
        val first = BoardPlaylistOps.lightNow(original, "climb-x", 40) { "minted" }
        val once = BoardPlaylistOps.commitProjection(original, first.entryId, "climb-x", 40,
            first.materializeEntry, first.placeAfterCurrent)
        val second = BoardPlaylistOps.lightNow(once, "climb-x", 40) { "never" }
        val twice = BoardPlaylistOps.commitProjection(once, second.entryId, "climb-x", 40,
            second.materializeEntry, second.placeAfterCurrent)
        assertEquals("minted", second.entryId)
        assertEquals(once.entries, twice.entries)
        assertEquals(1, twice.entries.count { it.climbUuid == "climb-x" })
    }

    @Test fun `failed physical write has no playlist half to apply`() {
        val state = playlist(entry("e1", "climb-a"), current = "e1")
        val plan = BoardPlaylistOps.lightNow(state, "climb-x", 40) { "minted" }
        assertTrue(plan.materializeEntry)
        assertNull(state.entry("minted"))
        assertEquals("e1", state.currentEntryId)
    }
}
