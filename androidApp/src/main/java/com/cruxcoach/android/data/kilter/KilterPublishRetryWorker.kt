package com.cruxcoach.android.data.kilter

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Drains the Kilter publish-retry queue.
 *
 * Picks up rows where:
 *   - origin = 'cruxcoach'
 *   - sync_status = 'published_nostr' (Nostr publish already succeeded)
 *   - kilter_status IS NULL OR 'failed' (Kilter side never landed)
 *
 * For each, attempts a fresh `POST /api/climbs/create-climb/transaction`
 * via [KilterApiClient.publishClimb] (idempotent: same climb_uuid as the
 * Nostr event, so re-attempts after partial state are safe). Updates the
 * `kilter_status` flags accordingly.
 *
 * Bundled-fallback retry is intentionally NOT covered by this worker —
 * it would need to re-sign a Nostr event for the bundled service, and
 * the user can always re-publish manually from the editor if needed.
 * For the self path the user's Keycloak token is enough; this is the
 * only retry mode v0.1.4 ships.
 */
@HiltWorker
class KilterPublishRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val boardRepository: BoardRepository,
    private val apiClient: KilterApiClient,
    private val tokenStore: KilterTokenStore,
    private val userPreferences: UserPreferences,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        if (!userPreferences.kilterClimbPublishEnabled.first()) {
            Log.i(TAG, "skip: user opt-out (kilterClimbPublishEnabled=false)")
            return androidx.work.ListenableWorker.Result.success()
        }
        // Same persistent-connection gate as `KilterClimbPublisher.submit`.
        // A leftover token from a failed one-time-import run must not become
        // a silent auto-push retry channel.
        if (!userPreferences.kilterSyncEnabled.first()) {
            Log.i(TAG, "skip: not persistently connected (kilterSyncEnabled=false)")
            return androidx.work.ListenableWorker.Result.success()
        }
        // No Kilter login → can't retry the self path. Bundled retry isn't
        // supported (see class kdoc). Nothing to do.
        if (tokenStore.getAccessToken() == null) {
            Log.i(TAG, "skip: no kilter access token")
            return androidx.work.ListenableWorker.Result.success()
        }

        Log.i(TAG, "worker start (publishEnabled=true, hasToken=true)")

        // Recover stuck-'pending' rows before listing the queue.
        // CommunityClimbPublisher's try/catch already downgrades on a
        // mid-flow throw, but the residual case where even the catch
        // path lost (process kill mid-mark, OOM in the SQLite driver)
        // leaves a row stranded in 'pending'. The queue criterion
        // matches NULL/'failed', so 'pending' rows are invisible to the
        // retry worker without this sweep.
        val stuckCutoffMs = System.currentTimeMillis() - STUCK_PENDING_GRACE_MS
        val swept = runCatching { boardRepository.sweepStuckKilterPending(stuckCutoffMs) }
            .onFailure { Log.w(TAG, "sweepStuckKilterPending threw", it) }
            .getOrDefault(0L)
        if (swept > 0) Log.i(TAG, "swept $swept stuck-pending row(s) back to 'failed'")

        val rows = runCatching { boardRepository.getClimbsAwaitingKilterRetry() }
            .getOrElse {
                Log.w(TAG, "could not list retry candidates", it)
                return androidx.work.ListenableWorker.Result.retry()
            }
        if (rows.isEmpty()) {
            Log.i(TAG, "no candidates")
            return androidx.work.ListenableWorker.Result.success()
        }

        val sizeId = userPreferences.boardProductSizeId.first()
        val boardSize = runCatching { boardRepository.getProductSize(sizeId) }.getOrNull()
            ?: return androidx.work.ListenableWorker.Result.retry()  // come back when board metadata is loaded

        var attempted = 0
        var ok = 0
        var transient = 0
        var permanent = 0
        var rowErrors = 0
        for (row in rows) {
            attempted++
            // Per-row try/catch: a single throw (parseFrames on corrupted
            // hex, SQLite lock during a mark-* call, OkHttp socket-close)
            // must not abort the rest of the batch — the next 6-hour tick
            // would otherwise be the soonest any other queued row gets
            // retried.
            try {
            // Resolve placementId → holeId so the API receives valid Kilter
            // hole_ids (see encodeClimbConcat docstring). Cached per-batch:
            // 692 rows is cheap and the loop stays tight.
            val pidToHoleId: Map<Int, Long> = runCatching { boardRepository.getAllPlacements() }
                .getOrDefault(emptyList())
                .associate { it.placementId.toInt() to it.holeId }
            val climbConcat = BoardClimbParser.encodeClimbConcat(
                BoardClimbParser.parseFrames(row.framesText),
                pidToHoleId,
            )
            if (climbConcat.isBlank()) {
                // Frames missing — mark permanently failed so we don't loop.
                boardRepository.markKilterPublishFailed(row.uuid, "frames empty on retry")
                continue
            }
            // Atomic CAS-claim: prevents racing the editor's
            // CommunityClimbPublisher.publish path. If a manual publish
            // is mid-flight for this uuid the worker skips the row;
            // it'll get re-queued on the next tick if the publish ended
            // in 'failed'/NULL.
            val claim = boardRepository.claimKilterPublishSlot(row.uuid)
            if (claim is com.cruxcoach.data.repository.KilterClaim.Lost) {
                Log.i(TAG, "row claim lost — another flow is publishing uuid=${row.uuid}; skip")
                continue
            }
            // Pick endpoint by the claim's pre-state — same column the
            // SELECT-snapshot `row.kilterSyncedAt` carries, but read
            // inside the CAS transaction so it's authoritative against
            // any racing UPDATE between getClimbsAwaitingKilterRetry
            // and now. A row with kilter_synced_at set was previously
            // accepted by Kilter (use update-climb); otherwise CREATE.
            val isUpdate = (claim as com.cruxcoach.data.repository.KilterClaim.Won)
                .previouslySyncedAtEpochSeconds != null
            // Angle from per-climb stats; 40° fallback if the stats row
            // hasn't materialized yet (e.g. row written but stats insert
            // failed mid-flow). The Kilter API requires a non-null angle.
            val angle = (boardRepository.getOwnClimbAngle(row.uuid) ?: 40L).toInt()
            val productLayoutUuid = boardSize.id.toString()
            val result = if (isUpdate) {
                apiClient.updateClimb(
                    climbUuid = row.uuid,
                    name = row.name,
                    description = row.description,
                    framesClimbConcat = climbConcat,
                    productName = productNameForLayout(row.layoutId),
                    productLayoutUuid = productLayoutUuid,
                    angle = angle,
                    edgeLeft = boardSize.edgeLeft.toInt(),
                    edgeRight = boardSize.edgeRight.toInt(),
                    edgeBottom = boardSize.edgeBottom.toInt(),
                    edgeTop = boardSize.edgeTop.toInt(),
                )
            } else {
                apiClient.publishClimb(
                    climbUuid = row.uuid,
                    name = row.name,
                    description = row.description,
                    framesClimbConcat = climbConcat,
                    productName = productNameForLayout(row.layoutId),
                    productLayoutUuid = productLayoutUuid,
                    angle = angle,
                    edgeLeft = boardSize.edgeLeft.toInt(),
                    edgeRight = boardSize.edgeRight.toInt(),
                    edgeBottom = boardSize.edgeBottom.toInt(),
                    edgeTop = boardSize.edgeTop.toInt(),
                )
            }
            val opEnum = if (isUpdate) com.cruxcoach.data.repository.KilterPublishOp.UPDATE
                         else com.cruxcoach.data.repository.KilterPublishOp.CREATE
            val attemptedAt = System.currentTimeMillis()
            when (result) {
                is KilterPublishResult.Success -> {
                    ok++
                    Log.i(TAG, "row ok uuid=${row.uuid} via=${if (isUpdate) "update" else "create"}")
                    boardRepository.markKilterPublishSynced(
                        uuid = row.uuid,
                        via = "self",
                        syncedAtEpochSeconds = attemptedAt / 1000,
                    )
                    recordAttempt(row.uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.SUCCESS, null, null)
                }
                is KilterPublishResult.NotAuthenticated -> {
                    Log.i(TAG, "auth missing mid-batch — abort, will resume next run")
                    recordAttempt(row.uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.AUTH, null, "token expired")
                    return androidx.work.ListenableWorker.Result.success()
                }
                is KilterPublishResult.TransientError -> {
                    transient++
                    Log.w(TAG, "row transient uuid=${row.uuid}: ${result.message.take(200)}")
                    boardRepository.markKilterPublishFailed(row.uuid, "retry transient: ${result.message}")
                    recordAttempt(row.uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.TRANSIENT, null, result.message)
                }
                is KilterPublishResult.RateLimited -> {
                    transient++
                    Log.w(
                        TAG,
                        "row rate-limited uuid=${row.uuid} " +
                            "retryAfter=${result.retryAfterSeconds ?: "n/a"}",
                    )
                    boardRepository.markKilterPublishFailed(
                        row.uuid,
                        "retry rate-limited (retry-after=${result.retryAfterSeconds ?: "n/a"}): ${result.message}",
                    )
                    recordAttempt(row.uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.RATE_LIMITED, 429, result.message)
                }
                is KilterPublishResult.PermanentError -> {
                    permanent++
                    Log.w(
                        TAG,
                        "row permanent uuid=${row.uuid} http=${result.httpCode} update=$isUpdate: " +
                            result.message.take(200),
                    )
                    // For UPDATE flow a 4xx is the diverged-signal: the
                    // server won't accept this edit. For CREATE a 4xx
                    // means the payload itself is rejected (validation,
                    // content-policy, account state). Both are terminal:
                    // 'diverged' for update, 'rejected' for create. Pre-fix
                    // CREATE 4xx fell back to 'failed' which kept the row
                    // in the retry queue forever — this stops that.
                    if (isUpdate) {
                        boardRepository.markKilterPublishDiverged(
                            row.uuid,
                            "retry http=${result.httpCode}: ${result.message.take(200)}",
                        )
                    } else {
                        boardRepository.markKilterPublishRejected(
                            row.uuid,
                            "retry http=${result.httpCode}: ${result.message.take(200)}",
                        )
                    }
                    recordAttempt(row.uuid, attemptedAt, opEnum,
                        com.cruxcoach.data.repository.KilterPublishOutcomeKind.PERMANENT,
                        result.httpCode, result.message)
                }
            }
            } catch (e: CancellationException) {
                // Cooperate with WorkManager cancellation — without this
                // re-throw, Kotlin's CancellationException (which extends
                // Exception) gets absorbed below and the worker marks
                // every remaining row as 'failed' with bogus excerpts and
                // returns Result.success(), masking the cancel signal.
                throw e
            } catch (e: Exception) {
                rowErrors++
                Log.w(TAG, "row threw uuid=${row.uuid}; continuing batch", e)
                runCatching {
                    boardRepository.markKilterPublishFailed(row.uuid, "row threw: ${e.message?.take(200)}")
                }
            }
        }

        Log.i(
            TAG,
            "retry batch done — attempted=$attempted ok=$ok transient=$transient " +
                "permanent=$permanent rowErrors=$rowErrors",
        )
        // If everything was transient, ask WorkManager to retry the batch
        // sooner than the next scheduled tick (still subject to backoff).
        return if (transient > 0 && transient == attempted) {
            androidx.work.ListenableWorker.Result.retry()
        } else {
            androidx.work.ListenableWorker.Result.success()
        }
    }

    private fun recordAttempt(
        uuid: String,
        attemptedAtMs: Long,
        op: com.cruxcoach.data.repository.KilterPublishOp,
        outcome: com.cruxcoach.data.repository.KilterPublishOutcomeKind,
        httpCode: Int?,
        errorExcerpt: String?,
    ) {
        runCatching {
            boardRepository.recordKilterPublishAttempt(
                climbUuid = uuid,
                attemptedAtMs = attemptedAtMs,
                op = op,
                via = "self",
                outcome = outcome,
                httpCode = httpCode,
                errorExcerpt = errorExcerpt?.take(200),
            )
        }.onFailure { Log.w(TAG, "recordKilterPublishAttempt threw for uuid=$uuid", it) }
    }

    /** Same mapping as KilterClimbPublisher.productNameFor. Kept duplicated
     *  rather than extracted because the retry-worker module would
     *  otherwise need to depend on the publisher class purely for one
     *  pure-function lookup. */
    private fun productNameForLayout(layoutId: Long): String = when (layoutId) {
        com.cruxcoach.android.data.BoardConstants.KILTER_HOMEWALL_LAYOUT.toLong() -> "Kilter Board Homewall"
        else -> "Kilter Board Original"
    }

    companion object {
        private const val TAG = "KilterRetryWorker"
        const val WORK_NAME = "kilter_publish_retry"
        // Distinct from WORK_NAME — see runOnce's docstring for why.
        const val ONESHOT_WORK_NAME = "kilter_publish_retry_oneshot"
        // Pending rows older than this cutoff (relative to the latest
        // attempt OR row creation when no attempt is recorded) are swept
        // back to 'failed' on each tick. 30 minutes is generous enough
        // that a legitimate in-flight publish (P99 < 10s end-to-end)
        // never gets reset, but tight enough that a stranded row
        // recovers within the next tick or two.
        private const val STUCK_PENDING_GRACE_MS = 30L * 60L * 1000L

        /**
         * Schedule the periodic retry. Uses the shared "publish-retry"
         * cadence: 6 hours, network-required, no foreground service. The
         * actual upload latency is bounded by the daily tick at worst —
         * Kilter publishing isn't time-critical.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KilterPublishRetryWorker>(
                6L, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Manual one-shot trigger — useful right after the user toggles
         *  the publish setting on, or after restoring connectivity.
         *
         *  Uses a DISTINCT unique name from [WORK_NAME] (the periodic
         *  cadence). Reason: WorkManager's `enqueueUniqueWork` against a
         *  name already held by a PeriodicWorkRequest is a documented
         *  edge case — across versions the request has been silently
         *  ignored, silently replaced, or queued depending on the
         *  policy and the WM internal state, with no log on the dropped
         *  path. Empirically the user's "Retry now" tap was a no-op
         *  with no JobScheduler entry created, despite the click
         *  reaching this entry point.
         *
         *  Race-with-periodic protection comes from the row-level
         *  CAS-claim in `claimKilterPublishSlot` (publisher + worker
         *  contend on the same atomic SQLite update; loser logs and
         *  skips), so two workers running concurrently can't double-
         *  publish the same row even if WorkManager schedules them
         *  in parallel. */
        fun runOnce(context: Context) {
            Log.i(TAG, "runOnce: enqueueing one-shot retry")
            val request = androidx.work.OneTimeWorkRequestBuilder<KilterPublishRetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_WORK_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
