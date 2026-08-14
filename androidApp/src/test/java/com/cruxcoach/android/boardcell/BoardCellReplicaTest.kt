package com.cruxcoach.android.boardcell

import org.junit.Assert.*
import org.junit.Test

class BoardCellReplicaTest {
    private fun initial() = BoardCellSnapshot(BoardCellId("cell"), PhysicalBoardId("board"),
        epoch = 7, sequence = 0, controllerId = "controller", leaseUntilMs = 100,
        members = setOf("controller", "member")).withComputedHash()

    @Test fun `sequence gap freezes until full snapshot`() {
        val replica = BoardCellReplica("member", initial())
        val current = replica.snapshot!!
        val event = BoardCellEvent.ProjectCommitted(BoardProjection("later", 40))
        val fakeNext = BoardCellReplica.reduce(current, event, 2)
        val result = replica.applyEvent(BoardCellEnvelope(current.cellId, current.physicalBoardId,
            current.epoch, 2, current.stateHash, event, fakeNext.stateHash))
        assertTrue(result is BoardCellApplyResult.NeedSnapshot)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT, replica.snapshot?.availability)
        val recovered = fakeNext.copy(members = setOf("controller", "member")).withComputedHash()
        assertTrue(replica.applySnapshot(recovered) is BoardCellApplyResult.Applied)
        assertEquals("later", replica.snapshot?.projection?.climbUuid)
    }

    @Test fun `wrong cell and nonmember snapshots are rejected`() {
        val replica = BoardCellReplica("member", initial())
        val foreign = initial().copy(cellId = BoardCellId("other")).withComputedHash()
        assertTrue(replica.applySnapshot(foreign) is BoardCellApplyResult.Rejected)
        val excluded = initial().copy(sequence = 1, members = setOf("controller")).withComputedHash()
        assertTrue(replica.applySnapshot(excluded) is BoardCellApplyResult.Rejected)
    }
}
