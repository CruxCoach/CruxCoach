package com.cruxcoach.android.fakes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.cruxcoach.android.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Creates a real [UserPreferences] backed by two temporary file-based DataStores.
 *
 * Pass a [scope] tied to the test's lifecycle (inside `runTest { ... }` use
 * `backgroundScope`) so DataStore's background writer is cancelled with the
 * test. If omitted, DataStore's default `Dispatchers.IO + SupervisorJob()`
 * scope outlives the test and surfaces as `UncaughtExceptionsBeforeTest` in
 * the next test that runs.
 */
fun createTestUserPreferences(scope: CoroutineScope): UserPreferences {
    val tempFile = File.createTempFile("test_prefs_", ".preferences_pb")
    tempFile.deleteOnExit()
    tempFile.delete()
    val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { tempFile }

    val keyTempFile = File.createTempFile("test_key_prefs_", ".preferences_pb")
    keyTempFile.deleteOnExit()
    keyTempFile.delete()
    val keyScopedDataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) { keyTempFile }

    return UserPreferences(dataStore, keyScopedDataStore)
}
