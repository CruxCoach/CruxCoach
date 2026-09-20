package com.cruxcoach.app.nostr

import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import com.vitorpamplona.quartz.nip01Core.crypto.EventHasher
import com.vitorpamplona.quartz.nip01Core.crypto.Nip01Crypto
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import com.vitorpamplona.quartz.nip44Encryption.Nip44v2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Our Nostr code checked against Quartz — the library Android's app uses — as
 * an independent implementation.
 *
 * This exists because a round trip against ourselves proves nothing about
 * interoperability: it would pass just as happily if both halves shared the
 * same mistake. Here one side is written by someone else, so a disagreement
 * shows up as a failing test rather than as an unreadable message on a
 * climber's other device.
 *
 * Quartz is a **test-only** dependency (`androidUnitTest`), never shipped. Its
 * Apple artifacts need Kotlin 2.4, which this project cannot use yet — see
 * NOSTR-DEPENDENCY-MIGRATION.md — so it cannot replace our production code,
 * but it can judge it.
 */
class QuartzCrossCheckTest {
    private val nip44 = Nip44v2()

    private fun keyPair(): Pair<ByteArray, String> {
        val secret = NostrKeys.generateSecretKey(JvmHashing)!!
        return secret to NostrKeys.publicKeyHex(secret)!!
    }

    @Test
    fun `Quartz decrypts what we encrypt`() {
        val (aliceSecret, alicePub) = keyPair()
        val (bobSecret, bobPub) = keyPair()
        val message = "the crux is the left heel — ümläut, 😀, and a tab\there"

        val ours = Nip44.conversationKey(JvmHashing, aliceSecret, bobPub)!!
        val payload = Nip44.encrypt(JvmHashing, ours, message)!!

        val theirs = nip44.getConversationKey(bobSecret, alicePub.hexToBytesOrNull()!!)
        assertEquals(message, nip44.decrypt(payload, theirs))
    }

    @Test
    fun `we decrypt what Quartz encrypts`() {
        val (aliceSecret, alicePub) = keyPair()
        val (bobSecret, bobPub) = keyPair()
        val message = "0.2.3 ist da. 🇩🇪"

        val theirs = nip44.getConversationKey(aliceSecret, bobPub.hexToBytesOrNull()!!)
        val payload = nip44.encrypt(message, theirs).encodePayload()

        val ours = Nip44.conversationKey(JvmHashing, bobSecret, alicePub)!!
        assertEquals(message, Nip44.decrypt(JvmHashing, ours, payload))
    }

    @Test
    fun `both derive the same conversation key from the same pair`() {
        val (aliceSecret, alicePub) = keyPair()
        val (bobSecret, bobPub) = keyPair()

        val ourAlice = Nip44.conversationKey(JvmHashing, aliceSecret, bobPub)!!
        val theirAlice = nip44.getConversationKey(aliceSecret, bobPub.hexToBytesOrNull()!!)
        assertTrue(ourAlice.contentEquals(theirAlice), "conversation keys diverge")

        // And it is symmetric, which is what makes a reply readable at all.
        val theirBob = nip44.getConversationKey(bobSecret, alicePub.hexToBytesOrNull()!!)
        assertTrue(ourAlice.contentEquals(theirBob))
    }

    @Test
    fun `padding agrees at the sizes where a mistake would be invisible`() {
        // A payload that is one byte off still decrypts on the side that made
        // the mistake, so the boundaries are where this has to be checked.
        for (length in listOf(1, 2, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257, 1000)) {
            assertEquals(
                nip44.calcPaddedLen(length),
                Nip44.calcPaddedLen(length),
                "padded length disagrees at $length",
            )
        }
    }

    @Test
    fun `an event we sign verifies in Quartz`() {
        val secret = NostrKeys.generateSecretKey(JvmHashing)!!
        val event = NostrKeys.sign(
            JvmHashing, secret,
            createdAt = 1_790_000_000L,
            kind = 1,
            tags = listOf(listOf("t", "cruxcoach"), listOf("p", "ff".repeat(32))),
            content = "topped it",
        )!!

        // The id is the canonical hash, so agreeing on it means agreeing on
        // the whole serialization — field order, escaping and all.
        assertTrue(
            Nip01Crypto.verify(
                event.sig.hexToBytesOrNull()!!,
                event.id.hexToBytesOrNull()!!,
                event.pubkey.hexToBytesOrNull()!!,
            ),
            "Quartz rejects a signature we produced",
        )
    }

    @Test
    fun `an event Quartz signs verifies here`() {
        val secret = Nip01Crypto.privKeyCreate()
        val pubkey = Nip01Crypto.pubKeyCreate(secret).toHex()
        val createdAt = 1_790_000_100L
        val tags = listOf(listOf("e", "aa".repeat(32)))
        val content = "sent from the library"

        val id = NostrEvents.computeId(JvmHashing, pubkey, createdAt, 1, tags, content)
        val sig = Nip01Crypto.sign(id.hexToBytesOrNull()!!, secret, null)
        val event = NostrEvent(id, pubkey, createdAt, 1, tags, content, sig.toHex())

        assertTrue(NostrEvents.verify(JvmHashing, event), "we reject a signature Quartz produced")
    }

