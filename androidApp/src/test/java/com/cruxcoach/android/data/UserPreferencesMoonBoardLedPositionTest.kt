package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.domain.board.MoonBoardLedMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserPreferencesMoonBoardLedPositionTest {

    @Test
    fun `LED position defaults below and persists every mode`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        assertEquals(MoonBoardLedMode.BELOW, preferences.moonBoardLedMode.first())

        preferences.setMoonBoardLedMode(MoonBoardLedMode.ABOVE)
        assertEquals(MoonBoardLedMode.ABOVE, preferences.moonBoardLedMode.first())

        preferences.setMoonBoardLedMode(MoonBoardLedMode.BOTH)
        assertEquals(MoonBoardLedMode.BOTH, preferences.moonBoardLedMode.first())
    }
}
