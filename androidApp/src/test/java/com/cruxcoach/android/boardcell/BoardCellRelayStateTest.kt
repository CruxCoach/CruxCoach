package com.cruxcoach.android.boardcell

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.data.CruxRelayOwnershipPolicy
import com.cruxcoach.android.data.RelayBoardLinkHealth
import com.cruxcoach.android.data.RelayOffer
import com.cruxcoach.android.fips.FipsMeshRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cell's relay, as everybody in it can verify.
 *
 * Ownership is not what is replicated here — `controllerId` already decides
 * that and is fenced on its own. What travels is the *description*: whether a
 * relay is being offered, how many guest slots are held open, and how well the
 * owner can reach the board. A member needs it to answer "can I get on this
 * board through somebody" without asking, and it is only worth anything if a
 * peer cannot forge it. Hence: bounded values, inside the state hash, and
 * fenced to the exact lease that stamped it.
 */
class BoardCellRelayStateTest {

    private fun cell(
        epoch: Long = 7,
        term: Long = 3,
        controller: String = "controller",
        relay: BoardCellRelayState = BoardCellRelayState.NONE,
    ) = BoardCellSnapshot(
        BoardCellId("cell"), PhysicalBoardId("board"),
        epoch = epoch, sequence = 0, controllerId = controller, controllerTerm = term,
        lineageId = "lineage", members = setOf("controller", "member"), relay = relay,
    ).withComputedHash()

    private fun healthy(epoch: Long = 7, term: Long = 3, free: Int = 1) = BoardCellRelayState(
        offered = true, guaranteedSlots = 1, freeSlots = free,
        health = BoardCellRelayHealth.HEALTHY, epoch = epoch, controllerTerm = term,
    )

    // ── It is real state, not a rumour ────────────────────────────────────

    @Test
    fun `the relay claim is inside the state hash`() {
        val without = cell()
        val with = cell(relay = healthy())

        assertNotEquals(without.stateHash, with.stateHash)
        assertTrue(with.hasValidHash())
    }

    /** Edit one slot count in transit and the state it described stops verifying. */
    @Test
    fun `an altered slot count invalidates the snapshot it was carried in`() {
        val honest = cell(relay = healthy(free = 1))

        val tampered = honest.copy(relay = honest.relay.copy(freeSlots = 4))

        assertFalse(tampered.hasValidHash())
    }

    /** A cell from before this existed still verifies, and claims nothing. */
    @Test
    fun `a legacy snapshot stays valid and offers no relay`() {
        val legacy = cell().copy(stateHash = "")
        val v9 = legacy.copy(stateHash = BoardCellHash.computeLegacyV9(legacy))

        assertTrue(v9.hasValidHash())
        assertEquals(BoardCellRelayState.NONE, v9.relay)
    }

    /** And a legacy hash cannot be used as cover for a claim. */
    @Test
    fun `a legacy hash cannot carry a relay claim`() {
        val base = cell(relay = healthy()).copy(stateHash = "")

        val forged = base.copy(stateHash = BoardCellHash.computeLegacyV9(base))

        assertFalse(forged.hasValidHash())
    }

    // ── Only the lease that stamped it ────────────────────────────────────

    @Test
    fun `the controller's own claim is accepted`() {
        val current = cell()

        val next = BoardCellReplica.reduce(
            current, BoardCellEvent.RelayStateChanged(healthy()), current.sequence + 1,
        )

        assertTrue(next.relay.offered)
        assertEquals(1, next.relay.freeSlots)
    }

    /** A claim from a term that has passed describes a cell that no longer exists. */
    @Test
    fun `a claim from a superseded term is dropped, not merged`() {
        val current = cell(term = 4, relay = healthy(term = 4))

        val next = BoardCellReplica.reduce(
            current, BoardCellEvent.RelayStateChanged(healthy(term = 3, free = 4)),
            current.sequence + 1,
        )

        assertEquals("the cell keeps what its own lease said", current.relay, next.relay)
    }

