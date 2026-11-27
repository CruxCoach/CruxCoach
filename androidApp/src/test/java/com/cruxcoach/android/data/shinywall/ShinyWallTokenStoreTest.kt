package com.cruxcoach.android.data.shinywall

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The store's own rules, on plain preferences (the Keystore is not there in a JVM test). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ShinyWallTokenStoreTest {

    private val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("shinywall_test", Context.MODE_PRIVATE)
    private fun store(prefix: String = "abcd") = ShinyWallTokenStore(prefix) { prefs }
    private val session = ShinyWallSession("uid1", "id", "refresh", 1_000L, "a@b.c")

    @Test
    fun a_refresh_that_ends_after_a_disconnect_leaves_no_token_behind() {
        val store = store()
        store.storeSession(session)
        store.clearSession()
        store.updateTokens("uid1", "id2", "refresh2", 3600)
        store.updateDisplayName("uid1", "climber")
        assertNull(store.session())
        assertTrue(prefs.all.keys.none { it.endsWith("_refresh_token") || it.endsWith("_id_token") || it.endsWith("_display_name") })

        // An import that ends after the disconnect keeps its record, not the time of the last import.
        store.recordImport("uid1", 5L, 100L, mapOf("log" to 90L))
        assertNull(store.lastImportAtMs())
        assertEquals(100L, store.historyWatermarkMs("uid1"))
    }

    @Test
    fun the_record_keeps_the_count_each_project_row_was_written_with() {
        val store = store()
        store.recordImport("uid1", 5L, 100L, mapOf("a" to 90L, "b" to 95L), mapOf("b" to 2L, "gone" to 3L))
        assertEquals(mapOf("a" to 90L, "b" to 95L), store.placedLogs("uid1"))
        // Only for placed entries: the record keeps one window.
        assertEquals(mapOf("b" to 2L), store.writtenCounts("uid1"))
    }

    @Test
    fun a_refresh_of_another_account_leaves_the_session_alone() {
        val store = store()
        store.storeSession(session.copy(uid = "uid2"))
        store.updateTokens("uid1", "id2", "refresh2", 3600)
        assertEquals("refresh", store.session()!!.refreshToken)

        store.updateTokens("uid2", "id2", "refresh2", 3600)
        assertEquals("id2" to "refresh2", store.session()!!.let { it.idToken to it.refreshToken })
    }

    @Test
    fun resetting_the_records_keeps_the_session_and_other_identities() {
        val store = store()
        val other = store("efgh")
        store.storeSession(session)
        for (s in listOf(store, other)) {
            s.recordImport("uid1", 5L, 100L, mapOf("log" to 90L))
            s.recordImportedLists("uid1", setOf("shinywall:favorites"))
        }

        store.resetImportRecords()
        assertNull(store.historyWatermarkMs("uid1"))
        assertTrue(store.placedLogs("uid1").isEmpty())
        assertTrue(store.importedLists("uid1").isEmpty())
        assertEquals(session, store.session())

        assertEquals(100L, other.historyWatermarkMs("uid1"))
        assertEquals(mapOf("log" to 90L), other.placedLogs("uid1"))
        assertEquals(setOf("shinywall:favorites"), other.importedLists("uid1"))
    }
}
