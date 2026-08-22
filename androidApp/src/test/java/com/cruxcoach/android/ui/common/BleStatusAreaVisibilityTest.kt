package com.cruxcoach.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class BleStatusAreaVisibilityTest {
    @Test fun `occupied summary disappears when expanded discovery is empty`() {
        assertEquals(0, visibleBoardOccupiedCount(rawCount = 2, visibleDiscoveryExists = false))
    }

    @Test fun `occupied summary remains when discovery can explain it`() {
        assertEquals(2, visibleBoardOccupiedCount(rawCount = 2, visibleDiscoveryExists = true))
    }
}
