package com.cruxcoach.android.data

import com.cruxcoach.android.boardcell.BoardPlaylistEntryId
import com.cruxcoach.android.boardcell.BoardRelayOperation
import com.cruxcoach.android.boardcell.BoardPlaylistState
import java.security.MessageDigest
import java.util.Locale

/**
 * The identity of one relayed guest write.
 *
 * Two things have to be true at once, and the first two attempts each got one
 * of them:
 *
 *  - **A retry is the same operation.** A guest's app re-sending, a reconnect,
 *    or a controller handover mid-write must all converge on one occurrence.
 *    Minting the id locally (pass 6) failed this: the successor after a
 *    handover has its own manager and would mint a second one.
 *  - **A new intention is a new operation.** Two guests sending the same climb
 *    are two people asking for it, and one guest sending it again an hour
 *    later is asking for another go. Deriving the id from the content (pass 7)
 *    failed this: identical bytes collapsed into one occurrence forever.
 *
 * So the id is a **nonce, minted once per intention**, and the *record* of that
 * intention is replicated in canonical state. A controller that has never seen
 * the write looks the intention up in the shared playlist and reuses its ids;
 * a write with no matching intention starts a new one.
 *
 * The fingerprint is what "the same write" means — the cell, the climb, the
 * angle and the hold data — and the guest key is what "the same person" means.
 * Both are needed: without the guest, two people collapse into one occurrence;
 * without the content, a guest's *second, different* climb would be mistaken
 * for a retry of their first.
 */
object RelayIngressIdentity {

    /**
     * How long an intention stays open, landed or not.
     *
     * Past this, a write that looks identical is somebody asking again rather
     * than the same request arriving twice, and guessing wrong in that
     * direction is the harmless one: an occurrence somebody wanted.
     *
     * It bounds an *unlanded* record too, which is the case that matters when
     * a terminal commit does not make it: without the bound the request stays
     * open forever and every later send by that guest is folded back into it.
     */
    const val INTENT_TTL_MS = 10 * 60_000L

    /**
     * How long a *delivered* request stays recognisable at all.
     *
     * Long enough for a guest whose success answer was lost to re-send — with
     * or without a reconnect, since the BLE stack takes seconds, not minutes —
     * and short enough that the same bytes later are the person asking again.
     *
     * It bounds the same-guest case and the reconnect case alike, and that
     * symmetry is the point. Bounding only the reconnect meant a guest on an
     * unchanged address had their *delivered* request replayed for the full
     * [INTENT_TTL_MS]: the official apps re-send the same climb on every
     * re-light and every angle change, so a deliberate re-light minutes later
     * was answered "already delivered" and the wall — which had moved on to
     * somebody else's climb in the meantime — was never written.
     */
    const val DELIVERED_REPLAY_MS = 30_000L

    /**
     * What the wire and the hash carry for one guest write.
     *
     * Deliberately not the raw address: a hash of it is enough to tell two
     * guests apart, and the cell has no business replicating the BLE addresses
     * of people who are not even in it.
     */
    fun fingerprint(cellId: String, climbUuid: String, angle: Int, framesHash: Long): String =
        digest("relay|$cellId|${climbUuid.lowercase(Locale.ROOT)}|$angle|$framesHash").take(32)

    /**
     * The same, for a write whose climb has no name.
     *
     * An unlisted climb, a board-clear, a MoonBoard byte stream: the content is
     * all there is to go on, so that is what the fingerprint is over. It does
     * the one job the fingerprint has — making a retry recognisable as the same
     * request — and the ids themselves stay nonces, so identical bytes sent
     * again after the window are a new intention rather than the same one for
     * ever. The previous shape derived nothing at all and stamped the clock
     * into the ids, which made every retry a fresh operation and every fresh
     * operation another board write.
     */
    fun anonymousFingerprint(cellId: String, contentHash: Long): String =
        digest("relay-anon|$cellId|$contentHash").take(32)

    fun guestKey(deviceAddress: String): String = digest("guest|$deviceAddress").take(16)

