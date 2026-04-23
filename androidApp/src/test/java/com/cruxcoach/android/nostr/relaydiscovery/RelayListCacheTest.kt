package com.cruxcoach.android.nostr.relaydiscovery

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.android.nostr.model.RelaySource
import com.cruxcoach.android.nostr.model.ResolvedRelayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class RelayListCacheTest {

    private lateinit var tempFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private var fakeNow: Long = 1_700_000_000_000L   // fixed epoch for determinism

    @Before
    fun setUp() {
        tempFile = File.createTempFile("relay_cache_test_", ".preferences_pb")
        tempFile.delete()
        tempFile.deleteOnExit()
    }

    @After
    fun tearDown() {
        tempFile.delete()
    }

    private fun newCache(scope: CoroutineScope): RelayListCache {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { tempFile }
        return RelayListCache(dataStore = dataStore, clock = { fakeNow })
    }

    private fun sampleList(resolvedAt: Long = fakeNow): ResolvedRelayList = ResolvedRelayList(
        relays = listOf(
            RelayConfig("wss://relay.a.example.com", read = true, write = true, source = RelaySource.USER_NIP65),
            RelayConfig("wss://relay.b.example.com", read = true, write = false, source = RelaySource.DEFAULT),
        ),
        resolvedAtEpochMs = resolvedAt,
        hasUserList = true,
    )

    @Test
    fun `write then read returns the same list`() = runTest {
        val cache = newCache(backgroundScope)
        cache.write(sampleList())

        val read = cache.read()
        assertEquals(sampleList(), read)
    }

    @Test
    fun `read on empty store returns null`() = runTest {
        val cache = newCache(backgroundScope)
        assertNull(cache.read())
    }

    @Test
    fun `second write overrides first`() = runTest {
        val cache = newCache(backgroundScope)
        cache.write(sampleList())

        val updated = sampleList().copy(
            relays = listOf(RelayConfig("wss://only-me.example.com")),
            resolvedAtEpochMs = fakeNow + 60_000,
        )
        cache.write(updated)
        assertEquals(updated, cache.read())
    }

    @Test
    fun `clear removes the entry`() = runTest {
        val cache = newCache(backgroundScope)
        cache.write(sampleList())
        cache.clear()
        assertNull(cache.read())
    }

    @Test
    fun `isStale true when cache is empty`() = runTest {
        val cache = newCache(backgroundScope)
        assertTrue(cache.isStale(24.hours))
    }

    @Test
    fun `isStale false when within TTL`() = runTest {
        val cache = newCache(backgroundScope)
        cache.write(sampleList(resolvedAt = fakeNow - 1_000L))  // just written 1s ago
        assertFalse(cache.isStale(24.hours))
    }

    @Test
    fun `isStale true when past TTL`() = runTest {
        val cache = newCache(backgroundScope)
        val past = fakeNow - 25.hours.inWholeMilliseconds
        cache.write(sampleList(resolvedAt = past))
        assertTrue(cache.isStale(24.hours))
    }

    @Test
    fun `corrupt stored json surfaces as cache miss`() = runTest {
        val cache = newCache(backgroundScope)
        // Write a raw non-JSON string under the key — read should null out
        dataStore.edit { prefs ->
            prefs[com.cruxcoach.android.data.PreferenceKeys.NIP65_RESOLVED_RELAYS] = "not-json"
        }
        assertNull(cache.read())
    }

    @Test
    fun `schema mismatch returns null`() = runTest {
        val cache = newCache(backgroundScope)
        dataStore.edit { prefs ->
            prefs[com.cruxcoach.android.data.PreferenceKeys.NIP65_RESOLVED_RELAYS] =
                """{"schemaVersion":0,"relays":[],"resolvedAtEpochMs":0,"hasUserList":false}"""
        }
        assertNull(cache.read())
    }
}
