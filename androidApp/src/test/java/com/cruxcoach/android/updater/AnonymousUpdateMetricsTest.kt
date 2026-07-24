package com.cruxcoach.android.updater

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class AnonymousUpdateMetricsTest {

    @Test
    fun `verified update payload contains only aggregate dimensions`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val client = testClient(server)

            assertTrue(client.isConfigured)
            client.recordVerifiedUpdate("0.2.2", "codeberg")

            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/v1/app-event", request.path)
            assertEquals("CruxCoach-Metrics/1", request.getHeader("User-Agent"))
            assertEquals("no-store", request.getHeader("Cache-Control"))
            assertEquals(
                "application/json; charset=utf-8",
                request.getHeader("Content-Type"),
            )
            assertEquals(
                "{\"metric\":\"app_update_verified\",\"version\":\"0.2.2\",\"source\":\"codeberg\"}",
                request.body.readUtf8(),
            )
            val headerNames = request.headers.names().map(String::lowercase)
            assertFalse("cookie" in headerNames)
            assertFalse("referer" in headerNames)
            assertFalse("origin" in headerNames)
            assertFalse("authorization" in headerNames)
            assertFalse("proxy-authorization" in headerNames)
        }
    }

    @Test
    fun `production client rejects empty malformed credentialed and insecure endpoints`() {
        val invalidEndpoints = listOf(
            "",
            "not-a-url",
            "http://stats.example/v1/app-event",
            "https://user:password@stats.example/v1/app-event",
            "https://stats.example/v1/app-event#fragment",
        )

        invalidEndpoints.forEach { endpoint ->
            val client = AnonymousUpdateMetricsClient(endpoint)
            assertFalse(client.isConfigured, endpoint)
            client.recordVerifiedUpdate("0.2.2", "codeberg")
        }

        MockWebServer().use { server ->
            val insecureLoopback = AnonymousUpdateMetricsClient(
                server.url("/v1/app-event").toString(),
            )
            assertFalse(insecureLoopback.isConfigured)
            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `invalid versions and unknown sources never issue a request`() {
        MockWebServer().use { server ->
            val client = testClient(server)

            client.recordVerifiedUpdate("0.2.2-dev", "codeberg")
            client.recordVerifiedUpdate("0.2.2", "other")
            client.recordVerifiedUpdate("1000.2.2", "zapstore")

            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `server errors are not retried`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val client = testClient(server)

            client.recordVerifiedUpdate("0.2.2", "zapstore")

            assertEquals("/v1/app-event", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `redirect responses are not followed`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(307)
                    .addHeader("Location", server.url("/unexpected")),
            )
            val client = testClient(server)

            client.recordVerifiedUpdate("0.2.2", "codeberg")

            assertEquals("/v1/app-event", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
            assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `download source accepts only configured codeberg releases or zapstore CDN`() {
        val zapstoreBase = "https://cdn.zapstore.dev"
        val apiBase = "https://codeberg.org/api/v1"
        assertEquals(
            "zapstore",
            anonymousUpdateSource(
                "https://cdn.zapstore.dev/${"a".repeat(64)}",
                zapstoreBase,
                apiBase,
                "CruxCoach",
                "CruxCoach",
            ),
        )
        assertEquals(
            "codeberg",
            anonymousUpdateSource(
                "https://codeberg.org/CruxCoach/CruxCoach/releases/download/v0.2.2/app.apk",
                zapstoreBase,
                apiBase,
                "CruxCoach",
                "CruxCoach",
            ),
        )

        val unknown = listOf(
            "not-a-url",
            "http://codeberg.org/CruxCoach/CruxCoach/releases/download/v0.2.2/app.apk",
            "https://codeberg.org.evil.example/CruxCoach/CruxCoach/releases/download/v0.2.2/app.apk",
            "https://codeberg.org/Other/CruxCoach/releases/download/v0.2.2/app.apk",
            "https://cdn.zapstore.dev.evil.example/${"a".repeat(64)}",
        )
        unknown.forEach { url ->
            assertNull(
                anonymousUpdateSource(
                    url,
                    zapstoreBase,
                    apiBase,
                    "CruxCoach",
                    "CruxCoach",
                ),
                url,
            )
        }
    }

    private fun testClient(server: MockWebServer) = AnonymousUpdateMetricsClient(
        endpoint = server.url("/v1/app-event").toString(),
        allowInsecureLoopbackForTests = true,
    )
}