    @Test
    fun `the backup data key travels between the two implementations`() {
        // The encrypted backup wraps its AES key in a NIP-44 envelope
        // addressed to the user themselves. If that envelope were not
        // interoperable, a backup made on Android would be unreadable here
        // and the failure would only show up on the day it was needed.
        val secret = NostrKeys.generateSecretKey(JvmHashing)!!
        val pubkey = NostrKeys.publicKeyHex(secret)!!
        val dataKeyHex = JvmHashing.randomBytes(32).toHex()

        // Ours out, Quartz in.
        val ourWrap = Nip44.encryptToSelf(JvmHashing, secret, dataKeyHex)!!
        val selfKey = nip44.getConversationKey(secret, pubkey.hexToBytesOrNull()!!)
        assertEquals(dataKeyHex, nip44.decrypt(ourWrap, selfKey))

        // Quartz out, ours in — this is the direction a restore takes.
        val theirWrap = nip44.encrypt(dataKeyHex, selfKey).encodePayload()
        assertEquals(dataKeyHex, Nip44.decryptFromSelf(JvmHashing, secret, theirWrap))
    }

    @Test
    fun `the id of an event carrying a control character is not agreed across clients`() {
        // Not a round trip and not a bug in either side: NIP-01 says the
        // remaining control characters go in verbatim, and nobody does that.
        // JS, Rust and Kotlin escape them lowercase; Jackson — so Quartz, so
        // CruxCoach's own Android app — escapes them uppercase, which hashes
        // differently. We follow the lowercase majority, including the library
        // the iOS app links. Pinned here so the disagreement is visible rather
        // than discovered again by someone else.
        val pubkey = "ff".repeat(32)
        val content = "a\u001fb"
        assertNotEquals(
            EventHasher.hashId(pubkey, 1L, 1, arrayOf(), content),
            NostrEvents.computeId(JvmHashing, pubkey, 1L, 1, emptyList(), content),
            "Quartz has started agreeing with us — re-read NIP-01 and drop this test",
        )
    }

    @Test
    fun `canonicalisation agrees on the content a naive serializer would break`() {
        // The event id is a hash of a JSON array, so every escaping decision is
        // part of the protocol. These are the strings where a hand-written
        // serializer and a real one diverge: quotes, backslashes, the control
        // characters JSON spells two ways, and text outside the BMP.
        val nasty = listOf(
            "a \"quoted\" word",
            "back\\slash",
            "line\nbreak\ttab",
            // Control characters are deliberately absent: see the test above.
            "emoji 😀 and äöü",
            "",
            "\u007f delete",
        )
        val pubkey = "ff".repeat(32)
        for (content in nasty) {
            val ourId = NostrEvents.computeId(
                JvmHashing, pubkey, 1_790_000_000L, 1,
                listOf(listOf("t", content), listOf("p", pubkey, "")), content,
            )
            val theirId = EventHasher.hashId(
                pubkey, 1_790_000_000L, 1,
                arrayOf(arrayOf("t", content), arrayOf("p", pubkey, "")), content,
            )
            assertEquals(theirId, ourId, "event id diverges for: " + content)
        }
    }

    @Test
    fun `npub encoding agrees`() {
        repeat(5) {
            val pubkey = NostrKeys.publicKeyHex(NostrKeys.generateSecretKey(JvmHashing)!!)!!
            assertEquals(NPub.create(pubkey), Nip19.encodeNpub(pubkey), "npub diverges")
        }
        // And a known key, so a shared mistake in both generators cannot hide.
        val known = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
        assertEquals(NPub.create(known), Nip19.encodeNpub(known))
    }

    @Test
    fun `a tampered payload is rejected by both`() {
        val (aliceSecret, alicePub) = keyPair()
        val (bobSecret, bobPub) = keyPair()
        val ours = Nip44.conversationKey(JvmHashing, aliceSecret, bobPub)!!
        val payload = Nip44.encrypt(JvmHashing, ours, "untouched")!!

        // Flip one character of the base64 body, well past the version byte.
        val chars = payload.toCharArray()
        val at = chars.size / 2
        chars[at] = if (chars[at] == 'A') 'B' else 'A'
        val tampered = String(chars)

        val theirs = nip44.getConversationKey(bobSecret, alicePub.hexToBytesOrNull()!!)
        assertEquals(null, Nip44.decrypt(JvmHashing, ours, tampered), "we accepted a tampered payload")
        assertFalse(
            runCatching { nip44.decrypt(tampered, theirs) }.isSuccess,
            "Quartz accepted a tampered payload",
        )
    }
}
