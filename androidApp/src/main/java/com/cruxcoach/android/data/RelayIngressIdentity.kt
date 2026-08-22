package com.cruxcoach.android.data

import java.security.MessageDigest
import java.util.Locale

/**
 * The identity of one relayed guest write, derived rather than remembered.
 *
 * A guest's climb becomes an operation on the board and an occurrence on the
 * shared list, and both need an id that is the same on every device that could
 * end up serving the write. A locally minted id cannot be: the successor after
 * a controller handover runs its own `CruxRelayManager` with its own ledger,
 * and would mint a second pair for the retry the guest is about to send —
 * which is exactly how one write became two occurrences.
 *
 * So the ids are a pure function of what the write *is*: the cell it is being
 * relayed into, the climb and angle it resolved to, and the hash of the hold
 * data itself. Every controller in that cell derives the same pair from the
 * same bytes, without transferring anything and without a lease to lose.
 *
 * Deliberately **not** part of the input:
 *
 *  - the guest's BLE address, which rotates and changes on a reconnect — the
 *    same person re-sending after a reconnect is the same operation;
 *  - anything local to a device, a process or a moment, which is what made the
 *    previous version look right in a test and fail in production.
 */
object RelayIngressIdentity {

    /**
     * @param cellId scopes the identity to one board's cell, so the same climb
     *   relayed to two different walls is two operations.
     * @param framesHash the reassembler's hold-data hash: insensitive to
     *   re-chunking, sensitive to a changed hold.
     */
    fun of(
        cellId: String,
        climbUuid: String,
        angle: Int,
        framesHash: Long,
    ): RelayInboundGate.Operation {
        val fingerprint = "relay|$cellId|${climbUuid.lowercase(Locale.ROOT)}|$angle|$framesHash"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fingerprint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return RelayInboundGate.Operation(
            operationId = "relay-op-${digest.substring(0, 32)}",
            // Well inside MAX_ENTRY_ID_LENGTH, and prefixed so an occurrence a
            // guest caused is recognisable in a log without a lookup.
            entryId = "rl${digest.substring(32, 62)}",
            fingerprint = fingerprint,
        )
    }
}
