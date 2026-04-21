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
    private val personalBoardRepo: com.cruxcoach.data.repository.PersonalBoardRepository
) {
    private companion object {
        const val TAG = "BoardSyncManager"
        /** Max concurrent chunk downloads. */
        const val PARALLEL_DOWNLOADS = 4
        /** Denormalized-field refresh batch size — keeps per-transaction
         *  write-lock hold time short so user writes can interleave. */
        const val REFRESH_BATCH_SIZE = 100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BoardSyncState())
    val state: StateFlow<BoardSyncState> = _state.asStateFlow()

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

    /** User acknowledged "up to date" — just dismiss and mark sync complete. */
    fun forceSync() {
        _state.update { it.copy(
            showUpToDateDialog = false,
            syncComplete = true,
            alreadyImported = true
        ) }
    }

    fun dismissUpToDateDialog() {
        _state.update { it.copy(showUpToDateDialog = false) }
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
                showUpToDateDialog = true,
                lastSyncCompletedAtMillis = System.currentTimeMillis()
            ) }
            return
        }

        // 3. Download and decompress changed chunks (parallel, max 4 concurrent)
        val chunkFiles = mutableMapOf<String, File>()
        try {
            val totalAllBytes = chunksToDownload.sumOf { it.size }
            val completedBytes = AtomicLong(0)

            coroutineScope {
                chunksToDownload.chunked(PARALLEL_DOWNLOADS).forEach { batch ->
                    batch.mapIndexed { batchIdx, chunk ->
                        val globalIdx = chunksToDownload.indexOf(chunk)
                        async {
                            val outputFile = File(appContext.cacheDir, "blossom_${chunk.name}.sqlite3")
                            blossomSyncManager.downloadAndDecompressChunk(
                                chunk = chunk,
                                outputFile = outputFile,
                                onProgress = { bytesRead, totalBytes ->
                                    val cumulative = completedBytes.get() + bytesRead
                                    _state.update { it.copy(
                                        importStep = ImportStep.DownloadChunk(
                                            chunkName = chunk.name,
                                            chunkIndex = globalIdx,
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
                    }.awaitAll()
                }
            }

            // 4. Group chunks by type and import
            // v2 manifests have type field; v1 fallback uses name matching
            val metaFiles = mutableListOf<File>()
            val climbFiles = mutableListOf<File>()
            val statFiles = mutableListOf<File>()
            for (chunk in chunksToDownload) {
                val file = chunkFiles[chunk.name] ?: continue
                val resolvedType = chunk.type.takeIf { it != "unknown" && it.isNotEmpty() } ?: inferType(chunk.name)
                when (resolvedType) {
                    "meta" -> metaFiles.add(file)
                    "climbs" -> climbFiles.add(file)
                    "stats" -> statFiles.add(file)
                }
            }

            Log.d(TAG, "Importing chunks: meta=${metaFiles.size}, climbs=${climbFiles.size}, stats=${statFiles.size}")
            importer.importFromChunks(
                metaDbFiles = metaFiles,
                climbsDbFiles = climbFiles,
                statsDbFiles = statFiles,
                onProgress = { step ->
                    _state.update { it.copy(importStep = step) }
                }
            )
            Log.d(TAG, "Import completed successfully")

            // 5. Refresh denormalized data in SecureDB
            _state.update { it.copy(
                importStep = com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep.Finalizing(
                    "Eigene Daten aktualisieren"
                )
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
    val showUpToDateDialog: Boolean = false,
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
