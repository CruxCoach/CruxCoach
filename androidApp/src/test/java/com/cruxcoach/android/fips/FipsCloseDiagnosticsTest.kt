package com.cruxcoach.android.fips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FipsCloseDiagnosticsTest {
    @Test fun `maps a closed BLE address to its authenticated peer`() {
        val snapshot = """peers	{"peers":[{"npub":"npub-one","transport_addr":"ble0/AA:BB:CC:DD:EE:01"},{"npub":"npub-two","transport_addr":"ble0/AA:BB:CC:DD:EE:02"}]}"""

        assertEquals("npub-two", peerAtBleAddress(snapshot, "aa:bb:cc:dd:ee:02"))
        assertNull(peerAtBleAddress(snapshot, "AA:BB:CC:DD:EE:03"))
        assertNull(peerAtBleAddress("peers\tnot-json", "AA:BB:CC:DD:EE:01"))
    }
}
