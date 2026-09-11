package com.cruxcoach.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * BIP-173 bech32 decoding, as NIP-19 uses it.
 *
 * Hand-written rather than delegated, for one reason worth recording: the
 * Nostr library already on the classpath compiles its NIP-19 parser to a newer
 * bytecode level than the unit-test JVM runs, so calling it would leave the
 * npub path untestable — and an untested identity parser is precisely what let
 * an un-verifiable peer identity into the ledger in the first place.
 *
 * The vector is this project's own maintainer key, decoded independently of any
 * code under test.
 */
class Bech32Test {

    private companion object {
        const val NPUB = "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s"
        const val HEX = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"
    }

    private fun ByteArray.hex() = joinToString("") { b ->
        ((b.toInt() and 0xff) + 0x100).toString(16).substring(1)
    }

    @Test
    fun a_known_npub_decodes_to_its_known_key() {
        val decoded = Bech32.decode(NPUB)
        assertEquals("npub", decoded?.hrp)
        assertEquals(HEX, decoded?.data?.hex())
    }

    @Test
    fun the_payload_is_exactly_thirty_two_bytes() {
        assertEquals(32, Bech32.decode(NPUB)?.data?.size)
    }

    @Test
    fun an_all_uppercase_string_decodes_the_same() {
        assertEquals(HEX, Bech32.decode(NPUB.uppercase())?.data?.hex())
    }

    @Test
    fun mixed_case_is_rejected_as_the_specification_requires() {
        val mixed = NPUB.replaceFirst("npub1", "NPub1")
        assertNull(Bech32.decode(mixed))
    }

    @Test
    fun a_single_altered_character_fails_the_checksum() {
        // Every position in the data part, one substitution each.
        val data = NPUB.substringAfter("npub1")
        data.indices.forEach { i ->
            val replacement = if (data[i] == 'q') 'p' else 'q'
            val broken = "npub1" + data.replaceRange(i, i + 1, replacement.toString())
            assertNull(Bech32.decode(broken), "position $i must fail the checksum")
        }
    }

    @Test
    fun a_character_outside_the_alphabet_is_rejected() {
        assertNull(Bech32.decode(NPUB.dropLast(1) + "b"))
        assertNull(Bech32.decode(NPUB.dropLast(1) + "i"))
        assertNull(Bech32.decode(NPUB.dropLast(1) + "o"))
    }

    @Test
    fun a_string_without_a_separator_is_rejected() {
        assertNull(Bech32.decode("npubuadpshqpn5ysf82lev8zngkvn07szmkq"))
    }

    @Test
    fun an_empty_or_tiny_string_is_rejected() {
        assertNull(Bech32.decode(""))
        assertNull(Bech32.decode("1"))
        assertNull(Bech32.decode("npub1"))
    }

    @Test
    fun a_string_with_no_human_readable_part_is_rejected() {
        assertNull(Bech32.decode("1" + NPUB.substringAfter("npub1")))
    }

    @Test
    fun an_over_long_string_is_rejected() {
        assertNull(Bech32.decode("npub1" + "q".repeat(120)))
    }

    @Test
    fun a_control_character_is_rejected() {
        assertNull(Bech32.decode(NPUB + "\u0000"))
        assertNull(Bech32.decode(NPUB + " "))
    }

    @Test
    fun the_human_readable_part_is_reported_so_the_caller_can_check_the_type() {
        // A well-formed bech32 string whose hrp happens to be `nsec`: it
        // decodes fine, and the caller refuses it on the hrp rather than on the
        // checksum. The payload is four zero symbols rather than anything
        // key-shaped — see SharingSourceHygieneTest for why this feature keeps
        // no private-key-shaped literal, even a harmless one.
        val nsec = "nsec1qqqq5ur7sx"
        assertEquals("nsec", Bech32.decode(nsec)?.hrp)
    }
}
