package com.cruxcoach.android.community

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
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.android.util.WorkerRunLog
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.community.ClimbEditorState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Drains the Nostr-publish retry queue.
 *
 * Picks up rows where the editor's publish path called
 * `markClimbPublishFailed` because zero relays accepted the Kind-30078
 * event (transient relay failure, network blip, captive portal). The
 * queue criterion mirrors `getClimbsAwaitingNostrRetry`:
 *   - source = 'local'
 *   - sync_status = 'failed'
 *   - created_by_pubkey = <local pubkey>
 *
 * For each, reconstructs a [ClimbEditorState] from the stored frames /
 * name / description / climb_stats and re-runs
 * [CommunityClimbPublisher.publish]. The d-tag is stable per pubkey+uuid
 * so a relay that received the original send-attempt simply replaces
 * the existing event (NIP-78). On accepted-by-at-least-one-relay the
 * publisher flips sync_status back to 'published_nostr' and the row
 * leaves the queue.
 *
 * Sibling worker to [com.cruxcoach.android.data.kilter.KilterPublishRetryWorker]
 * — that one handles the Kilter-API mirror; this one handles the
 * mandatory Nostr leg. Same lifecycle (6h periodic + manual runOnce),
 * same per-row try/catch isolation.
 */
@HiltWorker
class CommunityPublishRetryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val boardRepository: BoardRepository,
    private val nostrSigner: NostrSigner,
    private val communityClimbPublisher: CommunityClimbPublisher,
    private val userPreferences: UserPreferences,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val startedAt = WorkerRunLog.started()
        var errorClass: String? = null
        val result = try {
            CommunityPublishDrainGate.tryRun { drainQueue() }
                ?: androidx.work.ListenableWorker.Result.success().also {
                    Log.i(TAG, "skip: another Nostr retry drain is already in flight")
                }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            errorClass = e.javaClass.simpleName
            Log.w(TAG, "worker pre-flight failed; retrying (${e.javaClass.simpleName})")
            androidx.work.ListenableWorker.Result.retry()
        }
        return WorkerRunLog.finished(
            TAG,
            WORK_NAME,
            runAttemptCount,
            startedAt,
            result,
            errorClass,
        )
    }

    private suspend fun drainQueue(): androidx.work.ListenableWorker.Result {
        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
        if (pubkey.isNullOrBlank()) {
            Log.i(TAG, "skip: no local pubkey (signer not initialized)")
            return androidx.work.ListenableWorker.Result.success()
        }

        Log.i(TAG, "worker start (pubkey=${pubkey.take(8)}…)")

        val rows = runCatching { boardRepository.getClimbsAwaitingNostrRetry(pubkey) }
            .getOrElse {
                Log.w(TAG, "could not list retry candidates", it)
                return androidx.work.ListenableWorker.Result.retry()
            }
        if (rows.isEmpty()) {
            Log.i(TAG, "no candidates")
            return androidx.work.ListenableWorker.Result.success()
        }

        // Active board's size name — ONLY a fallback for rows whose own
        // (brand, layout) can't resolve a size label below. The brand +
        // layout used to publish are always the CLIMB'S OWN (resolved per
        // row via getClimbPublishContext), never the active board's:
        // a queued draft may belong to a board that isn't currently active,
        // and publishing it with the active layout/brand would mislabel it
        // (e.g. a Grasshopper draft going out as a Kilter climb because both
        // are layout_id=1). Best-effort — null is tolerated, the row's own
        // context usually supplies the label anyway.
        val activeSizeId = userPreferences.boardProductSizeId.first()
        val fallbackSizeLabel =
            runCatching { boardRepository.getProductSize(activeSizeId) }.getOrNull()?.name

        var attempted = 0
        var ok = 0
        var failed = 0
        for (row in rows) {
            attempted++
            try {
                val stats = runCatching { boardRepository.getClimbStatsForUuid(row.uuid) }.getOrNull()
                // Angle/grade fallbacks: every editor-published row HAS a
                // stats row (ClimbValidation requires both fields), so a
                // miss here is a mid-flow insert failure. Pre-fix the
                // nulls hit buildCommunityClimbEvent's `require(...)`,
                // the row stayed at 'failed' and re-threw every 6h
                // forever — this queue has no terminal state, so the only
                // exit is a successful publish. Same fallback values the
                // Kilter retry worker uses for its angle (40°) and the
                // grade slider uses for a missing seed.
                if (stats == null || stats.second == null) {
                    Log.w(TAG, "stats missing/incomplete for uuid=${row.uuid}; publishing with fallback angle/grade")
                }
                val state = ClimbEditorState(
                    selectedHolds = parseHolds(row.framesText),
                    name = row.name,
                    description = row.description,
                    setterGradeId = stats?.second
                        ?: com.cruxcoach.domain.board.KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
                    angle = stats?.first ?: 40,
                )
                // Resolve the CLIMB'S OWN publish coordinates — its brand +
                // layout (both authoritative) plus a best-effort size label.
                // Fall back to the row's stored layoutId / the active board's
                // size name only when the context lookup comes up empty.
                val ctx = runCatching { boardRepository.getClimbPublishContext(row.uuid) }.getOrNull()
                val boardBrand = BoardBrand.fromWire(ctx?.boardBrand)
                val layoutId = ctx?.layoutId ?: row.layoutId
                val sizeLabel = ctx?.sizeLabel ?: fallbackSizeLabel ?: ""
                // CommunityClimbPublisher.publish throws on accepted == 0
                // (after marking the row failed again). The retry worker's
                // job is to make that throw isolated — one bad row shouldn't
                // poison the rest of the batch — so we catch and keep going.
                communityClimbPublisher.publish(
                    uuid = row.uuid,
                    layoutId = layoutId,
                    boardBrand = boardBrand,
                    state = state,
                    sizeLabel = sizeLabel,
                    isEdit = true, // existing row in DB; publisher's `isEdit` skips dup-check
                )
                ok++
                Log.i(TAG, "row ok uuid=${row.uuid} brand=${boardBrand.wireValue} layout=$layoutId")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed++
                Log.w(TAG, "row threw uuid=${row.uuid}; continuing batch", e)
            }
        }

        Log.i(TAG, "retry batch done — attempted=$attempted ok=$ok failed=$failed")
        // If everything failed, ask WorkManager to retry the batch sooner
        // than the next scheduled tick (subject to backoff).
        return if (failed > 0 && failed == attempted) {
            androidx.work.ListenableWorker.Result.retry()
        } else {
            androidx.work.ListenableWorker.Result.success()
        }
    }

    private fun parseHolds(framesText: String): Map<Int, Int> {
        val holds = com.cruxcoach.domain.board.BoardClimbParser.parseFrames(framesText)
        return holds.associate { it.placementId to it.roleId }
    }

    companion object {
        private const val TAG = "CommunityRetryWorker"
        const val WORK_NAME = "community_publish_retry"
        // Distinct from WORK_NAME — see runOnce's docstring for why.
        const val ONESHOT_WORK_NAME = "community_publish_retry_oneshot"

        /**
         * Schedule the periodic retry. Uses the same 6-hour cadence as
         * the Kilter-side retry worker — Nostr publish failures are
         * almost always transient relay outages that clear within
         * minutes; a periodic 6h tick chips at the queue without
         * burning battery on aggressive retries.
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CommunityPublishRetryWorker>(
                6L, TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Manual one-shot trigger — useful right after the user resolves
         *  a connectivity issue from the editor's failure snackbar.
         *
         *  Uses a DISTINCT unique name from [WORK_NAME] (the periodic
         *  cadence): `enqueueUniqueWork` against a name already held by
         *  a PeriodicWorkRequest is silently dropped on some WorkManager
         *  versions — see [com.cruxcoach.android.data.kilter.KilterPublishRetryWorker.runOnce]
         *  where the same bug was diagnosed and fixed. The process-wide
         *  drain gate makes a one-shot racing the periodic tick harmless;
         *  the pre-mark is crash safety, not a dequeue claim. */
        fun runOnce(context: Context) {
            Log.i(TAG, "runOnce: enqueueing one-shot retry")
            val request = androidx.work.OneTimeWorkRequestBuilder<CommunityPublishRetryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
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
