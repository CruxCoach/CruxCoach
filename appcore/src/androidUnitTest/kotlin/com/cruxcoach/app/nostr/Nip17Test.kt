package com.cruxcoach.app.nostr

import com.cruxcoach.app.testing.JvmHashing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * NIP-17 over NIP-59. The point of the three layers is what a relay can see,
 * so these cases assert the negatives as hard as the round trip.
 */
class Nip17Test {
    private val cipher = KotlinNip44Cipher(JvmHashing)
    private val alice = NostrKeys.generateSecretKey(JvmHashing)!!
    private val bob = NostrKeys.generateSecretKey(JvmHashing)!!
    private val mallory = NostrKeys.generateSecretKey(JvmHashing)!!
    private val alicePub = NostrKeys.publicKeyHex(alice)!!
    private val bobPub = NostrKeys.publicKeyHex(bob)!!
    private val now = 1_790_000_000L

    @Test
    fun `the recipient and the sender can both read the message`() {
        val (rumor, wraps) = Nip17.wrap(
            JvmHashing, cipher, alice, bobPub, "the crux is the left heel",
            tags = listOf(listOf("l", "bug-report", "com.cruxcoach.type")),
            createdAt = now,
        )!!
        assertEquals(2, wraps.size)

        val toBob = wraps.first { it.firstTagValue("p") == bobPub }
        val toAlice = wraps.first { it.firstTagValue("p") == alicePub }

        val read = Nip17.unwrap(JvmHashing, cipher, bob, toBob)!!
        assertEquals("the crux is the left heel", read.content)
        assertEquals(alicePub, read.pubkey)
        assertEquals(rumor.id, read.id)
        assertEquals(KIND_CHAT_MESSAGE, read.kind)
        assertTrue(read.tags.any { it.getOrNull(1) == "bug-report" })

        // The sender's own devices can still read what was sent.
        assertEquals("the crux is the left heel", Nip17.unwrap(JvmHashing, cipher, alice, toAlice)!!.content)
    }

    @Test
    fun `a wrap tells a relay nothing but who may read it`() {
        val (_, wraps) = Nip17.wrap(JvmHashing, cipher, alice, bobPub, "secret", createdAt = now)!!
        val toBob = wraps.first { it.firstTagValue("p") == bobPub }

        assertEquals(KIND_GIFT_WRAP, toBob.kind)
        assertNotEquals(alicePub, toBob.pubkey, "the wrap must not be signed by the sender")
        assertNotEquals(bobPub, toBob.pubkey)
        assertEquals(listOf(listOf("p", bobPub)), toBob.tags, "only the recipient may be public")
        assertTrue("secret" !in toBob.content)
        assertTrue(alicePub !in toBob.content)
        assertTrue(NostrEvents.verify(JvmHashing, toBob), "a relay has to be able to verify it")

        // Two wraps of one message are signed by two different throwaway keys.
        val other = wraps.first { it.firstTagValue("p") == alicePub }
        assertNotEquals(toBob.pubkey, other.pubkey)
    }

    @Test
    fun `timestamps are pushed into the past and never into the future`() {
        repeat(20) {
            val (rumor, wraps) = Nip17.wrap(JvmHashing, cipher, alice, bobPub, "x", createdAt = now)!!
            assertEquals(now, rumor.createdAt, "the rumor keeps the real time — it is encrypted")
            for (wrap in wraps) {
                assertTrue(wrap.createdAt <= now, "a wrap must not be dated in the future")
                assertTrue(
                    wrap.createdAt >= now - Nip17.MAX_BACKDATE_SECONDS,
                    "backdated by more than two days: ${now - wrap.createdAt}",
                )
            }
        }
    }

    @Test
    fun `a stranger cannot read the message`() {
        val (_, wraps) = Nip17.wrap(JvmHashing, cipher, alice, bobPub, "secret", createdAt = now)!!
        val toBob = wraps.first { it.firstTagValue("p") == bobPub }
        assertNull(Nip17.unwrap(JvmHashing, cipher, mallory, toBob))
    }

    @Test
    fun `a rumor claiming a foreign author is refused`() {
        // Mallory seals a rumor that says it came from Alice and sends it to Bob.
        val forged = """{"id":"${"0".repeat(64)}","pubkey":"$alicePub","created_at":$now,"kind":14,"tags":[],"content":"trust me"}"""
        val sealKey = Nip44.conversationKey(JvmHashing, mallory, bobPub)!!
        val seal = NostrKeys.sign(
            JvmHashing, mallory, now, KIND_SEAL, emptyList(),
            Nip44.encrypt(JvmHashing, sealKey, forged)!!,
        )!!
        val ephemeral = NostrKeys.generateSecretKey(JvmHashing)!!
        val wrapKey = Nip44.conversationKey(JvmHashing, ephemeral, bobPub)!!
        val wrap = NostrKeys.sign(
            JvmHashing, ephemeral, now, KIND_GIFT_WRAP, listOf(listOf("p", bobPub)),
            Nip44.encrypt(
                JvmHashing, wrapKey,
                Json.encodeToString(JsonObject.serializer(), seal.toJson()),
            )!!,
        )!!

        assertNull(
            Nip17.unwrap(JvmHashing, cipher, bob, wrap),
            "the seal's signature is the only proof of authorship",
        )
    }

    @Test
    fun `a tampered rumor id is refused`() {
        val rumor = """{"id":"${"1".repeat(64)}","pubkey":"$alicePub","created_at":$now,"kind":14,"tags":[],"content":"hi"}"""
        assertEquals(alicePub, Nip17.parseRumor(rumor)!!.pubkey)
        val sealKey = Nip44.conversationKey(JvmHashing, alice, bobPub)!!
        val seal = NostrKeys.sign(
            JvmHashing, alice, now, KIND_SEAL, emptyList(),
            Nip44.encrypt(JvmHashing, sealKey, rumor)!!,
        )!!
        val ephemeral = NostrKeys.generateSecretKey(JvmHashing)!!
        val wrapKey = Nip44.conversationKey(JvmHashing, ephemeral, bobPub)!!
        val wrap = NostrKeys.sign(
            JvmHashing, ephemeral, now, KIND_GIFT_WRAP, listOf(listOf("p", bobPub)),
            Nip44.encrypt(
                JvmHashing, wrapKey,
                Json.encodeToString(JsonObject.serializer(), seal.toJson()),
            )!!,
        )!!
        assertNull(Nip17.unwrap(JvmHashing, cipher, bob, wrap), "the id has to bind the body")
    }

    @Test
    fun `an event that is not a gift wrap is refused`() {
        val note = NostrKeys.sign(JvmHashing, alice, now, 1, emptyList(), "hello")!!
        assertNull(Nip17.unwrap(JvmHashing, cipher, bob, note))
    }
}
