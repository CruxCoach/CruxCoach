package com.cruxcoach.android.nostr.relaydiscovery

import com.cruxcoach.android.nostr.NostrConfig
import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.android.nostr.model.RelaySource
import com.cruxcoach.android.nostr.model.ResolvedRelayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The default relays that stopped taking NIP-17 gift wraps (wellorder, oxtr) are gone from
 * the defaults, and a relay list an install saved with them gets the current defaults instead,
 * while the relays of the user's own list stay.
 */
class RetiredDefaultRelaysTest {

    private val wellorder = "wss://nostr-pub.wellorder.net"
    private val oxtr = "wss://nostr.oxtr.dev"

    private fun default(url: String) = RelayConfig(url, source = RelaySource.DEFAULT)

    private fun user(url: String, read: Boolean = true, write: Boolean = true) =
        RelayConfig(url, read = read, write = write, source = RelaySource.USER_NIP65)

    /** A list as 0.2.4 saved it: the user's relays first, then the defaults of that build. */
    private fun savedBy024(vararg userRelays: RelayConfig) = ResolvedRelayList(
        relays = userRelays.toList() + listOf(
            "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", wellorder, oxtr,
        ).filter { url -> userRelays.none { it.url == url } }.map(::default),
        resolvedAtEpochMs = 1_000L,
        hasUserList = userRelays.isNotEmpty(),
    )

    @Test
    fun `the defaults take nostr mom and purplerelay instead of wellorder and oxtr`() {
        val urls = NostrConfig.DEFAULT_RELAYS.map { it.url }

        assertEquals(
            listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://purplerelay.com"),
            urls,
        )
        NostrConfig.DEFAULT_RELAYS.forEach { assertTrue(it.url, it.read && it.write) }
    }

    @Test
    fun `a saved list without a user list gets the current defaults`() {
        val fixed = savedBy024().withCurrentDefaults()

        assertEquals(NostrConfig.DEFAULT_RELAYS.map { it.url }, fixed.relays.map { it.url })
        fixed.relays.forEach { assertEquals(RelaySource.DEFAULT, it.source) }
        assertEquals(1_000L, fixed.resolvedAtEpochMs)
        assertFalse(fixed.hasUserList)
    }

    @Test
    fun `relays the user listed stay, also a retired default and its markers`() {
        val saved = savedBy024(
            user("wss://my.relay.example.com"),
            user(wellorder, read = false, write = true),
        )

        val fixed = saved.withCurrentDefaults()

        assertEquals(
            listOf(
                "wss://my.relay.example.com", wellorder,
                "wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://nostr.mom", "wss://purplerelay.com",
            ),
            fixed.relays.map { it.url },
        )
        val keptWellorder = fixed.relays.single { it.url == wellorder }
        assertEquals(user(wellorder, read = false, write = true), keptWellorder)
        assertFalse(fixed.relays.any { it.url == oxtr })
        assertTrue(fixed.hasUserList)
    }

    @Test
    fun `a default the user also listed keeps the user's markers`() {
        val saved = savedBy024(user("wss://nos.lol", read = true, write = false))

        val fixed = saved.withCurrentDefaults()

        assertEquals(user("wss://nos.lol", read = true, write = false), fixed.relays.single { it.url == "wss://nos.lol" })
        assertEquals(NostrConfig.DEFAULT_RELAYS.size, fixed.relays.size)
    }

    @Test
    fun `a list with the current defaults comes back unchanged`() {
        val current = ResolvedRelayList(
            relays = listOf(user("wss://my.relay.example.com")) + NostrConfig.DEFAULT_RELAYS,
            resolvedAtEpochMs = 1_000L,
            hasUserList = true,
        )

        assertSame(current, current.withCurrentDefaults())
    }
}
