package com.cruxcoach.app.map

/**
 * Validation for the links a venue sheet opens. Port of Android
 * `VenueSheet.kt`'s helpers: the dataset is third-party, so a crafted record
 * must not point the browser at an internal host or hand the dialer garbage.
 * Returns plain strings (Android returns `Uri`); "" / null means "hide the action".
 */
object MapLinks {

    /** Dialer-safe characters only; null when no digit is left. */
    fun sanitisedPhoneOrNull(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val cleaned = trimmed.filter { it == '+' || it == '-' || it == '(' || it == ')' || it == ' ' || it.isDigit() }
        return cleaned.takeIf { it.any(Char::isDigit) }
    }

    /** An Instagram handle ("@gym", "gym") or profile URL as one https profile link. */
    fun instagramUrlOrNull(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val handle = trimmed
            .substringAfter("instagram.com/", trimmed)
            .removePrefix("@")
            .substringBefore('/')
            .substringBefore('?')
        if (!HANDLE.matches(handle)) return null
        return "https://www.instagram.com/$handle/"
    }

    private val HANDLE = Regex("^[A-Za-z0-9._]{1,30}$")

    /** A plain e-mail address (no header injection); null otherwise. */
    fun validatedEmailOrNull(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return trimmed.takeIf { EMAIL.matches(it) }
    }

    // Android's Patterns.EMAIL_ADDRESS, minus its 256-character local-part form.
    private val EMAIL = Regex(
        "^[a-zA-Z0-9+._%\\-]{1,256}@[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}(\\.[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25})+$",
    )

    /**
     * The URL only when its scheme is http(s) and its host is not private,
     * loopback or link-local. A bare "example.com" becomes https.
     */
    fun validatedHttpUrlOrNull(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val scheme = candidate.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val afterScheme = candidate.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        val hostPort = authority.substringAfterLast('@')
        // An IPv6 literal is bracketed in a URL, so any other ':' starts the port.
        val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
        if (host.isEmpty() || isPrivateOrLoopbackHost(host)) return null
        return candidate
    }

    /**
     * True for hosts never to open from dataset URLs: localhost, IPv4
     * loopback/private/link-local in every inet_aton encoding, and IPv6
     * loopback / unique-local / link-local (also IPv4-mapped).
     */
    fun isPrivateOrLoopbackHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase()?.removeSurrounding("[", "]") ?: return true
        if (h.isEmpty() || h == "localhost") return true
        if (':' in h) {
            val mapped = h.substringAfterLast("::ffff:", "")
            if (mapped.isNotEmpty()) {
                ipv4LiteralToLong(mapped)?.let { return isPrivateOrLoopbackIpv4(it) }
                val g = mapped.split(":")
                if (g.size == 2) {
                    val hi = g[0].toLongOrNull(16)
                    val lo = g[1].toLongOrNull(16)
                    if (hi != null && lo != null && hi <= 0xFFFF && lo <= 0xFFFF) {
                        return isPrivateOrLoopbackIpv4((hi shl 16) or lo)
                    }
                }
            }
            return h == "::1" || h == "::" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }
        val ipv4 = ipv4LiteralToLong(h) ?: return false // not numeric: a real (public) hostname
        return isPrivateOrLoopbackIpv4(ipv4)
    }

    private fun isPrivateOrLoopbackIpv4(v: Long): Boolean {
        val a = (v ushr 24) and 0xFF
        val b = (v ushr 16) and 0xFF
        return a == 127L || a == 10L || a == 0L ||
            (a == 172L && b in 16L..31L) ||
            (a == 192L && b == 168L) ||
            (a == 169L && b == 254L)
    }

    private fun ipv4LiteralToLong(s: String): Long? {
        if (s.isEmpty() || s.any { it !in "0123456789abcdefx." }) return null
        val parts = s.split(".")
        if (parts.size > 4) return null
        val nums = parts.map { p -> parseIpv4Part(p) ?: return null }
        return when (nums.size) {
            1 -> nums[0].takeIf { it in 0..0xFFFFFFFFL }
            2 -> if (nums[0] <= 0xFF && nums[1] <= 0xFFFFFF) (nums[0] shl 24) or nums[1] else null
            3 -> if (nums[0] <= 0xFF && nums[1] <= 0xFF && nums[2] <= 0xFFFF)
                (nums[0] shl 24) or (nums[1] shl 16) or nums[2] else null
            4 -> if (nums.all { it <= 0xFF })
                (nums[0] shl 24) or (nums[1] shl 16) or (nums[2] shl 8) or nums[3] else null
            else -> null
        }
    }

    private fun parseIpv4Part(p: String): Long? = when {
        p.isEmpty() -> null
        p.startsWith("0x") -> p.drop(2).ifEmpty { null }?.toLongOrNull(16)
        p.length > 1 && p[0] == '0' -> p.toLongOrNull(8)
        else -> p.toLongOrNull(10)
    }
}
