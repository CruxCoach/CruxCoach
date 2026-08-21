package com.cruxcoach.android.data

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.boardcell.BoardCellAvailability
import com.cruxcoach.android.boardcell.BoardCellHandover
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.HandoverPhase
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.android.ble.RelayGattServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exactly one CruxRelay per mesh, and only while it can actually take a guest.
 *
 * The design is in `CRUXRELAY_MESH_DESIGN.md`. Two things it turns on:
 *
 *  - the owner is the BoardCell controller, not a separately elected role, so
 *    "exactly one" is inherited from a lease that already has an epoch, a term
 *    and handover phases rather than from a second election that could disagree
 *    with the first;
 *  - FIPS peers, the board link and relay guests are one Android BLE adapter,
 *    not three budgets, so the relay yields to the mesh instead of advertising
 *    a slot the radio does not have.
 */
class CruxRelayOwnershipPolicyTest {

    private val me = "node-me"
    private val other = "node-other"
    private val board = PhysicalBoardId("kilter:ble:AA:BB:CC:DD:EE:FF")

    private fun snapshot(
        controller: String = me,
        members: Set<String> = setOf(me, other),
        availability: BoardCellAvailability = BoardCellAvailability.ACTIVE,
        handover: BoardCellHandover? = null,
        epoch: Long = 1,
        term: Long = 1,
        physical: PhysicalBoardId = board,
    ) = BoardCellSnapshot(
        cellId = BoardCellId.forPhysical(physical),
        physicalBoardId = physical,
        epoch = epoch,
        sequence = 10,
        controllerId = controller,
        controllerTerm = term,
        lineageId = "lineage",
        members = members,
        availability = availability,
        handover = handover,
    )

    private fun evaluate(
        snapshot: BoardCellSnapshot? = snapshot(),
        startedUnder: RelayLease? = null,
        meshAvailable: Boolean = true,
        health: RelayBoardLinkHealth = RelayBoardLinkHealth.HEALTHY,
        meshPeers: Int = 1,
        relayClients: Int = 0,
    ) = CruxRelayOwnershipPolicy.evaluate(
        localNodeId = me,
        snapshot = snapshot,
        startedUnder = startedUnder,
        meshAvailable = meshAvailable,
        boardHealth = health,
        meshPeers = meshPeers,
        activeRelayClients = relayClients,
        serverCeiling = RelayGattServer.MAX_CONNECTED_DEVICES,
    )

    private fun suppression(offer: RelayOffer) = (offer as RelayOffer.Suppressed).reason

    // ── Ownership ─────────────────────────────────────────────────────────

    @Test
    fun `the controller of an active cell offers the relay`() {
        assertEquals(RelayOffer.Offer(5.coerceAtMost(4)), evaluate())
    }

    @Test
    fun `a member that is not the controller never offers one`() {
        assertEquals(
            RelaySuppression.NOT_CONTROLLER,
            suppression(evaluate(snapshot = snapshot(controller = other))),
        )
    }

    /** Two devices cannot both be `controllerId`, so two cannot both offer. */
    @Test
    fun `simultaneous candidates resolve to exactly one offer`() {
        val canonical = snapshot(controller = other)
        val mine = CruxRelayOwnershipPolicy.evaluate(
            localNodeId = me, snapshot = canonical, startedUnder = null, meshAvailable = true,
            boardHealth = RelayBoardLinkHealth.HEALTHY, meshPeers = 1, activeRelayClients = 0,
            serverCeiling = 4,
        )
        val theirs = CruxRelayOwnershipPolicy.evaluate(
            localNodeId = other, snapshot = canonical, startedUnder = null, meshAvailable = true,
            boardHealth = RelayBoardLinkHealth.HEALTHY, meshPeers = 1, activeRelayClients = 0,
            serverCeiling = 4,
        )
        assertTrue(mine is RelayOffer.Suppressed)
        assertTrue(theirs is RelayOffer.Offer)
    }

    @Test
    fun `a device that has left the cell stops offering`() {
        assertEquals(
            RelaySuppression.NOT_A_MEMBER,
            suppression(evaluate(snapshot = snapshot(members = setOf(other)))),
        )
    }

