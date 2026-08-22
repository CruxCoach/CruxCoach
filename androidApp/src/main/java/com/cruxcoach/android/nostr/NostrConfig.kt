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

    // Community-climb publish + live-subscribe set. nos.lol is the relay that
    // empirically RETAINS one-time community-climb Kind-30078 events long-term
    // (damus/primal age them out); wellorder + oxtr are added for redundancy
    // so a community climb never depends on a single relay surviving. wellorder
    // is the same retention-friendly operator we already trust for the manifest.
    // Probed 2026-06-05: community-climb events were on nos.lol only.
    // relay.snort.social replaced by nostr.oxtr.dev on 2026-08-05: snort had
    // stopped answering entirely (no WebSocket, no HTTP), so it was not a
    // fifth relay, it was a timeout on every fetch that looked like one.
    val DEFAULT_RELAYS = listOf(
        RelayConfig(url = "wss://relay.damus.io"),
        RelayConfig(url = "wss://nos.lol"),
        RelayConfig(url = "wss://relay.primal.net"),
        RelayConfig(url = "wss://nostr-pub.wellorder.net"),
        RelayConfig(url = "wss://nostr.oxtr.dev")
    )

    /**
     * Relays used exclusively for the Blossom board-DB manifest (Kind 30078).
     *
     * The manifest event is ~127 KB for Kilter (measured 2026-08-05), far
     * past the 64 KB default limit most public relays enforce — of nine
     * candidates probed with the real event that day, exactly one accepted
     * it. That is why this list cannot simply be widened with popular
     * relays; each entry has to be verified with a real publish. This list is a curated subset of relays confirmed
     * to accept that size, so losing any single one doesn't strand the app.
     * Verified 2026-04-21 by publishing the live manifest and reading back the
     * OK/NOTICE response:
     *   - relay.primal.net           — publisher's home relay
     *   - relay.damus.io             — large iOS client base, high uptime
     *   - nostr-pub.wellorder.net    — independent operator, generous limits
     */
    /*
     * Widened 2026-08-05 after a fresh install failed to import the So iLL
     * catalogue. A replication sweep (cruxcoach-blossom-sync/
     * check_manifest_replication.py) found five of seven board manifests on
     * a single relay, four of them on damus alone — while damus was serving
     * 503. Three relays is only three-fold redundancy if the event is
     * actually ON three; it was not.
     *
     * More readers here cannot fix a manifest that was never replicated, but
     * it widens where a correctly published one can be found, and it makes
     * the publish side's job checkable: the pipeline publishes to this set
     * and verifies against it.
     *
     * Adding a relay only helps builds that ship with it — this list is
     * compiled in, so installs on 0.2.1 and older keep querying the old
     * three. That is the same one-way constraint the release-source list has,
     * and the reason both are worth widening now rather than later.
     *
     * 0.2.3 adds the operator-controlled manifest-only relay. It is public
     * for reads but accepts writes exclusively from the dedicated manifest
     * signer for the known board d-tags. Keeping the independent public
     * relays preserves failure-domain diversity while guaranteeing that one
     * compatible endpoint remains under CruxCoach operational control.
     */
    val MANIFEST_RELAYS = listOf(
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://nostr-pub.wellorder.net",
        "wss://nos.lol",
        "wss://nostr.oxtr.dev",
        "wss://blossom.cruxcoach.org/nostr",
    )

    /**
     * Rumor tag carrying the LOCAL (self-wrap) id of the thread root on
     * outgoing replies: `["self_root", <localRootId>]`.
     *
     * NIP-17 wraps the same rumor twice with DIFFERENT event ids (self-wrap
     * = our local row id, recipient-wrap = what the dashboard stores), and a
     * rumor cannot reference its own wrap ids (they only exist after
     * wrapping). The outgoing `["e", …, "reply"]` tag must carry the
     * RECIPIENT-wrap id of the root so the dashboard can thread the reply —
     * which leaves the local root id unrecoverable when a wipe-and-refetch
     * re-ingests our own reply echoes. This extra tag preserves it.
     *
     * Deliberately NOT a second `e` tag: the dashboard threads on `e` tags
     * and must never see the self-wrap id (it would recreate the orphan
     * thread bug this tag exists to prevent). Unknown tags are ignored by
     * other NIP-17 clients.
     */
    const val RUMOR_TAG_SELF_ROOT = "self_root"

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
