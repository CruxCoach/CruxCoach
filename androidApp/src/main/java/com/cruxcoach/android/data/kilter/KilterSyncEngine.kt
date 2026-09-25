package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.ClimbUuid
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * One queued upload paired with the row-version snapshot needed to mark it
 * synced without clobbering a concurrent local edit. Lets the upload be
 * chunked while keeping the optimistic per-row mark correct.
 */
private data class PendingUpload(
    val log: KilterLog,
    val uuid: String,
    val rowVersion: Long,
    val isAscent: Boolean,
)

data class KilterSyncReport(
    val downloaded: Int,
    val uploaded: Int,
    /** True when the download half succeeded but the Kilter upload call
     *  failed — the unsynced logs stay queued for the next sync. Surfaced
     *  so a half-failed sync doesn't render as a clean success. */
    val uploadFailed: Boolean = false,
    val uploadStatus: KilterUploadStatus? = null,
)

@Singleton
class KilterSyncEngine @Inject constructor(
    private val apiClient: KilterApiClient,
    private val tokenStore: KilterTokenStore,
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val secureDb: SecureDatabase,
    private val userPreferences: UserPreferences,
    private val uploadDiagnostics: KilterUploadDiagnostics,
    /** Defers the own-climb backfill while the Kilter catalogue loads. */
    private val pendingImports: dagger.Lazy<com.cruxcoach.android.data.PendingImports>,
) {
    private companion object {
        const val TAG = "KilterSyncEngine"

        /**
         * Max uuids per board-catalogue IN() lookup. SQLite caps bound
         * variables (historically 999); a large logbook can reference far
         * more distinct climbs, so [insertLogs] chunks its lookup below this.
         */
        const val CLIMB_LOOKUP_CHUNK = 400

        /**
         * Max logs per Kilter bulk-upload request. A large offline backlog is
         * split into batches so one oversized POST can't fail the whole
         * upload, and each batch that succeeds is marked synced independently.
         */
        const val UPLOAD_CHUNK = 200

        /**
         * Normalize a climb uuid to a spelling-agnostic key — see [ClimbUuid],
         * which owns the three spellings the catalogue actually stores.
         */
        fun normUuidKey(uuid: String): String = ClimbUuid.normKey(uuid)

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

    private val uploadMutex = Mutex()
    val uploadStatus get() = uploadDiagnostics.latest
    suspend fun clearUploadDiagnostics() = uploadMutex.withLock { uploadDiagnostics.clear() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Backfills the user's own logged + authored Kilter climbs into the
     *  board DB (see [KilterClimbBackfiller] for the full contract). */
    private val climbBackfiller = KilterClimbBackfiller(apiClient, boardRepository)

    /**
     * Backfills the user's own logged and authored climbs, or — while the
     * Kilter catalogue is still downloading — leaves that to [PendingImports]:
     * before the catalogue every logged climb looks missing and was inserted
     * as a placeholder, which also made the app think a catalogue existed.
     * The logs themselves are stored now and linked once it is in.
     */
    private suspend fun backfillOwnClimbs(): Pair<Int, Int> {
        val pending = pendingImports.get()
        if (pending.waitingForKilterCatalogue()) {
            pending.deferKilterBackfill()
            Log.i(TAG, "Own-climb backfill deferred until the Kilter catalogue is in")
            return 0 to 0
        }
        return climbBackfiller.backfillLoggedClimbs() to climbBackfiller.backfillAuthoredClimbs()
    }

    /** The backfill [backfillOwnClimbs] deferred, run by [PendingImports] once the catalogue is in. */
    suspend fun runDeferredBackfill() {
        if (!tokenStore.hasCredentials()) return
        climbBackfiller.backfillLoggedClimbs()
        climbBackfiller.backfillAuthoredClimbs()
    }

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
    suspend fun resolveAndStoreWallContext(prefetchedLogs: List<KilterLog>? = null) {
        if (tokenStore.hasWallContext()) return

        val resolved = if (prefetchedLogs != null) {
            apiClient.resolveWallContextFromLogs(prefetchedLogs)
        } else {
            apiClient.resolveWallContext()
        }
        when (val result = resolved) {
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
            try {
                val uploaded = uploadPendingLogs(KilterUploadTrigger.NEW_LOG).uploaded
                if (uploaded > 0) {
                    Log.d(TAG, "Auto-uploaded $uploaded ascents to Kilter")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Auto-upload to Kilter failed (${e.javaClass.simpleName})")
            }
        }
    }

    /**
     * Sync on app start if persistent sync is enabled.
     * Downloads new Kilter logs, uploads unsynced local logs (if push enabled),
     * and proactively refreshes the access token to keep the session alive.
     * Upload outcomes are retained for the Kilter settings and optional bug diagnostics.
     */
    fun syncOnAppStartIfEnabled() {
        scope.launch {
            if (!userPreferences.kilterSyncEnabled.first()) return@launch
            if (!tokenStore.hasCredentials()) return@launch
            try {
                val upload = if (userPreferences.kilterPushEnabled.first()) {
                    uploadPendingLogs(KilterUploadTrigger.APP_START)
                } else null
                val uploaded = upload?.uploaded ?: 0

                if (upload?.reason == KilterUploadReason.AUTHENTICATION) return@launch
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
                backfillOwnClimbs()
                circuitImporter.importCircuits()
                val imported = insertLogs(logs).totalNew

                if ((imported > 0 || uploaded > 0) && upload?.failed != true && (upload == null || upload.pending == 0)) {
                    val timestamp = DateTimeUtil.nowIso()
                    userPreferences.setKilterLastSync(timestamp)
                    Log.i(TAG, "App-start sync: imported=$imported, uploaded=$uploaded")
                }
            } catch (e: CancellationException) {
                throw e
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
            // Was der Nutzer geloescht hat, ist nicht "neu" — sonst kuendigt
            // die Vorschau einen Import an, den insertLogs gleich ueberspringt.
            val deletedLogUuids = personalBoardRepo.pendingLogDeletions().toHashSet()
            var newAscents = 0
            var newBids = 0
            for (log in logs) {
                if (log.logUuid in existingLogUuids || log.logUuid in deletedLogUuids) continue
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
        val logsResult = apiClient.fetchLogs()
        logsResult.map { logs ->
            // Resolve wall context on first import (auto-detect from the
            // user's Kilter data). Reuse the logs we just fetched instead of
            // downloading the entire logbook a second time inside
            // resolveWallContext.
            if (!oneTimeOnly) {
                resolveAndStoreWallContext(logs)
            }
            // Backfill PowerSync-only climbs into the board DB before
            // insertLogs denormalizes names/frames (best-effort, non-fatal).
            // Their counts feed the import summary (previously silent).
            val (backfilledClimbs, ownClimbs) = backfillOwnClimbs()
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

            // Download once, up front, and reuse the logs for wall-context
            // resolution instead of letting resolveAndStoreWallContext fetch
            // the whole logbook a second time.
            // Zuerst die Loeschungen nachholen: sonst liefert /logs den
            // Eintrag noch mit, und er wuerde zwar vom Grabstein abgefangen,
            // aber bei jedem Sync erneut uebertragen.
            if (pushEnabled) pushPendingLogDeletions()

            val logsResult = apiClient.fetchLogs()
            val logs = logsResult.getOrThrow()

            // Backfill PowerSync-only climbs into the board DB before
            // insertLogs denormalizes names/frames (best-effort, non-fatal).
            backfillOwnClimbs()
            circuitImporter.importCircuits()
            val downloaded = insertLogs(logs).totalNew

            // Preserve partial progress and blocked/failed outcomes for the UI.
            val upload = if (pushEnabled) uploadPendingLogs(prefetchedLogs = logs) else null
            val uploaded = upload?.uploaded ?: 0

            val timestamp = DateTimeUtil.nowIso()
            if (upload?.failed != true && (upload == null || upload.pending == 0)) userPreferences.setKilterLastSync(timestamp)

            Log.i(TAG, "Sync: downloaded=$downloaded, uploaded=$uploaded (push=$pushEnabled)")
            Result.success(KilterSyncReport(downloaded, uploaded, uploadFailed = upload?.failed == true, uploadStatus = upload))
        } catch (e: CancellationException) {
            throw e
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
        val existingLogUuids = personalBoardRepo.getExistingLogUuids()
        // Only genuinely NEW logs are written. Re-imported logs are skipped
        // entirely — insertAscent/insertBid are INSERT OR REPLACE, so
        // re-writing an existing row would reset row_version and clobber a
        // user's locally-edited quality/comment (the Aurora path guards the
        // same way with INSERT OR IGNORE). Skipping them also makes a
        // steady-state re-sync effectively free instead of rewriting the
        // whole logbook every time. A row left blank because BoardDB wasn't
        // synced yet is healed on display by repairMissingDenormalized, so
        // skipping duplicates here loses nothing.
        // Ein geloeschter Eintrag ist nicht mehr vorhanden, seine uuid fehlt
        // also in existingLogUuids — ohne diese Vormerkung haette der Download
        // ihn fuer neu gehalten und wieder angelegt, bei jedem Sync aufs Neue.
        // Genau daran war Loeschen bisher wirkungslos. Die Liste ist im
        // Normalfall leer: sie haelt nur, was Kilter noch nicht bestaetigt hat.
        val deletedLogUuids = personalBoardRepo.pendingLogDeletions().toHashSet()
        val newLogs = logs.filter {
            it.logUuid !in existingLogUuids && it.logUuid !in deletedLogUuids
        }
        val duplicates = logs.size - newLogs.size
        if (newLogs.isEmpty()) return LogInsertCounts(0, 0, duplicates)

        // Pre-fetch denormalized climb data from BoardDB, keyed by a
        // spelling-agnostic key. Query EVERY spelling the catalogue stores
        // ([ClimbUuid.spellings]) — querying only the uuid as given plus the
        // legacy nodash-UPPERCASE form left the 68 950 nodash-lowercase and
        // 40 828 dashed-lowercase catalogue rows unreachable from a log that
        // spelled them differently, and those ascents kept an empty name.
        // Chunk the IN() list so a large logbook can't blow SQLite's
        // bound-variable limit. One angle-agnostic query per chunk (the
        // denormalized name/grade is angle-independent for display).
        val climbCache = mutableMapOf<String, Pair<String, Double?>>() // normKey -> (name, diffAvg)
        val framesCache = mutableMapOf<String, Pair<String, Long>>()   // normKey -> (frames, framesCount)
        // Board-Familie und Layout der Zeile. Ohne sie legte der Kilter-Sync
        // seine Ascents mit layout_id = NULL an und verliess sich darauf, dass
        // refreshDenormalizedData sie spaeter nachtraegt — bis dahin konnte das
        // Logbuch die Board-Variante nicht bestimmen (Original vs Homewall).
        // Der Katalog kennt beides hier schon, also wird es gleich gesetzt.
        val boardCache = mutableMapOf<String, Pair<String, Long?>>()   // normKey -> (brand, layoutId)
        val lookupUuids = newLogs.asSequence()
            .map { it.climbUuid }
            .distinct()
            .flatMap { ClimbUuid.spellings(it).asSequence() }
            .distinct()
            .toList()
        for (chunk in lookupUuids.chunked(CLIMB_LOOKUP_CHUNK)) {
            for (climb in boardRepository.getClimbsByUuidsAnyAngle(chunk)) {
                val key = normUuidKey(climb.uuid)
                climbCache[key] = climb.name to climb.difficultyAverage
                framesCache[key] = climb.frames to climb.framesCount
                boardCache[key] = climb.boardBrand to climb.layoutId
            }
        }

        var newAscents = 0
        var newBids = 0
        personalBoardRepo.runInTransaction {
            for (log in newLogs) {
                val key = normUuidKey(log.climbUuid)
                val (climbName, diffAvg) = climbCache[key] ?: ("" to null)
                val (brand, layoutId) = boardCache[key] ?: ("kilter" to null)
                if (log.topped) {
                    val (frames, framesCount) = framesCache[key] ?: ("" to 1L)
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
                        framesCount = framesCount,
                        boardBrand = brand,
                        layoutId = layoutId,
                    )
                    newAscents++
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
                    newBids++
                }
            }
        }
        return LogInsertCounts(newAscents, newBids, duplicates)
    }

    /**
     * Spelling-agnostic keys of the CruxCoach community climbs among
     * [climbUuids] that Kilter never accepted. Looks up every stored spelling
     * in chunks, like [insertLogs]: a community climb missed here is uploaded
     * to Kilter, which rejects the whole batch.
     */
    private fun communityOnlyClimbKeys(climbUuids: List<String>): Set<String> {
        if (climbUuids.isEmpty()) return emptySet()
        val lookup = climbUuids.asSequence()
            .distinct()
            .flatMap { ClimbUuid.spellings(it).asSequence() }
            .distinct()
            .toList()
        return lookup.chunked(CLIMB_LOOKUP_CHUNK)
            .flatMap { boardRepository.communityOnlyClimbUuids(it) }
            .mapTo(HashSet()) { normUuidKey(it) }
    }

    /**
     * Holt beim Nutzer geloeschte Eintraege auch bei Kilter nach.
     *
     * Ohne das war Loeschen bei Dauersync wirkungslos in beide Richtungen:
     * lokal kam der Eintrag zurueck (dagegen die Grabsteine), und in Kilter
     * blieb er stehen, weil ueberhaupt kein Lösch-Aufruf existierte. Der
     * Grabstein haelt `pending_remote`, bis Kilter bestaetigt hat — so
     * ueberlebt das Loeschen auch einen Offline-Moment.
     *
     * Best effort: ein Fehlschlag laesst den Grabstein stehen und wird beim
     * naechsten Sync erneut versucht. Der lokale Eintrag bleibt in jedem Fall
     * geloescht.
     */
    private suspend fun pushPendingLogDeletions(): Int {
        val pending = personalBoardRepo.pendingLogDeletions()
        if (pending.isEmpty()) return 0
        var done = 0
        for (logUuid in pending) {
            when (apiClient.deleteLog(logUuid)) {
                // Auch 404 gilt als Erfolg: dann kennt Kilter den Eintrag
                // nicht (mehr), was das gewuenschte Ergebnis ist. Die
                // Vormerkung wird geloescht — sie hat ihren Zweck erfuellt,
                // /logs liefert den Eintrag ab jetzt ohnehin nicht mehr.
                is KilterPublishResult.Success -> {
                    personalBoardRepo.clearLogDeletion(logUuid)
                    done++
                }
                // Dauerhafte Ablehnung: erneutes Versuchen braechte nichts.
                // Die Vormerkung BLEIBT aber stehen — sie ist dann das
                // einzige, was den Eintrag noch lokal fernhaelt.
                is KilterPublishResult.PermanentError -> Unit
                // Voruebergehend (offline, 5xx): beim naechsten Sync erneut.
                else -> Unit
            }
        }
        if (done > 0) Log.i(TAG, "Pushed $done log deletion(s) to Kilter")
        return done
    }

    /** Serialized across manual and automatic triggers; only successful batches are stamped. */
    suspend fun uploadPendingLogs(
        trigger: KilterUploadTrigger = KilterUploadTrigger.MANUAL,
        prefetchedLogs: List<KilterLog>? = null,
    ): KilterUploadStatus =
        withContext(Dispatchers.IO) { uploadMutex.withLock {
            val start = System.currentTimeMillis()
            var uploaded = 0
            var attempted = 0
            var pendingCount = 0
            fun finish(reason: KilterUploadReason = KilterUploadReason.NONE, http: Int? = null): KilterUploadStatus {
                val status = KilterUploadStatus(uploaded, pendingCount, attempted, reason, http,
                    durationMs = System.currentTimeMillis() - start, trigger = trigger)
                uploadDiagnostics.record(status)
                if (reason == KilterUploadReason.AUTHENTICATION) _sessionExpired.value = true
                return status
            }
            try {
                // Logs of CruxCoach community climbs that Kilter never accepted
                // stay local. Kilter does not know their uuid: a batch holding
                // one could fail as a whole on every sync and hold back every
                // log behind it, or leave a log of an unknown climb in the
                // account. They are not pending, just not Kilter's.
                val allAscents = personalBoardRepo.getUnsyncedAscents()
                val allBids = personalBoardRepo.getUnsyncedBids()
                val localOnly = communityOnlyClimbKeys(
                    allAscents.map { it.climbUuid } + allBids.map { it.climbUuid },
                )
                val unsyncedAscents = allAscents.filter { normUuidKey(it.climbUuid) !in localOnly }
                val unsyncedBids = allBids.filter { normUuidKey(it.climbUuid) !in localOnly }
                pendingCount = unsyncedAscents.size + unsyncedBids.size
                if (!userPreferences.kilterPushEnabled.first()) return@withLock finish(KilterUploadReason.DISABLED)
                if (pendingCount == 0) return@withLock finish()
                val userUuid = tokenStore.getUserUuid()?.takeIf { it.isNotBlank() }
                    ?: return@withLock finish(KilterUploadReason.AUTHENTICATION)
                if (!tokenStore.hasCredentials()) return@withLock finish(KilterUploadReason.AUTHENTICATION)
                if (!tokenStore.hasWallContext()) resolveAndStoreWallContext(prefetchedLogs)
                val gymUuid = tokenStore.getGymUuid()?.takeIf { it.isNotBlank() }
                    ?: return@withLock finish(KilterUploadReason.WALL_CONTEXT)
                val wallUuid = tokenStore.getWallUuid()?.takeIf { it.isNotBlank() }
                    ?: return@withLock finish(KilterUploadReason.WALL_CONTEXT)
                val layoutUuid = tokenStore.getProductLayoutUuid()?.takeIf { it.isNotBlank() }
                    ?: return@withLock finish(KilterUploadReason.WALL_CONTEXT)

                val pending = ArrayList<PendingUpload>(unsyncedAscents.size + unsyncedBids.size)

                for (ascent in unsyncedAscents) {
                    pending.add(PendingUpload(
                        log = KilterLog(
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
                        ),
                        uuid = ascent.uuid,
                        rowVersion = ascent.rowVersion,
                        isAscent = true,
                    ))
                }

                for (bid in unsyncedBids) {
                    pending.add(PendingUpload(
                        log = KilterLog(
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
                        ),
                        uuid = bid.uuid,
                        rowVersion = bid.rowVersion,
                        isAscent = false,
                    ))
                }

                for (batch in pending.chunked(UPLOAD_CHUNK)) {
                    // An opt-out while waiting/in flight takes effect before the next request.
                    if (!userPreferences.kilterPushEnabled.first()) return@withLock finish(KilterUploadReason.DISABLED)
                    attempted += batch.size
                    apiClient.uploadLogs(batch.map { it.log }).getOrThrow()
                    // Optimistic mark — stamp synced=1 only when row_version still
                    // matches the snapshot captured at read time. Any user edit
                    // during the HTTP upload window bumps row_version and the stamp
                    // is skipped, so the next sync re-uploads the newer data
                    // instead of silently losing it to a stale write.
                    var skipped = 0
                    personalBoardRepo.runInTransaction {
                        for (item in batch) {
                            val applied = if (item.isAscent) {
                                personalBoardRepo.markAscentSyncedIfUnchanged(item.uuid, item.rowVersion)
                            } else {
                                personalBoardRepo.markBidSyncedIfUnchanged(item.uuid, item.rowVersion)
                            }
                            if (!applied) skipped++
                        }
                    }
                    if (skipped > 0) {
                        Log.i(TAG, "Sync: $skipped log(s) edited during upload — will re-upload next sync")
                    }
                    uploaded += batch.size
                    pendingCount -= batch.size - skipped
                }
                finish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when (e) {
                    is KilterLogConflictException -> finish(KilterUploadReason.CONFLICT)
                    is KilterUploadException -> finish(
                        if (e.status == 401 || e.status == 403) KilterUploadReason.AUTHENTICATION else KilterUploadReason.HTTP,
                        e.status)
                    is KilterApiException -> finish(
                        if (e.reason == KilterAuthResult.Error.Reason.NotAuthenticated) KilterUploadReason.AUTHENTICATION else KilterUploadReason.INTERNAL)
                    is java.io.IOException -> finish(KilterUploadReason.NETWORK)
                    else -> finish(KilterUploadReason.INTERNAL)
                }
            }
        } }
}
