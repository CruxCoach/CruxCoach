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

    const val RELAY_TIMEOUT_MS = 10_000L
    const val RECONNECT_DELAY_MS = 10_000L
    const val RECONNECT_MAX_DELAY_MS = 60_000L
    const val MAX_RECONNECT_ATTEMPTS = 5
}
