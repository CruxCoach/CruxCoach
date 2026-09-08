package com.cruxcoach.android.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature

/** Pre-parser bounds. OkHttp 4 still buffers a message before this callback. */
internal object RelayInputGuard {
    const val MAX_BYTES = 1024 * 1024

    fun accepts(text: String): Boolean {
        if (text.length > MAX_BYTES) return false
        var bytes = 0
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in text) {
            // Counting surrogate pairs as six bytes is conservative, without
            // allocating another attacker-sized UTF-8 byte array.
            bytes += when { c.code < 128 -> 1; c.code < 2048 -> 2; else -> 3 }
            if (bytes > MAX_BYTES) return false
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '[', '{' -> if (++depth > 32) return false
                ']', '}' -> if (--depth < 0) return false
            }
        }
        return !quoted && depth == 0
    }
}

/** One gate per collected subscription: another consumer cannot suppress it. */
internal class VerifiedEventDeliveryGate(private val authenticate: (String) -> String? = ::verifiedRelayEventId) {
    private val seen = object : LinkedHashMap<String, Boolean>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 10_000
    }

    fun accepts(json: String, skipDedup: Boolean = false): Boolean {
        if (!RelayInputGuard.accepts(json)) return false
        val id = authenticate(json) ?: return false
        return skipDedup || seen.put(id, true) == null
    }
}

private fun verifiedRelayEventId(json: String): String? {
    val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return null
    if (event.tags.size > 4096 || event.tags.any { it.size > 256 }) return null
    // Verify the body as well as the claimed ID, even on historical refetch.
    if (!runCatching { event.verifyId() && event.verifySignature() }.getOrDefault(false)) return null
    return event.id
}
