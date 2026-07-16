package com.cruxcoach.android.nostr

import kotlin.test.Test
import kotlin.test.assertEquals

class DevicePrivacyTest {
    @Test
    fun `consented diagnostic line includes app version without exact device model`() {
        assertEquals(
            "Fork Board 1.2.3 (45) | Android API 36 | mid-range | de | " +
                "memory-pressure=occasional",
            formatGeneralizedDeviceInfoLine(
                36,
                "mid-range",
                "de",
                "1.2.3",
                45,
                "occasional",
                "Fork Board",
            ),
        )
    }

    @Test
    fun `memory pressure count is exported only as a coarse bucket`() {
        assertEquals("none", memoryPressureBucket(0))
        assertEquals("occasional", memoryPressureBucket(3))
        assertEquals("frequent", memoryPressureBucket(4))
    }
}
