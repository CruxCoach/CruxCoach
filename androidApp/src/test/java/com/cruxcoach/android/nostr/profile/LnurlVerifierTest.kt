package com.cruxcoach.android.nostr.profile

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LnurlVerifierTest {

    @Test
    fun parseAddress_splits_and_lowercases_domain() {
        assertEquals(
            "alice" to "walletofsatoshi.com",
            LnurlVerifier.parseAddress("alice@walletofsatoshi.com"),
        )
        // Domain is lowercased so the resulting URL is canonical, but
        // the local-part is preserved as-typed (LNURL-pay servers may
        // be case-sensitive on the local — we don't second-guess).
        assertEquals(
            "Alice" to "walletofsatoshi.com",
            LnurlVerifier.parseAddress("Alice@WalletOfSatoshi.COM"),
        )
    }

    @Test
    fun parseAddress_rejects_malformed_inputs() {
        assertNull(LnurlVerifier.parseAddress(""))
        assertNull(LnurlVerifier.parseAddress("alice"))               // no @
        assertNull(LnurlVerifier.parseAddress("alice@"))              // empty domain
        assertNull(LnurlVerifier.parseAddress("@example.com"))        // empty local
        assertNull(LnurlVerifier.parseAddress("alice@nodot"))         // domain has no dot
    }

    @Test
    fun parseAddress_rejects_local_with_path_injection_chars() {
        // Char-allowlist on local-part keeps URL-path-injection
        // payloads out of the interpolation
        // `https://$domain/.well-known/lnurlp/$local`. A bare `..` is
        // harmless without an accompanying slash, but the slash itself
        // is what makes traversal possible — that's the load-bearing
        // reject.
        assertNull(LnurlVerifier.parseAddress("alice/admin@example.com"))    // slash
        assertNull(LnurlVerifier.parseAddress("alice/../admin@example.com")) // path traversal
        assertNull(LnurlVerifier.parseAddress("ali ce@example.com"))         // space
        assertNull(LnurlVerifier.parseAddress("ali+ce@example.com"))         // forbidden char (LNURL spec excludes +)
        assertNull(LnurlVerifier.parseAddress("alice%2F@example.com"))       // percent
        assertNull(LnurlVerifier.parseAddress("alice?@example.com"))         // query-string char
    }

    @Test
    fun parseAddress_accepts_namelike_local_chars() {
        // NIP-05 allows lowercase letters/digits/-_. Mirroring LNURL
        // here, plus uppercase since some wallets are case-sensitive.
        assertEquals("a.b_c-1" to "example.com", LnurlVerifier.parseAddress("a.b_c-1@example.com"))
        assertEquals("Alice" to "example.com", LnurlVerifier.parseAddress("Alice@example.com"))
    }

    @Test
    fun hasCallback_recognises_lnurlp_response_shape() {
        val callbackPresent = """{"callback":"https://x/lnurlp/y","minSendable":1000,"maxSendable":1000000}"""
        assertTrue(LnurlVerifier.hasCallback(callbackPresent))
    }

    @Test
    fun hasCallback_false_on_missing_or_blank_or_bad_json() {
        assertFalse(LnurlVerifier.hasCallback("""{"minSendable":1000}"""))
        assertFalse(LnurlVerifier.hasCallback("""{"callback":""}"""))
        assertFalse(LnurlVerifier.hasCallback("not-json"))
        assertFalse(LnurlVerifier.hasCallback(""))
    }
}
