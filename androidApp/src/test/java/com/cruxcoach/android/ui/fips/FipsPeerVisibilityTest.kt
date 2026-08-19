package com.cruxcoach.android.ui.fips

import com.cruxcoach.android.fips.FipsPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class FipsPeerVisibilityTest {
    private val departed = FipsPeer("departed", true, "ble", 1)
    private val member = FipsPeer("member", true, "ble", 2)
    private val disconnected = FipsPeer("disconnected", false, "ble", 3)

    @Test fun `canonical leave hides a still cached connected peer immediately`() {
        assertEquals(
            listOf(member),
            visibleCanonicalPeers(
                listOf(departed, member, disconnected),
                setOf("member"),
            ),
        )
    }

    @Test fun `canonical rejoin makes the cached peer visible again`() {
        assertEquals(
            listOf(departed, member),
            visibleCanonicalPeers(
                listOf(departed, member),
                setOf("departed", "member"),
            ),
        )
    }

    @Test fun `transport diagnostics remain available before a cell snapshot exists`() {
        assertEquals(
            listOf(departed),
            visibleCanonicalPeers(listOf(departed, disconnected), null),
        )
    }
}
