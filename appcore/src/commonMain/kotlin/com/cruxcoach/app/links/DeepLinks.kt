package com.cruxcoach.app.links

/** What a recognised link asks the app to open. */
sealed class DeepLink {
    /** [authorPubkeyHex] is empty for catalogue climbs. */
    data class Climb(val climbUuid: String, val angle: Int, val authorPubkeyHex: String) : DeepLink()

    /** The still-unparsed playlist payload; [PlaylistShareLink.parse] validates it. */
    data class PlaylistImport(val payload: String) : DeepLink()
}

/**
 * Port of the `MainActivity` link handling: `https://<host>/c/…` and
 * `https://<host>/l/…`.
 *
 * iOS free-provisioning builds cannot register universal links, so the custom
 * scheme `cruxcoach://c/…` and `cruxcoach://l/…` are accepted as equivalents
 * and pasted URLs go through the same parser. That is an iOS-side addition:
 * it changes no link the app hands out.
 */
class DeepLinkParser(
    private val host: String = DEFAULT_APP_LINK_HOST,
    private val naddr: NaddrCodec = UnsupportedNaddrCodec,
    /** The user's preferred angle; the link never carries one by design. */
    private val defaultAngle: () -> Int = { 40 },
) {

    fun parse(url: String): DeepLink? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
        val rest = when {
            trimmed.startsWith("$CUSTOM_SCHEME://", ignoreCase = true) ->
                trimmed.substring(CUSTOM_SCHEME.length + 3)
            trimmed.startsWith("https://", ignoreCase = true) -> {
                val afterScheme = trimmed.substring(8)
                val slash = afterScheme.indexOf('/')
                if (slash <= 0) return null
                // Host must match exactly; a look-alike host is not our link.
                if (!afterScheme.substring(0, slash).equals(host, ignoreCase = true)) return null
                afterScheme.substring(slash + 1)
            }
            else -> return null
        }
        val path = rest.substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        return when (segments[0]) {
            "c" -> climbLink(segments[1])
            "l" -> playlistLink(segments[1])
            else -> null
        }
    }

    private fun climbLink(ref: String): DeepLink? {
        if (!ref.startsWith("naddr1")) {
            // Raw-uuid form: legacy 32-hex Kilter uuids and dashed ones alike.
            return if (isUuidish(ref)) DeepLink.Climb(ref, defaultAngle(), "") else null
        }
        val address = naddr.decode(ref) ?: return null
        if (address.kind != ClimbShareLink.KIND_REPLACEABLE_PARAMETERIZED) return null
        // Expected d-tag: "cruxcoach:climb:<pubkey-prefix-8>:<uuid>".
        val parts = address.dTag.split(":")
        if (parts.size != 4 || parts[0] != "cruxcoach" || parts[1] != "climb") return null
        val uuid = parts[3]
        val author = address.authorPubkeyHex
        if (!isUuidish(uuid)) return null
        if (author.length != 64 || !author.all { isHex(it) }) return null
        if (!parts[2].equals(author.take(8), ignoreCase = true)) return null
        return DeepLink.Climb(uuid, defaultAngle(), author.lowercase())
    }

    private fun playlistLink(payload: String): DeepLink? {
        if (payload.isEmpty() || payload.length > MAX_PAYLOAD_LENGTH) return null
        // Shape check only; the import screen does the real validation.
        if (!payload.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }) return null
        return DeepLink.PlaylistImport(payload)
    }

    private fun isUuidish(value: String): Boolean =
        value.length in 8..64 && value.all { isHex(it) || it == '-' }

    private fun isHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    companion object {
        const val CUSTOM_SCHEME = "cruxcoach"
        const val MAX_PAYLOAD_LENGTH = 4096
        const val MAX_URL_LENGTH = 8192
    }
}
