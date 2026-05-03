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
        // No Kilter login → can't retry the self path. Bundled retry isn't
        // supported (see class kdoc). Nothing to do.
        if (tokenStore.getAccessToken() == null) {
            Log.i(TAG, "skip: no kilter access token")
            return androidx.work.ListenableWorker.Result.success()
        }

        Log.i(TAG, "worker start (publishEnabled=true, hasToken=true)")

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
        for (row in rows) {
            attempted++
            val climbConcat = BoardClimbParser.encodeClimbConcat(
                BoardClimbParser.parseFrames(row.framesText)
            )
            if (climbConcat.isBlank()) {
                // Frames missing — mark permanently failed so we don't loop.
                boardRepository.markKilterPublishFailed(row.uuid, "frames empty on retry")
                continue
            }
            // Pick endpoint by prior-sync state. A row with kilterSyncedAt
            // set was previously accepted by Kilter; a subsequent retry
            // means the user edited it (kilter_status got reset to
            // 'failed' in the publish path). Use update-climb in that
            // case. A row that was never synced gets create-climb (the
            // initial publish never landed).
            val isUpdate = row.kilterSyncedAt != null
            val result = if (isUpdate) {
                apiClient.updateClimb(
                    climbUuid = row.uuid,
                    name = row.name,
                    description = row.description,
                    framesClimbConcat = climbConcat,
                    productName = "Kilter Board Original",
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
                    productName = "Kilter Board Original",
                    edgeLeft = boardSize.edgeLeft.toInt(),
                    edgeRight = boardSize.edgeRight.toInt(),
                    edgeBottom = boardSize.edgeBottom.toInt(),
                    edgeTop = boardSize.edgeTop.toInt(),
                )
            }
            when (result) {
                is KilterPublishResult.Success -> {
                    ok++
                    Log.i(TAG, "row ok uuid=${row.uuid} via=${if (isUpdate) "update" else "create"}")
                    boardRepository.markKilterPublishSynced(
                        uuid = row.uuid,
                        via = "self",
                        syncedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    )
                }
                is KilterPublishResult.NotAuthenticated -> {
                    Log.i(TAG, "auth missing mid-batch — abort, will resume next run")
                    return androidx.work.ListenableWorker.Result.success()
                }
                is KilterPublishResult.TransientError -> {
                    transient++
                    Log.w(TAG, "row transient uuid=${row.uuid}: ${result.message.take(200)}")
                    boardRepository.markKilterPublishFailed(row.uuid, "retry transient: ${result.message}")
                }
                is KilterPublishResult.PermanentError -> {
                    permanent++
                    Log.w(
                        TAG,
                        "row permanent uuid=${row.uuid} http=${result.httpCode} update=$isUpdate: " +
                            result.message.take(200),
                    )
                    // For UPDATE flow a 4xx is the diverged-signal: the
                    // server won't accept this edit. Stop retrying.
                    if (isUpdate) {
                        boardRepository.markKilterPublishDiverged(
                            row.uuid,
                            "retry http=${result.httpCode}: ${result.message.take(200)}",
                        )
                    } else {
                        boardRepository.markKilterPublishFailed(
                            row.uuid,
                            "retry http=${result.httpCode}: ${result.message.take(200)}",
                        )
                    }
                }
            }
        }

        Log.i(TAG, "retry batch done — attempted=$attempted ok=$ok transient=$transient permanent=$permanent")
        // If everything was transient, ask WorkManager to retry the batch
        // sooner than the next scheduled tick (still subject to backoff).
        return if (transient > 0 && transient == attempted) {
            androidx.work.ListenableWorker.Result.retry()
        } else {
            androidx.work.ListenableWorker.Result.success()
        }
    }

    companion object {
        private const val TAG = "KilterRetryWorker"
        const val WORK_NAME = "kilter_publish_retry"

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
         *  the publish setting on, or after restoring connectivity. */
        fun runOnce(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<KilterPublishRetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_oneshot",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
