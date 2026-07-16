package com.cruxcoach.android.nostr

import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.android.nostr.model.RelaySource
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Contract-only tests for the FEAT-001 pool surface: writeRelays, readRelays,
 * onRelaysChanged. No network, no coroutines — just verifies the `@Volatile`
 * snapshot semantics.
 */
class NostrRelayPoolContractTest {

    private fun pool(): NostrRelayPool = NostrRelayPool(OkHttpClient())

    @Test
    fun `initial writeRelays mirror DEFAULT_RELAYS filtered by write`() {
        val write = pool().writeRelays().map { it.url }.toSet()
        val expected = NostrConfig.DEFAULT_RELAYS.filter { it.write }.map { it.url }.toSet()
        assertEquals(expected, write)
    }

    @Test
    fun `initial readRelays mirror DEFAULT_RELAYS filtered by read`() {
        val read = pool().readRelays().map { it.url }.toSet()
        val expected = NostrConfig.DEFAULT_RELAYS.filter { it.read }.map { it.url }.toSet()
        assertEquals(expected, read)
    }

    @Test
    fun `onRelaysChanged updates the snapshot`() {
        val p = pool()
        val newList = listOf(
            RelayConfig("wss://a.example.com", read = true, write = true, source = RelaySource.USER_NIP65),
            RelayConfig("wss://b.example.com", read = false, write = true, source = RelaySource.USER_NIP65),
            RelayConfig("wss://c.example.com", read = true, write = false, source = RelaySource.DEFAULT),
        )
        p.onRelaysChanged(newList)

        assertEquals(setOf("wss://a.example.com", "wss://b.example.com"), p.writeRelays().map { it.url }.toSet())
        assertEquals(setOf("wss://a.example.com", "wss://c.example.com"), p.readRelays().map { it.url }.toSet())
    }

    @Test
    fun `onRelaysChanged empty list is a no-op and keeps previous state`() {
        val p = pool()
        val newList = listOf(
            RelayConfig("wss://unique.example.com", read = true, write = true, source = RelaySource.USER_NIP65),
        )
        p.onRelaysChanged(newList)
        assertTrue(p.writeRelays().any { it.url == "wss://unique.example.com" })

        p.onRelaysChanged(emptyList())
        // Previous state still present
        assertTrue(p.writeRelays().any { it.url == "wss://unique.example.com" })
    }

    @Test
    fun `write and read filters reflect marker correctly`() {
        val p = pool()
        p.onRelaysChanged(
            listOf(
                RelayConfig("wss://read-only.example.com", read = true, write = false),
                RelayConfig("wss://write-only.example.com", read = false, write = true),
                RelayConfig("wss://both.example.com", read = true, write = true),
            ),
        )
        assertEquals(
            setOf("wss://write-only.example.com", "wss://both.example.com"),
            p.writeRelays().map { it.url }.toSet(),
        )
        assertEquals(
            setOf("wss://read-only.example.com", "wss://both.example.com"),
            p.readRelays().map { it.url }.toSet(),
        )
    }

    @Test
    fun `publish selection skips an open circuit when another relay is available`() {
        val relays = listOf(RelayConfig("wss://down.example"), RelayConfig("wss://up.example"))

        val selected = selectPublishRelays(relays) { it.url.contains("down") }

        assertEquals(listOf("wss://up.example"), selected.map { it.url })
    }

    @Test
    fun `publish selection still attempts all relays when every circuit is open`() {
        val relays = listOf(RelayConfig("wss://a.example"), RelayConfig("wss://b.example"))

        assertEquals(relays, selectPublishRelays(relays) { true })
    }

    @Test
    fun `equal jitter stays bounded and varies with draw`() {
        assertEquals(5_000L, equalJitterDelay(10_000L) { 0L })
        assertEquals(10_000L, equalJitterDelay(10_000L) { upper -> upper - 1L })
        assertFalse(equalJitterDelay(10_000L) { 1L } == equalJitterDelay(10_000L) { 4_000L })
    }
}
