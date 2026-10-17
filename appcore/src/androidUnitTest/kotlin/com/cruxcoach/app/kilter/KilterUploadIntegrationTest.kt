package com.cruxcoach.app.kilter

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.WallClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload as the app wires it: the engine over the real SQLite logbook and
 * catalogue, [KilterApi] over a transport double, the preferences ledger and
 * the bundled id tables. Never run against the real portal: every assertion
 * is about the request that would be sent.
 */
class KilterUploadIntegrationTest {
    private class Secrets : SecretStore {
        private val items = HashMap<String, ByteArray>()
        override fun read(name: String) = items[name]?.copyOf()
        override fun write(name: String, value: ByteArray): Boolean { items[name] = value.copyOf(); return true }
        override fun delete(name: String) = items.remove(name) != null
    }

    private class Clock : WallClock {
        var millis = 1_790_000_000_000L
        override fun epochSeconds() = millis / 1000
        override fun epochMillis() = millis
    }

    /** Kilter in one class: logs by uuid, the climbs it knows in its own spelling. */
    private inner class Http : HttpTransport {
        val logs = LinkedHashMap<String, String>()
        var known = setOf<String>()
        val posts = ArrayList<String>()
        val deletions = ArrayList<String>()
        var deleteStatus = 404
        var beforeAnswer: () -> Unit = {}

        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?,
            maxResponseBytes: Long, timeoutSeconds: Int,
        ): HttpResult = when {
            method == "DELETE" -> {
                deletions += url.substringAfterLast('/')
                HttpResult.Ok(HttpResponse(deleteStatus, emptyMap(), ByteArray(0)))
            }
            method == "GET" && url.endsWith("/logs") ->
                HttpResult.Ok(HttpResponse(200, emptyMap(), "[${logs.values.joinToString(",")}]".encodeToByteArray()))
            method == "POST" && url.endsWith("/logs/bulk") -> {
                val text = body!!.decodeToString()
                posts += text
                beforeAnswer()
                val rows = Json.parseToJsonElement(text) as JsonArray
                val ids = rows.map { ((it as JsonObject).getValue("climbUuid") as JsonPrimitive).content }
                val status = if (ids.all { it in known }) 200 else 500
                if (status == 200) for (row in rows) logs[((row as JsonObject).getValue("logUuid") as JsonPrimitive).content] = row.toString()
                HttpResult.Ok(HttpResponse(status, emptyMap(), ByteArray(0)))
            }
            else -> HttpResult.Failed(HttpFailure.OTHER, "unexpected $method $url")
        }

