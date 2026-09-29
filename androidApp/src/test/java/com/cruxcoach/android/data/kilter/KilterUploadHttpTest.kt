package com.cruxcoach.android.data.kilter

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
    @Test fun posts_exactly_the_given_logs_without_rereading_the_logbook() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(200))
            val tokens = mockk<KilterTokenStore>(relaxed = true)
            every { tokens.getAccessToken() } returns "synthetic-test-token"
            every { tokens.isAccessTokenExpired() } returns false
            val client = KilterApiClient(tokens, OkHttpClient())
            client.setEndpointsForTesting(server.url("/").toString().trimEnd('/'))
            // Spelling is the caller's decision (KilterClimbWireIds): the client
            // must not re-case either compact form nor touch a dashed id.
            val ids = listOf("a046c7f911034fda84faa329380f6c98", "D0E5387D5B974D38B4E93FC4DFD61EF6",
                "abcdef12-3456-4789-abcd-0123456789ab")
            val logs = ids.mapIndexed { index, id ->
                KilterLog(logUuid = "stable-log-$index", climbUuid = id,
                    topped = index != 1, attempts = index + 1, comment = "synthetic fixture")
            }

            assertTrue(client.uploadLogs(logs).isSuccess)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/logs/bulk", request.path)
            val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonArray
            assertEquals(3, sent.size)
            sent.forEachIndexed { index, value ->
                val row = value.jsonObject
                assertEquals(ids[index], row.getValue("climbUuid").jsonPrimitive.content)
                assertEquals(logs[index].logUuid, row.getValue("logUuid").jsonPrimitive.content)
                assertEquals(logs[index].topped.toString(), row.getValue("topped").jsonPrimitive.content)
                assertEquals(logs[index].attempts.toString(), row.getValue("attempts").jsonPrimitive.content)
                assertEquals(logs[index].comment, row.getValue("comment").jsonPrimitive.content)
            }
        }
    }

    @Test fun rejection_keeps_status_but_discards_echoed_private_body() = runTest {
        MockWebServer().use { server ->
            server.start()
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
            assertEquals(1, server.requestCount)
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

    @Test fun lost_response_is_a_network_failure_the_caller_must_settle() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val result = client(server).uploadLogs(listOf(KilterLog("stable-log", climbUuid = "native-id")))
            assertIs<java.io.IOException>(result.exceptionOrNull())
            assertEquals(1, server.requestCount)
        }
    }
}
