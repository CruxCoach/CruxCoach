package com.cruxcoach.android.nostr.relaydiscovery

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.model.RelaySource
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [RelayListResolver.mergeAdditive]. The resolver's
 * collaborators are mocked — this is just about the merge algorithm.
 */
class RelayListMergeTest {

    private fun makeResolver(): RelayListResolver = RelayListResolver(
        fetcher = mockk(relaxed = true),
        cache = mockk(relaxed = true),
        pool = mockk<NostrRelayPool>(relaxed = true),
        pubkeyProvider = RelayListResolver.PubkeyProvider { null },
        userPreferences = mockk<UserPreferences>(relaxed = true),
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        clock = { 0L },
    )

    @Test
    fun `user list first then defaults not already present`() {
        val user = listOf(
            Kind10002Event.RelayMarker("wss://user-a.example.com", read = true, write = true),
            Kind10002Event.RelayMarker("wss://relay.damus.io", read = true, write = true),
        )
        val result = makeResolver().mergeAdditive(user)

        // user entries come first in order, then defaults not already in user list
        assertEquals("wss://user-a.example.com", result[0].url)
        assertEquals(RelaySource.USER_NIP65, result[0].source)
        assertEquals("wss://relay.damus.io", result[1].url)
        assertEquals(RelaySource.USER_NIP65, result[1].source)

        // remaining defaults (nos.lol, primal) come after, tagged DEFAULT
        val defaultUrls = result.drop(2).map { it.url }.toSet()
        assertTrue("nos.lol present" + " in " + defaultUrls, defaultUrls.contains("wss://nos.lol"))
        assertTrue("primal present", defaultUrls.contains("wss://relay.primal.net"))
        result.drop(2).forEach { assertEquals(RelaySource.DEFAULT, it.source) }
    }

    @Test
    fun `empty user list yields defaults only with DEFAULT source`() {
        val result = makeResolver().mergeAdditive(emptyList())

        assertEquals(NostrConfig.DEFAULT_RELAYS.size, result.size)
        result.forEach { assertEquals(RelaySource.DEFAULT, it.source) }
        val urls = result.map { it.url }.toSet()
        assertTrue(urls.containsAll(NostrConfig.DEFAULT_RELAYS.map { it.url }))
    }

    @Test
    fun `defaults are always appended even if user list is non-empty`() {
        val user = listOf(
            Kind10002Event.RelayMarker("wss://only-user.example.com", read = true, write = true),
        )
        val result = makeResolver().mergeAdditive(user)

        val urls = result.map { it.url }.toSet()
        assertTrue(urls.contains("wss://only-user.example.com"))
        // All three defaults must still be present
        NostrConfig.DEFAULT_RELAYS.forEach { default ->
            assertTrue("default ${default.url} missing", urls.contains(default.url))
        }
    }

    @Test
    fun `user marker wins over default when URL overlaps`() {
        // User says damus is write-only; defaults list it as bidirectional.
        val user = listOf(
            Kind10002Event.RelayMarker("wss://relay.damus.io", read = false, write = true),
        )
        val result = makeResolver().mergeAdditive(user)

        val damus = result.first { it.url == "wss://relay.damus.io" }
        assertFalse("user said read=false", damus.read)
        assertTrue("user said write=true", damus.write)
        assertEquals(RelaySource.USER_NIP65, damus.source)
    }

    @Test
    fun `user list order is preserved`() {
        val user = listOf(
            Kind10002Event.RelayMarker("wss://z.example.com", read = true, write = true),
            Kind10002Event.RelayMarker("wss://a.example.com", read = true, write = true),
            Kind10002Event.RelayMarker("wss://m.example.com", read = true, write = true),
        )
        val result = makeResolver().mergeAdditive(user)

        assertEquals("wss://z.example.com", result[0].url)
        assertEquals("wss://a.example.com", result[1].url)
        assertEquals("wss://m.example.com", result[2].url)
    }
}
