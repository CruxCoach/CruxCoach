package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserPreferencesBoardSendModeTest {
    @Test
    fun `send mode defaults to automatic and persists explicit`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        assertEquals(BoardSendMode.AUTOMATIC, preferences.boardSendMode.first())

        preferences.setBoardSendMode(BoardSendMode.EXPLICIT)

        assertEquals(BoardSendMode.EXPLICIT, preferences.boardSendMode.first())
    }

    @Test
    fun `unknown persisted send mode falls back to automatic`() {
        assertEquals(BoardSendMode.AUTOMATIC, BoardSendMode.fromWire("FUTURE_MODE"))
    }
}
