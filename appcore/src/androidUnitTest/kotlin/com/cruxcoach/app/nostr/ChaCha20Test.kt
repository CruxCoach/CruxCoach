package com.cruxcoach.app.nostr

import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RFC 8439 test vectors for the IETF ChaCha20 stream cipher. */
class ChaCha20Test {
    private fun hex(s: String) = s.hexToBytesOrNull()!!

    private fun keystream(key: ByteArray, nonce: ByteArray, counter: Int, length: Int): String =
        assertNotNull(ChaCha20.xor(key, nonce, counter, ByteArray(length))).toHex()

    @Test
    fun `RFC 8439 section 2_3_2 block function`() {
        assertEquals(
            "10f1e7e4d13b5915500fdd1fa32071c4c7d1f4c733c068030422aa9ac3d46c4e" +
                "d2826446079faa0914c2d705d98b02a2b5129cd1de164eb9cbd083e8a2503c4e",
            keystream(ByteArray(32) { it.toByte() }, hex("000000090000004a00000000"), 1, 64),
        )
    }

    @Test
    fun `RFC 8439 appendix A_1 keystream vectors`() {
        assertEquals(
            "76b8e0ada0f13d90405d6ae55386bd28bdd219b8a08ded1aa836efcc8b770dc7" +
                "da41597c5157488d7724e03fb8d84a376a43b8f41518a11cc387b669b2ee6586",
            keystream(ByteArray(32), ByteArray(12), 0, 64),
        )
        assertEquals(
            "3aeb5224ecf849929b9d828db1ced4dd832025e8018b8160b82284f3c949aa5a" +
                "8eca00bbb4a73bdad192b5c42f73f2fd4e273644c8b36125a64addeb006c13a0",
            keystream(ByteArray(32).also { it[31] = 1 }, ByteArray(12), 1, 64),
        )
    }

    @Test
    fun `RFC 8439 section 2_4_2 encryption vector`() {
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for " +
                "the future, sunscreen would be it."
            ).encodeToByteArray()
        val ciphertext = assertNotNull(
            ChaCha20.xor(ByteArray(32) { it.toByte() }, hex("000000000000004a00000000"), 1, plaintext),
        )
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d",
            ciphertext.toHex(),
        )
        // The cipher is its own inverse and spans block boundaries correctly.
        assertEquals(
            plaintext.toHex(),
            assertNotNull(ChaCha20.xor(ByteArray(32) { it.toByte() }, hex("000000000000004a00000000"), 1, ciphertext)).toHex(),
        )
    }

    @Test
    fun `wrong key or nonce sizes fail instead of truncating`() {
        assertNull(ChaCha20.xor(ByteArray(31), ByteArray(12), 0, ByteArray(4)))
        assertNull(ChaCha20.xor(ByteArray(32), ByteArray(8), 0, ByteArray(4)))
    }

    @Test
    fun `base64 round trips and rejects malformed input`() {
        for (size in 0..8) {
            val data = ByteArray(size) { (it * 37 + 5).toByte() }
            assertEquals(data.toHex(), assertNotNull(Base64.decode(Base64.encode(data))).toHex())
        }
        assertEquals("AgAB", Base64.encode(byteArrayOf(2, 0, 1)))
        assertNull(Base64.decode("#AAA"))
        assertNull(Base64.decode("AAAA="))
        assertNull(Base64.decode("A A A"))
        assertNull(Base64.decode("AA=A"))
    }
}
