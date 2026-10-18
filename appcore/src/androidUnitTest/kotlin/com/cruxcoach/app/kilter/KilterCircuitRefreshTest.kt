package com.cruxcoach.app.kilter

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.background.BackgroundRefresh
import com.cruxcoach.app.background.KilterCircuitStep
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android's app-start Kilter sync brings the circuits along (`syncOnAppStartIfEnabled`:
 * credentials, token refresh, `circuitImporter.importCircuits()`); on iOS that is the background
 * refresh's circuit step: once per start, only while signed in, after a token refresh.
 */
class KilterCircuitRefreshTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    private val db = SecureDatabase(driver)
    private val clock = FixedClock(1_700_000_000)
    private val secrets = Secrets()
    private val prefs = MemoryKeyValueStore()
    private val tokens = KilterTokens(secrets, prefs, "abc")
    private val http = TokenEndpoint()
    private val api = KilterApi(http, clock, tokens, tokenUrl = "https://idp.test/token")

    @AfterTest
    fun tearDown() = driver.close()

    private class Secrets : SecretStore {
        val items = HashMap<String, ByteArray>()
        override fun read(name: String) = items[name]?.copyOf()
        override fun write(name: String, value: ByteArray): Boolean { items[name] = value.copyOf(); return true }
        override fun delete(name: String) = items.remove(name) != null
    }

    /** The token endpoint: answers a refresh with [answer], counts the calls. */
    private class TokenEndpoint : HttpTransport {
        var calls = 0
        var answer: HttpResult = HttpResult.Failed(HttpFailure.OFFLINE, "offline")

        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Long, timeoutSeconds: Int,
        ): HttpResult {
            calls++
            return answer
        }

        override suspend fun download(
            url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit,
        ): HttpResult = HttpResult.Failed(HttpFailure.OTHER, "not used")
    }

    /** The PowerSync stream with one circuit of two climbs; records the bearer it was sent. */
    private class Stream : KilterCircuitLineSource {
        var calls = 0
        var bearer = ""

        override suspend fun streamLines(
            url: String, headers: Map<String, String>, body: ByteArray, timeoutSeconds: Int, maxBytes: Long, onLine: (String) -> Boolean,
        ): KilterCircuitStreamOutcome {
            calls++
            bearer = headers["Authorization"].orEmpty()
            for (line in LINES) if (onLine(line)) break
            return KilterCircuitStreamOutcome(200, KilterFailure.NONE)
        }

        companion object {
            val LINES = listOf(
                """{"checkpoint":{"buckets":[{"bucket":"circuit_buckets_c1","count":3}]}}""",
                """{"data":{"bucket":"circuit_buckets_c1","data":[""" +
                    """{"op":"PUT","object_type":"circuits","object_id":"c-1","data":{"circuit_uuid":"c-1","name":"Warm-ups","color":"00FF00","user_uuid":"user-1","created_at":"2026-07-11 10:00:00"}},""" +
                    """{"op":"PUT","object_type":"circuit_climbs","object_id":"c-1.a","data":{"circuit_uuid":"c-1","climb_uuid":"climb-a","sort_order":2}},""" +
                    """{"op":"PUT","object_type":"circuit_climbs","object_id":"c-1.b","data":{"circuit_uuid":"c-1","climb_uuid":"climb-b","sort_order":1}}""" +
                    """]}}""",
                """{"checkpoint_complete":{"last_op_id":"3"}}""",
            )
        }
    }

    private val stream = Stream()

    /** A fresh step per app start, wired as the composition root wires it. */
    private fun step(): KilterCircuitStep {
        val importer = KilterCircuitImporter(KilterCircuitFetcher(stream, tokens, clock), db)
        return KilterCircuitStep(
            signedIn = { tokens.hasCredentials() },
            freshToken = { api.ensureAccessToken() },
            importCircuits = { importer.importCircuits() },
        )
    }

    private fun refresh(step: KilterCircuitStep, keyValues: MemoryKeyValueStore = MemoryKeyValueStore()) =
        BackgroundRefresh(listOf(step), keyValues, { clock.epochMillis() }, kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined), Dispatchers.Unconfined)

    private fun circuitList(): Pair<String, Set<String>>? {
        val id = db.climbListsQueries.findClimbListByExternalId("kilter-api:circuit:c-1").executeAsOneOrNull() ?: return null
        val name = db.climbListsQueries.getClimbListById(id).executeAsOne().name
        val climbs = db.climbListsQueries.getAllClimbUuidsInList(id).executeAsList().toSet()
        return name to climbs
    }

    @Test
    fun `signed out nothing is fetched and the step is skipped`() = runBlocking {
        val summary = refresh(step()).runSteps(BackgroundRefresh.TRIGGER_FOREGROUND)
        assertEquals(listOf(KilterCircuitStep.CODE), summary.skipped)
        assertEquals(0, stream.calls)
        assertEquals(0, http.calls)
        assertEquals(null, circuitList())
    }

    @Test
    fun `signed in the circuits become a climb list once per start`() = runBlocking {
        tokens.store("access-1", "refresh-1", clock.seconds + 3600)
        val keyValues = MemoryKeyValueStore()
        val step = step()
        val refresh = refresh(step, keyValues)

        val first = refresh.runSteps(BackgroundRefresh.TRIGGER_BACKGROUND)
        assertEquals(listOf(KilterCircuitStep.CODE), first.ran)
        assertEquals(1, stream.calls)
        assertEquals("Bearer access-1", stream.bearer)
        // A valid token is not refreshed.
        assertEquals(0, http.calls)
        assertEquals("Warm-ups" to setOf("climb-a", "climb-b"), circuitList())

        // Every later return to the foreground of the same start: Android syncs once per start.
        clock.seconds += 3 * 3600
        val again = refresh.runSteps(BackgroundRefresh.TRIGGER_FOREGROUND)
        assertEquals(listOf(KilterCircuitStep.CODE), again.skipped)
        assertEquals(1, stream.calls)

        // The next start (a new core) imports again; the list is refreshed, not duplicated.
        tokens.store("access-2", "refresh-1", clock.seconds + 3600)
        val nextStart = refresh(step(), keyValues).runSteps(BackgroundRefresh.TRIGGER_FOREGROUND)
        assertEquals(listOf(KilterCircuitStep.CODE), nextStart.ran)
        assertEquals(2, stream.calls)
        assertEquals(1, db.climbListsQueries.getAllClimbLists().executeAsList().size)
    }

    @Test
    fun `an expired token is refreshed first and the stream gets the new one`() = runBlocking {
        tokens.store("old-access", "refresh-1", clock.seconds - 10)
        http.answer = HttpResult.Ok(
            HttpResponse(200, emptyMap(), """{"access_token":"new-access","refresh_token":"refresh-2","expires_in":300}""".encodeToByteArray()),
        )
        refresh(step()).runSteps(BackgroundRefresh.TRIGGER_FOREGROUND)
        assertEquals(1, http.calls)
        assertEquals("Bearer new-access", stream.bearer)
        assertEquals("refresh-2", tokens.refreshToken())
        assertTrue(circuitList() != null)
    }

    @Test
    fun `offline or a refused refresh skips the circuits until the next start`() = runBlocking {
        tokens.store("old-access", "refresh-1", clock.seconds - 10)
        val step = step()
        val keyValues = MemoryKeyValueStore()
        val offline = refresh(step, keyValues).runSteps(BackgroundRefresh.TRIGGER_FOREGROUND)
        // Not a failure of the refresh, and the expired token never goes to the stream.
        assertTrue(offline.success)
        assertEquals(0, stream.calls)
        assertEquals(null, circuitList())
        assertTrue(tokens.hasCredentials(), "offline is no expired session")

        http.answer = HttpResult.Ok(HttpResponse(400, emptyMap(), """{"error":"invalid_grant"}""".encodeToByteArray()))
        refresh(step(), keyValues).runSteps(BackgroundRefresh.TRIGGER_FOREGROUND)
        assertEquals(0, stream.calls)
        assertEquals(2, http.calls)
    }

    @Test
    fun `ensureAccessToken answers without a session and keeps a valid token`() = runBlocking {
        assertFalse(api.ensureAccessToken())
        assertEquals(0, http.calls)
        tokens.store("valid", "refresh", clock.seconds + 3600)
        assertTrue(api.ensureAccessToken())
        assertEquals(0, http.calls)
    }
}
