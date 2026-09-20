package com.cruxcoach.app.kilter

import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.logbook.newPersonalRepo
import com.cruxcoach.app.platform.HttpFailure
import com.cruxcoach.app.platform.HttpResponse
import com.cruxcoach.app.platform.HttpResult
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.SecretStore
import com.cruxcoach.app.platform.WallClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemorySecrets : SecretStore {
    val items = HashMap<String, ByteArray>()
    override fun read(name: String) = items[name]?.copyOf()
    override fun write(name: String, value: ByteArray): Boolean { items[name] = value.copyOf(); return true }
    override fun delete(name: String) = items.remove(name) != null
}

private class FixedClock(var seconds: Long = 1_700_000_000) : WallClock {
    override fun epochSeconds() = seconds
    override fun epochMillis() = seconds * 1000
}

private class FakeHttp : HttpTransport {
    val requests = mutableListOf<Triple<String, String, String>>()
    var responder: (String, String, String) -> HttpResult = { _, _, _ ->
        HttpResult.Failed(HttpFailure.OTHER, "no responder")
    }

    override suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maxResponseBytes: Long,
        timeoutSeconds: Int,
    ): HttpResult {
        val text = body?.decodeToString() ?: ""
        requests += Triple(method, url, text)
        return responder(method, url, text)
    }

    override suspend fun download(
        url: String,
        destinationPath: String,
        maxBytes: Long,
        timeoutSeconds: Int,
        onProgress: (Long, Long) -> Unit,
    ): HttpResult = HttpResult.Failed(HttpFailure.OTHER, "not used")
}

private fun ok(body: String, status: Int = 200) =
    HttpResult.Ok(HttpResponse(status, emptyMap(), body.encodeToByteArray()))

/** A JWT whose payload carries only `sub`; the signature is never checked here. */
private fun jwt(sub: String): String {
    fun b64(text: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = text.encodeToByteArray()
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) out.append(alphabet[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) out.append(alphabet[b2 and 0x3f])
            i += 3
        }
        return out.toString()
    }
    return "header.${b64("{\"sub\":\"$sub\",\"iss\":\"kilter\"}")}.signature"
}

class KilterApiTest {
    private val http = FakeHttp()
    private val clock = FixedClock()
    private val secrets = MemorySecrets()
    private val prefs = MemoryKeyValueStore()
    private val tokens = KilterTokens(secrets, prefs, "abc123")
    private val api = KilterApi(http, clock, tokens)

    @Test
    fun `signing in stores the tokens in the keychain and never in preferences`() = runTest {
        http.responder = { _, url, _ ->
            if (url.endsWith("/token")) ok("""{"access_token":"${jwt("user-1")}","refresh_token":"r1","expires_in":300}""")
            else ok("[]")
        }
        val outcome = api.signIn("climber@example.org", "hunter2")
        assertEquals(KilterFailure.NONE, outcome.failure)
        assertEquals("user-1", outcome.userUuid)
        assertTrue(tokens.isSignedIn)

        val stored = secrets.items.values.joinToString { it.decodeToString() }
        assertTrue(stored.contains("r1"), "the refresh token belongs in the keychain")
        val prefsDump = prefs.values.entries.joinToString()
        assertFalse(prefsDump.contains("r1"), "no token may reach preferences")
        assertFalse(prefsDump.contains("hunter2"), "the password is never stored")
        assertFalse(prefsDump.contains("climber@example.org"), "the address is not stored either")
    }

    @Test
    fun `a rejected password is reported and then throttled with a growing delay`() = runTest {
        http.responder = { _, _, _ -> ok("""{"error":"invalid_grant"}""", status = 401) }
        val first = api.signIn("climber@example.org", "wrong")
        assertEquals(KilterFailure.INVALID_CREDENTIALS, first.failure)
        assertTrue(first.retryAfterSeconds > 0)

        val second = api.signIn("climber@example.org", "wrong")
        assertEquals(KilterFailure.THROTTLED, second.failure)
        assertTrue(second.retryAfterSeconds > 0)

        // After the window the attempt is allowed again, with a longer next back-off.
        clock.seconds += second.retryAfterSeconds + 1
        val third = api.signIn("climber@example.org", "wrong")
        assertEquals(KilterFailure.INVALID_CREDENTIALS, third.failure)
        assertTrue(third.retryAfterSeconds > first.retryAfterSeconds)
    }

