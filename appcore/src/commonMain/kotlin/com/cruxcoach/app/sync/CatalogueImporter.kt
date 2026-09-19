package com.cruxcoach.app.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.data.BoardHotPathIndexes
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.FramesBinaryCodec

enum class ImportPhase { CLIMBS, STATS, LAYOUT, FINALIZING }

data class ImportProgress(val phase: ImportPhase, val done: Int, val total: Int)

enum class ImportFailure { SOURCE_REJECTED, DATABASE_ERROR, UNSUPPORTED }

sealed class ImportResult {
    data class Imported(val climbs: Long, val stats: Long, val placements: Long) : ImportResult()
    data class Failed(val reason: ImportFailure) : ImportResult()
}

/** A verified file whose contents still fail validation; nothing of it is kept. */
private class SourceRejected(message: String) : Exception(message)

/**
 * Copies verified, decompressed catalogue SQLite files into BoardDB — the
 * port of Android's `BoardDatabaseImporter` (Blossom paths only).
 *
 * Like Android this uses `ATTACH DATABASE` + `INSERT … SELECT`, so hundreds
 * of thousands of rows never cross into Kotlin. SQLDelight's native driver
 * pools connections and ATTACH is per connection, which dictates the shape
 * of [withAttached]: ATTACH and every read and write of the attached file
 * happen inside one transaction, which pins a single connection to the
 * calling thread.
 * For the same reason nothing here suspends, and callers must run one import
 * at a time ([CatalogueSyncController] does).
 *
 * Each source file is applied in a single transaction: a failure leaves
 * BoardDB exactly as it was before that file.
 */
class CatalogueImporter(private val handle: BoardDatabaseHandle) {
    private val driver get() = handle.driver

    /** Diagnostic text of the last failure, for logs only; never shown to users. */
    var lastFailureDetail: String? = null
        private set

    // ── Kilter: multi-chunk ──────────────────────────────────────────

