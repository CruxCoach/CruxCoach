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
     * API 28 has no FIPS identity, so it cannot be a cell member and cannot
     * get a command to a controller under its own name. Answering "yes" here
     * and failing later left the user with an empty started session; it takes
     * part as a GATT leaf of a gateway instead.
     */
    @Test fun `api 28 never takes part in the shared playlist, whatever the cell says`() {
        assertFalse(BoardCellPlatformPolicy.participatesInSharedPlaylist(
            apiLevel = 28, cellIsActive = true, localIsCellMember = true))
    }

    @Test fun `api 29 takes part exactly while it is in an active cell`() {
        assertTrue(BoardCellPlatformPolicy.participatesInSharedPlaylist(
            apiLevel = 29, cellIsActive = true, localIsCellMember = true))
        assertFalse(BoardCellPlatformPolicy.participatesInSharedPlaylist(
            apiLevel = 29, cellIsActive = false, localIsCellMember = true))
        assertFalse(BoardCellPlatformPolicy.participatesInSharedPlaylist(
            apiLevel = 29, cellIsActive = true, localIsCellMember = false))
    }

    @Test fun `shared playlists use the same hard platform boundary as FIPS`() {
        assertFalse(BoardCellPlatformPolicy.sharedPlaylistAvailable(28))
        assertTrue(BoardCellPlatformPolicy.sharedPlaylistAvailable(29))
    }

    @Test fun `legacy GATT playlists are retired on every platform`() {
        assertFalse(BoardCellPlatformPolicy.legacyGattPlaylistAvailable(28))
        assertFalse(BoardCellPlatformPolicy.legacyGattPlaylistAvailable(29))
        assertFalse(BoardCellPlatformPolicy.legacyGattPlaylistAvailable(35))
    }
}
