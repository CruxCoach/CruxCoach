package com.cruxcoach.android.ui.fips

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the mesh status strip offers to join the BoardCell's playlist.
 *
 * The rule this encodes is the one the product is built on: being in the mesh
 * makes the playlist *visible*, and only an explicit tap makes you a member.
 * Before this existed the join path had no call site at all — a member could
 * see the cell but had no way to discover, let alone take part in, the
 * playlist running on it.
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

    @Test fun `a cell member outside the playlist is offered the join`() {
        assertTrue(state(playlist = playlist()).canJoinPlaylist)
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

    @Test fun `discoverability alone says nothing about membership`() {
        // The two are deliberately separate fields: a member count of five
        // with localIsMember false is exactly the state the join button is
        // for, and it must never be read as "you are already in it".
        val visible = playlist(memberCount = 5, localIsMember = false)
        assertTrue(visible.offersJoin)
        assertFalse(visible.localIsMember)
        assertFalse(visible.localIsHost)
    }
}
