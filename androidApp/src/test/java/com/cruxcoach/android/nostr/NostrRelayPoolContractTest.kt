package com.cruxcoach.android.nostr

import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.android.nostr.model.RelaySource
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract-only tests for the FEAT-001 pool surface: writeRelays, readRelays,
 * onRelaysChanged. No network, no coroutines — just verifies the `@Volatile`
 * snapshot semantics.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
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
    fun `forged event cannot suppress authentic legacy event or another subscriber`() {
        val valid = """{"pubkey":"79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798","created_at":1788600000,"kind":1,"tags":[],"content":"legacy signed event","id":"05d4c98d95453b5ab2968f82e7372285e86140874108f8bfe55f0fd918326d51","sig":"890b4223e4126feab0731d2fe295e0f867ab103ec57dc83c666988fda003af19901dcffb1ffc1ffb22e3d23744401b62e0642714cdb46c7dc0e5b4800f45d07f"}"""
        // Quartz's Android artifact is Java 21 bytecode; CI uses JVM 17.
        // Exercise the delivery/authentication boundary with a deterministic
        // authenticator, not a mock event ID parsed from untrusted input.
        var checks = 0
        val authenticate: (String) -> String? = {
            checks++
            if (it == valid) "05d4c98d95453b5ab2968f82e7372285e86140874108f8bfe55f0fd918326d51" else null
        }
        val forged = valid.replace("legacy signed event", "forged body")
        val gate = VerifiedEventDeliveryGate(authenticate)
        assertFalse(gate.accepts(forged))
        assertFalse(gate.accepts(forged, skipDedup = true))
        assertTrue(gate.accepts(valid))
        assertFalse(gate.accepts(valid))
        assertTrue(gate.accepts(valid, skipDedup = true))
        assertTrue(VerifiedEventDeliveryGate(authenticate).accepts(valid))
        assertEquals(6, checks)
    }

    @Test
    fun `bounds oversized unicode and deeply nested frames before parsing`() {
        assertFalse(RelayInputGuard.accepts("x".repeat(RelayInputGuard.MAX_BYTES + 1)))
        assertFalse(RelayInputGuard.accepts("\"" + "ä".repeat(RelayInputGuard.MAX_BYTES / 2) + "\""))
        assertFalse(RelayInputGuard.accepts("[".repeat(33) + "]".repeat(33)))
        assertTrue(RelayInputGuard.accepts("""["NOTICE","[brackets in text]"]"""))
    }
}
