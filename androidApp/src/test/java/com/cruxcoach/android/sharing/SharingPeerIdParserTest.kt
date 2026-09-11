package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.PeerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * FEAT-062 §1.5: one canonical identity for a peer.
 *
 * The invite field said "npub" and stored whatever was typed, but every
 * signature check works on the 32-byte public key as lower-case hex. A peer
 * invited with a real bech32 `npub1…` therefore got a `PeerId` that no
 * acceptance and no device authorisation could ever verify against — the
 * relationship was permanently unusable, and an npub and its own hex would have
 * produced two separate relationships for one person.
 *
 * The field still accepts either form. The parser is what makes them the same
 * identity.
 *
 * The npub/hex vector below is this project's own maintainer key
 * (`MAINTAINER_PUBKEY` in `androidApp/build.gradle.kts`), decoded
 * independently rather than taken from the code under test.
 */
class SharingPeerIdParserTest {

    private companion object {
        const val NPUB = "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s"
        const val HEX = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"

        // Valid bech32 of the same 32 bytes under the wrong NIP-19 prefix, so
        // these are rejected for being the wrong *kind* of thing rather than
        // for being malformed.
        //
        // Deliberately not a well-formed one, and deliberately too short to be
        // mistaken for key material by a person or a secret scanner: `o` is not
        // even in the bech32 alphabet. The parser decides `nsec1` by prefix and
        // never decodes it, so this exercises exactly the branch a real one
        // would. See [SharingSourceHygieneTest] for the rule this obeys.
        const val NSEC = "nsec1" + "notakey"
        const val NOTE = "note1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxs2frknc"
        const val NPROFILE = "nprofile1qqswwksctsqe6zgyn40ukr3f5txfhlgpdmq0dkyjljv0dllqrqdysrgzradcr"
    }

    private fun valid(raw: String): PeerId {
        val result = SharingPeerIdParser.parse(raw)
        assertIs<PeerIdParseResult.Valid>(result, "expected $raw to parse")
        return result.peer
    }

    private fun invalid(raw: String): PeerIdParseError {
        val result = SharingPeerIdParser.parse(raw)
        assertIs<PeerIdParseResult.Invalid>(result, "expected $raw to be refused")
        return result.error
    }

    // ------------------------------------------------------------ accepted

    @Test
    fun a_real_npub_becomes_its_canonical_hex() {
        assertEquals(PeerId(HEX), valid(NPUB))
    }

    @Test
    fun canonical_hex_is_accepted_unchanged() {
        assertEquals(PeerId(HEX), valid(HEX))
    }

    @Test
    fun uppercase_hex_is_normalised_to_lower_case() {
        assertEquals(PeerId(HEX), valid(HEX.uppercase()))
    }

    @Test
    fun mixed_case_hex_is_normalised_too() {
        val mixed = HEX.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c }.joinToString("")
        assertEquals(PeerId(HEX), valid(mixed))
    }

    @Test
    fun surrounding_whitespace_is_ignored() {
        assertEquals(PeerId(HEX), valid("  $NPUB \n"))
        assertEquals(PeerId(HEX), valid("\t$HEX  "))
    }

    @Test
    fun an_npub_and_its_own_hex_are_one_identity() {
        // Otherwise inviting the same person twice, once each way, would create
        // two unrelated relationships for one human being.
        assertEquals(valid(NPUB), valid(HEX))
    }

    // ------------------------------------------------------------ refused

    @Test
    fun an_empty_input_is_refused() {
        assertEquals(PeerIdParseError.EMPTY, invalid(""))
        assertEquals(PeerIdParseError.EMPTY, invalid("   "))
    }

    @Test
    fun a_private_key_is_refused_and_never_stored() {
        assertEquals(PeerIdParseError.NOT_A_PUBLIC_KEY, invalid(NSEC))
    }

    @Test
    fun a_note_id_is_refused() {
        assertEquals(PeerIdParseError.NOT_A_PUBLIC_KEY, invalid(NOTE))
    }

    @Test
    fun an_nprofile_is_refused() {
        assertEquals(PeerIdParseError.NOT_A_PUBLIC_KEY, invalid(NPROFILE))
    }

    @Test
    fun hex_of_the_wrong_length_is_refused() {
        assertEquals(PeerIdParseError.MALFORMED, invalid(HEX.dropLast(1)))
        assertEquals(PeerIdParseError.MALFORMED, invalid(HEX + "ab"))
    }

    @Test
    fun hex_with_non_hex_characters_is_refused() {
        assertEquals(PeerIdParseError.MALFORMED, invalid(HEX.dropLast(1) + "z"))
    }

    @Test
    fun an_npub_with_a_broken_checksum_is_refused() {
        val broken = NPUB.dropLast(1) + if (NPUB.last() == 'q') 'p' else 'q'
        assertEquals(PeerIdParseError.MALFORMED, invalid(broken))
    }

    @Test
    fun assorted_rubbish_is_refused() {
        listOf(
            "npub1",
            "not an npub at all",
            "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s extra",
            "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d e75a",
            "https://example.com/npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s",
        ).forEach { rubbish ->
            assertIs<PeerIdParseResult.Invalid>(
                SharingPeerIdParser.parse(rubbish),
                "expected $rubbish to be refused",
            )
        }
    }
}
