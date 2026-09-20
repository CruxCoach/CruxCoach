package com.cruxcoach.app.kilter

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.logbook.newPersonalRepo
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.WallClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Pushing this device's logbook to the Kilter portal.
 *
 * Never run against the real portal from here: the transport is a double, and
 * every assertion is about the request that *would* be sent.
 */
class KilterUploaderTest {
    private class Secrets : SecretStore {
        private val items = HashMap<String, ByteArray>()
        override fun read(name: String) = items[name]?.copyOf()
        override fun write(name: String, value: ByteArray): Boolean {
            items[name] = value.copyOf(); return true
        }
        override fun delete(name: String) = items.remove(name) != null
    }

    private class Clock : WallClock {
        override fun epochSeconds() = 1_700_000_000L
        override fun epochMillis() = epochSeconds() * 1000
    }

    private class Http : HttpTransport {
        var remoteLogs = "[]"
        var uploadStatus = 200
        val posts = ArrayList<String>()
        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, body: ByteArray?,
            maxResponseBytes: Long, timeoutSeconds: Int,
        ): HttpResult = when {
            method == "GET" && url.endsWith("/logs") ->
                HttpResult.Ok(HttpResponse(200, emptyMap(), remoteLogs.encodeToByteArray()))
            method == "POST" && url.endsWith("/logs/bulk") -> {
                posts += body?.decodeToString() ?: ""
                HttpResult.Ok(HttpResponse(uploadStatus, emptyMap(), ByteArray(0)))
            }
            else -> HttpResult.Failed(HttpFailure.OTHER, "unexpected $method $url")
        }

        override suspend fun download(
            url: String, destinationPath: String, maxBytes: Long, timeoutSeconds: Int,
            onProgress: (Long, Long) -> Unit,
        ): HttpResult = error("not used")
    }

    private val http = Http()
    private val clock = Clock()
    private val prefs = MemoryKeyValueStore()
    private val tokens = KilterTokens(Secrets(), prefs, "abc123")
    private val api = KilterApi(http, clock, tokens)
    private val repo = newPersonalRepo()
    private val uploader = KilterUploader(api, tokens, repo)

    private fun signIn() {
        tokens.store(access = "a", refresh = "r", expiresAtEpochSeconds = clock.epochSeconds() + 3600)
        tokens.storeUserUuid("user-1")
    }

    private fun logSend(uuid: String, climb: String, tries: Long) = repo.insertAscent(
        uuid = uuid, climbUuid = climb, angle = 40L, isMirror = false, attemptId = 0,
        bidCount = tries, quality = 3, difficulty = null, isBenchmark = false, comment = null,
        climbedAt = "2026-09-19 18:30:00", synced = false, climbName = climb,
        difficultyAverage = 20.0, climbFrames = "p1r12", framesCount = 1,
    )

    private fun logAttempt(uuid: String, climb: String) = repo.insertBid(
        uuid = uuid, climbUuid = climb, angle = 40L, isMirror = false, bidCount = 3,
        comment = null, climbedAt = "2026-09-19 19:00:00", synced = false,
        climbName = climb, difficultyAverage = 20.0,
    )

    /** One log upstream is what makes the wall knowable. */
    private val remoteWithContext =
        """[{"logUuid":"old","climbUuid":"c0","angle":40,"topped":true,"attempts":1,""" +
            """"gymUuid":"g1","wallUuid":"w1","productLayoutUuid":"p1"}]"""

    @Test
    fun `sends and attempts go up with the wall the account already uses`() = runTest {
        signIn()
        http.remoteLogs = remoteWithContext
        logSend("a1", "climb-1", tries = 1)
        logAttempt("b1", "climb-2")

        val outcome = uploader.push()
        assertEquals(KilterFailure.NONE, outcome.failure)
        assertEquals(2, outcome.uploaded)
        assertEquals(0, outcome.pending, "everything that went up is stamped")

        val body = http.posts.single()
        assertTrue("\"gymUuid\":\"g1\"" in body, body)
        assertTrue("\"wallUuid\":\"w1\"" in body, body)
        assertTrue("\"productLayoutUuid\":\"p1\"" in body, body)
        // A send is topped; a first-go send is a flash; an attempt is neither.
        assertTrue("\"topped\":true" in body)
        assertTrue("\"flashed\":true" in body)
        assertTrue("\"topped\":false" in body)
        // The portal parses an instant: the stored local time needs a zone.
        assertTrue("2026-09-19T18:30:00Z" in body, body)

        assertTrue(repo.getUnsyncedAscents().isEmpty())
        assertTrue(repo.getUnsyncedBids().isEmpty())
    }

    @Test
    fun `nothing is sent when no log upstream names a wall`() = runTest {
        signIn()
        http.remoteLogs = """[{"logUuid":"old","climbUuid":"c0","angle":40}]"""
        logSend("a1", "climb-1", tries = 2)

        val outcome = uploader.push()
        assertTrue(outcome.missingWallContext, "a guessed wall files the session against the wrong board")
        assertEquals(0, outcome.uploaded)
        assertEquals(1, outcome.pending)
        assertTrue(http.posts.isEmpty())
        assertEquals(1, repo.getUnsyncedAscents().size, "the row stays pending")
    }

    @Test
    fun `a signed-out account uploads nothing`() = runTest {
        logSend("a1", "climb-1", tries = 1)
        val outcome = uploader.push()
        assertEquals(KilterFailure.NOT_SIGNED_IN, outcome.failure)
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `a rejected upload leaves every row pending`() = runTest {
        signIn()
        http.remoteLogs = remoteWithContext
        http.uploadStatus = 500
        logSend("a1", "climb-1", tries = 1)

        val outcome = uploader.push()
        assertEquals(KilterFailure.SERVER_ERROR, outcome.failure)
        assertEquals(1, outcome.pending)
        assertEquals(1, repo.getUnsyncedAscents().size)
    }

    @Test
    fun `an edit made during the upload is re-uploaded rather than lost`() = runTest {
        signIn()
        http.remoteLogs = remoteWithContext
        logSend("a1", "climb-1", tries = 1)
        val before = repo.getUnsyncedAscents().single()

        // The guard is the row version: bumping it stands in for the user
        // editing the entry while the request was in flight.
        repo.updateAscent(before.uuid, bidCount = 4, quality = 3, comment = "revised")
        assertFalse(
            repo.markAscentSyncedIfUnchanged(before.uuid, before.rowVersion),
            "a stale stamp would drop the newer data",
        )
        assertEquals(1, repo.getUnsyncedAscents().size)
    }

    @Test
    fun `an empty logbook does not touch the network`() = runTest {
        signIn()
        val outcome = uploader.push()
        assertEquals(KilterFailure.NONE, outcome.failure)
        assertEquals(0, outcome.uploaded)
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `a local timestamp gains the zone the portal needs`() {
        assertEquals("2026-09-19T18:30:00Z", KilterUploader.ensureUtcSuffix("2026-09-19 18:30:00"))
        assertEquals("2026-09-19T18:30:00Z", KilterUploader.ensureUtcSuffix("2026-09-19T18:30:00Z"))
        assertEquals("", KilterUploader.ensureUtcSuffix(""))
    }
}