    fun importKilterChunks(
        metaFiles: List<String>,
        climbFiles: List<String>,
        statFiles: List<String>,
        locationFiles: List<String> = emptyList(),
        betaFiles: List<String> = emptyList(),
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = guarded {
        // Snapshot once: later chunks see the rows earlier chunks inserted and
        // would otherwise re-enable the refresh passes over brand-new rows.
        val freshClimbs = queryLong("SELECT COUNT(*) FROM climbs") == 0L
        withDeferredIndexes(onRebuild = { onProgress(ImportProgress(ImportPhase.FINALIZING, 0, 0)) }) {
            climbFiles.forEachIndexed { i, file ->
                onProgress(ImportProgress(ImportPhase.CLIMBS, i, climbFiles.size))
                withAttached(file, "src") { importKilterClimbs(freshClimbs) }
            }
            statFiles.forEachIndexed { i, file ->
                onProgress(ImportProgress(ImportPhase.STATS, i, statFiles.size))
                withAttached(file, "src") { importKilterStats() }
            }
            // Geometry is tiny and re-imported every run so a newly published layout arrives on existing installs.
            metaFiles.forEachIndexed { i, file ->
                onProgress(ImportProgress(ImportPhase.LAYOUT, i, metaFiles.size))
                withAttached(file, "src") { importKilterMeta() }
            }
        }
        backfillMoveCounts()
        if (queryLong("SELECT COUNT(*) FROM sync_states WHERE table_name = 'metadata_v7'") == 0L) {
            exec("INSERT OR REPLACE INTO sync_states(table_name, last_synchronized_at) VALUES ('metadata_v7', 'done')")
        }
        // Gym locations are non-essential: a bad chunk must not sink the climbs already imported.
        for (file in locationFiles) {
            try { withAttached(file, "loc_src") { importLocations() } } catch (e: Exception) { /* keep previous locations */ }
        }
        for (file in betaFiles) {
            withAttached(file, "beta_src") {
                replaceEmbeddedBetaLinks("beta_src", "kilter") ?: throw SourceRejected("Kilter beta chunk has no beta link table")
            }
        }
        result("kilter")
    }

    private fun importKilterClimbs(freshInstall: Boolean) {
        val cols = tableColumns("src", "climbs")
        if (cols.isEmpty()) throw SourceRejected("climbs chunk has no climbs table")
        val hasOrigin = "origin" in cols
        val hasPubkey = "created_by_pubkey" in cols
        val hasMethod = "method" in cols
        val moveCountExpr = if ("move_count" in cols) "COALESCE(move_count, 0)" else "0"
        val methodExpr = if (hasMethod) "method" else "NULL"
        // A pre-method source must not clear a method an earlier snapshot set.
        val methodRefresh = if (hasMethod) ", method" else ""
        val baseOriginExpr = if (hasOrigin) "COALESCE(origin, 'kilter')" else "'kilter'"
        val pubkeyExpr = if (hasPubkey) "created_by_pubkey" else "NULL"
        // A setter pubkey is authoritative for CruxCoach authorship even when the blob's origin lags.
        val originExpr = if (hasPubkey) {
            "CASE WHEN created_by_pubkey IS NOT NULL AND created_by_pubkey != '' THEN 'cruxcoach' ELSE $baseOriginExpr END"
        } else baseOriginExpr
        val brandExpr = if ("board_brand" in cols) "COALESCE(board_brand, 'kilter')" else "'kilter'"
        val deletedGuard = if ("is_deleted" in cols) " AND COALESCE(is_deleted, 0) = 0" else ""
        val draftFilter = (if ("source" in cols) " AND LOWER(COALESCE(source, 'kilter')) != 'local'" else "") + deletedGuard

        exec(
            """
            CREATE TEMP TABLE IF NOT EXISTS chunk_norm (
                uuid TEXT PRIMARY KEY,
                layout_id INTEGER, setter_username TEXT, name TEXT, frames TEXT,
                frames_count INTEGER, is_listed INTEGER,
                edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER,
                created_at INTEGER, description TEXT,
                is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER,
                move_count INTEGER, origin TEXT, created_by_pubkey TEXT,
                board_brand TEXT, method TEXT
            ) WITHOUT ROWID
            """,
        )
        exec("DELETE FROM chunk_norm")
        // LOWER(TRIM(uuid)) is the canonical identity; staging it PK-indexed keeps every pass an O(log n) lookup.
        exec(
            """
            INSERT OR IGNORE INTO chunk_norm
            SELECT LOWER(TRIM(uuid)), layout_id, setter_username, name, frames,
                   frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top, created_at,
                   COALESCE(description, ''), COALESCE(is_nomatch, 0),
                   COALESCE(frames_pace, 0), COALESCE(hsm, 0),
                   $moveCountExpr, $originExpr, $pubkeyExpr, $brandExpr, $methodExpr
            FROM src.climbs
            WHERE 1=1$draftFilter
            """,
        )
        // New rows only, and never tombstones: existing rows keep every CruxCoach lifecycle column.
        exec(
            """
            INSERT OR IGNORE INTO climbs(
                uuid, layout_id, setter_username, name, frames,
                frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top, created_at,
                description, is_nomatch, frames_pace, hsm, move_count,
                origin, created_by_pubkey, board_brand, method)
            SELECT uuid, layout_id, setter_username, name, frames,
                   frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top, created_at,
                   description, is_nomatch, frames_pace, hsm, move_count,
                   origin, created_by_pubkey, board_brand, method
            FROM chunk_norm WHERE is_listed = 1
            """,
        )
        if (freshInstall) return
        // Content refresh: catalogue-origin rows only, and only from listed rows so a
        // tombstone shell (name='', frames='') cannot wipe what the logbook still shows.
        exec(
            """
            UPDATE climbs SET
                (layout_id, setter_username, name, frames, frames_count, is_listed,
                 edge_left, edge_right, edge_bottom, edge_top, created_at, description,
                 is_nomatch, frames_pace, hsm, move_count$methodRefresh)
                = (SELECT layout_id, setter_username, name, frames, frames_count, is_listed,
                          edge_left, edge_right, edge_bottom, edge_top, created_at, description,
                          is_nomatch, frames_pace, hsm, move_count$methodRefresh
                   FROM chunk_norm WHERE chunk_norm.uuid = main.climbs.uuid)
            WHERE origin = 'kilter'
              AND uuid IN (SELECT uuid FROM chunk_norm WHERE is_listed = 1)
            """,
        )
        exec("UPDATE climbs SET is_listed = 0 WHERE is_listed = 1 AND uuid IN (SELECT uuid FROM chunk_norm WHERE is_listed = 0)")
        // A delisted community climb is a deletion; arming is_deleted keeps a stray rebroadcast from re-listing it.
        exec(
            "UPDATE climbs SET is_deleted = 1 WHERE origin = 'cruxcoach' AND is_deleted = 0 " +
                "AND uuid IN (SELECT uuid FROM chunk_norm WHERE is_listed = 0)",
        )
        exec(
            """
            UPDATE climbs SET setter_username = COALESCE(
                (SELECT setter_username FROM chunk_norm WHERE chunk_norm.uuid = main.climbs.uuid),
                main.climbs.setter_username)
            WHERE origin = 'cruxcoach' AND uuid IN (SELECT uuid FROM chunk_norm)
            """,
        )
        if (hasOrigin) {
            exec(
                "UPDATE climbs SET origin = 'cruxcoach' WHERE origin != 'cruxcoach' " +
                    "AND uuid IN (SELECT uuid FROM chunk_norm WHERE origin = 'cruxcoach')",
            )
        }
        if (hasPubkey) {
            exec(
                """
                UPDATE climbs SET created_by_pubkey = (
                    SELECT created_by_pubkey FROM chunk_norm WHERE chunk_norm.uuid = main.climbs.uuid)
                WHERE created_by_pubkey IS NULL
                  AND uuid IN (SELECT uuid FROM chunk_norm WHERE created_by_pubkey IS NOT NULL)
                """,
            )
        }
    }

    private fun importKilterStats() {
        if (tableColumns("src", "climb_stats").isEmpty()) throw SourceRejected("stats chunk has no climb_stats table")
        exec(
            """
            INSERT OR REPLACE INTO climb_stats(
                climb_uuid, angle, display_difficulty, difficulty_average,
                quality_average, ascensionist_count, benchmark_difficulty,
                fa_username, fa_at, layout_id)
            SELECT LOWER(TRIM(s.climb_uuid)), s.angle, s.display_difficulty, s.difficulty_average,
                   s.quality_average, s.ascensionist_count, s.benchmark_difficulty,
                   s.fa_username, s.fa_at,
                   COALESCE((SELECT c.layout_id FROM climbs c WHERE c.uuid = LOWER(TRIM(s.climb_uuid))), 0)
            FROM src.climb_stats s
            """,
        )
    }

    private fun importKilterMeta() {
        exec(
            """
            INSERT OR REPLACE INTO placements(board_brand, placement_id, hole_id, set_id, x, y)
            SELECT 'kilter', p.id, p.hole_id, p.set_id, h.x, h.y
            FROM src.placements p JOIN src.holes h ON p.hole_id = h.id
            WHERE p.layout_id IN (SELECT id FROM src.layouts)
            """,
        )
        exec(
            """
            INSERT OR REPLACE INTO product_sizes(
                board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename)
            SELECT 'kilter', id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename
            FROM src.product_sizes WHERE is_listed = 1
            """,
        )
        exec(
            """
            INSERT OR REPLACE INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename)
            SELECT 'kilter', id, product_size_id, layout_id, set_id, image_filename
            FROM src.product_sizes_layouts_sets WHERE image_filename IS NOT NULL AND is_listed = 1
            """,
        )
        exec(
            "INSERT OR REPLACE INTO leds(board_brand, hole_id, product_size_id, position) " +
                "SELECT 'kilter', hole_id, product_size_id, position FROM src.leds",
        )
        exec(
            "INSERT OR REPLACE INTO sync_states(table_name, last_synchronized_at) " +
                "SELECT table_name, last_synchronized_at FROM src.shared_syncs",
        )
    }

    private fun importLocations() {
        val cols = tableColumns("loc_src", "kilter_board_location")
        if (cols.isEmpty()) return
        // A zero-row chunk (pipeline bug, truncated upload) must not wipe a populated map.
        if (queryLong("SELECT COUNT(*) FROM loc_src.kilter_board_location") > 0L) {
            val brandExpr = if ("board_brand" in cols) "COALESCE(board_brand, 'kilter')" else "'kilter'"
            val wellpassExpr = if ("wellpass" in cols) "wellpass" else "NULL"
            exec("DELETE FROM kilter_board_location")
            exec(
                """
                INSERT INTO kilter_board_location(
                    gym_uuid, name, lat, lng, address, city, country_code, phone, email, url, instagram,
                    layout_name, layout_id, size_label, product_size_id,
                    access_type, adjustability, fixed_angle, frame_maker, board_brand, wellpass)
                SELECT gym_uuid, name, lat, lng, address, city, country_code, phone, email, url, instagram,
                       layout_name, layout_id, size_label, product_size_id,
                       COALESCE(access_type, 'UNKNOWN'), COALESCE(adjustability, 'UNKNOWN'),
                       fixed_angle, frame_maker, $brandExpr, $wellpassExpr
                FROM loc_src.kilter_board_location
                """,
            )
        }
        if (tableColumns("loc_src", "kilter_board_wall").isNotEmpty() &&
            queryLong("SELECT COUNT(*) FROM loc_src.kilter_board_wall") > 0L
        ) {
            exec("DELETE FROM kilter_board_wall")
            exec(
                """
                INSERT INTO kilter_board_wall(
                    wall_uuid, gym_uuid, name, product_name, layout_id,
                    product_layout_uuid, product_size_id, size_label, is_adjustable,
                    min_angle, max_angle, angle_increments, fixed_angle,
                    accumulated_hold_set_value, serial_number, is_listed)
                SELECT wall_uuid, gym_uuid, name, product_name, layout_id,
                       product_layout_uuid, product_size_id, size_label, is_adjustable,
                       min_angle, max_angle, angle_increments, fixed_angle,
                       accumulated_hold_set_value, serial_number, is_listed
                FROM loc_src.kilter_board_wall
                """,
            )
        }
    }

    // ── Aurora family: one full snapshot per board ───────────────────

    fun importAuroraSnapshot(
        snapshotFile: String,
        boardBrand: String,
        onProgress: (ImportProgress) -> Unit = {},
    ): ImportResult = guarded {
        if (boardBrand !in AURORA_FAMILY) throw SourceRejected("not an Aurora-family board")
        var hasMoveCount = false
        // Indexes stay live: these snapshots are small next to the combined catalogue.
        withAttached(snapshotFile, "ab") {
            val cols = tableColumns("ab", "climbs")
            if (cols.isEmpty()) throw SourceRejected("snapshot has no climbs table")
            hasMoveCount = "move_count" in cols
            val total = queryLong("SELECT COUNT(*) FROM ab.climbs WHERE is_listed = 1").toInt()
            onProgress(ImportProgress(ImportPhase.CLIMBS, 0, total))
            mergeSnapshotClimbs("ab", boardBrand, cols)
            val statTotal = queryLong("SELECT COUNT(*) FROM ab.climb_stats").toInt()
            onProgress(ImportProgress(ImportPhase.STATS, 0, statTotal))
            exec(
                """
                INSERT OR REPLACE INTO climb_stats(
                    climb_uuid, angle, display_difficulty, difficulty_average,
                    quality_average, ascensionist_count, benchmark_difficulty,
                    fa_username, fa_at, official_kilter_difficulty, layout_id)
                SELECT LOWER(TRIM(climb_uuid)), angle, display_difficulty, difficulty_average,
                       quality_average, ascensionist_count, benchmark_difficulty,
                       fa_username, fa_at, NULL,
                       COALESCE((SELECT c.layout_id FROM climbs c WHERE c.uuid = LOWER(TRIM(climb_uuid))), 0)
                FROM ab.climb_stats
                """,
            )
            onProgress(ImportProgress(ImportPhase.LAYOUT, 0, 0))
            // Geometry is brand-namespaced: Aurora ids overlap Kilter's same-numbered ids.
            exec(
                """
                INSERT OR REPLACE INTO product_sizes(
                    board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename)
                SELECT ?, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename
                FROM ab.product_sizes
                """,
                boardBrand,
            )
            exec(
                """
                INSERT OR REPLACE INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename)
                SELECT ?, id, product_size_id, layout_id, set_id, image_filename
                FROM ab.product_sizes_layouts_sets WHERE image_filename IS NOT NULL
                """,
                boardBrand,
            )
            exec(
                """
                INSERT OR REPLACE INTO placements(board_brand, placement_id, hole_id, set_id, x, y)
                SELECT ?, p.id, p.hole_id, p.set_id, h.x, h.y
                FROM ab.placements p JOIN ab.holes h ON p.hole_id = h.id
                """,
                boardBrand,
            )
            exec(
                "INSERT OR REPLACE INTO leds(board_brand, hole_id, product_size_id, position) " +
                    "SELECT ?, hole_id, product_size_id, position FROM ab.leds",
                boardBrand,
            )
            if (tableColumns("ab", "placement_roles").isNotEmpty()) {
                exec(
                    "INSERT OR REPLACE INTO placement_roles(board_brand, id, name, led_color, screen_color) " +
                        "SELECT ?, id, name, led_color, screen_color FROM ab.placement_roles",
                    boardBrand,
                )
            }
            replaceEmbeddedBetaLinks("ab", boardBrand)
        }
        onProgress(ImportProgress(ImportPhase.FINALIZING, 0, 0))
        if (!hasMoveCount) backfillMoveCounts()
        result(boardBrand)
    }

    // ── MoonBoard: one snapshot, no geometry ─────────────────────────

    fun importMoonBoardSnapshot(snapshotFile: String, onProgress: (ImportProgress) -> Unit = {}): ImportResult = guarded {
        var hasMoveCount = false
        val hasExistingRows = queryLong("SELECT EXISTS(SELECT 1 FROM climbs WHERE board_brand = 'moonboard' LIMIT 1)") == 1L
        withDeferredIndexes(onRebuild = { onProgress(ImportProgress(ImportPhase.FINALIZING, 0, 0)) }) {
            withAttached(snapshotFile, "mb") {
                val cols = tableColumns("mb", "climbs")
                if (cols.isEmpty()) throw SourceRejected("snapshot has no climbs table")
                hasMoveCount = "move_count" in cols
                val hasAliases = tableColumns("mb", "climb_aliases").isNotEmpty()
                if (hasAliases) {
                    // Functions stay on the small alias side so the climbs PK remains usable.
                    val invalid = queryLong(
                        """
                        SELECT COUNT(*) FROM mb.climb_aliases a
                        LEFT JOIN mb.climbs alias_climb ON alias_climb.uuid = LOWER(TRIM(a.alias_uuid))
                        LEFT JOIN mb.climbs canonical_climb ON canonical_climb.uuid = LOWER(TRIM(a.canonical_uuid))
                        LEFT JOIN mb.climb_aliases chained ON chained.alias_uuid = a.canonical_uuid COLLATE NOCASE
                        WHERE TRIM(a.alias_uuid) = '' OR TRIM(a.canonical_uuid) = ''
                           OR LOWER(a.alias_uuid) = LOWER(a.canonical_uuid)
                           OR a.match_kind != 'legacy-exact-duplicate'
                           OR alias_climb.uuid IS NULL OR canonical_climb.uuid IS NULL
                           OR alias_climb.is_listed != 1 OR canonical_climb.is_listed != 1
                           OR chained.alias_uuid IS NOT NULL
                        """,
                    )
                    if (invalid != 0L) throw SourceRejected("invalid or chained climb aliases")
                }
                val total = queryLong("SELECT COUNT(*) FROM mb.climbs WHERE is_listed = 1").toInt()
                onProgress(ImportProgress(ImportPhase.CLIMBS, 0, total))
                mergeSnapshotClimbs("mb", "moonboard", cols)

                val statTotal = queryLong("SELECT COUNT(*) FROM mb.climb_stats").toInt()
                onProgress(ImportProgress(ImportPhase.STATS, 0, statTotal))
                val normalizedLayout =
                    "COALESCE((SELECT c.layout_id FROM climbs c WHERE c.uuid = LOWER(TRIM(incoming.climb_uuid))), 0)"
                val unchangedFilter = if (hasExistingRows) {
                    """
                    WHERE NOT EXISTS (
                        SELECT 1 FROM climb_stats existing
                        WHERE existing.climb_uuid = LOWER(TRIM(incoming.climb_uuid))
                          AND existing.angle = incoming.angle
                          AND existing.display_difficulty IS incoming.display_difficulty
                          AND existing.difficulty_average IS incoming.difficulty_average
                          AND existing.quality_average IS incoming.quality_average
                          AND existing.ascensionist_count IS incoming.ascensionist_count
                          AND existing.benchmark_difficulty IS incoming.benchmark_difficulty
                          AND existing.fa_username IS incoming.fa_username
                          AND existing.fa_at IS incoming.fa_at
                          AND existing.official_kilter_difficulty IS NULL
                          AND existing.layout_id IS $normalizedLayout)
                    """
                } else ""
                exec(
                    """
                    INSERT OR REPLACE INTO climb_stats(
                        climb_uuid, angle, display_difficulty, difficulty_average,
                        quality_average, ascensionist_count, benchmark_difficulty,
                        fa_username, fa_at, official_kilter_difficulty, layout_id)
                    SELECT LOWER(TRIM(incoming.climb_uuid)), incoming.angle,
                           incoming.display_difficulty, incoming.difficulty_average,
                           incoming.quality_average, incoming.ascensionist_count,
                           incoming.benchmark_difficulty, incoming.fa_username,
                           incoming.fa_at, NULL, $normalizedLayout
                    FROM mb.climb_stats incoming
                    $unchangedFilter
                    """,
                )
                // Re-list aliases hidden by an older import, then replace the bridge authoritatively.
                exec(
                    "UPDATE climbs SET is_listed = 1 WHERE board_brand = 'moonboard' " +
                        "AND uuid IN (SELECT alias_uuid FROM moonboard_climb_aliases) AND is_deleted = 0",
                )
                exec("DELETE FROM moonboard_climb_aliases")
                if (hasAliases) {
                    exec(
                        "INSERT INTO moonboard_climb_aliases(alias_uuid, canonical_uuid, match_kind) " +
                            "SELECT LOWER(TRIM(alias_uuid)), LOWER(TRIM(canonical_uuid)), match_kind FROM mb.climb_aliases",
                    )
                    exec(
                        "UPDATE climbs SET is_listed = 0 WHERE board_brand = 'moonboard' AND is_deleted = 0 " +
                            "AND uuid IN (SELECT alias_uuid FROM moonboard_climb_aliases)",
                    )
                }
            }
        }
        if (!hasMoveCount) backfillMoveCounts()
        result("moonboard")
    }

    // ── Not ported yet ───────────────────────────────────────────────

    /** Quantum snapshot import is not ported; the controller reports the brand as unsupported. */
    fun importQuantumSnapshot(@Suppress("UNUSED_PARAMETER") snapshotFile: String): ImportResult =
        ImportResult.Failed(ImportFailure.UNSUPPORTED)

    /** Standalone beta-media / MoonBoard beta-link snapshots are not ported. */
    fun importBetaMediaSnapshot(@Suppress("UNUSED_PARAMETER") snapshotFile: String, @Suppress("UNUSED_PARAMETER") board: String): ImportResult =
        ImportResult.Failed(ImportFailure.UNSUPPORTED)

    // ── Shared passes ────────────────────────────────────────────────

    /**
     * Snapshot merge. A blanket INSERT OR REPLACE is forbidden: the snapshots
     * also carry community climbs, and REPLACE would reset the author's
     * publish state and resurrect locally deleted rows.
     */
    private fun mergeSnapshotClimbs(alias: String, boardBrand: String, cols: Set<String>) {
        val hasPubkey = "created_by_pubkey" in cols
        val moveCountExpr = if ("move_count" in cols) "COALESCE(move_count, 0)" else "0"
        val methodExpr = if ("method" in cols) "method" else "NULL"
        val baseOriginExpr = if ("origin" in cols) "COALESCE(origin, 'kilter')" else "'kilter'"
        val pubkeyExpr = if (hasPubkey) "created_by_pubkey" else "NULL"
        val originExpr = if (hasPubkey) {
            "CASE WHEN created_by_pubkey IS NOT NULL AND created_by_pubkey != '' THEN 'cruxcoach' ELSE $baseOriginExpr END"
        } else baseOriginExpr
        exec(
            """
            CREATE TEMP TABLE IF NOT EXISTS snapshot_norm (
                uuid TEXT PRIMARY KEY,
                layout_id INTEGER, setter_username TEXT, name TEXT, frames TEXT,
                frames_count INTEGER, is_listed INTEGER,
                edge_left INTEGER, edge_right INTEGER, edge_bottom INTEGER, edge_top INTEGER,
                created_at INTEGER, description TEXT,
                is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER,
                move_count INTEGER, origin TEXT, created_by_pubkey TEXT, method TEXT
            ) WITHOUT ROWID
            """,
        )
        exec("CREATE TEMP TABLE IF NOT EXISTS snapshot_changed (uuid TEXT PRIMARY KEY) WITHOUT ROWID")
        exec("DELETE FROM snapshot_norm")
        exec("DELETE FROM snapshot_changed")
        exec(
            """
            INSERT OR IGNORE INTO snapshot_norm
            SELECT LOWER(TRIM(uuid)), layout_id, setter_username, name, frames,
                   frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top, created_at,
                   COALESCE(description, ''), COALESCE(is_nomatch, 0),
                   COALESCE(frames_pace, 0), COALESCE(hsm, 0), $moveCountExpr,
                   $originExpr, $pubkeyExpr, $methodExpr
            FROM $alias.climbs
            """,
        )
        // Asked before the INSERT: on a first import every row is new and refreshing them again is pure cost.
        val hasExistingCatalogueRows = queryLong(
            "SELECT EXISTS(SELECT 1 FROM climbs c INNER JOIN snapshot_norm s ON s.uuid = c.uuid " +
                "WHERE c.origin = 'kilter' AND s.is_listed = 1 LIMIT 1)",
        ) == 1L
        exec(
            """
            INSERT OR IGNORE INTO climbs(
                uuid, layout_id, setter_username, name, frames,
                frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top, created_at,
                description, is_nomatch, frames_pace, hsm, move_count,
                board_brand, origin, created_by_pubkey, method)
            SELECT uuid, layout_id, setter_username, name, frames,
                   frames_count, is_listed, edge_left, edge_right, edge_bottom, edge_top, created_at,
                   description, is_nomatch, frames_pace, hsm, move_count,
                   ?, origin, created_by_pubkey, method
            FROM snapshot_norm WHERE is_listed = 1
            """,
            boardBrand,
        )
        if (hasExistingCatalogueRows) {
            // Materialised delta: nested inside the UPDATE, SQLite may pick a repeated-scan plan.
            exec(
                """
                INSERT OR IGNORE INTO snapshot_changed(uuid)
                SELECT s.uuid FROM snapshot_norm s JOIN climbs existing ON existing.uuid = s.uuid
                WHERE s.is_listed = 1 AND existing.origin = 'kilter' AND (
                      existing.layout_id IS NOT s.layout_id OR existing.setter_username IS NOT s.setter_username OR
                      existing.name IS NOT s.name OR existing.frames IS NOT s.frames OR
                      existing.frames_count IS NOT s.frames_count OR existing.is_listed IS NOT s.is_listed OR
                      existing.edge_left IS NOT s.edge_left OR existing.edge_right IS NOT s.edge_right OR
                      existing.edge_bottom IS NOT s.edge_bottom OR existing.edge_top IS NOT s.edge_top OR
                      existing.created_at IS NOT s.created_at OR existing.description IS NOT s.description OR
                      existing.is_nomatch IS NOT s.is_nomatch OR existing.frames_pace IS NOT s.frames_pace OR
                      existing.hsm IS NOT s.hsm OR existing.move_count IS NOT s.move_count OR
                      existing.method IS NOT s.method)
                """,
            )
            exec(
                """
                UPDATE climbs SET
                    (layout_id, setter_username, name, frames, frames_count, is_listed,
                     edge_left, edge_right, edge_bottom, edge_top, created_at, description,
                     is_nomatch, frames_pace, hsm, move_count, method)
                    = (SELECT layout_id, setter_username, name, frames, frames_count, is_listed,
                              edge_left, edge_right, edge_bottom, edge_top, created_at, description,
                              is_nomatch, frames_pace, hsm, move_count, method
                       FROM snapshot_norm WHERE snapshot_norm.uuid = main.climbs.uuid)
                WHERE origin = 'kilter' AND uuid IN (SELECT uuid FROM snapshot_changed)
                """,
            )
        }
        exec("UPDATE climbs SET is_listed = 0 WHERE is_listed = 1 AND uuid IN (SELECT uuid FROM snapshot_norm WHERE is_listed = 0)")
        exec(
            "UPDATE climbs SET is_deleted = 1 WHERE origin = 'cruxcoach' AND is_deleted = 0 " +
                "AND uuid IN (SELECT uuid FROM snapshot_norm WHERE is_listed = 0)",
        )
        // Asymmetric heal for rows imported before the snapshot carried origin.
        exec(
            """
            UPDATE climbs SET origin = (SELECT origin FROM snapshot_norm WHERE snapshot_norm.uuid = main.climbs.uuid)
            WHERE origin = 'kilter' AND uuid IN (SELECT uuid FROM snapshot_norm WHERE origin != 'kilter')
            """,
        )
        exec(
            """
            UPDATE climbs SET created_by_pubkey = (
                SELECT created_by_pubkey FROM snapshot_norm WHERE snapshot_norm.uuid = main.climbs.uuid)
            WHERE created_by_pubkey IS NULL
              AND uuid IN (SELECT uuid FROM snapshot_norm WHERE created_by_pubkey IS NOT NULL)
            """,
        )
    }

    /**
     * Optional beta table inside an attached catalogue. Absent: the slice is
     * untouched (null). Present, even empty: authoritative. Any malformed row
     * rejects the whole source.
     */
    private fun replaceEmbeddedBetaLinks(alias: String, boardBrand: String): Int? {
        val table = when {
            tableColumns(alias, "climb_beta_links").isNotEmpty() -> "climb_beta_links"
            tableColumns(alias, "beta_links").isNotEmpty() -> "beta_links"
            else -> return null
        }
        val columns = tableColumns(alias, table)
        if ("climb_uuid" !in columns) throw SourceRejected("$table has no climb_uuid")
        val urlColumn = when {
            "url" in columns -> "url"
            "link" in columns -> "link"
            else -> throw SourceRejected("$table has no url/link")
        }
        fun text(column: String, fallback: String = "NULL") = if (column in columns) column else fallback
        val providerExpr = if ("provider" in columns) "LOWER(TRIM(provider))"
        else "CASE WHEN LOWER($urlColumn) LIKE '%instagram.com/%' THEN 'instagram' ELSE 'unknown' END"
        if ("board_brand" in columns &&
            queryLong("SELECT COUNT(*) FROM $alias.$table WHERE LOWER(TRIM(board_brand)) != ?", boardBrand) != 0L
        ) throw SourceRejected("$table contains another board brand")
        val invalid = queryLong(
            "SELECT COUNT(*) FROM $alias.$table WHERE TRIM(climb_uuid)='' " +
                "OR TRIM($urlColumn) NOT LIKE 'https://%' OR TRIM($urlColumn) LIKE '% %' " +
                (if ("provider" in columns) "OR TRIM(provider)='' " else "") +
                (if ("thumbnail" in columns) {
                    "OR (thumbnail IS NOT NULL AND (TRIM(thumbnail) NOT LIKE 'https://%' OR TRIM(thumbnail) LIKE '% %')) "
                } else ""),
        )
        if (invalid != 0L) throw SourceRejected("$table contains invalid beta links")
        val orphaned = queryLong(
            "SELECT COUNT(*) FROM $alias.$table b LEFT JOIN climbs c " +
                "ON c.uuid=LOWER(TRIM(b.climb_uuid)) AND c.board_brand=? AND c.is_deleted=0 WHERE c.uuid IS NULL",
            boardBrand,
        )
        if (orphaned != 0L) throw SourceRejected("$table contains beta links for unknown climbs")
        exec("DELETE FROM climb_beta_links WHERE board_brand=?", boardBrand)
        exec(
            """
            INSERT OR IGNORE INTO climb_beta_links(
                board_brand, climb_uuid, url, provider, media_id, foreign_username, angle, thumbnail, created_at)
            SELECT ?, LOWER(TRIM(climb_uuid)), TRIM($urlColumn), $providerExpr,
                   ${text("media_id", text("video_id"))}, ${text("foreign_username")}, ${text("angle")},
                   ${text("thumbnail")}, ${text("created_at")}
            FROM $alias.$table
            """,
            boardBrand,
        )
        return queryLong("SELECT COUNT(*) FROM climb_beta_links WHERE board_brand=?", boardBrand).toInt()
    }

    private class RoleSemantics(val footIds: Set<Int>, val startIds: Set<Int>)

    /**
     * Fills move_count for single-frame climbs the bulk SQL left at 0. Aurora
     * boards number roles board-locally, so each brand is counted against its
     * own placement_roles; brands without rows use Kilter's fixed ids.
     */
    fun backfillMoveCounts() {
        val foot = HashMap<String, MutableSet<Int>>()
        val start = HashMap<String, MutableSet<Int>>()
        query("SELECT board_brand, id, name FROM placement_roles") { c ->
            val brand = c.getString(0) ?: return@query
            val id = c.getLong(1)?.toInt() ?: return@query
            when (c.getString(2)?.lowercase()) {
                "foot" -> foot.getOrPut(brand) { HashSet() }.add(id)
                "start" -> start.getOrPut(brand) { HashSet() }.add(id)
            }
        }
        val semantics = (foot.keys + start.keys).associateWith { RoleSemantics(foot[it].orEmpty(), start[it].orEmpty()) }
        var lastUuid = ""
        while (true) {
            val updates = ArrayList<Pair<String, Long>>()
            var seen = 0
            query(
                "SELECT uuid, frames, board_brand FROM climbs WHERE move_count = 0 AND frames_count = 1 AND uuid > ? " +
                    "ORDER BY uuid LIMIT $BACKFILL_BATCH",
                lastUuid,
            ) { c ->
                seen++
                val uuid = c.getString(0) ?: return@query
                lastUuid = uuid
                val frames = FramesBinaryCodec.decode(c.getBytes(1) ?: ByteArray(0))
                val moves = moveCount(frames, semantics[c.getString(2)])
                if (moves > 0) updates += uuid to moves
            }
            if (seen == 0) break
            handle.database.transaction {
                for ((uuid, moves) in updates) exec("UPDATE climbs SET move_count = ? WHERE uuid = ?", moves, uuid)
            }
        }
    }

    private fun moveCount(frames: String, semantics: RoleSemantics?): Long {
        if (frames.isEmpty() || frames.contains(",")) return 0
        val holds = BoardClimbParser.parseFrames(frames)
        if (semantics == null) return BoardClimbParser.estimateMoveCount(holds).toLong()
        val moves = holds.size - holds.count { it.roleId in semantics.footIds } - holds.count { it.roleId in semantics.startIds }
        return moves.coerceAtLeast(0).toLong()
    }

    /** Planner statistics; run once, detached, after a sync changed the catalogue. */
    fun analyze(): Boolean = try { exec("ANALYZE"); true } catch (e: Exception) { false }

    // ── Index lifecycle ──────────────────────────────────────────────

    /**
     * Only an empty catalogue may drop the hot-path indexes: every board
     * shares these tables, and another brand's browse queries must stay
     * indexed while this one imports. Restoration runs on success and failure.
     */
    private fun <R> withDeferredIndexes(onRebuild: () -> Unit, block: () -> R): R {
        val hasRows = queryLong("SELECT EXISTS(SELECT 1 FROM climbs) OR EXISTS(SELECT 1 FROM climb_stats)") != 0L
        if (hasRows) restoreMissingIndexes()
        else handle.database.transaction { HOT_PATH_INDEXES.forEach { (name, _) -> exec("DROP INDEX IF EXISTS $name") } }
        try {
            return block()
        } finally {
            try { onRebuild() } finally { if (!hasRows) restoreMissingIndexes() }
        }
    }

    private fun restoreMissingIndexes() {
        val existing = queryStrings("SELECT name FROM sqlite_master WHERE type = 'index'").toSet()
        val missing = HOT_PATH_INDEXES.filterNot { it.first in existing }
        if (missing.isNotEmpty()) handle.database.transaction { missing.forEach { (_, ddl) -> exec(ddl) } }
    }

    // ── Plumbing ─────────────────────────────────────────────────────

    private fun guarded(block: () -> ImportResult): ImportResult = try {
        block()
    } catch (e: SourceRejected) {
        lastFailureDetail = e.message
        ImportResult.Failed(ImportFailure.SOURCE_REJECTED)
    } catch (e: Exception) {
        lastFailureDetail = e.message
        ImportResult.Failed(ImportFailure.DATABASE_ERROR)
    }

    private fun result(brand: String) = ImportResult.Imported(
        climbs = queryLong(
            "SELECT COUNT(*) FROM climbs WHERE board_brand = ? AND (source = 'kilter' OR (board_brand = 'quantum' AND source = 'quantum'))",
            brand,
        ),
        stats = queryLong("SELECT COUNT(*) FROM climb_stats"),
        placements = queryLong("SELECT COUNT(*) FROM placements WHERE board_brand = ?", brand),
    )

    /**
     * ATTACH happens INSIDE the transaction: only there do both drivers
     * guarantee that the statements that follow run on the same connection
     * (the JDBC driver closes a file connection after every statement outside
     * a transaction; the native driver sends reads to a reader pool). SQLite
     * refuses DETACH while a transaction is open, so it follows the commit or
     * rollback; ATTACH itself is not undone by a rollback.
     */
    private fun <R> withAttached(path: String, alias: String, block: () -> R): R {
        // A run killed between commit and DETACH leaves the alias on the native writer connection.
        detachQuietly(alias)
        try {
            return handle.database.transactionWithResult {
                exec("ATTACH DATABASE ? AS $alias", path)
                block()
            }
        } finally {
            detachQuietly(alias)
        }
    }

    private fun detachQuietly(alias: String) {
        try { exec("DETACH DATABASE $alias") } catch (e: Exception) { /* not attached on this connection */ }
    }

    /** Column names of an attached table; empty when the table does not exist. */
    private fun tableColumns(alias: String, table: String): Set<String> =
        queryStrings("SELECT name FROM pragma_table_info(?, ?)", table, alias).toSet()

    private fun exec(sql: String, vararg args: Any?) {
        driver.execute(null, sql.trimIndent(), args.size) { bindAll(args) }.value
    }

    private fun query(sql: String, vararg args: Any?, row: (SqlCursor) -> Unit) {
        driver.executeQuery(null, sql.trimIndent(), { cursor ->
            while (cursor.next().value) row(cursor)
            QueryResult.Unit
        }, args.size) { bindAll(args) }.value
    }

    private fun queryLong(sql: String, vararg args: Any?): Long {
        var value = 0L
        var first = true
        query(sql, *args) { c -> if (first) { value = c.getLong(0) ?: 0L; first = false } }
        return value
    }

    private fun queryStrings(sql: String, vararg args: Any?): List<String> {
        val out = ArrayList<String>()
        query(sql, *args) { c -> c.getString(0)?.let(out::add) }
        return out
    }

    private fun app.cash.sqldelight.db.SqlPreparedStatement.bindAll(args: Array<out Any?>) {
        args.forEachIndexed { i, arg ->
            when (arg) {
                null -> bindString(i, null)
                is String -> bindString(i, arg)
                is Long -> bindLong(i, arg)
                is Int -> bindLong(i, arg.toLong())
                else -> throw IllegalArgumentException("unsupported bind type")
            }
        }
    }

    companion object {
        val AURORA_FAMILY = setOf("tension", "grasshopper", "decoy", "soill", "touchstone")
        private const val BACKFILL_BATCH = 10_000

        /** Name + DDL, derived from the list :shared self-heals on open, so the two cannot drift. */
        internal val HOT_PATH_INDEXES: List<Pair<String, String>> = BoardHotPathIndexes.DDL.map { ddl ->
            ddl.substringAfter("IF NOT EXISTS ").substringBefore(" ON ").trim() to ddl
        }
    }
}
