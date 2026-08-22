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

        /**
         * Nothing could be established about it at all.
         *
         * Not a decodable command, or a catalogue that could not answer. A
         * write like this used to be projected regardless, with invented
         * timestamp ids and none of the checks above ever run.
         */
        UNREADABLE,

        /**
         * It can go on a wall, but it cannot go on a list — and this group
         * asked for the list.
         *
         * Under `APPEND_TO_END` the user has said inbound relay climbs do not
         * take the wall. A write with no climb identity has no occurrence to
         * queue, so honouring it would mean projecting it, which is that
         * setting's exact opposite.
         */
        NOT_QUEUEABLE,
    }

    /**
     * How far the relay got in working out what a guest sent.
     *
     * The gate needs this as an input because "unidentified" is not one
     * situation: an unlisted climb and a climb for somebody else's wall look
     * identical from here, and exactly one of them may be written to a board.
     */
    enum class Identity {
        /** A catalogue climb, named and angled: the full path. */
        NAMED,

        /**
         * Real bytes for this wall with no name — an unlisted or mirrored
         * climb, a hold set too small to identify, a board-clear. Every rule
         * that does not need a climb identity still applies to it.
         */
        ANONYMOUS,

        /**
         * A transport with no framing, forwarded byte-for-byte (MoonBoard).
         *
         * Anonymous, and additionally exempt from pacing: one command spans
         * several writes there, so a per-write rate limit would drop a
         * command's own tail rather than throttling anybody.
         */
        RAW_STREAM,

        /** LEDs this board does not have: written for a different wall. */
        FOREIGN_BOARD,

        /** Nothing decidable at all. */
        UNREADABLE,
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
     * [climbUuid] and [angle] are set only for [Identity.NAMED]. A write with
     * no climb identity cannot become an occurrence, but it is still a command
     * on somebody else's wall: it is deduplicated, paced and fenced like any
     * other, and it is refused outright when the wall it names is not this one.
     *
     * @param identity how far identification got; see [Identity]. This used to
     *   be inferred from `climbUuid == null`, which collapsed "an unlisted
     *   climb" and "a climb for a different board" into one answer — and that
     *   answer was to write the board.
     * @param operation the derived identity of this write; see
     *   [RelayIngressIdentity]. Null when there was nothing to derive one
     *   from, which is a refusal rather than a licence.
     * @param canonicallyLanded the shared playlist already shows this
     *   operation's occurrence on the wall. That is the ACK state a successor
     *   after a handover has and its local ledger does not, and believing it
     *   is what stops the wall being written a second time for a retry the
     *   previous controller had already completed.
     */
    fun evaluate(
        mode: RelayInboundClimbMode,
        identity: Identity,
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
        // What could not be established decides first, because everything
        // below assumes the write is one this board could legitimately show.
        // The previous version returned `ProjectNow` here with ids made out of
        // the clock: it skipped the board, layout and angle checks, the rate
        // limit, the ledger and the routing mode, and because the ids were
        // timestamps the guest's own retry arrived as a different operation
        // and wrote the wall again.
        when (identity) {
            Identity.FOREIGN_BOARD -> return Decision.Refused(Refusal.BOARD_MISMATCH)
            Identity.UNREADABLE -> return Decision.Refused(Refusal.UNREADABLE)
            Identity.ANONYMOUS, Identity.RAW_STREAM ->
                if (mode == RelayInboundClimbMode.APPEND_TO_END) {
                    return Decision.Refused(Refusal.NOT_QUEUEABLE)
                }
            Identity.NAMED ->
                if (climbUuid == null || angle == null) {
                    return Decision.Refused(Refusal.UNREADABLE)
                }
        }
        // No derived identity means no way to recognise the retry, and a
        // request that cannot be recognised twice is one that can be delivered
        // twice.
        if (operation == null) return Decision.Refused(Refusal.UNREADABLE)
        // One MoonBoard command spans several writes; see [Identity.RAW_STREAM].
        val paced = identity != Identity.RAW_STREAM
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
        // Only a named write has an angle to compare. An anonymous one is not
        // "at the wrong angle"; it is at no stated angle at all.
        if (angle != null && connectedAngle != null && angle != connectedAngle) {
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
                    decisionFor(mode, record.operation, identity)
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
                        start(mode, operation, nowMs, paced, identity)
                    }
            }
        }

        // A climb nobody has seen before still cannot arrive faster than the
        // wall can be used. Guest apps retry, and a retry storm would otherwise
        // be a stream of occurrences nobody put there.
        if (paced) {
            lastAcceptedAtMs?.let {
                if (nowMs - it < minIntervalMs) return Decision.Refused(Refusal.RATE_LIMITED)
            }
        }
        return start(mode, operation, nowMs, paced, identity)
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
        paced: Boolean,
        identity: Identity,
    ): Decision {
        ledger[keyOf(operation)] = Record(operation, State.PENDING, nowMs)
        if (paced) lastAcceptedAtMs = nowMs
        return decisionFor(mode, operation, identity)
    }

    /**
     * A write with no climb identity has no occurrence to queue, so the only
     * routing left for it is the wall — and `APPEND_TO_END` has already
     * refused it above rather than quietly projecting it anyway.
     */
    private fun decisionFor(
        mode: RelayInboundClimbMode,
        operation: Operation,
        identity: Identity,
    ): Decision = when (mode) {
        RelayInboundClimbMode.PROJECT_NOW -> Decision.ProjectNow(operation)
        RelayInboundClimbMode.APPEND_TO_END ->
            if (identity == Identity.NAMED) Decision.AppendToEnd(operation)
            else Decision.Refused(Refusal.NOT_QUEUEABLE)
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
