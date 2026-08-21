package com.cruxcoach.android.boardcell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertNull(BoardCellDurableResumePolicy.controllerSeed(
            base,
            cell,
            "controller",
            BoardCellDurableResumePolicy.Context.LIVE_NEARBY_JOIN,
        ))
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
        assertNull(BoardCellDurableResumePolicy.memberRecoverySeed(prepared, cell, "member"))
    }

    @Test fun `durable non controller member may seed only fenced board recovery`() {
        assertEquals(base, BoardCellDurableResumePolicy.memberRecoverySeed(base, cell, "member"))
        assertNull(BoardCellDurableResumePolicy.memberRecoverySeed(base, cell, "controller"))
        assertNull(BoardCellDurableResumePolicy.memberRecoverySeed(base, cell, "stranger"))
        assertNull(BoardCellDurableResumePolicy.memberRecoverySeed(
            base.copy(stateHash = "tampered"), cell, "member"))
        assertEquals(
            base.copy(availability = BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT).withComputedHash(),
            BoardCellDurableResumePolicy.memberRecoverySeed(
                base.copy(availability = BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT).withComputedHash(),
                cell,
                "member",
            ),
        )
    }

    @Test fun `only a valid local frozen fork may be replaced after physical fencing`() {
        val frozen = base.copy(availability = BoardCellAvailability.FROZEN_FORK).withComputedHash()

        assertTrue(BoardCellDurableResumePolicy.mayReplaceUnrecoverableFork(frozen, cell, "member"))
        assertFalse(BoardCellDurableResumePolicy.mayReplaceUnrecoverableFork(base, cell, "member"))
        assertFalse(BoardCellDurableResumePolicy.mayReplaceUnrecoverableFork(frozen, cell, "stranger"))
        assertFalse(BoardCellDurableResumePolicy.mayReplaceUnrecoverableFork(
            frozen.copy(stateHash = "tampered"), cell, "member"))
    }

    @Test fun `local fallback singleton is not mistaken for a foreign mesh controller`() {
        val local = base.copy(
            controllerId = "local-device-id",
            members = setOf("local-device-id"),
        ).withComputedHash()

        assertTrue(BoardCellFipsBootstrapPolicy.isLocalFallbackSingleton(local))
        assertFalse(BoardCellFipsBootstrapPolicy.hasKnownSharedCell(local, "npub-new"))
    }

    @Test fun `real foreign mesh membership remains fail closed`() {
        val foreign = base.copy(
            controllerId = "npub-foreign",
            members = setOf("npub-foreign"),
        ).withComputedHash()

        assertFalse(BoardCellFipsBootstrapPolicy.isLocalFallbackSingleton(foreign))
        assertTrue(BoardCellFipsBootstrapPolicy.hasKnownSharedCell(foreign, "npub-local"))
        assertTrue(BoardCellFipsBootstrapPolicy.hasKnownSharedCell(base, "controller"))
        assertFalse(BoardCellFipsBootstrapPolicy.hasKnownSharedCell(
            base.copy(members = setOf("controller")).withComputedHash(),
            "controller",
        ))
    }
}
