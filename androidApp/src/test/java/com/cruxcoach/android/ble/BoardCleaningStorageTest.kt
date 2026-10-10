package com.cruxcoach.android.ble

import android.app.Application
import android.content.Context
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BoardCleaningStorageTest {
    private val board = DiscoveredBoard("Kilter", "123", 3, "aa:bb:cc:dd:ee:ff", -40)

    @Test fun `physical controller key ignores display name but separates address family and protocol`() {
        val key = BoardCleaningStorage.key(board)
        assertEquals(key, BoardCleaningStorage.key(board.copy(displayName = "Renamed", address = board.address.uppercase())))
        assertNotEquals(key, BoardCleaningStorage.key(board.copy(address = "00:00:00:00:00:00")))
        assertNotEquals(key, BoardCleaningStorage.key(board.copy(boardBrand = BoardBrand.TENSION)))
        assertNotEquals(key, BoardCleaningStorage.key(board.copy(apiLevel = 2)))
        assertNull(BoardCleaningStorage.key(board.copy(boardBrand = BoardBrand.QUANTUM)))
        assertNull(BoardCleaningStorage.key(board.copy(isCruxRelay = true)))
        assertFalse(key!!.contains(board.address, ignoreCase = true))
    }

    @Test fun `daily collection survives storage reconstruction and corrupt cache degrades to empty`() {
        val context = RuntimeEnvironment.getApplication()
        val cache = mapOf(BoardCleaningStorage.key(board)!! to CleaningDay("2026-10-10", setOf(0, 84, 198)))
        BoardCleaningStorage(context).save(cache)
        assertEquals(cache, BoardCleaningStorage(context).load())
        context.getSharedPreferences("board_cleaning", Context.MODE_PRIVATE).edit()
            .putString("days", "broken").commit()
        assertTrue(BoardCleaningStorage(context).load().isEmpty())
    }
}
