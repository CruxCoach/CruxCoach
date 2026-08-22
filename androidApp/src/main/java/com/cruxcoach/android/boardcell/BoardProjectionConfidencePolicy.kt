package com.cruxcoach.android.boardcell

/**
 * What the wall is showing, and how well this device actually knows it.
 *
 * [projection] is the canonical claim; [confidence] is what that claim is
 * worth. The two are kept together because a climb name on its own invites
 * exactly the mistake this exists to prevent — reading "sent" as "confirmed"
 * on a board that cannot confirm anything.
 */
data class BoardProjectionStatus(
    val confidence: BoardProjectionConfidence,
    val projection: BoardProjection?,
    val pending: BoardPlaylistPendingProjection? = null,
    val inFlight: BoardProjection? = null,
) {
    /** The wall is showing this occurrence's climb, whoever put it there. */
    fun shows(entry: BoardPlaylistEntry?): Boolean =
        entry != null && projection?.climbUuid == entry.climbUuid &&
            projection.angle == entry.angle &&
            confidence != BoardProjectionConfidence.UNKNOWN

    /**
     * What is claimed about one occurrence, or null when nothing is.
     *
     * Deliberately separate from [confidence], which is about the wall. After a
     * failed send those are two different facts about two different
     * occurrences — the wall still shows the confirmed current, and the one
     * behind it did not get there — and a UI that collapses them either
     * misreports the wall or hides the failure.
     */
    fun confidenceFor(entry: BoardPlaylistEntry?): BoardProjectionConfidence? {
        if (entry == null) return null
        if (pending?.entryId == entry.entryId) return BoardProjectionConfidence.FAILED
        if (inFlight?.climbUuid == entry.climbUuid && inFlight.angle == entry.angle) {
            return BoardProjectionConfidence.PENDING
        }
        if (!shows(entry)) return null
        return confidence
    }

    companion object {
        val UNKNOWN = BoardProjectionStatus(BoardProjectionConfidence.UNKNOWN, null)
    }
}

/**
 * Derives [BoardProjectionConfidence] from state that is already canonical.
 *
 * Pure, and deliberately not transmitted: every input is either in the
 * snapshot or is this device's own in-flight write, so two members reading the
 * same snapshot agree about the wall without a protocol version for it. The
 * one local input — a write this device has out and unanswered — is the only
 * thing a peer genuinely cannot know, and it is also the only state that must
 * never be replicated: a member showing somebody else's "sending" would be
 * inventing progress it has no way to observe.
 */
object BoardProjectionConfidencePolicy {

    /**
     * Whether a controller readback names the climb the cell says is up there.
     *
     * [authoritative] matters as much as the ids: an empty route list this
     * device has never actually been told is not evidence that the wall is
     * empty, and treating it as evidence would turn every fresh connection
     * into a confident "not confirmed".
     */
    fun readbackNames(
        projection: BoardProjection?,
        authoritative: Boolean,
        heldRouteIds: Collection<String>,
    ): Boolean {
        if (!authoritative || projection == null) return false
        return heldRouteIds.any { it.equals(projection.climbUuid, ignoreCase = true) }
    }

    /**
     * @param inFlight a projection this device has written or requested and
     *   has not been answered about yet.
     * @param readbackNamesProjection the controller was asked what it holds
     *   and named the canonical projection's climb.
     * @param brandConfirmsByReadback the connected board can be asked at all;
     *   see `BoardBrand.confirmsProjectionByControllerReadback`.
     */
    fun evaluate(
        snapshot: BoardCellSnapshot?,
        inFlight: BoardProjection? = null,
        readbackNamesProjection: Boolean = false,
        brandConfirmsByReadback: Boolean = false,
    ): BoardProjectionStatus {
        val cell = snapshot ?: return BoardProjectionStatus.UNKNOWN
        // The occurrence somebody asked for and did not get. It travels beside
        // the wall's own answer rather than replacing it: after a failed send
        // the wall is still showing the confirmed current, and reporting the
        // wall as "failed" would be as wrong as hiding the failure.
        val pending = cell.playlist.pendingProjection
        // A write on its way is about the wall, so it does outrank what the
        // wall last confirmed — that is the one claim genuinely in doubt.
        if (inFlight != null) {
            return BoardProjectionStatus(
                BoardProjectionConfidence.PENDING, inFlight, pending, inFlight,
            )
        }
        val projection = cell.projection
        if (!cell.projectionKnown || projection == null) {
            return BoardProjectionStatus(BoardProjectionConfidence.UNKNOWN, null, pending)
        }
        // Only a board that answers may be believed to have answered. On every
        // write-only board a completed transport is the strongest honest claim
        // there is, and calling it confirmation would be inventing a readback
        // the protocol does not have.
        val confidence =
            if (brandConfirmsByReadback && readbackNamesProjection)
                BoardProjectionConfidence.CONTROLLER_CONFIRMED
            else BoardProjectionConfidence.TRANSPORTED
        return BoardProjectionStatus(confidence, projection, pending)
    }
}
