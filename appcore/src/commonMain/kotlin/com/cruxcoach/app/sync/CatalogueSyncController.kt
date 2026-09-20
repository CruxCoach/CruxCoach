package com.cruxcoach.app.sync

import com.cruxcoach.app.platform.FileSystem
import com.cruxcoach.app.platform.Hashing
import com.cruxcoach.app.platform.HttpTransport
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.platform.WebSocketConnector
import com.cruxcoach.app.platform.ZstdDecompressor
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

enum class CatalogueSyncPhase { IDLE, CHECKING, DOWNLOADING, VERIFYING, IMPORTING, UP_TO_DATE, DONE, FAILED }

enum class CatalogueSyncFailure {
    NONE,
    /** No relay returned a manifest that passed the trust checks. */
    MANIFEST_UNAVAILABLE,
    /** Signed and current, but not the shape this board's lane accepts. */
    MANIFEST_INVALID,
    OFFLINE,
    DOWNLOAD_FAILED,
    /** Some Kilter chunks arrived and were imported; the rest are fetched by the next run. */
    PARTIAL_DOWNLOAD,
    INSUFFICIENT_STORAGE,
    DECOMPRESSION_FAILED,
    SOURCE_REJECTED,
    IMPORT_FAILED,
    UNSUPPORTED_BRAND,
    CANCELLED,
}

data class BrandSyncState(
    val brand: BoardBrand,
    val phase: CatalogueSyncPhase = CatalogueSyncPhase.IDLE,
    val receivedBytes: Long = 0,
    val totalBytes: Long = 0,
    val failure: CatalogueSyncFailure = CatalogueSyncFailure.NONE,
    /** Catalogue climbs of this brand after a successful import; 0 otherwise. */
    val climbCount: Long = 0,
)

data class CatalogueSyncState(
    val running: Boolean = false,
    val brands: List<BrandSyncState> = emptyList(),
    val installedBrands: List<BoardBrand> = emptyList(),
    /** Bumped whenever catalogue contents changed, so browsers know to re-query. */
    val catalogueRevision: Int = 0,
    /**
     * False until the first database read has answered which catalogues are
     * installed. The UI must not conclude "nothing installed" before this, or a
     * returning user is shown first-run onboarding for a moment.
     */
    val installedKnown: Boolean = false,
)

/**
 * Swift-facing catalogue sync: fetch signed manifest → download changed chunks
 * → verify → inflate → import, one board after another. Imports never overlap
 * (the importer requires a single writer), only Kilter's downloads fan out.
 */
