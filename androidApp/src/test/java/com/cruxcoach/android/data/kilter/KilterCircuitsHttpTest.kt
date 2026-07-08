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
 * HTTP-level tests for [KilterApiClient.fetchCircuits] — the
 * `GET /api/circuits/{userUuid}` parser backing the circuit → local-list
 * import. The live shape is a bare JSON array; a user's circuit UUID is
 * appended to the path.
 *
 * ⚠ The NON-EMPTY circuit body here is inferred (see [KilterCircuit]) — the
 * probe account has zero circuits, so these assertions pin the tolerant
 * parser (multiple membership embeddings, envelope fallback), not a captured
 * live payload. Live verification against a real circuit is still owed.
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
    fun fetchCircuits_parses_bare_array_and_hits_user_scoped_path() = runTest {
        val body = """
            [
              {
                "circuitUuid": "c-1",
                "name": "Warmups",
                "description": "easy stuff",
                "color": "FF0000",
                "isPublic": false,
                "userUuid": "user-123",
                "createdAt": "2024-03-03T00:00:00Z",
                "updatedAt": "2024-03-04T00:00:00Z",
                "circuitClimbs": [
                  {"climbUuid": "b", "sortOrder": 2},
                  {"climbUuid": "a", "sortOrder": 1}
                ],
                "somethingUnknown": 42
              }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val circuits = client.fetchCircuits().getOrThrow()

        assertEquals(1, circuits.size)
        val c = circuits.first()
        assertEquals("c-1", c.circuitUuid)
        assertEquals("Warmups", c.name)
        assertEquals("FF0000", c.color)
        // sortOrder drives ordering: climb "a" (1) before "b" (2).
        assertEquals(listOf("a", "b"), c.memberClimbUuids())

        val request = server.takeRequest()
        assertEquals("/api/circuits/user-123", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun fetchCircuits_merges_alternate_membership_embeddings_deduped() = runTest {
        // A circuit that carries bare-uuid arrays instead of objects, with a
        // duplicate across the two — memberClimbUuids must merge + dedup.
        val body = """
            [
              {
                "circuitUuid": "c-2",
                "name": "Projects",
                "climbUuids": ["x", "y"],
                "climbs": ["y", "z"]
              }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val c = client.fetchCircuits().getOrThrow().single()
        assertEquals(listOf("x", "y", "z"), c.memberClimbUuids())
    }

    @Test
    fun fetchCircuits_tolerates_items_envelope_and_empty() = runTest {
        // A wrapped {items:[...]} envelope, then an empty array.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"items":[{"circuitUuid":"c-3","name":"L"}],"total":1}"""))
        assertEquals("c-3", client.fetchCircuits().getOrThrow().single().circuitUuid)

        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertTrue(client.fetchCircuits().getOrThrow().isEmpty())
    }

    @Test
    fun fetchCircuits_no_user_uuid_returns_failure_without_http_call() = runTest {
        every { tokenStore.getUserUuid() } returns null

        val result = client.fetchCircuits()

        val ex = result.exceptionOrNull()
        assertTrue(result.isFailure && ex is KilterApiException &&
            ex.reason == KilterAuthResult.Error.Reason.NotAuthenticated)
        assertEquals(0, server.requestCount, "no user uuid → no HTTP call")
    }

    @Test
    fun fetchCircuits_http_error_returns_failure() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertTrue(client.fetchCircuits().isFailure)
    }
}
