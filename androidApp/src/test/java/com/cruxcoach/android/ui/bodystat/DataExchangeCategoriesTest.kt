package com.cruxcoach.android.ui.bodystat

import com.cruxcoach.data.CruxCoachBackup.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class DataExchangeCategoriesTest {
    @Test
    fun `manual complete backup exposes every codec category`() {
        assertEquals(Category.entries.toSet(), VISIBLE_CATEGORIES)
    }
}