    /** A minority replica cannot commit, so it must not front the board. */
    @Test
    fun `a partitioned controller fails closed`() {
        BoardCellAvailability.entries.filter { it != BoardCellAvailability.ACTIVE }
            .forEach { availability ->
            assertEquals(
                availability.name,
                RelaySuppression.CELL_UNAVAILABLE,
                suppression(evaluate(snapshot = snapshot(availability = availability))),
            )
        }
    }

    @Test
    fun `no offer at any handover phase short of completed`() {
        HandoverPhase.entries.filter { it != HandoverPhase.COMPLETED }.forEach { phase ->
            val handover = BoardCellHandover(
                transferId = "t", sourceControllerId = me, targetControllerId = other,
                sourceTerm = 1, targetTerm = 2, baseSequence = 10, baseHash = "h", phase = phase,
            )
            assertEquals(
                phase.name,
                RelaySuppression.HANDOVER,
                suppression(evaluate(snapshot = snapshot(handover = handover))),
            )
        }
    }

    // ── Fencing ───────────────────────────────────────────────────────────

    /** A resurrected old owner recognises its own lease as superseded. */
    @Test
    fun `an offer from a superseded term is suppressed`() {
        val stale = RelayLease(epoch = 1, controllerTerm = 1, physicalBoardId = board.value)

        assertEquals(
            RelaySuppression.STALE_LEASE,
            suppression(evaluate(snapshot = snapshot(term = 2), startedUnder = stale)),
        )
    }

    @Test
    fun `an offer from a previous epoch is suppressed`() {
        val stale = RelayLease(epoch = 1, controllerTerm = 1, physicalBoardId = board.value)

        assertEquals(
            RelaySuppression.STALE_LEASE,
            suppression(evaluate(snapshot = snapshot(epoch = 2), startedUnder = stale)),
        )
    }

    @Test
    fun `an offer does not survive a board change`() {
        val onOldBoard = RelayLease(epoch = 1, controllerTerm = 1, physicalBoardId = board.value)
        val newBoard = PhysicalBoardId("kilter:ble:11:22:33:44:55:66")

        assertEquals(
            RelaySuppression.BOARD_CHANGED,
            suppression(evaluate(snapshot = snapshot(physical = newBoard), startedUnder = onOldBoard)),
        )
    }

    @Test
    fun `the lease it was started under keeps offering`() {
        val current = RelayLease(epoch = 1, controllerTerm = 1, physicalBoardId = board.value)

        assertTrue(evaluate(startedUnder = current) is RelayOffer.Offer)
    }

    // ── Health and grace ──────────────────────────────────────────────────

    @Test
    fun `a write in flight is not a lost board`() {
        assertEquals(
            RelayBoardLinkHealth.HEALTHY,
            CruxRelayOwnershipPolicy.health(ConnectionState.SENDING, msSinceBoardLinkLost = null),
        )
    }

    @Test
    fun `a blip is recovery, a longer gap is a loss`() {
        assertEquals(
            RelayBoardLinkHealth.GRACE,
            CruxRelayOwnershipPolicy.health(ConnectionState.DISCONNECTED, 1_000),
        )
        assertEquals(
            RelayBoardLinkHealth.LOST,
            CruxRelayOwnershipPolicy.health(ConnectionState.DISCONNECTED, 60_000),
        )
        assertEquals(
            "never connected is not a recovery",
            RelayBoardLinkHealth.LOST,
            CruxRelayOwnershipPolicy.health(ConnectionState.DISCONNECTED, null),
        )
    }

    @Test
    fun `recovery withdraws the offer without calling the board lost`() {
        assertEquals(
            RelaySuppression.BOARD_RECOVERING,
            suppression(evaluate(health = RelayBoardLinkHealth.GRACE)),
        )
        assertEquals(
            RelaySuppression.BOARD_LOST,
            suppression(evaluate(health = RelayBoardLinkHealth.LOST)),
        )
    }

