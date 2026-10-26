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
    /** A contact code shown as QR: npub and a one-time secret, valid an hour. */
    const val CONTACT_PREFIX = "cruxcoach:c1:"
    const val PATH = "/f/"
    private const val CODE_BYTES = 72
    private const val CONTACT_BYTES = 68
    private const val CONTACT_TTL_MS = 3_600_000L
    private val CODE = Regex("^[A-Za-z0-9_-]{96}$")
    private val CONTACT = Regex("^[A-Za-z0-9_-]{91}$")
    private val MAGIC = "ccl1".toByteArray()
    private val CONTACT_MAGIC = "ccc1".toByteArray()

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

    /**
     * Who issued [payload] (a link code or a scanned contact code) and until
     * when, without the native layer; null when it is neither. A contact code
     * carries no end: it is valid an hour from when it was shown, so at most
     * that long from [now].
     */
    fun issuer(payload: String, now: Long = System.currentTimeMillis()): LinkIssuer? = when {
        payload.startsWith(LINK_PREFIX) -> payload.removePrefix(LINK_PREFIX).takeIf(CODE::matches)?.let(::issuerOf)
        payload.startsWith(CONTACT_PREFIX) -> payload.removePrefix(CONTACT_PREFIX).takeIf(CONTACT::matches)?.let { code ->
            val bytes = runCatching { Base64.getUrlDecoder().decode(code) }.getOrNull()
            bytes?.takeIf { it.size == CONTACT_BYTES && it.copyOfRange(0, 4).contentEquals(CONTACT_MAGIC) }?.let {
                LinkIssuer(it.copyOfRange(4, 36).joinToString("") { b -> "%02x".format(b) }, now + CONTACT_TTL_MS)
            }
        }
        else -> null
    }

    /** What a scanned QR code holds: a contact code, or an invitation link shown as QR. */
    fun scanned(text: String, host: String = BuildConfig.APP_LINK_HOST): String? {
        val trimmed = text.trim()
        if (trimmed.length > 512) return null
        return trimmed.takeIf { it.startsWith(CONTACT_PREFIX) && issuer(it) != null } ?: payload(trimmed, host)
    }

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
