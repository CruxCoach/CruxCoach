package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
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

data class KilterSyncReport(
    val downloaded: Int,
    val uploaded: Int
)

@Singleton
class KilterSyncEngine @Inject constructor(
    private val apiClient: KilterApiClient,
    private val tokenStore: KilterTokenStore,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
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
                val uploaded = uploadUnsyncedLogs()
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
                backfillLoggedClimbs()
                val imported = insertLogs(logs)

                // Upload unsynced local logs (catches offline-logged ascents)
                val uploaded = if (userPreferences.kilterPushEnabled.first()) {
                    uploadUnsyncedLogs()
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
            val existingUuids = boardRepository.getAllClimbUuids()
            var newAscents = 0
            var newBids = 0
            for (log in logs) {
                // Use log_uuid as ascent uuid — if it already exists, it's a duplicate
                if (log.logUuid in existingUuids) continue
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
     * If [oneTimeOnly], credentials are cleared after import.
     * Returns the number of records imported.
     */
    suspend fun importLogs(oneTimeOnly: Boolean): Result<Int> = withContext(Dispatchers.IO) {
        // Resolve wall context on first import (auto-detect from user's Kilter data)
        if (!oneTimeOnly) {
            resolveAndStoreWallContext()
        }

        val logsResult = apiClient.fetchLogs()
        logsResult.map { logs ->
            // Backfill PowerSync-only climbs into the board DB before
            // insertLogs denormalizes names/frames (best-effort, non-fatal).
            backfillLoggedClimbs()
            val imported = insertLogs(logs)
            val timestamp = DateTimeUtil.nowIso()
            userPreferences.setKilterLastSync(timestamp)

            if (oneTimeOnly) {
                // Revoke server-side before clearing locally so the
                // 30-day Keycloak refresh token can't outlive the user's
                // explicit "import once and disconnect" choice. Best-
                // effort — failure here must not block the local clear.
                runCatching { apiClient.revokeRefreshToken() }
                tokenStore.clear()
                userPreferences.setKilterSyncEnabled(false)
            } else {
                userPreferences.setKilterSyncEnabled(true)
            }

            Log.i(TAG, "Imported $imported logs from Kilter (oneTime=$oneTimeOnly)")
            imported
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
            backfillLoggedClimbs()
            val downloaded = insertLogs(logs)

            // Upload unsynced local data (only if push is enabled)
            val uploaded = if (pushEnabled) uploadUnsyncedLogs() else 0

            val timestamp = DateTimeUtil.nowIso()
            userPreferences.setKilterLastSync(timestamp)

            Log.i(TAG, "Sync: downloaded=$downloaded, uploaded=$uploaded (push=$pushEnabled)")
            Result.success(KilterSyncReport(downloaded, uploaded))
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
     * Backfill the board DB with the user's OWN logged climbs that our
     * curated mirror lacks — the new-world (PowerSync-only) climbs a logbook
     * ascent can reference yet fail to resolve (the "Climb nicht gefunden"
     * bug). Runs BEFORE [insertLogs] so the denormalization there picks up
     * the freshly-upserted name/frames and the detail screen can resolve the
     * climb.
     *
     * Best-effort and NON-fatal: any failure (auth/network/parse) is logged
     * and swallowed so the normal log sync continues. Only climbs the board
     * DB does NOT already have are upserted — curated rows are never
     * clobbered. Compliance: a SINGLE GET of the user's own logged climbs;
     * no bulk/all-climbs fetch, no loop-crawl, no separate schedule — it
     * rides the existing logbook-sync trigger.
     */
    private suspend fun backfillLoggedClimbs() {
        val response = apiClient.fetchLoggedClimbs().getOrElse {
            Log.w(TAG, "Logged-climb backfill skipped (fetch failed): ${it.message}")
            return
        }
        if (response.climbs.isEmpty()) return

        // Only consider climbs the board DB is missing — never overwrite
        // curated rows. climbExistsByUuid is an indexed point-lookup.
        val missing = response.climbs.filter { climb ->
            climb.climbUuid.isNotBlank() && !boardRepository.climbExistsByUuid(climb.climbUuid)
        }
        if (missing.isEmpty()) return

        // Stats keyed by (uuid, angle) so each climb-stat row pairs with its
        // climb. The detail screen resolves a climb via the (uuid, angle)
        // LEFT JOIN, and insertLogs' denormalization needs the climb_stats
        // row to exist for the logged angle — so we upsert a stat at each
        // climb's own angle, falling back to the API stats list.
        val statsByKey = response.climbStats.associateBy { it.climbUuid to it.angle }

        var upserted = 0
        try {
            boardRepository.runInTransaction {
                for (climb in missing) {
                    val frames = climb.climbConcat
                    val moveCount = if (frames.isNotBlank()) {
                        BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames)).toLong()
                    } else 0L
                    // productLayoutUuid is a numeric string in Kilter's API
                    // ("10", "27", …) — the same value the board DB stores as
                    // layout_id. Unparseable → 0 (still resolvable by uuid).
                    val layoutId = climb.productLayoutUuid.toLongOrNull() ?: 0L
                    boardRepository.upsertClimb(
                        uuid = climb.climbUuid,
                        layoutId = layoutId,
                        setter = climb.username.ifBlank { null },
                        name = climb.name,
                        frames = frames,
                        framesCount = climb.frameCount.toLong().coerceAtLeast(1L),
                        isListed = if (climb.isListed) 1L else 0L,
                        edgeLeft = climb.edgeLeft?.toLong(),
                        edgeRight = climb.edgeRight?.toLong(),
                        edgeBottom = climb.edgeBottom?.toLong(),
                        edgeTop = climb.edgeTop?.toLong(),
                        createdAt = climb.createdAt.ifBlank { null },
                        description = climb.description,
                        framesPace = climb.framesPace.toLong(),
                        moveCount = moveCount,
                    )
                    // Stat row at the climb's own angle so the (uuid, angle)
                    // lookup resolves. Use the API stat if present; otherwise
                    // write a bare row carrying only the angle key.
                    val stat = statsByKey[climb.climbUuid to climb.angle]
                    boardRepository.upsertClimbStat(
                        climbUuid = climb.climbUuid,
                        angle = climb.angle.toLong(),
                        displayDifficulty = stat?.difficultyAverage,
                        difficultyAverage = stat?.difficultyAverage,
                        qualityAverage = stat?.qualityAverage,
                        ascensionistCount = stat?.ascentCount?.toLong(),
                        benchmarkDifficulty = null,
                        faUsername = stat?.faUsername,
                        faAt = stat?.faAt,
                        officialKilterDifficulty = stat?.currentDifficultyId?.toLong(),
                    )
                    upserted++
                }
            }
            if (upserted > 0) {
                Log.i(TAG, "Backfilled $upserted logged climb(s) missing from board DB")
            }
        } catch (e: Exception) {
            // Backfill is an enhancement, never a gate — keep the log sync alive.
            Log.w(TAG, "Logged-climb backfill failed mid-write — continuing log sync", e)
        }
    }

    /**
     * Insert Kilter logs into local DB. Uses log_uuid as aurora_ascent/bid uuid
     * so INSERT OR REPLACE handles duplicates idempotently.
     * Returns count of newly inserted records.
     */
    private fun insertLogs(logs: List<KilterLog>): Int {
        var count = 0

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
                count++
            }
        }
        return count
    }

    /**
     * Upload local ascents/bids that haven't been synced to Kilter yet.
     * Returns total count of uploaded records.
     */
    private suspend fun uploadUnsyncedLogs(): Int {
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
            return 0
        }
    }
}
