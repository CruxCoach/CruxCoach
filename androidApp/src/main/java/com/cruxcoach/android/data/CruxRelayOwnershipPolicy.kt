package com.cruxcoach.android.data

import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.boardcell.BoardCellAvailability
import com.cruxcoach.android.boardcell.BoardCellRelayHealth
import com.cruxcoach.android.boardcell.BoardCellRelayState
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.HandoverPhase
import com.cruxcoach.android.fips.FipsMeshRuntime

/** How well this device can currently reach the physical board it fronts. */
enum class RelayBoardLinkHealth {
    /** Connected, or mid-write — a write is not a loss. */
    HEALTHY,

    /** Dropped moments ago and inside the recovery window. */
    GRACE,

    /** Gone, or gone long enough that it counts as gone. */
    LOST,
}

/** Why a relay is not being offered right now. Ordered by diagnosis, not severity. */
enum class RelaySuppression {
    /** This platform has no FIPS mesh, so it never fronts a cell's board. */
    PLATFORM,
    NOT_A_MEMBER,
    NOT_CONTROLLER,
    CELL_UNAVAILABLE,

    /** A handover is under way; the offer belongs to whoever finishes it. */
    HANDOVER,

    /** This offer was started under an epoch or term that has been superseded. */
    STALE_LEASE,

    /** The board this offer was started for is not the board the cell is on. */
    BOARD_CHANGED,
    BOARD_LOST,

    /** The link dropped moments ago. The server stays warm; the offer does not. */
    BOARD_RECOVERING,

    /** The radio has nothing left after the board link and the mesh. */
    NO_CAPACITY,
}

sealed interface RelayOffer {
    /** Advertise, and accept up to [slots] guests. Always at least one. */
    data class Offer(val slots: Int) : RelayOffer

    /**
     * Keep serving the guests already here, but advertise nothing.
     *
     * The state that was missing, and its absence was destructive: with the
     * one guaranteed slot in use there is no room for a *new* guest, which the
     * policy reported as `NO_CAPACITY` — and the lifecycle then shut down the
     * relay the connected guest was in the middle of using. Full and finished
     * are not the same thing.
     */
    data class Serving(val guests: Int) : RelayOffer

    data class Suppressed(val reason: RelaySuppression) : RelayOffer
}

/** The lease an offer was started under, so a superseded one can be recognised. */
data class RelayLease(
    val epoch: Long,
    val controllerTerm: Long,
    val physicalBoardId: String,
)

/**
 * Whether this device may offer a CruxRelay, and for how many guests.
 *
 * The owner is the BoardCell controller — not a separately elected role. That
 * lease already has an epoch, a term, deterministic tie-breaking, a heartbeat
 * and handover phases, and the controller is already the only device that
 * writes the physical board. A second election would be a second lease that can
 * disagree with the first, which is the split-brain this is supposed to prevent,
 * and a second device holding a usable path to the same wall is how a second
 * physical writer appears.
 *
 * The capacity half is the part the code did not have. FIPS peers, the board
 * link and relay guests are not three budgets — they are one Android BLE
 * adapter, and the native layer already says so: seven CoC links are "an
 * admission ceiling, not a hardware promise", and OEM stacks may refuse one
 * "especially beside the physical board link". Advertising seven mesh peers
 * plus four guests plus a board is a promise no device keeps.
 *
 * So the relay yields. Mesh links are what make a cell converge; a guest is a
 * convenience. A controller with a full mesh offers nothing and stops
 * advertising — which is the honest answer, and better than a connectable
 * advertisement that refuses every connection.
 */
object CruxRelayOwnershipPolicy {

    /**
     * One Android BLE adapter, shared by everything this app connects.
     *
     * Read from the mesh runtime rather than restated, because the two have to
     * be the same number: the mesh's admission ceiling is what leaves the
     * reserve, and a copy here would let them drift into a relay slot that
     * exists in the accounting and not on the radio.
     */
    const val RADIO_BUDGET = FipsMeshRuntime.RADIO_BUDGET

