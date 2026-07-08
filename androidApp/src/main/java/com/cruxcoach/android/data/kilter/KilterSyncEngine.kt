package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import javax.inject.Inject
import javax.inject.Singleton

data class KilterImportPreview(
    val totalLogs: Int,
    val newAscents: Int,
    val newBids: Int,
    val duplicateCount: Int
)

/**
 * Full per-object outcome of a Kilter import, so the UI can report every
 * object type instead of one opaque total. Logs are deduped against the
 * already-imported set (re-imports count as [duplicateLogs], not new), and
 * the otherwise-silent own-climb / catalogue backfills are surfaced.
 */
data class KilterImportResult(
    val newAscents: Int,
    val newBids: Int,
    val duplicateLogs: Int,
    /** Own authored climbs recognized (newly inserted + existing rows stamped). */
    val ownClimbs: Int,
    /** Logged-climb rows backfilled into the board catalogue. */
    val backfilledClimbs: Int,
    /** Own Kilter circuits imported as local lists (inserted or refreshed). */
    val circuits: Int,
) {
    val totalNew: Int get() = newAscents + newBids
}

/** Internal (newAscents, newBids, duplicates) tally from [KilterSyncEngine.insertLogs]. */
private data class LogInsertCounts(val newAscents: Int, val newBids: Int, val duplicates: Int) {
    val totalNew: Int get() = newAscents + newBids
}

data class KilterSyncReport(
    val downloaded: Int,
    val uploaded: Int,
    /** True when the download half succeeded but the Kilter upload call
     *  failed — the unsynced logs stay queued for the next sync. Surfaced
     *  so a half-failed sync doesn't render as a clean success. */
    val uploadFailed: Boolean = false,
)

