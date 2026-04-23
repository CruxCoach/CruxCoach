package com.cruxcoach.android.nostr.relaydiscovery

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.cruxcoach.android.data.PreferenceKeys
import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.android.nostr.model.RelaySource
import com.cruxcoach.android.nostr.model.ResolvedRelayList
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * DataStore-Preferences-backed cache for the resolved relay list.
 *
 * Wire format: the [PersistedRelayList] JSON under a single string key
 * (`PreferenceKeys.NIP65_RESOLVED_RELAYS`). One key keeps migrations trivial:
 * bumping [PersistedRelayList.SCHEMA_VERSION] in code turns reads of the old
 * JSON into a cache-miss (see [read]), and the next successful fetch
 * overwrites the entry.
 *
 * No Flow-based API — consumers want a point-in-time snapshot, not a live
 * subscription. Writes are atomic per DataStore semantics.
 */
@Singleton
class RelayListCache @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the cached list, or `null` on cache miss. A cache miss can be
     * any of: never written, deserialization failed, schema version mismatch.
     * In all three cases the caller proceeds to fetch fresh data.
     */
    suspend fun read(): ResolvedRelayList? {
        val raw = dataStore.data.first()[PreferenceKeys.NIP65_RESOLVED_RELAYS] ?: return null
        val persisted = try {
            json.decodeFromString(PersistedRelayList.serializer(), raw)
        } catch (e: SerializationException) {
            Log.w(TAG, "event=cache_miss reason=corrupt", e)
            return null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "event=cache_miss reason=corrupt", e)
            return null
        }
        if (persisted.schemaVersion != PersistedRelayList.SCHEMA_VERSION) {
            Log.d(TAG, "event=cache_miss reason=schema-mismatch persisted=${persisted.schemaVersion} current=${PersistedRelayList.SCHEMA_VERSION}")
            return null
        }
        val age = clock() - persisted.resolvedAtEpochMs
        Log.d(TAG, "event=cache_hit ageMs=$age")
        return ResolvedRelayList(
            relays = persisted.relays.map { it.toRelayConfig() },
            resolvedAtEpochMs = persisted.resolvedAtEpochMs,
            hasUserList = persisted.hasUserList,
        )
    }

    suspend fun write(list: ResolvedRelayList) {
        val persisted = PersistedRelayList(
            relays = list.relays.map { it.toPersisted() },
            resolvedAtEpochMs = list.resolvedAtEpochMs,
            hasUserList = list.hasUserList,
        )
        val raw = json.encodeToString(PersistedRelayList.serializer(), persisted)
        dataStore.edit { it[PreferenceKeys.NIP65_RESOLVED_RELAYS] = raw }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(PreferenceKeys.NIP65_RESOLVED_RELAYS) }
    }

    /** True when there is no cache or its `resolvedAtEpochMs` is older than [ttl]. */
    suspend fun isStale(ttl: Duration = TTL_DEFAULT): Boolean {
        val cached = read() ?: return true
        val age = clock() - cached.resolvedAtEpochMs
        return age >= ttl.inWholeMilliseconds
    }

    private fun PersistedRelay.toRelayConfig(): RelayConfig {
        val parsedSource = runCatching { RelaySource.valueOf(source) }
            .getOrDefault(RelaySource.DEFAULT)
        return RelayConfig(url = url, read = read, write = write, source = parsedSource)
    }

    private fun RelayConfig.toPersisted(): PersistedRelay =
        PersistedRelay(url = url, read = read, write = write, source = source.name)

    companion object {
        private const val TAG = "Nip65Discovery"
        val TTL_DEFAULT: Duration = 24.hours
    }
}
