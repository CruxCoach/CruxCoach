package com.cruxcoach.android.ui.bodystat

import com.cruxcoach.data.CruxCoachBackup.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DataExchangeCategoriesTest {
    @Test
    fun `manual backup exposes only released user-facing data`() {
        assertEquals(
            setOf(
                Category.BOARD_LOGBOOK,
                Category.CLIMB_LISTS,
                Category.OWN_CLIMBS,
            ),
            VISIBLE_CATEGORIES,
        )
    }

    @Test
    fun `manual board logbook selection includes private climb notes on the wire`() {
        assertEquals(
            setOf(Category.BOARD_LOGBOOK, Category.CLIMB_NOTES),
            setOf(Category.BOARD_LOGBOOK).withBundledClimbNotes(),
        )
        assertFalse(Category.CLIMB_NOTES in setOf(Category.CLIMB_LISTS).withBundledClimbNotes())
    }

    @Test
    fun `wire-format climb notes appear as board logbook in manual import`() {
        assertEquals(
            setOf(Category.BOARD_LOGBOOK),
            setOf(Category.CLIMB_NOTES).toManualCategories(),
        )
    }
}