class CatalogueSyncController internal constructor(
    http: HttpTransport,
    private val files: FileSystem,
    hashing: Hashing,
    zstd: ZstdDecompressor,
    private val keyValues: KeyValueStore,
    private val clock: WallClock,
    private val database: BoardDatabaseHandle,
    dispatcher: CoroutineDispatcher,
    /** Seam for tests only: the publisher key is pinned, so a test cannot sign its own manifest events. */
    private val fetchManifest: suspend (dTag: String) -> ManifestFetchResult,
) {
    constructor(
        webSockets: WebSocketConnector,
        http: HttpTransport,
        files: FileSystem,
        hashing: Hashing,
        zstd: ZstdDecompressor,
        keyValues: KeyValueStore,
        clock: WallClock,
        database: BoardDatabaseHandle,
    ) : this(
        http, files, hashing, zstd, keyValues, clock, database, Dispatchers.Default,
        ManifestFetcher(webSockets, hashing, clock)::fetch,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val downloader = ChunkDownloader(http, files, hashing, zstd)
    private val importer = CatalogueImporter(database)
    private val _state = MutableStateFlow(CatalogueSyncState())
    private var job: Job? = null

    val state: StateFlow<CatalogueSyncState> = _state.asStateFlow()

    init {
        scope.launch {
            try {
                refreshInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The board database may be unreadable or already closed. An
                // empty installed list is the honest answer; an exception here
                // has no receiver and would take the process down with it.
            }
        }
    }

    val installedBrands: List<BoardBrand> get() = _state.value.installedBrands

    /** Ignored while a run is active; non-interactive brands are dropped. */
    fun start(brands: List<BoardBrand>) {
        val wanted = brands.filter { it.isInteractive }.distinct()
        if (wanted.isEmpty()) return
        var claimed = false
        _state.update { current ->
            if (current.running) current.also { claimed = false }
            else current.copy(running = true, brands = wanted.map { BrandSyncState(it, CatalogueSyncPhase.CHECKING) }).also { claimed = true }
        }
        if (!claimed) return
        job = scope.launch {
            var changed = false
            try {
                for (brand in wanted) {
                    try {
                        if (syncBrand(brand)) {
                            changed = true
                            _state.update { it.copy(catalogueRevision = it.catalogueRevision + 1) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fail(brand, CatalogueSyncFailure.IMPORT_FAILED)
                    }
                }
                if (changed) importer.analyze()
            } finally {
                refreshInstalledQuietly()
                _state.update { current ->
                    current.copy(
                        running = false,
                        brands = current.brands.map {
                            if (it.phase in TERMINAL) it else it.copy(phase = CatalogueSyncPhase.FAILED, failure = CatalogueSyncFailure.CANCELLED)
                        },
                    )
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Forget a board's incremental markers after its catalogue was deleted, so the next run re-downloads it. */
    fun resetBrand(brand: BoardBrand) {
        track(brand)?.let { ManifestTrackStore(keyValues, it.prefsName, clock).clear() }
    }

    /**
     * Settings deleted these catalogues behind the controller's back. Their
     * chunk hashes have to go with the data — otherwise the next run diffs the
     * new manifest against pre-deletion hashes, finds nothing changed and
     * leaves the user with an empty board and a "up to date" message. The
     * revision bump tells every screen holding catalogue-derived state to re-ask.
     */
    fun catalogueDeleted(brands: List<BoardBrand>) {
        brands.forEach { resetBrand(it) }
        scope.launch {
            refreshInstalledQuietly()
            _state.update { it.copy(catalogueRevision = it.catalogueRevision + 1) }
        }
    }

    // ── lanes ────────────────────────────────────────────────────────

    private class Track(val dTag: String, val prefsName: String, val importVersion: String?)

    private fun track(brand: BoardBrand): Track? = when {
        brand == BoardBrand.KILTER -> Track(CatalogueTrust.KILTER_D_TAG, CatalogueTrust.KILTER_PREFS, CatalogueTrust.BETA_IMPORT_VERSION)
        brand == BoardBrand.MOONBOARD -> Track(CatalogueTrust.MOONBOARD_D_TAG, CatalogueTrust.MOONBOARD_PREFS, null)
        brand.usesAuroraProtocol -> Track(
            CatalogueTrust.auroraDTag(brand.wireValue), CatalogueTrust.auroraPrefs(brand.wireValue), CatalogueTrust.BETA_IMPORT_VERSION,
        )
        else -> null
    }

    /** True when catalogue contents changed. */
    private suspend fun syncBrand(brand: BoardBrand): Boolean {
        // Quantum's importer is not ported; say so rather than download something that cannot be applied.
        val track = track(brand) ?: return false.also { fail(brand, CatalogueSyncFailure.UNSUPPORTED_BRAND) }
        update(brand) { it.copy(phase = CatalogueSyncPhase.CHECKING) }
        val store = ManifestTrackStore(keyValues, track.prefsName, clock)
        if (brand == BoardBrand.KILTER) consumeResyncMarkers(store)
        val manifest = when (val fetched = fetchManifest(track.dTag)) {
            is ManifestFetchResult.Found -> fetched.manifest
            is ManifestFetchResult.NotFound -> return false.also { fail(brand, CatalogueSyncFailure.MANIFEST_UNAVAILABLE) }
        }
        // A stale (rolled-back) manifest is a normal no-update result: keep what is installed.
        if (!store.canApplyManifest(manifest)) return false.also { finish(brand, CatalogueSyncPhase.UP_TO_DATE) }
        val changed = if (brand == BoardBrand.KILTER) {
            store.getChangedChunks(manifest, track.importVersion, CatalogueTrust::isBetaChunk)
        } else {
            store.getChangedChunks(manifest, track.importVersion)
        }
        if (changed.isEmpty()) {
            store.saveAcceptedManifestTimestamp(manifest)
            return false.also { finish(brand, CatalogueSyncPhase.UP_TO_DATE) }
        }
        return if (brand == BoardBrand.KILTER) syncKilter(manifest, changed, store)
        else syncSnapshot(brand, manifest, changed, store, track.importVersion)
    }

    private suspend fun syncSnapshot(
        brand: BoardBrand,
        manifest: CatalogueManifest,
        changed: List<CatalogueChunk>,
        store: ManifestTrackStore,
        importVersion: String?,
    ): Boolean {
        // Snapshots are single-chunk by design; importing only the first of several would be silent data loss.
        if (changed.size != 1) return false.also { fail(brand, CatalogueSyncFailure.MANIFEST_INVALID) }
        val chunk = changed.single()
        val output = "${files.workDirectory()}/${brand.wireValue}_${chunk.name}.sqlite3"
        try {
            update(brand) { it.copy(phase = CatalogueSyncPhase.DOWNLOADING, receivedBytes = 0, totalBytes = chunk.size) }
            val downloaded = downloader.downloadAndDecompress(
                chunk, manifest.compression, output,
                onProgress = { received, _ -> update(brand) { it.copy(phase = CatalogueSyncPhase.DOWNLOADING, receivedBytes = received) } },
                onVerifying = { update(brand) { it.copy(phase = CatalogueSyncPhase.VERIFYING) } },
            )
            if (downloaded is ChunkResult.Failed) return false.also { fail(brand, downloaded.reason.toSyncFailure()) }
            update(brand) { it.copy(phase = CatalogueSyncPhase.IMPORTING) }
            val imported = if (brand == BoardBrand.MOONBOARD) importer.importMoonBoardSnapshot(output)
            else importer.importAuroraSnapshot(output, brand.wireValue)
            return when (imported) {
                is ImportResult.Failed -> false.also { fail(brand, imported.reason.toSyncFailure()) }
                is ImportResult.Imported -> {
                    // Hash and rollback watermark move together, and only after the import committed.
                    store.saveCompletedManifest(manifest, changed, importVersion)
                    finish(brand, CatalogueSyncPhase.DONE, imported.climbs)
                    true
                }
            }
        } finally {
            files.delete(output)
        }
    }

    private suspend fun syncKilter(manifest: CatalogueManifest, changed: List<CatalogueChunk>, store: ManifestTrackStore): Boolean {
        val brand = BoardBrand.KILTER
        val total = changed.sumOf { it.size }
        update(brand) { it.copy(phase = CatalogueSyncPhase.DOWNLOADING, receivedBytes = 0, totalBytes = total) }
        val arrived = HashMap<String, String>()
        val failures = ArrayList<ChunkFailure>()
        val inFlight = HashMap<String, Long>()
        var completedBytes = 0L
        val lock = Mutex()
        try {
            val permits = Semaphore(PARALLEL_DOWNLOADS)
            coroutineScope {
                changed.map { chunk ->
                    async {
                        permits.withPermit {
                            val output = "${files.workDirectory()}/blossom_${chunk.name}.sqlite3"
                            val result = downloader.downloadAndDecompress(
                                chunk, manifest.compression, output,
                                onProgress = { received, _ ->
                                    // tryLock: progress is advisory and this callback may not suspend.
                                    if (lock.tryLock()) {
                                        try {
                                            inFlight[chunk.name] = received
                                            val sum = completedBytes + inFlight.values.sum()
                                            update(brand) { it.copy(receivedBytes = sum.coerceAtMost(total)) }
                                        } finally { lock.unlock() }
                                    }
                                },
                            )
                            lock.withLock {
                                inFlight.remove(chunk.name)
                                // Counted either way so the bar still completes when a chunk is skipped.
                                completedBytes += chunk.size
                                when (result) {
                                    is ChunkResult.Ok -> arrived[chunk.name] = result.path
                                    is ChunkResult.Failed -> { files.delete(output); failures += result.reason }
                                }
                                update(brand) { it.copy(receivedBytes = completedBytes.coerceAtMost(total)) }
                            }
                        }
                    }
                }.awaitAll()
            }
            // Nothing arrived: a real failure, not partial progress.
            if (arrived.isEmpty()) {
                val reason = failures.firstOrNull { it != ChunkFailure.ALL_MIRRORS_FAILED } ?: ChunkFailure.ALL_MIRRORS_FAILED
                return false.also { fail(brand, reason.toSyncFailure()) }
            }
            update(brand) { it.copy(phase = CatalogueSyncPhase.IMPORTING) }
            fun filesOf(type: String) = changed.filter { CatalogueTrust.resolvedType(it) == type }.mapNotNull { arrived[it.name] }
            val imported = importer.importKilterChunks(
                metaFiles = filesOf("meta"), climbFiles = filesOf("climbs"), statFiles = filesOf("stats"),
                locationFiles = filesOf("locations"), betaFiles = filesOf("beta"),
            )
            if (imported is ImportResult.Failed) return false.also { fail(brand, imported.reason.toSyncFailure()) }
            if (failures.isEmpty()) {
                store.saveCompletedManifest(manifest, changed, CatalogueTrust.BETA_IMPORT_VERSION)
                finish(brand, CatalogueSyncPhase.DONE, (imported as ImportResult.Imported).climbs)
            } else {
                // Keep what arrived, leave the watermark alone: the next run fetches only the missing chunks.
                for (chunk in changed) if (chunk.name in arrived) {
                    store.saveChunkHash(chunk.name, chunk.sha256, CatalogueTrust.BETA_IMPORT_VERSION.takeIf { CatalogueTrust.isBetaChunk(chunk) })
                }
                fail(brand, CatalogueSyncFailure.PARTIAL_DOWNLOAD)
            }
            return true
        } finally {
            changed.forEach { files.delete("${files.workDirectory()}/blossom_${it.name}.sqlite3") }
        }
    }

    /**
     * A schema migration that wiped Kilter rows leaves a marker in sync_states.
     * Without dropping the chunk hashes too, the next run would see "all
     * chunks current" over an empty catalogue.
     */
    private fun consumeResyncMarkers(store: ManifestTrackStore) {
        val queries = database.database.boardQueries
        val v8 = queries.hasPostV8ResyncMarker().executeAsOneOrNull() != null
        val homewall = queries.hasHomewallResyncMarker().executeAsOneOrNull() != null
        if (!v8 && !homewall) return
        database.database.transaction {
            queries.deleteKilterSourceClimbStats()
            queries.deleteKilterSourceClimbs()
        }
        store.clear()
        if (v8) queries.clearPostV8ResyncMarker()
        if (homewall) queries.clearHomewallResyncMarker()
        _state.update { it.copy(catalogueRevision = it.catalogueRevision + 1) }
    }

    // ── state plumbing ───────────────────────────────────────────────

    private fun update(brand: BoardBrand, change: (BrandSyncState) -> BrandSyncState) {
        _state.update { current -> current.copy(brands = current.brands.map { if (it.brand == brand) change(it) else it }) }
    }

    private fun fail(brand: BoardBrand, failure: CatalogueSyncFailure) =
        update(brand) { it.copy(phase = CatalogueSyncPhase.FAILED, failure = failure) }

    private fun finish(brand: BoardBrand, phase: CatalogueSyncPhase, climbs: Long = 0) =
        update(brand) { it.copy(phase = phase, failure = CatalogueSyncFailure.NONE, climbCount = climbs, receivedBytes = it.totalBytes) }

    private fun refreshInstalledQuietly() {
        try { refreshInstalled() } catch (e: Exception) { /* state keeps the previous list */ }
    }

    private fun refreshInstalled() {
        val wires = database.database.boardQueries.countClimbsByBrand().executeAsList()
            .filter { it.climbCount > 0 }.map { it.boardBrand }.toSet()
        _state.update {
            it.copy(installedBrands = BoardBrand.entries.filter { b -> b.wireValue in wires }, installedKnown = true)
        }
    }

    private fun ChunkFailure.toSyncFailure() = when (this) {
        ChunkFailure.INVALID_CHUNK, ChunkFailure.UNSUPPORTED_COMPRESSION -> CatalogueSyncFailure.MANIFEST_INVALID
        ChunkFailure.INSUFFICIENT_STORAGE -> CatalogueSyncFailure.INSUFFICIENT_STORAGE
        ChunkFailure.ALL_MIRRORS_FAILED -> CatalogueSyncFailure.DOWNLOAD_FAILED
        ChunkFailure.OFFLINE -> CatalogueSyncFailure.OFFLINE
        ChunkFailure.CANCELLED -> CatalogueSyncFailure.CANCELLED
        ChunkFailure.DECOMPRESSION_FAILED -> CatalogueSyncFailure.DECOMPRESSION_FAILED
    }

    private fun ImportFailure.toSyncFailure() = when (this) {
        ImportFailure.SOURCE_REJECTED -> CatalogueSyncFailure.SOURCE_REJECTED
        ImportFailure.DATABASE_ERROR -> CatalogueSyncFailure.IMPORT_FAILED
        ImportFailure.UNSUPPORTED -> CatalogueSyncFailure.UNSUPPORTED_BRAND
    }

    private companion object {
        /** Four streams fill a mobile link without tripping per-IP limits on mirrors that share a provider. */
        const val PARALLEL_DOWNLOADS = 4
        val TERMINAL = setOf(CatalogueSyncPhase.UP_TO_DATE, CatalogueSyncPhase.DONE, CatalogueSyncPhase.FAILED)
    }
}
