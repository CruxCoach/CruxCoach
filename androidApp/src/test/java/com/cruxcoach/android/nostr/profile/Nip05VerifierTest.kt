package com.cruxcoach.android.nostr.profile

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-function coverage for [Nip05Verifier]'s parser helpers. The
 * full HTTP flow goes through OkHttp; that's covered manually on
 * device. These tests pin the parser to make sure malformed inputs
 * and known-good well-known JSON shapes are interpreted correctly.
 */
class Nip05VerifierTest {

    @Test
    fun parseAddress_lowercases_and_splits_local_and_domain() {
        assertEquals("alice" to "example.com", Nip05Verifier.parseAddress("alice@example.com"))
        assertEquals("alice" to "example.com", Nip05Verifier.parseAddress("Alice@Example.COM"))
        assertEquals("a.b_c-1" to "example.com", Nip05Verifier.parseAddress("a.b_c-1@example.com"))
    }

    @Test
    fun parseAddress_rejects_malformed_inputs() {
        assertNull(Nip05Verifier.parseAddress(""))
        assertNull(Nip05Verifier.parseAddress("alice"))                  // no @
        assertNull(Nip05Verifier.parseAddress("alice@"))                 // empty domain
        assertNull(Nip05Verifier.parseAddress("@example.com"))           // empty local
        assertNull(Nip05Verifier.parseAddress("alice@invalid"))          // domain has no dot
        assertNull(Nip05Verifier.parseAddress("ali ce@example.com"))     // space in local
        assertNull(Nip05Verifier.parseAddress("ali+ce@example.com"))     // forbidden char
    }

    @Test
    fun parsePubkey_extracts_from_well_known_json() {
        val json = """{"names":{"alice":"abcd1234","bob":"5678"}}"""
        assertEquals("abcd1234", Nip05Verifier.parsePubkey(json, "alice"))
        assertEquals("5678", Nip05Verifier.parsePubkey(json, "bob"))
    }

    @Test
    fun parsePubkey_returns_null_on_missing_local_or_malformed_json() {
        val json = """{"names":{"alice":"abcd"}}"""
        assertNull(Nip05Verifier.parsePubkey(json, "carol"))             // local not in map
        assertNull(Nip05Verifier.parsePubkey("not-json", "alice"))        // not JSON
        assertNull(Nip05Verifier.parsePubkey("""{}""", "alice"))          // no names key
        assertNull(Nip05Verifier.parsePubkey("""{"names":{}}""", "x"))    // empty names
    }
}
