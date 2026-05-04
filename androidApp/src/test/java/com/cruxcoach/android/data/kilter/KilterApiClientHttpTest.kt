package com.cruxcoach.android.data.kilter

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP-level tests for [KilterApiClient.authenticate]. Exercises the
 * Keycloak `/protocol/openid-connect/token` response shapes that the
 * audit findings care about (error-path-tests/001, test-quality/005,
 * unit-test-gaps/004 — error mapping coverage).
 *
 * Pre-fix the auth path returned a single free-form German error string;
 * the typed [KilterAuthResult.Error.Reason] sealed class is now what we
 * test against. Each MockWebServer response shape maps to exactly one
 * Reason — drift in either the SDK's HTTP-status interpretation or the
 * Reason set surfaces here as a test failure.
 */
class KilterApiClientHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KilterApiClient
    private lateinit var tokenStore: KilterTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = mockk(relaxed = true)
        // Real OkHttp client, but with short timeouts so failed-tests
        // don't block CI for 30s. The User-Agent interceptor that the
        // production module installs isn't needed here — we don't assert
        // on UA in this test.
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .build()
        client = KilterApiClient(tokenStore, httpClient)
        client.setEndpointsForTesting(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun b64url(s: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    private fun jwtFor(sub: String, username: String): String {
        val header = b64url("""{"alg":"none","typ":"JWT"}""")
        val payload = b64url("""{"sub":"$sub","preferred_username":"$username"}""")
        val sig = b64url("ignored")
        return "$header.$payload.$sig"
    }

    // ── Happy path ──────────────────────────────────────────────────

    @Test
    fun authenticate_returns_success_with_jwt_claims() = runTest {
        val accessToken = jwtFor(sub = "user-uuid-123", username = "alice")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"$accessToken","refresh_token":"refresh-1","expires_in":3600}"""
            )
        )

        val result = client.authenticate("alice@example.com", "secret")

        assertTrue(result is KilterAuthResult.Success, "expected Success, got $result")
        assertEquals("user-uuid-123", result.userUuid)
        assertEquals("alice", result.username)
        assertEquals(accessToken, result.accessToken)
        assertEquals("refresh-1", result.refreshToken)
        assertEquals(3600L, result.expiresIn)
    }

    // ── Error mapping ───────────────────────────────────────────────

    @Test
    fun authenticate_401_maps_to_InvalidCredentials() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid_grant"}"""))

        val result = client.authenticate("alice@example.com", "wrong")

        assertTrue(result is KilterAuthResult.Error, "expected Error, got $result")
        assertEquals(KilterAuthResult.Error.Reason.InvalidCredentials, result.reason)
        assertEquals(401, result.httpCode)
    }

    @Test
    fun authenticate_500_maps_to_HttpFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = client.authenticate("alice@example.com", "secret")

        assertTrue(result is KilterAuthResult.Error, "expected Error, got $result")
        assertEquals(KilterAuthResult.Error.Reason.HttpFailure, result.reason)
        assertEquals(500, result.httpCode)
    }

    @Test
    fun authenticate_503_maps_to_HttpFailure() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody(""))

        val result = client.authenticate("alice@example.com", "secret")

        assertTrue(result is KilterAuthResult.Error, "expected Error, got $result")
        assertEquals(KilterAuthResult.Error.Reason.HttpFailure, result.reason)
        assertEquals(503, result.httpCode)
    }

    @Test
    fun authenticate_403_also_maps_to_HttpFailure_not_invalid_credentials() = runTest {
        // Only HTTP 401 is "wrong password"; 403 is something else
        // (rate-limit by Keycloak, account locked, etc.) and surfaces as
        // HttpFailure so the user sees the generic "login failed"
        // message + the HTTP code.
        server.enqueue(MockResponse().setResponseCode(403).setBody(""))

        val result = client.authenticate("alice@example.com", "secret")

        assertTrue(result is KilterAuthResult.Error)
        assertEquals(KilterAuthResult.Error.Reason.HttpFailure, result.reason)
        assertEquals(403, result.httpCode)
    }

    @Test
    fun authenticate_missing_sub_claim_maps_to_InvalidJwt() = runTest {
        // Build a JWT whose payload deliberately lacks `sub` — a valid
        // shape (3 parts, valid base64) but a malformed claim set. Pre-fix
        // this would silently leak through as Success(userUuid="").
        val header = b64url("""{"alg":"none","typ":"JWT"}""")
        val payloadNoSub = b64url("""{"preferred_username":"bob"}""")
        val sig = b64url("ignored")
        val accessToken = "$header.$payloadNoSub.$sig"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"$accessToken","refresh_token":"r","expires_in":3600}"""
            )
        )

        val result = client.authenticate("bob@example.com", "secret")

        assertTrue(result is KilterAuthResult.Error, "expected Error, got $result")
        assertEquals(KilterAuthResult.Error.Reason.InvalidJwt, result.reason)
    }

    @Test
    fun authenticate_blank_sub_also_maps_to_InvalidJwt() = runTest {
        val accessToken = jwtFor(sub = "", username = "")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"access_token":"$accessToken","refresh_token":"r","expires_in":3600}"""
            )
        )

        val result = client.authenticate("bob@example.com", "secret")

        assertTrue(result is KilterAuthResult.Error)
        assertEquals(KilterAuthResult.Error.Reason.InvalidJwt, result.reason)
    }

    // ── Throttle ────────────────────────────────────────────────────

    @Test
    fun authenticate_throttle_blocks_rapid_retries() = runTest {
        // First 401 bumps the backoff (1s for the first failure).
        server.enqueue(MockResponse().setResponseCode(401).setBody(""))
        val first = client.authenticate("alice@example.com", "x")
        assertTrue(first is KilterAuthResult.Error)
        assertEquals(KilterAuthResult.Error.Reason.InvalidCredentials, first.reason)

        // Immediate retry hits the throttle BEFORE the HTTP call —
        // MockWebServer doesn't see a second request.
        val second = client.authenticate("alice@example.com", "x")
        assertTrue(second is KilterAuthResult.Error)
        assertEquals(KilterAuthResult.Error.Reason.Throttled, second.reason)
        assertTrue((second.throttleSec ?: 0L) >= 1L,
            "expected non-zero throttleSec, got ${second.throttleSec}")
        assertEquals(1, server.requestCount, "throttle should suppress the second HTTP call")
    }

    // ── Network error ───────────────────────────────────────────────

    @Test
    fun authenticate_with_dead_server_maps_to_NetworkError() = runTest {
        // Shut the server down so the connect attempt throws.
        server.shutdown()

        val result = client.authenticate("alice@example.com", "secret")

        assertTrue(result is KilterAuthResult.Error, "expected Error, got $result")
        assertEquals(KilterAuthResult.Error.Reason.NetworkError, result.reason)
    }
}
