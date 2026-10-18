package com.cruxcoach.app.crypto

import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The hand-written primitives behind ncryptsec and recovery-word import, held
 * to published vectors (FIPS 180, RFC 7914, RFC 8439, draft-irtf-cfrg-xchacha-03)
 * and cross-checked against the JVM's own implementations.
 */
class CryptoPrimitivesTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").replace(":", "").lowercase().hexToBytesOrNull()!!

    @Test
    fun `sha256 and sha512 match the FIPS abc vectors and the JVM at every padding boundary`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.digest("abc".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            Sha512.digest("abc".encodeToByteArray()).toHex(),
        )
        val random = Random(7)
        for (length in 0..300) {
            val data = random.nextBytes(length)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(data), Sha256.digest(data), "sha256 len $length")
            assertContentEquals(MessageDigest.getInstance("SHA-512").digest(data), Sha512.digest(data), "sha512 len $length")
            // Split input hashes like the concatenation.
            val cut = length / 3
            assertContentEquals(Sha512.digest(data), Sha512.digest(data.copyOfRange(0, cut), data.copyOfRange(cut, length)))
        }
    }

    @Test
    fun `hmac matches the JVM for short and long keys`() {
        val random = Random(11)
        for (keyLength in listOf(0, 1, 32, 64, 65, 128, 129, 200)) {
            val key = random.nextBytes(keyLength)
            val data = random.nextBytes(77)
            fun jvm(algorithm: String) = Mac.getInstance(algorithm).apply {
                init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, algorithm))
            }
            if (key.isNotEmpty()) {
                assertContentEquals(jvm("HmacSHA256").doFinal(data), Hmac.SHA256.mac(key, data))
                assertContentEquals(jvm("HmacSHA512").doFinal(data), Hmac.SHA512.mac(key, data))
            }
        }
    }

    @Test
    fun `pbkdf2 matches RFC 7914 section 11 and the JVM for sha512`() {
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            Pbkdf2.hmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64).toHex(),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            Pbkdf2.hmacSha256("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80000, 64).toHex(),
        )
        val spec = PBEKeySpec("hunter2".toCharArray(), "mnemonicTREZOR".encodeToByteArray(), 2048, 512)
        val jvm = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        assertContentEquals(jvm, Pbkdf2.hmacSha512("hunter2".encodeToByteArray(), "mnemonicTREZOR".encodeToByteArray(), 2048, 64))
    }

    @Test
    fun `scrypt matches the RFC 7914 vectors`() {
        assertEquals(
            "77d6576238657b203b19ca42c18a0497f16b4844e3074ae8dfdffa3fede21442" +
                "fcd0069ded0948f8326a753a0fc81f17e8d3e0fb2e0d3628cf35e20c38d18906",
            Scrypt.derive(ByteArray(0), ByteArray(0), logN = 4, r = 1, p = 1, length = 64).toHex(),
        )
        assertEquals(
            "fdbabe1c9d3472007856e7190d01e9fe7c6ad7cbc8237830e77376634b373162" +
                "2eaf30d92e22a3886ff109279d9830dac727afb94a83ee6d8360cbdfa2cc0640",
            Scrypt.derive("password".encodeToByteArray(), "NaCl".encodeToByteArray(), logN = 10, r = 8, p = 16, length = 64).toHex(),
        )
        assertEquals(
            "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2" +
                "d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
            Scrypt.derive(
                "pleaseletmein".encodeToByteArray(), "SodiumChloride".encodeToByteArray(),
                logN = 14, r = 8, p = 1, length = 64,
            ).toHex(),
        )
    }

    @Test
    fun `poly1305 matches RFC 8439 section 2-5-2 and the appendix edge cases`() {
        assertEquals(
            "a8061dc1305136c6c22b8baf0c0127a9",
            Poly1305.mac(
                hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b"),
                "Cryptographic Forum Research Group".encodeToByteArray(),
            ).toHex(),
        )
        // RFC 8439 A.3 test vectors #5 to #11 (key = R || S).
        val zero16 = "00000000000000000000000000000000"
        val ff16 = "ffffffffffffffffffffffffffffffff"
        val cases = listOf(
            Triple("02" + "00".repeat(15) + zero16, ff16, "03" + "00".repeat(15)),
            Triple("02" + "00".repeat(15) + ff16, "02" + "00".repeat(15), "03" + "00".repeat(15)),
            Triple(
                "01" + "00".repeat(15) + zero16,
                ff16 + "f0" + "ff".repeat(15) + "11" + "00".repeat(15),
                "05" + "00".repeat(15),
            ),
            Triple(
                "01" + "00".repeat(15) + zero16,
                ff16 + "fb" + "fe".repeat(15) + "01".repeat(16),
                "00".repeat(16),
            ),
            Triple("02" + "00".repeat(15) + zero16, "fd" + "ff".repeat(15), "fa" + "ff".repeat(15)),
            Triple(
                "0100000000000000" + "0400000000000000" + zero16,
                "e33594d7505e43b900000000000000003394d7505e4379cd0100000000000000" +
                    zero16 + "01" + "00".repeat(15),
                "14000000000000005500000000000000",
            ),
            Triple(
                "0100000000000000" + "0400000000000000" + zero16,
                "e33594d7505e43b900000000000000003394d7505e4379cd0100000000000000" + zero16,
                "13000000000000000000000000000000",
            ),
        )
        for ((index, case) in cases.withIndex()) {
            assertEquals(case.third, Poly1305.mac(hex(case.first), hex(case.second)).toHex(), "vector #${index + 5}")
        }
    }

    @Test
    fun `hchacha20 matches draft-irtf-cfrg-xchacha section 2-2-1`() {
        assertEquals(
            "82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc",
            HChaCha20.derive(
                hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"),
                hex("000000090000004a0000000031415927"),
            ).toHex(),
        )
    }

    @Test
    fun `xchacha20-poly1305 matches the draft A-3-1 vector and rejects any tampering`() {
        val plaintext = hex(
            "4c616469657320616e642047656e746c656d656e206f662074686520636c6173" +
                "73206f66202739393a204966204920636f756c64206f6666657220796f75206f" +
                "6e6c79206f6e652074697020666f7220746865206675747572652c2073756e73" +
                "637265656e20776f756c642062652069742e",
        )
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("404142434445464748494a4b4c4d4e4f5051525354555657")
        val expected = hex(
            "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb" +
                "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452" +
                "2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9" +
                "21f9664c97637da9768812f615c68b13b52e" +
                "c0875924c1c7987947deafd8780acf49",
        )
        assertContentEquals(expected, XChaCha20Poly1305.seal(key, nonce, plaintext, aad))
        assertContentEquals(plaintext, XChaCha20Poly1305.open(key, nonce, expected, aad))

        for (i in expected.indices step 7) {
            val tampered = expected.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertNull(XChaCha20Poly1305.open(key, nonce, tampered, aad), "flipped byte $i")
        }
        assertNull(XChaCha20Poly1305.open(key, nonce, expected, hex("50515253c0c1c2c3c4c5c6c8")))
        assertNull(XChaCha20Poly1305.open(key.copyOf().also { it[0] = 0 }, nonce, expected, aad))
        assertNull(XChaCha20Poly1305.open(key, nonce, expected.copyOf(15), aad))
        assertNull(XChaCha20Poly1305.open(key, nonce.copyOf(12), expected, aad))
        assertNotNull(XChaCha20Poly1305.seal(key, nonce, ByteArray(0), ByteArray(0)))
    }

    @Test
    fun `nfkc follows the NIP-49 normalisation example`() {
        val input = "ÅΩẛ̣"
        assertContentEquals(
            byteArrayOf(0xC3.toByte(), 0x85.toByte(), 0xCE.toByte(), 0xA9.toByte(), 0xE1.toByte(), 0xB9.toByte(), 0xA9.toByte()),
            UnicodeNormalizer.nfkc(input).encodeToByteArray(),
        )
    }
}
