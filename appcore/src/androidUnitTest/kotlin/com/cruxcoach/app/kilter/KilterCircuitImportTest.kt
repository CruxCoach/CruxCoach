package com.cruxcoach.app.kilter

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.testing.FixedClock
import com.cruxcoach.db.secure.SecureDatabase
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The request plumbing of Android's KilterCircuitsHttpTest (POST to /sync/stream, Bearer token,
 * ndjson, first-sync body, stop once the circuit buckets drained, no call without a token, HTTP
 * error) against a fake line source, and Android's KilterCircuitImporter writes against a real
 * secure database: FEAT-005 identity (external id, colour, description) and a re-import that
 * refreshes metadata and membership instead of adding a second list.
 */
class KilterCircuitImportTest {
    private val driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SecureDatabase.Schema.create(it) }
    private val db = SecureDatabase(driver)
    private val clock = FixedClock(1_700_000_000)

    @AfterTest
    fun tearDown() = driver.close()

    private class Secrets : SecretStore {
        val items = HashMap<String, ByteArray>()
        override fun read(name: String) = items[name]
        override fun write(name: String, value: ByteArray): Boolean { items[name] = value; return true }
        override fun delete(name: String) = items.remove(name) != null
    }

    private fun tokens(access: String? = "test-token", expiresAt: Long = clock.seconds + 3600) =
        KilterTokens(Secrets(), MemoryKeyValueStore(), "abc").apply { if (access != null) store(access, "refresh", expiresAt) }

    /** Serves [lines] (or [status] without a body) and records what was asked and how far it was read. */
    private class FakeSource(val lines: List<String>, val status: Int = 200, val failure: KilterFailure = KilterFailure.NONE) : KilterCircuitLineSource {
        var calls = 0
        var url = ""
        var headers: Map<String, String> = emptyMap()
        var body = ""
        var linesRead = 0

        override suspend fun streamLines(
            url: String, headers: Map<String, String>, body: ByteArray, timeoutSeconds: Int, maxBytes: Long, onLine: (String) -> Boolean,
        ): KilterCircuitStreamOutcome {
            calls++
            this.url = url
            this.headers = headers
            this.body = body.decodeToString()
            if (failure != KilterFailure.NONE) return KilterCircuitStreamOutcome(0, failure)
            if (status in 200..299) {
                for (line in lines) {
                    linesRead++
                    if (onLine(line)) break
                }
            }
            return KilterCircuitStreamOutcome(status, KilterFailure.NONE)
        }
    }

    private val streamed = listOf(
        """{"checkpoint":{"buckets":[{"bucket":"circuit_buckets_c1","count":2},{"bucket":"global_climbs[]","count":31000}]}}""",
        """{"data":{"bucket":"circuit_buckets_c1","data":[""" +
            """{"op":"PUT","object_type":"circuits","object_id":"c-1","data":{"circuit_uuid":"c-1","name":"test","description":"Warm-ups","color":"FF0000","user_uuid":"user-123","created_at":"2026-07-11 10:00:00"}},""" +
            """{"op":"PUT","object_type":"circuit_climbs","object_id":"c-1.climb-a","data":{"circuit_uuid":"c-1","climb_uuid":"climb-a","sort_order":1}}""" +
            """]}}""",
        """{"data":{"bucket":"global_climbs[]","data":[]}}""",
        """{"checkpoint_complete":{"last_op_id":"2"}}""",
    )

    @Test
    fun fetch_streams_powersync_and_posts_to_sync_stream() = runBlocking {
        val source = FakeSource(streamed)
        val outcome = KilterCircuitFetcher(source, tokens(), clock, syncUrl = "https://sync.test/sync/stream").fetchCircuits()

        assertEquals(KilterFailure.NONE, outcome.failure)
        val c = outcome.circuits.single()
        assertEquals("c-1", c.circuitUuid)
        assertEquals("test", c.name)
        assertEquals("FF0000", c.color)
        assertEquals(listOf("climb-a"), c.memberClimbUuids())
        assertEquals("https://sync.test/sync/stream", source.url)
        assertEquals("Bearer test-token", source.headers["Authorization"])
        assertEquals("application/x-ndjson", source.headers["Accept"])
        assertTrue(source.body.contains("\"buckets\":[]"), "a first-sync request: no local bucket state")
        assertTrue(source.body.contains("\"raw_data\":true"))
        assertTrue(Regex("\"client_id\":\"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\"").containsMatchIn(source.body))
        assertEquals(2, source.linesRead, "stops once the circuit bucket drained, before global_climbs")
    }

    @Test
    fun fetch_is_empty_when_no_circuit_buckets() = runBlocking {
        val source = FakeSource(listOf(
            """{"checkpoint":{"buckets":[{"bucket":"global_climbs[]","count":31000},{"bucket":"global_gyms[]","count":900}]}}""",
            """{"data":{"bucket":"global_climbs[]","data":[]}}""",
        ))
        val outcome = KilterCircuitFetcher(source, tokens(), clock).fetchCircuits()
        assertEquals(KilterFailure.NONE, outcome.failure)
        assertTrue(outcome.circuits.isEmpty())
        assertEquals(1, source.linesRead)
    }

    @Test
    fun fetch_without_a_valid_token_makes_no_call() = runBlocking {
        val none = FakeSource(streamed)
        assertEquals(KilterFailure.NOT_SIGNED_IN, KilterCircuitFetcher(none, tokens(access = null), clock).fetchCircuits().failure)
        val expired = FakeSource(streamed)
        assertEquals(KilterFailure.NOT_SIGNED_IN,
            KilterCircuitFetcher(expired, tokens(expiresAt = clock.seconds + 30), clock).fetchCircuits().failure)
        assertEquals(0, none.calls + expired.calls, "no token → no HTTP call")
    }

    @Test
    fun fetch_http_errors_are_failures() = runBlocking {
        assertEquals(KilterFailure.SERVER_ERROR, KilterCircuitFetcher(FakeSource(emptyList(), status = 500), tokens(), clock).fetchCircuits().failure)
        assertEquals(KilterFailure.NOT_SIGNED_IN, KilterCircuitFetcher(FakeSource(emptyList(), status = 401), tokens(), clock).fetchCircuits().failure)
        assertEquals(KilterFailure.OFFLINE,
            KilterCircuitFetcher(FakeSource(emptyList(), failure = KilterFailure.OFFLINE), tokens(), clock).fetchCircuits().failure)
    }

    @Test
    fun the_line_cap_stops_a_stream_that_never_drains() = runBlocking {
        val endless = listOf("""{"checkpoint":{"buckets":[{"bucket":"circuit_buckets_x","count":5}]}}""") +
            List(KilterCircuitFetcher.SYNC_MAX_LINES + 50) { """{"token_expires_in":60}""" }
        val source = FakeSource(endless)
        val outcome = KilterCircuitFetcher(source, tokens(), clock).fetchCircuits()
        assertEquals(KilterFailure.NONE, outcome.failure)
        assertEquals(KilterCircuitFetcher.SYNC_MAX_LINES, source.linesRead)
    }

    private class ListRow(val id: Long, val name: String, val description: String?, val color: String?, val externalId: String?, val createdAt: String)

    private fun lists(): List<ListRow> = db.climbListsQueries.getAllClimbLists().executeAsList()
        .map { ListRow(it.id, it.name, it.description, it.color, it.external_id, it.created_at) }

    private fun members(listId: Long): List<String> =
        db.climbListsQueries.getAllClimbUuidsInList(listId).executeAsList()

    @Test
    fun circuits_become_climb_lists_and_a_reimport_refreshes_them() = runBlocking {
        val importer = KilterCircuitImporter(KilterCircuitFetcher(FakeSource(streamed), tokens(), clock), db) { "2026-10-18T07:00:00" }
        assertEquals(1, importer.importCircuits())
        val first = lists().single { it.externalId == "kilter-api:circuit:c-1" }
        assertEquals("test", first.name)
        assertEquals("Warm-ups", first.description)
        assertEquals("FF0000", first.color)
        assertEquals("2026-07-11 10:00:00", first.createdAt)
        assertEquals(listOf("climb-a"), members(first.id))

        // Edited on Kilter: renamed, recoloured, one member swapped and one added.
        val edited = KilterCircuit(
            circuitUuid = "c-1", name = "test 2", description = null, color = "00FF00",
            circuitClimbs = listOf(KilterCircuitClimb("climb-c", 2), KilterCircuitClimb("climb-b", 1)),
        )
        val blank = KilterCircuit(circuitUuid = " ", name = "ignored")
        assertEquals(1, importer.write(listOf(edited, blank)))
        val refreshed = lists().filter { it.externalId?.startsWith(KilterCircuitImporter.EXTERNAL_PREFIX) == true }
        assertEquals(1, refreshed.size, "one list per circuit, never a second copy")
        val again = refreshed.single()
        assertEquals(first.id, again.id)
        assertEquals("test 2", again.name)
        assertEquals(null, again.description)
        assertEquals("00FF00", again.color)
        assertEquals(setOf("climb-b", "climb-c"), members(again.id).toSet())

        // A circuit without created_at gets the import time.
        assertEquals(1, importer.write(listOf(KilterCircuit(circuitUuid = "c-2", name = "Liked Climbs"))))
        assertEquals("2026-10-18T07:00:00", lists().single { it.externalId == "kilter-api:circuit:c-2" }.createdAt)
    }

    @Test
    fun a_failed_fetch_writes_nothing_and_is_not_fatal() = runBlocking {
        val importer = KilterCircuitImporter(KilterCircuitFetcher(FakeSource(emptyList(), status = 503), tokens(), clock), db)
        assertEquals(0, importer.importCircuits())
        assertTrue(lists().none { it.externalId?.startsWith(KilterCircuitImporter.EXTERNAL_PREFIX) == true })
    }
}
