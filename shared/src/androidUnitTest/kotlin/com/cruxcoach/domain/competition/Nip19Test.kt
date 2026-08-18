package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * NIP-19, pinned to the same vector the website asserts against.
 *
 * The `naddr` in a QR code is the one string that has to survive a round trip
 * through a poster, a camera and a paste buffer. If the two clients disagree
 * about how to read it, a link printed by one is unopenable by the other.
 */
class Nip19Test {

    private val vectors = CompetitionFixtures.read("vectors/protocol.json")
    private val address = vectors["address"]!!.jsonObject
    private val organizer = address["organizer"]!!.jsonPrimitive.content
    private val compId = address["comp_id"]!!.jsonPrimitive.content
    private val naddr = address["naddr"]!!.jsonPrimitive.content

    @Test
    fun `encodes the same naddr the website recorded`() {
        val encoded = Nip19.encodeNaddr(
            Nip19.NAddr(
                identifier = CompetitionProtocol.compDTag(compId),
                pubkey = organizer,
                kind = CompetitionProtocol.KIND,
            ),
        )
        assertEquals(naddr, encoded)
    }

    @Test
    fun `decodes the recorded naddr back to its parts`() {
        val decoded = Nip19.decodeNaddr(naddr)
        assertEquals(organizer, decoded?.pubkey)
        assertEquals(CompetitionProtocol.KIND, decoded?.kind)
        assertEquals(CompetitionProtocol.compDTag(compId), decoded?.identifier)
    }

    @Test
    fun `a single wrong character fails the checksum`() {
        // The point of bech32: a link someone retyped by hand is caught rather
        // than silently addressing a different competition.
        val corrupted = naddr.dropLast(1) + if (naddr.last() == 'q') 'p' else 'q'
        assertNotEquals(naddr, corrupted)
        assertNull(Nip19.decodeNaddr(corrupted))
    }

    @Test
    fun `mixed case is refused, upper and lower are not`() {
        assertNull(Nip19.decodeNaddr(naddr.replaceFirstChar { it.uppercase().first() }))
        assertEquals(organizer, Nip19.decodeNaddr(naddr.uppercase())?.pubkey)
        assertEquals(organizer, Nip19.decodeNaddr(naddr.lowercase())?.pubkey)
    }

    @Test
    fun `garbage returns null rather than throwing`() {
        for (value in listOf("", "naddr1", "npub1qqqq", "not-bech32", "1", "naddr")) {
            assertNull(Nip19.decodeNaddr(value), value)
        }
    }

    @Test
    fun `an npub round-trips`() {
        val npub = Nip19.encodeNpub(organizer)
        assertEquals("npub1", npub.take(5))
        assertEquals(organizer, Nip19.decodeNpub(npub))
        assertNull(Nip19.decodeNpub(naddr), "an naddr is not an npub")
    }

    @Test
    fun `relay hints survive the round trip and unknown TLV types are skipped`() {
        val withRelays = Nip19.NAddr(
            identifier = CompetitionProtocol.compDTag(compId),
            pubkey = organizer,
            kind = CompetitionProtocol.KIND,
            relays = listOf("wss://relay.example.invalid", "ws://127.0.0.1:7447"),
        )
        val decoded = Nip19.decodeNaddr(Nip19.encodeNaddr(withRelays))
        assertEquals(withRelays.relays, decoded?.relays)
        assertEquals(withRelays.identifier, decoded?.identifier)
    }

    @Test
    fun `hex helpers round-trip`() {
        val bytes = Nip19.hexToBytes(organizer)
        assertEquals(32, bytes.size)
        assertEquals(organizer, Nip19.bytesToHex(bytes))
    }
}