    @Test
    fun `a claim from another epoch is dropped`() {
        val current = cell(epoch = 9)

        val next = BoardCellReplica.reduce(
            current, BoardCellEvent.RelayStateChanged(healthy(epoch = 8)), current.sequence + 1,
        )

        assertEquals(BoardCellRelayState.NONE, next.relay)
    }

    /**
     * The hostile case worth being explicit about: a member stamping itself as
     * the relay owner. It cannot — there is no owner field to stamp. Ownership
     * is `controllerId`, the claim only ever describes it, and a description
     * that lands changes nothing about who holds the board.
     */
    @Test
    fun `a relay claim can never change who the controller is`() {
        val current = cell(controller = "controller")

        val next = BoardCellReplica.reduce(
            current, BoardCellEvent.RelayStateChanged(healthy()), current.sequence + 1,
        )

        assertEquals("controller", next.controllerId)
    }

    // ── Bounded on read ───────────────────────────────────────────────────

    @Test
    fun `an inflated slot count is clamped rather than believed`() {
        val current = cell()

        val next = BoardCellReplica.reduce(
            current,
            BoardCellEvent.RelayStateChanged(healthy(free = 4_000)),
            current.sequence + 1,
        )

        assertEquals(BoardCellReplica.RELAY_SLOT_CEILING, next.relay.freeSlots)
    }

    @Test
    fun `a negative slot count cannot underflow the offer`() {
        assertEquals(0, healthy(free = -5).sanitized(FipsMeshRuntime.RADIO_BUDGET).freeSlots)
    }

    // ── What a member is allowed to believe ───────────────────────────────

    @Test
    fun `a member reads the current claim`() {
        val observed = CruxRelayOwnershipPolicy.observedRelay(cell(relay = healthy()))

        assertTrue(observed.offered)
        assertEquals(1, observed.guaranteedSlots)
    }

    @Test
    fun `a member ignores a claim belonging to a lease the cell has moved past`() {
        val stale = cell(term = 5, relay = healthy(term = 4))

        assertEquals(BoardCellRelayState.NONE, CruxRelayOwnershipPolicy.observedRelay(stale))
    }

    @Test
    fun `a cell with no controller has nobody entitled to describe its relay`() {
        val orphaned = cell(controller = "", relay = healthy())

        assertEquals(BoardCellRelayState.NONE, CruxRelayOwnershipPolicy.observedRelay(orphaned))
    }

    @Test
    fun `nothing is believed about a cell this device is not in`() {
        assertEquals(BoardCellRelayState.NONE, CruxRelayOwnershipPolicy.observedRelay(null))
    }

    // ── What the owner stamps ─────────────────────────────────────────────

    @Test
    fun `the claim an owner stamps carries its own lease`() {
        val snapshot = cell(epoch = 11, term = 2)

        val claim = CruxRelayOwnershipPolicy.claimFor(
            snapshot, RelayOffer.Offer(1), RelayBoardLinkHealth.HEALTHY,
        )

        assertEquals(11, claim.epoch)
        assertEquals(2, claim.controllerTerm)
        assertTrue(claim.isCurrentFor(snapshot))
        assertEquals(BoardCellRelayHealth.HEALTHY, claim.health)
        assertEquals(CruxRelayOwnershipPolicy.GUARANTEED_GUEST_SLOT, claim.guaranteedSlots)
    }

    @Test
    fun `a suppressed relay is described as offering nothing`() {
        val claim = CruxRelayOwnershipPolicy.claimFor(
            cell(),
            RelayOffer.Suppressed(com.cruxcoach.android.data.RelaySuppression.NO_CAPACITY),
            RelayBoardLinkHealth.HEALTHY,
        )

        assertFalse(claim.offered)
        assertEquals(0, claim.freeSlots)
    }

    /** Recovery is neither healthy nor lost, and a guest deciding where to go needs the difference. */
    @Test
    fun `board recovery is described as recovering`() {
        val claim = CruxRelayOwnershipPolicy.claimFor(
            cell(), RelayOffer.Offer(1),
            CruxRelayOwnershipPolicy.health(ConnectionState.DISCONNECTED, 1_000),
        )

        assertEquals(BoardCellRelayHealth.RECOVERING, claim.health)
    }
}
