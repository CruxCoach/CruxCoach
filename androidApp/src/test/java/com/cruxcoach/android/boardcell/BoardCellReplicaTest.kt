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
}
