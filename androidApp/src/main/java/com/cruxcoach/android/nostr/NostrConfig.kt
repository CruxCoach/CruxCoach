package com.cruxcoach.android.nostr

import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.nostr.model.RelayConfig

object NostrConfig {
    // Maintainer-bound: forks override via local.properties — see
    // CONTRIBUTING.md "Customizing for forks". Defaults baked in by the
    // Gradle build live in androidApp/build.gradle.kts.
    val DEV_PUBKEY: String = BuildConfig.MAINTAINER_PUBKEY
    val KOFI_URL: String = BuildConfig.MAINTAINER_KOFI_URL
    val DEV_LIGHTNING_ADDRESS: String = BuildConfig.MAINTAINER_LIGHTNING_ADDRESS
    val ANNOUNCE_NAMESPACE: String = BuildConfig.ANNOUNCE_NAMESPACE

    val DEFAULT_RELAYS = listOf(
        RelayConfig(url = "wss://relay.damus.io"),
        RelayConfig(url = "wss://nos.lol"),
        RelayConfig(url = "wss://relay.primal.net")
    )

    /**
     * Relays used exclusively for the Blossom board-DB manifest (Kind 30078).
     *
     * The manifest event is ~66 KB, which trips the 64 KB default limit most
     * public relays enforce. This list is a curated subset of relays confirmed
     * to accept that size, so losing any single one doesn't strand the app.
     * Verified 2026-04-21 by publishing the live manifest and reading back the
     * OK/NOTICE response:
     *   - relay.primal.net           — publisher's home relay
     *   - relay.damus.io             — large iOS client base, high uptime
     *   - nostr-pub.wellorder.net    — independent operator, generous limits
     */
    val MANIFEST_RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://nostr-pub.wellorder.net"
    )

    const val RELAY_TIMEOUT_MS = 10_000L
    const val RECONNECT_DELAY_MS = 10_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
    const val MAX_RECONNECT_ATTEMPTS = 5
    /** Once the bounded reconnect ladder above is exhausted, the
     *  pool falls back to a long-interval retry loop instead of
     *  giving up forever. 5 minutes balances battery cost against
     *  the worst-case "user wonders why nothing arrives even though
     *  Wi-Fi is fine" silence window. The loop exits as soon as a
     *  reconnect succeeds or `reconnectAll()` is called externally
     *  (foreground tick, OS network-event). */
    const val RECONNECT_SLOW_RETRY_MS = 5L * 60L * 1000L
}
