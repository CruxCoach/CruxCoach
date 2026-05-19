package com.cruxcoach.android.data

import android.content.Context
import android.util.Log
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.util.isNetworkAvailable
import com.cruxcoach.android.util.isWifiConnected
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Application-scoped sync manager that survives ViewModel lifecycle.
 * The sync continues running when the user navigates away from
 * BoardSyncScreen or backgrounds the app (as long as the process lives).
 */
class BoardSyncManager(
    private val importer: BoardDatabaseImporter,
    private val blossomSyncManager: BlossomSyncManager,
    private val userPreferences: UserPreferences,
    private val appContext: Context,
    private val boardRepository: com.cruxcoach.data.repository.BoardRepository,
    private val personalBoardRepo: com.cruxcoach.data.repository.PersonalBoardRepository,
    private val boardLocationRepository: com.cruxcoach.data.repository.BoardLocationRepository
) {
    private companion object {
        const val TAG = "BoardSyncManager"
        /** Max concurrent chunk downloads. On mobile links 4 streams
         *  hit the bandwidth-delay-product without each stream
         *  perpetually competing for TCP slow-start window; the 3
         *  Blossom mirrors per chunk also frequently resolve to the
         *  same provider, where 4 concurrent connections stay below
         *  per-IP rate-limit thresholds. */
        const val PARALLEL_DOWNLOADS = 4
        /** Denormalized-field refresh batch size — keeps per-transaction
         *  write-lock hold time short so user writes can interleave. */
        const val REFRESH_BATCH_SIZE = 100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BoardSyncState())
    val state: StateFlow<BoardSyncState> = _state.asStateFlow()

    private val _locationsBackfilling = MutableStateFlow(false)
    /** True only while [backfillLocationsIfMissing] is actively fetching /
     *  importing the locations chunk — lets the Map show a real progress
     *  state instead of the misleading "sync the board DB" prompt. */
    val locationsBackfilling: StateFlow<Boolean> = _locationsBackfilling.asStateFlow()

    init {
        scope.launch {
            val imported = importer.isImported()
            val lastSync = userPreferences.lastSyncTimestamp.first()
            _state.update { it.copy(
                alreadyImported = imported,
                syncComplete = imported,
                lastSyncTimestamp = lastSync
            ) }
        }
    }

    fun checkNetwork() {
        _state.update { it.copy(
            networkAvailable = isNetworkAvailable(appContext),
            wifiConnected = isWifiConnected(appContext)
        ) }
    }

    /**
     * Recovers from a partial-import state left by a previous run that was
     * killed mid-sync — e.g. [com.cruxcoach.android.ui.settings.restartApp]
     * during an identity switch, OOM kill, force-stop, or system reboot
     * while [BoardDatabaseImporter.importFromChunks] was still running.
     *
     * Detection: a successful import always populates both `climb` and
     * `placement` (climbs are imported first; the meta chunk that owns
     * placements is imported last). If climb rows exist but the placement
     * table is empty, the import was interrupted before its meta phase.
     * In that state [isImported] still returns true (so [syncIfStale] does
     * nothing) and the BoardBrowser shows zero holds — the user is stuck
     * unless they manually find "Sync now" in Settings.
     *
     * Recovery semantics differ from [syncIfStale]: a broken DB is always
     * worth fixing, so we bypass the WiFi-only gate (cellular is fine) and
     * the [SyncInterval.MANUAL] gate. We still require *some* network — no
     * connectivity at all means we'll retry on the next app start.
     */
    fun recoverPartialImportIfNeeded() {
        scope.launch {
            // EXISTS-based fast path: getClimbCount() blocks tens of
            // seconds during an active import, and this hook fires at
            // app-start where the user is already waiting on UI render.
            if (!boardRepository.hasAnyClimbs()) return@launch
            if (boardRepository.getAllPlacements().isNotEmpty()) return@launch

            Log.w(TAG, "Partial board DB detected (climbs>0, placements=0) — interrupted import; triggering recovery sync")

            if (!isNetworkAvailable(appContext)) {
                Log.w(TAG, "Recovery needed but no network — will retry on next app start")
                return@launch
            }

            startBackgroundSync()
        }
    }

    /**
     * Handles the post-7.sqm forced re-sync. The migration wipes Kilter-
     * side rows from the local board DB to escape the NOCASE-dedup
     * mismatch (see 7.sqm header). Without coordinated chunk-hash +
     * lastSyncTimestamp invalidation, [syncIfStale] would see "all
     * chunks up to date" and "data fresh" and the user would land on
     * an empty browser until they manually triggered a sync.
     *
     * 7.sqm leaves a `post_v8_force_resync` marker in `sync_states`;
     * this method consumes it on app start, clears the dependent state,
     * and triggers a background sync. The actual sync runs async and
     * will redownload every chunk fresh.
     */
    fun handlePostMigrationResync() {
        scope.launch {
            val v8 = boardRepository.hasPostV8ResyncMarker()
            val homewall = boardRepository.hasHomewallResyncMarker()
            if (!v8 && !homewall) return@launch
            val reason = listOfNotNull(
                "post-v8".takeIf { v8 },
                "homewall".takeIf { homewall },
            ).joinToString("+")
            // Defer the actual wipe + marker-clear until we have a
            // network path: the resync needs the network anyway, and
            // wiping the chunk-hash cache + lastSyncTimestamp offline
            // would just leave the user staring at a "never synced"
            // state with no way to recover until they go online. Their
            // existing local board DB stays fully usable in the
            // meantime — they'd only miss the *new* Homewall data
            // until the actual resync runs. The marker stays in
            // sync_states so the next app-start with network re-enters
            // this branch.
            if (!isNetworkAvailable(appContext)) {
                Log.i(TAG, "$reason resync: no network at app start; marker kept, retry on next launch")
                return@launch
            }
            Log.i(TAG, "$reason migration: forcing chunk-hash + timestamp reset for clean re-sync")
            // Wipe the cron-derived catalog rows so the resync runs
            // through the fresh-install fast path inside
            // [BoardDatabaseImporter.importFromChunks] (skips the per-
            // chunk UPDATE pass — without this an ~5min resync drags
            // through 174k+20k correlated subqueries that are mostly
            // no-ops). Cruxcoach-authored climbs (source='local' /
            // 'nostr') are preserved. Browse goes empty during the
            // ~30-60s of resync; the import progress UI is up the
            // whole time so it's framed as "syncing", not "broken".
            boardRepository.deleteKilterCatalogData()
            blossomSyncManager.clearStoredHashes()
            userPreferences.setLastSyncTimestamp(null)
            // Clear markers now (not after sync completes) so a sync
            // failure doesn't trap the user in a perpetual re-clear
            // loop on every app start. Worst case: sync fails, user
            // manually triggers one — chunk hashes are already gone,
            // so the manual retry is itself a full re-sync.
            if (v8) boardRepository.clearPostV8ResyncMarker()
            if (homewall) boardRepository.clearHomewallResyncMarker()
            startBackgroundSync()
        }
    }

    /**
     * Locations-only backfill for the 0.1.4 → 0.1.5 upgrade path.
     *
     * Pre-0.1.5 had no `locations` import branch, but [performBlossomSync]
     * still saved the `locations` chunk's SHA after download, so
     * [BlossomSyncManager.getChangedChunks] now reports it as up-to-date
     * and the normal sync never fills `kilter_board_location`.
     *
     * Runs only on the upgrade case: board DB already imported but the
     * locations table empty. Fresh installs are skipped on purpose —
     * their first sync has no stored hashes, so [performBlossomSync]
     * pulls the locations chunk like any other. Fetches ONLY the
     * locations chunk (never forces a full resync), is idempotent
     * (stops once count > 0, self-heals if a run failed offline), and
     * swallows errors since the chunk is non-essential.
     */
    fun backfillLocationsIfMissing() {
        scope.launch {
            if (!importer.isImported()) return@launch           // fresh install → full sync handles locations
            if (_state.value.isSyncing) return@launch            // a full sync is already importing everything
            if (boardLocationRepository.count() > 0L) return@launch  // already populated → nothing to do
            _locationsBackfilling.value = true
            try {
                val manifest = blossomSyncManager.fetchManifest()
                val locationChunks = manifest.chunks.filter { chunk ->
                    val type = chunk.type.takeIf { it != "unknown" && it.isNotEmpty() }
                        ?: inferType(chunk.name)
                    type == "locations"
                }
                if (locationChunks.isEmpty()) {
                    Log.d(TAG, "Locations backfill: manifest has no locations chunk yet — skipping")
                    return@launch
                }
                Log.i(TAG, "Locations backfill: board present, table empty — fetching ${locationChunks.size} locations chunk(s)")
                val files = mutableListOf<File>()
                for (chunk in locationChunks) {
                    val out = File(appContext.cacheDir, "blossom_${chunk.name}.sqlite3")
                    blossomSyncManager.downloadAndDecompressChunk(chunk = chunk, outputFile = out)
                    files.add(out)
                }
                importer.importFromChunks(
                    metaDbFiles = emptyList(),
                    climbsDbFiles = emptyList(),
                    statsDbFiles = emptyList(),
                    locationsDbFiles = files
                )
                locationChunks.forEach { blossomSyncManager.saveChunkHash(it.name, it.sha256) }
                Log.i(TAG, "Locations backfill: imported ${boardLocationRepository.count()} locations")
            } catch (e: Exception) {
                Log.w(TAG, "Locations backfill failed — will retry on next app start", e)
            } finally {
                _locationsBackfilling.value = false
            }
        }
    }

    fun dismissWifiDialog() {
        _state.update { it.copy(showWifiDialog = false) }
    }

    fun dismissNetworkDialog() {
        _state.update { it.copy(showNetworkDialog = false) }
    }

    /**
     * Called on app start. Checks if data is stale based on user's sync interval
     * and silently starts a Blossom sync if WiFi is available.
     */
    fun syncIfStale() {
        scope.launch {
            val imported = importer.isImported()
            if (!imported) {
                Log.d(TAG, "No data yet — skipping auto-sync")
                return@launch
            }

            // One-time backfill after v1→v2 migration added move_count column
            if (boardRepository.getSyncState("move_count_backfill") == null) {
                Log.d(TAG, "Running one-time move_count backfill...")
                importer.backfillMoveCounts()
                boardRepository.upsertSyncState("move_count_backfill", "done")
                Log.d(TAG, "Move count backfill complete")
            }

            if (_state.value.isSyncing) return@launch

            val interval = userPreferences.syncInterval.first()
            if (interval == SyncInterval.MANUAL) return@launch

            val lastSync = userPreferences.lastSyncTimestamp.first()
            if (!isStale(lastSync, interval)) {
                Log.d(TAG, "Data fresh — no sync needed")
                return@launch
            }

            if (!isWifiConnected(appContext)) {
                Log.d(TAG, "Data stale but no WiFi — skipping auto-sync")
                return@launch
            }

            // Check Blossom manifest for changed chunks
            Log.d(TAG, "Data stale, checking Blossom manifest...")
            try {
                val manifest = blossomSyncManager.fetchManifest()
                userPreferences.setBlossomManifestCreatedAt(manifest.createdAt)
                val changedChunks = blossomSyncManager.getChangedChunks(manifest)
                if (changedChunks.isEmpty()) {
                    Log.d(TAG, "All chunks up to date — skipping auto-sync")
                    val timestamp = DateTimeUtil.nowIso()
                    userPreferences.setLastSyncTimestamp(timestamp)
                    _state.update { it.copy(lastSyncTimestamp = timestamp) }
                    return@launch
                }
                Log.d(TAG, "Changed chunks: ${changedChunks.map { it.name }}")
                startBlossomSync()
            } catch (e: Exception) {
                Log.w(TAG, "Blossom manifest check failed — skipping auto-sync", e)
            }
        }
    }

    /**
     * Atomically claim the sync slot. Returns true if this caller flipped
     * isSyncing from false to true (i.e. wins the race and must proceed);
     * returns false if another sync is already running.
     *
     * Fixes the check-then-set race where two concurrent sync triggers
     * (user tap + auto-sync, Blossom + local share, etc.) could both
     * observe isSyncing=false and start duplicate imports.
     */
    private fun claimSyncSlot(initialStep: ImportStep): Boolean {
        var claimed = false
        _state.update { current ->
            if (current.isSyncing) {
                claimed = false
                current
            } else {
                claimed = true
                current.copy(
                    isSyncing = true,
                    syncComplete = false,
                    errorMessage = null,
                    importStep = initialStep,
                    syncGeneration = current.syncGeneration + 1
                )
            }
        }
        return claimed
    }

    private fun isStale(lastSync: String?, interval: SyncInterval): Boolean {
        if (lastSync == null) return true
        return try {
            val last = LocalDateTime.parse(lastSync).toInstant(TimeZone.currentSystemDefault())
            val hours = (Clock.System.now() - last).inWholeHours
            when (interval) {
                SyncInterval.DAILY -> hours >= 24
                SyncInterval.WEEKLY -> hours >= 168
                SyncInterval.MANUAL -> false
            }
        } catch (e: Exception) { Log.w(TAG, "Stale check failed", e); false }
    }

    /**
     * Stage a local-share import URL for user confirmation.
     *
     * The deep-link handler (cruxcoach://import-board-db) calls this after
     * MainActivity.isAllowedLocalImportUrl has validated the URL is on an
     * RFC1918 / loopback range. The actual download only starts once the
     * user taps confirm on the dialog shown by BoardSyncScreen.
     *
     * The tap on the hotspot's landing page happens in a browser whose
     * contents are controlled by whoever runs the AP — so that tap is not
     * a trustworthy consent signal. The in-app dialog is the real consent
     * moment: it runs in CruxCoach's own UI and shows the source host so
     * the user can refuse an unexpected import.
     */
    fun stageLocalImport(url: String) {
        if (_state.value.isSyncing) return
        _state.update { it.copy(pendingLocalImportUrl = url) }
    }

    fun confirmLocalImport() {
        val url = _state.value.pendingLocalImportUrl ?: return
        _state.update { it.copy(pendingLocalImportUrl = null) }
        performLocalImport(url)
    }

    fun dismissLocalImport() {
        _state.update { it.copy(pendingLocalImportUrl = null) }
    }

    fun startApiSync() {
        Log.d(TAG, "startApiSync() called, isSyncing=${_state.value.isSyncing}")
        if (_state.value.isSyncing) return

        checkNetwork()
        Log.d(TAG, "network=${_state.value.networkAvailable}, wifi=${_state.value.wifiConnected}")
        if (!_state.value.networkAvailable) {
            _state.update { it.copy(showNetworkDialog = true) }
            return
        }
        if (!_state.value.wifiConnected) {
            _state.update { it.copy(showWifiDialog = true) }
            return
        }

        startBlossomSync()
    }

    /**
     * Starts a Blossom-based sync: fetches manifest, downloads changed chunks,
     * decompresses, and imports into the board database.
     */
    private fun startBlossomSync() {
        // Atomic check-and-claim: only the caller that flips isSyncing
        // from false to true is allowed to proceed.
        if (!claimSyncSlot(ImportStep.FetchingManifest)) return

        scope.launch {
            try {
                performBlossomSync()
            } catch (e: Exception) {
                Log.w(TAG, "Blossom sync failed", e)
                // Distinguish network failures (where the "prüfe Internet"
                // hint is actually useful) from local-side import errors
                // (SQLite, parsing, disk) where it's misleading.
                val isNetworkError = e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException ||
                    (e is java.io.IOException && e !is java.io.FileNotFoundException)
                val msg = when {
                    isNetworkError && !importer.isImported() ->
                        "Download fehlgeschlagen. Bitte prüfe deine Internetverbindung und versuche es erneut."
                    isNetworkError ->
                        "Aktualisierung nicht möglich. Bestehende Daten bleiben erhalten."
                    !importer.isImported() ->
                        "Import fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
                    else ->
                        "Aktualisierung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}. Bestehende Daten bleiben erhalten."
                }
                _state.update { it.copy(
                    isSyncing = false,
                    importStep = null,
                    errorMessage = msg
                ) }
            }
        }
    }

    private suspend fun performBlossomSync() {
        // 1. Fetch manifest
        _state.update { it.copy(importStep = ImportStep.FetchingManifest) }
        Log.d(TAG, "Fetching Blossom manifest...")
        val manifest = blossomSyncManager.fetchManifest()
        // Persist manifest timestamp so CommunityClimbSubscriber can seed
        // its cursor on first run — avoids pulling the entire historical
        // Nostr tail when the cron has already merged it into the blob.
        userPreferences.setBlossomManifestCreatedAt(manifest.createdAt)
        Log.d(TAG, "Manifest fetched: ${manifest.chunks.size} chunks")

        // 2. Determine which chunks need downloading
        val chunksToDownload = blossomSyncManager.getChangedChunks(manifest)
        if (chunksToDownload.isEmpty()) {
            Log.d(TAG, "All Blossom chunks are up to date")
            val timestamp = DateTimeUtil.nowIso()
            userPreferences.setLastSyncTimestamp(timestamp)
            _state.update { it.copy(
                isSyncing = false,
                syncComplete = true,
                alreadyImported = true,
                lastSyncTimestamp = timestamp,
                importStep = null,
                lastSyncCompletedAtMillis = System.currentTimeMillis()
            ) }
            return
        }

        // 3. Download and decompress changed chunks (semaphore-bounded parallel).
        //    Chunked-batch + awaitAll() would stall each wave on its slowest
        //    download; a semaphore keeps PARALLEL_DOWNLOADS workers busy with
        //    whichever chunk is next in line.
        val chunkFiles = mutableMapOf<String, File>()
        try {
            val totalAllBytes = chunksToDownload.sumOf { it.size }
            val completedBytes = AtomicLong(0)
            val permits = Semaphore(PARALLEL_DOWNLOADS)

            coroutineScope {
                chunksToDownload.mapIndexed { idx, chunk ->
                    async {
                        permits.withPermit {
                            val outputFile = File(appContext.cacheDir, "blossom_${chunk.name}.sqlite3")
                            blossomSyncManager.downloadAndDecompressChunk(
                                chunk = chunk,
                                outputFile = outputFile,
                                onProgress = { bytesRead, totalBytes ->
                                    val cumulative = completedBytes.get() + bytesRead
                                    _state.update { it.copy(
                                        importStep = ImportStep.DownloadChunk(
                                            chunkName = chunk.name,
                                            chunkIndex = idx,
                                            totalChunks = chunksToDownload.size,
                                            bytesRead = bytesRead,
                                            totalBytes = totalBytes,
                                            cumulativeBytesRead = cumulative,
                                            cumulativeTotalBytes = totalAllBytes
                                        )
                                    ) }
                                }
                            )
                            completedBytes.addAndGet(chunk.size)
                            synchronized(chunkFiles) {
                                chunkFiles[chunk.name] = outputFile
                            }
                            Log.d(TAG, "Chunk ${chunk.name} downloaded+decompressed: ${outputFile.length()} bytes")
                        }
                    }
                }.awaitAll()
            }

            // 4. Group chunks by type and import
            // v2 manifests have type field; v1 fallback uses name matching
            val metaFiles = mutableListOf<File>()
            val climbFiles = mutableListOf<File>()
            val statFiles = mutableListOf<File>()
            val locationFiles = mutableListOf<File>()
            for (chunk in chunksToDownload) {
                val file = chunkFiles[chunk.name] ?: continue
                val resolvedType = chunk.type.takeIf { it != "unknown" && it.isNotEmpty() } ?: inferType(chunk.name)
                when (resolvedType) {
                    "meta" -> metaFiles.add(file)
                    "climbs" -> climbFiles.add(file)
                    "stats" -> statFiles.add(file)
                    "locations" -> locationFiles.add(file)
                }
            }

            Log.d(TAG, "Importing chunks: meta=${metaFiles.size}, climbs=${climbFiles.size}, stats=${statFiles.size}, locations=${locationFiles.size}")
            importer.importFromChunks(
                metaDbFiles = metaFiles,
                climbsDbFiles = climbFiles,
                statsDbFiles = statFiles,
                locationsDbFiles = locationFiles,
                onProgress = { step ->
                    _state.update { it.copy(importStep = step) }
                }
            )
            Log.d(TAG, "Import completed successfully")

            // 5. Refresh denormalized data in SecureDB. UI is already showing
            // Finalizing from the importer's index-rebuild callback, so we
            // just keep that state.
            _state.update { it.copy(
                importStep = com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep.Finalizing
            ) }
            refreshDenormalizedData()

            // 6. Save chunk hashes for incremental updates
            chunksToDownload.forEach { chunk ->
                blossomSyncManager.saveChunkHash(chunk.name, chunk.sha256)
            }

            val timestamp = DateTimeUtil.nowIso()
            userPreferences.setLastSyncTimestamp(timestamp)
            _state.update { it.copy(
                isSyncing = false,
                syncComplete = true,
                alreadyImported = true,
                lastSyncTimestamp = timestamp,
                errorMessage = null,
                importStep = null,
                lastSyncCompletedAtMillis = System.currentTimeMillis()
            ) }
        } finally {
            chunkFiles.values.forEach { it.delete() }
        }
    }

    /** Infer chunk type from name for v1 manifests without type field. */
    private fun inferType(name: String): String = when {
        name == "meta" -> "meta"
        name.startsWith("climbs") -> "climbs"
        name.startsWith("stats") -> "stats"
        name.startsWith("locations") -> "locations"
        else -> "unknown"
    }

    /**
     * Import board DB from a local URL (e.g., WiFi Direct share).
     * Downloads the full uncompressed SQLite file and imports it.
     *
     * Private because callers must go through [stageLocalImport] +
     * [confirmLocalImport] so the user sees a consent dialog before
     * untrusted bytes hit the SQLite parser.
     */
    private fun performLocalImport(url: String) {
        if (!claimSyncSlot(ImportStep.Download(0, 0))) return

        scope.launch {
            val tempFile = File(appContext.cacheDir, "local_board.sqlite3")
            try {
                // Download from local HTTP server
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 30000
                val totalBytes = connection.contentLengthLong
                var bytesRead = 0L
                connection.inputStream.use { input ->
                    java.io.FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(65536)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            _state.update { it.copy(
                                importStep = ImportStep.Download(bytesRead, totalBytes)
                            ) }
                        }
                    }
                }

                // Import the full DB file
                importer.importFromLocalDb(tempFile) { step ->
                    _state.update { it.copy(importStep = step) }
                }

                // Refresh denormalized data in SecureDB
                refreshDenormalizedData()

                val timestamp = DateTimeUtil.nowIso()
                userPreferences.setLastSyncTimestamp(timestamp)
                _state.update { it.copy(
                    isSyncing = false,
                    syncComplete = true,
                    alreadyImported = true,
                    lastSyncTimestamp = timestamp,
                    errorMessage = null,
                    importStep = null,
                    lastSyncCompletedAtMillis = System.currentTimeMillis()
                ) }
            } catch (e: Exception) {
                Log.e(TAG, "Local import failed", e)
                _state.update { it.copy(
                    isSyncing = false,
                    importStep = null,
                    errorMessage = "Lokaler Import fehlgeschlagen: ${e.message}"
                ) }
            } finally {
                tempFile.delete()
            }
        }
    }

    /**
     * Starts a background Blossom sync. Called by [BoardSyncWorker] and
     * auto-sync on stale data.
     */
    fun startBackgroundSync() {
        startBlossomSync()
    }

    fun resetAfterDataDeletion() {
        _state.update { it.copy(
            alreadyImported = false,
            syncComplete = false,
            lastSyncTimestamp = null
        ) }
        // Also wipe the per-chunk SHA-256 cache. Without this the next
        // sync's `getChangedChunks` matches every chunk against its
        // pre-deletion hash, returns an empty diff list, and the import
        // pipeline never runs — leaving the user with an empty board DB
        // and a no-op "Sync abgeschlossen" message. Mirror of
        // `handlePostMigrationResync` which already does this.
        blossomSyncManager.clearStoredHashes()
        scope.launch { userPreferences.setLastSyncTimestamp(null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    /**
     * After a board sync updates climb names/difficulties in BoardDB,
     * refresh the denormalized fields (climb_name, difficulty_average, etc.)
     * in the per-key SecureDB so logbook/list entries stay current.
     */
    private fun refreshDenormalizedData() {
        try {
            val keys = personalBoardRepo.getAllClimbKeys()
            if (keys.isEmpty()) return
            Log.d(TAG, "Refreshing denormalized data for ${keys.size} climb keys")

            // Chunk into batches so the secure-DB write lock is released
            // periodically, letting concurrent user writes (log ascent,
            // favorites toggle, comment edit) interleave instead of
            // blocking for the whole loop on users with many ascents.
            keys.chunked(REFRESH_BATCH_SIZE).forEach { batch ->
                personalBoardRepo.runInTransaction {
                    for ((climbUuid, angle) in batch) {
                        val climb = boardRepository.getClimbByUuid(climbUuid, angle.toInt()) ?: continue
                        personalBoardRepo.updateAscentDenormalized(
                            climbUuid, angle, climb.name, climb.difficultyAverage,
                            climb.frames, climb.framesCount
                        )
                        personalBoardRepo.updateBidDenormalized(
                            climbUuid, angle, climb.name, climb.difficultyAverage
                        )
                    }
                }
            }
            Log.d(TAG, "Denormalized data refresh complete")
        } catch (e: Exception) {
            Log.w(TAG, "Denormalized data refresh failed (non-fatal)", e)
        }
    }
}

data class BoardSyncState(
    val isSyncing: Boolean = false,
    val syncComplete: Boolean = false,
    val errorMessage: String? = null,
    val networkAvailable: Boolean = true,
    val wifiConnected: Boolean = false,
    val showNetworkDialog: Boolean = false,
    val showWifiDialog: Boolean = false,
    val importStep: ImportStep? = null,
    val alreadyImported: Boolean = false,
    val lastSyncTimestamp: String? = null,
    /**
     * A local-share import URL awaiting user confirmation. Set by
     * [BoardSyncManager.stageLocalImport]; cleared on confirm/dismiss.
     * Non-null means the BoardSyncScreen should show the consent dialog.
     */
    val pendingLocalImportUrl: String? = null,
    /** Incremented each time a real sync starts. Banner uses this to ignore initial state. */
    val syncGeneration: Int = 0,
    /**
     * Wall-clock millis when the most recent successful sync finished.
     * Used by [SyncStatusBannerSlot] to render the success banner for a
     * fixed window across screens (not per-screen restart).
     */
    val lastSyncCompletedAtMillis: Long? = null
)
