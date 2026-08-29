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

class UserPreferencesAutoDisconnectTest {

    @Test
    fun `board auto disconnect defaults off and preserves explicit opt in`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        assertEquals(0, preferences.bleAutoDisconnectSeconds.first())

        preferences.setBleAutoDisconnectSeconds(90)

        assertEquals(90, preferences.bleAutoDisconnectSeconds.first())
    }

    @Test
    fun `explicit legacy duration is retained on upgrade`() = runTest {
        val (preferences, dataStore) = createPreferencesWithStore(backgroundScope)
        dataStore.edit { it[PreferenceKeys.BLE_AUTO_DISCONNECT_MINUTES] = 3 }

        assertEquals(180, preferences.bleAutoDisconnectSeconds.first())
    }

    private fun createPreferencesWithStore(
        scope: CoroutineScope,
    ): Pair<UserPreferences, DataStore<Preferences>> {
        val dataFile = File.createTempFile("auto_disconnect_prefs_", ".preferences_pb")
        dataFile.deleteOnExit()
        dataFile.delete()
        val keyFile = File.createTempFile("auto_disconnect_keys_", ".preferences_pb")
        keyFile.deleteOnExit()
        keyFile.delete()
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { dataFile }
        val keyStore = PreferenceDataStoreFactory.create(scope = scope) { keyFile }
        return UserPreferences(dataStore, keyStore) to dataStore
    }
}
