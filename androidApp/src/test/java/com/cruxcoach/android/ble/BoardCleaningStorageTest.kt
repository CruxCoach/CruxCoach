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

    @Test fun `cleaned marks survive storage reconstruction and a corrupt cache degrades to empty`() {
        val context = RuntimeEnvironment.getApplication()
        val marks = mapOf(BoardCleaningStorage.key(board)!! to CleaningMark("2026-10-10", "2026-10-10T18:00", setOf(0, 84, 198)))
        BoardCleaningStorage(context).save(marks)
        assertEquals(marks, BoardCleaningStorage(context).load())
        context.getSharedPreferences("board_cleaning", Context.MODE_PRIVATE).edit()
            .putString("marks", "broken").commit()
        assertTrue(BoardCleaningStorage(context).load().isEmpty())
    }

    @Test fun `the sent-climb day collection of earlier builds is removed`() {
        val context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("board_cleaning", Context.MODE_PRIVATE)
        preferences.edit().putString("days", """{"abc":{"date":"2026-10-10","positions":[1,2]}}""").commit()
        assertTrue(BoardCleaningStorage(context).load().isEmpty())
        assertFalse(preferences.contains("days"))
    }

    @Test fun `stored marks hold only a date, a time and LED positions per hashed controller`() {
        val context = RuntimeEnvironment.getApplication()
        val key = BoardCleaningStorage.key(board)!!
        BoardCleaningStorage(context).save(mapOf(key to CleaningMark("2026-10-10", "2026-10-10T18:00", setOf(5))))
        val stored = context.getSharedPreferences("board_cleaning", Context.MODE_PRIVATE).all
        assertEquals(setOf("marks"), stored.keys)
        assertEquals("""{"$key":{"date":"2026-10-10","until":"2026-10-10T18:00","cleaned":[5]}}""", stored["marks"])
    }
}
