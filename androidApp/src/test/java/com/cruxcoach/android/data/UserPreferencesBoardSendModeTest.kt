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
import org.junit.Assert.assertEquals as assertEqualsWithMessage

class UserPreferencesBoardSendModeTest {
    @Test
    fun `single defaults automatic and multi defaults manual`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        assertEquals(
            BoardSendMode.AUTOMATIC,
            preferences.singleConnectionBoardSendMode.first(),
        )
        assertEquals(
            BoardSendMode.EXPLICIT,
            preferences.multiConnectionBoardSendMode.first(),
        )

        preferences.setSingleConnectionBoardSendMode(BoardSendMode.EXPLICIT)

        assertEquals(
            BoardSendMode.EXPLICIT,
            preferences.singleConnectionBoardSendMode.first(),
        )
        assertEqualsWithMessage(
            "opting one capacity in must not opt the other in",
            BoardSendMode.EXPLICIT,
            preferences.multiConnectionBoardSendMode.first(),
        )

        preferences.setMultiConnectionBoardSendMode(BoardSendMode.AUTOMATIC)

        assertEquals(
            BoardSendMode.AUTOMATIC,
            preferences.multiConnectionBoardSendMode.first(),
        )
    }

    /** A mode from a future build reads as the safe one, not the loud one. */
    @Test
    fun `unknown persisted send mode falls back to manual`() {
        assertEquals(BoardSendMode.EXPLICIT, BoardSendMode.fromWire("FUTURE_MODE"))
        assertEquals(BoardSendMode.EXPLICIT, BoardSendMode.fromWire(null))
    }

    // ── The one-shot upgrade ───────────────────────────────────────────────

    /**
     * 0.2.1 had one global mode and no per-capacity distinction, so an
     * upgrading install carries a value written under the old meaning — or
     * none, which used to read as AUTOMATIC. Both have to become manual once.
     */
    @Test
    fun `the upgrade preserves an existing automatic choice`() = runTest {
        val (preferences, dataStore) = createPreferencesWithStore(backgroundScope)
        dataStore.edit { prefs ->
            prefs[PreferenceKeys.BOARD_SEND_MODE] = BoardSendMode.AUTOMATIC.name
        }

        preferences.migrateToManualSendDefaultIfNeeded()

        assertEquals(BoardSendMode.AUTOMATIC, preferences.singleConnectionBoardSendMode.first())
        assertEquals(BoardSendMode.AUTOMATIC, preferences.multiConnectionBoardSendMode.first())
    }

    /**
     * The whole reason it is flag-guarded rather than value-guarded: after it
     * has run, "already manual" and "deliberately chose manual" look identical,
     * so a second run would undo a later opt-in on every cold start.
     */
    @Test
    fun `a later deliberate automatic survives every further launch`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        preferences.migrateToManualSendDefaultIfNeeded()
        preferences.setSingleConnectionBoardSendMode(BoardSendMode.AUTOMATIC)

        repeat(3) { preferences.migrateToManualSendDefaultIfNeeded() }

        assertEquals(
            BoardSendMode.AUTOMATIC,
            preferences.singleConnectionBoardSendMode.first(),
        )
    }

    @Test
    fun `a fresh install keeps capacity-specific defaults through migration`() = runTest {
        val preferences = createTestUserPreferences(backgroundScope)

        assertEquals(BoardSendMode.AUTOMATIC, preferences.singleConnectionBoardSendMode.first())
        preferences.migrateToManualSendDefaultIfNeeded()
        assertEquals(BoardSendMode.AUTOMATIC, preferences.singleConnectionBoardSendMode.first())
        assertEquals(BoardSendMode.EXPLICIT, preferences.multiConnectionBoardSendMode.first())
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
