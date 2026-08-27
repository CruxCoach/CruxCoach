package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `complete controller descriptors support direct reconnect per board family`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)
        val moon = RememberedBoardController(
            displayName = "MoonBoard",
            serial = "",
            apiLevel = 0,
            address = "AA:00:00:00:00:01",
            boardBrand = BoardBrand.MOONBOARD,
        )
        val kilter = RememberedBoardController(
            displayName = "Kilter Board",
            serial = "123456789012",
            apiLevel = 3,
            address = "BB:00:00:00:00:02",
            boardBrand = BoardBrand.KILTER,
        )

        preferences.setRememberedBoardController(moon)
        preferences.setRememberedBoardController(kilter)

        assertEquals(
            mapOf(BoardBrand.MOONBOARD to moon, BoardBrand.KILTER to kilter),
            preferences.rememberedBoardControllers.first(),
        )
        assertEquals(
            mapOf(
                BoardBrand.MOONBOARD to moon.address,
                BoardBrand.KILTER to kilter.address,
            ),
            preferences.lastUsedBoardAddresses.first(),
        )
    }

    @Test
    fun `legacy address without descriptor is not offered for direct reconnect`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        preferences.setLastUsedBoardAddress(BoardBrand.KILTER, "BB:00:00:00:00:02")

        assertTrue(preferences.rememberedBoardControllers.first().isEmpty())
    }

    @Test
    fun `capacity observation never crosses between controllers of one brand`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)
        val first = RememberedBoardController(
            displayName = "Kilter Board A",
            serial = "A",
            apiLevel = 3,
            address = "AA:00:00:00:00:01",
            boardBrand = BoardBrand.KILTER,
        )
        val second = first.copy(
            displayName = "Kilter Board B",
            serial = "B",
            address = "BB:00:00:00:00:02",
        )

        preferences.setRememberedBoardController(first)
        preferences.setRememberedBoardAdvertisesWhileConnected(
            brand = BoardBrand.KILTER,
            address = first.address,
        )
        assertEquals(
            true,
            preferences.rememberedBoardControllers.first()[BoardBrand.KILTER]
                ?.advertisesWhileConnected,
        )

        preferences.setRememberedBoardController(second)
        assertNull(
            preferences.rememberedBoardControllers.first()[BoardBrand.KILTER]
                ?.advertisesWhileConnected,
        )

        // A late result from the old connection must not modify the new one.
        preferences.setRememberedBoardAdvertisesWhileConnected(
            brand = BoardBrand.KILTER,
            address = first.address,
        )
        assertNull(
            preferences.rememberedBoardControllers.first()[BoardBrand.KILTER]
                ?.advertisesWhileConnected,
        )

        preferences.setRememberedBoardAdvertisesWhileConnected(
            brand = BoardBrand.KILTER,
            address = second.address,
        )
        assertEquals(
            true,
            preferences.rememberedBoardControllers.first()[BoardBrand.KILTER]
                ?.advertisesWhileConnected,
        )
    }
}
