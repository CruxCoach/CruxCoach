package com.cruxcoach.android.nostr.relaydiscovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Nip65RelayListParserTest {

    @Test
    fun `multi-relay event with mixed markers parses all three markers correctly`() {
        val tags = listOf(
            listOf("r", "wss://relay.example.com"),
            listOf("r", "wss://nos.lol", "read"),
            listOf("r", "wss://relay.primal.net", "write"),
        )
        val result = Nip65TagParser.parse(tags)

        assertEquals(3, result.size)
        assertEquals(
            Kind10002Event.RelayMarker("wss://relay.example.com", read = true, write = true),
            result[0],
        )
        assertEquals(
            Kind10002Event.RelayMarker("wss://nos.lol", read = true, write = false),
            result[1],
        )
        assertEquals(
            Kind10002Event.RelayMarker("wss://relay.primal.net", read = false, write = true),
            result[2],
        )
    }

    @Test
    fun `missing marker defaults to both read and write`() {
        val tags = listOf(listOf("r", "wss://relay.example.com"))
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertTrue(result[0].read)
        assertTrue(result[0].write)
    }

    @Test
    fun `unknown marker falls back to permissive default`() {
        val tags = listOf(listOf("r", "wss://relay.example.com", "readwrite"))
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertTrue(result[0].read)
        assertTrue(result[0].write)
    }

    @Test
    fun `non-wss scheme is skipped without failing the event`() {
        val tags = listOf(
            listOf("r", "http://relay.example.com"),
            listOf("r", "wss://valid.example.com"),
            listOf("r", "ftp://some.server"),
        )
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertEquals("wss://valid.example.com", result[0].url)
    }

    @Test
    fun `empty url is skipped`() {
        val tags = listOf(
            listOf("r", ""),
            listOf("r", "wss://valid.example.com"),
        )
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertEquals("wss://valid.example.com", result[0].url)
    }

    @Test
    fun `duplicate urls are deduplicated with first marker winning`() {
        val tags = listOf(
            listOf("r", "wss://relay.example.com", "read"),
            listOf("r", "wss://relay.example.com", "write"),
        )
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertTrue(result[0].read)
        assertEquals(false, result[0].write)
    }

    @Test
    fun `non-r tags are ignored`() {
        val tags = listOf(
            listOf("p", "somepubkey"),
            listOf("e", "someeventid"),
            listOf("r", "wss://valid.example.com"),
            listOf("t", "sometopic"),
        )
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertEquals("wss://valid.example.com", result[0].url)
    }

    @Test
    fun `single-element tag is skipped`() {
        val tags = listOf(
            listOf("r"),
            listOf("r", "wss://valid.example.com"),
        )
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertEquals("wss://valid.example.com", result[0].url)
    }

    @Test
    fun `empty tag list returns empty result`() {
        val result = Nip65TagParser.parse(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `marker whitespace is trimmed before comparison`() {
        val tags = listOf(listOf("r", "wss://relay.example.com", "  READ  "))
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
        assertTrue(result[0].read)
        assertEquals(false, result[0].write)
    }

    @Test
    fun `trailing slash in url is stripped so equality works`() {
        val tags = listOf(
            listOf("r", "wss://relay.example.com/"),
            listOf("r", "wss://relay.example.com"),
        )
        val result = Nip65TagParser.parse(tags)
        assertEquals(1, result.size)
    }

    // ── MAX_RELAYS=32 cap (B7 parse-boundary defense) ────────────────────────
    // A hostile bootstrap relay could publish a Kind 10002 event with
    // thousands of `r` tags. Without the cap the parser allocates a
    // thousand-entry list and every downstream pool-update path runs
    // O(n²). These tests pin the cap at 32 so a future "let's relax it"
    // refactor surfaces as a test failure that needs an explicit choice,
    // not a silent regression.

    @Test
    fun `accepts up to 32 distinct relays`() {
        val tags = (1..32).map { listOf("r", "wss://relay$it.example.com") }
        val result = Nip65TagParser.parse(tags)
        assertEquals(32, result.size)
    }

    @Test
    fun `caps oversized list at 32 entries (drops the rest)`() {
        val tags = (1..50).map { listOf("r", "wss://relay$it.example.com") }
        val result = Nip65TagParser.parse(tags)
        assertEquals(32, result.size)
        // First 32 are kept (input order preserved), entries 33+ silently dropped.
        assertEquals("wss://relay1.example.com", result.first().url)
        assertEquals("wss://relay32.example.com", result.last().url)
    }

    @Test
    fun `cap counts deduplicated entries, not raw tag count`() {
        // 30 distinct relays + 5 duplicates of the first → 30 kept; the
        // duplicates don't eat into the cap. This protects the legitimate
        // user from a misbehaving relay padding their list with copies.
        val tags = (1..30).map { listOf("r", "wss://relay$it.example.com") } +
            List(5) { listOf("r", "wss://relay1.example.com") }
        val result = Nip65TagParser.parse(tags)
        assertEquals(30, result.size)
    }
}
