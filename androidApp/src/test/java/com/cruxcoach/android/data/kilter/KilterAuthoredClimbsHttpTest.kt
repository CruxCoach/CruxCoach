package com.cruxcoach.android.data.kilter

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP-level tests for [KilterApiClient.fetchOwnAuthoredClimbs] — the
 * `GET /api/climbs/climbdetails/user` parser that backs the own-AUTHORED-
 * climb backfill. Unlike `/climbs/logged`, the live response is a BARE JSON
 * array (no `climbs[]`/`climbStats[]` envelope).
 *
 * These tests assert the parse handles a dashed-lowercase (new-world) uuid +
 * climbConcat, surfaces the author identity (`userUuid`) the publish gate
 * persists, hits the expected path, ignores unknown fields, and turns
 * auth/HTTP failures into a failed Result without throwing.
 */
class KilterAuthoredClimbsHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KilterApiClient
    private lateinit var tokenStore: KilterTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = mockk(relaxed = true)
        // A valid, unexpired access token so ensureValidToken() returns it
        // without attempting a refresh round-trip.
        every { tokenStore.getAccessToken() } returns "test-token"
        every { tokenStore.isAccessTokenExpired() } returns false
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

    @Test
    fun fetchOwnAuthoredClimbs_parses_bare_array() = runTest {
        // The live wire shape: a BARE top-level array of authored climbs —
        // dashed-lowercase (new-world) uuid + climbConcat, plus an extra
        // unknown field the parser must ignore.
        val body = """
            [
              {
                "climbUuid": "b41e9153-bffb-53df-9126-34a127d98870",
                "climbConcat": "h7p12h8p13h9p14",
                "name": "My Own Setter Line",
                "angle": 40,
                "description": "toe hook start",
                "edgeLeft": 4,
                "edgeRight": 140,
                "edgeBottom": 0,
                "edgeTop": 152,
                "frameCount": 1,
                "framesPace": 0,
                "productLayoutUuid": "10",
                "productName": "Kilter Board Original",
                "isListed": true,
                "isDraft": false,
                "origin": "NATIVE",
                "userUuid": "my-own-user-uuid",
                "username": "me",
                "createdAt": "2024-02-02T00:00:00Z",
                "updatedAt": "2024-02-02T00:00:00Z",
                "somethingUnknown": 42
              }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = client.fetchOwnAuthoredClimbs()

        assertTrue(result.isSuccess, "expected success, got $result")
        val climbs = result.getOrThrow()
        assertEquals(1, climbs.size)
        val climb = climbs.first()
        assertEquals("b41e9153-bffb-53df-9126-34a127d98870", climb.climbUuid)
        assertEquals("h7p12h8p13h9p14", climb.climbConcat)
        assertEquals("My Own Setter Line", climb.name)
        assertEquals(40, climb.angle)
        assertEquals(140, climb.edgeRight)
        assertEquals("10", climb.productLayoutUuid)
        // The author identity the publish gate compares against the
        // connected account's userUuid.
        assertEquals("my-own-user-uuid", climb.userUuid)

        val request = server.takeRequest()
        assertEquals("/api/climbs/climbdetails/user", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun fetchOwnAuthoredClimbs_tolerates_missing_optional_fields_and_empty_array() = runTest {
        // Minimal climb: only the required climbUuid present. Every other
        // field should fall back to its declared default.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""[{"climbUuid":"x-1"}]"""))
        val climbs = client.fetchOwnAuthoredClimbs().getOrThrow()
        assertEquals(1, climbs.size)
        assertEquals("x-1", climbs.first().climbUuid)
        assertEquals("", climbs.first().climbConcat)
        assertNull(climbs.first().edgeLeft)

        // A user with no authored climbs: empty array, still a success.
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(client.fetchOwnAuthoredClimbs().getOrThrow().isEmpty())
    }

    @Test
    fun fetchOwnAuthoredClimbs_no_token_returns_failure_without_http_call() = runTest {
        every { tokenStore.getAccessToken() } returns null

        val result = client.fetchOwnAuthoredClimbs()

        val ex = result.exceptionOrNull()
        assertTrue(result.isFailure && ex is KilterApiException &&
            ex.reason == KilterAuthResult.Error.Reason.NotAuthenticated)
        assertEquals(0, server.requestCount, "no token → no HTTP call")
    }

    @Test
    fun fetchOwnAuthoredClimbs_http_error_returns_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = client.fetchOwnAuthoredClimbs()

        assertTrue(result.isFailure)
    }
}
