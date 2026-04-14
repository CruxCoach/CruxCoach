package com.cruxcoach.android.nostr

import com.cruxcoach.android.nostr.model.RelayConfig

object NostrConfig {
    const val DEV_PUBKEY = "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"

    val DEFAULT_RELAYS = listOf(
        RelayConfig(url = "wss://relay.damus.io"),
        RelayConfig(url = "wss://nos.lol"),
        RelayConfig(url = "wss://relay.primal.net")
    )

    const val RELAY_TIMEOUT_MS = 10_000L
    const val RECONNECT_DELAY_MS = 10_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
    const val MAX_RECONNECT_ATTEMPTS = 5
    const val KOFI_URL = "https://ko-fi.com/cruxcoach"
    const val ANNOUNCE_NAMESPACE = "com.cruxcoach.announce"

    const val DEV_LIGHTNING_ADDRESS = "cruxcoach@npub.cash"
}
