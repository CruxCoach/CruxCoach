package com.cruxcoach.android.boardcell

import org.junit.Assert.*
import org.junit.Test

class BoardCellReplicaTest {
    private fun initial() = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"),
        epoch = 7, sequence = 0, controllerId = "controller", lineageId = "lineage",
        members = setOf("controller", "member")).withComputedHash()

    @Test fun `sequence gap freezes until full snapshot`() {
        val replica = BoardCellReplica("member", initial())
        val current = replica.snapshot!!
        val event = BoardCellEvent.ProjectCommitted(BoardProjection("later", 40), "command")
        val fakeNext = BoardCellReplica.reduce(current, event, 2)
        val result = replica.applyEvent(BoardCellEnvelope(current.cellId, current.physicalBoardId,
            current.epoch, current.controllerTerm, 2, current.stateHash, event, fakeNext.stateHash))
        assertTrue(result is BoardCellApplyResult.NeedSnapshot)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT, replica.snapshot?.availability)
        val recovered = fakeNext.copy(members = setOf("controller", "member")).withComputedHash()
        assertTrue(replica.applySnapshot(recovered) is BoardCellApplyResult.Applied)
        assertEquals("later", replica.snapshot?.projection?.climbUuid)
    }

    @Test fun `wrong cell is rejected but newer exclusion snapshot retires stale member`() {
        val replica = BoardCellReplica("member", initial())
        val foreign = initial().copy(cellId = BoardCellId("other")).withComputedHash()
        assertTrue(replica.applySnapshot(foreign) is BoardCellApplyResult.Rejected)
        val excluded = initial().copy(sequence = 1, members = setOf("controller")).withComputedHash()
        assertTrue(replica.applySnapshot(excluded) is BoardCellApplyResult.Applied)
        assertFalse("member" in replica.snapshot!!.members)
        assertTrue(BoardCellReplica("member").applySnapshot(excluded) is BoardCellApplyResult.Rejected)
    }

    @Test fun `legacy v4 hash migrates only before live membership revision advances`() {
        val base = initial().copy(stateHash = "")
        assertTrue(base.copy(stateHash = BoardCellHash.computeLegacyV4(base)).hasValidHash())

        val joined = BoardCellReplica.reduce(initial(), BoardCellEvent.MemberJoined("new-member"), 1)
        val withoutRevision = joined.copy(membershipRevision = 0, stateHash = "")
        val forgedLegacy = joined.copy(stateHash = BoardCellHash.computeLegacyV4(withoutRevision))
        assertFalse(forgedLegacy.hasValidHash())
    }

    @Test fun `legacy v6 snapshot is open while changed join mode requires v7 hash`() {
        val base = initial().copy(stateHash = "")
        assertTrue(base.copy(stateHash = BoardCellHash.computeLegacyV6(base)).hasValidHash())

        val approval = base.copy(joinMode = BoardJoinMode.APPROVAL_REQUIRED)
        assertFalse(approval.copy(stateHash = BoardCellHash.computeLegacyV6(approval)).hasValidHash())
        assertFalse(approval.copy(stateHash = BoardCellHash.computeLegacyV5(approval)).hasValidHash())
        val changed = BoardCellReplica.reduce(
            initial(),
            BoardCellEvent.JoinModeChanged(BoardJoinMode.APPROVAL_REQUIRED),
            1,
        )
        assertEquals(BoardJoinMode.APPROVAL_REQUIRED, changed.joinMode)
        assertTrue(changed.hasValidHash())
    }

    /**
     * Joining a board is joining its playlist, and there is nothing to record:
     * the playlist has no membership of its own, so the snapshot the new
     * member receives already *is* their participation.
     */
    @Test fun `joining or leaving the board never changes the shared playlist`() {
        val playlist = BoardPlaylistPolicy.apply(BoardPlaylistState(sessionId = 7),
            listOf(BoardPlaylistOp.Add("e1", "climb", 40)))
        val current = initial().copy(playlist = playlist).withComputedHash()

        val joined = BoardCellReplica.reduce(current, BoardCellEvent.MemberJoined("new-member"), 1)
        val left = BoardCellReplica.reduce(joined, BoardCellEvent.MemberLeft(
            "new-member", BoardCellMemberLeaveReason.VOLUNTARY), 2)

        assertTrue("new-member" in joined.members)
        assertEquals(playlist, joined.playlist)
        assertEquals(playlist, left.playlist)
        // Membership churn must not stale anybody's in-flight playlist command.
        assertEquals(current.playlistRevision, joined.playlistRevision)
        assertEquals(current.playlistRevision, left.playlistRevision)
    }

    @Test fun `a committed operation batch advances the playlist revision once`() {
        val current = initial()

        val next = BoardCellReplica.reduce(current, BoardCellEvent.PlaylistOpsCommitted(
            listOf(BoardPlaylistOp.Add("e1", "climb", 40)), "command-0001"), 1)

        assertEquals(listOf("e1"), next.playlist.entries.map { it.entryId })
        assertEquals("e1", next.playlist.currentEntryId)
        assertEquals(current.playlistRevision + 1, next.playlistRevision)
        assertTrue("command-0001" in next.recentCommandIds)
        assertTrue(next.hasValidHash())
    }
}
