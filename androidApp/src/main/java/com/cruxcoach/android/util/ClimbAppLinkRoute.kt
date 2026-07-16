package com.cruxcoach.android.util

/** Pure validation for the two external climb-link reference shapes before
 * interpolating a value into a Compose Navigation route. */
internal object ClimbAppLinkRoute {
    fun fromRawReference(reference: String): String? = routeForUuid(reference)

    fun fromNaddr(kind: Int, dTag: String, brandNamespace: String = "cruxcoach"): String? {
        if (kind != COMMUNITY_CLIMB_KIND) return null
        val parts = dTag.split(':')
        if (parts.size != 4 || parts[0] != brandNamespace || parts[1] != "climb") return null
        if (!parts[2].matches(PUBKEY_PREFIX)) return null
        return routeForUuid(parts[3])
    }

    private fun routeForUuid(uuid: String): String? =
        uuid.takeIf { it.matches(CANONICAL_UUID) || it.matches(PLAIN_HEX_UUID) }
            ?.let { "board_climb_detail/$it/$DEFAULT_ANGLE" }

    private val CANONICAL_UUID =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val PLAIN_HEX_UUID = Regex("[0-9a-fA-F]{32}")
    private val PUBKEY_PREFIX = Regex("[0-9a-fA-F]{8}")
    private const val COMMUNITY_CLIMB_KIND = 30078
    private const val DEFAULT_ANGLE = 40
}
