package com.cruxcoach.android.fakes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.cruxcoach.android.data.UserPreferences
import java.io.File

/**
 * Creates a real [UserPreferences] backed by a temporary file-based DataStore.
 * This avoids the need to mock the concrete class -- all flows work correctly
 * because they read from a real (empty) DataStore, returning default values.
 *
 * Usage:
 * ```
 * val prefs = createTestUserPreferences()
 * // prefs.boardAngle emits 40 (default), prefs.gradeScale emits FRENCH, etc.
 * ```
 */
fun createTestUserPreferences(): UserPreferences {
    val tempFile = File.createTempFile("test_prefs_", ".preferences_pb")
    tempFile.deleteOnExit()
    tempFile.delete()
    val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create { tempFile }

    val keyTempFile = File.createTempFile("test_key_prefs_", ".preferences_pb")
    keyTempFile.deleteOnExit()
    keyTempFile.delete()
    val keyScopedDataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create { keyTempFile }

    return UserPreferences(dataStore, keyScopedDataStore)
}
