package com.cruxcoach.app.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.kilter.FakeCatalogue
import com.cruxcoach.app.kilter.FakeLogbook
import com.cruxcoach.app.kilter.InMemoryKilterUploadLedger
import com.cruxcoach.app.kilter.KilterApi
import com.cruxcoach.app.kilter.KilterCircuitFetcher
import com.cruxcoach.app.kilter.KilterCircuitImporter
import com.cruxcoach.app.kilter.KilterCircuitLineSource
import com.cruxcoach.app.kilter.KilterCircuitStreamOutcome
import com.cruxcoach.app.kilter.KilterClimbIdTables
import com.cruxcoach.app.kilter.KilterFailure
import com.cruxcoach.app.kilter.KilterLogImporter
import com.cruxcoach.app.kilter.KilterTokens
import com.cruxcoach.app.kilter.KilterUploadDiagnostics
import com.cruxcoach.app.kilter.KilterUploadEngine
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Import" on the Kilter screen also brings the account's circuits in as climb lists, as
 * Android's sync engine does with every import; the count reaches the done line. A failing
 * circuit fetch never fails the log import.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KilterScreenCircuitsTest {
    private class Secrets : SecretStore {
        val items = HashMap<String, ByteArray>()
        override fun read(name: String) = items[name]
        override fun write(name: String, value: ByteArray): Boolean { items[name] = value; return true }
        override fun delete(name: String) = items.remove(name) != null
    }

    /** The portal answers the logbook with an empty list. */
    private val portal = object : HttpTransport {
        override suspend fun request(method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int) =
            if (url.endsWith("/logs")) HttpResult.Ok(HttpResponse(200, emptyMap(), """{"logs":[]}""".encodeToByteArray()))
            else HttpResult.Failed(HttpFailure.OFFLINE, "")
        override suspend fun download(url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit) =
            HttpResult.Failed(HttpFailure.OFFLINE, "")
    }

    private class Stream(val lines: List<String>, val status: Int) : KilterCircuitLineSource {
        override suspend fun streamLines(
            url: String, headers: Map<String, String>, body: ByteArray, timeoutSeconds: Int, maxBytes: Long, onLine: (String) -> Boolean,
        ): KilterCircuitStreamOutcome {
            if (status == 200) for (line in lines) if (onLine(line)) break
            return KilterCircuitStreamOutcome(status, KilterFailure.NONE)
        }
    }

    private val circuitLines = listOf(
        """{"checkpoint":{"buckets":[{"bucket":"circuit_buckets[\"c-1\"]","count":2}]}}""",
        """{"data":{"bucket":"circuit_buckets[\"c-1\"]","data":[""" +
            """{"op":"PUT","object_type":"circuits","object_id":"c-1","data":"{\"circuit_uuid\":\"c-1\",\"name\":\"Warm-ups\",\"color\":\"00FF00\"}"},""" +
            """{"op":"PUT","object_type":"circuit_climbs","object_id":"c-1.x","data":"{\"circuit_uuid\":\"c-1\",\"climb_uuid\":\"x\",\"sort_order\":0}"}""" +
            """]}}""",
    )

    private fun screen(stream: Stream, secure: SecureDatabase, dispatcher: kotlinx.coroutines.CoroutineDispatcher, db: BrowseTestDb): Pair<KilterScreenModel, CoroutineScope> {
        val prefs = MemoryKeyValueStore()
        val clock = FixedClock(1_700_000_000)
        val tokens = KilterTokens(Secrets(), prefs, "abc").apply { store("a", "r", clock.seconds + 3_600); storeUserUuid("user-1") }
        val api = KilterApi(portal, clock, tokens)
        val engine = KilterUploadEngine(
            api, tokens, FakeLogbook(), FakeCatalogue(), InMemoryKilterUploadLedger(), KilterUploadDiagnostics(prefs, "abc") { 1_000L },
            KilterClimbIdTables.EMPTY, "0.2.4", 204_001, clock = { 1_000L },
        )
        val runScope = CoroutineScope(SupervisorJob() + dispatcher)
        val model = KilterScreenModel(api, tokens, KilterLogImporter(db.boardRepo, db.personalRepo), engine, runScope, "0.2.4 (204001)",
            main = dispatcher, io = dispatcher)
        model.circuitImporter = KilterCircuitImporter(KilterCircuitFetcher(stream, tokens, clock), secure)
        return model to runScope
    }

    @Test
    fun import_brings_circuits_in_as_lists_and_counts_them() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = BrowseTestDb()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
        val secure = SecureDatabase(driver)
        val (model, runScope) = screen(Stream(circuitLines, 200), secure, dispatcher, db)
        model.importLogs()
        advanceUntilIdle()
        assertEquals("done", model.currentState.phase)
        assertEquals("none", model.currentState.failure)
        assertEquals(1, model.currentState.circuits)
        val list = secure.climbListsQueries.getAllClimbLists().executeAsList().single { it.external_id == "kilter-api:circuit:c-1" }
        assertEquals("Warm-ups", list.name)
        assertEquals("00FF00", list.color)
        assertEquals(listOf("x"), secure.climbListsQueries.getAllClimbUuidsInList(list.id).executeAsList())
        model.close()
        runScope.cancel()
        driver.close()
        db.close()
    }

    @Test
    fun a_failing_circuit_fetch_leaves_the_log_import_done() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val db = BrowseTestDb()
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
        val (model, runScope) = screen(Stream(emptyList(), 500), SecureDatabase(driver), dispatcher, db)
        model.importLogs()
        advanceUntilIdle()
        assertEquals("done", model.currentState.phase)
        assertEquals("none", model.currentState.failure)
        assertEquals(0, model.currentState.circuits)
        model.close()
        runScope.cancel()
        driver.close()
        db.close()
    }
}
