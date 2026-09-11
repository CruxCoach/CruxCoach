package com.cruxcoach.android.sharing

import android.content.SharedPreferences
import io.mockk.*
import kotlin.test.*

class MarmotRelayConfigurationTest {
    @Test fun only_a_never_configured_account_receives_the_six_defaults() {
        assertEquals(6, MarmotRelayDefaults.fromStored(null).size)
        assertEquals(MarmotRelayDefaults.urls, MarmotRelayDefaults.fromStored(null))
        assertEquals(listOf("wss://example.org/private"), MarmotRelayDefaults.fromStored("[\"wss://example.org/private\"]"))
    }

    @Test fun malformed_or_unknown_config_never_falls_back_to_public_relays() {
        for (raw in listOf("", "broken", "{}", "null", "[]", "[true]", "[\"ws://example.org\"]", "[\"wss://user:pass@example.org\"]", "[\"wss://example.org?token=synthetic\"]")) {
            assertTrue(MarmotRelayDefaults.fromStored(raw).isEmpty())
        }
    }

    @Test fun oversized_and_duplicate_pools_fail_closed() {
        assertFails { MarmotRelayDefaults.validate(List(17) { "wss://relay$it.example" }) }
        assertFails { MarmotRelayDefaults.validate(listOf("wss://example.org", "wss://example.org")) }
        assertFails { MarmotRelayDefaults.validate(listOf("wss://example.org/" + "x".repeat(512))) }
    }

    @Test fun stored_configuration_blocks_the_live_port_before_key_access() {
        val preferences = mockk<SharedPreferences>()
        val account = "a".repeat(64)
        val configuration = MarmotRelayConfiguration(preferences, account)
        var keyReads = 0
        every { preferences.getString("relays:$account", null) } returns "corrupt synthetic configuration"
        val port = LiveMarmotSnapshotPort(account, { null }, { account },
            authorised = { configuration.read().isNotEmpty() }, nativeLoaded = { MarmotNative.loaded },
            open = { keyReads++; error("key access must remain blocked") },
            invoke = { _, _ -> error("native commands must remain blocked") }, close = {},
            accountSign = { _, _, _, _ -> error("signing must remain blocked") },
            deviceSign = { error("signing must remain blocked") }, verify = Bip340Verifier { _, _, _ -> false })
        assertTrue(configuration.read().isEmpty())
        assertTrue(port.nativeAvailable, "native library must be present; configuration is the refusal cause")
        assertNull(port.withSession("b".repeat(64)) { it })
        every { preferences.getString("relays:$account", null) } throws ClassCastException("synthetic old preference type")
        assertTrue(configuration.read().isEmpty())
        assertNull(port.withSession("b".repeat(64)) { it })
        assertEquals(0, keyReads)
    }
}
