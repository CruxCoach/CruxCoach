package com.cruxcoach.android.nostr.relaydiscovery

import com.cruxcoach.android.nostr.UrlValidation

/**
 * A parsed NIP-65 relay-list event (Kind 10002).
 *
 * Only the fields FEAT-001 actually needs are kept — the raw Quartz event
 * is decoded at the fetcher boundary and thrown away.
 */
data class Kind10002Event(
    val pubkey: String,
    val createdAt: Long,
    val relays: List<RelayMarker>,
) {
    data class RelayMarker(
        val url: String,
        val read: Boolean,
        val write: Boolean,
    )
}

/**
 * Parser for the `tags` array of a Kind 10002 event.
 *
 * Rules (per NIP-65 + FEAT-001 §7.1):
 * - Keep only `["r", url, marker?]` tuples with a `wss://` URL.
 * - Missing or unknown marker → both `read = true` and `write = true`.
 * - `read` / `write` markers are case-insensitive.
 * - Empty URL, non-wss scheme, or duplicate URL → skip the tag (first
 *   occurrence wins for duplicates, so user-ordered preferences survive).
 * - No tag is a fatal error; a Kind 10002 event with zero valid `r` tags
 *   returns an empty list (the caller treats this as "no user list").
 */
internal object Nip65TagParser {

    fun parse(tags: List<List<String>>): List<Kind10002Event.RelayMarker> {
        val seen = LinkedHashMap<String, Kind10002Event.RelayMarker>()
        for (tag in tags) {
            if (tag.size < 2) continue
            if (tag[0] != "r") continue

            val rawUrl = tag[1].trim()
            if (rawUrl.isEmpty()) continue
            val normalized = normalizeWss(rawUrl) ?: continue
            if (seen.containsKey(normalized)) continue

            val marker = tag.getOrNull(2)?.trim()?.lowercase()
            val read: Boolean
            val write: Boolean
            when (marker) {
                "read" -> { read = true; write = false }
                "write" -> { read = false; write = true }
                null, "" -> { read = true; write = true }          // permissive default
                else -> { read = true; write = true }              // unknown marker → permissive
            }

            seen[normalized] = Kind10002Event.RelayMarker(
                url = normalized,
                read = read,
                write = write,
            )
        }
        return seen.values.toList()
    }

    /**
     * Normalize the raw tag value to a canonical `wss://…` form, or
     * return `null` if it isn't a plausible relay URL. Scheme, length
     * and whitespace rules are delegated to [UrlValidation] so the
     * same bar applies to Kind 10002 (here), Kind 10063 Blossom
     * servers, and Kind 30078 backup-pointer server lists.
     */
    private fun normalizeWss(url: String): String? {
        if (!url.startsWith("wss://", ignoreCase = true)) return null
        // Lowercase the scheme but preserve host + path casing (some
        // relays route on path case).
        val canonical = "wss://" + url.substring("wss://".length)
        val trimmed = canonical.trimEnd('/')
        return if (UrlValidation.isValidRelay(trimmed)) trimmed else null
    }
}
