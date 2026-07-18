package com.cruxcoach.android.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.cruxcoach.android.fakes.createTestUserPreferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UserPreferencesBoardSendModeTest {
    @Test
    fun `capacity-specific send modes default to automatic and persist independently`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        assertEquals(
            BoardSendMode.AUTOMATIC,
            preferences.singleConnectionBoardSendMode.first(),
        )
        assertEquals(
            BoardSendMode.AUTOMATIC,
            preferences.multiConnectionBoardSendMode.first(),
        )

        preferences.setSingleConnectionBoardSendMode(BoardSendMode.EXPLICIT)

        assertEquals(
            BoardSendMode.EXPLICIT,
            preferences.singleConnectionBoardSendMode.first(),
        )
        assertEquals(
            BoardSendMode.AUTOMATIC,
            preferences.multiConnectionBoardSendMode.first(),
        )

        preferences.setMultiConnectionBoardSendMode(BoardSendMode.EXPLICIT)

        assertEquals(
            BoardSendMode.EXPLICIT,
            preferences.multiConnectionBoardSendMode.first(),
        )
    }

    @Test
    fun `unknown persisted send mode falls back to automatic`() {
        assertEquals(BoardSendMode.AUTOMATIC, BoardSendMode.fromWire("FUTURE_MODE"))
    }

    @Test
    fun `legacy global mode seeds both capacity-specific modes`() = runTest {
        val (preferences, dataStore) = createPreferencesWithStore(backgroundScope)
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BOARD_SEND_MODE] = BoardSendMode.EXPLICIT.name
        }

        assertEquals(
            BoardSendMode.EXPLICIT,
            preferences.singleConnectionBoardSendMode.first(),
        )
        assertEquals(
            BoardSendMode.EXPLICIT,
            preferences.multiConnectionBoardSendMode.first(),
        )

        preferences.setSingleConnectionBoardSendMode(BoardSendMode.AUTOMATIC)

        assertEquals(
            BoardSendMode.AUTOMATIC,
            preferences.singleConnectionBoardSendMode.first(),
        )
        assertEquals(
            BoardSendMode.EXPLICIT,
            preferences.multiConnectionBoardSendMode.first(),
        )
    }

    private fun createPreferencesWithStore(
        scope: CoroutineScope,
    ): Pair<UserPreferences, DataStore<Preferences>> {
        val dataFile = File.createTempFile("send_mode_prefs_", ".preferences_pb")
        dataFile.deleteOnExit()
        dataFile.delete()
        val keyFile = File.createTempFile("send_mode_keys_", ".preferences_pb")
        keyFile.deleteOnExit()
        keyFile.delete()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dataFile }
        val keyStore = PreferenceDataStoreFactory.create(scope = scope) { keyFile }
        return UserPreferences(dataStore, keyStore) to dataStore
    }
}
