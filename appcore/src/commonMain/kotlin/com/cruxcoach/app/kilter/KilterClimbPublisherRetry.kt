package com.cruxcoach.app.kilter

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.data.repository.KilterClaim
import com.cruxcoach.data.repository.KilterPublishOp
import com.cruxcoach.data.repository.KilterPublishOutcomeKind
import com.cruxcoach.domain.board.BoardClimbParser
import kotlinx.coroutines.CancellationException

/**
 * What one retry pass did. [retrySoon] is Android's `Result.retry()`: every row
 * that was tried failed transiently, so the caller should come back sooner than
 * its regular cadence. [skipped] names why nothing was tried ("" when the queue
 * was read).
 */
class KilterClimbRetryReport(
    val skipped: String,
    val attempted: Int,
    val synced: Int,
    val transient: Int,
    val permanent: Int,
    val rowErrors: Int,
    val retrySoon: Boolean,
) {
    companion object {
        fun skipped(reason: String) = KilterClimbRetryReport(reason, 0, 0, 0, 0, 0, retrySoon = false)
    }
}

/**
 * The retry pass of Android's `KilterPublishRetryWorker.doWork`, without the
 * scheduling: the periodic trigger belongs to the background orchestrator, the
 * manual "Retry now" to [KilterPublishQueueModel].
 *
 * Drains climbs whose Nostr publish succeeded but whose Kilter side never
 * landed (`kilter_status` NULL or 'failed'), scoped to the active Nostr pubkey
 * so rows of another identity never go to this Kilter account. Each row
 * resolves its OWN Kilter product size, claims its slot atomically and maps the
 * portal's answer exactly as the publisher does.
 */
