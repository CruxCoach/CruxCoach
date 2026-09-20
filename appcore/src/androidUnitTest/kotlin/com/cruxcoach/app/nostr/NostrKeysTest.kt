package com.cruxcoach.app.nostr

import com.cruxcoach.app.testing.Fixtures
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import fr.acinq.secp256k1.Secp256k1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NostrKeysTest {
    private fun hex(s: String) = s.hexToBytesOrNull()!!

    @Test
    fun `official BIP-340 vectors`() {
        val rows = Fixtures.text("nostr/bip340-test-vectors.csv").trim().lines().drop(1).map { it.split(",") }
        var verified = 0
        var signed = 0
        for (row in rows) {
            val message = hex(row[4])
            // libsecp256k1's binding signs/verifies 32-byte messages only (all Nostr needs).
            if (message.size != 32) continue
            val expected = row[6] == "TRUE"
            val actual = try {
                Secp256k1.verifySchnorr(hex(row[5]), message, hex(row[2]))
            } catch (e: Exception) {
                false
            }
            assertEquals(expected, actual, "vector ${row[0]}")
            verified++
            if (row[1].isNotEmpty()) {
                val secret = hex(row[1])
                assertEquals(row[2].lowercase(), NostrKeys.publicKeyHex(secret), "pubkey ${row[0]}")
                assertEquals(row[5].lowercase(), Secp256k1.signSchnorr(message, secret, hex(row[3])).toHex(), "sig ${row[0]}")
                signed++
            }
        }
        assertEquals(15, verified)
        assertEquals(4, signed)
    }

    @Test
    fun `invalid scalars are rejected`() {
        assertFalse(NostrKeys.isValidSecretKey(ByteArray(32)))
        assertFalse(NostrKeys.isValidSecretKey(ByteArray(31) { 1 }))
        // The group order n and n+… are not valid secret keys; n-1 is.
        val n = hex("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141")
        assertFalse(NostrKeys.isValidSecretKey(n))
        assertFalse(NostrKeys.isValidSecretKey(ByteArray(32) { 0xff.toByte() }))
        assertTrue(NostrKeys.isValidSecretKey(hex("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140")))
        assertNull(NostrKeys.xOnlyPublicKey(n))
        assertNull(NostrKeys.sign(JvmHashing, n, 1, 1, emptyList(), ""))
    }

    @Test
    fun `generated keys are valid and distinct`() {
        val a = assertNotNull(NostrKeys.generateSecretKey(JvmHashing))
        val b = assertNotNull(NostrKeys.generateSecretKey(JvmHashing))
        assertTrue(NostrKeys.isValidSecretKey(a))
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `signed events verify and use fresh aux randomness`() {
        val secret = hex("67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa")
        val tags = listOf(listOf("d", "x\"y\\z"), listOf("t", "ü\n"))
        val one = assertNotNull(NostrKeys.sign(JvmHashing, secret, 1_700_000_000, 30078, tags, "héllo   \"q\""))
        val two = assertNotNull(NostrKeys.sign(JvmHashing, secret, 1_700_000_000, 30078, tags, "héllo   \"q\""))
        assertEquals("7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e", one.pubkey)
        assertTrue(NostrEvents.verify(JvmHashing, one))
        assertTrue(NostrEvents.verify(JvmHashing, two))
        assertEquals(one.id, two.id)
        assertNotEquals(one.sig, two.sig)
        assertFalse(NostrEvents.verify(JvmHashing, one.copy(content = "tampered")))
        // Survives a JSON round trip.
        assertEquals(one, NostrEvent.fromJson(one.toJson()))
    }

    @Test
    fun `HKDF matches RFC 5869`() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hkdf.extractExpand(JvmHashing, ikm, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42).toHex(),
        )
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            Hkdf.extract(JvmHashing, hex("000102030405060708090a0b0c"), ikm).toHex(),
        )
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            Hkdf.extractExpand(
                JvmHashing,
                ByteArray(80) { it.toByte() },
                ByteArray(80) { (0x60 + it).toByte() },
                ByteArray(80) { (0xb0 + it).toByte() },
                82,
            ).toHex(),
        )
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hkdf.extractExpand(JvmHashing, ikm, null, ByteArray(0), 42).toHex(),
        )
    }
}
