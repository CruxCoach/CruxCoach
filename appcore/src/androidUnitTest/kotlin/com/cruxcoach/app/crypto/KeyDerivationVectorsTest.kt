package com.cruxcoach.app.crypto

import com.cruxcoach.app.nostr.Nip19
import com.cruxcoach.app.nostr.NostrKeys
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BIP-39, BIP-32, NIP-06 and NIP-49 against their official test vectors. */
class KeyDerivationVectorsTest {
    private fun hex(s: String): ByteArray = s.hexToBytesOrNull()!!

    @Test
    fun `the word list is the published BIP-39 english list`() {
        val words = Bip39English.words
        assertEquals(2048, words.size)
        assertEquals(words.sorted(), words)
        // sha256 of bitcoin/bips bip-0039/english.txt (one word per line, trailing newline).
        val file = words.joinToString("\n", postfix = "\n").encodeToByteArray()
        assertEquals("2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda", Sha256.digest(file).toHex())
    }

    @Test
    fun `all trezor english vectors validate and give the published seed`() {
        val lines = javaClass.getResource("/fixtures/identity/bip39-english-vectors.txt")!!.readText()
            .lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(24, lines.size)
        for (line in lines) {
            val (_, mnemonic, seed, xprv) = line.split('|')
            val (sentence, problem) = Bip39.normalize(mnemonic)
            assertNull(problem, mnemonic)
            assertEquals(mnemonic, sentence)
            val derivedSeed = Bip39.seed(assertNotNull(sentence), "TREZOR")
            assertEquals(seed, derivedSeed.toHex())
            // The vector's xprv is the BIP-32 master key of that seed.
            val master = assertNotNull(Bip32.master(derivedSeed))
            val (chainCode, key) = decodeXprv(xprv)
            assertContentEquals(chainCode, master.chainCode)
            assertContentEquals(key, master.secretKey)
        }
    }

    @Test
    fun `typos, wrong order and wrong lengths are refused`() {
        val valid = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        assertNull(Bip39.normalize(valid).second)
        // Case and whitespace are not part of the words.
        assertEquals(valid, Bip39.normalize("  Legal  winner\nthank year wave sausage worth useful legal winner thank YELLOW ").first)
        assertEquals(Bip39.Problem.UNKNOWN_WORD, Bip39.normalize(valid.replace("sausage", "sausag")).second)
        assertEquals(Bip39.Problem.CHECKSUM, Bip39.normalize(valid.replace("yellow", "year")).second)
        val swapped = "winner legal thank year wave sausage worth useful legal winner thank yellow"
        assertEquals(Bip39.Problem.CHECKSUM, Bip39.normalize(swapped).second)
        assertEquals(Bip39.Problem.WORD_COUNT, Bip39.normalize(valid.substringBeforeLast(' ')).second)
        assertEquals(Bip39.Problem.WORD_COUNT, Bip39.normalize("$valid abandon").second)
    }

