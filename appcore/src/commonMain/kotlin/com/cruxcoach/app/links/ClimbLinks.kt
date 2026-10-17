package com.cruxcoach.app.links

import com.cruxcoach.app.nostr.Nip19

/** The climb share link exactly as Android's `util/ClimbShareLink.build` writes it. */
object ClimbLinks {
    /**
     * `https://<host>/c/<naddr>` for a community climb (kind 30078, its
     * author, the deterministic d-tag, no relays), `https://<host>/c/<uuid>`
     * for a catalogue climb. Null only if NIP-19 encoding fails.
     */
    fun shareLink(createdByPubkey: String?, uuid: String, host: String = DEFAULT_APP_LINK_HOST): String? {
        if (createdByPubkey == null) return "https://$host/c/$uuid"
        val naddr = Nip19.encodeClimbNaddr(createdByPubkey, uuid) ?: return null
        return "https://$host/c/$naddr"
    }
}
