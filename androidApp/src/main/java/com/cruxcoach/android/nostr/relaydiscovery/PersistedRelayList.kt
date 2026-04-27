package com.cruxcoach.android.nostr.relaydiscovery

import kotlinx.serialization.Serializable

/**
 * Wire shape for the DataStore cache entry under
 * `PreferenceKeys.NIP65_RESOLVED_RELAYS`. Bumping [schemaVersion] forces a
 * cache-clear-and-refetch on next read (see [RelayListCache.read]).
 *
 * This type is internal to the relaydiscovery package — consumers see only
 * [com.cruxcoach.android.nostr.model.ResolvedRelayList].
 */
@Serializable
internal data class PersistedRelayList(
    val schemaVersion: Int = SCHEMA_VERSION,
    val relays: List<PersistedRelay>,
    val resolvedAtEpochMs: Long,
    val hasUserList: Boolean,
) {
    companion object {
        /**
         * Bumping this forces a cache-clear + refetch. v1 is the only version
         * at 0.1.3 ship time; see FEAT-001 §11.
         */
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
internal data class PersistedRelay(
    val url: String,
    val read: Boolean,
    val write: Boolean,
    val source: String,
)
