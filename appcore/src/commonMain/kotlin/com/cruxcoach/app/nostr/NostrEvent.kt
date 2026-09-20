package com.cruxcoach.app.nostr

import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.util.hexToBytesOrNull
import com.cruxcoach.app.util.isLowerHex64
import com.cruxcoach.app.util.toHex
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A NIP-01 event exactly as it travels on the wire. */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String,
) {
    fun firstTagValue(name: String): String? =
        tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

    fun toJson(): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("pubkey", JsonPrimitive(pubkey))
        put("created_at", JsonPrimitive(createdAt))
        put("kind", JsonPrimitive(kind))
        put("tags", tagsToJson(tags))
        put("content", JsonPrimitive(content))
        put("sig", JsonPrimitive(sig))
    }

    companion object {
        /** Null unless every NIP-01 field is present with the right JSON type. */
        fun fromJson(element: JsonElement): NostrEvent? {
            val obj = element as? JsonObject ?: return null
            return try {
                NostrEvent(
                    id = obj.string("id") ?: return null,
                    pubkey = obj.string("pubkey") ?: return null,
                    createdAt = (obj["created_at"] as? JsonPrimitive)?.longOrNull ?: return null,
                    kind = (obj["kind"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null,
                    tags = (obj["tags"] as? JsonArray ?: return null).map { tag ->
                        tag.jsonArray.map { it.jsonPrimitive.contentOrNull ?: return null }
                    },
                    content = obj.string("content") ?: return null,
                    sig = obj.string("sig") ?: return null,
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

object NostrEvents {
    private val json = Json

    /**
     * NIP-01 id: sha256 of the canonical `[0,pubkey,created_at,kind,tags,content]`
     * array, serialized here rather than handed to a JSON library, so the
     * escaping rule is stated instead of inherited.
     *
     * NIP-01 names seven escapes — line feed, quote, backslash, carriage
     * return, tab, backspace, form feed — and says everything else goes in
     * verbatim. **The ecosystem does not actually do that** for the remaining
     * control characters, and it does not agree with itself either: JS
     * (`JSON.stringify`), Rust (`serde_json`) and Kotlin (`kotlinx`) escape
     * them as lowercase `\u001f`, while Jackson — and therefore Quartz, and
     * therefore CruxCoach's own Android app — emits uppercase `\u001F`, which
     * hashes differently. Established by probing Quartz directly; see
     * NOSTR-DEPENDENCY-MIGRATION.md §4.1a.
     *
     * We follow the lowercase majority, which is what this code already did
     * through `kotlinx` and what the library the iOS app now links uses. An
     * event carrying a raw control character therefore still has no single
     * agreed id across clients — that is a gap in NIP-01, not something this
     * function can fix, and it is recorded rather than papered over.
     */
    fun computeId(
        hashing: Hashing,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String {
        val canonical = StringBuilder()
        canonical.append("[0,")
        appendCanonicalString(canonical, pubkey)
        canonical.append(',').append(createdAt).append(',').append(kind).append(",[")
        tags.forEachIndexed { tagIndex, tag ->
            if (tagIndex > 0) canonical.append(',')
            canonical.append('[')
            tag.forEachIndexed { index, value ->
                if (index > 0) canonical.append(',')
                appendCanonicalString(canonical, value)
            }
            canonical.append(']')
        }
        canonical.append("],")
        appendCanonicalString(canonical, content)
        canonical.append(']')
        return hashing.sha256(canonical.toString().encodeToByteArray()).toHex()
    }

    /** NIP-01's seven escapes, then lowercase `\uXXXX` for any other control
     *  character — byte-for-byte what this code produced before, now explicit. */
    private fun appendCanonicalString(out: StringBuilder, value: String) {
        out.append('"')
        for (character in value) {
            when {
                character == '\n' -> out.append("\\n")
                character == '"' -> out.append("\\\"")
                character == '\\' -> out.append("\\\\")
                character == '\r' -> out.append("\\r")
                character == '\t' -> out.append("\\t")
                character == '\b' -> out.append("\\b")
                character == '\u000C' -> out.append("\\f")
                character < '\u0020' -> {
                    out.append("\\u")
                    val code = character.code
                    for (shift in intArrayOf(12, 8, 4, 0)) {
                        out.append(HEX_DIGITS[(code shr shift) and 0xF])
                    }
                }
                else -> out.append(character)
            }
        }
        out.append('"')
    }

    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * True only when the id binds the body AND the BIP-340 signature verifies
     * for that id under the event's pubkey. Same rule as Android's
     * `NostrEventPolicy`: nothing relay-sourced is consumed before this holds.
     */
    fun verify(hashing: Hashing, event: NostrEvent): Boolean {
        if (!event.id.isLowerHex64() || !event.pubkey.isLowerHex64()) return false
        val expectedId = computeId(hashing, event.pubkey, event.createdAt, event.kind, event.tags, event.content)
        if (expectedId != event.id) return false
        val sig = event.sig.hexToBytesOrNull()?.takeIf { it.size == 64 } ?: return false
        val id = event.id.hexToBytesOrNull() ?: return false
        val pubkey = event.pubkey.hexToBytesOrNull() ?: return false
        return try {
            Secp256k1.verifySchnorr(sig, id, pubkey)
        } catch (e: Exception) {
            false
        }
    }
}

private fun tagsToJson(tags: List<List<String>>): JsonArray = buildJsonArray {
    for (tag in tags) add(buildJsonArray { for (value in tag) add(JsonPrimitive(value)) })
}
