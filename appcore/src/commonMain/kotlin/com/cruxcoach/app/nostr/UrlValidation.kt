package com.cruxcoach.app.nostr

/**
 * Scheme + length gate for every relay / Blossom URL that arrives inside an
 * untrusted Nostr event (kind 10002 relay list, kind 10063 Blossom server
 * list, kind 30078 backup pointer). Ported unchanged from Android.
 *
 * iOS has no `network_security_config.xml`, so unlike on Android this really
 * is the thing that keeps a plaintext URL from being dialed: the platform
 * transports additionally refuse anything that is not https / wss.
 */
object UrlValidation {
    private const val MAX_URL_BYTES = 2048

    fun isValidRelay(url: String): Boolean = validWith(url, "wss://")

    fun isValidBlossom(url: String): Boolean = validWith(url, "https://")

    private fun validWith(url: String, requiredScheme: String): Boolean {
        if (url.isEmpty() || url.length > MAX_URL_BYTES) return false
        if (!url.startsWith(requiredScheme)) return false
        if (url.substring(requiredScheme.length).isBlank()) return false
        // Whitespace and control characters never occur in a legitimate entry
        // and are a classic log-injection / header-smuggling vector.
        for (c in url) {
            if (c.isWhitespace() || c.code < 0x20 || c.code == 0x7F) return false
        }
        return true
    }
}
