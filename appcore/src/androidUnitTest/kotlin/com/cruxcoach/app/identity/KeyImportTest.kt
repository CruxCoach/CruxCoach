package com.cruxcoach.app.identity

import com.cruxcoach.app.nostr.Nip19
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyImportTest {
    private val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
    private val hex = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
    private val pubkeyHex = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"

    @Test
    fun `format detection matches the Android table`() {
        assertEquals(KeyImport.Format.NSEC, KeyImport.detectFormat(nsec))
        assertEquals(KeyImport.Format.NCRYPTSEC, KeyImport.detectFormat("ncryptsec1abcdef"))
        assertEquals(KeyImport.Format.HEX, KeyImport.detectFormat(hex))
        assertEquals(KeyImport.Format.HEX, KeyImport.detectFormat("  $hex  "))
        assertEquals(KeyImport.Format.UNKNOWN, KeyImport.detectFormat(hex.uppercase()))
        assertEquals(KeyImport.Format.MNEMONIC, KeyImport.detectFormat(List(12) { "abandon" }.joinToString(" ")))
        assertEquals(KeyImport.Format.MNEMONIC, KeyImport.detectFormat(List(24) { "abandon" }.joinToString("\n")))
        assertEquals(KeyImport.Format.UNKNOWN, KeyImport.detectFormat(List(25) { "abandon" }.joinToString(" ")))
        assertEquals(KeyImport.Format.UNKNOWN, KeyImport.detectFormat(""))
        assertEquals(KeyImport.Format.UNKNOWN, KeyImport.detectFormat("npub1xyz"))
    }

    @Test
    fun `nsec and hex import to the same account and preview its npub`() {
        for (input in listOf(nsec, hex, " $hex\n")) {
            val ready = assertIs<KeyImport.Result.Ready>(KeyImport.preview(input, activePubkeyHex = null))
            assertEquals(pubkeyHex, ready.candidate.pubkeyHex)
            assertEquals(Nip19.encodeNpub(pubkeyHex), ready.candidate.npub)
            assertEquals(hex, ready.candidate.secretKey.toHex())
            assertFalse(ready.candidate.sameAccount)
            assertFalse(ready.candidate.replacesLocalKey)
            // The secret never leaks through the preview's own description.
            assertFalse(ready.candidate.toString().contains(hex))
        }
    }

    @Test
    fun `same account and replacement are reported for the confirm dialog`() {
        val same = assertIs<KeyImport.Result.Ready>(KeyImport.preview(nsec, pubkeyHex))
        assertTrue(same.candidate.sameAccount)
        assertFalse(same.candidate.replacesLocalKey)
        val other = assertIs<KeyImport.Result.Ready>(
            KeyImport.preview(nsec, "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"),
        )
        assertFalse(other.candidate.sameAccount)
        assertTrue(other.candidate.replacesLocalKey)
    }

    @Test
    fun `malformed and unsupported inputs are rejected with a code`() {
        fun failure(input: String) = assertIs<KeyImport.Result.Rejected>(KeyImport.preview(input, null)).failure
        assertEquals(KeyImport.Failure.INVALID_NSEC, failure(nsec.dropLast(1) + "q"))
        assertEquals(KeyImport.Failure.INVALID_NSEC, failure("nsec1"))
        assertEquals(KeyImport.Failure.UNKNOWN_FORMAT, failure("hello"))
        assertEquals(KeyImport.Failure.INVALID_NCRYPTSEC, failure("ncryptsec1qqqq"))
        // Twelve known words whose checksum fails (the valid all-zero mnemonic ends in "about").
        assertEquals(KeyImport.Failure.INVALID_MNEMONIC_CHECKSUM, failure(List(12) { "abandon" }.joinToString(" ")))
        assertEquals(KeyImport.Failure.UNKNOWN_MNEMONIC_WORD, failure(List(11) { "abandon" }.joinToString(" ") + " abou"))
        assertEquals(KeyImport.Failure.INVALID_MNEMONIC_LENGTH, failure(List(13) { "abandon" }.joinToString(" ")))
        // 64 hex characters that are not a valid scalar: zero and n itself.
        assertEquals(KeyImport.Failure.INVALID_HEX, failure("0".repeat(64)))
        assertEquals(
            KeyImport.Failure.INVALID_HEX,
            failure("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141"),
        )
    }

    private val specNcryptsec =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"

    @Test
    fun `ncryptsec asks for its password and then previews the NIP-49 vector account`() {
        assertEquals(KeyImport.Format.NCRYPTSEC, KeyImport.detectFormat(specNcryptsec))
        assertTrue(KeyImport.isSlow(specNcryptsec))
        assertNull(KeyImport.checkNcryptsec(specNcryptsec, maxScryptLogN = 16))
        assertEquals(KeyImport.Failure.NCRYPTSEC_TOO_EXPENSIVE, KeyImport.checkNcryptsec(specNcryptsec, maxScryptLogN = 15))
        assertEquals(
            KeyImport.Failure.PASSWORD_REQUIRED,
            assertIs<KeyImport.Result.Rejected>(KeyImport.preview(specNcryptsec, null, null, 16)).failure,
        )
        assertEquals(
            KeyImport.Failure.WRONG_PASSWORD,
            assertIs<KeyImport.Result.Rejected>(KeyImport.preview(specNcryptsec, "nostr!", null, 16)).failure,
        )
        val ready = assertIs<KeyImport.Result.Ready>(KeyImport.preview(" $specNcryptsec\n", "nostr", null, 16))
        assertEquals("3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683", ready.candidate.secretKey.toHex())
        assertEquals(Nip19.encodeNpub(NostrKeys.publicKeyHex(ready.candidate.secretKey)!!), ready.candidate.npub)
    }

    @Test
    fun `recovery words preview the NIP-06 vector account`() {
        val words = "leader monkey parrot ring guide accident before fence cannon height naive bean"
        assertEquals(KeyImport.Format.MNEMONIC, KeyImport.detectFormat(words))
        assertTrue(KeyImport.isSlow(words))
        val ready = assertIs<KeyImport.Result.Ready>(KeyImport.preview(words.replace(" ", "\n"), null))
        assertEquals("7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a", ready.candidate.secretKey.toHex())
        assertEquals("npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu", ready.candidate.npub)
        assertFalse(KeyImport.isSlow(nsec))
    }

    @Test
    fun `export produces the nsec the key was imported from`() {
        val ready = assertIs<KeyImport.Result.Ready>(KeyImport.preview(hex, null))
        assertEquals(nsec, KeyExport.nsec(ready.candidate.secretKey))
        assertEquals(ready.candidate.npub, KeyExport.npub(ready.candidate.secretKey))
        assertNull(KeyExport.nsec(ByteArray(32)))
        assertNull(KeyExport.nsec(ByteArray(31) { 3 }))
        // Round trip through a freshly generated key.
        val fresh = assertNotNull(NostrKeys.generateSecretKey(JvmHashing))
        val exported = assertNotNull(KeyExport.nsec(fresh))
        assertEquals(fresh.toHex(), assertIs<KeyImport.Result.Ready>(KeyImport.preview(exported, null)).candidate.secretKey.toHex())
    }
}
