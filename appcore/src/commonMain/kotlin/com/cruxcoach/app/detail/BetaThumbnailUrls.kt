package com.cruxcoach.app.detail

import com.cruxcoach.app.util.toHex

// Port of Android's ui/board/BetaThumbnailUrls.kt without java.net.URI: a strict
// parser that accepts less than URI does never widens the trusted set.

/** Shared by URL admission and failover so every mirror can serve as the primary. */
val betaThumbnailHosts = listOf(
    "nostr.download", "blossom.primal.net", "cdn.hzrd149.com", "blossom.cruxcoach.org",
)

private val blobPath = Regex("/[0-9a-f]{64}")
private val httpsUrl = Regex("^https://([^/?#]*)([^?#]*)(\\?[^#]*)?(#.*)?$")
private val hostName = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

private class ParsedHttps(val host: String, val path: String, val plain: Boolean)

// https only, no userinfo, default port or 443, no whitespace/control characters.
private fun parseHttps(url: String): ParsedHttps? {
    if (url.any { it <= ' ' || it == '\\' || it.code == 0x7f }) return null
    val m = httpsUrl.matchEntire(url) ?: return null
    val authority = m.groupValues[1]
    if ('@' in authority) return null
    val colon = authority.lastIndexOf(':')
    val host = if (colon >= 0) authority.substring(0, colon) else authority
    if (colon >= 0) {
        val port = authority.substring(colon + 1)
        if (port.isNotEmpty() && port.toIntOrNull() != 443) return null
    }
    if (!hostName.matches(host)) return null
    return ParsedHttps(host, m.groupValues[2], m.groupValues[3].isEmpty() && m.groupValues[4].isEmpty())
}

/**
 * Empty = not loadable (non-https, userinfo, foreign port, unparseable). A single
 * element = loadable without failover. Only content-addressed images
 * (`/<64 lowercase hex>`, no query/fragment) on known Blossom mirrors get failover.
 */
fun betaThumbnailUrls(url: String): List<String> {
    val u = parseHttps(url) ?: return emptyList()
    if (u.host !in betaThumbnailHosts || !u.plain || !blobPath.matches(u.path)) return listOf(url)
    return (listOf(url) + betaThumbnailHosts.map { "https://$it${u.path}" }).distinct()
}

/** Known placeholder images that must never be shown as a beta thumbnail. */
private val betaThumbnailPlaceholderHashes = setOf(
    "555f5ee1978ef15c15ca6bd780f1b205e56117f551eb4561ec10d1b9437c9a8e",
)

const val BETA_THUMBNAIL_MAX_BYTES = 512 * 1024

/** The sha256 the bytes behind [url] MUST have, or null when the URL is not a verifiable mirror blob. */
fun betaThumbnailHash(url: String): String? {
    val u = parseHttps(url) ?: return null
    if (!u.plain || u.host !in betaThumbnailHosts) return null
    return u.path.removePrefix("/").takeIf {
        Regex("[0-9a-f]{64}").matches(it) && it !in betaThumbnailPlaceholderHashes
    }
}

/** Only bytes that pass this check may be decoded as an image; the image view never sees a remote URL. */
fun validBetaThumbnailBytes(bytes: ByteArray, hash: String, hashing: com.cruxcoach.app.platform.Hashing): Boolean =
    bytes.isNotEmpty() && bytes.size <= BETA_THUMBNAIL_MAX_BYTES &&
        hashing.sha256(bytes).toHex() == hash
