package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.kilter.FakeCatalogue
import com.cruxcoach.app.kilter.FakeLogbook
import com.cruxcoach.app.kilter.InMemoryKilterUploadLedger
import com.cruxcoach.app.kilter.KilterApi
import com.cruxcoach.app.kilter.KilterBulkOutcome
import com.cruxcoach.app.kilter.KilterClimbIdTables
import com.cruxcoach.app.kilter.KilterDeleteOutcome
import com.cruxcoach.app.kilter.KilterFailure
import com.cruxcoach.app.kilter.KilterLog
import com.cruxcoach.app.kilter.KilterLogImporter
import com.cruxcoach.app.kilter.KilterLogsOutcome
import com.cruxcoach.app.kilter.KilterTokens
import com.cruxcoach.app.kilter.KilterUploadApi
import com.cruxcoach.app.kilter.KilterUploadDiagnostics
import com.cruxcoach.app.kilter.KilterUploadEngine
import com.cruxcoach.app.kilter.KilterUploadTrigger
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.testing.FixedClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android's Kilter import choice (KilterSyncEngine.importLogs(oneTimeOnly)): "Import once" brings
 * the logbook in, then revokes the session at Kilter and forgets it here but keeps the user uuid,
 * with continuous sync off; "Sync continuously" keeps the session and turns it on. A failed import
 * keeps the session. Disconnecting ends continuous sync; "Upload to Kilter" starts an upload.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KilterImportModeTest {
    private val db = BrowseTestDb()

    @AfterTest fun close() = db.close()

    private class Secrets : SecretStore {
        val items = HashMap<String, ByteArray>()
        override fun read(name: String) = items[name]
        override fun write(name: String, value: ByteArray): Boolean { items[name] = value; return true }
        override fun delete(name: String) = items.remove(name) != null
    }

    /** Kilter's API: the logbook (empty, or a server error) and the session revocation. */
    private class Portal(var logsStatus: Int = 200) : HttpTransport {
        val requests = ArrayList<String>()
        var logsBody = "[]"
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int): HttpResult {
            requests += "$method $url"
            return when {
                method == "GET" && url.endsWith("/logs") -> HttpResult.Ok(HttpResponse(logsStatus, emptyMap(), logsBody.encodeToByteArray()))
                method == "POST" && url == KilterApi.TOKEN_URL -> HttpResult.Ok(HttpResponse(200, emptyMap(),
                    """{"access_token":"x.e30.y","refresh_token":"r2","expires_in":3600}""".encodeToByteArray()))
                method == "POST" && url == KilterApi.LOGOUT_URL -> HttpResult.Ok(HttpResponse(204, emptyMap(), ByteArray(0)))
                else -> HttpResult.Failed(HttpFailure.OTHER, url)
            }
        }
        override suspend fun download(url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit) =
            HttpResult.Failed(HttpFailure.OFFLINE, "")
    }

    private val prefs = MemoryKeyValueStore()
    private val settings = SettingsStore(prefs)
    private val tokens = KilterTokens(Secrets(), prefs, "abc").apply {
        store("a", "r", 4_000_000_000)
        storeUserUuid("user-1")
    }
    private val portal = Portal()

    private fun TestScope.model(): Pair<KilterScreenModel, KilterUploadEngine> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val api = object : KilterUploadApi {
            override suspend fun fetchLogs() = KilterLogsOutcome(emptyList<KilterLog>(), KilterFailure.NONE)
            override suspend fun uploadLogs(logs: List<KilterLog>) = KilterBulkOutcome(200, KilterFailure.NONE, true)
            override suspend fun deleteLogIfHeld(logUuid: String) = KilterDeleteOutcome.NOT_HELD
            override suspend fun deleteLog(logUuid: String) = KilterFailure.NONE
        }
        val engine = KilterUploadEngine(
            api, tokens, FakeLogbook(), FakeCatalogue(), InMemoryKilterUploadLedger(),
            KilterUploadDiagnostics(prefs, "abc") { 1_000L }, KilterClimbIdTables.EMPTY, "0.2.4", 204_001, clock = { 1_000L },
        )
        val model = KilterScreenModel(
            KilterApi(portal, FixedClock(1), tokens), tokens, KilterLogImporter(db.boardRepo, db.personalRepo),
            engine, CoroutineScope(SupervisorJob() + dispatcher), "0.2.4 (204001)", main = dispatcher, io = dispatcher,
        )
        model.syncSettings = settings
        return model to engine
    }

    @Test fun import_once_revokes_and_forgets_the_session_but_keeps_the_user() = runTest {
        val (model, _) = model()
        advanceUntilIdle()
        assertFalse(model.currentState.syncContinuously)

        model.importOnce()
        advanceUntilIdle()

        val s = model.currentState
        assertEquals("done", s.phase)
        assertEquals("none", s.failure)
        assertTrue(s.importedOnce)
        assertFalse(s.signedIn)
        assertFalse(tokens.isSignedIn)
        assertEquals("user-1", tokens.userUuid(), "own authored climbs stay claimable")
        assertTrue(portal.requests.contains("POST ${KilterApi.LOGOUT_URL}"), "the refresh token is revoked at Kilter")
        assertFalse(settings.snapshot.kilterSyncEnabled)
        assertFalse(s.syncContinuously)
        assertNotNull(settings.snapshot.kilterLastSync)
    }

    @Test fun sync_continuously_keeps_the_session_and_turns_the_start_sync_on() = runTest {
        val (model, _) = model()
        model.importContinuously()
        advanceUntilIdle()

        val s = model.currentState
        assertEquals("done", s.phase)
        assertFalse(s.importedOnce)
        assertTrue(s.signedIn)
        assertTrue(tokens.isSignedIn)
        assertTrue(portal.requests.none { it.startsWith("POST") }, "nothing is revoked")
        assertTrue(settings.snapshot.kilterSyncEnabled)
        assertTrue(s.syncContinuously)
        assertEquals(settings.snapshot.kilterLastSync, s.lastSync)

        // Android's disconnect ends continuous sync with the session.
        model.signOut()
        advanceUntilIdle()
        assertFalse(settings.snapshot.kilterSyncEnabled)
        assertFalse(model.currentState.syncContinuously)
        assertFalse(model.currentState.signedIn)
    }

    @Test fun a_failed_import_keeps_the_session_and_the_choice_open() = runTest {
        portal.logsStatus = 500
        val (model, _) = model()
        model.importOnce()
        advanceUntilIdle()

        val s = model.currentState
        assertEquals("serverError", s.failure)
        assertTrue(s.signedIn)
        assertTrue(tokens.isSignedIn)
        assertFalse(s.importedOnce)
        assertTrue(portal.requests.none { it.startsWith("POST") })
        assertFalse(settings.snapshot.kilterSyncEnabled)
        assertNull(settings.snapshot.kilterLastSync)
    }

    @Test fun a_sign_in_shows_androids_preview_before_the_choice() = runTest {
        // One of the two logs is here already: the preview counts it as imported.
        portal.logsBody = """[
            {"logUuid":"log-1","climbUuid":"climb-1","angle":40,"topped":true,"attempts":1,"createdAt":"2026-09-19 18:30:00"},
            {"logUuid":"log-2","climbUuid":"climb-2","angle":40,"topped":false,"attempts":3,"createdAt":"2026-09-20 18:30:00"}
        ]"""
        KilterLogImporter(db.boardRepo, db.personalRepo).import(listOf(
            KilterLog(logUuid = "log-1", climbUuid = "climb-1", angle = 40, topped = true, createdAt = "2026-09-19 18:30:00"),
        ))
        tokens.clear()
        val (model, _) = model()
        assertFalse(model.currentState.signedIn)

        model.signIn("me@example.org", "secret")
        advanceUntilIdle()

        val s = model.currentState
        assertTrue(s.signedIn)
        assertEquals("none", s.failure)
        assertTrue(s.previewLoaded)
        assertEquals(2, s.previewTotal)
        assertEquals(1, s.previewNew)
        assertEquals(1, s.previewDuplicates)
        assertFalse(s.busy)
        assertEquals(1, db.personalRepo.getExistingLogUuids().size, "a preview writes nothing")
    }

    @Test fun a_failed_preview_keeps_the_sign_in_and_says_so() = runTest {
        portal.logsStatus = 503
        tokens.clear()
        val (model, _) = model()
        model.signIn("me@example.org", "secret")
        advanceUntilIdle()

        val s = model.currentState
        assertTrue(s.signedIn)
        assertTrue(s.previewFailed)
        assertEquals("serverError", s.failure)
        assertFalse(s.previewLoaded)
        model.dismissFailure()
        assertFalse(model.currentState.previewFailed)
    }

    @Test fun switching_upload_on_starts_an_upload() = runTest {
        val (model, engine) = model()
        model.importContinuously()
        advanceUntilIdle()
        assertFalse(model.currentState.pushEnabled)

        model.setPushEnabled(true)
        advanceUntilIdle()
        assertTrue(settings.snapshot.kilterPushEnabled)
        assertTrue(model.currentState.pushEnabled)
        assertEquals(KilterUploadTrigger.ENABLED, engine.uploadStatus.value?.trigger)

        model.setPushEnabled(false)
        advanceUntilIdle()
        assertFalse(settings.snapshot.kilterPushEnabled)
    }
}
