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
        /** The same climb again, within the window a re-send lands in. */
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
     * Both ids are decided before the first byte goes out, and they are not
     * decided *here*: [RelayIngressIdentity] derives them from the write
     * itself, so every controller in the cell computes the same pair. This
     * class only tracks what has happened to the operation — which is state a
     * device may lose without the operation losing its identity.
     */
    data class Operation(
        val operationId: String,
        val entryId: String,
        val fingerprint: String,
    )

    sealed interface Decision {
        /** Write it to the wall and record it as the current occurrence. */
        data class ProjectNow(val operation: Operation) : Decision

        /** Queue it at the end; the wall keeps what it has. */
        data class AppendToEnd(val operation: Operation) : Decision

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
                    "relay-op-unidentified-$nowMs", "rl-unidentified-$nowMs", "unidentified",
                ),
            )
        }
        val fingerprint = operation.fingerprint

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
            return Decision.Refused(Refusal.DUPLICATE)
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
                        Decision.Refused(Refusal.DUPLICATE)
                    } else {
                        // Long enough after the fact to be somebody putting the
                        // same climb up again on purpose. The ids are the same
                        // — they describe this write, and it is the same write
                        // — so the occurrence it already has is re-lit rather
                        // than duplicated.
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
        ledger[operation.fingerprint]?.let { it.state = State.LANDED; it.updatedAtMs = nowMs }
    }

    /** The write did not land. The next attempt is a retry, not a new climb. */
    fun markFailed(operation: Operation, nowMs: Long) {
        ledger[operation.fingerprint]?.let { it.state = State.FAILED; it.updatedAtMs = nowMs }
    }

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
        ledger[operation.fingerprint] = Record(operation, State.PENDING, nowMs)
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
