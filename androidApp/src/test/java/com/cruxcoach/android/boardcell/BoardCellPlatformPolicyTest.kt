package com.cruxcoach.android.boardcell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCellPlatformPolicyTest {
    @Test fun `api 28 disables mesh but never the BoardCell safety boundary`() {
        assertFalse(BoardCellPlatformPolicy.meshAvailable(28))
        assertTrue(BoardCellPlatformPolicy.requiresSafetyBoundary(28))
        assertTrue(BoardCellPlatformPolicy.meshAvailable(29))
        assertTrue(BoardCellPlatformPolicy.requiresSafetyBoundary(29))
    }
}