    /**
     * The guest slot the mesh keeps free.
     *
     * `FipsMeshRuntime` admits five direct peers instead of seven precisely so
     * that this one is still there when a handover makes this device the
     * controller. Subtracting live peers afterwards — what this policy did
     * before — meant a busy cell had no relay at all.
     */
    const val GUARANTEED_GUEST_SLOT = FipsMeshRuntime.RELAY_GUEST_RESERVE

    /**
     * Long enough for an OEM reconnect, short enough not to strand a guest.
     *
     * A ceiling, not a target: at exactly this many milliseconds the link is
     * gone. Measured on a monotonic clock, because a wall clock can be stepped
     * by NTP or a timezone change and would hand out a grace window of an
     * arbitrary length in either direction.
     */
    const val GRACE_MS = 8_000L

    fun health(
        connectionState: ConnectionState,
        msSinceBoardLinkLost: Long?,
        graceMs: Long = GRACE_MS,
    ): RelayBoardLinkHealth = when {
        // SENDING is a transient sub-state of a write. Treating it as a loss
        // would make a relay tear itself down in the middle of relaying.
        connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.SENDING -> RelayBoardLinkHealth.HEALTHY
        // Strictly inside the window. At the deadline itself the board is lost,
        // so the contractual "at most eight seconds" is exactly that.
        msSinceBoardLinkLost != null && msSinceBoardLinkLost < graceMs -> RelayBoardLinkHealth.GRACE
        else -> RelayBoardLinkHealth.LOST
    }

    /**
     * What is left for guests after the board and the mesh have taken theirs.
     *
     * [activeRelayClients] are already-connected guests: they hold slots, so the
     * remaining offer shrinks as they arrive rather than the advertisement
     * promising the same free slot to everybody.
     */
    fun availableSlots(
        meshPeers: Int,
        boardLinkHeld: Boolean,
        activeRelayClients: Int,
        serverCeiling: Int,
        radioBudget: Int = RADIO_BUDGET,
    ): Int {
        val guests = activeRelayClients.coerceAtLeast(0)
        val reserved = (if (boardLinkHeld) 1 else 0) + meshPeers.coerceAtLeast(0) + guests
        // The mesh admits five direct peers so that one slot survives for a
        // guest. Where the peer count is at or under that ceiling the reserve
        // is guaranteed rather than merely hoped for; above it — a peer count
        // this device did not admit — the arithmetic still refuses to promise
        // what the radio does not have.
        val spare = (radioBudget - reserved).coerceAtLeast(
            if (meshPeers <= FipsMeshRuntime.MAX_DIRECT_CONNECTIONS && boardLinkHeld) {
                GUARANTEED_GUEST_SLOT - guests
            } else 0,
        )
        val ceilingLeft = serverCeiling - guests
        return minOf(spare, ceilingLeft).coerceAtLeast(0)
    }

