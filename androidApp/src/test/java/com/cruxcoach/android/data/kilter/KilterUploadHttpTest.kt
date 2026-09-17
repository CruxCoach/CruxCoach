package com.cruxcoach.android.data.kilter

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class KilterUploadHttpTest {
    @Test fun legacy_catalogue_ids_keep_compact_uppercase_statistics_identity_on_wire() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setResponseCode(200))
            val tokens = mockk<KilterTokenStore>(relaxed = true)
            every { tokens.getAccessToken() } returns "synthetic-test-token"
            every { tokens.isAccessTokenExpired() } returns false
            val client = KilterApiClient(tokens, OkHttpClient())
            client.setEndpointsForTesting(server.url("/").toString().trimEnd('/'))
            val canonical = "abcdef12-3456-4789-abcd-0123456789ab"
            val inputs = listOf(canonical.replace("-", ""), canonical.replace("-", "").uppercase(), canonical)
            val expected = listOf(canonical.replace("-", "").uppercase(), canonical.replace("-", "").uppercase(), canonical)
            val logs = inputs.mapIndexed { index, id ->
                KilterLog(logUuid = "stable-log-$index", climbUuid = id,
                    topped = index != 1, attempts = index + 1, comment = "synthetic fixture")
            }

            assertTrue(client.uploadLogs(logs).isSuccess)
            assertEquals("GET", server.takeRequest().method)
            val request = server.takeRequest()
            assertEquals("/api/logs/bulk", request.path)
            val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonArray
            assertEquals(3, sent.size)
            sent.forEachIndexed { index, value ->
                val row = value.jsonObject
                assertEquals(expected[index], row.getValue("climbUuid").jsonPrimitive.content)
                assertEquals(logs[index].logUuid, row.getValue("logUuid").jsonPrimitive.content)
                assertEquals(logs[index].topped.toString(), row.getValue("topped").jsonPrimitive.content)
                assertEquals(logs[index].attempts.toString(), row.getValue("attempts").jsonPrimitive.content)
                assertEquals(logs[index].comment, row.getValue("comment").jsonPrimitive.content)
            }
            assertEquals(inputs, logs.map { it.climbUuid })
        }
    }

    @Test fun rejection_keeps_status_but_discards_echoed_private_body() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setResponseCode(422).setBody("private log comment, account and token"))
            val tokens = mockk<KilterTokenStore>(relaxed = true)
            every { tokens.getAccessToken() } returns "synthetic-test-token"
            every { tokens.isAccessTokenExpired() } returns false
            val client = KilterApiClient(tokens, OkHttpClient())
            client.setEndpointsForTesting(server.url("/").toString().trimEnd('/'))
            val result = client.uploadLogs(listOf(KilterLog(logUuid = "test-log", climbUuid = "test-climb")))
            val error = assertIs<KilterUploadException>(result.exceptionOrNull())
            assertEquals(422, error.status)
            assertFalse(error.message.orEmpty().contains("private"))
            assertEquals("GET", server.takeRequest().method)
            val request = server.takeRequest()
            assertTrue(request.path!!.endsWith("/logs/bulk"))
            assertEquals("POST", request.method)
        }
    }
    private fun client(server: MockWebServer): KilterApiClient {
        val tokens = mockk<KilterTokenStore>(relaxed = true)
        every { tokens.getAccessToken() } returns "synthetic-test-token"
        every { tokens.isAccessTokenExpired() } returns false
        return KilterApiClient(tokens, OkHttpClient.Builder().retryOnConnectionFailure(false)
            .readTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()).also {
            it.setEndpointsForTesting(server.url("/").toString().trimEnd('/'))
        }
    }

    @Test fun retry_after_lost_response_does_not_post_an_existing_log_again() = runTest {
        MockWebServer().use { server ->
            server.start()
            val log = KilterLog("stable-log", climbUuid = "native-id", topped = true,
                createdAt = "2026-09-17T15:00:00Z")
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val api = client(server)
            assertTrue(api.uploadLogs(listOf(log)).isFailure)
            server.enqueue(MockResponse().setBody(Json.encodeToString(listOf(log))))
            assertTrue(api.uploadLogs(listOf(log)).isSuccess)
            assertEquals(listOf("GET", "POST", "GET"), List(3) { server.takeRequest().method })
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun edited_remote_log_is_reported_as_conflict_without_duplicate_or_delete() = runTest {
        MockWebServer().use { server ->
            server.start()
            val original = KilterLog("stable-log", climbUuid = "native-id", topped = true)
            server.enqueue(MockResponse().setBody(Json.encodeToString(listOf(original))))
            val result = client(server).uploadLogs(listOf(original.copy(attempts = 2)))
            assertIs<KilterLogConflictException>(result.exceptionOrNull())
            assertEquals(1, server.requestCount)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun partially_persisted_batch_only_posts_missing_logs() = runTest {
        MockWebServer().use { server ->
            server.start()
            val original = KilterLog("saved", climbUuid = "native-id")
            server.enqueue(MockResponse().setBody(Json.encodeToString(listOf(original))))
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(client(server).uploadLogs(listOf(original, original.copy(logUuid = "new"))).isSuccess)
            server.takeRequest()
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonArray
            assertEquals(1, body.size)
            assertEquals("new", body.single().jsonObject.getValue("logUuid").jsonPrimitive.content)
        }
    }
}
