package com.cruxcoach.android.data.kilter

import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.RawAscent
import com.cruxcoach.data.repository.RawBid
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
    private val uploadLedger: KilterUploadLedger = InMemoryKilterUploadLedger(),
    private val lowercaseIndexSource: KilterLowercaseClimbIndexSource = KilterLowercaseClimbIndexSource.EMPTY,
    private val aliasSource: KilterClimbAliasSource = KilterClimbAliasSource.EMPTY,
) {
    /** Wall clock of the upload ledger; replaced in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

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
         * Bulk requests per upload run. A clean queue needs one per
         * [UPLOAD_CHUNK] rows; isolating a refused row costs about two per
         * halving. Whatever is left over waits for the next run.
         */
        const val MAX_REQUESTS_PER_RUN = 40

        /** Statuses that say nothing about the rows: stop and retry later. */
        val TRANSIENT_HTTP = setOf(408, 425, 429, 502, 503, 504)

        /**
         * Requests a run may spend on rows sent one by one before the bulk
         * chunks: retries of unproven refusals, and rows of climbs Kilter
         * refused before or keeps under another id. They go first so that the
         * chunks after them can prove a refusal.
         */
        const val MAX_SINGLE_REQUESTS_PER_RUN = 16

        /** Retry spacing for a refusal without proof when a run has nothing else to send: 1 h, 4 h, 12 h, then daily. */
        val UNPROVEN_RETRY_MS = longArrayOf(1, 4, 12, 24).map { it * 60 * 60 * 1000 }

        /**
         * Lone refusals on schedule (at least 1 h, then 4 h apart) after which
         * the list tells the user Kilter does not know the climb. They never
         * hold a row back: a failing upload endpoint while Kilter's logbook
         * still reads (e.g. a changed upload contract) looks the same, and the
         * row keeps being retried, at most daily, until one upload proves it.
         */
        const val UNPROVEN_STRIKES = 3

        /** external_id prefix of entries imported from an Aurora export (FEAT-005). */
        const val AURORA_IMPORT_PREFIX = "aurora-json:"

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

            val generation = uploadGeneration.get()
            val logsResult = apiClient.fetchLogs()
            val logs = logsResult.getOrThrow()

            // Backfill PowerSync-only climbs into the board DB before
            // insertLogs denormalizes names/frames (best-effort, non-fatal).
            backfillOwnClimbs()
            circuitImporter.importCircuits()
            val downloaded = insertLogs(logs).totalNew

            // Bestandseinträge aus älteren Versionen tragen einen Grad vom
            // falschen Winkel (GROUP BY lieferte dort einen beliebigen).
            // refreshDenormalizedData rechnet winkelgenau und heilt sie —
            // best effort, ein Fehlschlag darf den Sync nicht kippen.
            runCatching { pendingImports.get().refreshLogbookLinks() }
                .onFailure { Log.w(TAG, "logbook relink after sync failed", it) }

            // Preserve partial progress and blocked/failed outcomes for the UI.
            val upload = if (pushEnabled) uploadPendingLogs(prefetchedLogs = logs, snapshotGeneration = generation) else null
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
        // bound-variable limit. Der angle-agnostische Treffer liefert Name,
        // Frames, Brand und Layout — die sind winkelunabhaengig. Die
        // SCHWIERIGKEIT ist es NICHT: sie kommt weiter unten pro geloggtem
        // Winkel dazu.
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

        // Die Schwierigkeit gehoert zum geloggten Winkel. getClimbsByUuidsAnyAngle
        // kollabiert die Winkel per GROUP BY zu einer beliebigen Zeile — in der
        // Praxis dem kleinsten Winkel —, und genau die stand bisher im Logbuch:
        // "Floats Your Boat", bei 40 Grad geklettert, erschien als 4a statt 6a,
        // weil 0 Grad mit 10,01 statt 15,96 bewertet ist. Das verfaelschte jeden
        // Eintrag und damit auch "Bester Grad".
        // Eine Abfrage je vorkommendem Winkel, nicht je Log.
        val difficultyByAngle = mutableMapOf<Pair<String, Int>, Double>()
        for (angle in newLogs.map { it.angle }.distinct()) {
            for (chunk in lookupUuids.chunked(CLIMB_LOOKUP_CHUNK)) {
                boardRepository.getClimbDifficultiesForAngle(chunk, angle).forEach { (uuid, diff) ->
                    difficultyByAngle[normUuidKey(uuid) to angle] = diff
                }
            }
        }

        var newAscents = 0
        var newBids = 0
        personalBoardRepo.runInTransaction {
            for (log in newLogs) {
                val key = normUuidKey(log.climbUuid)
                val (climbName, _) = climbCache[key] ?: ("" to null)
                val (brand, layoutId) = boardCache[key] ?: ("kilter" to null)
                // Kein Grad statt eines fremden: hat der Boulder fuer diesen
                // Winkel keine Bewertung, bleibt das Feld leer und wird beim
                // naechsten refreshDenormalizedData nachgetragen.
                val diffAvg = difficultyByAngle[key to log.angle]
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

    /**
     * Upload the local logbook entries Kilter does not have yet.
     *
     * Serialized across manual and automatic triggers. One run reads Kilter's
     * copy of the logbook once and settles everything it can locally: entries
     * Kilter already holds are marked synced without a request, entries that
     * differ from Kilter's copy of their uuid stay local as conflicts, and
     * climb ids are sent in the spelling Kilter stores ([KilterClimbWireIds]).
     *
     * Kilter fails a bulk request as a whole when it refuses one row. A run
     * therefore sends one by one the rows of a climb Kilter refused with
     * proof before, then settles retries of earlier refusals, then everything
     * else in chunks, splitting a refused chunk until the row is alone. A lone
     * row tries every id its climb may have on Kilter
     * ([KilterClimbWireIds.candidates]), the one Kilter lists for a moved
     * climb first. It is held back ([KilterUploadLedger])
     * only with proof: Kilter accepted another request after the row's last
     * refusal in the same run, or it refused the same climb with proof before.
     * Kilter answers its own failures with HTTP 500 too, and an outage, also
     * one that begins mid-run, must not park a row Kilter would take. Without
     * proof the row is retried in the next run that sends anything, or after a
     * growing pause when there is nothing else. Requests per run are bounded;
     * what is left waits for the next run.
     *
     * [prefetchedLogs] (Kilter's logbook, read by the caller) is used only
     * while no upload happened since it was read ([snapshotGeneration]).
     */
    suspend fun uploadPendingLogs(
        trigger: KilterUploadTrigger = KilterUploadTrigger.MANUAL,
        prefetchedLogs: List<KilterLog>? = null,
        snapshotGeneration: Long? = null,
    ): KilterUploadStatus = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            val snapshot = prefetchedLogs?.takeIf { snapshotGeneration == null || snapshotGeneration == uploadGeneration.get() }
            UploadRun(trigger).run(snapshot)
        }
    }

    /** Upload opt-in for Aurora-imported entries; see [KilterUploadLedger.importedUploadEnabled]. */
    val importedUploadEnabled get() = uploadLedger.importedUploadEnabled

    suspend fun setImportedUploadEnabled(enabled: Boolean) = uploadLedger.setImportedUploadEnabled(enabled)

    /** Forget held-back rows and the imported opt-in, e.g. when the Kilter account is disconnected. */
    suspend fun clearUploadLedger() = uploadMutex.withLock { uploadLedger.clear() }

    /**
     * The entries the upload could not hand to Kilter, with the reason, for
     * the list the user can open and report. Entries synced since are left
     * out; one edited since is listed until the next run retries it.
     */
    suspend fun notUploadedEntries(): List<KilterNotUploadedEntry> = withContext(Dispatchers.IO) {
        uploadMutex.withLock {
            val now = clock()
            val versionCode = com.cruxcoach.android.BuildConfig.VERSION_CODE
            val rejections = uploadLedger.rejections()
                .filter { it.appVersionCode == versionCode && now - it.atMs in 0..KilterUploadLedger.MAX_AGE_MS }
            val outcome = uploadLedger.lastOutcome()
            val rows = (personalBoardRepo.getUnsyncedAscents().map { it.toRow() } +
                personalBoardRepo.getUnsyncedBids().map { it.toRow() }).associateBy { it.uuid }
            val names = climbNames(rows.values.map { it.climbUuid })
            fun entry(row: CandidateRow, reason: KilterNotUploadedReason, wireId: String? = null, http: Int? = null) =
                KilterNotUploadedEntry(
                    logUuid = row.uuid, climbUuid = row.climbUuid, climbName = names[normUuidKey(row.climbUuid)],
                    angle = row.angle, climbedAt = row.climbedAt, isAscent = row.isAscent,
                    reason = reason, wireId = wireId, httpStatus = http,
                )
            buildList {
                for (r in rejections) {
                    val row = rows[r.logUuid] ?: continue
                    val reason = if (r.confirmed || r.unproven >= UNPROVEN_STRIKES) KilterNotUploadedReason.NOT_ON_KILTER
                        else KilterNotUploadedReason.RETRY_LATER
                    add(entry(row, reason, r.wireId, r.httpStatus))
                }
                outcome.conflicts.mapNotNull(rows::get).forEach { add(entry(it, KilterNotUploadedReason.CONFLICT)) }
                outcome.invalid.mapNotNull(rows::get).forEach { add(entry(it, KilterNotUploadedReason.INVALID_DATE)) }
            }.distinctBy { it.logUuid }.sortedByDescending { it.climbedAt }
        }
    }

    private fun climbNames(climbUuids: List<String>): Map<String, String> = runCatching {
        climbUuids.asSequence().flatMap { ClimbUuid.spellings(it).asSequence() }.distinct().toList()
            .chunked(CLIMB_LOOKUP_CHUNK)
            .flatMap { boardRepository.getClimbsByUuidsAnyAngle(it) }
            .associate { normUuidKey(it.uuid) to it.name }
    }.getOrElse { emptyMap() }

    /** Counts runs that sent requests: a logbook read before one of them may be stale. */
    private val uploadGeneration = java.util.concurrent.atomic.AtomicLong()

    private inner class UploadRun(private val trigger: KilterUploadTrigger) {
        private val start = clock()
        private var uploaded = 0
        private val attempted = HashSet<String>()
        private val accepted = HashSet<String>()
        private var pending = 0
        private var requests = 0
        private var heldImported = 0
        private var alreadyOnKilter = 0
        private var rejectedByKilter = 0
        private var rejectedConflict = 0
        private var rejectedInvalid = 0
        private var unconfirmed = 0

        private fun finish(reason: KilterUploadReason = KilterUploadReason.NONE, http: Int? = null): KilterUploadStatus {
            if (requests > 0) uploadGeneration.incrementAndGet()
            val status = KilterUploadStatus(
                uploaded = uploaded, pending = pending, attempted = attempted.size,
                reason = reason, httpStatus = http,
                durationMs = clock() - start, trigger = trigger,
                rejectedByKilter = rejectedByKilter, rejectedConflict = rejectedConflict,
                rejectedInvalid = rejectedInvalid, heldImported = heldImported,
                alreadyOnKilter = alreadyOnKilter, requests = requests, unconfirmed = unconfirmed,
            )
            uploadDiagnostics.record(status)
            if (reason == KilterUploadReason.AUTHENTICATION) _sessionExpired.value = true
            return status
        }

        suspend fun run(prefetchedLogs: List<KilterLog>?): KilterUploadStatus = try {
            upload(prefetchedLogs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed read of Kilter's logbook counts like a failed upload:
            // 401/403 asks for a new login, other statuses are Kilter's.
            val stop = stopFor(e, (e as? KilterUploadException)?.status ?: (e as? KilterHttpException)?.status)
            finish(stop.reason, stop.http)
        }

        private suspend fun upload(prefetchedLogs: List<KilterLog>?): KilterUploadStatus {
            // Logs of CruxCoach community climbs that Kilter never accepted
            // stay local: Kilter does not know their uuid. They are not
            // pending, just not Kilter's.
            val allAscents = personalBoardRepo.getUnsyncedAscents()
            val allBids = personalBoardRepo.getUnsyncedBids()
            val localClimbs = (allAscents.map { it.climbUuid } + allBids.map { it.climbUuid }).distinct()
            val catalogue = catalogueFacts(localClimbs)
            val rows = allAscents.filter { normUuidKey(it.climbUuid) !in catalogue.communityOnly }.map { it.toRow() } +
                allBids.filter { normUuidKey(it.climbUuid) !in catalogue.communityOnly }.map { it.toRow() }

            val now = clock()
            val versionCode = com.cruxcoach.android.BuildConfig.VERSION_CODE
            val previous = uploadLedger.rejections()
                .filter { it.appVersionCode == versionCode && now - it.atMs in 0..KilterUploadLedger.MAX_AGE_MS }
                .associateBy { it.logUuid }
            val importedEnabled = uploadLedger.importedUploadEnabled.first()
            // An imported entry tried under the opt-in stays queued until Kilter settled it.
            val candidates = rows.filter { !it.imported || importedEnabled || it.uuid in previous }
            heldImported = rows.size - candidates.size
            // Until Kilter's logbook is read, rows held back with proof count as
            // refused, not pending (a run that stops early reports them so).
            rejectedByKilter = candidates.count { previous[it.uuid]?.confirmed == true }
            pending = candidates.size - rejectedByKilter
            if (!userPreferences.kilterPushEnabled.first()) return finish(KilterUploadReason.DISABLED)
            if (candidates.isEmpty()) return finish()
            val userUuid = tokenStore.getUserUuid()?.takeIf { it.isNotBlank() }
                ?: return finish(KilterUploadReason.AUTHENTICATION)
            if (!tokenStore.hasCredentials()) return finish(KilterUploadReason.AUTHENTICATION)
            if (!tokenStore.hasWallContext()) resolveAndStoreWallContext(prefetchedLogs)
            val wall = WallContext(
                gymUuid = tokenStore.getGymUuid()?.takeIf { it.isNotBlank() } ?: return finish(KilterUploadReason.WALL_CONTEXT),
                wallUuid = tokenStore.getWallUuid()?.takeIf { it.isNotBlank() } ?: return finish(KilterUploadReason.WALL_CONTEXT),
                productLayoutUuid = tokenStore.getProductLayoutUuid()?.takeIf { it.isNotBlank() }
                    ?: return finish(KilterUploadReason.WALL_CONTEXT),
            )

            // Kilter's copy of the logbook, read once per run: retries after a
            // lost response, entries restored under a new uuid and edits of
            // already-uploaded rows are all settled against it without a request.
            // A log the user deleted here is on its way out of Kilter and must
            // not stand in for another entry.
            val deleting = personalBoardRepo.pendingLogDeletions().toHashSet()
            val remote = (prefetchedLogs ?: apiClient.fetchLogs().getOrThrow()).filter { it.logUuid !in deleting }
            val remoteByUuid = remote.associateBy { it.logUuid }
            val twins = KilterTwinIndex(remote)
            val learned = LinkedHashMap(uploadLedger.learnedWireIds())
            val wireIds = KilterClimbWireIds(
                lowercaseIndex = lowercaseIndexSource.load(),
                cruxcoachKeys = catalogue.cruxcoach,
                accountSpellings = KilterClimbWireIds.accountSpellings(remote),
                legacyKeys = catalogue.legacy,
                aliases = aliasSource.load(),
                learned = learned,
            )

            val settled = ArrayList<KilterUploadItem>()
            var unmatched = ArrayList<KilterUploadItem>()
            val conflicts = ArrayList<String>()
            val invalid = ArrayList<String>()
            pending = candidates.size
            rejectedByKilter = 0
            for (row in candidates.sortedByDescending { it.sortMillis }) {
                val createdAt = KilterLogUploadPlan.kilterTimestamp(row.climbedAt)
                if (createdAt == null) {
                    rejectedInvalid++
                    pending--
                    invalid += row.uuid
                    continue
                }
                val ids = wireIds.candidates(row.climbUuid)
                val item = KilterUploadItem(
                    uuid = row.uuid, rowVersion = row.rowVersion, isAscent = row.isAscent, imported = row.imported,
                    log = KilterLog(
                        logUuid = row.uuid, userUuid = userUuid,
                        climbUuid = ids.first(),
                        gymUuid = row.gymUuid ?: wall.gymUuid, wallUuid = row.wallUuid ?: wall.wallUuid,
                        productLayoutUuid = row.productLayoutUuid ?: wall.productLayoutUuid,
                        angle = row.angle, flashed = row.isAscent && row.bidCount <= 1L, topped = row.isAscent,
                        attempts = row.bidCount.toInt().coerceAtLeast(1), createdAt = createdAt, comment = row.comment,
                    ),
                    climbKey = normUuidKey(row.climbUuid),
                    candidates = ids,
                )
                val known = remoteByUuid[row.uuid]
                when {
                    known == null -> unmatched += item
                    item.candidates.any { KilterLogUploadPlan.sameContent(item.log.copy(climbUuid = it), known) } -> settled += item
                    // Bulk cannot update a log, and deleting to re-create it
                    // would be a silent rewrite: the edit stays local.
                    else -> {
                        rejectedConflict++
                        pending--
                        conflicts += row.uuid
                    }
                }
            }
            // Exact twins for every entry first (under any id the climb has on
            // Kilter), then the nearest log up to a day away for imported ones,
            // so no exact twin is taken by a looser match.
            unmatched = unmatched.filterTo(ArrayList()) { item ->
                val twin = item.candidates.any { twins.claimExact(item.log.copy(climbUuid = it)) }
                if (twin) settled += item
                !twin
            }
            val near = twins.claimNearest(
                unmatched.filter { it.imported }.flatMap { item -> item.candidates.map { item.uuid to item.log.copy(climbUuid = it) } },
            )
            unmatched = unmatched.filterTo(ArrayList()) { item -> (item.uuid !in near).also { if (!it) settled += item } }
            if (settled.isNotEmpty()) {
                val marked = markSynced(settled)
                alreadyOnKilter += marked
                pending -= marked
            }

            // Entries of rows this run does not reach (request budget) keep
            // their state; settled and accepted rows drop theirs below.
            val ledger = LinkedHashMap(previous)
            settled.forEach { ledger.remove(it.uuid) }
            // A climb Kilter refused under every id, with proof, is not on Kilter:
            // its other rows go one by one and their lone refusal counts as proven.
            // Rows of a climb refused without proof wait like the refused row.
            val provenClimbs = previous.values.filter { it.confirmed }.mapNotNullTo(HashSet()) { it.climbKey }
            val suspectClimbs = previous.values.filterNot { it.confirmed }.associate { (it.climbKey ?: it.logUuid) to it.retryAtMs }
            val waiting = ArrayList<KilterUploadItem>()
            val notOnKilter = ArrayList<KilterUploadItem>()
            val chunked = ArrayList<KilterUploadItem>()
            for (item in unmatched) {
                val held = previous[item.uuid]?.takeIf { it.fingerprint == item.fingerprint }
                when {
                    held?.confirmed == true -> {
                        rejectedByKilter++
                        pending--
                    }
                    held != null || item.climbKey in suspectClimbs -> waiting += item
                    item.climbKey in provenClimbs -> notOnKilter += item.copy(candidates = listOf(item.log.climbUuid))
                    else -> chunked += item
                }
            }
            // Only rows that may be accepted can prove a refusal; without them
            // unproven refusals are retried after a pause, not on every trigger.
            val fresh = chunked.isNotEmpty()
            val (due, resting) = waiting.partition { item ->
                fresh || now >= (previous[item.uuid]?.retryAtMs ?: suspectClimbs[item.climbKey] ?: 0L)
            }
            unconfirmed += resting.size

            val outcome = send(notOnKilter, retried = due, chunked = chunked)
            accepted.forEach { ledger.remove(it) }
            learned.putAll(outcome.learned)
            var unprovenHttp: Int? = null
            val provenNow = outcome.refused.filter { outcome.lastAcceptedRequest > it.request }.mapTo(HashSet()) { it.item.climbKey }
            for (r in outcome.refused) {
                val earlier = previous[r.item.uuid]?.takeIf { it.fingerprint == r.item.fingerprint && !it.confirmed }
                // Only a refusal on schedule counts as a strike; an early retry
                // (others were sent anyway) keeps the schedule as it was.
                val onSchedule = earlier == null || now >= earlier.retryAtMs
                val strikes = (earlier?.unproven ?: 0) + if (onSchedule) 1 else 0
                val proven = outcome.lastAcceptedRequest > r.request ||
                    r.item.climbKey in provenClimbs || r.item.climbKey in provenNow
                ledger[r.item.uuid] = KilterUploadRejection(
                    logUuid = r.item.uuid, fingerprint = r.item.fingerprint, appVersionCode = versionCode,
                    httpStatus = r.http, atMs = now, confirmed = proven,
                    retryAtMs = when {
                        proven -> 0
                        onSchedule -> now + UNPROVEN_RETRY_MS[(strikes - 1).coerceIn(0, UNPROVEN_RETRY_MS.lastIndex)]
                        else -> earlier!!.retryAtMs
                    },
                    unproven = strikes, climbKey = r.item.climbKey, wireId = r.item.log.climbUuid,
                )
                // Unproven rows stay pending: they are retried.
                if (proven) {
                    rejectedByKilter++
                    pending--
                } else {
                    unconfirmed++
                    unprovenHttp = r.http
                }
            }
            uploadLedger.saveRejections(ledger.values.sortedBy { it.atMs })
            uploadLedger.saveLearnedWireIds(learned)
            uploadLedger.saveLastOutcome(KilterUploadOutcome(conflicts = conflicts, invalid = invalid))
            // The imported opt-in covers the entries present when it was given.
            val complete = outcome.stop == null && !outcome.exhausted && outcome.deferred == 0
            if (importedEnabled && complete) uploadLedger.setImportedUploadEnabled(false)

            val stop = outcome.stop
            return when {
                stop != null -> finish(stop.reason, stop.http)
                // New rows refused and nothing accepted after any of them:
                // Kilter may be down. Shown as a failure, nothing held back.
                unprovenHttp != null && !outcome.anyAccepted && fresh -> finish(KilterUploadReason.HTTP, unprovenHttp)
                else -> finish(http = unprovenHttp)
            }
        }

        /** Stamps synced=1 per row where its version is unchanged; returns how many were stamped. */
        private fun markSynced(items: List<KilterUploadItem>): Int {
            var marked = 0
            personalBoardRepo.runInTransaction {
                for (item in items) {
                    val applied = if (item.isAscent) personalBoardRepo.markAscentSyncedIfUnchanged(item.uuid, item.rowVersion)
                    else personalBoardRepo.markBidSyncedIfUnchanged(item.uuid, item.rowVersion)
                    if (applied) marked++
                }
            }
            if (marked < items.size) {
                Log.i(TAG, "Sync: ${items.size - marked} log(s) edited during upload — will re-upload next sync")
            }
            return marked
        }

        /** One request; marks its rows synced when Kilter took it. */
        private suspend fun post(batch: List<KilterUploadItem>): Posted {
            requests++
            batch.forEach { attempted += it.uuid }
            val result = apiClient.uploadLogs(batch.map { it.log })
            if (result.isSuccess) {
                batch.forEach { accepted += it.uuid }
                val marked = markSynced(batch)
                uploaded += batch.size
                pending -= marked
                return Posted.Accepted
            }
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            val http = (error as? KilterUploadException)?.status
            // Unauthenticated, offline, overloaded or a lost response (the
            // rows may have been written; the next run's read of the logbook
            // settles them): stop, keep everything pending.
            if (http == null || http == 401 || http == 403 || http in TRANSIENT_HTTP) return Posted.Stopped(stopFor(error, http))
            return Posted.Refused(http)
        }

        /**
         * Sends [singles] (rows of a climb Kilter refused with proof) one by
         * one, then [retried] and [chunked] in chunks,
         * the retries first so that the chunks after them can prove a renewed
         * refusal. Every full chunk goes out before a refused one is isolated,
         * so a single bad row costs the queue nothing but its own requests.
         * Rows of a climb Kilter refused under every id in this run leave the
         * chunks not yet sent and wait for the next run.
         */
        private suspend fun send(
            singles: List<KilterUploadItem>,
            retried: List<KilterUploadItem>,
            chunked: List<KilterUploadItem>,
        ): SendOutcome {
            val out = SendOutcome()
            val refusedClimbs = HashSet<String>()
            val promoted = ArrayList<KilterUploadItem>()
            val queue = ArrayDeque(singles)
            var current: KilterUploadItem? = null
            while (true) {
                // An opt-out while waiting/in flight takes effect before the next request.
                if (!userPreferences.kilterPushEnabled.first()) return out.apply { stop = Stop(KilterUploadReason.DISABLED) }
                val item = current ?: queue.removeFirstOrNull() ?: break
                current = null
                if (requests >= MAX_SINGLE_REQUESTS_PER_RUN) {
                    out.deferred += 1 + queue.size
                    break
                }
                // Kilter refused this climb under every id already: one request settles the row.
                val row = if (item.climbKey in refusedClimbs && item.tried == 0) item.copy(candidates = listOf(item.log.climbUuid)) else item
                when (val posted = post(listOf(row))) {
                    Posted.Accepted -> {
                        out.accept(requests)
                        out.learned[row.climbKey] = row.log.climbUuid
                        // The climb's other queued rows now know their id and can go in a chunk.
                        val same = queue.filter { it.climbKey == row.climbKey }
                        queue.removeAll(same.toSet())
                        promoted += same.map { it.startingWith(row.log.climbUuid) }
                    }
                    is Posted.Stopped -> return out.apply { stop = posted.stop }
                    is Posted.Refused -> {
                        current = row.nextCandidate()
                        if (current == null) {
                            out.refused += Refusal(row, posted.http, requests)
                            refusedClimbs += row.climbKey
                        }
                    }
                }
            }

            // Retries are settled first, splitting included, so that the chunks
            // after them can prove a renewed refusal.
            val retries = ArrayDeque(retried.chunked(UPLOAD_CHUNK))
            val chunks = ArrayDeque((promoted + chunked).chunked(UPLOAD_CHUNK))
            val isolating = ArrayDeque<List<KilterUploadItem>>()
            while (retries.isNotEmpty() || chunks.isNotEmpty() || isolating.isNotEmpty()) {
                if (!userPreferences.kilterPushEnabled.first()) return out.apply { stop = Stop(KilterUploadReason.DISABLED) }
                if (requests >= MAX_REQUESTS_PER_RUN) return out.apply { exhausted = true }
                val split = if (retries.isNotEmpty()) retries else isolating
                var batch = retries.removeFirstOrNull() ?: chunks.removeFirstOrNull() ?: isolating.removeFirst()
                if (batch.size > 1 && refusedClimbs.isNotEmpty()) {
                    val (refusedClimb, rest) = batch.partition { it.climbKey in refusedClimbs }
                    out.deferred += refusedClimb.size
                    if (rest.isEmpty()) continue
                    batch = rest
                }
                when (val posted = post(batch)) {
                    Posted.Accepted -> {
                        out.accept(requests)
                        batch.singleOrNull()?.takeIf { it.tried > 0 }?.let { out.learned[it.climbKey] = it.log.climbUuid }
                    }
                    is Posted.Stopped -> return out.apply { stop = posted.stop }
                    is Posted.Refused -> {
                        if (batch.size > 1) {
                            val half = batch.size / 2
                            split.addFirst(batch.subList(half, batch.size))
                            split.addFirst(batch.subList(0, half))
                            continue
                        }
                        val item = batch.single()
                        val next = item.nextCandidate()
                        if (next != null) {
                            split.addFirst(listOf(next))
                        } else {
                            out.refused += Refusal(item, posted.http, requests)
                            refusedClimbs += item.climbKey
                        }
                    }
                }
            }
            return out
        }
    }

    private fun KilterUploadItem.startingWith(id: String) =
        copy(log = log.copy(climbUuid = id), candidates = listOf(id) + candidates.filter { it != id }, tried = 0)

    private sealed interface Posted {
        data object Accepted : Posted
        class Refused(val http: Int) : Posted
        class Stopped(val stop: Stop) : Posted
    }

    private class Stop(val reason: KilterUploadReason, val http: Int? = null)

    private fun stopFor(error: Throwable?, http: Int?): Stop = when {
        http == 401 || http == 403 -> Stop(KilterUploadReason.AUTHENTICATION, http)
        http != null -> Stop(KilterUploadReason.HTTP, http)
        error is KilterApiException && error.reason == KilterAuthResult.Error.Reason.NotAuthenticated ->
            Stop(KilterUploadReason.AUTHENTICATION)
        error is java.io.IOException -> Stop(KilterUploadReason.NETWORK)
        else -> {
            Log.w(TAG, "Kilter upload failed (${error?.javaClass?.simpleName})")
            Stop(KilterUploadReason.INTERNAL)
        }
    }

    /** A row Kilter refused under every id it could name; [request] orders it against acceptances. */
    private class Refusal(val item: KilterUploadItem, val http: Int, val request: Int)

    private class SendOutcome {
        var anyAccepted = false
        /** Ordinal of the last request Kilter accepted, -1 if none: proof for refusals before it. */
        var lastAcceptedRequest = -1
        val refused = ArrayList<Refusal>()
        /** Ids Kilter took for rows that needed another than their first. */
        val learned = LinkedHashMap<String, String>()
        /** Rows left for the next run although the budget held. */
        var deferred = 0
        var stop: Stop? = null
        /** The request budget ran out with rows still unsent. */
        var exhausted = false

        fun accept(request: Int) {
            anyAccepted = true
            lastAcceptedRequest = request
        }
    }

    /** One unsynced ascent or bid, flattened for the upload plan. */
    private class CandidateRow(
        val uuid: String,
        val climbUuid: String,
        val angle: Int,
        val bidCount: Long,
        val comment: String?,
        val climbedAt: String,
        val gymUuid: String?,
        val wallUuid: String?,
        val productLayoutUuid: String?,
        val rowVersion: Long,
        val isAscent: Boolean,
        val imported: Boolean,
    ) {
        val sortMillis: Long = KilterLogUploadPlan.kilterTimestamp(climbedAt)
            ?.let(KilterLogUploadPlan::epochMillis) ?: Long.MIN_VALUE
    }

    private fun RawAscent.toRow() = CandidateRow(
        uuid = uuid, climbUuid = climbUuid, angle = angle.toInt(), bidCount = bidCount, comment = comment,
        climbedAt = climbedAt, gymUuid = gymUuid, wallUuid = wallUuid, productLayoutUuid = productLayoutUuid,
        rowVersion = rowVersion, isAscent = true, imported = externalId.isAuroraImport(),
    )

    private fun RawBid.toRow() = CandidateRow(
        uuid = uuid, climbUuid = climbUuid, angle = angle.toInt(), bidCount = bidCount, comment = comment,
        climbedAt = climbedAt, gymUuid = gymUuid, wallUuid = wallUuid, productLayoutUuid = productLayoutUuid,
        rowVersion = rowVersion, isAscent = false, imported = externalId.isAuroraImport(),
    )

    private fun String?.isAuroraImport() = this?.startsWith(AURORA_IMPORT_PREFIX) == true

    /** What the board catalogue knows about the logbook's climbs, by normKey. */
    private class CatalogueFacts(
        /** CruxCoach climbs Kilter never accepted. */
        val communityOnly: Set<String>,
        /** Every CruxCoach-authored climb. */
        val cruxcoach: Set<String>,
        /** Dashed-spelled logbook climbs whose catalogue row is compact (legacy). */
        val legacy: Set<String>,
    )

    /**
     * Looks up every stored spelling in chunks, like [insertLogs]. The board
     * DB can be busy right after a catalogue import; a failed lookup fails
     * the run, which the next trigger repeats: without these facts a dashed
     * spelling of a legacy climb would be sent dashed, which Kilter accepts
     * under a separate statistics identity, so no retry would correct it.
     */
    private fun catalogueFacts(climbUuids: List<String>): CatalogueFacts {
        if (climbUuids.isEmpty()) return CatalogueFacts(emptySet(), emptySet(), emptySet())
        val lookup = climbUuids.asSequence()
            .flatMap { ClimbUuid.spellings(it).asSequence() }
            .distinct()
            .toList()
        val chunks = lookup.chunked(CLIMB_LOOKUP_CHUNK)
        val communityOnly = chunks.flatMap { boardRepository.communityOnlyClimbUuids(it) }.mapTo(HashSet()) { normUuidKey(it) }
        val cruxcoach = chunks.flatMap { boardRepository.cruxcoachClimbUuids(it) }.mapTo(HashSet()) { normUuidKey(it) }
        val dashedCompact = climbUuids.asSequence()
            .filter { it.length == 36 && it[8] == '-' }
            .map { normUuidKey(it) }
            .distinct()
            .toList()
        val legacy = dashedCompact.chunked(CLIMB_LOOKUP_CHUNK)
            .flatMap { boardRepository.existingClimbUuids(it) }
            .mapTo(HashSet()) { normUuidKey(it) }
        return CatalogueFacts(communityOnly, cruxcoach, legacy)
    }
}
