package com.cruxcoach.android.nostr.model

/**
 * The single source of truth for which relays the app talks to, after
 * FEAT-001 discovery has run. Consumers read this via
 * [com.cruxcoach.android.nostr.relaydiscovery.RelayListResolver.current] and
 * never hold it across refreshes — the resolver emits a new instance each
 * time the resolved set changes.
 *
 * [hasUserList] is `false` when the user has no Kind 10002 published and the
 * resolved list is `NostrConfig.DEFAULT_RELAYS` only. Telemetry uses this to
 * distinguish cold-start-defaults from "user list is empty on purpose".
 */
data class ResolvedRelayList(
    val relays: List<RelayConfig>,
    val resolvedAtEpochMs: Long,
    val hasUserList: Boolean,
)
