package com.cruxcoach.android.data.kilter

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class KilterUploadHttpTest {
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
