package com.cruxcoach.app.training

import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Side
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android `PlayerPageSidesTest` (device test D47): a per-side exercise said "0 of 4 sets" on the strip. */
class PlayerPageSidesTest {

    private fun set(i: Int, side: Side?, done: Boolean) =
        ExerciseSet("s$i$side", "w", "core.turkish_getup", 0, i, side = side, completedAt = if (done) 1L else null)

    @Test
    fun `a per-side exercise counts sets per side and a set is done when both sides are`() {
        val page = WorkoutPlayerPage(listOf(0), null, listOf(set(1, Side.LEFT, true), set(1, Side.RIGHT, false), set(2, Side.LEFT, false), set(2, Side.RIGHT, false)))
        assertTrue(page.perSide)
        assertEquals(2, page.setsTotal)
        assertEquals(0, page.setsDone)
        val both = page.copy(sets = page.sets.map { if (it.setIndex == 1) it.copy(completedAt = 1L) else it })
        assertEquals(1, both.setsDone)
        // The raw counts stay for the fill of the strip.
        assertEquals(4, both.total)
        assertEquals(2, both.done)
    }

    @Test
    fun `an ordinary exercise keeps its plain set count`() {
        val page = WorkoutPlayerPage(listOf(0), null, listOf(set(1, null, true), set(2, null, false), set(3, null, false)))
        assertFalse(page.perSide)
        assertEquals(1, page.setsDone)
        assertEquals(3, page.setsTotal)
        // One side only (a single-arm exercise logged for one side) is not per side either.
        val oneSide = WorkoutPlayerPage(listOf(0), null, listOf(set(1, Side.LEFT, true), set(2, Side.LEFT, false)))
        assertFalse(oneSide.perSide)
        assertEquals(2, oneSide.setsTotal)
    }
}
