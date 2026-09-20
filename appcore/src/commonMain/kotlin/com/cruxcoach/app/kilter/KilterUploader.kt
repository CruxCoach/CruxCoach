package com.cruxcoach.app.kilter

import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid

/** What a push to the Kilter portal did. */
class KilterPushOutcome(
    val uploaded: Int,
    val pending: Int,
    val failure: KilterFailure,
    /** True when no log could name a gym, wall and layout. */
    val missingWallContext: Boolean = false,
)

/**
 * Pushes this device's own ascents and attempts to the Kilter portal, port of
 * the upload half of Android's `KilterSyncEngine`.
 *
 * Two rules carry over unchanged because they protect the user's data:
 *
 *  - the portal needs a gym, wall and layout for every log, and the only
 *    trustworthy source is a log the user already has up there. Guessing one
 *    would file the session against the wrong wall, so a missing context stops
 *    the push instead.
 *  - a row is stamped as synced only if its `row_version` still matches the
 *    one read before the upload. An edit made while the request was in flight
 *    therefore re-uploads later rather than being silently lost.
 */
class KilterUploader(
    private val api: KilterApi,
    private val tokens: KilterTokens,
    private val repository: PersonalBoardRepository,
) {
    suspend fun push(): KilterPushOutcome {
        val ascents = repository.getUnsyncedAscents()
        val bids = repository.getUnsyncedBids()
        val pending = ascents.size + bids.size
        if (pending == 0) return KilterPushOutcome(0, 0, KilterFailure.NONE)

        val userUuid = tokens.userUuid().ifBlank {
            return KilterPushOutcome(0, pending, KilterFailure.NOT_SIGNED_IN)
        }
        val remote = api.fetchLogs()
        if (remote.failure != KilterFailure.NONE) {
            return KilterPushOutcome(0, pending, remote.failure)
        }
        val context = api.wallContextFromLogs(remote.logs)
            ?: return KilterPushOutcome(0, pending, KilterFailure.NONE, missingWallContext = true)

        val logs = buildLogs(userUuid, context, ascents, bids)
        val outcome = api.uploadLogs(logs, remote = remote.logs)
        if (outcome.failure != KilterFailure.NONE) {
            return KilterPushOutcome(0, pending, outcome.failure)
        }
        // Optimistic stamp, guarded by the version read above.
        var stamped = 0
        for (ascent in ascents) {
            if (repository.markAscentSyncedIfUnchanged(ascent.uuid, ascent.rowVersion)) stamped++
        }
        for (bid in bids) {
            if (repository.markBidSyncedIfUnchanged(bid.uuid, bid.rowVersion)) stamped++
        }
        return KilterPushOutcome(outcome.uploaded, pending - stamped, KilterFailure.NONE)
    }

    companion object {
        /**
         * The logs the portal would receive. Pure, so the mapping is tested
         * without a network: an ascent is topped, a bid is not, and a send on
         * the first go is a flash.
         */
        internal fun buildLogs(
            userUuid: String,
            context: KilterWallContext,
            ascents: List<RawAscent>,
            bids: List<RawBid>,
        ): List<KilterLog> = ascents.map { ascent ->
            KilterLog(
                logUuid = ascent.uuid,
                userUuid = userUuid,
                climbUuid = ascent.climbUuid,
                gymUuid = ascent.gymUuid ?: context.gymUuid,
                wallUuid = ascent.wallUuid ?: context.wallUuid,
                productLayoutUuid = ascent.productLayoutUuid ?: context.productLayoutUuid,
                angle = ascent.angle.toInt(),
                flashed = ascent.bidCount <= 1L,
                topped = true,
                attempts = ascent.bidCount.toInt().coerceAtLeast(1),
                createdAt = ensureUtcSuffix(ascent.climbedAt),
                comment = ascent.comment,
            )
        } + bids.map { bid ->
            KilterLog(
                logUuid = bid.uuid,
                userUuid = userUuid,
                climbUuid = bid.climbUuid,
                gymUuid = bid.gymUuid ?: context.gymUuid,
                wallUuid = bid.wallUuid ?: context.wallUuid,
                productLayoutUuid = bid.productLayoutUuid ?: context.productLayoutUuid,
                angle = bid.angle.toInt(),
                flashed = false,
                topped = false,
                attempts = bid.bidCount.toInt().coerceAtLeast(1),
                createdAt = ensureUtcSuffix(bid.climbedAt),
                comment = bid.comment,
            )
        }

        /**
         * The app stores `YYYY-MM-DD HH:MM:SS` local time; the portal parses
         * an instant and rejects anything without a zone.
         */
        internal fun ensureUtcSuffix(timestamp: String): String = when {
            timestamp.endsWith("Z") -> timestamp
            timestamp.isBlank() -> timestamp
            else -> timestamp.replace(' ', 'T') + "Z"
        }
    }
}
