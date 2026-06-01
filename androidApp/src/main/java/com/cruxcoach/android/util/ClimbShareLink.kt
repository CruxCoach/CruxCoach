package com.cruxcoach.android.util

import com.cruxcoach.android.BuildConfig
import com.cruxcoach.domain.community.communityClimbDTag
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

/**
 * Builds the shareable App-Link for a published CruxCoach community climb —
 * the SAME link the climb creator's Kind-1 auto-note uses
 * (`https://<APP_LINK_HOST>/c/<naddr>`).
 *
 * The `naddr` is the NIP-19 reference to the climb's replaceable Kind-30078
 * event `(kind, author pubkey, d-tag)`. The d-tag is deterministic
 * (`communityClimbDTag(pubkey, uuid)`), so the link can be rebuilt from just
 * the author pubkey + uuid — no stored d-tag needed. Opening the link
 * deep-links into the app (manifest App Link `/c/`, parsed by MainActivity)
 * or, if the app isn't installed, falls through to the cruxcoach.org website.
 *
 * Only meaningful for community climbs (those with a `created_by_pubkey`);
 * native Kilter-catalogue climbs have no Nostr event to reference.
 */
object ClimbShareLink {

    private const val KIND_REPLACEABLE_PARAMETERIZED = 30078

    fun build(authorPubkeyHex: String, uuid: String): String {
        val dTag = communityClimbDTag(authorPubkeyHex, uuid)
        val naddr = NAddress.create(
            kind = KIND_REPLACEABLE_PARAMETERIZED,
            pubKeyHex = authorPubkeyHex,
            dTag = dTag,
            relays = emptyList(),
        )
        return "https://${BuildConfig.APP_LINK_HOST}/c/$naddr"
    }
}
