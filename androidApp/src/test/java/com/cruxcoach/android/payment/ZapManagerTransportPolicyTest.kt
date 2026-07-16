package com.cruxcoach.android.payment

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ZapManagerTransportPolicyTest {

    @Test
    fun `valid LNURL response retains HTTPS callback`() {
        val parsed = parseLnurlPayResponseOrNull(response("https://pay.example/callback?token=abc"))

        assertEquals("https://pay.example/callback?token=abc", parsed?.callback)
        assertEquals(1_000L, parsed?.minSendable)
        assertEquals(10_000L, parsed?.maxSendable)
    }

    @Test
    fun `remote LNURL response cannot select a non-HTTPS second hop`() {
        listOf(
            "http://pay.example/callback",
            "//pay.example/callback",
            "https://user@pay.example/callback",
        ).forEach { callback ->
            assertNull(parseLnurlPayResponseOrNull(response(callback)), callback)
        }
    }

    private fun response(callback: String): String =
        """{"callback":"$callback","minSendable":1000,"maxSendable":10000,"allowsNostr":true}"""
}
