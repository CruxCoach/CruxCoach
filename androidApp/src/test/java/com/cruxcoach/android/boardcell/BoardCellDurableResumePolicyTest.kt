package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardCellDurableResumePolicyTest {
    private val cell = BoardCellId("cell")
    private val base = BoardCellSnapshot(
        cellId = cell,
        physicalBoardId = PhysicalBoardId("board"),
        epoch = 1,
        sequence = 4,
        controllerId = "controller",
        controllerTerm = 2,
        lineageId = "lineage",
        members = setOf("controller", "member"),
    ).withComputedHash()

    @Test fun `only exact active durable controller may seed a deadlocked rejoin`() {
        assertEquals(base, BoardCellDurableResumePolicy.controllerSeed(base, cell, "controller"))
        assertNull(BoardCellDurableResumePolicy.controllerSeed(base, cell, "member"))
        assertNull(BoardCellDurableResumePolicy.controllerSeed(base, BoardCellId("other"), "controller"))
        assertNull(BoardCellDurableResumePolicy.controllerSeed(
            base.copy(stateHash = "tampered"), cell, "controller"))
        assertNull(BoardCellDurableResumePolicy.controllerSeed(
            base.copy(availability = BoardCellAvailability.FROZEN_NEEDS_CONTROLLER).withComputedHash(),
            cell,
            "controller",
        ))
    }

    @Test fun `in flight handover cannot be resurrected by its durable source`() {
        val handover = BoardCellHandover(
            transferId = "transfer",
            sourceControllerId = "controller",
            targetControllerId = "member",
            sourceTerm = 2,
            targetTerm = 3,
            baseSequence = base.sequence,
            baseHash = base.stateHash,
            phase = HandoverPhase.PREPARED,
        )
        val prepared = base.copy(handover = handover).withComputedHash()
        assertNull(BoardCellDurableResumePolicy.controllerSeed(prepared, cell, "controller"))
    }
}
