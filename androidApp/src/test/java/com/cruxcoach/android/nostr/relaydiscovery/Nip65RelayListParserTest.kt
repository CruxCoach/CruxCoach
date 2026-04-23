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
}
