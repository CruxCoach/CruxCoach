package com.cruxcoach.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestRelayConfigTest {
    @Test
    fun `0_2_2 reads the operator controlled manifest relay`() {
        assertTrue(
            NostrConfig.MANIFEST_RELAYS.contains(
                "wss://blossom.cruxcoach.org/nostr",
            ),
        )
        assertEquals(6, NostrConfig.MANIFEST_RELAYS.distinct().size)
    }
}