    /**
     * A stable 64-bit hash of raw guest bytes (FNV-1a).
     *
     * For a transport with no framing there is no `framesHash` to reuse, and
     * the fingerprint still has to be the same on a retry and on a successor
     * after a handover — so it is derived, never minted.
     */
    fun contentHash(value: ByteArray): Long {
        var hash = -0x340d631b7bdddcdbL
        for (byte in value) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= 0x100000001b3L
        }
        return hash
    }

    /**
     * The intention this write belongs to, from canonical state.
     *
     * Null when there is none — which is the signal to start one. A landed
     * record past [INTENT_TTL_MS] is deliberately *not* a match: the request it
     * recorded is finished, so an identical write after it is a new one.
     *
     * [connectedGuestKeys] is what makes a reconnect distinguishable from a
     * second guest. A central's BLE address rotates, so the same person coming
     * back looks like somebody new; but if the only open request for these
     * exact bytes belongs to a guest who is no longer attached, there is
     * nobody else it could be. Two guests both connected keep their own
     * intentions, because both keys are live and neither is adopted.
     *
     * Where it genuinely cannot be told — two matching open requests, or the
     * original guest still attached — a new intention is started. That is the
     * safe direction: an extra occurrence somebody asked for, rather than two
     * people's requests merged into one.
     */
    fun openIntent(
        playlist: BoardPlaylistState,
        fingerprint: String,
        guestKey: String,
        nowEpochMs: Long,
        connectedGuestKeys: Set<String> = emptySet(),
    ): BoardRelayOperation? {
        // Age bounds an unlanded record exactly as it bounds a landed one.
        // Treating "not finished" as "still live, forever" meant a terminal
        // commit that never landed — refused, or interrupted by a stop or a
        // handover — kept the request open indefinitely, and the same guest's
        // deliberate send an hour later was put back on the old operation's
        // ids. A request nobody has completed within the window is not a
        // request somebody is still making.
        //
        // A *delivered* record is live only for [DELIVERED_REPLAY_MS], because
        // "this request is finished" stops being the answer to the same bytes
        // long before the intention itself ages out. An unfinished one stays
        // live for the whole TTL: that is the record a handover or a slow
        // controller needs to find, and adopting it is what stops one guest tap
        // becoming two occurrences.
        fun live(record: BoardRelayOperation) =
            record.fingerprint == fingerprint &&
                nowEpochMs - record.stampedAtEpochMs < INTENT_TTL_MS &&
                (!record.landed || nowEpochMs - record.stampedAtEpochMs < DELIVERED_REPLAY_MS)

        playlist.relayOperations.firstOrNull { live(it) && it.guestKey == guestKey }
            ?.let { return it }

        // A record left by an address that is no longer attached is adoptable
        // too: a central's address rotates, so the same person coming back
        // looks like somebody new. If the only live request for these exact
        // bytes belongs to a guest who is no longer here, there is nobody else
        // it could be — and recognising it is what turns a lost answer into a
        // replayed success instead of a second occurrence with new ids.
        //
        // The landed bound is in `live` and applies here identically. Beyond
        // it an identical payload from somebody who was not here is a new
        // intention, and replaying a stranger's occurrence at them would lose
        // theirs.
        val orphaned = playlist.relayOperations.filter {
            live(it) && it.guestKey !in connectedGuestKeys
        }
        // Exactly one, or it is a guess rather than a deduction.
        return orphaned.singleOrNull()?.copy(guestKey = guestKey)
    }

    /** A fresh intention: random ids, so nothing about it is guessable. */
    fun newIntent(
        fingerprint: String,
        guestKey: String,
        nowEpochMs: Long,
        newId: () -> String = BoardPlaylistEntryId::random,
    ): BoardRelayOperation = BoardRelayOperation(
        fingerprint = fingerprint,
        guestKey = guestKey,
        operationId = "relay-op-${newId()}",
        entryId = "rl${newId().take(30)}",
        stampedAtEpochMs = nowEpochMs,
    )

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
