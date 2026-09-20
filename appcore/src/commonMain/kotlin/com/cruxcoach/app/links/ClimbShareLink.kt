package com.cruxcoach.app.links

import com.cruxcoach.domain.community.communityClimbDTag

/** NIP-19 `naddr` for a parameterized replaceable event. */
data class NostrAddress(val kind: Int, val authorPubkeyHex: String, val dTag: String)

/**
 * NIP-19 bech32 is written by another agent; the share link only needs the two
 * directions of it. Both return null when the value cannot be represented.
 */
interface NaddrCodec {
    fun encode(address: NostrAddress): String?
    fun decode(naddr: String): NostrAddress?
}

/** No NIP-19 available: community links can be neither built nor read. */
object UnsupportedNaddrCodec : NaddrCodec {
    override fun encode(address: NostrAddress): String? = null
    override fun decode(naddr: String): NostrAddress? = null
}

/**
 * Shareable link for a climb: `https://<host>/c/…`.
 *
 * Community climbs (with a Nostr author) use `/c/<naddr>` — the same link the
 * author's note carries; the d-tag is deterministic, so the address rebuilds
 * from pubkey + uuid alone. Catalogue climbs use `/c/<uuid>`: they have no
 * event to reference, and every device carries the catalogue.
 */
object ClimbShareLink {

    const val KIND_REPLACEABLE_PARAMETERIZED = 30078

    /** Null when a community link is requested but NIP-19 is unavailable. */
    fun build(
        authorPubkeyHex: String?,
        uuid: String,
        host: String = DEFAULT_APP_LINK_HOST,
        naddr: NaddrCodec = UnsupportedNaddrCodec,
    ): String? {
        if (authorPubkeyHex == null) return "https://$host/c/$uuid"
        val encoded = naddr.encode(
            NostrAddress(
                kind = KIND_REPLACEABLE_PARAMETERIZED,
                authorPubkeyHex = authorPubkeyHex,
                dTag = communityClimbDTag(authorPubkeyHex, uuid),
            )
        ) ?: return null
        return "https://$host/c/$encoded"
    }
}
