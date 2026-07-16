package com.cruxcoach.android.util

import com.cruxcoach.android.BuildConfig
import com.cruxcoach.domain.community.communityClimbDTag
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

/**
 * Builds the shareable App-Link for a climb (`https://<APP_LINK_HOST>/c/…`).
 * Opening the link deep-links into the app (manifest App Link `/c/`, parsed
 * by MainActivity) or, if the app isn't installed, falls through to the
 * cruxcoach.org website.
 *
 * Two link shapes, distinguished by whether the climb has a Nostr author:
 *
 *  - Community climbs (`created_by_pubkey` set): `/c/<naddr>` — the SAME
 *    link the climb creator's Kind-1 auto-note uses. The `naddr` is the
 *    NIP-19 reference to the climb's replaceable Kind-30078 event
 *    `(kind, author pubkey, d-tag)`. The d-tag is deterministic
 *    (`communityClimbDTag(pubkey, uuid)`), so the link can be rebuilt from
 *    just the author pubkey + uuid — no stored d-tag needed.
 *
 *  - Catalogue climbs (no pubkey): `/c/<uuid>` — the climb has no Nostr
 *    event to reference, but every device carries the catalogue, so the
 *    raw uuid resolves locally on the receiving side.
 */
object ClimbShareLink {

    private const val KIND_REPLACEABLE_PARAMETERIZED = 30078

    fun build(authorPubkeyHex: String?, uuid: String): String {
        if (authorPubkeyHex == null) {
            return "https://${BuildConfig.APP_LINK_HOST}/c/$uuid"
        }
        val dTag = communityClimbDTag(
            authorPubkeyHex,
            uuid,
            BuildConfig.BRAND_NAMESPACE,
        )
        val naddr = NAddress.create(
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            pubKeyHex = authorPubkeyHex,
            dTag = dTag,
            relays = emptyList(),
        )
        return "https://${BuildConfig.APP_LINK_HOST}/c/$naddr"
    }
}
