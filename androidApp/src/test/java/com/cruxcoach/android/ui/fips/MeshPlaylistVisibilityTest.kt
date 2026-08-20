package com.cruxcoach.android.ui.fips

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The mesh status strip never offers a second playlist join.
 *
 * Board membership is playlist membership. Keeping a separate join action
 * would recreate the hidden membership layer the Board UX removed.
 */
class MeshPlaylistVisibilityTest {

    private fun state(
        availability: String? = "ACTIVE",
        playlist: MeshPlaylistUi? = null,
    ) = FipsMeshUiState(cellId = "cell", availability = availability, playlist = playlist)

    private fun playlist(
        itemCount: Int = 3,
        memberCount: Int = 1,
        localIsMember: Boolean = false,
        localIsHost: Boolean = false,
    ) = MeshPlaylistUi(itemCount, memberCount, localIsMember, localIsHost)

    @Test fun `a Board playlist never offers a second join`() {
        assertFalse(state(playlist = playlist()).canJoinPlaylist)
    }

    @Test fun `a playlist member is not offered a second join`() {
        assertFalse(state(playlist = playlist(localIsMember = true)).canJoinPlaylist)
        assertFalse(state(playlist = playlist(localIsMember = true, localIsHost = true))
            .canJoinPlaylist)
    }

    @Test fun `no running playlist means nothing to join`() {
        assertFalse(state(playlist = null).canJoinPlaylist)
        // A playlist whose queue is empty is not something to walk up to.
        assertFalse(state(playlist = playlist(itemCount = 0)).canJoinPlaylist)
    }

    @Test fun `a cell that is not active does not offer a join that would fail`() {
        assertFalse(state(availability = "FROZEN_NEEDS_CONTROLLER", playlist = playlist())
            .canJoinPlaylist)
        assertFalse(state(availability = null, playlist = playlist()).canJoinPlaylist)
    }

    @Test fun `even a stale non-member snapshot cannot expose the retired join`() {
        val visible = playlist(memberCount = 5, localIsMember = false)
        assertFalse(visible.offersJoin)
        assertFalse(visible.localIsMember)
        assertFalse(visible.localIsHost)
    }
}
