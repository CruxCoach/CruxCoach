package com.cruxcoach.android.data

import com.cruxcoach.android.ble.SessionCommand
import com.cruxcoach.android.boardcell.BoardPlaylistEntry
import com.cruxcoach.android.boardcell.BoardPlaylistState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlaylistCommandRebaserTest {
    private val a = BoardPlaylistEntry("owner-a", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 40)
    private val b = BoardPlaylistEntry("owner-b", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 40)
    private val c = BoardPlaylistEntry("owner-c", "cccccccccccccccccccccccccccccccc", 40)
    private val d = BoardPlaylistEntry("owner-d", "dddddddddddddddddddddddddddddddd", 40)

    @Test fun `concurrent adds are both safe`() {
        val base = BoardPlaylistState(7, 0, listOf(a, b))
        val command = SessionCommand.Add(c.climbUuid, c.angle)
        val context = PlaylistCommandRebaser.context(command, base)
        val current = base.copy(items = base.items + d)

        assertEquals(command, assertIs<PlaylistCommandRebaser.Result.Apply>(
            PlaylistCommandRebaser.rebase(command, context, current, false)).command)
    }

    @Test fun `remove follows its item across unrelated reorder`() {
        val base = BoardPlaylistState(7, 0, listOf(a, b, c, d))
        val command = SessionCommand.Remove(3)
        val context = PlaylistCommandRebaser.context(command, base)
        val current = base.copy(items = listOf(b, a, c, d))

        assertEquals(SessionCommand.Remove(3), assertIs<PlaylistCommandRebaser.Result.Apply>(
            PlaylistCommandRebaser.rebase(command, context, current, false)).command)
    }

    @Test fun `move rebases when destination anchors are unchanged`() {
        val base = BoardPlaylistState(7, 0, listOf(a, b, c, d))
        val command = SessionCommand.Move(0, 2) // b,c remain the destination edge
        val context = PlaylistCommandRebaser.context(command, base)
        val current = base.copy(items = listOf(a, c, d))

        assertEquals(SessionCommand.Move(0, 1), assertIs<PlaylistCommandRebaser.Result.Apply>(
            PlaylistCommandRebaser.rebase(command, context, current, false)).command)
    }

    @Test fun `next conflicts after another participant advances`() {
        val base = BoardPlaylistState(7, 0, listOf(a, b, c))
        val command = SessionCommand.Next
        val context = PlaylistCommandRebaser.context(command, base)
        val current = base.copy(currentIndex = 1)

        assertIs<PlaylistCommandRebaser.Result.Conflict>(
            PlaylistCommandRebaser.rebase(command, context, current, false))
    }

    @Test fun `resend follows the same current climb but rejects an advance`() {
        val base = BoardPlaylistState(7, 0, listOf(a, b, c))
        val command = SessionCommand.Resend
        val context = PlaylistCommandRebaser.context(command, base)

        assertEquals(command, assertIs<PlaylistCommandRebaser.Result.Apply>(
            PlaylistCommandRebaser.rebase(command, context, base.copy(items = listOf(a, c)), false)).command)
        assertIs<PlaylistCommandRebaser.Result.Conflict>(
            PlaylistCommandRebaser.rebase(command, context, base.copy(currentIndex = 1), false))
    }

    @Test fun `duplicate count changes are treated as ambiguous`() {
        val base = BoardPlaylistState(7, 0, listOf(a, b, a))
        val command = SessionCommand.Remove(2)
        val context = PlaylistCommandRebaser.context(command, base)
        val current = base.copy(items = listOf(a, b, a, a))

        assertIs<PlaylistCommandRebaser.Result.Conflict>(
            PlaylistCommandRebaser.rebase(command, context, current, false))
    }
}
