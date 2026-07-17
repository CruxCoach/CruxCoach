package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserPreferencesLastUsedBoardTest {
    @Test
    fun `last successful controller is stored independently per board family`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        preferences.setLastUsedBoardAddress(BoardBrand.MOONBOARD, "AA:00:00:00:00:01")
        preferences.setLastUsedBoardAddress(BoardBrand.KILTER, "BB:00:00:00:00:02")
        preferences.setLastUsedBoardAddress(BoardBrand.MOONBOARD, "AA:00:00:00:00:03")

        assertEquals(
            mapOf(
                BoardBrand.MOONBOARD to "AA:00:00:00:00:03",
                BoardBrand.KILTER to "BB:00:00:00:00:02",
            ),
            preferences.lastUsedBoardAddresses.first(),
        )
    }
}
