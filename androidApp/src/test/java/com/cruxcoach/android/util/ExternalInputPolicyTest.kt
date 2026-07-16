package com.cruxcoach.android.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalInputPolicyTest {
    @Test
    fun `generic HTTPS boundary accepts cross-origin assets and callbacks`() {
        listOf(
            "https://downloads.example/app.apk",
            "https://pay.example:8443/callback?token=abc",
        ).forEach { assertEquals(it, ExternalInputPolicy.trustedHttpsUrlOrNull(it)) }
    }

    @Test
    fun `generic HTTPS boundary rejects cleartext opaque and ambiguous URLs`() {
        listOf(
            "http://downloads.example/app.apk",
            "//downloads.example/app.apk",
            "javascript:alert(1)",
            "https://user@downloads.example/app.apk",
            "https:///app.apk",
            "https://downloads.example/app.apk#fragment",
            " https://downloads.example/app.apk",
            "https://downloads.example/app.apk\n",
        ).forEach { assertNull(ExternalInputPolicy.trustedHttpsUrlOrNull(it), it) }
    }

    @Test
    fun `release pages stay on configured HTTPS origin`() {
        val base = "https://codeberg.org/api/v1"
        val release = "https://codeberg.org/CruxCoach/CruxCoach/releases/tag/v1.2.3"
        assertEquals(release, ExternalInputPolicy.trustedReleasePageUrlOrNull(release, base))

        val forkBase = "https://updates.example:8443/api/v1"
        val forkRelease = "https://updates.example:8443/acme/app/releases/tag/v1"
        assertEquals(forkRelease, ExternalInputPolicy.trustedReleasePageUrlOrNull(forkRelease, forkBase))
    }

    @Test
    fun `release pages reject untrusted intent targets`() {
        val base = "https://codeberg.org/api/v1"
        listOf(
            "http://codeberg.org/CruxCoach/CruxCoach",
            "javascript:alert(1)",
            "https://evil.example/CruxCoach/CruxCoach",
            "https://codeberg.org@evil.example/CruxCoach/CruxCoach",
            "https://user@codeberg.org/CruxCoach/CruxCoach",
            "https://codeberg.org:444/CruxCoach/CruxCoach",
            " https://codeberg.org/CruxCoach/CruxCoach",
            "https://codeberg.org/CruxCoach/CruxCoach\n",
        ).forEach { assertNull(ExternalInputPolicy.trustedReleasePageUrlOrNull(it, base), it) }
    }

    @Test
    fun `accepts BOLT11 examples with and without amount`() {
        assertEquals(ZERO_AMOUNT_INVOICE, ExternalInputPolicy.validBolt11OrNull(ZERO_AMOUNT_INVOICE))
        assertEquals(AMOUNT_INVOICE, ExternalInputPolicy.validBolt11OrNull(AMOUNT_INVOICE))
        assertEquals(ZERO_AMOUNT_INVOICE.uppercase(), ExternalInputPolicy.validBolt11OrNull(ZERO_AMOUNT_INVOICE.uppercase()))
    }

    @Test
    fun `rejects malformed or URI-shaped Lightning payloads`() {
        listOf(
            ZERO_AMOUNT_INVOICE.replaceRange(8, 9, "J"),
            ZERO_AMOUNT_INVOICE.dropLast(1) + if (ZERO_AMOUNT_INVOICE.last() == 'q') "p" else "q",
            "lightning:$ZERO_AMOUNT_INVOICE",
            "$ZERO_AMOUNT_INVOICE?amount=1",
            "$ZERO_AMOUNT_INVOICE\n",
            ZERO_AMOUNT_INVOICE.replaceFirst("lnbc", "lnsb"),
            "lnbc01" + ZERO_AMOUNT_INVOICE.substringAfter('1'),
            "lnbc1p" + "q".repeat(116),
        ).forEach { assertNull(ExternalInputPolicy.validBolt11OrNull(it), it.take(40)) }
    }

    private companion object {
        const val ZERO_AMOUNT_INVOICE =
            "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
        const val AMOUNT_INVOICE =
            "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"
    }
}