class KilterClimbPublishRetry(
    private val publisher: KilterClimbPublisher,
    private val account: KilterClimbAccount,
    private val boardRepository: BoardRepository,
    private val publishEnabled: () -> Boolean,
    private val activePubkey: () -> String?,
    private val clockMillis: () -> Long,
) {
    suspend fun run(): KilterClimbRetryReport {
        if (!publishEnabled()) return KilterClimbRetryReport.skipped("user-opted-out")
        if (!account.isSignedIn()) return KilterClimbRetryReport.skipped("no-kilter-login")
        val pubkey = activePubkey()?.takeIf { it.isNotBlank() }
            ?: return KilterClimbRetryReport.skipped("no-nostr-pubkey")

        // Rows stranded in 'pending' (process killed mid-publish) are invisible to the queue.
        runCatching { boardRepository.sweepStuckKilterPending(clockMillis() - STUCK_PENDING_GRACE_MS) }

        val rows = try {
            boardRepository.getClimbsAwaitingKilterRetry(pubkey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return KilterClimbRetryReport("queue-unreadable", 0, 0, 0, 0, 0, retrySoon = true)
        }
        if (rows.isEmpty()) return KilterClimbRetryReport.skipped("")

        var attempted = 0
        var ok = 0
        var transient = 0
        var permanent = 0
        var rowErrors = 0
        // placementId -> holeId once per batch: the portal wants hole ids.
        val pidToHoleId: Map<Int, Long> by lazy {
            runCatching { boardRepository.getAllPlacements() }.getOrDefault(emptyList())
                .associate { it.placementId.toInt() to it.holeId }
        }
        for (row in rows) {
            attempted++
            // One row's throw must not cost every later row its retry.
            try {
                val boardSize = resolveRowBoardSize(row)
                if (boardSize == null) {
                    boardRepository.markKilterPublishFailed(row.uuid, "no product size resolvable for layout=${row.layoutId}")
                    continue
                }
                val climbConcat = BoardClimbParser.encodeClimbConcat(BoardClimbParser.parseFrames(row.framesText), pidToHoleId)
                if (climbConcat.isBlank()) {
                    boardRepository.markKilterPublishFailed(row.uuid, "frames empty on retry")
                    continue
                }
                val claim = boardRepository.claimKilterPublishSlot(row.uuid)
                if (claim !is KilterClaim.Won) continue
                val isUpdate = claim.previouslySyncedAtEpochSeconds != null
                val angle = (boardRepository.getOwnClimbAngle(row.uuid) ?: 40L).toInt()
                val result = publisher.sendClimb(
                    isUpdate = isUpdate,
                    uuid = row.uuid,
                    name = row.name,
                    description = row.description,
                    framesClimbConcat = climbConcat,
                    layoutId = row.layoutId,
                    boardSize = boardSize,
                    angle = angle,
                )
                val op = if (isUpdate) KilterPublishOp.UPDATE else KilterPublishOp.CREATE
                val attemptedAt = clockMillis()
                when (result) {
                    is KilterPublishResult.Success -> {
                        ok++
                        boardRepository.markKilterPublishSynced(row.uuid, "self", attemptedAt / 1000)
                        record(row.uuid, attemptedAt, op, KilterPublishOutcomeKind.SUCCESS, null, null)
                    }
                    is KilterPublishResult.NotAuthenticated -> {
                        // Android leaves the claimed row 'pending' here; the sweep
                        // returns it to the queue after the grace period.
                        record(row.uuid, attemptedAt, op, KilterPublishOutcomeKind.AUTH, null, "token expired")
                        return KilterClimbRetryReport("auth-lost", attempted, ok, transient, permanent, rowErrors, retrySoon = false)
                    }
                    is KilterPublishResult.TransientError -> {
                        transient++
                        boardRepository.markKilterPublishFailed(row.uuid, "retry transient: ${result.message}")
                        record(row.uuid, attemptedAt, op, KilterPublishOutcomeKind.TRANSIENT, null, result.message)
                    }
                    is KilterPublishResult.RateLimited -> {
                        transient++
                        boardRepository.markKilterPublishFailed(
                            row.uuid,
                            "retry rate-limited (retry-after=${result.retryAfterSeconds ?: "n/a"}): ${result.message}",
                        )
                        record(row.uuid, attemptedAt, op, KilterPublishOutcomeKind.RATE_LIMITED, 429, result.message)
                    }
                    is KilterPublishResult.PermanentError -> {
                        permanent++
                        val excerpt = "retry http=${result.httpCode}: ${result.message.take(200)}"
                        if (isUpdate) {
                            boardRepository.markKilterPublishDiverged(row.uuid, excerpt)
                        } else {
                            boardRepository.markKilterPublishRejected(row.uuid, excerpt)
                        }
                        record(row.uuid, attemptedAt, op, KilterPublishOutcomeKind.PERMANENT, result.httpCode, result.message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rowErrors++
                runCatching { boardRepository.markKilterPublishFailed(row.uuid, "row threw: ${e.message?.take(200)}") }
            }
        }
        return KilterClimbRetryReport(
            "", attempted, ok, transient, permanent, rowErrors,
            retrySoon = transient > 0 && transient == attempted,
        )
    }

    /**
     * The climb's OWN Kilter product size, never the active board: the
     * bbox-pinned source size, else the largest size of the climb's layout that
     * has board images (a larger board always holds the climb's holds).
     */
    private fun resolveRowBoardSize(row: CommunityClimbRow): BoardSize? {
        val sourceSizeId = runCatching { boardRepository.getProductSizeForClimbRender(row.uuid) }.getOrNull()
        if (sourceSizeId != null) {
            runCatching { boardRepository.getProductSize(sourceSizeId) }.getOrNull()?.let { return it }
        }
        return runCatching { boardRepository.getProductSizesForLayout(row.layoutId.toInt()) }
            .getOrDefault(emptyList())
            .mapNotNull { sizeId -> runCatching { boardRepository.getProductSize(sizeId) }.getOrNull() }
            .maxByOrNull { (it.edgeRight - it.edgeLeft) * (it.edgeTop - it.edgeBottom) }
    }

    private fun record(
        uuid: String,
        attemptedAt: Long,
        op: KilterPublishOp,
        outcome: KilterPublishOutcomeKind,
        httpCode: Int?,
        excerpt: String?,
    ) = KilterClimbPublisher.recordAttempt(boardRepository, uuid, attemptedAt, op, outcome, httpCode, excerpt)

    companion object {
        /** Android's grace before a 'pending' row counts as stranded: 30 minutes. */
        const val STUCK_PENDING_GRACE_MS = 30L * 60L * 1000L
    }
}
