package com.cruxcoach.android.nostr.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.cruxcoach.android.data.SyncInterval
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * FEAT-021: BackupPreferences.backupInterval round-trip.
 *
 * The pre-fix code path persisted the user's chosen backup cadence only
 * in the BackupSettingsViewModel's StateFlow — the next cold start
 * silently dropped to the default and re-scheduled the worker against
 * the unrelated *board-sync* interval. These tests pin the persistence
 * contract so a future regression triggers immediately.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupPreferencesIntervalTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        tempFile = File.createTempFile("backup_prefs_interval_test_", ".preferences_pb")
        tempFile.delete()
        tempFile.deleteOnExit()
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    private fun newPrefs(scope: CoroutineScope): BackupPreferences {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { tempFile }
        return BackupPreferences(dataStore = dataStore)
    }

    @Test
    fun `backupInterval defaults to DAILY when key is unset`() = runTest {
        val prefs = newPrefs(backgroundScope)
        assertEquals(SyncInterval.DAILY, prefs.backupInterval.first())
    }

    @Test
    fun `setBackupInterval persists across reads`() = runTest {
        val prefs = newPrefs(backgroundScope)
        prefs.setBackupInterval(SyncInterval.WEEKLY)
        assertEquals(SyncInterval.WEEKLY, prefs.backupInterval.first())
    }

    @Test
    fun `setBackupInterval overwrites previous value`() = runTest {
        val prefs = newPrefs(backgroundScope)
        prefs.setBackupInterval(SyncInterval.WEEKLY)
        prefs.setBackupInterval(SyncInterval.MANUAL)
        assertEquals(SyncInterval.MANUAL, prefs.backupInterval.first())
    }

    @Test
    fun `backupInterval persists by name not ordinal`() = runTest {
        // Pin the storage shape so a future change to SyncInterval enum
        // ordering doesn't silently re-map existing user choices to a
        // different cadence.
        val prefs = newPrefs(backgroundScope)
        prefs.setBackupInterval(SyncInterval.WEEKLY)
        val raw = dataStore.data.first()[BackupPreferences.Keys.BACKUP_INTERVAL]
        assertEquals("WEEKLY", raw)
    }
}