        override suspend fun download(
            url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int, onProgress: (Long, Long) -> Unit,
        ): HttpResult = error("not used")
    }

    private val db = BrowseTestDb()
    private val repo = db.personalRepo
    private val http = Http()
    private val clock = Clock()
    private val prefs = MemoryKeyValueStore()
    private val tokens = KilterTokens(Secrets(), prefs, "abc123")
    private val api = KilterApi(http, clock, tokens)

    private fun engine() = KilterUploadEngine(
        api = api, session = tokens,
        logbook = RepositoryKilterUploadLogbook(repo), catalogue = RepositoryKilterUploadCatalogue(db.boardRepo),
        ledger = KeyValueKilterUploadLedger(prefs, "abc123"),
        diagnostics = KilterUploadDiagnostics(prefs, "abc123") { clock.millis },
        idTables = BundledKilterClimbIdTables(BundledKilterTables.files, BundledKilterTables.directory.path),
        appVersionName = "0.2.4", appVersionCode = 204_001, clock = { clock.millis },
    )

    @AfterTest fun close() = db.close()

    private fun signIn() {
        tokens.store(access = "a", refresh = "r", expiresAtEpochSeconds = clock.epochSeconds() + 3600)
        tokens.storeUserUuid("user-1")
    }

    private fun logSend(uuid: String, climb: String, tries: Long, at: String = "2026-09-19 18:30:00") = repo.insertAscent(
        uuid = uuid, climbUuid = climb, angle = 40L, isMirror = false, attemptId = 0,
        bidCount = tries, quality = 3, difficulty = null, isBenchmark = false, comment = null,
        climbedAt = at, synced = false, climbName = climb,
        difficultyAverage = 20.0, climbFrames = "p1r12", framesCount = 1,
    )

    private fun logAttempt(uuid: String, climb: String) = repo.insertBid(
        uuid = uuid, climbUuid = climb, angle = 40L, isMirror = false, bidCount = 3,
        comment = null, climbedAt = "2026-09-19 19:00:00", synced = false,
        climbName = climb, difficultyAverage = 20.0,
    )

    /** One log upstream is what makes the wall knowable. */
    private fun accountHasAWall() {
        http.logs["old"] = """{"logUuid":"old","climbUuid":"c0","angle":40,"topped":true,"attempts":1,""" +
            """"createdAt":"2026-01-01T10:00:00Z","gymUuid":"g1","wallUuid":"w1","productLayoutUuid":"p1"}"""
    }

    @Test fun sends_and_attempts_go_up_with_the_wall_the_account_already_uses() = runTest {
        signIn()
        accountHasAWall()
        http.known = setOf("climb-1", "climb-2")
        logSend("a1", "climb-1", tries = 1)
        logAttempt("b1", "climb-2")

        val status = engine().upload()
        assertEquals(KilterUploadReason.NONE, status.reason)
        assertEquals(2, status.uploaded)
        assertEquals(0, status.pending)
        val body = http.posts.single()
        assertTrue("\"gymUuid\":\"g1\"" in body, body)
        assertTrue("\"wallUuid\":\"w1\"" in body, body)
        // A send is topped; a first-go send is a flash; an attempt is neither.
        assertTrue("\"topped\":true" in body && "\"flashed\":true" in body && "\"topped\":false" in body, body)
        // The stored local time becomes the instant it was on this device.
        val sent = KilterLogUploadPlan.kilterTimestamp("2026-09-19 18:30:00")!!
        assertTrue("\"createdAt\":\"$sent\"" in body, body)
        assertTrue(repo.getUnsyncedAscents().isEmpty())
        assertTrue(repo.getUnsyncedBids().isEmpty())
        assertEquals("w1", tokens.wallContext()?.wallUuid)
        assertEquals(0, engine().upload().requests, "nothing left: no request")
    }

    @Test fun legacy_climbs_from_the_catalogue_go_out_in_the_case_kilter_stores() = runTest {
        // The 0.2.3 bug: every compact id went up uppercased, so a lowercase
        // legacy climb failed its whole 200-row request on every trigger.
        signIn()
        accountHasAWall()
        val lowercase = "a046c7f911034fda84faa329380f6c98"
        val uppercase = "D0E5387D5B974D38B4E93FC4DFD61EF6"
        http.known = setOf(lowercase, uppercase)
        logSend("a1", lowercase, tries = 2, at = "2026-09-19 18:30:00")
        logSend("a2", uppercase.lowercase(), tries = 2, at = "2026-09-19 18:40:00")

        val status = engine().upload()
        assertEquals(2, status.uploaded)
        assertEquals(1, status.requests)
        assertTrue(lowercase in http.posts.single() && uppercase in http.posts.single(), http.posts.single())
        // The logbook keeps its own spelling.
        assertEquals(setOf(lowercase, uppercase.lowercase()), repo.getUserLogbookAllLight().map { it.climbUuid }.toSet())
    }

    @Test fun a_refused_entry_no_longer_blocks_the_rest_and_stays_held_after_a_restart() = runTest {
        signIn()
        accountHasAWall()
        http.known = (1..5).map { "climb-$it" }.toSet()
        for (i in 1..5) logSend("ok-$i", "climb-$i", tries = 2, at = "2026-09-19 18:3$i:00")
        logSend("bad", "ghost-climb", tries = 2, at = "2026-09-19 19:00:00")

        val first = engine().upload()
        assertEquals(5, first.uploaded)
        assertEquals(1, first.rejectedByKilter, "Kilter accepted the rest after it: proof")
        assertEquals(0, first.pending)

        val restarted = engine().upload()
        assertEquals(0, restarted.requests, "the decision survives in the preferences")
        assertEquals(1, restarted.rejectedByKilter)
        val listed = engine().notUploadedEntries().single()
        assertEquals("bad", listed.logUuid)
        assertEquals(KilterNotUploadedReason.NOT_ON_KILTER, listed.reason)
        assertEquals("ghost-climb", listed.wireId)
    }

    @Test fun an_edit_made_during_the_upload_is_uploaded_again_rather_than_lost() = runTest {
        signIn()
        accountHasAWall()
        http.known = setOf("climb-1")
        logSend("a1", "climb-1", tries = 1)
        // The user edits the entry while the request is in flight.
        http.beforeAnswer = { repo.updateAscent("a1", bidCount = 4, quality = 3, comment = "revised"); http.beforeAnswer = {} }

        val status = engine().upload()
        assertEquals(1, status.uploaded)
        assertEquals(1, status.pending, "a stale stamp would drop the newer data")
        assertEquals(1, repo.getUnsyncedAscents().size)
    }

    @Test fun an_offline_deletion_stays_queued_until_kilter_confirms_it() = runTest {
        signIn()
        logSend("delete-me", "climb-1", tries = 1)
        repo.deleteAscent("delete-me")
        http.deleteStatus = 503
        engine().upload()
        assertEquals(listOf("delete-me"), repo.pendingLogDeletions())
        http.deleteStatus = 404
        engine().upload()
        assertTrue(repo.pendingLogDeletions().isEmpty())
        assertEquals(listOf("delete-me", "delete-me"), http.deletions)
        assertTrue(http.posts.isEmpty())
    }

    @Test fun a_signed_out_account_uploads_nothing() = runTest {
        logSend("a1", "climb-1", tries = 1)
        val status = engine().upload()
        assertEquals(KilterUploadReason.AUTHENTICATION, status.reason)
        assertTrue(http.posts.isEmpty())
    }
}