    /**
     * First match wins, and the order is deliberate: ownership, then health,
     * then capacity. A stale owner should never be told it merely lacks room.
     */
    fun evaluate(
        localNodeId: String,
        snapshot: BoardCellSnapshot?,
        startedUnder: RelayLease?,
        meshAvailable: Boolean,
        boardHealth: RelayBoardLinkHealth,
        meshPeers: Int,
        activeRelayClients: Int,
        serverCeiling: Int,
        radioBudget: Int = RADIO_BUDGET,
    ): RelayOffer {
        if (!meshAvailable) return RelayOffer.Suppressed(RelaySuppression.PLATFORM)
        if (snapshot == null || localNodeId !in snapshot.members) {
            return RelayOffer.Suppressed(RelaySuppression.NOT_A_MEMBER)
        }
        if (snapshot.controllerId != localNodeId) {
            return RelayOffer.Suppressed(RelaySuppression.NOT_CONTROLLER)
        }
        // FROZEN_WRITE_RECOVERY included: a controller that cannot commit must
        // not be fronting the board for strangers.
        if (snapshot.availability != BoardCellAvailability.ACTIVE) {
            return RelayOffer.Suppressed(RelaySuppression.CELL_UNAVAILABLE)
        }
        snapshot.handover?.let {
            if (it.phase != HandoverPhase.COMPLETED) {
                return RelayOffer.Suppressed(RelaySuppression.HANDOVER)
            }
        }
        // A resurrected old owner recognises itself here: the lease it started
        // under is not the lease the cell is on.
        startedUnder?.let { lease ->
            if (lease.physicalBoardId != snapshot.physicalBoardId.value) {
                return RelayOffer.Suppressed(RelaySuppression.BOARD_CHANGED)
            }
            if (lease.epoch != snapshot.epoch || lease.controllerTerm != snapshot.controllerTerm) {
                return RelayOffer.Suppressed(RelaySuppression.STALE_LEASE)
            }
        }
        when (boardHealth) {
            RelayBoardLinkHealth.LOST -> return RelayOffer.Suppressed(RelaySuppression.BOARD_LOST)
            RelayBoardLinkHealth.GRACE ->
                return RelayOffer.Suppressed(RelaySuppression.BOARD_RECOVERING)
            RelayBoardLinkHealth.HEALTHY -> Unit
        }
        val slots = availableSlots(
            meshPeers = meshPeers,
            boardLinkHeld = true,
            activeRelayClients = activeRelayClients,
            serverCeiling = serverCeiling,
            radioBudget = radioBudget,
        )
        if (slots > 0) return RelayOffer.Offer(slots)
        // No room for another guest. Whether that ends the relay depends on
        // whether it is doing anything: a guest mid-session keeps it alive and
        // merely un-advertised, and an empty relay with no room to grow has
        // nothing to stay up for.
        return if (activeRelayClients > 0) RelayOffer.Serving(activeRelayClients)
        else RelayOffer.Suppressed(RelaySuppression.NO_CAPACITY)
    }

    /**
     * The relay claim this owner may stamp into canonical state.
     *
     * Only ever about itself, and only ever for the lease it is actually on:
     * the `(epoch, term)` it carries is what makes a claim from a superseded
     * owner recognisable as stale rather than merely old.
     */
    fun claimFor(snapshot: BoardCellSnapshot, offer: RelayOffer, health: RelayBoardLinkHealth):
        BoardCellRelayState = BoardCellRelayState(
        // Only an advertised relay is one a member can reach right now. A
        // serving-but-full one is running, and saying "offered" about it would
        // send somebody at an advertisement that is not out.
        offered = offer is RelayOffer.Offer,
        guaranteedSlots = GUARANTEED_GUEST_SLOT,
        freeSlots = (offer as? RelayOffer.Offer)?.slots ?: 0,
        health = when (health) {
            RelayBoardLinkHealth.HEALTHY -> BoardCellRelayHealth.HEALTHY
            RelayBoardLinkHealth.GRACE -> BoardCellRelayHealth.RECOVERING
            RelayBoardLinkHealth.LOST -> BoardCellRelayHealth.LOST
        },
        epoch = snapshot.epoch,
        controllerTerm = snapshot.controllerTerm,
    ).sanitized(RADIO_BUDGET)

    /**
     * What a member may believe about the cell's relay.
     *
     * Three ways a claim is worthless, and none of them can change who the
     * owner is — that is decided by `controllerId`, which is canonical and
     * fenced independently:
     *
     *  - it belongs to a lease the cell has moved past;
     *  - it was stamped by somebody who is not the controller;
     *  - it describes more radio than the protocol allows, which is clamped
     *    rather than believed.
     */
    fun observedRelay(snapshot: BoardCellSnapshot?): BoardCellRelayState {
        val cell = snapshot ?: return BoardCellRelayState.NONE
        val claim = cell.relay
        if (!claim.isCurrentFor(cell)) return BoardCellRelayState.NONE
        // A cell with no controller has nobody entitled to stamp one.
        if (cell.controllerId.isBlank()) return BoardCellRelayState.NONE
        return claim.sanitized(RADIO_BUDGET)
    }

    /**
     * Whether a guest's write may be reported as landed.
     *
     * Only a healthy board path can honestly answer yes. During recovery the
     * guest's app has already been told its write succeeded by the GATT layer;
     * acknowledging it here as well would make a dark wall look like a
     * delivered climb.
     */
    fun mayAcknowledgeWrite(boardHealth: RelayBoardLinkHealth): Boolean =
        boardHealth == RelayBoardLinkHealth.HEALTHY
}
