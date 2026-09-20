package com.cruxcoach.app.nostr

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.Nip44Cipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** NIP-17 private direct message (the unsigned rumor). */
const val KIND_CHAT_MESSAGE = 14

/** NIP-59 seal: the rumor, NIP-44-encrypted to the recipient, signed by the sender. */
const val KIND_SEAL = 13

/** NIP-59 gift wrap: the seal, encrypted and signed by a throwaway key. */
const val KIND_GIFT_WRAP = 1059

/** An unsigned NIP-59 rumor: a NIP-01 event with an id but no signature. */
class Rumor(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
)

/**
 * NIP-17 private messages over NIP-59 gift wrap, on top of the NIP-44 v2 and
 * BIP-340 code that is already vector-tested here.
 *
 * The three layers exist so a relay learns as little as possible: the outer
 * gift wrap is signed by a key used exactly once, its `created_at` is pushed
 * up to two days into the past, and only the p-tag naming the recipient is
 * public. Neither the sender, the kind, nor the text is visible to anyone but
 * the recipient.
 *
 * One call produces two wraps, as NIP-17 requires: one addressed to the
 * recipient and one to the sender, so the sender's own devices can still read
 * what they sent.
 */
object Nip17 {
    private val JSON = Json

    /** How far into the past a gift wrap's timestamp may be pushed. */
    const val MAX_BACKDATE_SECONDS = 2L * 24 * 60 * 60

    /**
     * Builds the rumor and returns `(rumor, wraps)`. [randomSeconds] supplies
     * the per-wrap backdating and is injectable so tests are deterministic.
     */
    fun wrap(
        hashing: Hashing,
        cipher: Nip44Cipher,
        senderSecret: ByteArray,
        recipientPubkey: String,
        content: String,
        tags: List<List<String>> = emptyList(),
        createdAt: Long,
        randomSeconds: () -> Long = { randomBackdate(hashing) },
    ): Pair<Rumor, List<NostrEvent>>? {
        val senderPubkey = NostrKeys.publicKeyHex(senderSecret) ?: return null
        val rumorTags = listOf(listOf("p", recipientPubkey)) + tags
        val rumorId = NostrEvents.computeId(
            hashing, senderPubkey, createdAt, KIND_CHAT_MESSAGE, rumorTags, content,
        )
        val rumor = Rumor(rumorId, senderPubkey, createdAt, KIND_CHAT_MESSAGE, rumorTags, content)
        val rumorJson = JSON.encodeToString(JsonObject.serializer(), rumor.toJson())

        // One wrap per reader: the recipient, and the sender's own devices.
        val wraps = listOf(recipientPubkey, senderPubkey).mapNotNull { reader ->
            wrapFor(hashing, cipher, senderSecret, reader, rumorJson, createdAt, randomSeconds())
        }
        if (wraps.size != 2) return null
        return rumor to wraps
    }

    private fun wrapFor(
        hashing: Hashing,
        cipher: Nip44Cipher,
        senderSecret: ByteArray,
        readerPubkey: String,
        rumorJson: String,
        createdAt: Long,
        backdate: Long,
    ): NostrEvent? {
        val sealedContent = cipher.encrypt(senderSecret, readerPubkey, rumorJson) ?: return null
        // The seal carries the sender's identity and is itself timestamped in
        // the past: its created_at is inside the wrap, but a recipient's relay
        // could still correlate an exact match with the wrap it arrived in.
        val seal = NostrKeys.sign(
            hashing, senderSecret, createdAt - backdate, KIND_SEAL, emptyList(), sealedContent,
        ) ?: return null

        // A key used for exactly one wrap: the outer event must not be
        // attributable to the sender at all.
        val ephemeral = NostrKeys.generateSecretKey(hashing) ?: return null
        try {
            val wrappedContent = cipher.encrypt(
                ephemeral, readerPubkey,
                JSON.encodeToString(JsonObject.serializer(), seal.toJson()),
            ) ?: return null
            return NostrKeys.sign(
                hashing,
                ephemeral,
                createdAt - backdate,
                KIND_GIFT_WRAP,
                listOf(listOf("p", readerPubkey)),
                wrappedContent,
            )
        } finally {
            ephemeral.fill(0)
        }
    }

    /**
     * Opens a gift wrap addressed to [readerSecret] and returns the rumor.
     *
     * Null whenever any layer does not hold: the wrap must verify, the seal
     * inside it must verify, and — the rule that matters — the seal's author
     * must be the rumor's author. Without that check anyone could seal a rumor
     * claiming to come from someone else.
     */
    fun unwrap(
        hashing: Hashing,
        cipher: Nip44Cipher,
        readerSecret: ByteArray,
        wrap: NostrEvent,
    ): Rumor? {
        if (wrap.kind != KIND_GIFT_WRAP) return null
        if (!NostrEvents.verify(hashing, wrap)) return null
        val sealJson = cipher.decrypt(readerSecret, wrap.pubkey, wrap.content) ?: return null
        val seal = try {
            NostrEvent.fromJson(JSON.parseToJsonElement(sealJson) as? JsonObject ?: return null)
        } catch (_: Exception) {
            null
        } ?: return null
        if (seal.kind != KIND_SEAL || !NostrEvents.verify(hashing, seal)) return null
        val rumorJson = cipher.decrypt(readerSecret, seal.pubkey, seal.content) ?: return null
        val rumor = parseRumor(rumorJson) ?: return null
        // The seal's signature is the only proof of authorship there is.
        if (rumor.pubkey != seal.pubkey) return null
        val expectedId = NostrEvents.computeId(
            hashing, rumor.pubkey, rumor.createdAt, rumor.kind, rumor.tags, rumor.content,
        )
        if (expectedId != rumor.id) return null
        return rumor
    }

    /** A whole number of seconds in `[0, MAX_BACKDATE_SECONDS]`. */
    private fun randomBackdate(hashing: Hashing): Long {
        val bytes = hashing.randomBytes(4)
        if (bytes.size != 4) return 0
        var value = 0L
        for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
        return value % (MAX_BACKDATE_SECONDS + 1)
    }

    internal fun parseRumor(json: String): Rumor? = try {
        val obj = JSON.parseToJsonElement(json) as? JsonObject ?: return null
        Rumor(
            id = obj["id"]?.jsonPrimitive?.content ?: return null,
            pubkey = obj["pubkey"]?.jsonPrimitive?.content ?: return null,
            createdAt = obj["created_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: return null,
            kind = obj["kind"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null,
            tags = (obj["tags"] as? JsonArray)?.map { tag ->
                tag.jsonArray.map { it.jsonPrimitive.content }
            } ?: emptyList(),
            content = obj["content"]?.jsonPrimitive?.content ?: return null,
        )
    } catch (_: Exception) {
        null
    }

    private fun Rumor.toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", createdAt)
        put("kind", kind)
        put("tags", buildJsonArray {
            tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(JsonPrimitive(it)) } }) }
        })
        put("content", content)
    }
}
