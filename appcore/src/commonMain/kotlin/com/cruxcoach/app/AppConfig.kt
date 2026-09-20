package com.cruxcoach.app

/**
 * Public, maintainer-bound constants, mirroring the defaults Android's
 * `androidApp/build.gradle.kts` bakes into `BuildConfig`. None of these is a
 * secret: they are the project's own published identity, and a fork replaces
 * them here the way it replaces them in `local.properties` on Android.
 */
object AppConfig {
    /** Nostr key bug reports and feature requests are addressed to. */
    const val MAINTAINER_PUBKEY =
        "e75a185c019d09049d5fcb0e29a2cc9bfd016ec0f6d892fc98f6ffe0181a480d"

    /** Label namespace of the project's own announcements. */
    const val ANNOUNCE_NAMESPACE = "com.cruxcoach.announce"

    const val KOFI_URL = "https://ko-fi.com/cruxcoach"

    const val MAINTAINER_LIGHTNING_ADDRESS =
        "npub1uadpshqpn5ysf82lev8zngkvn07szmkq7mvf9lyc7ml7qxq6fqxsmrqt2s@npub.cash"
}
