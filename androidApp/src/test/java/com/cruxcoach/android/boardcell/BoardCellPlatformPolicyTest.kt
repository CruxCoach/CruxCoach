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

    /**
     * API 28 has no FIPS identity, so a canonical start could never have
     * succeeded. Answering "yes" here and failing inside the start left the
     * user with an empty started session instead of the legacy GATT joinable
     * path, which still works perfectly well on that platform.
     */
    @Test fun `api 28 never starts a canonical playlist, whatever the cell says`() {
        assertFalse(BoardCellPlatformPolicy.canStartCanonicalPlaylist(
            apiLevel = 28, cellIsActive = true, localIsCellMember = true))
    }

    @Test fun `api 29 starts one only inside an active cell it belongs to`() {
        assertTrue(BoardCellPlatformPolicy.canStartCanonicalPlaylist(
            apiLevel = 29, cellIsActive = true, localIsCellMember = true))
        assertFalse(BoardCellPlatformPolicy.canStartCanonicalPlaylist(
            apiLevel = 29, cellIsActive = false, localIsCellMember = true))
        assertFalse(BoardCellPlatformPolicy.canStartCanonicalPlaylist(
            apiLevel = 29, cellIsActive = true, localIsCellMember = false))
    }
}
