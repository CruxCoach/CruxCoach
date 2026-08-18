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
    fun `invalid versions and malformed source ids never issue a request`() {
        MockWebServer().use { server ->
            val client = testClient(server)

            client.recordVerifiedUpdate("0.2.2-dev", "codeberg")
            client.recordVerifiedUpdate("1000.2.2", "zapstore")
            // Malformed source ids: uppercase, punctuation, empty, over-long.
            client.recordVerifiedUpdate("0.2.2", "Codeberg")
            client.recordVerifiedUpdate("0.2.2", "forge!")
            client.recordVerifiedUpdate("0.2.2", "")
            client.recordVerifiedUpdate("0.2.2", "-leading-dash")
            client.recordVerifiedUpdate("0.2.2", "x".repeat(25))

            assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun `a well-formed but unconfigured source id is still reported`() {
        // Since FEAT-050 the source list is data: a host this build has never
        // heard of can legitimately serve an update. Gating the counter on a
        // compiled-in allowlist would drop exactly the events that prove a
        // newly configured forge works — so the client checks shape only and
        // lets the collector bucket the unknown id.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val client = testClient(server)

            client.recordVerifiedUpdate("0.2.2", "new-forge")

            val request = server.takeRequest(2, TimeUnit.SECONDS)
            assertEquals("/v1/app-event", request!!.path)
            assertTrue(
                request.body.readUtf8().contains("\"source\":\"new-forge\""),
                "expected the unknown source id in the body",
            )
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
    fun `download source accepts only configured sources, by origin not prefix`() {
        val sources = listOf(
            UpdateSource(
                id = "forge",
                kind = UpdateSource.Kind.FORGE,
                url = "https://codeberg.org/api/v1",
                owner = "CruxCoach",
                repo = "CruxCoach",
            ),
            UpdateSource(
                id = "zapstore",
                kind = UpdateSource.Kind.NOSTR,
                url = "wss://relay.zapstore.dev",
                cdn = "https://cdn.zapstore.dev",
            ),
            UpdateSource(
                id = "blossom",
                kind = UpdateSource.Kind.BLOSSOM,
                url = "https://blossom.primal.net",
            ),
        )

        assertEquals(
            "zapstore",
            resolveSourceId("https://cdn.zapstore.dev/${"a".repeat(64)}", sources),
        )
        assertEquals(
            "forge",
            resolveSourceId(
                "https://codeberg.org/CruxCoach/CruxCoach/releases/download/v0.2.2/app.apk",
                sources,
            ),
        )
        assertEquals(
            "blossom",
            resolveSourceId("https://blossom.primal.net/${"a".repeat(64)}", sources),
        )

        val unknown = listOf(
            "not-a-url",
            // cleartext must never resolve, whatever the host
            "http://codeberg.org/CruxCoach/CruxCoach/releases/download/v0.2.2/app.apk",
            // look-alike hosts: a string-prefix match would accept these
            "https://codeberg.org.evil.example/CruxCoach/CruxCoach/releases/download/v0.2.2/app.apk",
            "https://cdn.zapstore.dev.evil.example/${"a".repeat(64)}",
            "https://blossom.primal.net.evil.example/${"a".repeat(64)}",
            // right host, wrong repo
            "https://codeberg.org/Other/CruxCoach/releases/download/v0.2.2/app.apk",
            // a host that is simply not in the list
            "https://unconfigured.example/${"a".repeat(64)}",
        )
        unknown.forEach { url ->
            // kotlin.test.assertNull takes (actual, message) — the opposite
            // order from org.junit.Assert.assertNull.
            assertNull(resolveSourceId(url, sources), url)
        }
    }

    @Test
    fun `a disabled or malformed source contributes no download URL`() {
        val sha = "a".repeat(64)
        val sources = listOf(
            // disabled → filtered by isUsable() upstream, but downloadUrlFor
            // must not invent a URL for a forge without owner/repo either
            UpdateSource(id = "broken", kind = UpdateSource.Kind.FORGE, url = "https://f.example/api/v1"),
            UpdateSource(id = "blossom", kind = UpdateSource.Kind.BLOSSOM, url = "https://b.example"),
        )
        assertEquals(
            listOf("https://b.example/$sha"),
            buildDownloadUrls("v9.9.9", sha, primaryUrl = null, sources = sources),
        )
    }

    @Test
    fun `download URL list keeps the announcing source first and de-duplicates`() {
        val sha = "a".repeat(64)
        val sources = listOf(
            UpdateSource(id = "blossom", kind = UpdateSource.Kind.BLOSSOM, url = "https://b.example"),
            UpdateSource(id = "blossom-2", kind = UpdateSource.Kind.BLOSSOM, url = "https://b2.example"),
        )
        val urls = buildDownloadUrls(
            tagName = "v9.9.9",
            apkSha256 = sha,
            // the announcing source is also in the list — must not appear twice
            primaryUrl = "https://b.example/$sha",
            sources = sources,
        )
        assertEquals(
            listOf("https://b.example/$sha", "https://b2.example/$sha"),
            urls,
        )
    }

    @Test
    fun `cleartext sources are dropped without disabling the rest`() {
        val sha = "a".repeat(64)
        val sources = listOf(
            UpdateSource(id = "insecure", kind = UpdateSource.Kind.BLOSSOM, url = "http://plain.example"),
            UpdateSource(id = "ok", kind = UpdateSource.Kind.BLOSSOM, url = "https://b.example"),
        )
        assertEquals(
            listOf("https://b.example/$sha"),
            buildDownloadUrls("v9.9.9", sha, primaryUrl = null, sources = sources),
        )
    }

    private fun testClient(server: MockWebServer) = AnonymousUpdateMetricsClient(
        endpoint = server.url("/v1/app-event").toString(),
        allowInsecureLoopbackForTests = true,
    )
}