    /** The guest's app was already told the write succeeded by the GATT layer. */
    @Test
    fun `only a healthy path may acknowledge a write as landed`() {
        assertTrue(CruxRelayOwnershipPolicy.mayAcknowledgeWrite(RelayBoardLinkHealth.HEALTHY))
        listOf(RelayBoardLinkHealth.GRACE, RelayBoardLinkHealth.LOST).forEach {
            assertEquals(it.name, false, CruxRelayOwnershipPolicy.mayAcknowledgeWrite(it))
        }
    }

    // ── Capacity ──────────────────────────────────────────────────────────

    /**
     * The real boundary, and it is one lower than the mesh ceiling suggests:
     * the board link is a radio slot too. Five peers plus the board leaves one
     * for a guest; six leaves none.
     */
    @Test
    fun `five peers plus a board leave exactly one guest slot`() {
        assertEquals(RelayOffer.Offer(1), evaluate(meshPeers = 5))
    }

    @Test
    fun `six peers plus a board is already the whole radio`() {
        assertEquals(RelaySuppression.NO_CAPACITY, suppression(evaluate(meshPeers = 6)))
    }

    /** Seven CoC links is the mesh ceiling; with a board link it is over it. */
    @Test
    fun `a full mesh offers no relay at all`() {
        assertEquals(RelaySuppression.NO_CAPACITY, suppression(evaluate(meshPeers = 7)))
    }

    /** Eight is impossible; it must not wrap into a negative or a large slot count. */
    @Test
    fun `an over-full mesh cannot produce a negative slot count`() {
        assertEquals(RelaySuppression.NO_CAPACITY, suppression(evaluate(meshPeers = 8)))
        assertEquals(
            0,
            CruxRelayOwnershipPolicy.availableSlots(
                meshPeers = 8, boardLinkHeld = true, activeRelayClients = 0, serverCeiling = 4,
            ),
        )
    }

    @Test
    fun `nineteen joiners cannot inflate the offer`() {
        assertEquals(
            0,
            CruxRelayOwnershipPolicy.availableSlots(
                meshPeers = 19, boardLinkHeld = true, activeRelayClients = 0, serverCeiling = 4,
            ),
        )
    }

    @Test
    fun `each guest that arrives shrinks the remaining offer`() {
        assertEquals(RelayOffer.Offer(2), evaluate(meshPeers = 2, relayClients = 2))
        assertEquals(RelaySuppression.NO_CAPACITY, suppression(evaluate(meshPeers = 2, relayClients = 4)))
    }

    @Test
    fun `the server ceiling still bounds a quiet mesh`() {
        assertEquals(
            RelayGattServer.MAX_CONNECTED_DEVICES,
            CruxRelayOwnershipPolicy.availableSlots(
                meshPeers = 0, boardLinkHeld = true, activeRelayClients = 0,
                serverCeiling = RelayGattServer.MAX_CONNECTED_DEVICES,
            ),
        )
    }

    /**
     * A simulated forty-member cell: only the controller offers, and only when
     * its own radio has room. Membership size is not the constraint — direct
     * BLE peers are.
     */
    @Test
    fun `a forty member topology still has exactly one offer`() {
        val members = (1..40).map { "node-$it" }.toSet() + me
        val canonical = snapshot(controller = me, members = members)

        val offers = (members).count { node ->
            CruxRelayOwnershipPolicy.evaluate(
                localNodeId = node, snapshot = canonical, startedUnder = null,
                meshAvailable = true, boardHealth = RelayBoardLinkHealth.HEALTHY,
                meshPeers = 5, activeRelayClients = 0, serverCeiling = 4,
            ) is RelayOffer.Offer
        }

        assertEquals("exactly one logical provider per epoch", 1, offers)
    }

    @Test
    fun `a platform without the mesh never offers a relay`() {
        assertEquals(RelaySuppression.PLATFORM, suppression(evaluate(meshAvailable = false)))
    }

    /**
     * Ownership is decided before health and health before capacity, so a
     * stale owner is never told it merely lacks room.
     */
    @Test
    fun `ownership outranks health and capacity in the diagnosis`() {
        assertEquals(
            RelaySuppression.NOT_CONTROLLER,
            suppression(
                evaluate(
                    snapshot = snapshot(controller = other),
                    health = RelayBoardLinkHealth.LOST,
                    meshPeers = 7,
                ),
            ),
        )
    }
}
