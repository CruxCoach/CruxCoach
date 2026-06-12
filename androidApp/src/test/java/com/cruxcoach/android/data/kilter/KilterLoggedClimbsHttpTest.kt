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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HTTP-level tests for [KilterApiClient.fetchLoggedClimbs] — the
 * `GET /api/climbs/logged` envelope parser that backs the own-logged-climb
 * backfill (PowerSync-only climbs that never landed in the curated mirror).
 *
 * The envelope is identical in shape to `/climbs/curated`: an object with
 * `climbs[]` + `climbStats[]`. These tests assert the parse handles a
 * dashed-lowercase (new-world) uuid + climbConcat, ignores unknown fields,
 * and surfaces auth/HTTP failures as a failed Result without throwing.
 */
class KilterLoggedClimbsHttpTest {

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
    fun fetchLoggedClimbs_parses_climbs_and_stats_envelope() = runTest {
        // A new-world (PowerSync-only) climb: dashed-lowercase uuid +
        // climbConcat (h<holdId>p<role>), plus an extra unknown field the
        // parser must ignore.
        val body = """
            {
              "climbs": [
                {
                  "climbUuid": "a30d8042-aeea-42ce-8015-239016c87769",
                  "climbConcat": "h1p12h2p13h3p14",
                  "name": "Tallakrennesvingen",
                  "angle": 25,
                  "description": "",
                  "edgeLeft": 0,
                  "edgeRight": 144,
                  "edgeBottom": 0,
                  "edgeTop": 156,
                  "frameCount": 1,
                  "framesPace": 0,
                  "productLayoutUuid": "10",
                  "productName": "Kilter Board Original",
                  "isListed": true,
                  "isDraft": false,
                  "origin": "MIGRATED",
                  "userUuid": "u-1",
                  "username": "alice",
                  "createdAt": "2024-01-01T00:00:00Z",
                  "updatedAt": "2024-01-01T00:00:00Z",
                  "somethingUnknown": 42
                }
              ],
              "climbStats": [
                {
                  "climbUuid": "a30d8042-aeea-42ce-8015-239016c87769",
                  "angle": 25,
                  "difficultyAverage": 17.5,
                  "qualityAverage": 3.0,
                  "ascentCount": 12,
                  "currentDifficultyId": 18,
                  "faUsername": "bob",
                  "faAt": "2024-01-02T00:00:00Z"
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val result = client.fetchLoggedClimbs()

        assertTrue(result.isSuccess, "expected success, got $result")
        val env = result.getOrThrow()
        assertEquals(1, env.climbs.size)
        val climb = env.climbs.first()
        assertEquals("a30d8042-aeea-42ce-8015-239016c87769", climb.climbUuid)
        assertEquals("h1p12h2p13h3p14", climb.climbConcat)
        assertEquals("Tallakrennesvingen", climb.name)
        assertEquals(25, climb.angle)
        assertEquals(144, climb.edgeRight)
        assertEquals("10", climb.productLayoutUuid)

        assertEquals(1, env.climbStats.size)
        val stat = env.climbStats.first()
        assertEquals("a30d8042-aeea-42ce-8015-239016c87769", stat.climbUuid)
        assertEquals(25, stat.angle)
        assertEquals(17.5, stat.difficultyAverage)
        assertEquals(12, stat.ascentCount)
        assertEquals(18, stat.currentDifficultyId)
    }

    @Test
    fun fetchLoggedClimbs_tolerates_missing_optional_fields() = runTest {
        // Minimal climb: only the required climbUuid present. Every other
        // field should fall back to its declared default.
        val body = """{"climbs":[{"climbUuid":"x-1"}],"climbStats":[]}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val env = client.fetchLoggedClimbs().getOrThrow()
        assertEquals(1, env.climbs.size)
        val climb = env.climbs.first()
        assertEquals("x-1", climb.climbUuid)
        assertEquals("", climb.climbConcat)
        assertEquals("", climb.name)
        assertNull(climb.edgeLeft)
        assertTrue(env.climbStats.isEmpty())
    }

    @Test
    fun fetchLoggedClimbs_no_token_returns_failure_without_http_call() = runTest {
        every { tokenStore.getAccessToken() } returns null

        val result = client.fetchLoggedClimbs()

        assertTrue(result.isFalseAuth())
        assertEquals(0, server.requestCount, "no token → no HTTP call")
    }

    @Test
    fun fetchLoggedClimbs_http_error_returns_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = client.fetchLoggedClimbs()

        assertTrue(result.isFailure)
    }

    private fun Result<KilterLoggedClimbsResponse>.isFalseAuth(): Boolean {
        val ex = exceptionOrNull()
        return isFailure && ex is KilterApiException &&
            ex.reason == KilterAuthResult.Error.Reason.NotAuthenticated
    }
}
