package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand

/**
 * What happens to a climb another CruxCoach user relays to this device.
 *
 * The relay is a guest writing to somebody else's board through their phone,
 * so it is the one inbound path where the sender is not the person standing in
 * front of the wall. It gets the same sequencer as every local command — no
 * private route to the board — plus the three checks a remote sender needs and
 * a local one does not: not the same climb twice, not faster than a wall can
 * be climbed, and not a climb from a different board.
 *
 * Deliberately free of BLE, DataStore and coroutines so the rules can be read
 * and tested as rules.
 */
class RelayInboundGate(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val duplicateWindowMs: Long = DUPLICATE_WINDOW_MS,
) {
    /** Why an inbound relay climb did not reach the wall. */
    enum class Refusal {
        /** The same climb again, within the window a re-send lands in. */
        DUPLICATE,

        /** Arriving faster than anybody could be climbing them. */
        RATE_LIMITED,

        /** A climb for a different board family than the one on the link. */
        BOARD_MISMATCH,
    }

    sealed interface Decision {
        /** Write it to the wall and record it as the current occurrence. */
        data object ProjectNow : Decision

        /** Queue it at the end; the wall keeps what it has. */
        data object AppendToEnd : Decision

        data class Refused(val reason: Refusal) : Decision
    }

    private var lastAcceptedKey: String? = null

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
     */
    fun evaluate(
        mode: RelayInboundClimbMode,
        climbUuid: String?,
        angle: Int?,
        climbBrand: BoardBrand?,
        connectedBrand: BoardBrand?,
        nowMs: Long,
    ): Decision {
        if (climbUuid == null || angle == null) return Decision.ProjectNow

        // A climb the connected board cannot show is not a climb this board's
        // group should be told is on the wall.
        if (climbBrand != null && connectedBrand != null && climbBrand != connectedBrand) {
            return Decision.Refused(Refusal.BOARD_MISMATCH)
        }

        val key = "${climbUuid.lowercase()}@$angle"
        val sinceLast = lastAcceptedAtMs?.let { nowMs - it }
        if (sinceLast != null) {
            if (key == lastAcceptedKey && sinceLast < duplicateWindowMs) {
                return Decision.Refused(Refusal.DUPLICATE)
            }
            // A different climb still cannot arrive faster than the wall can be
            // used. Guest apps retry, and a retry storm would otherwise be a
            // stream of occurrences nobody put there.
            if (sinceLast < minIntervalMs) return Decision.Refused(Refusal.RATE_LIMITED)
        }

        lastAcceptedKey = key
        lastAcceptedAtMs = nowMs
        return when (mode) {
            RelayInboundClimbMode.PROJECT_NOW -> Decision.ProjectNow
            RelayInboundClimbMode.APPEND_TO_END -> Decision.AppendToEnd
        }
    }

    /** A new relay session starts with no history to compare against. */
    fun reset() {
        lastAcceptedKey = null
        lastAcceptedAtMs = null
    }

    private companion object {
        /** Two identical writes this close together are one intention. */
        const val DUPLICATE_WINDOW_MS = 10_000L

        /** Long enough to read a wall, short enough not to feel throttled. */
        const val MIN_INTERVAL_MS = 1_500L
    }
}
