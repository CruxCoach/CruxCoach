package com.cruxcoach.app.nostr

import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Nip19Test {
    // The two worked examples in the NIP-19 specification.
    private val specNpub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
    private val specNpubHex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val specNsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private val specNsecHex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"

    @Test
    fun `npub and nsec match the NIP-19 spec examples`() {
        assertEquals(specNpub, Nip19.encodeNpub(specNpubHex))
        assertEquals(specNpubHex, Nip19.decodeNpub(specNpub))
        assertEquals(specNsec, Nip19.encodeNsec(specNsecHex.hexToBytesOrNull()!!))
        assertEquals(specNsecHex, Nip19.decodeNsec(specNsec)?.toHex())
        // The spec's nsec is the secret key of a different keypair than its npub;
        // deriving it proves the decoded bytes are usable, not just well-formed.
        assertEquals(
            "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e",
            NostrKeys.publicKeyHex(Nip19.decodeNsec(specNsec)!!),
        )
    }

    @Test
    fun `decoding is strict`() {
        assertNull(Nip19.decodeNpub(specNpub.uppercase()), "uppercase")
        assertNull(Nip19.decodeNpub(specNpub.replaceFirstChar { 'N' }), "mixed case")
        assertNull(Nip19.decodeNpub(specNpub.dropLast(1) + "q"), "bad checksum")
        assertNull(Nip19.decodeNpub(specNpub.dropLast(1)), "truncated")
        assertNull(Nip19.decodeNpub(specNsec), "wrong hrp")
        assertNull(Nip19.decodeNsec(specNpub), "wrong hrp")
        assertNull(Nip19.decodeNpub("npub1" + "b".repeat(6)), "charset")
        assertNull(Nip19.decodeNpub(""), "empty")
        assertNull(Nip19.decodeNpub("npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"), "wrong payload length")
        assertNull(Nip19.encodeNpub(specNpubHex.uppercase()))
        assertNull(Nip19.encodeNsec(ByteArray(31)))
    }

    // Golden strings produced by an independent bech32 implementation (the
    // BIP-173 reference code in Python) over the same TLV payload.
    private val climbAuthor = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"
    private val climbUuid = "4f0e2b1c-9a3d-4e5f-8a7b-1c2d3e4f5a6b"

    @Test
    fun `naddr for a CruxCoach climb link`() {
        val expectedNoRelays =
            "naddr1qq7kxun40p3k7ctrdqaxxmrfd43r5dm9xajnjce5xgarge3sv5exyvtr95ukzvmy956x2dtx95uxzdmz" +
                "95ckxvnyxdjnge34vymxyq3q0elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qxpqqqp6huzajv0r"
        assertEquals(expectedNoRelays, Nip19.encodeClimbNaddr(climbAuthor, climbUuid))

        val expectedWithRelays =
            "naddr1qq7kxun40p3k7ctrdqaxxmrfd43r5dm9xajnjce5xgarge3sv5exyvtr95ukzvmy956x2dtx95uxzdmz" +
                "95ckxvnyxdjnge34vymxyqg5waehxw309aex2mrp0yhxgctdw4eju6t0qyxhwumn8ghj7mn0wvhxcmmvqgs8ul5u" +
                "g253hlh3n75jne0a5xmjur4urfxpzst88cnegg6ds6ka7nsrqsqqqat7v848px"
        val relays = listOf("wss://relay.damus.io", "wss://nos.lol")
        assertEquals(expectedWithRelays, Nip19.encodeClimbNaddr(climbAuthor, climbUuid, relays))

        val decoded = assertNotNull(Nip19.decodeNaddr(expectedWithRelays))
        assertEquals(30078, decoded.kind)
        assertEquals(climbAuthor, decoded.authorHex)
        assertEquals("cruxcoach:climb:7e7e9c42:$climbUuid", decoded.identifier)
        assertEquals(relays, decoded.relays)
        assertEquals(climbUuid, Nip19.climbUuidFromNaddr(expectedWithRelays))
        assertEquals(climbUuid, Nip19.climbUuidFromNaddr(expectedNoRelays))
    }

    @Test
    fun `naddr round trips and rejects foreign or malformed links`() {
        val naddr = Nip19.Naddr(kind = 0, authorHex = specNpubHex, identifier = "", relays = emptyList())
        val encoded = assertNotNull(Nip19.encodeNaddr(naddr))
        assertEquals(naddr, Nip19.decodeNaddr(encoded))
        val unicode = Nip19.Naddr(30078, specNpubHex, "ü/\u0000:…", listOf("wss://a.example"))
        assertEquals(unicode, Nip19.decodeNaddr(assertNotNull(Nip19.encodeNaddr(unicode))))

        assertNull(Nip19.decodeNaddr(specNpub), "npub is not an naddr")
        // A well-formed naddr for someone else's kind is not a climb link.
        val foreign = Nip19.encodeNaddr(Nip19.Naddr(30023, specNpubHex, "cruxcoach:climb:3bf0c63f:$climbUuid"))!!
        assertNull(Nip19.climbUuidFromNaddr(foreign), "wrong kind")
        // d-tag pubkey prefix must match the author of the naddr.
        val mismatched = Nip19.encodeNaddr(
            Nip19.Naddr(30078, specNpubHex, "cruxcoach:climb:7e7e9c42:$climbUuid"),
        )!!
        assertNull(Nip19.climbUuidFromNaddr(mismatched), "author/d-tag mismatch")
        assertNull(Nip19.climbUuidFromNaddr(Nip19.encodeNaddr(Nip19.Naddr(30078, specNpubHex, "other:thing"))!!))
        assertNull(Nip19.encodeNaddr(Nip19.Naddr(30078, specNpubHex, "x", List(17) { "wss://r$it" })), "relay cap")
        assertNull(Nip19.encodeNaddr(Nip19.Naddr(30078, specNpubHex, "x".repeat(1025))), "identifier cap")
    }

    @Test
    fun `bech32 payload padding must be canonical`() {
        // Re-encoding 33 bytes of payload as npub yields a 53-group body with
        // non-zero padding bits; the decoder must refuse it.
        val nonCanonical = Bech32.encode("npub", Bech32.toBase5(ByteArray(32) { 0xff.toByte() }) + byteArrayOf(1))
        assertNotNull(nonCanonical)
        assertNull(Nip19.decodeNpub(nonCanonical))
        assertTrue(Bech32.fromBase5(byteArrayOf(31, 31)) == null || Bech32.fromBase5(byteArrayOf(0, 0))!!.isEmpty())
    }
}
