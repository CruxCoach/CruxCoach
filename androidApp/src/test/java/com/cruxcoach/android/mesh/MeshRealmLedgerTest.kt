package com.cruxcoach.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The complete concurrent/incompatible realm policy, independent of any radio. */
class MeshRealmLedgerTest {
    private val board = MeshRealmId("cell-a")
    private val other = MeshRealmId("cell-b")
    private val boardMeta = MeshRealmMetadata(MeshRealmKind.BOARD_CELL, "cell-a", "Kilter")
    private val otherMeta = MeshRealmMetadata(MeshRealmKind.BOARD_CELL, "cell-b")

    @Test fun `the first owner activates the realm`() {
        val ledger = MeshRealmLedger()

        val outcome = ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertTrue(outcome is MeshAcquireOutcome.Activated)
        assertEquals(board, ledger.activeRealm())
        assertEquals(1, ledger.references(MeshOwners.BOARD_CELL, board))
    }

    @Test fun `a second feature shares the realm instead of switching it`() {
        val ledger = MeshRealmLedger()
        ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        val competition = ledger.acquire(MeshOwners.competition("comp-1"), board,
            boardMeta.copy(displayName = null))

        assertTrue(competition is MeshAcquireOutcome.Joined)
        assertEquals(1, (competition as MeshAcquireOutcome.Joined).references)
        assertEquals(setOf(MeshOwners.BOARD_CELL, MeshOwners.competition("comp-1")), ledger.owners())
        // A later owner may not erase the board's display name.
        assertEquals("Kilter", ledger.activeMetadata()?.displayName)
    }

    @Test fun `every acquire needs its own release`() {
        val ledger = MeshRealmLedger()
        repeat(3) { ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta) }
        assertEquals(3, ledger.references(MeshOwners.BOARD_CELL, board))

        assertEquals(MeshReleaseOutcome.Retained(2), ledger.release(MeshOwners.BOARD_CELL, board))
        assertEquals(MeshReleaseOutcome.Retained(1), ledger.release(MeshOwners.BOARD_CELL, board))
        assertEquals(MeshReleaseOutcome.Deactivated(board), ledger.release(MeshOwners.BOARD_CELL, board))
        assertNull(ledger.activeRealm())
    }

    @Test fun `releaseAll drops an owner's whole stack at once`() {
        val ledger = MeshRealmLedger()
        repeat(3) { ledger.acquire(MeshOwners.SESSION, board, boardMeta) }
        ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertEquals(MeshReleaseOutcome.OwnerReleased(board), ledger.releaseAll(MeshOwners.SESSION))

        assertEquals(0, ledger.references(MeshOwners.SESSION, board))
        assertEquals(board, ledger.activeRealm())
        assertEquals(MeshReleaseOutcome.Deactivated(board), ledger.releaseAll(MeshOwners.BOARD_CELL))
    }

    @Test fun `the realm only ends with the last owner`() {
        val ledger = MeshRealmLedger()
        ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        ledger.acquire(MeshOwners.competition("comp-1"), board, boardMeta)

        assertEquals(MeshReleaseOutcome.OwnerReleased(board), ledger.release(MeshOwners.BOARD_CELL, board))
        assertEquals(board, ledger.activeRealm())
        assertEquals(MeshReleaseOutcome.Deactivated(board),
            ledger.release(MeshOwners.competition("comp-1"), board))
        assertNull(ledger.activeRealm())
    }

    @Test fun `an owner without a lease cannot displace somebody else's realm`() {
        val ledger = MeshRealmLedger()
        ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        val denied = ledger.acquire(MeshOwners.competition("comp-1"), other, otherMeta)

        assertEquals(MeshAcquireOutcome.Denied(MeshRealmDenial.REALM_CONFLICT, board), denied)
        assertEquals(board, ledger.activeRealm())
        assertEquals(0, ledger.references(MeshOwners.competition("comp-1"), other))
    }

    @Test fun `a holder re-targeting the realm evicts the other owners explicitly`() {
        val ledger = MeshRealmLedger()
        repeat(2) { ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta) }
        ledger.acquire(MeshOwners.competition("comp-1"), board, boardMeta)

        val outcome = ledger.acquire(MeshOwners.BOARD_CELL, other, otherMeta)

        assertTrue(outcome is MeshAcquireOutcome.Superseded)
        outcome as MeshAcquireOutcome.Superseded
        assertEquals(board, outcome.previous)
        assertEquals(setOf(MeshOwners.competition("comp-1")), outcome.evicted)
        assertEquals(other, ledger.activeRealm())
        // Leases live on a realm, so the old stack does not follow to the new one.
        assertEquals(1, ledger.references(MeshOwners.BOARD_CELL, other))
        assertEquals(0, ledger.references(MeshOwners.competition("comp-1"), other))
    }

    @Test fun `a realm is not shared by owners meaning a different physical scope`() {
        val ledger = MeshRealmLedger()
        ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        val wrongKind = ledger.acquire(MeshOwners.competition("comp-1"), board,
            MeshRealmMetadata(MeshRealmKind.COMPETITION, "cell-a"))
        val wrongCell = ledger.acquire(MeshOwners.competition("comp-1"), board,
            MeshRealmMetadata(MeshRealmKind.BOARD_CELL, "cell-z"))

        assertEquals(MeshAcquireOutcome.Denied(MeshRealmDenial.METADATA_CONFLICT, board), wrongKind)
        assertEquals(MeshAcquireOutcome.Denied(MeshRealmDenial.METADATA_CONFLICT, board), wrongCell)
        assertEquals(setOf(MeshOwners.BOARD_CELL), ledger.owners())
    }

    @Test fun `a stale or duplicated release never steals another lease`() {
        val ledger = MeshRealmLedger()
        ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)

        assertEquals(MeshReleaseOutcome.Unknown, ledger.release(MeshOwners.BOARD_CELL, other))
        assertEquals(MeshReleaseOutcome.Unknown, ledger.release(MeshOwners.NEARBY_BOARD_CELL, board))
        assertEquals(MeshReleaseOutcome.Unknown, ledger.releaseAll(MeshOwners.HANDOVER))
        assertEquals(board, ledger.activeRealm())

        assertEquals(MeshReleaseOutcome.Deactivated(board), ledger.release(MeshOwners.BOARD_CELL, board))
        assertEquals(MeshReleaseOutcome.Unknown, ledger.release(MeshOwners.BOARD_CELL, board))
    }

    @Test fun `a failed activation leaves no lease behind`() {
        val ledger = MeshRealmLedger()

        ledger.acquire(MeshOwners.BOARD_CELL, board, boardMeta)
        ledger.rollback(MeshOwners.BOARD_CELL, board)

        assertNull(ledger.activeRealm())
        assertEquals(emptySet<MeshOwner>(), ledger.owners())
        // The next owner is free to take whichever realm it needs.
        assertTrue(ledger.acquire(MeshOwners.NEARBY_BOARD_CELL, other, otherMeta)
            is MeshAcquireOutcome.Activated)
    }
}
