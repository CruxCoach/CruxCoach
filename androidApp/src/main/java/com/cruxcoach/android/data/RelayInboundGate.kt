package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand

/**
 * What happens to a climb another CruxCoach user relays to this device.
 *
 * The relay is a guest writing to somebody else's board through their phone,
 * so it is the one inbound path where the sender is not the person standing in
 * front of the wall. It gets the same sequencer as every local command — no
 * private route to the board — plus the checks a remote sender needs and a
 * local one does not: not the same climb twice, not faster than a wall can be
 * climbed, and not a climb the board on the link cannot show.
 *
 * It is also where the identity of the whole operation is decided. A guest
 * write becomes exactly one operation id and one playlist occurrence id, here,
 * once — before anything is written and before anything is committed. Every
 * later step reuses them, so a retry, a controller handover or a relay restart
 * converges on the occurrence that already exists instead of minting a second
 * one for the same tap.
 *
 * Deliberately free of BLE, DataStore and coroutines so the rules can be read
 * and tested as rules.
 */
class RelayInboundGate(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val duplicateWindowMs: Long = DUPLICATE_WINDOW_MS,
    private val operationTtlMs: Long = OPERATION_TTL_MS,
) {
    /** Why an inbound relay climb did not reach the wall. */
    enum class Refusal {
        /**
         * The same climb again, within the window a re-send lands in.
         *
         * Retained for a repeat this device can neither deliver nor confirm.
         * A repeat of a write that *did* land is [Decision.AlreadyDelivered],
         * which is a success — see there for why the difference matters.
         */
        DUPLICATE,

        /** Arriving faster than anybody could be climbing them. */
        RATE_LIMITED,

        /** A climb for a different board family than the one on the link. */
        BOARD_MISMATCH,

        /** The right family, but not the layout this board is built with. */
        LAYOUT_MISMATCH,

        /** Written for an angle this board is not set to. */
        ANGLE_MISMATCH,
    }

    /**
     * One guest write, from arrival to wall.
     *
     * The ids are decided before the first byte goes out, and they are not
     * decided *here*: a nonce is minted once per intention and the record of
     * it is replicated in canonical state, so a controller that takes the
     * board over reads the same pair instead of minting a second. See
     * [RelayIngressIdentity]. This class only tracks what has happened to the
     * operation locally, which is state a device may lose without the
     * operation losing its identity.
     */
    typealias Operation = com.cruxcoach.android.boardcell.BoardRelayOperation

    sealed interface Decision {
        /** Write it to the wall and record it as the current occurrence. */
        data class ProjectNow(val operation: Operation) : Decision

        /** Queue it at the end; the wall keeps what it has. */
        data class AppendToEnd(val operation: Operation) : Decision

        /**
         * This exact request has already been delivered.
         *
         * Nothing to write and nothing to add — and it is a **success**, not a
         * refusal. A guest whose success answer was lost re-sends, and the
         * honest reply is that the climb they asked for is on the wall. Telling
         * them it failed, which is what a duplicate refusal did, invites the
         * one thing exactly-once semantics cannot survive: a retry that means
         * something different from the request it repeats.
         */
        data class AlreadyDelivered(val operation: Operation) : Decision

        data class Refused(val reason: Refusal) : Decision
    }

    /** How far an operation has got. Only [State.LANDED] is a terminal yes. */
    private enum class State { PENDING, LANDED, FAILED }

    private class Record(
        val operation: Operation,
        var state: State,
        var updatedAtMs: Long,
    )

    /**
     * Bounded, and deliberately not cleared when the relay restarts.
     *
     * The relay is torn down and rebuilt for reasons that have nothing to do
     * with the guest — a peer joining the mesh, a moment of board recovery —
     * so the ledger outlives the server and ages out on its own clock.
     *
     * It is an accelerator, not the identity: losing it (a process restart, a
     * controller handover to a device that never saw the write) costs a
     * duplicate board write at worst, because the ids are derived and the
     * canonical playlist is consulted for what already landed.
     */
    private val ledger = object : LinkedHashMap<String, Record>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Record>?) =
            size > MAX_TRACKED_OPERATIONS
    }

    /**
     * Null, not a sentinel timestamp. A "very long ago" sentinel has to be
     * subtracted from to be useful, and `now - Long.MIN_VALUE` overflows into a
     * negative interval — which read as "too soon" and refused the first climb
     * of every session.
     */
    private var lastAcceptedAtMs: Long? = null

    /**
     * [climbUuid] and [angle] are null when the write could not be identified
     * against the catalogue — a MoonBoard byte stream, or an Aurora frame no
     * catalogue climb matches. Those cannot be deduplicated or queued as an
     * occurrence, so they only ever pass straight through as an external write.
     *
     * [fingerprint] identifies the guest's write itself (who sent it and what
     * the bytes were), which is what makes a retry recognisable as the same
     * operation. [newId] supplies fresh ids and is injectable for tests.
     */
    /**
     * @param operation the derived identity of this write; see
     *   [RelayIngressIdentity]. Null only for a write that could not be
     *   identified at all, which cannot become an occurrence anyway.
     * @param canonicallyLanded the shared playlist already shows this
     *   operation's occurrence on the wall. That is the ACK state a successor
     *   after a handover has and its local ledger does not, and believing it
     *   is what stops the wall being written a second time for a retry the
     *   previous controller had already completed.
     */
    fun evaluate(
        mode: RelayInboundClimbMode,
        climbUuid: String?,
        angle: Int?,
        climbBrand: BoardBrand?,
        connectedBrand: BoardBrand?,
        nowMs: Long,
        operation: Operation?,
        climbLayoutId: Long? = null,
        connectedLayoutId: Long? = null,
        connectedAngle: Int? = null,
        canonicallyLanded: Boolean = false,
    ): Decision {
        evictExpired(nowMs)
        if (climbUuid == null || angle == null || operation == null) {
            return Decision.ProjectNow(
                operation ?: Operation(
                    fingerprint = "unidentified-$nowMs",
                    guestKey = "unidentified",
                    operationId = "relay-op-unidentified-$nowMs",
                    entryId = "rl-unidentified-$nowMs",
                ),
            )
        }
        // The ledger is keyed by the intention, not by the content: the same
        // bytes from two different guests are two intentions with two nonces.
        val fingerprint = "${operation.fingerprint}|${operation.guestKey}|${operation.entryId}"

        // A climb the connected board cannot show is not a climb this board's
        // group should be told is on the wall. Brand is the coarsest of the
        // three: the same family still comes in layouts whose holds are in
        // different places, and a board that is not at this angle is showing
        // a different problem even when every LED is right.
        if (climbBrand != null && connectedBrand != null && climbBrand != connectedBrand) {
            return Decision.Refused(Refusal.BOARD_MISMATCH)
        }
        if (climbLayoutId != null && connectedLayoutId != null && climbLayoutId != connectedLayoutId) {
            return Decision.Refused(Refusal.LAYOUT_MISMATCH)
        }
        if (connectedAngle != null && angle != connectedAngle) {
            return Decision.Refused(Refusal.ANGLE_MISMATCH)
        }

        // What the cell already knows outranks what this device happens to
        // remember. A fresh gate — new process, new controller — would
        // otherwise re-write a wall that is already showing this very climb.
        if (canonicallyLanded && ledger[fingerprint]?.state != State.FAILED) {
            ledger[fingerprint] = Record(operation, State.LANDED, nowMs)
            return Decision.AlreadyDelivered(operation)
        }
        ledger[fingerprint]?.let { record ->
            return when (record.state) {
                // Still on its way, or it already failed: both are the same
                // operation and both reuse its ids. A retry of a failed write
                // is exactly what a guest whose climb never lit should be able
                // to do, and refusing it as a duplicate — which is what this
                // did before — left them with no way to try again.
                State.PENDING, State.FAILED -> {
                    record.state = State.PENDING
                    record.updatedAtMs = nowMs
                    decisionFor(mode, record.operation)
                }
                State.LANDED ->
                    if (nowMs - record.updatedAtMs < duplicateWindowMs) {
                        // The same request again, and it is already on the
                        // wall. Answering it as delivered is both true and the
                        // only answer a retrying guest can act on.
                        Decision.AlreadyDelivered(record.operation)
                    } else {
                        // Long enough after the fact to be a new intention.
                        // The caller has already decided that — it minted a new
                        // nonce rather than finding an open one — so this is a
                        // different operation with a different occurrence.
                        start(mode, operation, nowMs)
                    }
            }
        }

        // A climb nobody has seen before still cannot arrive faster than the
        // wall can be used. Guest apps retry, and a retry storm would otherwise
        // be a stream of occurrences nobody put there.
        lastAcceptedAtMs?.let { if (nowMs - it < minIntervalMs) return Decision.Refused(Refusal.RATE_LIMITED) }
        return start(mode, operation, nowMs)
    }

    /** The write reached the wall. Anything identical for a while now is a re-send. */
    fun markLanded(operation: Operation, nowMs: Long) {
        ledger[keyOf(operation)]?.let { it.state = State.LANDED; it.updatedAtMs = nowMs }
    }

    /** The write did not land. The next attempt is a retry, not a new climb. */
    fun markFailed(operation: Operation, nowMs: Long) {
        ledger[keyOf(operation)]?.let { it.state = State.FAILED; it.updatedAtMs = nowMs }
    }

    private fun keyOf(operation: Operation): String =
        "${operation.fingerprint}|${operation.guestKey}|${operation.entryId}"

    /**
     * A new relay session paces from scratch.
     *
     * The pacing clock is about this device's radio and starts again with the
     * server. What survives is the ledger: see its comment for why forgetting
     * it here is how one guest write became two occurrences.
     */
    fun reset() {
        lastAcceptedAtMs = null
    }

    private fun start(
        mode: RelayInboundClimbMode,
        operation: Operation,
        nowMs: Long,
    ): Decision {
        ledger[keyOf(operation)] = Record(operation, State.PENDING, nowMs)
        lastAcceptedAtMs = nowMs
        return decisionFor(mode, operation)
    }

    private fun decisionFor(mode: RelayInboundClimbMode, operation: Operation): Decision = when (mode) {
        RelayInboundClimbMode.PROJECT_NOW -> Decision.ProjectNow(operation)
        RelayInboundClimbMode.APPEND_TO_END -> Decision.AppendToEnd(operation)
    }

    private fun evictExpired(nowMs: Long) {
        val iterator = ledger.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().value.updatedAtMs >= operationTtlMs) iterator.remove()
        }
    }

    private companion object {
        /** Two identical writes this close together are one intention. */
        const val DUPLICATE_WINDOW_MS = 10_000L

        /** Long enough to read a wall, short enough not to feel throttled. */
        const val MIN_INTERVAL_MS = 1_500L

        /**
         * How long an operation stays recognisable.
         *
         * Past this, a retry cannot be told from somebody sending the same
         * climb again, and guessing wrong in that direction is the harmless
         * one: a second occurrence somebody asked for.
         */
        const val OPERATION_TTL_MS = 10 * 60_000L

        /** A guest cannot have more open operations than this; the rest age out. */
        const val MAX_TRACKED_OPERATIONS = 64
    }
}
