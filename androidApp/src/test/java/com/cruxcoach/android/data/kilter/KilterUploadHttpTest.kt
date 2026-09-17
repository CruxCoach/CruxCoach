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
    @Test fun legacy_catalogue_ids_keep_compact_uppercase_statistics_identity_on_wire() = runTest {
        MockWebServer().use { server ->
            server.start()
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
            val request = server.takeRequest()
            assertTrue(request.path!!.endsWith("/logs/bulk"))
            assertEquals("POST", request.method)
        }
    }
}
