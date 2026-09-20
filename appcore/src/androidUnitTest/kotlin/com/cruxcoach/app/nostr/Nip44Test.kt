package com.cruxcoach.app.nostr

import com.cruxcoach.app.testing.Fixtures
import com.cruxcoach.app.testing.JvmHashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The official NIP-44 v2 vector file
 * (github.com/paulmillr/nip44, nip44.vectors.json, 37 KB — committed whole).
 */
class Nip44Test {
    private val vectors: JsonObject =
        Json.parseToJsonElement(Fixtures.text("nostr/nip44.vectors.json")).jsonObject["v2"]!!.jsonObject
    private val valid = vectors["valid"]!!.jsonObject
    private val invalid = vectors["invalid"]!!.jsonObject

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun hex(s: String) = s.hexToBytesOrNull()!!
    private fun cases(section: JsonObject, name: String): List<JsonObject> =
        section[name]!!.jsonArray.map { it.jsonObject }

    @Test
    fun `get_conversation_key`() {
        val cases = cases(valid, "get_conversation_key")
        assertEquals(35, cases.size)
        for (case in cases) {
            assertEquals(
                case.str("conversation_key"),
                Nip44.conversationKey(JvmHashing, hex(case.str("sec1")), case.str("pub2"))?.toHex(),
                case.toString(),
            )
        }
    }

    @Test
    fun `get_message_keys`() {
        val section = valid["get_message_keys"]!!.jsonObject
        val conversationKey = hex(section.str("conversation_key"))
        val cases = section["keys"]!!.jsonArray.map { it.jsonObject }
        assertEquals(32, cases.size)
        for (case in cases) {
            val keys = assertNotNull(Nip44.messageKeys(JvmHashing, conversationKey, hex(case.str("nonce"))))
            assertEquals(case.str("chacha_key"), keys.chachaKey.toHex())
            assertEquals(case.str("chacha_nonce"), keys.chachaNonce.toHex())
            assertEquals(case.str("hmac_key"), keys.hmacKey.toHex())
        }
    }

    @Test
    fun `calc_padded_len`() {
        val cases = valid["calc_padded_len"]!!.jsonArray.map { it.jsonArray }
        assertEquals(24, cases.size)
        for (case in cases) {
            assertEquals(case[1].jsonPrimitive.int, Nip44.calcPaddedLen(case[0].jsonPrimitive.int), case.toString())
        }
    }

    @Test
    fun `encrypt_decrypt`() {
        val cases = cases(valid, "encrypt_decrypt")
        assertEquals(10, cases.size)
        for (case in cases) {
            val sec1 = hex(case.str("sec1"))
            val pub2 = assertNotNull(NostrKeys.publicKeyHex(hex(case.str("sec2"))))
            val conversationKey = assertNotNull(Nip44.conversationKey(JvmHashing, sec1, pub2))
            assertEquals(case.str("conversation_key"), conversationKey.toHex())
            assertEquals(
                case.str("payload"),
                Nip44.encryptWithNonce(JvmHashing, conversationKey, case.str("plaintext"), hex(case.str("nonce"))),
                case.str("plaintext"),
            )
            assertEquals(case.str("plaintext"), Nip44.decrypt(JvmHashing, conversationKey, case.str("payload")))
            // Decryption also works from the other side of the conversation.
            val reverse = assertNotNull(
                Nip44.conversationKey(JvmHashing, hex(case.str("sec2")), assertNotNull(NostrKeys.publicKeyHex(sec1))),
            )
            assertEquals(case.str("plaintext"), Nip44.decrypt(JvmHashing, reverse, case.str("payload")))
        }
    }

    @Test
    fun `encrypt_decrypt_long_msg`() {
        val cases = cases(valid, "encrypt_decrypt_long_msg")
        assertEquals(3, cases.size)
        for (case in cases) {
            val conversationKey = hex(case.str("conversation_key"))
            val plaintext = case.str("pattern").repeat(case["repeat"]!!.jsonPrimitive.int)
            assertEquals(case.str("plaintext_sha256"), JvmHashing.sha256(plaintext.encodeToByteArray()).toHex())
            val payload = assertNotNull(
                Nip44.encryptWithNonce(JvmHashing, conversationKey, plaintext, hex(case.str("nonce"))),
            )
            assertEquals(case.str("payload_sha256"), JvmHashing.sha256(payload.encodeToByteArray()).toHex())
            assertEquals(plaintext, Nip44.decrypt(JvmHashing, conversationKey, payload))
        }
    }

    @Test
    fun `invalid message lengths are refused`() {
        val lengths = invalid["encrypt_msg_lengths"]!!.jsonArray.map { it.jsonPrimitive.int }
        assertEquals(listOf(0, 65536, 100000, 10000000), lengths)
        val conversationKey = ByteArray(32) { 7 }
        for (length in lengths) {
            assertNull(
                Nip44.encryptWithNonce(JvmHashing, conversationKey, "a".repeat(length), ByteArray(32)),
                "length $length",
            )
        }
        assertNotNull(Nip44.encryptWithNonce(JvmHashing, conversationKey, "a".repeat(65535), ByteArray(32)))
    }

    @Test
    fun `invalid conversation keys are refused`() {
        val cases = cases(invalid, "get_conversation_key")
        assertEquals(8, cases.size)
        for (case in cases) {
            assertNull(
                Nip44.conversationKey(JvmHashing, hex(case.str("sec1")), case.str("pub2")),
                case.str("note"),
            )
        }
    }

    @Test
    fun `invalid payloads are refused`() {
        val cases = cases(invalid, "decrypt")
        assertEquals(12, cases.size)
        for (case in cases) {
            assertNull(
                Nip44.decrypt(JvmHashing, hex(case.str("conversation_key")), case.str("payload")),
                case.str("note"),
            )
        }
    }

    @Test
    fun `a flipped bit anywhere in a payload fails the MAC`() {
        val conversationKey = ByteArray(32) { (it * 11).toByte() }
        val payload = assertNotNull(Nip44.encrypt(JvmHashing, conversationKey, "backup pointer"))
        assertEquals("backup pointer", Nip44.decrypt(JvmHashing, conversationKey, payload))
        val raw = assertNotNull(Base64.decode(payload))
        for (index in intArrayOf(0, 1, 20, 33, raw.size - 40, raw.size - 1)) {
            val tampered = raw.copyOf()
            tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertNull(Nip44.decrypt(JvmHashing, conversationKey, Base64.encode(tampered)), "byte $index")
        }
        // A different conversation key never decrypts it.
        assertNull(Nip44.decrypt(JvmHashing, ByteArray(32), payload))
    }

    @Test
    fun `self-encryption round trips through a real keypair`() {
        val secret = assertNotNull(NostrKeys.generateSecretKey(JvmHashing))
        val payload = assertNotNull(Nip44.encryptToSelf(JvmHashing, secret, """{"sha256":"ab"}"""))
        assertEquals(2, assertNotNull(Base64.decode(payload))[0].toInt(), "v2 version byte")
        assertEquals("""{"sha256":"ab"}""", Nip44.decryptFromSelf(JvmHashing, secret, payload))
        val other = assertNotNull(NostrKeys.generateSecretKey(JvmHashing))
        assertNull(Nip44.decryptFromSelf(JvmHashing, other, payload))
    }
}
