package com.cruxcoach.android.nostr.relaydiscovery

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.android.nostr.model.RelaySource
import com.cruxcoach.android.nostr.model.ResolvedRelayList
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RelayListResolverTest {

    private fun resolvedList(
        count: Int = 2,
        hasUserList: Boolean = true,
        resolvedAt: Long = 0L,
    ): ResolvedRelayList = ResolvedRelayList(
        relays = List(count) {
            RelayConfig(
                url = "wss://relay-$it.example.com",
                read = true,
                write = true,
                source = if (hasUserList) RelaySource.USER_NIP65 else RelaySource.DEFAULT,
            )
        },
        resolvedAtEpochMs = resolvedAt,
        hasUserList = hasUserList,
    )

    private data class Fixture(
        val fetcher: Nip65RelayListFetcher,
        val cache: RelayListCache,
        val pool: NostrRelayPool,
        val prefs: UserPreferences,
        val resolver: RelayListResolver,
    )

    /**
     * Builds the resolver wired against mocked [fetcher]/[cache]/[prefs] and a
     * real [NostrRelayPool] (its `onRelaysChanged` is pure state; no network).
     *
     * Pass a scope that is cancelled when the test ends so no background
     * coroutine outlives it — `runTest { backgroundScope }` does this
     * automatically.
     */
    private fun makeFixture(
        testScope: TestScope,
        flagEnabled: Boolean = true,
        hasKey: Boolean = true,
        cacheRead: ResolvedRelayList? = null,
        cacheStale: Boolean = true,
        fetchResult: Kind10002Event? = null,
    ): Fixture {
        // Use the TestScope itself as the resolver's app-scope so any
        // launch/async the resolver kicks off lands on the test's scheduler
        // and is advanced by advanceUntilIdle().
        val scope: CoroutineScope = testScope
        val fetcher = mockk<Nip65RelayListFetcher>()
        val cache = mockk<RelayListCache>(relaxed = true)
        val prefs = mockk<UserPreferences>()
        val pool = NostrRelayPool(OkHttpClient())
        val pubkeyProvider = RelayListResolver.PubkeyProvider {
            if (hasKey) FAKE_PUBKEY else null
        }

        coEvery { prefs.isNip65DiscoveryEnabled() } returns flagEnabled
        coEvery { cache.read() } returns cacheRead
        coEvery { cache.isStale(any()) } returns cacheStale
        coEvery { fetcher.fetch(any(), any(), any()) } returns fetchResult

        val resolver = RelayListResolver(
            fetcher = fetcher,
            cache = cache,
            pool = pool,
            pubkeyProvider = pubkeyProvider,
            userPreferences = prefs,
            appScope = scope,
            clock = { 1_000L },
        )
        return Fixture(fetcher, cache, pool, prefs, resolver)
    }

    @Test
    fun `kill-switch off short-circuits to defaults and notifies pool`() = runTest {
        val f = makeFixture(this, flagEnabled = false)

        val result = f.resolver.current()

        assertEquals(NostrConfig.DEFAULT_RELAYS.size, result.relays.size)
        assertEquals(false, result.hasUserList)
        coVerify(exactly = 0) { f.fetcher.fetch(any(), any(), any()) }
        assertEquals(
            NostrConfig.DEFAULT_RELAYS.filter { it.write }.map { it.url }.toSet(),
            f.pool.writeRelays().map { it.url }.toSet(),
        )
    }

    @Test
    fun `cache fresh returns cached list and skips fetch`() = runTest {
        val cached = resolvedList(count = 4, hasUserList = true)
        val f = makeFixture(this, cacheRead = cached, cacheStale = false)

        val result = f.resolver.current()

        assertEquals(cached, result)
        coVerify(exactly = 0) { f.fetcher.fetch(any(), any(), any()) }
        assertEquals(
            cached.relays.map { it.url }.toSet(),
            f.pool.writeRelays().map { it.url }.toSet(),
        )
    }

    @Test
    fun `cache miss + no key returns defaults and does not fetch`() = runTest {
        val f = makeFixture(this, cacheRead = null, hasKey = false)

        val result = f.resolver.current()

        assertEquals(false, result.hasUserList)
        assertEquals(NostrConfig.DEFAULT_RELAYS.size, result.relays.size)
        coVerify(exactly = 0) { f.fetcher.fetch(any(), any(), any()) }
    }

    @Test
    fun `cache miss + key triggers fetch and caches the result`() = runTest {
        val event = Kind10002Event(
            pubkey = FAKE_PUBKEY,
            createdAt = 100L,
            relays = listOf(
                Kind10002Event.RelayMarker("wss://user-a.example.com", read = true, write = true),
            ),
        )
        val f = makeFixture(this, cacheRead = null, hasKey = true, fetchResult = event)

        val result = f.resolver.current()
        advanceUntilIdle()

        assertTrue("has user list", result.hasUserList)
        val urls = result.relays.map { it.url }
        assertTrue("user relay present", urls.contains("wss://user-a.example.com"))
        coVerify(exactly = 1) { f.fetcher.fetch(any(), any(), any()) }
        coVerify(exactly = 1) { f.cache.write(any()) }
        assertTrue(
            "pool received resolved relays",
            f.pool.writeRelays().any { it.url == "wss://user-a.example.com" },
        )
    }

    @Test
    fun `fetcher returns null yields defaults only`() = runTest {
        val f = makeFixture(this, cacheRead = null, hasKey = true, fetchResult = null)

        val result = f.resolver.current()
        advanceUntilIdle()

        assertEquals(false, result.hasUserList)
        assertEquals(NostrConfig.DEFAULT_RELAYS.size, result.relays.size)
        coVerify(exactly = 1) { f.fetcher.fetch(any(), any(), any()) }
        coVerify(exactly = 1) { f.cache.write(any()) }
    }

    @Test
    fun `invalidate clears cache`() = runTest {
        val f = makeFixture(this)
        f.resolver.invalidate()
        coVerify(exactly = 1) { f.cache.clear() }
    }

    @Test
    fun `onKeyChanged with flag off reverts to defaults without fetching`() = runTest {
        val f = makeFixture(this, flagEnabled = false, cacheRead = null, hasKey = true)

        f.resolver.onKeyChanged()
        advanceUntilIdle()

        coVerify(exactly = 1) { f.cache.clear() }
        coVerify(exactly = 0) { f.fetcher.fetch(any(), any(), any()) }
        assertEquals(
            NostrConfig.DEFAULT_RELAYS.map { it.url }.toSet(),
            f.pool.writeRelays().map { it.url }.toSet(),
        )
    }

    @Test
    fun `identical relay set does not trigger duplicate pool notification`() = runTest {
        val cached = resolvedList()
        val f = makeFixture(this, cacheRead = cached, cacheStale = false)

        f.resolver.current()
        val poolStateAfterFirst = f.pool.writeRelays().map { it.url }
        f.resolver.current()
        val poolStateAfterSecond = f.pool.writeRelays().map { it.url }
        assertEquals(poolStateAfterFirst, poolStateAfterSecond)
    }

    companion object {
        private const val FAKE_PUBKEY =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef"
    }
}
