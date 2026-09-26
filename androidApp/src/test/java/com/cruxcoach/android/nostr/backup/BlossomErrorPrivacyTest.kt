package com.cruxcoach.android.nostr.backup

import android.app.Application
import io.mockk.*
import okhttp3.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BlossomErrorPrivacyTest {
    @Test fun `rejected upload never consumes or logs the untrusted response body`() {
        val body = mockk<ResponseBody>()
        every { body.source() } throws AssertionError("Error body must not be read")
        val response = Response.Builder().request(Request.Builder().url("https://example.org/upload").build())
            .protocol(Protocol.HTTP_1_1).code(500).message("Server error").body(body).build()
        val result = BlossomUploader(mockk(), mockk(), mockk()).parseUploadResponse("https://example.org", response)
        assertFalse(result.accepted)
        assertEquals("HTTP 500", result.error)
        verify(exactly = 0) { body.source() }
    }
}
