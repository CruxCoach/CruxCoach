package com.cruxcoach.domain.competition

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * CruxCoach Canonical JSON — FEAT-058 §4.1.
 *
 * The website's `competitions/app/protocol/ccj.mjs` is the other half of this;
 * both are pinned to `competition/vectors/protocol.json`. The rules are narrow
 * on purpose, because every degree of freedom is a way for two languages to
 * disagree about bytes that get hashed:
 *
 *  - object keys sorted ascending by UTF-16 code unit (Kotlin's natural
 *    [String.compareTo] and JavaScript's default comparison agree on this, and
 *    keys are restricted to `[a-z0-9_]` so the agreement is total, not merely
 *    likely)
 *  - no insignificant whitespace
 *  - integers only — a fractional number is a programming error and throws
 *  - `null` is never emitted; an absent value is an absent key
 *  - string escaping identical to JavaScript's `JSON.stringify`
 */
object Ccj {

    /** Serialize [element] canonically. */
    fun encode(element: JsonElement): String = buildString { write(element, this) }

    private fun write(element: JsonElement, out: StringBuilder) {
        when (element) {
            is JsonNull -> throw IllegalArgumentException("CCJ: null must be omitted, not serialized")
            is JsonPrimitive -> writePrimitive(element, out)
            is JsonArray -> {
                out.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    write(item, out)
                }
                out.append(']')
            }
            is JsonObject -> {
                val keys = element.entries
                    .filter { it.value !is JsonNull }
                    .map { it.key }
                    .sorted()
                out.append('{')
                keys.forEachIndexed { index, key ->
                    require(KEY_PATTERN.matches(key)) { "CCJ: object key \"$key\" is not [a-z0-9_]+" }
                    if (index > 0) out.append(',')
                    escape(key, out)
                    out.append(':')
                    write(element.getValue(key), out)
                }
                out.append('}')
            }
        }
    }

    private fun writePrimitive(primitive: JsonPrimitive, out: StringBuilder) {
        if (primitive.isString) {
            escape(primitive.content, out)
            return
        }
        val raw = primitive.content
        if (raw == "true" || raw == "false") {
            out.append(raw)
            return
        }
        val value = raw.toLongOrNull()
            ?: throw IllegalArgumentException("CCJ: only integers are allowed, got $raw")
        require(raw == value.toString()) { "CCJ: $raw is not in shortest integer form" }
        require(value != 0L || !raw.startsWith("-")) { "CCJ: -0 is not a valid value" }
        require(value in SAFE_MIN..SAFE_MAX) { "CCJ: integer $value is outside the safe range" }
        out.append(value)
    }

    /**
     * Exactly what `JSON.stringify` produces: the seven two-character escapes,
     * every other C0 control character as `\uXXXX`, and — since ES2019's
     * well-formed `JSON.stringify` — lone surrogates as `\uXXXX` too. A paired
     * surrogate is written through literally, so an emoji stays an emoji.
     */
    private fun escape(value: String, out: StringBuilder) {
        out.append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\u000C' -> out.append("\\f")
                char == '\n' -> out.append("\\n")
                char == '\r' -> out.append("\\r")
                char == '\t' -> out.append("\\t")
                char < ' ' -> out.append(unicodeEscape(char))
                char.isHighSurrogate() -> {
                    val next = value.getOrNull(index + 1)
                    if (next != null && next.isLowSurrogate()) {
                        out.append(char).append(next)
                        index++
                    } else {
                        out.append(unicodeEscape(char))
                    }
                }
                char.isLowSurrogate() -> out.append(unicodeEscape(char))
                else -> out.append(char)
            }
            index++
        }
        out.append('"')
    }

    private fun unicodeEscape(char: Char): String {
        val hex = char.code.toString(16)
        return "\\u" + "0".repeat(4 - hex.length) + hex
    }

    private val KEY_PATTERN = Regex("^[a-z0-9_]+$")

    /** JavaScript's `Number.MAX_SAFE_INTEGER`; the wire format must not exceed it. */
    private const val SAFE_MAX = 9007199254740991L
    private const val SAFE_MIN = -9007199254740991L
}

/**
 * SHA-256 of a UTF-8 string as lowercase hex.
 *
 * `expect`/`actual` for the same reason [com.cruxcoach.domain.community.FramesHash]
 * is: Kotlin Multiplatform has no common digest, and this project does not
 * carry a crypto dependency into `commonMain` for one hash.
 */
expect object CompetitionDigest {
    fun sha256Hex(input: String): String
}

/** The state hash two clients must agree on (FEAT-058 §4.3). */
fun ccjHash(element: JsonElement): String = CompetitionDigest.sha256Hex(Ccj.encode(element))
