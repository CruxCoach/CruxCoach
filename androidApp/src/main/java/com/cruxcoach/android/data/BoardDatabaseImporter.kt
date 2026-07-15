package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardClimbParser
import java.io.File
import java.io.FileOutputStream

/**
 * Imports the Kilter Board database into our SQLDelight tables.
 *
 * Sources:
 * - **Blossom** ([importFromChunks]): Imports from 3 separate chunk files (meta, climbs, stats)
 * - **Online legacy** ([downloadAndImport]): Delegates APK download and DB extraction
 *   to [ApkDownloader], then imports the extracted file.
 *
 * All produce temp SQLite files with the Kilter board schema (Aurora-style: climbs, climb_stats,
 * placements, holes, etc.), then bulk-insert into SQLDelight.
 */
class BoardDatabaseImporter(
    private val context: Context,
    private val boardRepository: BoardRepository,
    private val apkDownloader: ApkDownloader
) {
    companion object {
        private const val TAG = "BoardImporter"
        private const val BATCH_SIZE = 500
        private const val BULK_BATCH_SIZE = 10_000

        // Hot-path indexes for the climbs table — dropped before bulk
        // import + recreated afterwards. Must stay byte-equivalent (modulo
        // `IF NOT EXISTS`) to DatabaseFactory.HOT_PATH_INDEX_DDL —
        // HotPathIndexDriftTest asserts both sets agree.
        internal val CLIMB_INDEXES = arrayOf(
            "idx_climbs_listed" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_listed ON climbs(is_listed)",
            "idx_climbs_frames_count" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_frames_count ON climbs(is_listed, frames_count, uuid)",
            // FEAT-003 + 0.1.4 community-climb indexes. Added to the
            // bulk-import drop/rebuild dance so each INSERT during a fresh
            // 270k-row import doesn't pay 6 extra index-maintenance writes
            // per row — pre-fix, fresh installs spent 6+ minutes in the
            // climbs phase on slower-eMMC devices because these indexes
            // were live throughout. Keep this list byte-equivalent (modulo
            // `IF NOT EXISTS`) to DatabaseFactory.HOT_PATH_INDEX_DDL.
            "idx_climbs_source" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_source ON climbs(source)",
            "idx_climbs_frames_hash" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_frames_hash ON climbs(frames_hash)",
            "idx_climbs_pubkey" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_pubkey ON climbs(created_by_pubkey)",
            "idx_climbs_origin" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_origin ON climbs(origin)",
            "idx_climbs_kilter_status" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_kilter_status ON climbs(kilter_status)",
            "idx_climbs_nostr_via" to
                    "CREATE INDEX IF NOT EXISTS idx_climbs_nostr_via ON climbs(nostr_publish_via)",
        )

        internal val STAT_INDEXES = arrayOf(
            "idx_climb_stats_angle" to
                    "CREATE INDEX IF NOT EXISTS idx_climb_stats_angle ON climb_stats(angle)",
            "idx_climb_stats_browse" to
                    "CREATE INDEX IF NOT EXISTS idx_climb_stats_browse ON climb_stats(layout_id, angle, difficulty_average, quality_average, ascensionist_count, benchmark_difficulty, climb_uuid)",
            "idx_climb_stats_by_popularity" to
                    "CREATE INDEX IF NOT EXISTS idx_climb_stats_by_popularity ON climb_stats(layout_id, angle, ascensionist_count, difficulty_average, climb_uuid)",
            "idx_climb_stats_count_cover" to
                    "CREATE INDEX IF NOT EXISTS idx_climb_stats_count_cover ON climb_stats(layout_id, angle, ascensionist_count, difficulty_average, benchmark_difficulty, climb_uuid)"
        )
    }

    /** Returns true if board data has already been imported (including layout data).
     *  Uses the EXISTS-based fast path — boardRepository.getClimbCount()
     *  is a full table-scan that blocks on the importer's writer-lock
     *  (~28s on slower-eMMC), unacceptable for the BoardSyncManager's
     *  startup-decision read that needs to feel instant. */
    fun isImported(): Boolean {
        return boardRepository.hasAnyClimbs()
    }

    fun getClimbCount(): Long = boardRepository.getClimbCount()
    fun getStatCount(): Long = boardRepository.getStatCount()
    fun climbExistsByUuid(uuid: String): Boolean = boardRepository.climbExistsByUuid(uuid)
    fun statExistsByUuid(uuid: String): Boolean = boardRepository.statExistsByUuid(uuid)

    /** Returns true if board layout data (placements, sizes, images) needs importing. */
    fun needsLayoutImport(): Boolean {
        return boardRepository.getAllPlacements().isEmpty()
    }

    /** Delegates to [ApkDownloader.checkForUpdate]. */
    fun checkForUpdate(): ApkDownloader.UpdateCheck =
        apkDownloader.checkForUpdate()

    /** Delegates to [ApkDownloader.saveApkVersionCode]. */
    fun saveApkVersionCode(versionCode: String) = apkDownloader.saveApkVersionCode(versionCode)

    // ── Public entry points ──────────────────────────────────────────

    /**
     * Blossom import: imports from multiple SQLite chunk files grouped by type.
     * Supports monthly chunking (v2): N climb chunks + N stat chunks + 1 meta chunk.
     *
     * Import order: climbs first, then stats, then meta (layout data).
     */
    // @Synchronized: all five board-DB writers serialise on this @Singleton
    // importer's monitor (reentrant — the imports call backfillMoveCounts
    // internally). Single writer at a time → no concurrent ATTACH/index-DDL,
    // no SQLITE_BUSY from a backfill racing a sync (#3 concurrency cluster).
    @Synchronized
    fun importFromChunks(
        metaDbFiles: List<File>,
        climbsDbFiles: List<File>,
        statsDbFiles: List<File>,
        locationsDbFiles: List<File> = emptyList(),
        onProgress: ((step: ImportStep) -> Unit)? = null
    ) {
        val snapshot = loadExistingSnapshot()
        // Snapshot once — fresh-install vs incremental gates the per-
        // chunk UPDATE passes inside [importClimbs] / [importClimbStats].
        // Without this, only the FIRST chunk runs the fast path because
        // subsequent chunks observe a non-empty target and incorrectly
        // re-enable the (semantically no-op) UPDATE pass over rows we
        // just inserted.
        val freshInstallClimbs: Boolean = openTargetDb().let { db ->
            try { queryLong(db, "SELECT COUNT(*) FROM climbs") == 0L }
            finally { db.close() }
        }
        val freshInstallStats: Boolean = openTargetDb().let { db ->
            try { queryLong(db, "SELECT COUNT(*) FROM climb_stats") == 0L }
            finally { db.close() }
        }

        // Pre-count totals across all chunks (COUNT(*) is ~instant on SQLite)
        val climbChunkCounts = climbsDbFiles.map { file ->
            openReadOnly(file) { db ->
                val table = resolveClimbsTable(db)
                queryLong(db, "SELECT COUNT(*) FROM $table WHERE is_listed = 1").toInt()
            }
        }
        val statChunkCounts = statsDbFiles.map { file ->
            openReadOnly(file) { db ->
                val table = resolveStatsTable(db)
                queryLong(db, "SELECT COUNT(*) FROM $table").toInt()
            }
        }
        val grandClimbTotal = climbChunkCounts.sum()
        val grandStatTotal = statChunkCounts.sum()

        // Drop indexes before bulk import, rebuild after (avoids per-row index
        // maintenance). Layout/meta import is also inside the block so its
        // INSERTs benefit, and the UI sees a single clean phase progression:
        // Climbs → Stats → Layout → Finalizing (rebuild + backfill + denorm).
        withDeferredIndexes(
            onRebuild = { onProgress?.invoke(ImportStep.Finalizing) }
        ) {
            // Import all climb chunks (bulk ATTACH or row-by-row fallback).
            // Single shared target connection across chunks: avoids
            // re-running 4 PRAGMAs per chunk + keeps the climbs.uuid PK
            // B-tree warm in the page cache between chunks (otherwise
            // each new connection starts cold and re-faults the same
            // pages we just read in the previous chunk).
            //
            // wal_autocheckpoint = 0 disables the default 1000-page
            // ( ~4 MB) auto-checkpoint that otherwise stops the writer
            // mid-import once the WAL grows past the threshold. We
            // checkpoint(TRUNCATE) explicitly at end-of-phase to reclaim
            // the WAL space before starting the next phase.
            if (climbsDbFiles.isNotEmpty()) {
                val sharedDb = openTargetDb()
                sharedDb.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { it.moveToFirst() }
                try {
                    var cumInserted = 0; var cumScanned = 0
                    onProgress?.invoke(ImportStep.ImportClimbs(0, 0, grandClimbTotal))
                    for ((i, file) in climbsDbFiles.withIndex()) {
                        val baseInserted = cumInserted; val baseScanned = cumScanned
                        openReadOnly(file) { rawDb ->
                            importClimbs(rawDb, freshInstall = freshInstallClimbs, sharedTargetDb = sharedDb) { inserted, scanned, _ ->
                                onProgress?.invoke(ImportStep.ImportClimbs(
                                    baseInserted + inserted, baseScanned + scanned, grandClimbTotal
                                ))
                            }
                        }.also { chunkInserted ->
                            cumInserted += chunkInserted
                            cumScanned += climbChunkCounts[i]
                        }
                    }
                } finally {
                    sharedDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    sharedDb.rawQuery("PRAGMA wal_autocheckpoint = 1000", null).use { it.moveToFirst() }
                    sharedDb.close()
                }
            }

            // Import all stat chunks (bulk ATTACH or row-by-row fallback).
            // Same shared-connection + WAL-tuning rationale as the
            // climbs phase above. Stats is the larger of the two phases
            // (several stats per climb), so the cache-warmth benefit of
            // the shared connection dominates here.
            if (statsDbFiles.isNotEmpty()) {
                val sharedDb = openTargetDb()
                sharedDb.rawQuery("PRAGMA wal_autocheckpoint = 0", null).use { it.moveToFirst() }
                try {
                    var cumInserted = 0; var cumScanned = 0
                    onProgress?.invoke(ImportStep.ImportStats(0, 0, grandStatTotal))
                    for ((i, file) in statsDbFiles.withIndex()) {
                        val baseInserted = cumInserted; val baseScanned = cumScanned
                        openReadOnly(file) { rawDb ->
                            importClimbStats(rawDb, freshInstall = freshInstallStats, sharedTargetDb = sharedDb) { inserted, scanned, _ ->
                                onProgress?.invoke(ImportStep.ImportStats(
                                    baseInserted + inserted, baseScanned + scanned, grandStatTotal
                                ))
                            }
                        }.also { chunkInserted ->
                            cumInserted += chunkInserted
                            cumScanned += statChunkCounts[i]
                        }
                    }
                } finally {
                    sharedDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                    sharedDb.rawQuery("PRAGMA wal_autocheckpoint = 1000", null).use { it.moveToFirst() }
                    sharedDb.close()
                }
            }

            // Import meta chunks (usually just 1).
            //
            // We used to short-circuit `importPlacements` /
            // `importProductSizes` / `importBoardImages` whenever the
            // device already had layout data, on the (then-correct)
            // assumption that those rows never change. The 0.1.4 cron
            // ships Homewall, so existing-install upgrades suddenly need
            // to *gain* a layout's worth of rows — short-circuit would
            // leave them on the Original-only data forever. The total
            // volume here is tiny (~1.2k placements, 16 sizes, 34 board-
            // images, ~5k leds) so just re-importing on every run is
            // cheap and keeps the device in lockstep with whatever the
            // cron published.
            for (file in metaDbFiles) {
                openReadOnly(file) { rawDb ->
                    onProgress?.invoke(ImportStep.ImportLayout(0))
                    val layoutCount = importPlacements(rawDb)
                    importProductSizes(rawDb)
                    importBoardImages(rawDb)
                    importLeds(rawDb)
                    importSyncState(rawDb)
                    onProgress?.invoke(ImportStep.ImportLayout(layoutCount))
                }
            }
        }

        // Now indexes are back; UI has already entered Finalizing via onRebuild.
        backfillMoveCounts()

        if (boardRepository.getSyncState("metadata_v7") == null) {
            boardRepository.upsertSyncState("metadata_v7", "done")
        }

        // Import location chunks (FEAT-006). Disjoint from climb/stat/layout
        // tables; safe to run after the deferred-index block has finished.
        // Older clients ignore this chunk type; newer clients with no
        // locations chunk in the manifest get an empty list and skip.
        // Non-essential source: a single corrupt/unreadable locations chunk must
        // NOT sink the whole sync (climbs/stats/layout are already imported).
        // Mirrors the error isolation in BoardSyncManager.backfillLocationsIfMissing.
        for (file in locationsDbFiles) {
            runCatching { openReadOnly(file) { rawDb -> importLocations(rawDb) } }
                .onFailure { Log.w(TAG, "locations chunk import failed (non-essential) — skipping ${file.name}", it) }
        }

        val climbCount = boardRepository.getClimbCount()
        val statCount = boardRepository.getStatCount()
        val placementCount = boardRepository.getAllPlacements().size
        val nomatchCount = boardRepository.countNomatchClimbs()
        Log.i(
            TAG,
            "importFromChunks done: climbs=$climbCount stats=$statCount " +
                "placements=$placementCount nomatch=$nomatchCount",
        )
        // Post-import integrity probe. Steady-state expectation after the
        // 6.sqm NOCASE migration: orphanStats and noStatsClimbs both stay
        // small and stable across syncs. A sudden jump signals a stats-
        // chunk import failure or a fresh case-drift regression. Wrapped
        // in runCatching so a transient SQLite error never strands the
        // sync's success state.
        runCatching {
            val orphanStats = boardRepository.countOrphanStats()
            val noStatsClimbs = boardRepository.countListedClimbsWithoutStats()
            Log.i(
                TAG,
                "importFromChunks integrity: orphanStats=$orphanStats " +
                    "listedClimbsWithoutStats=$noStatsClimbs",
            )
        }.onFailure { Log.w(TAG, "integrity probe failed", it) }
        onProgress?.invoke(ImportStep.Done(climbCount.toInt(), statCount.toInt(), placementCount, nomatchCount.toInt()))
    }

    /**
     * Import the MoonBoard catalogue snapshot (FEAT-027). Unlike the
     * Kilter chunked sync, the MoonBoard snapshot is a single SQLite
     * file carrying both `climbs` and `climb_stats` for the whole
     * catalogue — produced one-shot from the spookykat dump by the
     * out-of-repo board-DB build pipeline.
     *
     * Every row is tagged board_brand='moonboard' on insert. Climbs go
     * through [mergeSnapshotClimbs] — the snapshot analogue of the Kilter
     * chunk path's preserve-CruxCoach-columns merge — because the daily
     * cron merges community climbs (origin='cruxcoach') into this same
     * snapshot: a blanket INSERT OR REPLACE would wipe the author's local
     * publish state (source, sync_status, nostr_*, frames_hash) and
     * resurrect locally-deleted community climbs on every re-import.
     *
     * MoonBoard uuids (uuidv5 of "moonboard:{apiId}") never collide with
     * Kilter uuids, so this only ever touches MoonBoard rows — the
     * Kilter catalogue is untouched. No placements/holes/leds: MoonBoard
     * board geometry is hard-coded ([com.cruxcoach.domain.board.MoonBoardVariant]),
     * not carried in the snapshot.
     */
    @Synchronized
    fun importMoonBoardSnapshot(
        snapshotFile: File,
        onProgress: ((step: ImportStep) -> Unit)? = null
    ) {
        // Snapshots built 2026-05+ carry a precomputed move_count column.
        // When present it is copied straight through and the post-import
        // backfill is skipped — the Kilter chunk path ([importClimbs]) does
        // the same via its own `hasMoveCount` check.
        var snapshotHasMoveCount = false
        withDeferredIndexes(
            onRebuild = { onProgress?.invoke(ImportStep.Finalizing) }
        ) {
            val targetDb = openTargetDb()
            try {
                targetDb.execSQL("ATTACH DATABASE ? AS mb", arrayOf(snapshotFile.absolutePath))

                // One scan for the optional columns: move_count (precomputed),
                // and — crucially for the community/origin browse filters —
                // origin + created_by_pubkey. The MoonBoard snapshot now carries
                // BoardSesh-imported climbs (origin='boardsesh'); without copying
                // origin they defaulted to the catalogue value, so the BoardSesh
                // filter (origin='boardsesh') found nothing even though the
                // climbs were present in the ALL list.
                var snapshotHasOrigin = false
                var snapshotHasPubkey = false
                targetDb.rawQuery("PRAGMA mb.table_info(climbs)", null).use { c ->
                    while (c.moveToNext()) {
                        when (c.getString(1)) {
                            "move_count" -> snapshotHasMoveCount = true
                            "origin" -> snapshotHasOrigin = true
                            "created_by_pubkey" -> snapshotHasPubkey = true
                        }
                    }
                }
                val moveCountExpr = if (snapshotHasMoveCount) "COALESCE(move_count, 0)" else "0"
                val baseOriginExpr = if (snapshotHasOrigin) "COALESCE(origin, 'kilter')" else "'kilter'"
                val pubkeyExpr = if (snapshotHasPubkey) "created_by_pubkey" else "NULL"
                // A snapshot row carrying a setter pubkey is CruxCoach-authored
                // even when the blob's own origin column lags behind — the same
                // authoritative-pubkey rule the Kilter chunk path applies (see
                // [importClimbs]).
                val originExpr = if (snapshotHasPubkey)
                    "CASE WHEN created_by_pubkey IS NOT NULL AND created_by_pubkey != '' " +
                        "THEN 'cruxcoach' ELSE $baseOriginExpr END"
                else baseOriginExpr

                val climbTotal = queryLong(
                    targetDb, "SELECT COUNT(*) FROM mb.climbs WHERE is_listed = 1"
                ).toInt()
                onProgress?.invoke(ImportStep.ImportClimbs(0, 0, climbTotal))
                mergeSnapshotClimbs(
                    targetDb, "mb", "moonboard",
                    moveCountExpr, originExpr, pubkeyExpr
                )
                onProgress?.invoke(ImportStep.ImportClimbs(climbTotal, climbTotal, climbTotal))

                val statTotal = queryLong(
                    targetDb, "SELECT COUNT(*) FROM mb.climb_stats"
                ).toInt()
                onProgress?.invoke(ImportStep.ImportStats(0, 0, statTotal))
                targetDb.execSQL(
                    """
                    INSERT OR REPLACE INTO climb_stats(
                        climb_uuid, angle, display_difficulty, difficulty_average,
                        quality_average, ascensionist_count, benchmark_difficulty,
                        fa_username, fa_at, official_kilter_difficulty, layout_id)
                    SELECT LOWER(climb_uuid), angle, display_difficulty, difficulty_average,
                           quality_average, ascensionist_count, benchmark_difficulty,
                           fa_username, fa_at, NULL,
                           COALESCE((SELECT c.layout_id FROM climbs c WHERE c.uuid = LOWER(climb_uuid)), 0)
                    FROM mb.climb_stats
                    """.trimIndent()
                )
                onProgress?.invoke(ImportStep.ImportStats(statTotal, statTotal, statTotal))
            } finally {
                runCatching { targetDb.execSQL("DETACH DATABASE mb") }
                targetDb.close()
            }
        }
        // Older snapshots ship no move_count column — compute it from
        // `frames` post-import (same as pre-2026-04 Kilter chunks). Newer
        // snapshots carry it precomputed, so the backfill is skipped.
        if (!snapshotHasMoveCount) backfillMoveCounts()
        val climbCount = boardRepository.getClimbCount()
        val statCount = boardRepository.getStatCount()
        Log.i(TAG, "importMoonBoardSnapshot done: catalogue totals climbs=$climbCount stats=$statCount")
        onProgress?.invoke(ImportStep.Done(climbCount.toInt(), statCount.toInt(), 0, 0))
    }

    /**
     * Import a full Aurora-family board snapshot (FEAT-031): Tension,
     * Grasshopper, Decoy, So iLL, Touchstone. Unlike [importMoonBoardSnapshot]
     * these boards carry full Aurora geometry (product_sizes, board_images,
     * placements, leds), so the renderer + LED send work data-driven exactly
     * like Kilter. Every row — climbs and geometry alike — is stamped with
     * [boardBrand] (the wire value, e.g. "tension") so the namespaced
     * (board_brand, id) geometry tables and climbs.board_brand resolve per
     * board and never collide with Kilter's same-numbered Aurora ids (18.sqm).
     *
     * The snapshot is the raw Aurora shape produced by the cron's
     * build_board_db.py: climbs, climb_stats, placements[id,hole_id,set_id],
     * holes[id,x,y], product_sizes, product_sizes_layouts_sets, leds. Brand
     * identity comes from the per-board manifest d-tag (cruxcoach/<board>-db),
     * NOT inferred from layout_id (Aurora layout_ids overlap Kilter's, so
     * [com.cruxcoach.domain.board.BoardBrand.fromLayoutId] cannot tell them
     * apart — see its doc).
     */
    @Synchronized
    fun importAuroraSnapshot(
        snapshotFile: File,
        boardBrand: String,
        onProgress: ((step: ImportStep) -> Unit)? = null
    ) {
        // Snapshots built by build_board_db.py carry is_nomatch; move_count is
        // computed post-import (the bundle has no move_count column).
        var snapshotHasMoveCount = false
        val brand = arrayOf<Any?>(boardBrand)
        withDeferredIndexes(
            onRebuild = { onProgress?.invoke(ImportStep.Finalizing) }
        ) {
            val targetDb = openTargetDb()
            try {
                targetDb.execSQL("ATTACH DATABASE ? AS ab", arrayOf(snapshotFile.absolutePath))

                // Optional columns in one scan — move_count plus origin +
                // created_by_pubkey, so Aurora community climbs (origin='cruxcoach'
                // / 'boardsesh', once the cron merges them into Aurora chunks)
                // keep their provenance for the origin browse filters instead of
                // defaulting to the catalogue value. No-op for today's
                // catalogue-only Aurora chunks, which carry no origin column.
                var snapshotHasOrigin = false
                var snapshotHasPubkey = false
                targetDb.rawQuery("PRAGMA ab.table_info(climbs)", null).use { c ->
                    while (c.moveToNext()) {
                        when (c.getString(1)) {
                            "move_count" -> snapshotHasMoveCount = true
                            "origin" -> snapshotHasOrigin = true
                            "created_by_pubkey" -> snapshotHasPubkey = true
                        }
                    }
                }
                val moveCountExpr = if (snapshotHasMoveCount) "COALESCE(move_count, 0)" else "0"
                val baseOriginExpr = if (snapshotHasOrigin) "COALESCE(origin, 'kilter')" else "'kilter'"
                val pubkeyExpr = if (snapshotHasPubkey) "created_by_pubkey" else "NULL"
                // A snapshot row carrying a setter pubkey is CruxCoach-authored
                // even when the blob's own origin column lags behind — the same
                // authoritative-pubkey rule the Kilter chunk path applies (see
                // [importClimbs]).
                val originExpr = if (snapshotHasPubkey)
                    "CASE WHEN created_by_pubkey IS NOT NULL AND created_by_pubkey != '' " +
                        "THEN 'cruxcoach' ELSE $baseOriginExpr END"
                else baseOriginExpr

                // ── climbs (board_brand = the board's wire value) ──
                val climbTotal = queryLong(
                    targetDb, "SELECT COUNT(*) FROM ab.climbs WHERE is_listed = 1"
                ).toInt()
                onProgress?.invoke(ImportStep.ImportClimbs(0, 0, climbTotal))
                mergeSnapshotClimbs(
                    targetDb, "ab", boardBrand,
                    moveCountExpr, originExpr, pubkeyExpr
                )
                onProgress?.invoke(ImportStep.ImportClimbs(climbTotal, climbTotal, climbTotal))

                // ── climb_stats (layout_id denormalized from the climb) ──
                val statTotal = queryLong(targetDb, "SELECT COUNT(*) FROM ab.climb_stats").toInt()
                onProgress?.invoke(ImportStep.ImportStats(0, 0, statTotal))
                targetDb.execSQL(
                    """
                    INSERT OR REPLACE INTO climb_stats(
                        climb_uuid, angle, display_difficulty, difficulty_average,
                        quality_average, ascensionist_count, benchmark_difficulty,
                        fa_username, fa_at, official_kilter_difficulty, layout_id)
                    SELECT LOWER(climb_uuid), angle, display_difficulty, difficulty_average,
                           quality_average, ascensionist_count, benchmark_difficulty,
                           fa_username, fa_at, NULL,
                           COALESCE((SELECT c.layout_id FROM climbs c WHERE c.uuid = LOWER(climb_uuid)), 0)
                    FROM ab.climb_stats
                    """.trimIndent()
                )
                onProgress?.invoke(ImportStep.ImportStats(statTotal, statTotal, statTotal))

                // ── geometry, all brand-namespaced (board_brand leads each PK) ──
                targetDb.execSQL(
                    """
                    INSERT OR REPLACE INTO product_sizes(
                        board_brand, id, product_id, name,
                        edge_left, edge_right, edge_bottom, edge_top, image_filename)
                    SELECT ?, id, product_id, name,
                           edge_left, edge_right, edge_bottom, edge_top, image_filename
                    FROM ab.product_sizes
                    """.trimIndent(),
                    brand
                )
                targetDb.execSQL(
                    """
                    INSERT OR REPLACE INTO board_images(
                        board_brand, id, product_size_id, layout_id, set_id, image_filename)
                    SELECT ?, id, product_size_id, layout_id, set_id, image_filename
                    FROM ab.product_sizes_layouts_sets
                    WHERE image_filename IS NOT NULL
                    """.trimIndent(),
                    brand
                )
                // CruxCoach placements carry x/y pre-joined from holes (the raw
                // Aurora placements table has only hole_id), mirroring the
                // Kilter chunk path.
                targetDb.execSQL(
                    """
                    INSERT OR REPLACE INTO placements(
                        board_brand, placement_id, hole_id, set_id, x, y)
                    SELECT ?, p.id, p.hole_id, p.set_id, h.x, h.y
                    FROM ab.placements p
                    JOIN ab.holes h ON p.hole_id = h.id
                    """.trimIndent(),
                    brand
                )
                targetDb.execSQL(
                    """
                    INSERT OR REPLACE INTO leds(board_brand, hole_id, product_size_id, position)
                    SELECT ?, hole_id, product_size_id, position
                    FROM ab.leds
                    """.trimIndent(),
                    brand
                )
                // placement_roles (FEAT-031) — present only when the board's
                // chunk opts in. Drives per-board LED + render colours
                // (placement_roles.led_color). Guarded: current chunks may not
                // carry it yet, in which case colours fall back to the
                // conventional per-brand defaults.
                val hasPlacementRoles = queryLong(
                    targetDb,
                    "SELECT COUNT(*) FROM ab.sqlite_master WHERE type='table' AND name='placement_roles'"
                ) > 0
                if (hasPlacementRoles) {
                    targetDb.execSQL(
                        """
                        INSERT OR REPLACE INTO placement_roles(
                            board_brand, id, name, led_color, screen_color)
                        SELECT ?, id, name, led_color, screen_color
                        FROM ab.placement_roles
                        """.trimIndent(),
                        brand
                    )
                }
            } finally {
                runCatching { targetDb.execSQL("DETACH DATABASE ab") }
                targetDb.close()
            }
        }
        if (!snapshotHasMoveCount) backfillMoveCounts()
        val climbCount = boardRepository.getClimbCount()
        val statCount = boardRepository.getStatCount()
        Log.i(TAG, "importAuroraSnapshot($boardBrand) done: catalogue totals climbs=$climbCount stats=$statCount")
        onProgress?.invoke(ImportStep.Done(climbCount.toInt(), statCount.toInt(), 0, 0))
    }

    /**
     * Merge a snapshot's `climbs` (attached as [alias]) into the target —
     * the snapshot analogue of the Kilter chunk path's two-step merge in
     * [importClimbs]. A blanket INSERT OR REPLACE is forbidden here: the
     * cron merges community climbs (origin='cruxcoach') into the
     * MoonBoard/Aurora snapshots, and SQLite REPLACE re-inserts the row,
     * resetting every column missing from the insert list — wiping the
     * author's publish state (source, sync_status, nostr_event_id,
     * nostr_d_tag, frames_hash, kilter_*) and resurrecting locally-deleted
     * community climbs (is_deleted → 0).
     *
     * Passes, mirroring [importClimbs]:
     *  1. INSERT OR IGNORE listed rows — new climbs only; existing rows
     *     keep all CruxCoach lifecycle columns.
     *  2. Catalogue content refresh for origin='kilter' rows from listed
     *     snapshot rows (community rows have Nostr as source of truth).
     *  3. Delist flip: snapshot is_listed=0 → local is_listed=0
     *     (column-only, so local name/frames survive for the logbook and
     *     detail screen).
     *  4. Origin upgrade, asymmetric kilter→non-kilter: heals rows
     *     imported before the snapshot carried origin (e.g. the
     *     BoardSesh-imported MoonBoard climbs stamped 'kilter' by
     *     pre-fix imports).
     *  5. created_by_pubkey backfill, NULL-only.
     *
     * Unlike the Kilter path there is no setter_username propagation pass:
     * the MoonBoard/Aurora community merge still writes pubkey-prefix
     * stubs, which must not overwrite the author's real local name.
     */
    private fun mergeSnapshotClimbs(
        targetDb: SQLiteDatabase,
        alias: String,
        boardBrand: String,
        moveCountExpr: String,
        originExpr: String,
        pubkeyExpr: String,
    ) {
        // Stage with uuid pre-lowercased + PK-indexed so every pass below
        // is an O(log n) lookup, same rationale as the Kilter chunk_norm.
        targetDb.execSQL(
            """
            CREATE TEMP TABLE IF NOT EXISTS snapshot_norm (
                uuid TEXT PRIMARY KEY,
                layout_id INTEGER, setter_username TEXT, name TEXT, frames TEXT,
                frames_count INTEGER, is_listed INTEGER,
                edge_left INTEGER, edge_right INTEGER,
                edge_bottom INTEGER, edge_top INTEGER,
                created_at INTEGER, description TEXT,
                is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER,
                move_count INTEGER, origin TEXT, created_by_pubkey TEXT
            ) WITHOUT ROWID
            """.trimIndent()
        )
        targetDb.beginTransaction()
        try {
            targetDb.execSQL("DELETE FROM snapshot_norm")
            targetDb.execSQL(
                """
                INSERT OR IGNORE INTO snapshot_norm
                SELECT LOWER(uuid), layout_id, setter_username, name, frames,
                       frames_count, is_listed, edge_left, edge_right,
                       edge_bottom, edge_top, created_at,
                       COALESCE(description, ''), COALESCE(is_nomatch, 0),
                       COALESCE(frames_pace, 0), COALESCE(hsm, 0), $moveCountExpr,
                       $originExpr, $pubkeyExpr
                FROM $alias.climbs
                """.trimIndent()
            )
            targetDb.execSQL(
                """
                INSERT OR IGNORE INTO climbs(
                    uuid, layout_id, setter_username, name, frames,
                    frames_count, is_listed, edge_left, edge_right,
                    edge_bottom, edge_top, created_at,
                    description, is_nomatch, frames_pace, hsm, move_count,
                    board_brand, origin, created_by_pubkey)
                SELECT uuid, layout_id, setter_username, name, frames,
                       frames_count, is_listed, edge_left, edge_right,
                       edge_bottom, edge_top, created_at,
                       description, is_nomatch, frames_pace, hsm, move_count,
                       ?, origin, created_by_pubkey
                FROM snapshot_norm
                WHERE is_listed = 1
                """.trimIndent(),
                arrayOf<Any?>(boardBrand)
            )
            targetDb.execSQL(
                """
                UPDATE climbs SET
                    (layout_id, setter_username, name, frames,
                     frames_count, is_listed, edge_left, edge_right,
                     edge_bottom, edge_top, created_at, description,
                     is_nomatch, frames_pace, hsm, move_count)
                    = (SELECT layout_id, setter_username, name, frames,
                              frames_count, is_listed, edge_left, edge_right,
                              edge_bottom, edge_top, created_at, description,
                              is_nomatch, frames_pace, hsm, move_count
                       FROM snapshot_norm
                       WHERE snapshot_norm.uuid = main.climbs.uuid)
                WHERE origin = 'kilter'
                  AND uuid IN (SELECT uuid FROM snapshot_norm WHERE is_listed = 1)
                """.trimIndent()
            )
            targetDb.execSQL(
                """
                UPDATE climbs SET is_listed = 0
                WHERE is_listed = 1
                  AND uuid IN (SELECT uuid FROM snapshot_norm WHERE is_listed = 0)
                """.trimIndent()
            )
            // FEAT-041 item 1: same delete-convergence as the Kilter chunk
            // path — a delisted community (origin='cruxcoach') row from the
            // snapshot is a deletion, so arm is_deleted=1 (and thus the L3
            // resurrection guard) on chunk-only devices. Kilter-origin delist
            // is not a deletion, so it is left untouched.
            targetDb.execSQL(
                """
                UPDATE climbs SET is_deleted = 1
                WHERE origin = 'cruxcoach'
                  AND is_deleted = 0
                  AND uuid IN (SELECT uuid FROM snapshot_norm WHERE is_listed = 0)
                """.trimIndent()
            )
            targetDb.execSQL(
                """
                UPDATE climbs SET origin = (
                    SELECT origin FROM snapshot_norm
                    WHERE snapshot_norm.uuid = main.climbs.uuid
                )
                WHERE origin = 'kilter'
                  AND uuid IN (SELECT uuid FROM snapshot_norm WHERE origin != 'kilter')
                """.trimIndent()
            )
            targetDb.execSQL(
                """
                UPDATE climbs SET created_by_pubkey = (
                    SELECT created_by_pubkey FROM snapshot_norm
                    WHERE snapshot_norm.uuid = main.climbs.uuid
                )
                WHERE created_by_pubkey IS NULL
                  AND uuid IN (
                    SELECT uuid FROM snapshot_norm WHERE created_by_pubkey IS NOT NULL
                  )
                """.trimIndent()
            )
            targetDb.setTransactionSuccessful()
        } finally {
            targetDb.endTransaction()
        }
    }

    /**
     * Import from a full uncompressed board DB (e.g. received via local WiFi share).
     * This is the same as the legacy online import path.
     */
    @Synchronized
    fun importFromLocalDb(
        dbFile: File,
        onProgress: ((step: ImportStep) -> Unit)? = null
    ) {
        importFromDbFile(dbFile, onProgress)
    }

    /**
     * Online import: delegates APK download and DB extraction to [ApkDownloader],
     * then imports via the shared [importFromDbFile].
     */
    fun downloadAndImport(
        onProgress: ((step: ImportStep) -> Unit)? = null
    ) {
        val tempDb = File(context.cacheDir, "aurora_apk_db.sqlite3")
        try {
            onProgress?.invoke(ImportStep.Download(0, 0))
            apkDownloader.downloadAndExtractDatabase(
                targetDbFile = tempDb,
                onDownloadProgress = { bytesRead, totalBytes ->
                    onProgress?.invoke(ImportStep.Download(bytesRead, totalBytes))
                }
            )

            onProgress?.invoke(ImportStep.Extract)
            importFromDbFile(tempDb, onProgress)
        } finally {
            tempDb.delete()
        }
    }

    // ── Shared import core ───────────────────────────────────────────

    /**
     * Central import method used by both online and offline paths.
     * Opens the raw-schema SQLite [dbFile], runs delta comparison if data
     * already exists, and bulk-inserts all tables into SQLDelight.
     */
    private fun importFromDbFile(
        dbFile: File,
        onProgress: ((step: ImportStep) -> Unit)? = null
    ) {
        val snapshot = loadExistingSnapshot()
        // Same fresh-install gate as importFromChunks — captured once
        // at the start so the hot path stays consistent across the
        // run. See importFromChunks for the rationale.
        val freshInstallClimbs: Boolean = openTargetDb().let { db ->
            try { queryLong(db, "SELECT COUNT(*) FROM climbs") == 0L }
            finally { db.close() }
        }
        val freshInstallStats: Boolean = openTargetDb().let { db ->
            try { queryLong(db, "SELECT COUNT(*) FROM climb_stats") == 0L }
            finally { db.close() }
        }

        val rawDb = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY
        )
        try {
            // The in-app offline share serves the sender's own cruxcoach.db —
            // OUR schema (marker: `sync_states`), not the Kilter-APK schema
            // this method historically imported. Climb/stat copying is
            // column-probed and works for both; the geometry + sync-state
            // finalization below must branch. See [LocalShareSchema].
            val isModernSource = hasTable(rawDb, LocalShareSchema.MODERN_MARKER_TABLE)
            // Pre-flight BEFORE any write: the geometry copies use fixed
            // column lists (unlike the column-probed climb/stat import), so
            // a source from an older app used to blow up in the geometry
            // transaction AFTER climbs/stats had already committed — a
            // partial import. Probing every source SELECT first turns that
            // into a clean, zero-write abort.
            if (isModernSource) preflightModernSource(dbFile)
            val (climbCount, statCount) = withDeferredIndexes {
                onProgress?.invoke(ImportStep.ImportClimbs(0, 0, 0))
                val climbs = importClimbs(rawDb, freshInstall = freshInstallClimbs) { inserted, scanned, total ->
                    onProgress?.invoke(ImportStep.ImportClimbs(inserted, scanned, total))
                }
                onProgress?.invoke(ImportStep.ImportStats(0, 0, 0))
                val stats = importClimbStats(rawDb, freshInstall = freshInstallStats) { inserted, scanned, total ->
                    onProgress?.invoke(ImportStep.ImportStats(inserted, scanned, total))
                }
                climbs to stats
            }

            backfillMoveCounts()

            if (boardRepository.getSyncState("metadata_v7") == null) {
                boardRepository.upsertSyncState("metadata_v7", "done")
            }

            onProgress?.invoke(ImportStep.ImportLayout(0))
            val layoutCount = if (isModernSource) {
                // The legacy branches below assume the Kilter-APK schema
                // ("SELECT p.id … JOIN holes", product_sizes.is_listed) or
                // the pre-rename aurora_* tables; a modern source has
                // neither and the import deterministically died right here
                // ("no such column: p.id") — at the very end, after minutes
                // of climb copying. Modern geometry matches our own schema
                // 1:1 with board_brand in every PK, so one bulk upsert both
                // bootstraps a fresh install and merges brands an existing
                // install doesn't have yet — deliberately NOT gated on
                // hasLayout like the Kilter path.
                val copied = importModernGeometry(dbFile)
                // The sender's DB also carries gym locations + walls.
                // Guarded + non-fatal by design (skips when absent/empty).
                importLocations(rawDb)
                copied
            } else {
                val hasLayout = snapshot != null && snapshot.placementCount > 0
                val count = if (hasLayout) {
                    snapshot!!.placementCount
                } else {
                    importPlacements(rawDb)
                }
                if (!hasLayout) {
                    importProductSizes(rawDb)
                    importBoardImages(rawDb)
                }
                if (snapshot == null || snapshot.ledCount == 0) {
                    importLeds(rawDb)
                }
                count
            }
            importSyncState(rawDb)
            onProgress?.invoke(ImportStep.ImportLayout(layoutCount))

            val nomatchCount = boardRepository.countNomatchClimbs()
            onProgress?.invoke(ImportStep.Done(
                climbCount, statCount, layoutCount,
                nomatchCount = nomatchCount.toInt()
            ))
        } finally {
            rawDb.close()
        }
    }

    // ── Progress model ───────────────────────────────────────────────

    sealed class ImportStep {
        data object CheckingUpdate : ImportStep()
        data class Download(val bytesRead: Long, val totalBytes: Long) : ImportStep()
        data object Extract : ImportStep()

        /** Fetching the Blossom manifest from Nostr relays. */
        data object FetchingManifest : ImportStep()
        /** Downloading Blossom chunks (possibly in parallel). */
        data class DownloadChunk(
            val chunkName: String,
            val chunkIndex: Int,
            val totalChunks: Int,
            val bytesRead: Long,
            val totalBytes: Long,
            /** Cumulative bytes downloaded across ALL chunks so far. */
            val cumulativeBytesRead: Long = 0,
            /** Total compressed size of ALL chunks to download. */
            val cumulativeTotalBytes: Long = 0
        ) : ImportStep()
        data class ImportClimbs(val inserted: Int, val scanned: Int, val total: Int) : ImportStep()
        data class ImportStats(val inserted: Int, val scanned: Int, val total: Int) : ImportStep()
        data class ImportLayout(val count: Int) : ImportStep()
        /** Post-import work the user can't see otherwise (index rebuild,
         *  move-count backfill, denormalized refresh). Without this the UI
         *  freezes at "100% Statistiken importieren" for 30s–2min. */
        data object Finalizing : ImportStep()
        data class Done(
            val climbs: Int, val stats: Int, val placements: Int,
            val nomatchCount: Int = 0
        ) : ImportStep()
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private inline fun <R> openReadOnly(file: File, block: (SQLiteDatabase) -> R): R {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try { block(db) } finally { db.close() }
    }

    // ── Delta snapshot ───────────────────────────────────────────────

    private fun loadExistingSnapshot(): DiffSnapshot? {
        if (!boardRepository.hasAnyClimbs()) return null
        return DiffSnapshot(
            placementCount = boardRepository.getAllPlacements().size,
            ledCount = boardRepository.countLeds().toInt()
        )
    }

    private data class DiffSnapshot(
        val placementCount: Int,
        val ledCount: Int
    )

    // ── Schema detection ───────────────────────────────────────────

    /**
     * Detect whether a source DB uses the Kilter schema (`climbs`, `climb_stats`)
     * or the legacy CruxCoach schema (`aurora_climb`, `aurora_climb_stat`) — kept for backward-compat with old kilter_board.bin extracts.
     * CruxCoach's own `cruxcoach.db` is served during local WiFi share.
     */
    private fun hasTable(db: SQLiteDatabase, table: String): Boolean {
        val cursor = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(table)
        )
        return cursor.use { it.moveToFirst() }
    }

    private fun resolveClimbsTable(db: SQLiteDatabase): String =
        if (hasTable(db, "climbs")) "climbs" else "aurora_climb"

    private fun resolveStatsTable(db: SQLiteDatabase): String =
        if (hasTable(db, "climb_stats")) "climb_stats" else "aurora_climb_stat"

    /** Compute move count for a single-frame boulder from its frames string,
     *  using Kilter's fixed role IDs (12/13/14/15). Fallback for brands whose
     *  chunk ships no placement_roles table. */
    private fun computeMoveCount(frames: String): Long {
        if (frames.isEmpty() || frames.contains(",")) return 0
        return BoardClimbParser.estimateMoveCount(BoardClimbParser.parseFrames(frames)).toLong()
    }

    /**
     * A board's foot/start role IDs, derived from placement_roles.name.
     * Aurora boards number their roles board-locally (Tension: 1=start,
     * 2=middle, 3=finish, 4=foot — plus a mirrored set 5-8) instead of
     * Kilter's fixed 12/13/14/15, so move_count has to be counted against
     * each board's own role table rather than the hard-coded HoldRole ids.
     */
    private class RoleSemantics(val footIds: Set<Int>, val startIds: Set<Int>)

    /** Build a per-brand role map from the (already-imported) placement_roles
     *  table. Brands with no rows (e.g. Kilter) are simply absent — callers
     *  fall back to [computeMoveCount]'s fixed-id path for those. */
    private fun loadRoleSemantics(db: SQLiteDatabase): Map<String, RoleSemantics> {
        val foot = mutableMapOf<String, MutableSet<Int>>()
        val start = mutableMapOf<String, MutableSet<Int>>()
        db.rawQuery("SELECT board_brand, id, name FROM placement_roles", null).use { c ->
            while (c.moveToNext()) {
                val brand = c.getString(0) ?: continue
                val id = c.getInt(1)
                when (c.getString(2)?.lowercase()) {
                    "foot" -> foot.getOrPut(brand) { mutableSetOf() }.add(id)
                    "start" -> start.getOrPut(brand) { mutableSetOf() }.add(id)
                }
            }
        }
        return (foot.keys + start.keys).associateWith {
            RoleSemantics(foot[it].orEmpty(), start[it].orEmpty())
        }
    }

    /** Move count for an Aurora climb via its board's role table:
     *  total holds − foot − start (= hand + finish), mirroring the
     *  Kilter [BoardClimbParser.estimateMoveCount] semantics. */
    private fun computeMoveCount(frames: String, sem: RoleSemantics): Long {
        if (frames.isEmpty() || frames.contains(",")) return 0
        val holds = BoardClimbParser.parseFrames(frames)
        val moves = holds.size -
            holds.count { it.roleId in sem.footIds } -
            holds.count { it.roleId in sem.startIds }
        return moves.coerceAtLeast(0).toLong()
    }

    /**
     * Batch-update move_count for all boulders (single-frame climbs) where
     * move_count is still 0. Called after bulk ATTACH imports where frames
     * were inserted via SQL without Kotlin-side parsing. Counts moves with
     * each climb's own board role IDs (placement_roles), so Aurora boards —
     * whose role IDs differ from Kilter's — get correct counts instead of 0.
     */
    @Synchronized
    internal fun backfillMoveCounts() {
        val db = openTargetDb()
        try {
            val roleSem = loadRoleSemantics(db)
            val stmt = db.compileStatement(
                "UPDATE climbs SET move_count = ? WHERE uuid = ?"
            )
            // Process in batches to avoid CursorWindow overflow on older APIs
            var lastUuid = ""
            while (true) {
                val cursor = db.rawQuery(
                    """SELECT uuid, frames, board_brand FROM climbs
                       WHERE move_count = 0 AND frames_count = 1 AND uuid > ?
                       ORDER BY uuid LIMIT $BULK_BATCH_SIZE""",
                    arrayOf(lastUuid)
                )
                if (cursor.count == 0) { cursor.close(); break }
                db.beginTransaction()
                try {
                    cursor.use {
                        while (it.moveToNext()) {
                            lastUuid = it.getString(0)
                            val frames = it.getString(1) ?: ""
                            val sem = roleSem[it.getString(2)]
                            val moves = if (sem != null) computeMoveCount(frames, sem)
                                        else computeMoveCount(frames)
                            if (moves > 0) {
                                stmt.bindLong(1, moves)
                                stmt.bindString(2, lastUuid)
                                stmt.executeUpdateDelete()
                            }
                        }
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        } finally {
            db.close()
        }
    }

    // ── Table import methods (Kilter board / Aurora-style schema) ───

    /**
     * Bulk-import climbs from a chunk SQLite file using ATTACH DATABASE.
     * One SQL statement replaces the entire cursor→batch→upsert loop.
     */
    private fun importClimbs(
        rawDb: SQLiteDatabase,
        existingUuids: Set<String>? = null,
        freshInstall: Boolean = false,
        sharedTargetDb: SQLiteDatabase? = null,
        onProgress: ((inserted: Int, scanned: Int, total: Int) -> Unit)? = null
    ): Int {
        val chunkPath = rawDb.path ?: return importClimbsLegacy(rawDb, existingUuids, onProgress)
        val srcTable = resolveClimbsTable(rawDb)
        // sharedTargetDb is owned by the caller (importFromChunks holds one
        // connection per phase to avoid PRAGMA-roundtrip + page-cache-cold
        // overhead on every chunk). Only close locally-opened ones.
        val targetDb = sharedTargetDb ?: openTargetDb()
        val ownsTargetDb = sharedTargetDb == null
        try {
            targetDb.execSQL("ATTACH DATABASE ? AS src", arrayOf(chunkPath))
            // Source-column probe + derived expressions come before the
            // total-count query because $draftFilter feeds it.
            //
            // Copy move_count when the source has it (CruxCoach backups always,
            // Blossom chunks from 2026-04-21+). Old chunks without the column
            // fall back to 0 and backfillMoveCounts() computes it post-import.
            val srcCols = rawDb.rawQuery("PRAGMA table_info($srcTable)", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(1)) }
            }
            val hasMoveCount = "move_count" in srcCols
            val moveCountExpr = if (hasMoveCount) "COALESCE(move_count, 0)" else "0"
            // origin column landed in Blossom chunks at a known schema-roll
            // boundary; older chunks without it fall back to the schema
            // default 'kilter' on the target side. Note: the UPDATE pass
            // below intentionally does NOT touch origin — locally-set
            // 'cruxcoach' (e.g. via CommunityClimbSubscriber on a row the
            // cron later refreshes) must survive a Blossom blob refresh.
            val hasOrigin = "origin" in srcCols
            // baseOriginExpr preserves whatever origin the blob carries
            // ('kilter' | 'cruxcoach' | 'boardsesh'), defaulting legacy
            // chunks to 'kilter'. NOTE: BoardSesh-imported rows must be
            // written by the cron with created_by_pubkey=NULL — the
            // originExpr below reclassifies ANY row with a non-empty pubkey
            // to 'cruxcoach', which would otherwise silently fold BoardSesh
            // climbs into the CruxCoach-community provenance on every fresh
            // install. With a NULL pubkey the blob's 'boardsesh' survives.
            val baseOriginExpr = if (hasOrigin) "COALESCE(origin, 'kilter')" else "'kilter'"
            // Plan C: cron writes created_by_pubkey for cruxcoach-origin
            // climbs so the SettersListScreen + profile-resolution chain
            // works for fresh installs. Defensive — pre-Plan-C blobs
            // don't have it.
            val hasCreatedByPubkey = "created_by_pubkey" in srcCols
            val pubkeyExpr = if (hasCreatedByPubkey) "created_by_pubkey" else "NULL"
            // A climb that carries a setter pubkey is CruxCoach-authored — a
            // native Kilter climb never has one — so recognise it as
            // origin='cruxcoach' even when the blob's own origin column says
            // 'kilter'. The published blob's origin can lag the cruxcoach
            // classification (it's COALESCE(origin,'kilter') over the cron's
            // work DB), but created_by_pubkey is authoritative. Without this a
            // fresh install (whose only source is the blob) imports community
            // climbs as 'kilter' and stops recognising them as CruxCoach
            // climbs — no edit/publish actions, missing from the cruxcoach
            // filter. 21.sqm heals rows imported before this landed.
            val originExpr = if (hasCreatedByPubkey)
                "CASE WHEN created_by_pubkey IS NOT NULL AND created_by_pubkey != '' " +
                    "THEN 'cruxcoach' ELSE $baseOriginExpr END"
            else baseOriginExpr
            // board_brand exists on modern CruxCoach sources (the in-app
            // offline share serves the sender's own cruxcoach.db, which
            // carries EVERY brand's climbs); Kilter cron chunks are
            // Aurora-schema without the column → schema default 'kilter'.
            // Without this, a multi-brand offline import silently collapsed
            // all MoonBoard/Aurora climbs onto board_brand='kilter'.
            val brandExpr = if ("board_brand" in srcCols) "COALESCE(board_brand, 'kilter')" else "'kilter'"
            // A modern source also carries the sender's own unpublished
            // drafts (source='local'). Those are private working copies —
            // exclude them; published/community/catalogue rows all pass.
            // Hard-deleted rows (is_deleted=1) are equally excluded — a
            // deleted-but-still-listed row should never exist, but a
            // crafted/corrupted source must not resurrect one here.
            val deletedGuard = if ("is_deleted" in srcCols) " AND COALESCE(is_deleted, 0) = 0" else ""
            val draftFilter =
                (if ("source" in srcCols) "AND COALESCE(source, 'kilter') != 'local'" else "") + deletedGuard

            // Drafts are excluded from `total` too, so the progress bar's
            // denominator matches what the staging INSERT actually scans.
            val total = queryLong(
                targetDb,
                "SELECT COUNT(*) FROM src.$srcTable WHERE is_listed = 1 $draftFilter"
            ).toInt()
            // Fresh-install fast path: when the *original* import started
            // with an empty `climbs` table, every UPDATE pass below would
            // target only rows we just INSERT-ed in the same batch — i.e.
            // self-no-ops semantically (refresh a row with values we'd
            // just pulled from the same source row). Skipping them saves
            // the per-batch full-range correlated-subquery scan that's
            // the single largest CPU cost in the bulk-import on first
            // launch.
            //
            // The flag is plumbed in by [importFromChunks] from a single
            // `COUNT(*)` snapshot at the start of the run. We can't infer
            // it locally because chunk #2 onwards observes a non-empty
            // target (chunk #1 just populated it) and would otherwise
            // wrongly re-enable the slow UPDATE path.
            //
            // For incremental syncs (`freshInstall=false`), the UPDATE
            // passes remain mandatory for content / tombstone / pubkey
            // refresh.
            val skipUpdatePasses = freshInstall
            // countBefore is now only used for the inserted-count math
            // on the incremental path; on the fresh-install path we skip
            // it entirely (saves an O(N) PK-index scan on a 174k+ row
            // target before each chunk's batches).
            val countBefore = if (skipUpdatePasses) 0L
            else queryLong(targetDb, "SELECT COUNT(*) FROM climbs")
            onProgress?.invoke(0, 0, total)

            // Two-step bulk merge per batch:
            //
            //   Step 1 — INSERT OR IGNORE: adds rows whose uuid isn't yet
            //   in target. Existing rows are skipped, so CruxCoach-side
            //   metadata (origin, source, sync_status, nostr_*, kilter_*,
            //   created_by_pubkey, frames_hash) is preserved.
            //
            //   Step 2 — UPDATE … SET (cols) = (SELECT cols FROM src):
            //   refreshes Kilter-authoritative fields on rows that exist
            //   in both. Tuple-update-from-select is supported on
            //   SQLite ≥3.15 (Android API 26+, our minSdk). The SET list
            //   intentionally excludes every CruxCoach column so they
            //   survive the refresh.
            //
            // Includes is_listed in the UPDATE set + lets the UPDATE
            // batch include unlisted rows (so the cron's tombstone-sync
            // — `UPDATE blob.climbs SET is_listed = 0 WHERE …` — actually
            // propagates to client-side defaults that filter is_listed=1).
            // The INSERT path stays is_listed=1 only (no point inserting
            // tombstones for climbs we don't have).
            // Tier-2 incremental-sync optimisation: stage each batch into
            // a temp table with `uuid` already lower-cased + indexed, then
            // run all UPDATE passes against THAT (chunk_norm) instead of
            // src. The original `WHERE LOWER(src.uuid) = main.climbs.uuid`
            // killed src's PK index (function on indexed column) → each
            // outer row paid an O(K) full-scan of src. With chunk_norm
            // pre-normalised, every UPDATE becomes an O(log K) PK lookup.
            // Per-chunk cost: O(K log K) instead of O(K²) per pass × 5
            // passes = ~700-1000× faster on incremental sync. Fresh-install
            // costs one extra K-row copy per batch (worth it: the INSERT
            // path also wins from the same indexed lookup vs LOWER() in
            // its own SELECT).
            //
            // Temp tables are connection-scoped + the connection is shared
            // across chunks via [sharedTargetDb], so we IF-NOT-EXISTS once
            // and DELETE between batches to reuse storage.
            targetDb.execSQL("""
                CREATE TEMP TABLE IF NOT EXISTS chunk_norm (
                    uuid TEXT PRIMARY KEY,
                    layout_id INTEGER, setter_username TEXT, name TEXT, frames TEXT,
                    frames_count INTEGER, is_listed INTEGER,
                    edge_left INTEGER, edge_right INTEGER,
                    edge_bottom INTEGER, edge_top INTEGER,
                    created_at INTEGER, description TEXT,
                    is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER,
                    move_count INTEGER, origin TEXT, created_by_pubkey TEXT,
                    board_brand TEXT
                ) WITHOUT ROWID
            """)
            val minRowid = queryLong(targetDb, "SELECT MIN(rowid) FROM src.$srcTable")
            val maxRowid = queryLong(targetDb, "SELECT MAX(rowid) FROM src.$srcTable")
            var batchStart = minRowid
            var scanned = 0
            while (batchStart <= maxRowid) {
                val batchEnd = batchStart + BULK_BATCH_SIZE - 1
                targetDb.beginTransaction()
                try {
                    // LOWER(uuid) is the canonical write form (see 7.sqm
                    // for full rationale). The Kilter blob carries the
                    // same hex in mixed casings *with divergent metadata*
                    // — they are *not* the same logical climb on the
                    // cron's side — so we collapse case at the import
                    // boundary and let BINARY collation enforce identity.
                    // INSERT OR IGNORE handles intra-batch duplicate
                    // LOWER(uuid) collisions (rare — the cron typically
                    // dedupes within a month-chunk).
                    targetDb.execSQL("DELETE FROM chunk_norm")
                    targetDb.execSQL("""
                        INSERT OR IGNORE INTO chunk_norm
                        SELECT LOWER(uuid), layout_id, setter_username, name, frames,
                               frames_count, is_listed, edge_left, edge_right,
                               edge_bottom, edge_top, created_at,
                               COALESCE(description, ''), COALESCE(is_nomatch, 0),
                               COALESCE(frames_pace, 0), COALESCE(hsm, 0),
                               $moveCountExpr,
                               $originExpr,
                               $pubkeyExpr,
                               $brandExpr
                        FROM src.$srcTable
                        WHERE rowid BETWEEN $batchStart AND $batchEnd
                          $draftFilter
                    """)
                    // Insert listed rows from chunk_norm. Tombstones
                    // (is_listed=0) are intentionally not inserted — no
                    // point materialising rows for climbs we don't have.
                    targetDb.execSQL("""
                        INSERT OR IGNORE INTO climbs(
                            uuid, layout_id, setter_username, name, frames,
                            frames_count, is_listed, edge_left, edge_right,
                            edge_bottom, edge_top, created_at,
                            description, is_nomatch, frames_pace, hsm, move_count,
                            origin, created_by_pubkey, board_brand)
                        SELECT uuid, layout_id, setter_username, name, frames,
                               frames_count, is_listed, edge_left, edge_right,
                               edge_bottom, edge_top, created_at,
                               description, is_nomatch, frames_pace, hsm, move_count,
                               origin, created_by_pubkey, board_brand
                        FROM chunk_norm
                        WHERE is_listed = 1
                    """)
                    // Content refresh — only for origin='kilter' rows AND
                    // only for chunk rows that are themselves listed
                    // (chunk_norm.is_listed = 1). The latter guard exists
                    // because the cron now plants tombstone-shell rows
                    // (name='', frames='', is_listed=0) for cruxcoach-
                    // published-and-deleted climbs whose row went missing
                    // from the work-DB. Without this guard, the SET-from-
                    // SELECT would overwrite any meaningful local content
                    // (real name, holds) with the tombstone shell's empty
                    // strings, wiping the metadata that the user's logbook
                    // and the detail-screen still want to render for
                    // already-logged climbs.
                    //
                    // Climbs authored via CruxCoach (origin='cruxcoach')
                    // have Nostr as their source of truth and are
                    // protected from blob refresh entirely.
                    if (!skipUpdatePasses) {
                        targetDb.execSQL("""
                            UPDATE climbs SET
                                (layout_id, setter_username, name, frames,
                                 frames_count, is_listed, edge_left, edge_right,
                                 edge_bottom, edge_top, created_at, description,
                                 is_nomatch, frames_pace, hsm, move_count)
                                = (SELECT layout_id, setter_username, name, frames,
                                          frames_count, is_listed, edge_left, edge_right,
                                          edge_bottom, edge_top, created_at, description,
                                          is_nomatch, frames_pace, hsm, move_count
                                   FROM chunk_norm
                                   WHERE chunk_norm.uuid = main.climbs.uuid)
                            WHERE origin = 'kilter'
                              AND uuid IN (SELECT uuid FROM chunk_norm WHERE is_listed = 1)
                        """)
                    }
                    // Tombstone propagation. Symmetric flip for BOTH
                    // origin flavours: a chunk row with is_listed=0 means
                    // "this uuid was tombstoned upstream", regardless of
                    // how the local row got labelled. Only updates the
                    // is_listed column, so the local name/setter/frames
                    // survive — the user's logbook entries for this climb
                    // remain readable, and the detail-screen can still
                    // render the holds the user actually attempted.
                    //
                    // Pre-fix the kilter-origin update above carried the
                    // tombstone-overwrite by also propagating is_listed
                    // along with name/frames; a tombstone-shell from the
                    // cron silently wiped the meaningful local data.
                    // Splitting the listing flip into its own UPDATE
                    // pass keeps tombstone propagation working while
                    // separating it from the kilter content-refresh.
                    if (!skipUpdatePasses) {
                        targetDb.execSQL("""
                            UPDATE climbs SET is_listed = 0
                            WHERE is_listed = 1
                              AND uuid IN (SELECT uuid FROM chunk_norm WHERE is_listed = 0)
                        """)
                    }
                    // FEAT-041 item 1: converge the chunk-only delete path with
                    // the live tombstone. The delist flip above hides the climb
                    // (is_listed=0), but a device that only ever sees the chunk
                    // (never the live Kind-5 tombstone) keeps is_deleted=0 — so
                    // the L3 stale-resurrection guard (keys on is_deleted=1)
                    // stays disarmed and a later stray Original-Event rebroadcast
                    // could re-list it. For origin='cruxcoach' a chunk is_listed=0
                    // IS a deletion (community rows are only delisted upstream
                    // when the author deletes), so flip is_deleted=1 too — the
                    // same on-disk state markCommunityClimbDeleted produces on
                    // live-sub devices. Kilter-origin is_listed=0 is a catalogue
                    // delist, NOT a deletion, so it is left untouched.
                    if (!skipUpdatePasses) {
                        targetDb.execSQL("""
                            UPDATE climbs SET is_deleted = 1
                            WHERE origin = 'cruxcoach'
                              AND is_deleted = 0
                              AND uuid IN (SELECT uuid FROM chunk_norm WHERE is_listed = 0)
                        """)
                    }
                    // Setter-username propagation for cruxcoach-origin
                    // climbs (Plan C: cron resolves Kind-0 + writes the
                    // display_name into the blob). COALESCE keeps the
                    // local value when source is NULL.
                    if (!skipUpdatePasses) {
                        targetDb.execSQL("""
                            UPDATE climbs SET setter_username = COALESCE(
                                (SELECT setter_username FROM chunk_norm
                                 WHERE chunk_norm.uuid = main.climbs.uuid),
                                main.climbs.setter_username
                            )
                            WHERE origin = 'cruxcoach'
                              AND uuid IN (SELECT uuid FROM chunk_norm)
                        """)
                    }
                    // Origin upgrade — kilter→cruxcoach only (asymmetric).
                    if (!skipUpdatePasses && hasOrigin) {
                        targetDb.execSQL("""
                            UPDATE climbs SET origin = 'cruxcoach'
                            WHERE origin != 'cruxcoach'
                              AND uuid IN (SELECT uuid FROM chunk_norm WHERE origin = 'cruxcoach')
                        """)
                    }
                    // Pubkey backfill — fills NULL only, never overwrites.
                    if (!skipUpdatePasses && hasCreatedByPubkey) {
                        targetDb.execSQL("""
                            UPDATE climbs SET created_by_pubkey = (
                                SELECT created_by_pubkey FROM chunk_norm
                                WHERE chunk_norm.uuid = main.climbs.uuid
                            )
                            WHERE created_by_pubkey IS NULL
                              AND uuid IN (
                                SELECT uuid FROM chunk_norm WHERE created_by_pubkey IS NOT NULL
                              )
                        """)
                    }
                    targetDb.setTransactionSuccessful()
                } finally {
                    targetDb.endTransaction()
                }
                // Approximate scanned-progress from rowid arithmetic
                // (avoids a per-batch COUNT scan on the source chunk).
                // Source rowids are dense for fresh dumps so the upper
                // cap of `total` keeps the UI from overshooting on the
                // rare WHERE is_listed = 1 holes.
                //
                // inserted=0 (sentinel) keeps the UI's "+N new" label
                // anchored at the prior chunk-final value mid-chunk;
                // the real chunk-inserted count flows through the
                // post-loop onProgress invoke below. Without this the
                // label flickered fast-→-slow as Tier-2 dropped per-
                // chunk time from 30s to <1s.
                scanned = (scanned + (batchEnd - batchStart + 1).toInt()).coerceAtMost(total)
                onProgress?.invoke(0, scanned, total)
                batchStart = batchEnd + 1
            }

            // Fresh-install fast path: target was empty before this
            // chunk, every scanned row got inserted (no update pass, no
            // PK collisions). Skip the post-import COUNT(*) — on the
            // last few chunks of a 97-chunk import that's a full PK-
            // index scan over 170k+ rows each, total O(N²) just for
            // the progress callback.
            // Incremental path keeps the precise COUNT(*) so the UI's
            // "X new climbs" line is accurate.
            val inserted = if (skipUpdatePasses) {
                scanned
            } else {
                val countAfter = queryLong(targetDb, "SELECT COUNT(*) FROM climbs")
                (countAfter - countBefore).toInt()
            }
            onProgress?.invoke(inserted, total, total)
            targetDb.execSQL("DETACH DATABASE src")
            return inserted
        } catch (e: Exception) {
            try { targetDb.execSQL("DETACH DATABASE src") } catch (_: Exception) {}
            Log.w(TAG, "ATTACH-import failed for climbs; falling back to legacy row-by-row", e)
            return importClimbsLegacy(rawDb, existingUuids, onProgress)
        } finally {
            if (ownsTargetDb) targetDb.close()
        }
    }

    /** Row-by-row fallback for when ATTACH is not available (e.g. in-memory DB).
     *  Origin propagation from the source is not wired here — this path is
     *  exercised only for in-memory DBs in tests, where origin is irrelevant.
     *  Real-device imports go through the ATTACH path above which honours
     *  the source's `origin` column. */
    private fun importClimbsLegacy(
        rawDb: SQLiteDatabase,
        existingUuids: Set<String>? = null,
        onProgress: ((inserted: Int, scanned: Int, total: Int) -> Unit)? = null
    ): Int {
        val srcTable = resolveClimbsTable(rawDb)
        val cursor = rawDb.rawQuery(
            """SELECT uuid, layout_id, setter_username, name, frames,
                      frames_count, is_listed, edge_left, edge_right,
                      edge_bottom, edge_top, created_at,
                      COALESCE(description, '') AS description,
                      COALESCE(is_nomatch, 0) AS is_nomatch,
                      COALESCE(frames_pace, 0) AS frames_pace,
                      COALESCE(hsm, 0) AS hsm
               FROM $srcTable WHERE is_listed = 1""",
            null
        )
        val total = cursor.count
        var inserted = 0
        var scanned = 0
        cursor.use {
            while (it.moveToNext()) {
                scanned++
                // Canonical lowercase: pairs with the ATTACH-bulk path's
                // LOWER(uuid) above so existsClimb / upsertClimb operate
                // on the same string regardless of which path the chunk
                // took to get here.
                val uuid = it.getString(0).lowercase()
                if (existingUuids != null && uuid in existingUuids) {
                    if (scanned % (BATCH_SIZE * 4) == 0) onProgress?.invoke(inserted, scanned, total)
                    continue
                }
                inserted++
                val frames = it.getString(4)
                val moves = computeMoveCount(frames)
                boardRepository.upsertClimb(
                    uuid, it.getLong(1), if (it.isNull(2)) null else it.getString(2),
                    it.getString(3), frames, it.getLong(5), it.getLong(6),
                    if (it.isNull(7)) null else it.getLong(7),
                    if (it.isNull(8)) null else it.getLong(8),
                    if (it.isNull(9)) null else it.getLong(9),
                    if (it.isNull(10)) null else it.getLong(10),
                    if (it.isNull(11)) null else it.getString(11),
                    it.getString(12), it.getLong(13), it.getLong(14), it.getLong(15),
                    moveCount = moves
                )
                if (inserted % BATCH_SIZE == 0) onProgress?.invoke(inserted, scanned, total)
            }
            onProgress?.invoke(inserted, scanned, total)
        }
        return inserted
    }

    /**
     * Bulk-import stats from a chunk SQLite file using ATTACH DATABASE.
     */
    private fun importClimbStats(
        rawDb: SQLiteDatabase,
        existingStats: Map<Pair<String, Long>, Long?>? = null,
        freshInstall: Boolean = false,
        sharedTargetDb: SQLiteDatabase? = null,
        onProgress: ((inserted: Int, scanned: Int, total: Int) -> Unit)? = null
    ): Int {
        val chunkPath = rawDb.path ?: return importClimbStatsLegacy(rawDb, existingStats, onProgress)
        val srcTable = resolveStatsTable(rawDb)
        // See [importClimbs] — long-lived shared connection avoids
        // PRAGMA + page-cache reset on every chunk.
        val targetDb = sharedTargetDb ?: openTargetDb()
        val ownsTargetDb = sharedTargetDb == null
        try {
            targetDb.execSQL("ATTACH DATABASE ? AS src", arrayOf(chunkPath))
            val total = queryLong(targetDb, "SELECT COUNT(*) FROM src.$srcTable").toInt()
            // See [importClimbs] for the same fresh-install fast-path
            // rationale — the only diff is climb_stats has no UPDATE
            // pass, just an INSERT OR REPLACE, so the only saving here
            // is the per-chunk countBefore / countAfter pair which goes
            // O(N) over a 290k-row target by the last few chunks.
            val countBefore = if (freshInstall) 0L
            else queryLong(targetDb, "SELECT COUNT(*) FROM climb_stats")
            onProgress?.invoke(0, 0, total)

            // Import in batches by rowid range (avoids OFFSET scanning and CursorWindow issues on older APIs)
            val minRowid = queryLong(targetDb, "SELECT MIN(rowid) FROM src.$srcTable")
            val maxRowid = queryLong(targetDb, "SELECT MAX(rowid) FROM src.$srcTable")
            var batchStart = minRowid
            var scanned = 0
            while (batchStart <= maxRowid) {
                val batchEnd = batchStart + BULK_BATCH_SIZE - 1
                targetDb.beginTransaction()
                try {
                    // Mirror the climbs-side LOWER() canonicalization so
                    // climb_stats.climb_uuid matches the lowercase uuids
                    // we wrote into climbs. Without this the JOIN in
                    // climb_browse fails for any chunk that ships stats
                    // in upper-case while climbs landed lower-case.
                    targetDb.execSQL("""
                        INSERT OR REPLACE INTO climb_stats(
                            climb_uuid, angle, display_difficulty, difficulty_average,
                            quality_average, ascensionist_count, benchmark_difficulty,
                            fa_username, fa_at, layout_id)
                        SELECT LOWER(climb_uuid), angle, display_difficulty, difficulty_average,
                               quality_average, ascensionist_count, benchmark_difficulty,
                               fa_username, fa_at,
                               COALESCE((SELECT c.layout_id FROM climbs c WHERE c.uuid = LOWER(climb_uuid)), 0)
                        FROM src.$srcTable
                        WHERE rowid BETWEEN $batchStart AND $batchEnd
                    """)
                    targetDb.setTransactionSuccessful()
                } finally {
                    targetDb.endTransaction()
                }
                // Same rowid-arithmetic optimisation as [importClimbs] —
                // skip the per-batch source COUNT scan. inserted=0
                // sentinel: see [importClimbs] for the no-flicker
                // rationale.
                scanned = (scanned + (batchEnd - batchStart + 1).toInt()).coerceAtMost(total)
                onProgress?.invoke(0, scanned, total)
                batchStart = batchEnd + 1
            }

            val inserted = if (freshInstall) {
                scanned
            } else {
                val countAfter = queryLong(targetDb, "SELECT COUNT(*) FROM climb_stats")
                (countAfter - countBefore).toInt()
            }
            onProgress?.invoke(inserted, total, total)
            targetDb.execSQL("DETACH DATABASE src")
            return inserted
        } catch (e: Exception) {
            try { targetDb.execSQL("DETACH DATABASE src") } catch (_: Exception) {}
            Log.w(TAG, "ATTACH-import failed for climb_stats; falling back to legacy row-by-row", e)
            return importClimbStatsLegacy(rawDb, existingStats, onProgress)
        } finally {
            if (ownsTargetDb) targetDb.close()
        }
    }

    /** Row-by-row fallback for stats. */
    private fun importClimbStatsLegacy(
        rawDb: SQLiteDatabase,
        existingStats: Map<Pair<String, Long>, Long?>? = null,
        onProgress: ((inserted: Int, scanned: Int, total: Int) -> Unit)? = null
    ): Int {
        val srcTable = resolveStatsTable(rawDb)
        val cursor = rawDb.rawQuery(
            """SELECT climb_uuid, angle, display_difficulty, difficulty_average,
                      quality_average, ascensionist_count, benchmark_difficulty,
                      fa_username, fa_at
               FROM $srcTable""",
            null
        )
        val total = cursor.count
        var inserted = 0
        var scanned = 0
        cursor.use {
            while (it.moveToNext()) {
                scanned++
                val climbUuid = it.getString(0).lowercase()
                val angle = it.getLong(1)
                val ascensionistCount = if (it.isNull(5)) null else it.getLong(5)
                if (existingStats != null) {
                    val key = climbUuid to angle
                    if (existingStats.containsKey(key) && existingStats[key] == ascensionistCount) {
                        if (scanned % (BATCH_SIZE * 4) == 0) onProgress?.invoke(inserted, scanned, total)
                        continue
                    }
                }
                inserted++
                boardRepository.upsertClimbStat(
                    climbUuid, angle,
                    if (it.isNull(2)) null else it.getDouble(2),
                    if (it.isNull(3)) null else it.getDouble(3),
                    if (it.isNull(4)) null else it.getDouble(4),
                    ascensionistCount,
                    if (it.isNull(6)) null else it.getDouble(6),
                    if (it.isNull(7)) null else it.getString(7),
                    if (it.isNull(8)) null else it.getString(8)
                )
                if (inserted % BATCH_SIZE == 0) onProgress?.invoke(inserted, scanned, total)
            }
            onProgress?.invoke(inserted, scanned, total)
        }
        return inserted
    }

    /** Open the target board database directly for ATTACH operations. */
    private fun openTargetDb(): SQLiteDatabase {
        val dbFile = context.getDatabasePath("cruxcoach.db")
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        // Prevent SQLITE_BUSY when the SQLDelight driver concurrently reads
        // the same DB during an import: wait up to 5s for locks before
        // failing. PRAGMA busy_timeout returns the new value as a row, so
        // execSQL() throws "Queries can be performed using SQLiteDatabase
        // query or rawQuery methods only" — which the sync UI mis-renders
        // as "prüfe Internetverbindung" even when the download succeeded.
        db.rawQuery("PRAGMA busy_timeout = 5000", null).use { it.moveToFirst() }
        // Bulk-import tuning. These PRAGMAs are connection-scoped so they
        // need to be re-set every time we open a fresh handle (this fn is
        // called many times across an import). All three are safe defaults
        // for an interactively-used DB, not just for import:
        //   synchronous=NORMAL  — fsync at tx-commit boundaries, not in
        //     between. Standard recommendation for WAL mode (which Android
        //     SQLite uses by default since API 16). FULL costs an extra
        //     fsync per write without practical durability gain on Android,
        //     where the OS journals the underlying filesystem already.
        //   temp_store=MEMORY   — keeps temp tables / sort buffers in RAM
        //     instead of spilling to disk. Important for the per-batch
        //     correlated-subquery UPDATEs in [importClimbs] (incremental
        //     sync path).
        //   cache_size=-65536   — 64 MiB page cache (negative = KiB). The
        //     PK uniqueness check on every INSERT OR IGNORE row reads the
        //     uuid index back; with the default 2 MiB cache we churn pages
        //     hard on a 174k-row import. 64 MiB lets the entire PK index
        //     stay resident even on a fresh install.
        db.rawQuery("PRAGMA synchronous = NORMAL", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA temp_store = MEMORY", null).use { it.moveToFirst() }
        db.rawQuery("PRAGMA cache_size = -65536", null).use { it.moveToFirst() }
        return db
    }

    private fun queryLong(db: SQLiteDatabase, sql: String): Long {
        val cursor = db.rawQuery(sql, null)
        return cursor.use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    // ── Index management for bulk import performance ────────────────
    // Index DDLs live in the companion object at the top of the class so
    // they can also be referenced by HotPathIndexDriftTest.

    private fun dropIndexes(db: SQLiteDatabase, indexes: Array<Pair<String, String>>) {
        for ((name, _) in indexes) db.execSQL("DROP INDEX IF EXISTS $name")
    }

    private fun createIndexes(db: SQLiteDatabase, indexes: Array<Pair<String, String>>) {
        for ((_, ddl) in indexes) db.execSQL(ddl)
    }

    /** Drop climb+stat indexes, run [block], rebuild indexes, then PRAGMA
     *  optimize. [onRebuild] fires before the rebuild starts so callers can
     *  surface a "finalizing" status (rebuild can take 30s–2min on a fresh
     *  full sync). */
    private inline fun <R> withDeferredIndexes(
        crossinline onRebuild: () -> Unit = {},
        block: () -> R,
    ): R {
        val db = openTargetDb()
        try {
            dropIndexes(db, CLIMB_INDEXES)
            dropIndexes(db, STAT_INDEXES)
        } finally {
            db.close()
        }
        try {
            return block()
        } finally {
            onRebuild()
            val db2 = openTargetDb()
            try {
                createIndexes(db2, CLIMB_INDEXES)
                createIndexes(db2, STAT_INDEXES)
                // Note: PRAGMA optimize used to run here but was dropped —
                // on a fresh import it triggers a full ANALYZE pass over
                // the freshly-built indexes (174k climbs + 290k stats),
                // which takes the same 10-30s the user just waited
                // through for the index rebuild. The SQLite query planner
                // copes fine with fresh indexes that have no sqlite_stat1
                // entries; ANALYZE can be re-introduced in an idle-time
                // worker if a query-plan regression actually shows up.
            } finally {
                db2.close()
            }
        }
    }

    /**
     * Refresh SQLite query-planner statistics (`sqlite_stat1`).
     *
     * Both import paths ([importFromChunks] for Kilter, [importMoonBoardSnapshot]
     * for MoonBoard) go through [withDeferredIndexes], which deliberately skips
     * `ANALYZE` inline — a full pass adds 10-30s to the visible "finalizing"
     * phase. Per the note there, it must instead run once, detached, after a
     * sync completes: without fresh stats the planner mis-plans multi-table
     * filtered counts once the catalogue is large. The MoonBoard catalogue
     * alone adds ~245k climbs, which turned `countFilteredClimbs` into a ~3s
     * query and janked the UI. Safe to call on a background dispatcher.
     */
    fun analyzeDatabase() {
        val db = openTargetDb()
        try {
            val t0 = System.currentTimeMillis()
            db.execSQL("ANALYZE")
            Log.i(TAG, "ANALYZE done in ${System.currentTimeMillis() - t0}ms")
        } finally {
            db.close()
        }
    }

    private fun importPlacements(rawDb: SQLiteDatabase): Int {
        val isCruxCoachSchema = hasTable(rawDb, "aurora_placement")
        val query = if (isCruxCoachSchema) {
            // aurora_placement already has x/y pre-joined; PK is placement_id
            """SELECT placement_id, hole_id, set_id, x, y
               FROM aurora_placement"""
        } else {
            // Import placements for ALL layouts the source carries — pre-
            // 0.1.4 cron output had only Original (layout 1, product 1)
            // so a `MIN`-pinned filter happened to be correct; the 0.1.4
            // cron ships Homewall (layout 8, product 7) too. Filtering
            // by `layout_id IN (SELECT id FROM layouts)` covers whatever
            // the meta chunk decided to include without us hard-coding
            // a layout list here.
            """SELECT p.id, p.hole_id, p.set_id, h.x, h.y
               FROM placements p
               JOIN holes h ON p.hole_id = h.id
               WHERE p.layout_id IN (SELECT id FROM layouts)"""
        }
        val cursor = rawDb.rawQuery(query, null)
        var inserted = 0
        cursor.use {
            val rows = mutableListOf<PlacementRow>()
            while (it.moveToNext()) {
                rows.add(
                    PlacementRow(
                        placementId = it.getLong(0),
                        holeId = it.getLong(1),
                        setId = it.getLong(2),
                        x = it.getLong(3),
                        y = it.getLong(4)
                    )
                )
                if (rows.size >= BATCH_SIZE) {
                    flushPlacements(rows)
                    inserted += rows.size
                    rows.clear()
                }
            }
            if (rows.isNotEmpty()) {
                flushPlacements(rows)
                inserted += rows.size
            }
        }
        return inserted
    }

    private fun flushPlacements(rows: List<PlacementRow>) {
        boardRepository.runInTransaction {
            for (r in rows) {
                boardRepository.upsertPlacement(r.placementId, r.holeId, r.setId, r.x, r.y)
            }
        }
    }

    private fun importProductSizes(rawDb: SQLiteDatabase) {
        val isKilter = hasTable(rawDb, "product_sizes")
        val table = if (isKilter) "product_sizes" else "aurora_product_size"
        // Kilter schema has is_listed; CruxCoach aurora_product_size does not
        val filter = if (isKilter) " WHERE is_listed = 1" else ""
        val cursor = rawDb.rawQuery(
            """SELECT id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename
               FROM $table$filter""",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                boardRepository.upsertProductSize(
                    id = it.getLong(0),
                    productId = it.getLong(1),
                    name = it.getString(2),
                    edgeLeft = it.getLong(3),
                    edgeRight = it.getLong(4),
                    edgeBottom = it.getLong(5),
                    edgeTop = it.getLong(6),
                    imageFilename = if (it.isNull(7)) null else it.getString(7)
                )
            }
        }
    }

    private fun importBoardImages(rawDb: SQLiteDatabase) {
        val isKilter = hasTable(rawDb, "product_sizes_layouts_sets")
        val table = if (isKilter) "product_sizes_layouts_sets" else "aurora_board_image"
        // Kilter schema has is_listed; CruxCoach aurora_board_image does not
        val filter = if (isKilter) " AND is_listed = 1" else ""
        val cursor = rawDb.rawQuery(
            """SELECT id, product_size_id, layout_id, set_id, image_filename
               FROM $table
               WHERE image_filename IS NOT NULL$filter""",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                boardRepository.upsertBoardImage(
                    id = it.getLong(0),
                    productSizeId = it.getLong(1),
                    layoutId = it.getLong(2),
                    setId = it.getLong(3),
                    imageFilename = it.getString(4)
                )
            }
        }
    }

    private fun importLeds(rawDb: SQLiteDatabase) {
        val table = if (hasTable(rawDb, "leds")) "leds" else "aurora_led"
        val cursor = rawDb.rawQuery(
            "SELECT hole_id, product_size_id, position FROM $table",
            null
        )
        cursor.use {
            val rows = mutableListOf<Triple<Long, Long, Long>>()
            while (it.moveToNext()) {
                rows.add(Triple(it.getLong(0), it.getLong(1), it.getLong(2)))
                if (rows.size >= BATCH_SIZE) {
                    boardRepository.runInTransaction {
                        for ((holeId, productSizeId, position) in rows) {
                            boardRepository.upsertLed(holeId, productSizeId, position)
                        }
                    }
                    rows.clear()
                }
            }
            if (rows.isNotEmpty()) {
                boardRepository.runInTransaction {
                    for ((holeId, productSizeId, position) in rows) {
                        boardRepository.upsertLed(holeId, productSizeId, position)
                    }
                }
            }
        }
    }

    private fun importSyncState(rawDb: SQLiteDatabase) {
        // Three source flavours with disjoint marker tables: Kilter-APK DBs
        // carry `shared_syncs`, modern CruxCoach DBs (in-app offline share =
        // the sender's own cruxcoach.db) carry `sync_states`, pre-rename
        // CruxCoach bundles `aurora_sync_state` — kept as the final fallback
        // so legacy sources keep their historical behaviour. All three share
        // the same two columns, so only the table name varies. Pre-fix a
        // modern source fell through to aurora_sync_state and the whole
        // offline-share import died here ("no such table") — at the very
        // end, after minutes of climb copying.
        val table = when {
            hasTable(rawDb, "shared_syncs") -> "shared_syncs"
            hasTable(rawDb, LocalShareSchema.MODERN_MARKER_TABLE) -> LocalShareSchema.MODERN_MARKER_TABLE
            else -> "aurora_sync_state"
        }
        val cursor = rawDb.rawQuery(
            "SELECT table_name, last_synchronized_at FROM $table",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                boardRepository.upsertSyncState(it.getString(0), it.getString(1))
            }
        }
    }

    /**
     * Bulk-copy the geometry/metadata tables from a MODERN CruxCoach source
     * (in-app offline share) via ATTACH. The statements live in
     * [LocalShareSchema] so LocalShareModernSchemaTest can execute every one
     * of them against the real SQLDelight-generated schema — column drift on
     * either side fails the build instead of the next offline share.
     *
     * INSERT OR REPLACE against brand-composite PKs: idempotent, merges
     * brands this device doesn't have yet, never deletes local rows.
     *
     * @return number of placement rows the source carried (progress display,
     *   mirroring what [importPlacements] reports on the legacy path).
     */
    /**
     * Zero-write validation of a modern share source: compiles every
     * geometry-copy SELECT ([LocalShareSchema.MODERN_GEOMETRY_SOURCE_PROBES])
     * against the ATTACHed source. A source whose schema predates any
     * referenced table/column (sender app older than the receiver) fails
     * HERE — before climbs/stats are written — instead of aborting the
     * geometry transaction after minutes of copying (partial import).
     *
     * @throws IllegalStateException with a user-actionable message.
     */
    private fun preflightModernSource(srcFile: File) {
        val targetDb = openTargetDb()
        try {
            targetDb.execSQL("ATTACH DATABASE ? AS src", arrayOf(srcFile.absolutePath))
            try {
                for (probe in LocalShareSchema.MODERN_GEOMETRY_SOURCE_PROBES) {
                    try {
                        targetDb.rawQuery(probe, null).use { it.count }
                    } catch (e: android.database.SQLException) {
                        throw IllegalStateException(
                            "Die geteilte Datenbank stammt von einer älteren " +
                                "CruxCoach-Version — bitte zuerst die App auf dem " +
                                "sendenden Gerät aktualisieren. (${e.message})",
                            e
                        )
                    }
                }
            } finally {
                targetDb.execSQL("DETACH DATABASE src")
            }
        } finally {
            targetDb.close()
        }
    }

    private fun importModernGeometry(srcFile: File): Int {
        val targetDb = openTargetDb()
        try {
            targetDb.execSQL("ATTACH DATABASE ? AS src", arrayOf(srcFile.absolutePath))
            try {
                targetDb.beginTransaction()
                try {
                    for (statement in LocalShareSchema.MODERN_GEOMETRY_COPY) {
                        targetDb.execSQL(statement)
                    }
                    targetDb.setTransactionSuccessful()
                } finally {
                    targetDb.endTransaction()
                }
                return queryLong(targetDb, "SELECT COUNT(*) FROM src.placements").toInt()
            } finally {
                targetDb.execSQL("DETACH DATABASE src")
            }
        } finally {
            targetDb.close()
        }
    }

    /**
     * Import the kilter_board_location table from a chunk SQLite file via
     * ATTACH DATABASE. The chunk's table name is the same as ours, so a
     * single SELECT replaces the entire row dataset (cron always publishes
     * a complete snapshot, never deltas — the dataset is small).
     *
     * If the chunk is structurally invalid (table missing, etc.) we log
     * and bail without throwing — locations are non-essential and must
     * not break the rest of the sync.
     */
    private fun importLocations(rawDb: SQLiteDatabase) {
        if (!hasTable(rawDb, "kilter_board_location")) {
            Log.w("BoardDatabaseImporter", "locations chunk missing kilter_board_location table — skipping")
            return
        }
        val chunkPath = rawDb.path ?: run {
            Log.w("BoardDatabaseImporter", "locations chunk has no path (in-memory) — skipping")
            return
        }
        val targetDb = openTargetDb()
        try {
            targetDb.execSQL("ATTACH DATABASE ? AS loc_src", arrayOf(chunkPath))

            // Source-side row count first. A zero-row chunk (pipeline bug,
            // truncated upload) would otherwise wipe the populated local
            // table and leave the user with an empty map.
            val srcLocCount = queryLong(targetDb, "SELECT COUNT(*) FROM loc_src.kilter_board_location")
            if (srcLocCount == 0L) {
                Log.w("BoardDatabaseImporter", "locations chunk has 0 source rows — refusing to wipe local table")
            } else {
                val beforeCount = queryLong(targetDb, "SELECT COUNT(*) FROM kilter_board_location")
                // board_brand landed in the locations chunk alongside
                // MoonBoard gyms (0.2.0 cron). Pre-0.2.0 chunks lack the
                // column → fall back to the schema default 'kilter', which
                // is correct since every such row is a Kilter installation.
                val srcLocCols = rawDb.rawQuery(
                    "PRAGMA table_info(kilter_board_location)", null
                ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(1)) } }
                val brandExpr = if ("board_brand" in srcLocCols)
                    "COALESCE(board_brand, 'kilter')" else "'kilter'"
                // wellpass landed in the 0.2.0 Phase-2 chunk; pre-Phase-2
                // chunks lack it → NULL (unknown), matching the schema default.
                val wellpassExpr = if ("wellpass" in srcLocCols) "wellpass" else "NULL"
                targetDb.beginTransaction()
                try {
                    // Replace-all semantics: cron snapshot is authoritative,
                    // so old rows that fell out of the dataset (delisted gym,
                    // closed location) get removed automatically.
                    targetDb.execSQL("DELETE FROM kilter_board_location")
                    targetDb.execSQL("""
                        INSERT INTO kilter_board_location(
                            gym_uuid, name, lat, lng, address, city, country_code,
                            phone, email, url, instagram,
                            layout_name, layout_id, size_label, product_size_id,
                            access_type, adjustability, fixed_angle, frame_maker,
                            board_brand, wellpass
                        )
                        SELECT gym_uuid, name, lat, lng, address, city, country_code,
                               phone, email, url, instagram,
                               layout_name, layout_id, size_label, product_size_id,
                               COALESCE(access_type, 'UNKNOWN'),
                               COALESCE(adjustability, 'UNKNOWN'),
                               fixed_angle, frame_maker,
                               $brandExpr, $wellpassExpr
                        FROM loc_src.kilter_board_location
                    """)
                    targetDb.setTransactionSuccessful()
                } finally {
                    targetDb.endTransaction()
                }
                val afterCount = queryLong(targetDb, "SELECT COUNT(*) FROM kilter_board_location")
                Log.i("BoardDatabaseImporter", "kilter_board_location: $beforeCount → $afterCount (source=$srcLocCount)")
            }

            // Per-wall detail (FEAT-007). Additive + guarded: pre-0.1.6
            // chunks have no kilter_board_wall, so skip silently rather
            // than fail the (critical) location import. Own transaction
            // so a wall-side error never rolls back locations.
            if (hasTable(rawDb, "kilter_board_wall")) {
                try {
                    val srcWallCount = queryLong(targetDb, "SELECT COUNT(*) FROM loc_src.kilter_board_wall")
                    if (srcWallCount == 0L) {
                        Log.w("BoardDatabaseImporter", "kilter_board_wall chunk has 0 source rows — refusing to wipe local table")
                    } else {
                        val beforeWalls = queryLong(targetDb, "SELECT COUNT(*) FROM kilter_board_wall")
                        targetDb.beginTransaction()
                        try {
                            targetDb.execSQL("DELETE FROM kilter_board_wall")
                            targetDb.execSQL("""
                                INSERT INTO kilter_board_wall(
                                    wall_uuid, gym_uuid, name, product_name, layout_id,
                                    product_layout_uuid, product_size_id, size_label, is_adjustable,
                                    min_angle, max_angle, angle_increments, fixed_angle,
                                    accumulated_hold_set_value, serial_number, is_listed
                                )
                                SELECT wall_uuid, gym_uuid, name, product_name, layout_id,
                                       product_layout_uuid, product_size_id, size_label, is_adjustable,
                                       min_angle, max_angle, angle_increments, fixed_angle,
                                       accumulated_hold_set_value, serial_number, is_listed
                                FROM loc_src.kilter_board_wall
                            """)
                            targetDb.setTransactionSuccessful()
                        } finally {
                            targetDb.endTransaction()
                        }
                        val afterWalls = queryLong(targetDb, "SELECT COUNT(*) FROM kilter_board_wall")
                        Log.i("BoardDatabaseImporter", "kilter_board_wall: $beforeWalls → $afterWalls (source=$srcWallCount)")
                    }
                } catch (e: Exception) {
                    Log.w("BoardDatabaseImporter", "kilter_board_wall import failed (non-fatal)", e)
                }
            } else {
                Log.d("BoardDatabaseImporter", "locations chunk has no kilter_board_wall (pre-0.1.6 chunk) — skipping walls")
            }
            targetDb.execSQL("DETACH DATABASE loc_src")
        } catch (e: Exception) {
            try { targetDb.execSQL("DETACH DATABASE loc_src") } catch (_: Exception) {}
            Log.w("BoardDatabaseImporter", "locations chunk import failed", e)
        } finally {
            targetDb.close()
        }
    }

    // ── Row data classes ─────────────────────────────────────────────

    private data class PlacementRow(
        val placementId: Long, val holeId: Long, val setId: Long,
        val x: Long, val y: Long
    )
}
