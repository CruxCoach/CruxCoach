package com.cruxcoach.android.nostr

/**
 * Scheme + length gate for every relay / Blossom URL that arrives from
 * an untrusted Nostr event (Kind 10002 relay list, Kind 10063 Blossom
 * server list, Kind 30078 backup pointer). The app's
 * `network_security_config.xml` already refuses cleartext traffic at the
 * OS layer, so these validators are strictly defense-in-depth and
 * hygiene (drop junk at the parse boundary, emit a clear log event,
 * bound cache/DataStore size) — they are NOT the only thing keeping
 * `http://` / `ws://` URLs from being dialed.
 *
 * The 2 KB cap is generous: RFC 1035 caps a DNS host at 253 chars, and
 * every legitimate relay / Blossom URL seen in the wild is well under
 * 128 chars. Anything above that is either a bug, a signed-event bloat
 * attack, or a custom scheme we don't want to cache anyway.
 */
internal object UrlValidation {

    private const val MAX_URL_BYTES = 2048

    /**
     * True if [url] is a syntactically plausible `wss://` relay URL.
     *
     * Rejects: empty, over-length, non-wss scheme, embedded whitespace /
     * control chars, hostname-less schemes (`wss://`) alone.
     */
    fun isValidRelay(url: String): Boolean =
        validWith(url, requiredScheme = "wss://")

    /**
     * True if [url] is a syntactically plausible `https://` Blossom
     * server URL. Same shape as [isValidRelay] but with `https` scheme.
     */
    fun isValidBlossom(url: String): Boolean =
        validWith(url, requiredScheme = "https://")

    private fun validWith(url: String, requiredScheme: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_BYTES) return false
        if (!url.startsWith(requiredScheme, ignoreCase = false)) return false
        val hostAndPath = url.substring(requiredScheme.length)
        if (hostAndPath.isBlank()) return false
        // Reject URLs with any embedded whitespace or control character.
        // These never occur in legitimate Nostr relay / Blossom entries
        // and are a classic log-injection + header-smuggling vector.
        for (c in url) {
            if (c.isWhitespace() || c.code < 0x20 || c.code == 0x7F) return false
        }
        return true
    }
}