    @Test
    fun `bip32 derivation matches test vectors 1 and 2`() {
        val h = Bip32.HARDENED
        checkChain(
            "000102030405060708090a0b0c0d0e0f",
            longArrayOf(0 + h, 1, 2 + h, 2, 1000000000),
            listOf(
                "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi",
                "xprv9uHRZZhk6KAJC1avXpDAp4MDc3sQKNxDiPvvkX8Br5ngLNv1TxvUxt4cV1rGL5hj6KCesnDYUhd7oWgT11eZG7XnxHrnYeSvkzY7d2bhkJ7",
                "xprv9wTYmMFdV23N2TdNG573QoEsfRrWKQgWeibmLntzniatZvR9BmLnvSxqu53Kw1UmYPxLgboyZQaXwTCg8MSY3H2EU4pWcQDnRnrVA1xe8fs",
                "xprv9z4pot5VBttmtdRTWfWQmoH1taj2axGVzFqSb8C9xaxKymcFzXBDptWmT7FwuEzG3ryjH4ktypQSAewRiNMjANTtpgP4mLTj34bhnZX7UiM",
                "xprvA2JDeKCSNNZky6uBCviVfJSKyQ1mDYahRjijr5idH2WwLsEd4Hsb2Tyh8RfQMuPh7f7RtyzTtdrbdqqsunu5Mm3wDvUAKRHSC34sJ7in334",
                "xprvA41z7zogVVwxVSgdKUHDy1SKmdb533PjDz7J6N6mV6uS3ze1ai8FHa8kmHScGpWmj4WggLyQjgPie1rFSruoUihUZREPSL39UNdE3BBDu76",
            ),
        )
        checkChain(
            "fffcf9f6f3f0edeae7e4e1dedbd8d5d2cfccc9c6c3c0bdbab7b4b1aeaba8a5a29f9c999693908d8a8784817e7b7875726f6c696663605d5a5754514e4b484542",
            longArrayOf(0, 2147483647 + h, 1, 2147483646 + h, 2),
            listOf(
                "xprv9s21ZrQH143K31xYSDQpPDxsXRTUcvj2iNHm5NUtrGiGG5e2DtALGdso3pGz6ssrdK4PFmM8NSpSBHNqPqm55Qn3LqFtT2emdEXVYsCzC2U",
                "xprv9vHkqa6EV4sPZHYqZznhT2NPtPCjKuDKGY38FBWLvgaDx45zo9WQRUT3dKYnjwih2yJD9mkrocEZXo1ex8G81dwSM1fwqWpWkeS3v86pgKt",
                "xprv9wSp6B7kry3Vj9m1zSnLvN3xH8RdsPP1Mh7fAaR7aRLcQMKTR2vidYEeEg2mUCTAwCd6vnxVrcjfy2kRgVsFawNzmjuHc2YmYRmagcEPdU9",
                "xprv9zFnWC6h2cLgpmSA46vutJzBcfJ8yaJGg8cX1e5StJh45BBciYTRXSd25UEPVuesF9yog62tGAQtHjXajPPdbRCHuWS6T8XA2ECKADdw4Ef",
                "xprvA1RpRA33e1JQ7ifknakTFpgNXPmW2YvmhqLQYMmrj4xJXXWYpDPS3xz7iAxn8L39njGVyuoseXzU6rcxFLJ8HFsTjSyQbLYnMpCqE2VbFWc",
                "xprvA2nrNbFZABcdryreWet9Ea4LvTJcGsqrMzxHx98MMrotbir7yrKCEXw7nadnHM8Dq38EGfSh6dqA9QWTyefMLEcBYJUuekgW4BYPJcr9E7j",
            ),
        )
    }

    private fun checkChain(seedHex: String, path: LongArray, xprvs: List<String>) {
        var key = assertNotNull(Bip32.master(hex(seedHex)))
        for (level in xprvs.indices) {
            if (level > 0) key = assertNotNull(Bip32.child(key, path[level - 1]))
            val (chainCode, secret) = decodeXprv(xprvs[level])
            assertContentEquals(secret, key.secretKey, "level $level")
            assertContentEquals(chainCode, key.chainCode, "level $level")
        }
    }

    @Test
    fun `nip06 spec vectors derive the published keys`() {
        val vectors = listOf(
            Triple(
                "leader monkey parrot ring guide accident before fence cannon height naive bean",
                "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a",
                "npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu",
            ),
            Triple(
                "what bleak badge arrange retreat wolf trade produce cricket blur garlic valid proud rude strong " +
                    "choose busy staff weather area salt hollow arm fade",
                "c15d739894c81a2fcfd3a2df85a0d2c0dbc47a280d092799f144d73d7ae78add",
                "npub16sdj9zv4f8sl85e45vgq9n7nsgt5qphpvmf7vk8r5hhvmdjxx4es8rq74h",
            ),
        )
        for ((mnemonic, keyHex, npub) in vectors) {
            val key = assertIs<Nip06.Result.Key>(Nip06.secretKeyFromMnemonic(mnemonic)).secretKey
            assertEquals(keyHex, key.toHex())
            assertEquals(npub, Nip19.encodeNpub(assertNotNull(NostrKeys.publicKeyHex(key))))
        }
        assertIs<Nip06.Result.Invalid>(Nip06.secretKeyFromMnemonic("leader monkey parrot ring guide accident before fence cannon height naive naive"))
    }

    @Test
    fun `nip06 agrees with the library Android uses`() {
        val reference = com.vitorpamplona.quartz.nip06KeyDerivation.Nip06()
        val random = java.util.Random(5)
        for (entropySize in listOf(16, 20, 24, 28, 32)) {
            repeat(3) {
                val entropy = ByteArray(entropySize).also { random.nextBytes(it) }
                val words = com.vitorpamplona.quartz.nip06KeyDerivation.Bip39Mnemonics.toMnemonics(entropy).joinToString(" ")
                val ours = assertIs<Nip06.Result.Key>(Nip06.secretKeyFromMnemonic(words)).secretKey
                assertContentEquals(reference.privateKeyFromMnemonic(words, 0L), ours, words)
            }
        }
    }

