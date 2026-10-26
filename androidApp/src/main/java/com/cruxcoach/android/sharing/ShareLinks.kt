package com.cruxcoach.android.sharing

import com.cruxcoach.android.BuildConfig
import java.net.URI
import java.util.Base64

/** Who sent a link and until when it is valid (epoch ms), read before anything is imported. */
data class LinkIssuer(val peer: String, val expiresAt: Long)

/**
 * Invitation links: `https://<host>/f/#<code>`. The code sits in the
 * fragment, which a browser never sends to a web server, so the server of the
 * domain cannot see invitations; without the app the page only explains how to
 * get it. A code in the path or the query is refused.
 *
 * The code is the native link code: npub of the issuer, a one-time secret and
 * the end of validity. Whoever holds the link can use it once, so it is a
 * bearer token: valid seven days, revocable, and the issuer confirms.
 */
object ShareLinks {
    const val LINK_PREFIX = "cruxcoach:l1:"
    const val PATH = "/f/"
    private const val CODE_BYTES = 72
    private val CODE = Regex("^[A-Za-z0-9_-]{96}$")
    private val MAGIC = "ccl1".toByteArray()

    fun url(payload: String, host: String = BuildConfig.APP_LINK_HOST): String {
        require(payload.startsWith(LINK_PREFIX)) { "link_format" }
        return "https://$host$PATH#${payload.removePrefix(LINK_PREFIX)}"
    }

    /** The native payload of a link, or null when [text] is no invitation link of [host]. */
    fun payload(text: String, host: String = BuildConfig.APP_LINK_HOST): String? {
        if (text.length > 512) return null
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != host || uri.port != -1) return null
        if (uri.rawPath != PATH || uri.rawQuery != null || uri.rawUserInfo != null) return null
        val code = uri.rawFragment ?: return null
        if (!CODE.matches(code) || issuerOf(code) == null) return null
        return LINK_PREFIX + code
    }

    /** Who issued [payload] and until when, without the native layer; null when it is no link code. */
    fun issuer(payload: String): LinkIssuer? =
        payload.takeIf { it.startsWith(LINK_PREFIX) }?.removePrefix(LINK_PREFIX)?.takeIf(CODE::matches)?.let(::issuerOf)

    private fun issuerOf(code: String): LinkIssuer? {
        val bytes = runCatching { Base64.getUrlDecoder().decode(code) }.getOrNull() ?: return null
        if (bytes.size != CODE_BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        val peer = bytes.copyOfRange(4, 36).joinToString("") { "%02x".format(it) }
        val until = bytes.copyOfRange(68, 72).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        return LinkIssuer(peer, until * 1000)
    }
}

/**
 * A link opened while the app was not looking at it: kept in memory only
 * (never in saved state or a navigation route) until the person sends the
 * request or dismisses it.
 */
object ShareLinkInbox {
    private val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val link: kotlinx.coroutines.flow.StateFlow<String?> = pending

    fun offer(payload: String) {
        pending.value = payload
    }

    /** Forget [payload] (or whatever waits, when null), unless another link replaced it meanwhile. */
    fun clear(payload: String? = null) {
        if (payload == null) pending.value = null else pending.compareAndSet(payload, null)
    }
}
