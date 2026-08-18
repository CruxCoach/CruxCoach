package com.cruxcoach.android.updater

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdaterPreferencesMetricsTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        tempFile = File.createTempFile("updater_metrics_test_", ".preferences_pb")
        tempFile.delete()
        tempFile.deleteOnExit()
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    private fun preferences(scope: CoroutineScope): UpdaterPreferences {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { tempFile }
        return UpdaterPreferences(dataStore)
    }

    @Test
    fun `anonymous counter defaults to enabled with no attempted version`() = runTest {
        val state = preferences(backgroundScope).snapshot()

        assertTrue(state.anonymousUpdateMetricsEnabled)
        assertNull(state.lastAnonymousMetricsAttemptVersion)
    }

    @Test
    fun `opt out and attempted target version persist together`() = runTest {
        val preferences = preferences(backgroundScope)

        preferences.update {
            it.copy(
                anonymousUpdateMetricsEnabled = false,
                lastAnonymousMetricsAttemptVersion = "0.2.2",
            )
        }

        val restored = preferences.snapshot()
        assertEquals(false, restored.anonymousUpdateMetricsEnabled)
        assertEquals("0.2.2", restored.lastAnonymousMetricsAttemptVersion)
    }

    @Test
    fun `attempted target version can be cleared without changing opt out`() = runTest {
        val preferences = preferences(backgroundScope)
        preferences.update {
            it.copy(
                anonymousUpdateMetricsEnabled = false,
                lastAnonymousMetricsAttemptVersion = "0.2.2",
            )
        }

        preferences.update { it.copy(lastAnonymousMetricsAttemptVersion = null) }

        val restored = preferences.snapshot()
        assertEquals(false, restored.anonymousUpdateMetricsEnabled)
        assertNull(restored.lastAnonymousMetricsAttemptVersion)
    }
}