    private val specNcryptsec =
        "ncryptsec1qgg9947rlpvqu76pj5ecreduf9jxhselq2nae2kghhvd5g7dgjtcxfqtd67p9m0w57lspw8gsq6yphnm8623nsl8xn9j4jdzz84zm3frztj3z7s35vpzmqf6ksu8r89qk5z2zxfmu5gv8th8wclt0h4p"

    @Test
    fun `nip49 spec vector decrypts with password nostr`() {
        val payload = assertNotNull(Nip49.parse(specNcryptsec))
        assertEquals(16, payload.logN)
        val key = assertIs<Nip49.Result.Key>(Nip49.decrypt(payload, "nostr", maxLogN = 16)).secretKey
        assertEquals("3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683", key.toHex())
    }

    @Test
    fun `nip49 refuses wrong passwords, altered payloads and costs above the cap`() {
        val payload = assertNotNull(Nip49.parse(specNcryptsec))
        assertIs<Nip49.Result.WrongPassword>(Nip49.decrypt(payload, "Nostr", maxLogN = 16))
        assertIs<Nip49.Result.TooExpensive>(Nip49.decrypt(payload, "nostr", maxLogN = 15))
        // A bech32 typo fails the checksum; the wrong prefix and an nsec are no ncryptsec.
        assertNull(Nip49.parse(specNcryptsec.replaceRange(40, 41, if (specNcryptsec[40] == 'q') "p" else "q")))
        assertNull(Nip49.parse(specNcryptsec.dropLast(1)))
        assertNull(Nip49.parse("nsec10allq0gjx7fddtzef0ax00mdps9t2kmtrldkyjfs8l5xruwvh2dq0lhhkp"))
        assertNull(Nip49.parse("ncryptsec1qqqq"))
    }

    @Test
    fun `nip49 reads what the library Android uses writes`() {
        val reference = com.vitorpamplona.quartz.nip49PrivKeyEnc.Nip49()
        val secret = "67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa"
        for (password in listOf("correct horse", "ÅΩẛ̣")) {
            val encrypted = reference.encrypt(secret, password, 12, 2.toByte())
            val payload = assertNotNull(Nip49.parse(encrypted))
            assertEquals(12, payload.logN)
            assertEquals(secret, assertIs<Nip49.Result.Key>(Nip49.decrypt(payload, password, maxLogN = 16)).secretKey.toHex())
            // The decomposed and composed spellings of a password are the same password.
            if (password.length == 4) {
                assertIs<Nip49.Result.Key>(Nip49.decrypt(payload, "ÅΩṩ", maxLogN = 16))
            }
        }
    }

    @Test
    fun `the scrypt cap follows the phone's memory`() {
        val gib = 1L shl 30
        assertEquals(20, Nip49.maxLogNFor(4 * gib))
        assertEquals(20, Nip49.maxLogNFor(8 * gib))
        assertEquals(19, Nip49.maxLogNFor(3 * gib))
        // Exactly a quarter still fits: 2 GiB → 512 MiB → log_n 19.
        assertEquals(19, Nip49.maxLogNFor(2 * gib))
        assertEquals(18, Nip49.maxLogNFor(3 * gib / 2))
        assertEquals(16, Nip49.maxLogNFor(64L shl 20))
        assertTrue(Nip49.MAX_LOG_N < 21)
    }

    /** Base58Check xprv → (chain code, private key). */
    private fun decodeXprv(xprv: String): Pair<ByteArray, ByteArray> {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var value = BigInteger.ZERO
        for (c in xprv) value = value.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(alphabet.indexOf(c).toLong()))
        val raw = value.toByteArray().let { if (it.size > 82) it.copyOfRange(it.size - 82, it.size) else ByteArray(82 - it.size) + it }
        val body = raw.copyOfRange(0, 78)
        val check = MessageDigest.getInstance("SHA-256").let { it.digest(it.digest(body)) }.copyOfRange(0, 4)
        assertContentEquals(check, raw.copyOfRange(78, 82), "xprv checksum")
        assertEquals(0, body[45].toInt())
        return body.copyOfRange(13, 45) to body.copyOfRange(46, 78)
    }
}
