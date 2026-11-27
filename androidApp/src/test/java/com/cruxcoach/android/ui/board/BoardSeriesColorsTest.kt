package com.cruxcoach.android.ui.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardSeriesColorsTest {

    @Test
    fun `boards take the colour slots in board order, not in the order they come in`() {
        assertEquals(
            mapOf("kilter" to 0, "moonboard" to 1, "tension" to 2),
            BoardSeriesColors.slots(listOf("tension", "kilter", "moonboard", "kilter")),
        )
    }

    @Test
    fun `two boards always get the first two slots`() {
        assertEquals(mapOf("tension" to 0, "shinywall" to 1), BoardSeriesColors.slots(listOf("shinywall", "tension")))
    }

    @Test
    fun `a ninth board gets no hue of its own`() {
        val boards = listOf(
            "kilter", "moonboard", "tension", "grasshopper", "decoy", "soill", "touchstone", "quantum", "shinywall",
        )
        val slots = BoardSeriesColors.slots(boards)
        assertEquals((0..7).toList(), boards.take(8).map { slots.getValue(it) })
        assertNull(slots.getValue("shinywall"))
    }
}