    @Test
    fun `an expired access token is refreshed once before the request`() = runTest {
        tokens.store(access = "old", refresh = "r1", expiresAtEpochSeconds = clock.epochSeconds() - 10)
        var refreshed = false
        http.responder = { _, url, body ->
            when {
                url.endsWith("/token") && body.contains("grant_type=refresh_token") -> {
                    refreshed = true
                    ok("""{"access_token":"new","refresh_token":"r2","expires_in":300}""")
                }
                url.endsWith("/logs") -> ok("""{"logs":[{"log_uuid":"l1","climb_uuid":"c1","angle":40,"topped":true,"attempts":2,"created_at":"2026-01-02T10:00:00Z"}]}""")
                else -> ok("{}")
            }
        }
        val outcome = api.fetchLogs()
        assertTrue(refreshed)
        assertEquals(KilterFailure.NONE, outcome.failure)
        assertEquals(1, outcome.logs.size)
        assertEquals("l1", outcome.logs.first().logUuid)
    }

    @Test
    fun `both response shapes parse and junk is refused rather than guessed`() = runTest {
        tokens.store(access = "a", refresh = "r", expiresAtEpochSeconds = clock.epochSeconds() + 3600)
        http.responder = { _, _, _ -> ok("""[{"log_uuid":"l2","climb_uuid":"c2","angle":30}]""") }
        assertEquals(1, api.fetchLogs().logs.size)

        http.responder = { _, _, _ -> ok("<html>not json</html>") }
        assertEquals(KilterFailure.MALFORMED_RESPONSE, api.fetchLogs().failure)

        http.responder = { _, _, _ -> HttpResult.Failed(HttpFailure.OFFLINE, "") }
        assertEquals(KilterFailure.OFFLINE, api.fetchLogs().failure)
    }

    @Test
    fun `without a session the logs call does not reach the network`() = runTest {
        val outcome = api.fetchLogs()
        assertEquals(KilterFailure.NOT_SIGNED_IN, outcome.failure)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `signing out revokes the refresh token and forgets it`() = runTest {
        tokens.store(access = "a", refresh = "r1", expiresAtEpochSeconds = clock.epochSeconds() + 300)
        http.responder = { _, _, _ -> ok("") }
        api.signOut()
        assertTrue(http.requests.any { it.second.endsWith("/logout") && it.third.contains("refresh_token=r1") })
        assertNull(tokens.refreshToken())
        assertNull(tokens.accessToken())
        assertFalse(tokens.isSignedIn)
    }

    @Test
    fun `importing is idempotent and reports climbs the catalogue does not have`() = runTest {
        val personal = newPersonalRepo()
        val db = com.cruxcoach.app.browse.testing.BrowseTestDb()
        try {
            db.climb("c1", name = "Test Problem")
            val importer = KilterLogImporter(db.boardRepo, personal)
            val logs = listOf(
                KilterLog(logUuid = "l1", climbUuid = "c1", angle = 40, topped = true, attempts = 3, createdAt = "2026-01-02T10:00:00Z"),
                KilterLog(logUuid = "l2", climbUuid = "c1", angle = 40, topped = false, attempts = 2, createdAt = "2026-01-02T11:00:00Z"),
                KilterLog(logUuid = "l3", climbUuid = "missing", angle = 40, topped = true, createdAt = "2026-01-02T12:00:00Z"),
            )
            val first = importer.import(logs)
            assertEquals(2, first.imported)
            assertEquals(1, first.unknownClimb)

            val second = importer.import(logs)
            assertEquals(0, second.imported, "a second import must not duplicate anything")
            assertEquals(2, second.alreadyPresent)

            val rows = personal.getUserHistoryForClimb("c1")
            assertEquals(2, rows.size)
            assertEquals(1, rows.count { it.isSend })
        } finally {
            db.close()
        }
    }

    @Test
    fun `a flashed send is recorded as a single try`() = runTest {
        val personal = newPersonalRepo()
        val db = com.cruxcoach.app.browse.testing.BrowseTestDb()
        try {
            db.climb("c9", name = "Flashed")
            KilterLogImporter(db.boardRepo, personal).import(
                listOf(KilterLog(logUuid = "f1", climbUuid = "c9", angle = 40, topped = true, flashed = true, attempts = 7, createdAt = "2026-02-01T09:00:00Z"))
            )
            assertEquals(1L, personal.getUserHistoryForClimb("c9").single().bidCount)
        } finally {
            db.close()
        }
    }

    @Test
    fun `the user uuid comes out of the token subject`() {
        assertEquals("user-42", KilterApi.subjectOf(jwt("user-42")))
        assertEquals("", KilterApi.subjectOf("not-a-jwt"))
        assertEquals("", KilterApi.subjectOf(""))
    }
}
