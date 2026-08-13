package com.cruxcoach.android.data

import android.content.Context
import android.net.Network
import android.util.Log
import com.cruxcoach.android.BuildConfig
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.android.data.blossom.BlossomSyncException
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.notification.BoardSyncWorker
import com.cruxcoach.android.util.isNetworkAvailable
import com.cruxcoach.android.util.isNetworkPermissionGranted
import com.cruxcoach.android.util.isWifiConnected
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.android.util.LocalShareClient
import com.cruxcoach.android.util.LocalShareDiscovery
import com.cruxcoach.android.util.LocalShareNetwork
import com.cruxcoach.android.util.LocalShareProtocol
import com.cruxcoach.android.util.LocalShareResumeStore
import com.cruxcoach.android.util.ShareCompression
import com.cruxcoach.android.updater.IntegrityVerifier
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private val boardLocationRepository: com.cruxcoach.data.repository.BoardLocationRepository,
    private val moonBoardCatalogueSync: MoonBoardCatalogueSync,
    private val auroraCatalogueSync: AuroraCatalogueSync,
    private val integrityVerifier: IntegrityVerifier,
) : CatalogueRevisionSource {
    private companion object {
        const val TAG = "BoardSyncManager"
        /** How long a requested sync may take to actually start before the
         *  user is told the system is holding it back. Long enough for a cold
         *  WorkManager handover, short enough to still read as feedback. */
        const val SYNC_START_GRACE_MS = 8_000L
        /** Missed automatic cycles before the card says so. One skipped run is
         *  ordinary (no unmetered network, battery low); three is a fault. */
        const val MISSED_CYCLES_BEFORE_WARNING = 3
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
        /** Wall-clock cap on locations backfill — stalled chunk downloads
         *  (captive portal, dead TCP socket) would otherwise pin the
         *  in-app "Standorte werden geladen" snackbar indefinitely. */
        const val BACKFILL_TIMEOUT_MS = 120_000L
        /** How long the local-share receiver keeps polling a 503-answering
         *  sender for its board-DB snapshot. Generous on purpose — the
         *  sender's copy + scrub + VACUUM may run minutes on a slow phone,
         *  and every 503 poll proves the sender is alive (a dead one fails
         *  the connect within [CONNECT_MAX_RETRIES] instead). */
        const val SNAPSHOT_WAIT_MAX_MS = 15 * 60_000L
        /** Consecutive connect failures before the local import gives up —
         *  distinguishes "sender gone" (fail fast) from "sender busy
         *  preparing" (503, keep polling). */
        const val CONNECT_MAX_RETRIES = 3
        const val MAX_LOCAL_SHARE_DB_BYTES = 1_024L * 1_024L * 1_024L
        /** Lets Android finish dropping the local-only hotspot before the
         *  status banners re-check which normal network became active. */
        const val LOCAL_SHARE_NETWORK_RECHECK_MS = 2_500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var deferralWatchdog: Job? = null
    private val localShareResumeStore = LocalShareResumeStore(appContext)

    private val _state = MutableStateFlow(BoardSyncState())
    val state: StateFlow<BoardSyncState> = _state.asStateFlow()

    private val _locationsBackfilling = MutableStateFlow(false)
    /** True only while [backfillLocationsIfMissing] is actively fetching /
     *  importing the locations chunk — lets the Map show a real progress
     *  state instead of the misleading "sync the board DB" prompt. */
    val locationsBackfilling: StateFlow<Boolean> = _locationsBackfilling.asStateFlow()

    /**
     * FEAT-049 §3.7. Kept as a projection of [state] so there is exactly one
     * counter and no way for the two to drift; see [CatalogueRevisionSource]
     * for why [BoardSyncState.syncGeneration] cannot serve this purpose.
     */
    override val catalogueRevision: Flow<Int> =
        state.map { it.catalogueRevision }.distinctUntilChanged()

    private val _boardDataDeletion = MutableStateFlow(BoardDataDeletionState())
    /** Progress of the board-catalogue deletion (Settings → "Delete board
     *  data"). App-scoped here so the Settings UI can observe a run that
     *  outlives its own ViewModel — see [deleteBoardData]. */
    val boardDataDeletion: StateFlow<BoardDataDeletionState> = _boardDataDeletion.asStateFlow()

    init {
        scope.safeLaunch(TAG) {
            val imported = importer.isImported()
            val lastSync = userPreferences.lastSyncTimestamp.first()
            _state.update { it.copy(
                alreadyImported = imported,
                syncComplete = imported,
                lastSyncTimestamp = lastSync
            ) }
            resumePendingOfflineShareIfPossible()
        }
    }

    /**
     * Notices when automatic syncing has quietly stopped happening.
     *
     * A background sync has nowhere to report to: the periodic worker runs,
     * every download fails (no network permission, a dead mirror, a captive
     * portal), it returns retry, and the app looks exactly as it does when
     * everything is fine. That is how a catalogue went a month without an
     * update while "daily" was configured and nobody was told.
     *
     * So the age of the last SUCCESSFUL sync is checked against the interval
     * the user chose. Three missed cycles is the threshold — one skipped run
     * is normal (no unmetered network, low battery), three in a row is not.
     */
    fun refreshAutoSyncHealth() {
        scope.safeLaunch(TAG) {
            val interval = userPreferences.syncInterval.first()
            if (interval == SyncInterval.MANUAL) {
                _state.update { it.copy(autoSyncOverdueDays = null) }
                return@safeLaunch
            }
            val last = userPreferences.lastSyncTimestamp.first()
            val lastMillis = last?.let {
                runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            // Never synced at all is a different story — the card already
            // shows the first-run download prompt for that.
            if (lastMillis == null) {
                _state.update { it.copy(autoSyncOverdueDays = null) }
                return@safeLaunch
            }
            val ageDays = ((System.currentTimeMillis() - lastMillis) / 86_400_000L).toInt()
            val cycleDays = if (interval == SyncInterval.WEEKLY) 7 else 1
            val overdue = ageDays >= cycleDays * MISSED_CYCLES_BEFORE_WARNING
            if (overdue) Log.w(TAG, "auto-sync overdue: last success ${ageDays}d ago, interval=$interval")
            _state.update { it.copy(autoSyncOverdueDays = if (overdue) ageDays else null) }
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
        scope.safeLaunch(TAG) {
            // EXISTS-based fast path: getClimbCount() blocks tens of
            // seconds during an active import, and this hook fires at
            // app-start where the user is already waiting on UI render.
            if (!boardRepository.hasAnyClimbs()) return@safeLaunch
            if (boardRepository.getAllPlacements().isNotEmpty()) return@safeLaunch

            Log.w(TAG, "Partial board DB detected (climbs>0, placements=0) — interrupted import; triggering recovery sync")

            if (!isNetworkAvailable(appContext)) {
                Log.w(TAG, "Recovery needed but no network — will retry on next app start")
                return@safeLaunch
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
        scope.safeLaunch(TAG) {
            val v8 = boardRepository.hasPostV8ResyncMarker()
            val homewall = boardRepository.hasHomewallResyncMarker()
            if (!v8 && !homewall) return@safeLaunch
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
                return@safeLaunch
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
            // Deletes every source='kilter' row, MoonBoard's catalogue
            // included — a content change like any other (FEAT-049 §3.7).
            bumpCatalogueRevision()
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
            // Track cache files outside the timeout block so finally can clean
            // them up regardless of which path exits.
            val backfillFiles = mutableListOf<File>()
            try {
                if (!importer.isImported()) return@launch           // fresh install → full sync handles locations
                if (_state.value.isSyncing) return@launch            // a full sync is already importing everything
                if (boardLocationRepository.count() > 0L) return@launch  // already populated → nothing to do
                _locationsBackfilling.value = true
                val completed = withTimeoutOrNull(BACKFILL_TIMEOUT_MS) {
                    val manifest = blossomSyncManager.fetchManifest()
                    val locationChunks = manifest.chunks.filter { chunk ->
                        val type = chunk.type.takeIf { it != "unknown" && it.isNotEmpty() }
                            ?: inferType(chunk.name)
                        type == "locations"
                    }
                    if (locationChunks.isEmpty()) {
                        Log.d(TAG, "Locations backfill: manifest has no locations chunk yet — skipping")
                        return@withTimeoutOrNull true
                    }
                    Log.i(TAG, "Locations backfill: board present, table empty — fetching ${locationChunks.size} locations chunk(s)")
                    for (chunk in locationChunks) {
                        // Disambiguating prefix: a concurrent full sync writes
                        // its chunk cache to `blossom_${chunk.name}.sqlite3`
                        // (BlossomSyncManager-side). The backfill path now
                        // routes through `blossom_backfill_${chunk.name}.sqlite3`
                        // so the two coroutines cannot collide on the same
                        // chunk-cache file during the FEAT-015 upgrade window.
                        val out = File(appContext.cacheDir, "blossom_backfill_${chunk.name}.sqlite3")
                        blossomSyncManager.downloadAndDecompressChunk(chunk = chunk, outputFile = out)
                        backfillFiles.add(out)
                    }
                    importer.importFromChunks(
                        metaDbFiles = emptyList(),
                        climbsDbFiles = emptyList(),
                        statsDbFiles = emptyList(),
                        locationsDbFiles = backfillFiles
                    )
                    locationChunks.forEach { blossomSyncManager.saveChunkHash(it.name, it.sha256) }
                    Log.i(TAG, "Locations backfill: imported ${boardLocationRepository.count()} locations")
                    true
                }
                if (completed == null) {
                    Log.w(TAG, "Locations backfill timed out after ${BACKFILL_TIMEOUT_MS}ms — will retry on next app start")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Locations backfill failed — will retry on next app start", e)
            } finally {
                // Always cleanup the backfill-prefixed cache files so they
                // don't accumulate across retries.
                backfillFiles.forEach { file ->
                    runCatching { if (file.exists()) file.delete() }
                        .onFailure { Log.w(TAG, "Failed to delete backfill cache file ${file.name}", it) }
                }
                _locationsBackfilling.value = false
            }
        }
    }

    fun dismissMeteredConfirm() {
        _state.update { it.copy(showMeteredConfirmDialog = false) }
    }

    /** Consent action of the metered-download dialog: the user explicitly
     *  chose to pull the full catalogue over mobile data (one-time consent;
     *  the background auto-sync paths stay WiFi-only). */
    fun confirmMeteredSync() {
        _state.update { it.copy(showMeteredConfirmDialog = false) }
        startApiSync(bypassWifi = true)
    }

    fun dismissNetworkDialog() {
        _state.update { it.copy(showNetworkDialog = false) }
    }

    /**
     * Called on app start. Checks if data is stale based on user's sync interval
     * and silently starts a Blossom sync if WiFi is available.
     */
    fun syncIfStale() {
        scope.safeLaunch(TAG) {
            val imported = importer.isImported()
            if (!imported) {
                Log.d(TAG, "No data yet — skipping auto-sync")
                return@safeLaunch
            }

            // One-time backfill after v1→v2 migration added move_count column.
            // Bumped to _v2 for FEAT-031: the original backfill counted moves
            // with Kilter's fixed role IDs, leaving every Aurora climb at 0.
            // Re-running once recomputes those with each board's placement_roles
            // (the WHERE move_count = 0 filter skips already-counted climbs).
            if (boardRepository.getSyncState("move_count_backfill_v2") == null) {
                Log.d(TAG, "Running one-time move_count backfill (v2, role-aware)...")
                importer.backfillMoveCounts()
                boardRepository.upsertSyncState("move_count_backfill_v2", "done")
                Log.d(TAG, "Move count backfill complete")
            }

            // FEAT-031: make sure the active non-Kilter board's catalogue is
            // present before the Kilter-gated staleness logic below — otherwise
            // an Aurora/MoonBoard board whose first download failed never gets
            // retried while Kilter stays unchanged (empty-browser bug).
            ensureActiveBoardCatalogue()

            if (_state.value.isSyncing) return@safeLaunch

            val interval = userPreferences.syncInterval.first()
            if (interval == SyncInterval.MANUAL) return@safeLaunch

            val lastSync = userPreferences.lastSyncTimestamp.first()
            if (!isStale(lastSync, interval)) {
                Log.d(TAG, "Data fresh — no sync needed")
                return@safeLaunch
            }

            if (!isWifiConnected(appContext)) {
                Log.d(TAG, "Data stale but no WiFi — skipping auto-sync")
                return@safeLaunch
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
                    return@safeLaunch
                }
                Log.d(TAG, "Changed chunks: ${changedChunks.map { it.name }}")
                // Run under a foreground service so the stale-data
                // auto-sync isn't killed if the user backgrounds the
                // app right after launch.
                BoardSyncWorker.enqueueExpedited(appContext)
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
    private fun claimSyncSlot(initialStep: ImportStep, localShare: Boolean = false): Boolean {
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
                    localShareInProgress = localShare,
                    localShareBoardSteps = if (localShare) {
                        interactiveBoardBrands().associateWith { initialStep }
                    } else {
                        emptyMap()
                    },
                    // Drop the previous run's per-board terminal steps so a
                    // fresh sync doesn't render stale Done rows (and their
                    // old counts) for boards whose lane hasn't started yet.
                    auroraSteps = emptyMap(),
                    syncGeneration = current.syncGeneration + 1
                )
            }
        }
        return claimed
    }

    /**
     * First-onboarding entry point. A newly installed receiver is already on
     * the sender's Wi-Fi after downloading the APK in its browser, so probe
     * that network first. Only when no valid CruxCoach manifest is present do
     * we fall back to the normal online Blossom sync.
     */
    fun startInitialSyncIfNeeded() {
        val current = _state.value
        if (current.alreadyImported || current.isSyncing) return
        // Discovery is only a probe at this point. Do not advertise a nearby
        // transfer in the UI until a valid peer manifest has actually been
        // found; on an ordinary fresh install this probe simply falls through
        // to the online catalogue sync.
        if (!claimSyncSlot(ImportStep.CheckingUpdate)) return

        scope.launch {
            val found = runCatching { LocalShareDiscovery(appContext).discover() }
                .onFailure { Log.w(TAG, "Local-share onboarding discovery failed", it) }
                .getOrNull()
            if (found == null) {
                _state.update {
                    it.copy(
                        isSyncing = false,
                        importStep = null,
                        localShareInProgress = false,
                        localShareBoardSteps = emptyMap(),
                    )
                }
                startApiSync()
                return@launch
            }
            try {
                updateLocalShareProgress(
                    ImportStep.DiscoveringLocalShare,
                    sharedBoardBrands(found.manifest.board),
                )
                runOfflineShare(
                    network = found.network,
                    baseUrl = found.baseUrl,
                    initialManifest = found.manifest,
                    releaseNetwork = {},
                )
            } catch (error: Exception) {
                failOfflineShare(error)
            }
        }
    }

    private fun interactiveBoardBrands(): List<BoardBrand> =
        BoardBrand.entries.filter { it.isInteractive }

    private fun sharedBoardBrands(board: LocalShareProtocol.BoardArtifact?): List<BoardBrand> =
        board?.catalogues
            ?.mapNotNull { BoardBrand.fromWireOrNull(it.boardBrand) }
            ?.filter { it.isInteractive }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: interactiveBoardBrands()

    private fun updateLocalShareProgress(step: ImportStep, brands: List<BoardBrand>) {
        _state.update {
            it.copy(
                importStep = step,
                localShareInProgress = true,
                localShareBoardSteps = brands.associateWith { step },
            )
        }
    }

    private fun completedLocalShareSteps(brands: List<BoardBrand>): Map<BoardBrand, ImportStep> {
        val counts = boardRepository.getClimbCountsByBrand()
        return brands.associateWith { brand ->
            ImportStep.Done(
                climbs = (counts[brand.wireValue] ?: 0L)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                stats = 0,
                placements = 0,
            )
        }
    }

    /**
     * Record that catalogue contents changed (FEAT-049 §3.7): a chunk was
     * committed, or catalogue rows were deleted.
     *
     * Called at the commit/delete points of every catalogue lane, not only
     * MoonBoard's: "the catalogue changed" is what the counter claims, and a
     * consumer that only cares about MoonBoard re-asks its own cheap question
     * one extra time rather than being told a different lie. Additive to those
     * lanes — nothing in the Kilter or Aurora flow reads it or branches on it.
     *
     * Reviewed and kept global on purpose. A MoonBoard-scoped counter would not
     * actually be narrower: the local-share import writes whatever brands its
     * snapshot carries, and both deletion paths remove `source='kilter'` rows
     * across every brand — so MoonBoard's own consumers would still have to be
     * woken from those non-MoonBoard call sites, and the "only MoonBoard moves
     * this" rule would be false the first time someone imported a shared DB.
     * The price of staying global is that a live hold-set picker re-runs its
     * gate probe and two scoped counts on a Kilter or Aurora commit that cannot
     * have changed either. That is bounded (the picker exists only while its
     * settings screen is open) and it buys correctness in the cases above; no
     * regression has been measured from it.
     */
    private suspend fun bumpCatalogueRevision() {
        _state.update { it.copy(catalogueRevision = it.catalogueRevision + 1) }
        // The remembered peer snapshot is a valid no-download gate only while
        // the catalogue has not changed through any other path.
        userPreferences.setLastLocalShareSnapshotSha256(null)
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

    fun stageOfflineShare(invitation: LocalShareProtocol.Invitation) {
        if (_state.value.isSyncing) return
        _state.update { it.copy(pendingOfflineShare = invitation) }
    }

    fun confirmOfflineShare() {
        val invitation = _state.value.pendingOfflineShare ?: return
        _state.update { it.copy(pendingOfflineShare = null) }
        performOfflineShare(invitation)
    }

    fun dismissOfflineShare() {
        _state.update { it.copy(pendingOfflineShare = null) }
    }

    fun dismissLocalShareUpdate() {
        _state.update { it.copy(localShareUpdate = null) }
    }

    fun confirmLocalImport() {
        val url = _state.value.pendingLocalImportUrl ?: return
        _state.update { it.copy(pendingLocalImportUrl = null) }
        performLocalImport(url)
    }

    fun dismissLocalImport() {
        _state.update { it.copy(pendingLocalImportUrl = null) }
    }

    fun startApiSync(bypassWifi: Boolean = false) {
        Log.d(TAG, "startApiSync() called, isSyncing=${_state.value.isSyncing}, bypassWifi=$bypassWifi")
        if (_state.value.isSyncing) return

        // Before connectivity: a revoked INTERNET permission (GrapheneOS makes
        // it revocable) leaves every connectivity check green while our own
        // sockets are refused, so the sync would start and fail on every
        // download with nothing to point at.
        if (!isNetworkPermissionGranted(appContext)) {
            Log.w(TAG, "startApiSync: no INTERNET permission for this app")
            _state.update {
                it.copy(errorMessage = appContext.getString(R.string.board_sync_no_network_permission))
            }
            return
        }

        checkNetwork()
        Log.d(TAG, "network=${_state.value.networkAvailable}, wifi=${_state.value.wifiConnected}")
        if (!_state.value.networkAvailable) {
            _state.update { it.copy(showNetworkDialog = true) }
            return
        }
        // Not on WiFi: no hard block anymore — ask instead. The consent dialog
        // warns about the ~85 MB mobile-data download; its confirm re-enters
        // via confirmMeteredSync() with bypassWifi=true (the same path the
        // explicit per-board Kilter reload from the sync-status list already
        // uses). Background auto-sync (syncIfStale / periodic worker /
        // missing-catalogue autoload) never reaches this prompt — WiFi-only.
        if (!bypassWifi && !_state.value.wifiConnected) {
            _state.update { it.copy(showMeteredConfirmDialog = true) }
            return
        }

        // Execute under a foreground-service worker so the sync
        // survives the app being backgrounded mid-download.
        BoardSyncWorker.enqueueExpedited(appContext)
        watchForSilentDeferral()
    }

    /**
     * Says something when the system keeps a requested sync waiting.
     *
     * Handing work to WorkManager can end in nothing happening at all — Doze,
     * an aggressive battery-optimisation setting, an exhausted expedited quota
     * — and the app has no callback for "the platform has not run this yet".
     * A tap that produces no sync, no progress and no error reads as a dead
     * button, which is exactly how this surfaced in the field. If the worker
     * has not taken over within a few seconds, say so.
     */
    private fun watchForSilentDeferral() {
        deferralWatchdog?.cancel()
        // A delta sync can be done inside the grace period, and "finished"
        // looks exactly like "never started" from isSyncing alone — so the
        // completion stamp is what tells them apart.
        val completedBefore = _state.value.lastSyncCompletedAtMillis
        deferralWatchdog = scope.launch {
            delay(SYNC_START_GRACE_MS)
            if (!_state.value.isSyncing &&
                _state.value.lastSyncCompletedAtMillis == completedBefore &&
                _state.value.errorMessage == null
            ) {
                Log.w(TAG, "sync did not start within ${SYNC_START_GRACE_MS}ms — deferred by the system")
                _state.update {
                    it.copy(errorMessage = appContext.getString(R.string.board_sync_deferred))
                }
            }
        }
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
                // Refresh SQLite query-planner stats now the catalogue may
                // have grown substantially (Kilter + MoonBoard imports both
                // skip ANALYZE inline to keep the "finalizing" phase short).
                // Runs detached, after performBlossomSync already signalled
                // syncComplete, so it never extends the visible sync.
                runCatching { importer.analyzeDatabase() }
                    .onFailure { Log.w(TAG, "Post-sync ANALYZE failed", it) }
            } catch (e: Exception) {
                Log.w(TAG, "Blossom sync failed", e)
                // Distinguish network failures (where the "prüfe Internet"
                // hint is actually useful) from local-side import errors
                // (SQLite, parsing, disk) where it's misleading.
                val isNetworkError = e is java.net.UnknownHostException ||
                    e is java.net.ConnectException ||
                    e is java.net.SocketTimeoutException ||
                    (e is java.io.IOException && e !is java.io.FileNotFoundException)
                // Localized (en/de) + no raw exception text in the UI: the
                // ErrorCard renders this verbatim, so a hardcoded German string
                // or an interpolated e.message (SQLite/IO text, cache paths)
                // would leak to every user. The raw exception is logged above.
                val msg = when {
                    isNetworkError && !importer.isImported() ->
                        appContext.getString(R.string.board_sync_error_download)
                    isNetworkError ->
                        appContext.getString(R.string.board_sync_error_update_offline)
                    !importer.isImported() ->
                        appContext.getString(R.string.board_sync_error_import)
                    else ->
                        appContext.getString(R.string.board_sync_error_update_failed)
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
        _state.update { it.copy(
            importStep = ImportStep.FetchingManifest,
            moonBoardStep = null,
            moonBoardError = null,
        ) }
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
            Log.d(TAG, "All Kilter Blossom chunks are up to date")
            // Kilter catalogue unchanged — mark its checklist section
            // complete so the MoonBoard section is the only one still
            // showing progress while it re-checks.
            _state.update { it.copy(importStep = ImportStep.Done(
                boardRepository.getClimbCount().toInt(),
                boardRepository.getStatCount().toInt(),
                0,
            )) }
            // MoonBoard rides on the same board-data sync (FEAT-027) — re-check
            // it even when the Kilter catalogue itself is unchanged.
            syncMoonBoardCatalogue()
            syncAllAuroraBoards()
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
        // Chunks whose every mirror failed this pass. Recorded (not thrown) so a
        // single unreachable chunk can no longer sink the entire Kilter sync — we
        // import the chunks that DID arrive, save only their hashes, and let the
        // next sync re-fetch just the missing ones via getChangedChunks.
        val failedChunks = mutableListOf<String>()
        try {
            val totalAllBytes = chunksToDownload.sumOf { it.size }
            val completedBytes = AtomicLong(0)
            val permits = Semaphore(PARALLEL_DOWNLOADS)

            coroutineScope {
                chunksToDownload.mapIndexed { idx, chunk ->
                    async {
                        permits.withPermit {
                            val outputFile = File(appContext.cacheDir, "blossom_${chunk.name}.sqlite3")
                            try {
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
                                synchronized(chunkFiles) {
                                    chunkFiles[chunk.name] = outputFile
                                }
                                Log.d(TAG, "Chunk ${chunk.name} downloaded+decompressed: ${outputFile.length()} bytes")
                            } catch (e: Exception) {
                                // Every mirror for this chunk failed (downloadAndVerifyChunk
                                // already retried all mirrors twice). Drop the partial temp
                                // file and record the miss instead of propagating — one bad
                                // chunk must not abort the whole sync and throw away the
                                // ~200-chunk download. Its hash stays unsaved so the next
                                // sync re-fetches only this chunk.
                                outputFile.delete()
                                synchronized(failedChunks) { failedChunks.add(chunk.name) }
                                Log.w(TAG, "Chunk ${chunk.name} failed all mirrors — skipping this pass", e)
                            } finally {
                                // Advance the cumulative counter regardless so the progress
                                // bar still reaches 100% when some chunks were skipped.
                                completedBytes.addAndGet(chunk.size)
                            }
                        }
                    }
                }.awaitAll()
            }

            if (chunkFiles.isEmpty()) {
                // Every chunk failed every mirror — a real failure, not partial
                // progress. Throw so the run is marked failed and BoardSyncWorker
                // retries, instead of importing nothing and marking the sync
                // complete (which on a fresh install would leave an empty catalogue
                // flagged as "already imported").
                throw BlossomSyncException(
                    "Kilter sync failed: all ${chunksToDownload.size} chunk(s) failed every mirror"
                )
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
            var kilterDone: ImportStep.Done? = null
            importer.importFromChunks(
                metaDbFiles = metaFiles,
                climbsDbFiles = climbFiles,
                statsDbFiles = statFiles,
                locationsDbFiles = locationFiles,
                onProgress = { step ->
                    if (step is ImportStep.Done) kilterDone = step
                    _state.update { it.copy(importStep = step) }
                }
            )
            Log.d(TAG, "Import completed successfully")
            bumpCatalogueRevision()

            // 5. Refresh denormalized data in SecureDB. UI is already showing
            // Finalizing from the importer's index-rebuild callback, so we
            // just keep that state.
            _state.update { it.copy(
                importStep = com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep.Finalizing
            ) }
            refreshDenormalizedData()

            // 6. Save chunk hashes for incremental updates — ONLY for chunks that
            // actually downloaded + imported this pass. A skipped (failed) chunk
            // keeps its old/absent hash so the next sync's getChangedChunks still
            // reports it as changed and re-fetches just that chunk.
            chunksToDownload.forEach { chunk ->
                if (chunkFiles.containsKey(chunk.name)) {
                    blossomSyncManager.saveChunkHash(chunk.name, chunk.sha256)
                }
            }
            if (failedChunks.isNotEmpty()) {
                // Partial success: the imported chunks + their saved hashes are
                // durable, so the sync still reports complete (the user sees the
                // data that arrived). getChangedChunks will re-report the skipped
                // chunks on the next sync and the catalogue converges — no full
                // re-download, no hard failure.
                Log.w(
                    TAG,
                    "Kilter sync imported ${chunkFiles.size}/${chunksToDownload.size} chunks; " +
                        "${failedChunks.size} failed all mirrors, will retry next sync: " +
                        failedChunks.take(8).joinToString()
                )
            }

            // 7. MoonBoard catalogue — synced as part of the board-data sync.
            //    Mark the Kilter section complete first so the card shows
            //    Kilter done + MoonBoard in progress as two distinct sections.
            _state.update { it.copy(
                importStep = kilterDone ?: ImportStep.Done(
                    boardRepository.getClimbCount().toInt(),
                    boardRepository.getStatCount().toInt(),
                    0,
                )
            ) }
            syncMoonBoardCatalogue()
            syncAllAuroraBoards()

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

    /**
     * Sync the MoonBoard catalogue as part of the board-data sync (FEAT-027).
     * Idempotent — when the published MoonBoard manifest is unchanged it
     * short-circuits to AlreadyCurrent, so periodic re-checks are cheap.
     * Progress is forwarded to [BoardSyncState.moonBoardStep] so the
     * MoonBoard download shows as its own section in the sync card,
     * separate from the Kilter [BoardSyncState.importStep]. Failures are
     * logged + surfaced via [BoardSyncState.moonBoardError] — a MoonBoard
     * hiccup must never fail the Kilter board sync.
     */
    private suspend fun syncMoonBoardCatalogue(): Boolean {
        return try {
            when (val result = moonBoardCatalogueSync.sync(
                onProgress = { step -> _state.update { it.copy(moonBoardStep = step) } }
            )) {
                is MoonBoardCatalogueSync.Result.AlreadyCurrent -> {
                    Log.d(TAG, "MoonBoard catalogue already current")
                    // No import ran — mark the section complete anyway so
                    // the user sees the MoonBoard catalogue is accounted for.
                    _state.update { it.copy(moonBoardStep = ImportStep.Done(0, 0, 0)) }
                    false
                }
                is MoonBoardCatalogueSync.Result.Imported -> {
                    Log.i(TAG, "MoonBoard catalogue imported (total catalogue climbs=${result.climbCount})")
                    // The commit that can flip the FEAT-049 presence gate, and
                    // the moment the browser's mask cache must re-ask it. Still
                    // inside the same sync run, so syncGeneration says nothing.
                    bumpCatalogueRevision()
                    true
                }
                is MoonBoardCatalogueSync.Result.Failed -> {
                    Log.w(TAG, "MoonBoard catalogue sync failed: ${result.message}")
                    _state.update { it.copy(moonBoardStep = null, moonBoardError = result.message) }
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MoonBoard catalogue sync threw — Kilter board sync unaffected", e)
            _state.update { it.copy(
                moonBoardStep = null,
                moonBoardError = e.message ?: e.javaClass.simpleName,
            ) }
            false
        }
    }

    /**
     * Sync the active Aurora-family board's catalogue as part of the board-data
     * sync (FEAT-031), so Tension / Grasshopper / Decoy / So iLL / Touchstone
     * get ongoing catalogue updates and their own progress section in the sync
     * card. Single-active-board model: only the currently-selected Aurora board
     * is synced. Idempotent and isolated — a failure never fails the Kilter
     * sync (mirrors [syncMoonBoardCatalogue]).
     */
    private suspend fun syncActiveAuroraBoard() {
        val brand = BoardBrand.fromWire(userPreferences.boardBrand.first())
        // Kilter + MoonBoard have their own lanes above; only the non-Kilter
        // Aurora family is handled here.
        if (!brand.usesAuroraProtocol || brand == BoardBrand.KILTER) return
        syncAuroraBoard(brand)
    }

    /**
     * Sync EVERY interactive Aurora board's catalogue (FEAT-031). Product
     * decision 2026-06-11: the full board-data sync — onboarding first sync,
     * the manual re-download button, and the scheduled background sync —
     * loads ALL boards, not just already-loaded ones, so a fresh install or
     * a post-deletion re-download restores the complete multiboard catalogue
     * without per-board activation. The bins are small (0.3–26 MB gz) and
     * unchanged boards short-circuit to AlreadyCurrent on every later run,
     * so repeat syncs stay cheap.
     */
    private suspend fun syncAllAuroraBoards() {
        BoardBrand.entries
            .filter { it.usesAuroraProtocol && it != BoardBrand.KILTER }
            .forEach { syncAuroraBoard(it) }
    }

    /**
     * Sync one specific Aurora board's catalogue — not necessarily the active
     * one, so the sync status list (FEAT-031) can load/retry any board on
     * demand. Reports into the per-board progress/error map; isolated — never
     * throws into the caller, a failure never fails the Kilter sync.
     */
    private suspend fun syncAuroraBoard(brand: BoardBrand): Boolean {
        return try {
            when (val result = auroraCatalogueSync.sync(brand) { step -> reportBoardStep(brand, step) }) {
                is AuroraCatalogueSync.Result.AlreadyCurrent -> {
                    reportBoardStep(brand, ImportStep.Done(0, 0, 0))
                    false
                }
                is AuroraCatalogueSync.Result.Imported -> {
                    Log.i(TAG, "${brand.wireValue} catalogue imported (total catalogue climbs=${result.climbCount})")
                    bumpCatalogueRevision()
                    true
                }
                is AuroraCatalogueSync.Result.Failed -> {
                    Log.w(TAG, "${brand.wireValue} catalogue sync failed: ${result.message}")
                    reportBoardStep(brand, null)
                    reportBoardError(brand, result.message)
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aurora catalogue sync threw — Kilter board sync unaffected", e)
            reportBoardStep(brand, null)
            reportBoardError(brand, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    /**
     * FEAT-031: guarantee the *active* non-Kilter board's catalogue is present,
     * independent of the Kilter manifest/staleness gate that drives the rest of
     * [syncIfStale]. Kilter has its own lane; MoonBoard + Aurora catalogues were
     * otherwise only synced when the Kilter manifest happened to change, so an
     * active board whose first download failed (transient network) stayed empty
     * indefinitely — the empty-browser symptom. Runs only when that board has
     * zero local climbs, and only on WiFi (a manual load from the status list
     * can override the WiFi gate).
     */
    private suspend fun ensureActiveBoardCatalogue() {
        val brand = BoardBrand.fromWire(userPreferences.boardBrand.first())
        if (brand == BoardBrand.KILTER) return  // Kilter handled by the main lane
        val loaded = withContext(Dispatchers.IO) {
            (boardRepository.getClimbCountsByBrand()[brand.wireValue] ?: 0L) > 0L
        }
        if (loaded) return
        if (!isWifiConnected(appContext)) {
            Log.d(TAG, "${brand.wireValue} catalogue missing but no WiFi — deferring to manual load")
            return
        }
        Log.i(TAG, "Active board ${brand.wireValue} has no catalogue — auto-loading")
        if (!claimSyncSlot(ImportStep.FetchingManifest)) return
        // Board-specific load: clear the Kilter importStep the slot-claim set so
        // only this board's row shows progress (not a phantom Kilter row).
        _state.update { it.copy(importStep = null) }
        var imported = false
        try {
            imported = if (brand == BoardBrand.MOONBOARD) syncMoonBoardCatalogue() else syncAuroraBoard(brand)
        } finally {
            finishSyncSlot()
        }
        // FEAT-037B: refresh planner stats for the freshly-imported single
        // board. The full Blossom sync analyzes via startBlossomSync; this
        // on-demand path otherwise left stale stats → slow first browse.
        analyzeAfterSingleBoardImport(imported)
    }

    /**
     * Load (or retry) a specific board's catalogue on demand from the sync
     * status list (FEAT-031), without changing the active board. Unlike the
     * auto path it's explicit user intent, so it bypasses the WiFi gate.
     * Reports into the per-board map so the row shows the same step checklist.
     */
    fun loadBoardCatalogue(brand: BoardBrand) {
        if (brand == BoardBrand.KILTER) { startApiSync(bypassWifi = true); return }
        if (!claimSyncSlot(ImportStep.FetchingManifest)) return
        // Board-specific load: clear the Kilter importStep the slot-claim set so
        // only this board's row shows progress (not a phantom Kilter row).
        _state.update { it.copy(importStep = null) }
        scope.launch {
            var imported = false
            try {
                imported = if (brand == BoardBrand.MOONBOARD) syncMoonBoardCatalogue() else syncAuroraBoard(brand)
            } finally {
                finishSyncSlot()
            }
            // FEAT-037B: refresh planner stats for the freshly-imported board.
            analyzeAfterSingleBoardImport(imported)
        }
    }

    /** Release the sync slot after a board-scoped catalogue load, flipping the
     *  card back to the idle status list (the per-board Done step persists). */
    private fun finishSyncSlot() {
        _state.update { it.copy(
            isSyncing = false,
            syncComplete = true,
            importStep = null,
            lastSyncCompletedAtMillis = System.currentTimeMillis(),
        ) }
    }

    /**
     * FEAT-037B: refresh SQLite query-planner stats after a single-board
     * on-demand catalogue import ([ensureActiveBoardCatalogue] / [loadBoardCatalogue]).
     * The full Blossom sync already runs [BoardDatabaseImporter.analyzeDatabase]
     * once after [performBlossomSync]; the single-board picker paths previously
     * skipped it, leaving stale stats so the first browse of a freshly-imported
     * Aurora/MoonBoard board mis-planned and ran slow. Detached on [scope] so it
     * never extends the visible sync, and import-gated because ANALYZE is a full
     * pass (10-30s) — pointless when the board was already current.
     */
    private fun analyzeAfterSingleBoardImport(imported: Boolean) {
        if (!imported) return
        scope.launch {
            runCatching { importer.analyzeDatabase() }
                .onFailure { Log.w(TAG, "Post single-board import ANALYZE failed", it) }
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
     * Report an Aurora-family board's catalogue-sync progress into the shared
     * per-board state map (FEAT-031). Lets a sync triggered elsewhere (e.g. the
     * Settings board picker via AuroraCatalogueSync) surface in the same sync
     * card as the Kilter + MoonBoard streams. Pass null to clear it.
     */
    fun reportBoardStep(brand: BoardBrand, step: ImportStep?) {
        _state.update {
            it.copy(auroraSteps = if (step == null) it.auroraSteps - brand else it.auroraSteps + (brand to step))
        }
    }

    /** Report (or clear, with null) an Aurora board's non-fatal sync error. */
    fun reportBoardError(brand: BoardBrand, error: String?) {
        _state.update {
            it.copy(auroraErrors = if (error == null) it.auroraErrors - brand else it.auroraErrors + (brand to error))
        }
    }

    /** Continue the board import after PackageInstaller replaced the app. */
    private suspend fun resumePendingOfflineShareIfPossible() {
        val pending = localShareResumeStore.load() ?: return
        if (pending.requiredVersionCode > BuildConfig.VERSION_CODE.toLong()) {
            val apk = pending.apkPath?.let(::File)?.takeIf { it.isFile }
            if (apk == null) {
                localShareResumeStore.clear()
                return
            }
            _state.update {
                it.copy(
                    localShareUpdate = LocalShareUpdate(
                        apkPath = apk.absolutePath,
                        versionName = pending.apkVersionName ?: "",
                    ),
                )
            }
            return
        }

        val compressed = pending.boardPath?.let(::File)?.takeIf { it.isFile }
        val board = pending.board
        if (compressed == null || board == null) {
            pending.apkPath?.let(::File)?.delete()
            localShareResumeStore.clear()
            return
        }
        if (!claimSyncSlot(ImportStep.Extract, localShare = true)) return
        try {
            importDownloadedBoard(compressed, board)
            pending.apkPath?.let(::File)?.delete()
            localShareResumeStore.clear()
            val timestamp = DateTimeUtil.nowIso()
            userPreferences.setLastSyncTimestamp(timestamp)
            _state.update {
                it.copy(
                    isSyncing = false,
                    syncComplete = true,
                    alreadyImported = true,
                    lastSyncTimestamp = timestamp,
                    importStep = null,
                    errorMessage = null,
                    localShareInProgress = false,
                    lastSyncCompletedAtMillis = System.currentTimeMillis(),
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Resuming board import after local APK update failed", error)
            _state.update {
                it.copy(
                    isSyncing = false,
                    importStep = null,
                    localShareInProgress = false,
                    localShareBoardSteps = emptyMap(),
                    errorMessage = appContext.getString(R.string.board_sync_error_import),
                )
            }
        }
    }

    private suspend fun importDownloadedBoard(
        compressed: File,
        board: LocalShareProtocol.BoardArtifact,
    ) {
        val brands = sharedBoardBrands(board)
        require(board.uncompressedSizeBytes in 1..MAX_LOCAL_SHARE_DB_BYTES) {
            "Shared board snapshot exceeds size limit"
        }
        if (compressed.length() != board.artifact.sizeBytes ||
            LocalShareProtocol.sha256(compressed) != board.artifact.sha256
        ) {
            throw java.io.IOException("Compressed board snapshot failed verification")
        }
        val raw = File(
            appContext.cacheDir,
            "local_board_${board.uncompressedSha256.take(16)}.sqlite3",
        )
        try {
            updateLocalShareProgress(ImportStep.VerifyingSnapshot, brands)
            raw.delete()
            require(board.compression == "gzip") { "Unsupported share compression" }
            ShareCompression.gunzip(
                inputFile = compressed,
                outputFile = raw,
                maxOutputBytes = board.uncompressedSizeBytes,
            ) { outputBytes ->
                updateLocalShareProgress(
                    ImportStep.Decompress(outputBytes, board.uncompressedSizeBytes),
                    brands,
                )
            }
            updateLocalShareProgress(ImportStep.VerifyingSnapshot, brands)
            if (raw.length() != board.uncompressedSizeBytes ||
                LocalShareProtocol.sha256(raw) != board.uncompressedSha256
            ) {
                throw java.io.IOException("Shared board snapshot failed verification")
            }
            importer.importFromLocalDb(raw) { step ->
                // ImportStep.Done is emitted before the manager's own
                // denormalized refresh and durable hash bookkeeping. Keep the
                // visible run in Finalizing until all of that is actually done.
                updateLocalShareProgress(
                    if (step is ImportStep.Done) ImportStep.Finalizing else step,
                    brands,
                )
            }
            updateLocalShareProgress(ImportStep.Finalizing, brands)
            bumpCatalogueRevision()
            userPreferences.setLastLocalShareSnapshotSha256(board.uncompressedSha256)
            refreshDenormalizedData()
            val completedSteps = completedLocalShareSteps(brands)
            _state.update { current ->
                current.copy(
                    localShareBoardSteps = completedSteps,
                )
            }
            compressed.delete()
        } finally {
            raw.delete()
        }
    }

    /**
     * Runs the v1 one-scan offline-share flow. The sender always advertises
     * APK + board artifacts; this receiver compares the manifest first and
     * transfers only a newer APK and/or a board snapshot it has not already
     * imported. All HTTP connections are opened on the explicitly requested
     * share Wi-Fi. Closing that scoped request immediately after the last byte
     * lets Android restore the receiver's previous Wi-Fi before decompression
     * and SQLite import begin.
     */
    private fun performOfflineShare(invitation: LocalShareProtocol.Invitation) {
        if (!claimSyncSlot(ImportStep.FetchingManifest, localShare = true)) return

        scope.launch {
            try {
                val networkSession = LocalShareNetwork(appContext).connect(invitation)
                runOfflineShare(
                    network = networkSession.network,
                    baseUrl = invitation.baseUrl,
                    initialManifest = null,
                    releaseNetwork = networkSession::close,
                )
            } catch (error: Exception) {
                failOfflineShare(error)
            }
        }
    }

    private suspend fun runOfflineShare(
        network: Network,
        baseUrl: String,
        initialManifest: LocalShareProtocol.Manifest?,
        releaseNetwork: () -> Unit,
    ) {
        val client = LocalShareClient()
        var boardDownload: File? = null
        var apkDownload: File? = null
        var boardArtifact: LocalShareProtocol.BoardArtifact? = null
        val receivedManifest: LocalShareProtocol.Manifest
        try {
            val initial = initialManifest
            receivedManifest = if (initial != null && initial.boardStatus != "preparing") {
                initial
            } else {
                updateLocalShareProgress(
                    if (initial?.boardStatus == "preparing") {
                        ImportStep.PreparingSnapshot
                    } else {
                        ImportStep.FetchingManifest
                    },
                    interactiveBoardBrands(),
                )
                client.awaitReadyManifest(
                    network = network,
                    baseUrl = baseUrl,
                    timeoutMs = SNAPSHOT_WAIT_MAX_MS,
                    onPreparing = {
                        updateLocalShareProgress(
                            ImportStep.PreparingSnapshot,
                            interactiveBoardBrands(),
                        )
                    },
                )
            }

            val offeredBoard = receivedManifest.board
            val brands = sharedBoardBrands(offeredBoard)
            updateLocalShareProgress(ImportStep.CheckingUpdate, brands)
            val lastSnapshotHash = userPreferences.lastLocalShareSnapshotSha256.first()
            val needsBoard = offeredBoard != null &&
                offeredBoard.uncompressedSha256 != lastSnapshotHash
            if (needsBoard) {
                boardArtifact = offeredBoard
                val target = File(
                    appContext.cacheDir,
                    "local_board_${offeredBoard.artifact.sha256.take(20)}.gz",
                )
                boardDownload = client.downloadResumable(
                    network = network,
                    baseUrl = baseUrl,
                    artifact = offeredBoard.artifact,
                    target = target,
                    onVerifying = {
                        updateLocalShareProgress(ImportStep.VerifyingSnapshot, brands)
                    },
                ) { downloaded, total ->
                    updateLocalShareProgress(ImportStep.Download(downloaded, total), brands)
                }
            } else if (offeredBoard == null && !importer.isImported()) {
                throw java.io.IOException("Sender has no board snapshot")
            }

            if (receivedManifest.apkVersionCode > BuildConfig.VERSION_CODE.toLong()) {
                val safeVersion = receivedManifest.apkVersionName
                    .replace(Regex("[^0-9A-Za-z._-]"), "_")
                val target = File(
                    appContext.cacheDir,
                    "local_share_update_${safeVersion}_${receivedManifest.apk.sha256.take(16)}.apk",
                )
                apkDownload = client.downloadResumable(
                    network = network,
                    baseUrl = baseUrl,
                    artifact = receivedManifest.apk,
                    target = target,
                    onVerifying = {
                        updateLocalShareProgress(ImportStep.VerifyingApk, brands)
                    },
                ) { downloaded, total ->
                    updateLocalShareProgress(ImportStep.DownloadApk(downloaded, total), brands)
                }
            }
            runCatching { client.notifyDownloadComplete(network, baseUrl) }
                .onFailure { Log.w(TAG, "Could not notify sender that downloads completed", it) }
        } finally {
            // The explicit one-scan path releases its scoped Wi-Fi request at
            // this exact boundary, before decompression/import. Android can
            // immediately restore the receiver's previous Wi-Fi. The fresh-
            // install discovery path did not create the connection, so its
            // callback is intentionally a no-op.
            releaseNetwork()
        }

        val downloadedApk = apkDownload
        val readyUpdate = if (downloadedApk != null) {
            when (integrityVerifier.verify(downloadedApk, receivedManifest.apk.sha256)) {
                IntegrityVerifier.Result.Ok -> LocalShareUpdate(
                    apkPath = downloadedApk.absolutePath,
                    versionName = receivedManifest.apkVersionName,
                )
                else -> {
                    downloadedApk.delete()
                    throw SecurityException("Shared APK failed integrity verification")
                }
            }
        } else {
            null
        }

        val offeredBoard = boardArtifact
        val compressedBoard = boardDownload
        var boardImported = false
        if (readyUpdate != null) {
            // The running (older) app must not parse a snapshot from a newer
            // schema. Persist both verified downloads; the new APK resumes the
            // local import on its first launch.
            localShareResumeStore.save(
                LocalShareResumeStore.Pending(
                    requiredVersionCode = receivedManifest.apkVersionCode,
                    apkPath = readyUpdate.apkPath,
                    apkVersionName = readyUpdate.versionName,
                    boardPath = compressedBoard?.absolutePath,
                    board = offeredBoard,
                ),
            )
        } else if (offeredBoard != null && compressedBoard != null) {
            importDownloadedBoard(compressedBoard, offeredBoard)
            boardImported = true
        }

        val timestamp = DateTimeUtil.nowIso()
        if (boardImported) userPreferences.setLastSyncTimestamp(timestamp)
        val imported = importer.isImported()
        val terminalBoardSteps = if (readyUpdate != null && compressedBoard != null) {
            // A newer app will resume this downloaded board snapshot. Do not
            // leave non-terminal spinners behind in the old process.
            emptyMap()
        } else {
            completedLocalShareSteps(sharedBoardBrands(receivedManifest.board))
        }
        val networkAvailable = isNetworkAvailable(appContext)
        val wifiConnected = isWifiConnected(appContext)
        _state.update {
            it.copy(
                isSyncing = false,
                syncComplete = imported,
                alreadyImported = imported,
                lastSyncTimestamp = if (boardImported) timestamp else it.lastSyncTimestamp,
                errorMessage = null,
                importStep = null,
                localShareInProgress = false,
                localShareBoardSteps = terminalBoardSteps,
                localShareUpdate = readyUpdate,
                networkAvailable = networkAvailable,
                wifiConnected = wifiConnected,
                lastSyncCompletedAtMillis = System.currentTimeMillis(),
            )
        }
        refreshNetworkAfterLocalShare()
    }

    private fun failOfflineShare(error: Throwable) {
        Log.e(TAG, "Offline share failed", error)
        _state.update {
            it.copy(
                isSyncing = false,
                importStep = null,
                localShareInProgress = false,
                localShareBoardSteps = emptyMap(),
                errorMessage = appContext.getString(R.string.board_sync_error_import),
            )
        }
        refreshNetworkAfterLocalShare()
    }

    private fun refreshNetworkAfterLocalShare() {
        scope.launch {
            delay(LOCAL_SHARE_NETWORK_RECHECK_MS)
            checkNetwork()
        }
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
                // Poll the sender until its scrubbed board-DB snapshot is
                // ready. The sender answers every request instantly: 200 +
                // stream once the snapshot exists, 503 + Retry-After while it
                // is still building (copy + scrub + VACUUM can take a while
                // on a slow phone). Polling instead of one long silent wait
                // means no read-timeout can ever race the snapshot build —
                // the failure mode that surfaced as "timeout". Each poll also
                // re-arms the sender's idle auto-shutdown and (on failure)
                // re-triggers the build, so transient causes heal themselves.
                val deadlineMs = System.currentTimeMillis() + SNAPSHOT_WAIT_MAX_MS
                var connectFailures = 0
                var connection: java.net.HttpURLConnection
                while (true) {
                    connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 60_000
                    val code = try {
                        connection.responseCode
                    } catch (e: java.io.IOException) {
                        // Connect-level hiccup (WiFi-Direct renegotiation,
                        // sender busy). A dead sender fails all retries fast.
                        connection.disconnect()
                        if (++connectFailures > CONNECT_MAX_RETRIES) throw e
                        Log.w(TAG, "Local import: connect failed (${e.message}), retry $connectFailures/$CONNECT_MAX_RETRIES")
                        kotlinx.coroutines.delay(3_000)
                        continue
                    }
                    connectFailures = 0
                    if (code == java.net.HttpURLConnection.HTTP_OK) break
                    val retryAfterSec = parseRetryAfterSeconds(connection.getHeaderField("Retry-After"))
                    connection.errorStream?.close()
                    connection.disconnect()
                    if (code != java.net.HttpURLConnection.HTTP_UNAVAILABLE ||
                        System.currentTimeMillis() >= deadlineMs
                    ) {
                        throw java.io.IOException("HTTP $code")
                    }
                    Log.i(TAG, "Local import: sender still preparing snapshot — retrying in ${retryAfterSec}s")
                    kotlinx.coroutines.delay(retryAfterSec * 1000)
                }
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
                bumpCatalogueRevision()

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
                    // Same rule as the sync path above: the ErrorCard renders
                    // this verbatim, so no German literal and no e.message.
                    errorMessage = appContext.getString(R.string.board_sync_error_import)
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

    /**
     * Delete the board catalogue of the selected [brands] in the
     * application-level [scope]. SettingsViewModel used to run this in
     * viewModelScope: the delete takes ~20s on a full multi-board
     * catalogue, so leaving the Settings screen — or killing the app —
     * cancelled the coroutine and SQLite silently rolled the transaction
     * back, leaving the user with a "deleted" confirmation flow but an
     * intact catalogue. Progress is observable via [boardDataDeletion];
     * start / end / duration / selected brands are logged so a field
     * report can be diagnosed from logcat.
     *
     * Selecting every interactive board routes through the historical
     * [BoardRepository.deleteAllBoardData] full wipe (which now shares
     * the same local/nostr-climb protection); any subset takes the
     * per-brand scoped path.
     */
    fun deleteBoardData(brands: Set<BoardBrand>) {
        if (brands.isEmpty()) return
        // Atomic check-and-claim, mirrors claimSyncSlot: only the caller
        // that flips running from false to true starts the delete.
        var claimed = false
        _boardDataDeletion.update { current ->
            if (current.running) {
                claimed = false
                current
            } else {
                claimed = true
                current.copy(running = true)
            }
        }
        if (!claimed) return
        scope.launch {
            val startMs = System.currentTimeMillis()
            val brandNames = brands.map { it.wireValue }.sorted().joinToString(",")
            val allBoards = brands.containsAll(BoardBrand.entries.filter { it.isInteractive })
            Log.i(TAG, "destructive: deleteBoardData(brands=[$brandNames], allBoards=$allBoards) started")
            try {
                if (allBoards) {
                    boardRepository.deleteAllBoardData()
                    resetAfterDataDeletion()
                } else {
                    boardRepository.deleteBoardDataForBrands(brands.map { it.wireValue }.toSet())
                    resetSyncStateForBrands(brands)
                }
                // Catalogue contents changed in the other direction: a gate
                // that was true is now false, and anything holding a mask
                // derived from it has to re-ask (FEAT-049 §3.7).
                bumpCatalogueRevision()
                Log.i(TAG, "destructive: deleteBoardData(brands=[$brandNames]) done in ${System.currentTimeMillis() - startMs}ms")
                _boardDataDeletion.update { it.copy(running = false, completions = it.completions + 1) }
            } catch (e: Exception) {
                Log.e(TAG, "destructive: deleteBoardData(brands=[$brandNames]) failed after ${System.currentTimeMillis() - startMs}ms", e)
                _boardDataDeletion.update { it.copy(running = false) }
            }
        }
    }

    fun resetAfterDataDeletion() {
        resetSyncStateForBrands(BoardBrand.entries.filter { it.isInteractive }.toSet())
    }

    /**
     * Per-board freshness reset after a catalogue deletion: each selected
     * board's chunk-hash store must go with its data. Without this the
     * next sync's `getChangedChunks` matches every chunk against its
     * pre-deletion hash, returns an empty diff list, and the import
     * pipeline never runs — leaving the user with an empty board DB and a
     * no-op "Sync abgeschlossen" message (mirror of
     * `handlePostMigrationResync`, which clears the Kilter hashes for the
     * same reason). Kilter's store is the injected [blossomSyncManager]
     * ("blossom_sync" prefs); every other interactive board keeps its
     * hashes in its own "blossom_sync_<wire>" prefs file (see
     * MoonBoardCatalogueSync / AuroraCatalogueSync). UNSELECTED boards'
     * stores stay intact so their incremental sync state survives.
     *
     * The Kilter-centric global flags (alreadyImported / syncComplete /
     * lastSyncTimestamp) gate the main Blossom sync, so they reset only
     * when Kilter itself is among [brands] — a MoonBoard-only deletion
     * must not advertise the Kilter catalogue as missing.
     */
    private fun resetSyncStateForBrands(brands: Set<BoardBrand>) {
        if (BoardBrand.KILTER in brands) {
            _state.update { it.copy(
                alreadyImported = false,
                syncComplete = false,
                lastSyncTimestamp = null
            ) }
            blossomSyncManager.clearStoredHashes()
            scope.launch { userPreferences.setLastSyncTimestamp(null) }
        }
        if (BoardBrand.MOONBOARD in brands) {
            appContext.getSharedPreferences(
                BlossomSyncManager.MOONBOARD_PREFS_NAME, Context.MODE_PRIVATE
            ).edit().clear().apply()
        }
        brands
            .filter { it.usesAuroraProtocol && it != BoardBrand.KILTER }
            .forEach { board ->
                appContext.getSharedPreferences(
                    "blossom_sync_${board.wireValue}", Context.MODE_PRIVATE
                ).edit().clear().apply()
            }
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
                        // Also back-fills board_brand + layout_id, self-healing
                        // legacy / restored rows that defaulted to kilter/NULL.
                        personalBoardRepo.updateAscentDenormalized(
                            climbUuid, angle, climb.name, climb.difficultyAverage,
                            climb.frames, climb.framesCount,
                            climb.boardBrand, climb.layoutId
                        )
                        personalBoardRepo.updateBidDenormalized(
                            climbUuid, angle, climb.name, climb.difficultyAverage,
                            climb.boardBrand, climb.layoutId
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

/**
 * Parses a `Retry-After` header (seconds form) for the local-share snapshot
 * poll, clamped to a sane [2s, 15s] window: never hammer the sender faster
 * than every 2s, never let a bogus header stall a poll for minutes. The
 * HTTP-date form and garbage both fall back to 5s.
 */
internal fun parseRetryAfterSeconds(header: String?): Long =
    (header?.trim()?.toLongOrNull() ?: 5L).coerceIn(2L, 15L)

/**
 * Observable progress of the board-catalogue deletion. [completions] is a
 * monotonic success counter so the Settings UI can show the success banner
 * exactly once per finished run — a plain running-flag transition could not
 * distinguish success from failure.
 */
data class BoardDataDeletionState(
    val running: Boolean = false,
    val completions: Int = 0,
)

data class LocalShareUpdate(
    val apkPath: String,
    val versionName: String,
)

data class BoardSyncState(
    val isSyncing: Boolean = false,
    val syncComplete: Boolean = false,
    val errorMessage: String? = null,
    val networkAvailable: Boolean = true,
    val wifiConnected: Boolean = false,
    val showNetworkDialog: Boolean = false,
    /** Metered-download consent: set when a user-triggered full sync starts
     *  without WiFi; confirm proceeds over mobile data, dismiss cancels. */
    val showMeteredConfirmDialog: Boolean = false,
    val importStep: ImportStep? = null,
    /**
     * Progress of the MoonBoard catalogue sync (FEAT-027), tracked
     * separately from [importStep] so the sync card can show the Kilter
     * and MoonBoard catalogues as two distinct sections. Null until the
     * MoonBoard phase starts.
     */
    val moonBoardStep: ImportStep? = null,
    /** Set when the MoonBoard catalogue sync failed — surfaced as a
     *  non-fatal note; the Kilter sync is never failed by a MoonBoard
     *  hiccup. */
    val moonBoardError: String? = null,
    val alreadyImported: Boolean = false,
    val lastSyncTimestamp: String? = null,
    /**
     * A local-share import URL awaiting user confirmation. Set by
     * [BoardSyncManager.stageLocalImport]; cleared on confirm/dismiss.
     * Non-null means the BoardSyncScreen should show the consent dialog.
     */
    val pendingLocalImportUrl: String? = null,
    /** One-scan invitation awaiting the in-app trust confirmation. */
    val pendingOfflineShare: LocalShareProtocol.Invitation? = null,
    /** A newer, hash- and signer-verified APK downloaded from the peer. */
    val localShareUpdate: LocalShareUpdate? = null,
    /** True from local sender discovery through the final local DB refresh. */
    val localShareInProgress: Boolean = false,
    /** Per-catalogue projection of the shared full-DB import. A local snapshot
     *  contains all brands in shared tables; mapping the common ingest phase
     *  onto every advertised brand prevents the old Kilter-only spinner. */
    val localShareBoardSteps: Map<BoardBrand, ImportStep> = emptyMap(),
    /** Incremented each time a real sync starts. Banner uses this to ignore initial state. */
    val syncGeneration: Int = 0,
    /**
     * Incremented each time catalogue CONTENTS changed: after a catalogue
     * commit and after a catalogue deletion. Deliberately not [syncGeneration],
     * which marks a run being claimed *before* any import — see
     * [CatalogueRevisionSource] for what keying a cached fact on the wrong one
     * costs. Observed through [BoardSyncManager.catalogueRevision].
     */
    val catalogueRevision: Int = 0,
    /**
     * Wall-clock millis when the most recent successful sync finished.
     * Used by [SyncStatusBannerSlot] to render the success banner for a
     * fixed window across screens (not per-screen restart).
     */
    val lastSyncCompletedAtMillis: Long? = null,
    /**
     * Days since the last successful sync, but only once automatic syncing
     * has plainly stopped working (see
     * [BoardSyncManager.refreshAutoSyncHealth]). Null while it is doing its
     * job — this exists to break the silence, not to nag.
     */
    val autoSyncOverdueDays: Int? = null,
    /**
     * FEAT-031: per-Aurora-board catalogue-sync progress + non-fatal errors,
     * keyed by board family (Tension / Grasshopper / Decoy / So iLL /
     * Touchstone). Kilter + MoonBoard keep their dedicated [importStep] /
     * [moonBoardStep] / [moonBoardError] fields above; [boardSteps] /
     * [boardErrors] unify all of them into one per-board map for the sync card.
     */
    val auroraSteps: Map<BoardBrand, ImportStep> = emptyMap(),
    val auroraErrors: Map<BoardBrand, String> = emptyMap(),
) {
    /** Unified per-board sync progress (FEAT-031): the Kilter + MoonBoard
     *  streams plus every Aurora board, keyed by brand and ordered
     *  Kilter → MoonBoard → Aurora. Drives the per-board sync-card sections so
     *  the UI is map-driven rather than two hardcoded streams. */
    val boardSteps: Map<BoardBrand, ImportStep>
        get() = buildMap {
            importStep?.let { put(BoardBrand.KILTER, it) }
            moonBoardStep?.let { put(BoardBrand.MOONBOARD, it) }
            putAll(auroraSteps)
            // Local share is a multi-brand full-DB path. Put it last so its
            // truthful per-catalogue state overrides the legacy Kilter-only
            // projection of importStep while this run is active/completing.
            putAll(localShareBoardSteps)
        }

    /** Unified per-board non-fatal sync errors (FEAT-031). */
    val boardErrors: Map<BoardBrand, String>
        get() = buildMap {
            moonBoardError?.let { put(BoardBrand.MOONBOARD, it) }
            putAll(auroraErrors)
        }
}
