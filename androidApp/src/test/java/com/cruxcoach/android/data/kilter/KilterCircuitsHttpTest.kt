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
import kotlin.test.assertTrue

/**
 * HTTP-level tests for [KilterApiClient.fetchCircuits] — the PowerSync
 * `POST /sync/stream` read backing the circuit → local-list import.
 *
 * User circuits live ONLY in PowerSync `circuit_buckets[...]` (REST
 * `/api/circuits` is curated-only, verified live 2026-07-11), so the fetch
 * streams the sync checkpoint, drains just the circuit buckets and disconnects
 * before `global_climbs`. These tests pin the request plumbing + a basic fold;
 * [KilterCircuitSyncParserTest] exercises the ndjson fold in depth.
 */
class KilterCircuitsHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KilterApiClient
    private lateinit var tokenStore: KilterTokenStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tokenStore = mockk(relaxed = true)
        every { tokenStore.getAccessToken() } returns "test-token"
        every { tokenStore.isAccessTokenExpired() } returns false
        every { tokenStore.getUserUuid() } returns "user-123"
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
    fun fetchCircuits_streams_powersync_and_posts_to_sync_stream() = runTest {
        // checkpoint announces one 2-op circuit bucket; the data line delivers
        // exactly those 2 ops so the fetch drains + disconnects before the
        // (never-sent) checkpoint_complete.
        val ndjson = listOf(
            """{"checkpoint":{"buckets":[{"bucket":"circuit_buckets_c1","count":2}]}}""",
            """{"data":{"bucket":"circuit_buckets_c1","data":[""" +
                """{"op":"PUT","object_type":"circuits","object_id":"c-1","data":{"circuit_uuid":"c-1","name":"test","color":"FF0000","user_uuid":"user-123"}},""" +
                """{"op":"PUT","object_type":"circuit_climbs","object_id":"c-1.climb-a","data":{"circuit_uuid":"c-1","climb_uuid":"climb-a","sort_order":1}}""" +
                """]}}""",
            """{"checkpoint_complete":{"last_op_id":"2"}}""",
        ).joinToString("\n")
        server.enqueue(MockResponse().setResponseCode(200).setBody(ndjson))

        val circuits = client.fetchCircuits().getOrThrow()

        assertEquals(1, circuits.size)
        val c = circuits.single()
        assertEquals("c-1", c.circuitUuid)
        assertEquals("test", c.name)
        assertEquals("FF0000", c.color)
        assertEquals(listOf("climb-a"), c.memberClimbUuids())

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/sync/stream", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("application/x-ndjson", request.getHeader("Accept"))
        // A first-sync request: no local bucket state.
        assertTrue(request.body.readUtf8().contains("\"buckets\":[]"))
    }

    @Test
    fun fetchCircuits_empty_when_no_circuit_buckets() = runTest {
        // Only global buckets in the checkpoint → nothing to import, and we
        // stop immediately without reading any bucket data.
        val ndjson =
            """{"checkpoint":{"buckets":[{"bucket":"global_climbs[]","count":31000},{"bucket":"global_gyms[]","count":900}]}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(ndjson))

        assertTrue(client.fetchCircuits().getOrThrow().isEmpty())
        assertEquals("/sync/stream", server.takeRequest().path)
    }

    @Test
    fun fetchCircuits_no_token_returns_failure_without_http_call() = runTest {
        every { tokenStore.getAccessToken() } returns null
        every { tokenStore.isAccessTokenExpired() } returns true

        val result = client.fetchCircuits()

        val ex = result.exceptionOrNull()
        assertTrue(result.isFailure && ex is KilterApiException &&
            ex.reason == KilterAuthResult.Error.Reason.NotAuthenticated)
        assertEquals(0, server.requestCount, "no token → no HTTP call")
    }

    @Test
    fun fetchCircuits_http_error_returns_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(client.fetchCircuits().isFailure)
    }
}