@Singleton
class KilterSyncEngine @Inject constructor(
    private val apiClient: KilterApiClient,
    private val tokenStore: KilterTokenStore,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val secureDb: SecureDatabase,
    private val userPreferences: UserPreferences
) {
    private companion object {
        const val TAG = "KilterSyncEngine"

        /**
         * Ensure a timestamp ends with "Z" (UTC) for the Kilter API.
         * Local timestamps from [DateTimeUtil.nowIso] lack a timezone suffix,
         * which causes java.time.Instant parse failures on the server.
         */
        fun ensureUtcSuffix(timestamp: String): String {
            if (timestamp.endsWith("Z")) return timestamp
            return try {
                val local = LocalDateTime.parse(timestamp)
                local.toInstant(TimeZone.currentSystemDefault()).toString()
            } catch (_: Exception) {
                // Last resort: just append Z (assumes local ≈ UTC)
                "${timestamp}Z"
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Backfills the user's own logged + authored Kilter climbs into the
     *  board DB (see [KilterClimbBackfiller] for the full contract). */
    private val climbBackfiller = KilterClimbBackfiller(apiClient, boardRepository)

    /** Imports the user's own Kilter circuits into local `climb_lists`
     *  (see [KilterCircuitImporter]). */
    private val circuitImporter = KilterCircuitImporter(apiClient, secureDb)

    private val _sessionExpired = MutableStateFlow(false)

    /** True when the Kilter refresh token has expired and re-login is needed. */
    val sessionExpired: StateFlow<Boolean> = _sessionExpired

    /** Call after a successful re-login to clear the expired flag. */
    fun clearSessionExpired() { _sessionExpired.value = false }

    /**
     * Resolve and store the wall context (gym/wall/layout) required for log uploads.
     * Called after login. Two-stage fallback:
     *   1. Extract from user's existing Kilter logs (covers any user with history)
     *   2. If the user truly has no logs: create a custom wall with sensible defaults
     *      (Kilter Board Original + Kickboard) — always succeeds via local-UUID fallback.
     *
     * On network/API errors we do NOT fall through to Strategy 2 — otherwise a
     * transient hiccup would overwrite a real wall context with a locally generated one.
     */
    suspend fun resolveAndStoreWallContext() {
        if (tokenStore.hasWallContext()) return

        when (val result = apiClient.resolveWallContext()) {
            is KilterApiClient.ResolveResult.Found -> {
                val ctx = result.context
                tokenStore.setWallContext(ctx.gymUuid, ctx.wallUuid, ctx.productLayoutUuid)
                Log.i(TAG, "Wall context resolved from logs: gym=${ctx.gymUuid}, wall=${ctx.wallUuid}")
            }
            is KilterApiClient.ResolveResult.NoLogsYet -> {
                // User truly has no Kilter history — create a custom wall
                val username = tokenStore.getUsername() ?: "Home"
                val customWall = apiClient.createCustomWall(name = "$username's Kilter Board")
                tokenStore.setWallContext(
                    customWall.gymUuid,
                    customWall.wallUuid,
                    customWall.productLayoutUuid
                )
                Log.i(TAG, "Custom wall context ready: gym=${customWall.gymUuid}, wall=${customWall.wallUuid}")
            }
            is KilterApiClient.ResolveResult.Error -> {
                Log.w(TAG, "Wall context lookup failed (${result.message}) — will retry on next sync")
            }
        }
    }

    /**
     * Fire-and-forget: upload unsynced ascents to Kilter if persistent sync AND push are enabled.
     * Called after each local ascent log.
     */
    fun uploadNewAscentIfEnabled() {
        scope.launch {
            if (!userPreferences.kilterSyncEnabled.first()) return@launch
            if (!userPreferences.kilterPushEnabled.first()) return@launch
            if (!tokenStore.hasCredentials()) return@launch
            try {
                val uploaded = uploadUnsyncedLogs() ?: 0
                if (uploaded > 0) {
                    Log.d(TAG, "Auto-uploaded $uploaded ascents to Kilter")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-upload to Kilter failed", e)
            }
        }
    }

    /**
     * Sync on app start if persistent sync is enabled.
     * Downloads new Kilter logs, uploads unsynced local logs (if push enabled),
     * and proactively refreshes the access token to keep the session alive.
     * Silent — errors are logged but not surfaced to the user.
     */
    fun syncOnAppStartIfEnabled() {
        scope.launch {
            if (!userPreferences.kilterSyncEnabled.first()) return@launch
            if (!tokenStore.hasCredentials()) return@launch
            try {
                // Proactive token refresh — keeps the offline session alive.
                // The offline_access refresh token lasts ~30 days and renews
                // on each use, so this effectively prevents expiry.
                if (tokenStore.isAccessTokenExpired()) {
                    val refreshed = apiClient.refreshAccessToken()
                    if (!refreshed) {
                        Log.w(TAG, "App-start: token refresh failed — session expired")
                        _sessionExpired.value = true
                        return@launch
                    }
                }
                // Backfill the display username if the cached value is
                // stale (pre-fix login flow stored email-shaped
                // preferred_username, which the publish-path now refuses
                // to send to Kilter as a setter handle). Best-effort
                // background fetch; failure leaves the cached email in
                // place — the publish path's own email-shape guard
                // surfaces the issue to the user via Snackbar instead
                // of leaking PII silently.
                runCatching { apiClient.refreshUsernameIfStale() }
                    .onFailure { Log.w(TAG, "Username backfill failed (cached value will be re-checked next app-start)", it) }

                // Download
                val logsResult = apiClient.fetchLogs()
                val logs = logsResult.getOrNull() ?: return@launch
                // Backfill board-DB rows for PowerSync-only climbs BEFORE
                // denormalizing names/frames in insertLogs (best-effort).
                climbBackfiller.backfillLoggedClimbs()
                climbBackfiller.backfillAuthoredClimbs()
                circuitImporter.importCircuits()
                val imported = insertLogs(logs).totalNew

                // Upload unsynced local logs (catches offline-logged ascents)
                val uploaded = if (userPreferences.kilterPushEnabled.first()) {
                    uploadUnsyncedLogs() ?: 0
                } else 0

                if (imported > 0 || uploaded > 0) {
                    val timestamp = DateTimeUtil.nowIso()
                    userPreferences.setKilterLastSync(timestamp)
                    Log.i(TAG, "App-start sync: imported=$imported, uploaded=$uploaded")
                }
            } catch (e: Exception) {
                Log.w(TAG, "App-start Kilter sync failed", e)
            }
        }
    }

    /**
     * Fetch logs from Kilter and count how many are new vs duplicates.
     * Does NOT write anything to the database.
     */
    suspend fun previewImport(): Result<KilterImportPreview> = withContext(Dispatchers.IO) {
        val logsResult = apiClient.fetchLogs()
        logsResult.map { logs ->
            // Dedup against the already-imported LOG uuids (ascent + bid PKs),
            // NOT climb uuids — a log_uuid is never a climb uuid, so the old
            // `getAllClimbUuids()` check matched nothing and reported every log
            // as new even on a pure re-import.
            val existingLogUuids = personalBoardRepo.getExistingLogUuids()
            var newAscents = 0
            var newBids = 0
            for (log in logs) {
                if (log.logUuid in existingLogUuids) continue
                if (log.topped) newAscents++ else newBids++
            }
            KilterImportPreview(
                totalLogs = logs.size,
                newAscents = newAscents,
                newBids = newBids,
                duplicateCount = logs.size - newAscents - newBids
            )
        }
    }

    /**
     * Import Kilter logs into local board database.
     * If [oneTimeOnly], the session TOKENS are discarded after import but the
     * account identity (userUuid) is kept so own-authored climbs stay
     * claimable (see [KilterTokenStore.clearTokensKeepIdentity]).
     * Returns a per-object [KilterImportResult] (new ascents/bids, duplicates,
     * own climbs recognized, catalogue backfills) so the UI can report every
     * object type rather than one opaque total.
     */
    suspend fun importLogs(oneTimeOnly: Boolean): Result<KilterImportResult> = withContext(Dispatchers.IO) {
        // Resolve wall context on first import (auto-detect from user's Kilter data)
        if (!oneTimeOnly) {
            resolveAndStoreWallContext()
        }

        val logsResult = apiClient.fetchLogs()
        logsResult.map { logs ->
            // Backfill PowerSync-only climbs into the board DB before
            // insertLogs denormalizes names/frames (best-effort, non-fatal).
            // Their counts feed the import summary (previously silent).
            val backfilledClimbs = climbBackfiller.backfillLoggedClimbs()
            val ownClimbs = climbBackfiller.backfillAuthoredClimbs()
            val circuits = circuitImporter.importCircuits()
            val counts = insertLogs(logs)
            val timestamp = DateTimeUtil.nowIso()
            userPreferences.setKilterLastSync(timestamp)

            if (oneTimeOnly) {
                // Revoke server-side before clearing locally so the
                // 30-day Keycloak refresh token can't outlive the user's
                // explicit "import once and disconnect" choice. Best-
                // effort — failure here must not block the local clear.
                runCatching { apiClient.revokeRefreshToken() }
                // Discard the tokens but KEEP the userUuid: the authored-climb
                // backfill above stamped `kilter_author_uuid` on the user's own
                // climbs, and the "Meine Climbs" hub + claim flow can only
                // recognize them by matching that against the stored userUuid.
                // A full clear() would silently make the just-imported authored
                // climbs unclaimable forever. The connection still reads as
                // "not connected" (hasCredentials checks the removed refresh
                // token) and no tokenless push can fire (syncEnabled=false).
                tokenStore.clearTokensKeepIdentity()
                userPreferences.setKilterSyncEnabled(false)
            } else {
                userPreferences.setKilterSyncEnabled(true)
            }

            val result = KilterImportResult(
                newAscents = counts.newAscents,
                newBids = counts.newBids,
                duplicateLogs = counts.duplicates,
                ownClimbs = ownClimbs,
                backfilledClimbs = backfilledClimbs,
                circuits = circuits,
            )
            Log.i(TAG, "Kilter import (oneTime=$oneTimeOnly): $result")
            result
        }
    }

    /**
     * Sync with Kilter:
     * 1. Download new Kilter logs → insert locally (always)
     * 2. Upload unsynced local ascents/bids → mark as synced (only if push enabled)
     */
    suspend fun syncBidirectional(): Result<KilterSyncReport> = withContext(Dispatchers.IO) {
        try {
            val pushEnabled = userPreferences.kilterPushEnabled.first()

            // Ensure wall context is available before upload
            if (pushEnabled) resolveAndStoreWallContext()

            // Download
            val logsResult = apiClient.fetchLogs()
            val logs = logsResult.getOrThrow()
            // Backfill PowerSync-only climbs into the board DB before
            // insertLogs denormalizes names/frames (best-effort, non-fatal).
            climbBackfiller.backfillLoggedClimbs()
            climbBackfiller.backfillAuthoredClimbs()
            circuitImporter.importCircuits()
            val downloaded = insertLogs(logs).totalNew

            // Upload unsynced local data (only if push is enabled). null =
            // the upload call itself failed → report it in the result so a
            // half-failed sync doesn't render as a clean success.
            val uploaded = if (pushEnabled) uploadUnsyncedLogs() else 0

            val timestamp = DateTimeUtil.nowIso()
            userPreferences.setKilterLastSync(timestamp)

            Log.i(TAG, "Sync: downloaded=$downloaded, uploaded=$uploaded (push=$pushEnabled)")
            Result.success(KilterSyncReport(downloaded, uploaded ?: 0, uploadFailed = uploaded == null))
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            // Pre-fix this pattern-matched on `e.message?.contains("Nicht
            // angemeldet")` — brittle to any i18n change of the (now
            // typed) error. KilterApiClient throws KilterApiException
            // with a typed reason, so we can dispatch on that directly.
            if (e is KilterApiException &&
                e.reason == KilterAuthResult.Error.Reason.NotAuthenticated) {
                _sessionExpired.value = true
            }
            Result.failure(e)
        }
    }

    /**
     * Insert Kilter logs into local DB. Uses log_uuid as aurora_ascent/bid uuid
     * so INSERT OR REPLACE handles duplicates idempotently.
     * Returns how many were genuinely NEW (split ascent/bid) vs already
     * present — the old "count every row" tally reported a full re-import as
     * all-new even though the writes were idempotent no-ops.
     */
    private fun insertLogs(logs: List<KilterLog>): LogInsertCounts {
        // Snapshot the already-imported log uuids BEFORE writing so the
        // INSERT-OR-REPLACE below doesn't make every row look pre-existing.
        val existingLogUuids = personalBoardRepo.getExistingLogUuids()
        var newAscents = 0
        var newBids = 0
        var duplicates = 0

        // Pre-fetch denormalized climb data from BoardDB
        val climbUuids = logs.map { it.climbUuid }.distinct()
        val angleSet = logs.map { it.angle }.distinct()
        val climbCache = mutableMapOf<String, Pair<String, Double?>>() // uuid -> (name, diffAvg)
        val framesCache = mutableMapOf<String, Pair<String, Long>>() // uuid -> (frames, framesCount)
        for (angle in angleSet) {
            val climbs = boardRepository.getClimbsByUuids(climbUuids, angle)
            for (climb in climbs) {
                climbCache[climb.uuid] = climb.name to climb.difficultyAverage
                framesCache[climb.uuid] = climb.frames to climb.framesCount
            }
        }

        personalBoardRepo.runInTransaction {
            for (log in logs) {
                val isNew = log.logUuid !in existingLogUuids
                val (climbName, diffAvg) = climbCache[log.climbUuid] ?: ("" to null)
                if (log.topped) {
                    val (frames, framesCount) = framesCache[log.climbUuid] ?: ("" to 1L)
                    personalBoardRepo.insertAscent(
                        uuid = log.logUuid,
                        climbUuid = log.climbUuid,
                        angle = log.angle.toLong(),
                        isMirror = false,
                        attemptId = if (log.flashed) 0L else 1L,
                        bidCount = log.attempts.toLong(),
                        quality = null,
                        difficulty = null,
                        isBenchmark = false,
                        comment = log.comment,
                        climbedAt = log.createdAt,
                        synced = true,
                        gymUuid = log.gymUuid.ifEmpty { null },
                        wallUuid = log.wallUuid.ifEmpty { null },
                        productLayoutUuid = log.productLayoutUuid.ifEmpty { null },
                        climbName = climbName,
                        difficultyAverage = diffAvg,
                        climbFrames = frames,
                        framesCount = framesCount
                    )
                } else {
                    personalBoardRepo.insertBid(
                        uuid = log.logUuid,
                        climbUuid = log.climbUuid,
                        angle = log.angle.toLong(),
                        isMirror = false,
                        bidCount = log.attempts.toLong(),
                        comment = log.comment,
                        climbedAt = log.createdAt,
                        synced = true,
                        gymUuid = log.gymUuid.ifEmpty { null },
                        wallUuid = log.wallUuid.ifEmpty { null },
                        productLayoutUuid = log.productLayoutUuid.ifEmpty { null },
                        climbName = climbName,
                        difficultyAverage = diffAvg
                    )
                }
                if (isNew) {
                    if (log.topped) newAscents++ else newBids++
                } else {
                    duplicates++
                }
            }
        }
        return LogInsertCounts(newAscents, newBids, duplicates)
    }

    /**
     * Upload local ascents/bids that haven't been synced to Kilter yet.
     * Returns the total count of uploaded records; 0 when there is nothing
     * to upload (or no wall context); null when the Kilter upload call
     * itself failed — those records stay unsynced and retry next sync.
     */
    private suspend fun uploadUnsyncedLogs(): Int? {
        val userUuid = tokenStore.getUserUuid() ?: return 0

        // Wall context is required for Kilter API — try to resolve if missing
        if (!tokenStore.hasWallContext()) {
            resolveAndStoreWallContext()
        }
        val gymUuid = tokenStore.getGymUuid() ?: run {
            Log.w(TAG, "No wall context — skipping upload")
            return 0
        }
        val wallUuid = tokenStore.getWallUuid() ?: return 0
        val layoutUuid = tokenStore.getProductLayoutUuid() ?: return 0

        val unsyncedAscents = personalBoardRepo.getUnsyncedAscents()
        val unsyncedBids = personalBoardRepo.getUnsyncedBids()
        Log.d(TAG, "Unsynced: ${unsyncedAscents.size} ascents, ${unsyncedBids.size} bids")

        if (unsyncedAscents.isEmpty() && unsyncedBids.isEmpty()) return 0

        val kilterLogs = mutableListOf<KilterLog>()

        for (ascent in unsyncedAscents) {
            kilterLogs.add(KilterLog(
                logUuid = ascent.uuid,
                userUuid = userUuid,
                climbUuid = ascent.climbUuid,
                gymUuid = ascent.gymUuid ?: gymUuid,
                wallUuid = ascent.wallUuid ?: wallUuid,
                productLayoutUuid = ascent.productLayoutUuid ?: layoutUuid,
                angle = ascent.angle.toInt(),
                flashed = ascent.bidCount <= 1L,
                topped = true,
                attempts = ascent.bidCount.toInt().coerceAtLeast(1),
                createdAt = ensureUtcSuffix(ascent.climbedAt),
                comment = ascent.comment
            ))
        }

        for (bid in unsyncedBids) {
            kilterLogs.add(KilterLog(
                logUuid = bid.uuid,
                userUuid = userUuid,
                climbUuid = bid.climbUuid,
                gymUuid = bid.gymUuid ?: gymUuid,
                wallUuid = bid.wallUuid ?: wallUuid,
                productLayoutUuid = bid.productLayoutUuid ?: layoutUuid,
                angle = bid.angle.toInt(),
                flashed = false,
                topped = false,
                attempts = bid.bidCount.toInt().coerceAtLeast(1),
                createdAt = ensureUtcSuffix(bid.climbedAt),
                comment = bid.comment
            ))
        }

        val result = apiClient.uploadLogs(kilterLogs)
        if (result.isSuccess) {
            // Optimistic mark — stamp synced=1 only when row_version still
            // matches the snapshot captured at read time. Any user edit
            // during the HTTP upload window bumps row_version and the stamp
            // is skipped, so the next sync re-uploads the newer data
            // instead of silently losing it to a stale write.
            var skipped = 0
            personalBoardRepo.runInTransaction {
                for (ascent in unsyncedAscents) {
                    val applied = personalBoardRepo.markAscentSyncedIfUnchanged(
                        uuid = ascent.uuid,
                        expectedRowVersion = ascent.rowVersion
                    )
                    if (!applied) skipped++
                }
                for (bid in unsyncedBids) {
                    val applied = personalBoardRepo.markBidSyncedIfUnchanged(
                        uuid = bid.uuid,
                        expectedRowVersion = bid.rowVersion
                    )
                    if (!applied) skipped++
                }
            }
            if (skipped > 0) {
                Log.i(TAG, "Sync: $skipped log(s) edited during upload — will re-upload next sync")
            }
            return kilterLogs.size
        } else {
            Log.w(TAG, "Upload failed: ${result.exceptionOrNull()?.message}")
            return null
        }
    }
}
