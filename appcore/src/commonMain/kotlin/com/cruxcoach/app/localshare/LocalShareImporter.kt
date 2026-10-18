package com.cruxcoach.app.localshare

import com.cruxcoach.app.sync.CatalogueImporter
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.domain.board.QuantumBoardModel

/** One step of a peer import, for the progress line (Android `ImportStep`). */
class LocalShareImportStep(val code: String, val scanned: Long, val total: Long)

sealed class LocalShareImportResult {
    class Imported(val climbs: Long, val stats: Long, val placements: Long) : LocalShareImportResult()

    /** [sourceRejected]: the file itself failed validation; otherwise the database failed. Nothing was kept. */
    class Failed(val sourceRejected: Boolean, val detail: String) : LocalShareImportResult()
}

private class PeerSourceRejected(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Imports a verified, pruned snapshot of a peer's own board database — Android's
 * `BoardDatabaseImporter.importFromLocalDb` with the `UNVERIFIED_LOCAL_SHARE` policy, MODERN
 * branch (the in-app share always serves the sender's `cruxcoach.db`):
 *
 *  - additive only: rows already on this phone are never refreshed, tombstoned or re-owned;
 *  - peer provenance is stripped (`origin` forced to 'kilter', no pubkey), drafts and deleted
 *    rows never cross (see Android `LocalShareSchema.CLIMBS_PEER_SHARE_CONTRACT`);
 *  - geometry only for board families this phone has none of yet;
 *  - the Quantum controller bridge must cover the accepted Quantum catalogue exactly once;
 *  - climbs, stats, geometry and bridge commit in ONE transaction, after a zero-write preflight.
 *
 * Same single-writer rule as [CatalogueImporter]: one import at a time, nothing suspends.
 */
class LocalShareImporter(private val handle: BoardDatabaseHandle) {
    private val db = SqlOps(handle.driver)

    fun importSnapshot(
        snapshotPath: String,
        includeQuantum: Boolean,
        onProgress: (LocalShareImportStep) -> Unit = {},
    ): LocalShareImportResult = try {
        val freshClimbs = db.long("SELECT COUNT(*) FROM climbs") == 0L
        val imported = withDeferredIndexes(onRebuild = { onProgress(LocalShareImportStep(STEP_FINALIZING, 0, 0)) }) {
            withAttached(snapshotPath) {
                if (!db.hasTable(MODERN_MARKER_TABLE, "src")) throw PeerSourceRejected("Not a modern CruxCoach board database")
                preflight(includeQuantum)
                importAtomically(freshClimbs, includeQuantum, onProgress)
            }
        }
        onProgress(LocalShareImportStep(STEP_FINALIZING, 0, 0))
        CatalogueImporter(handle).backfillMoveCounts()
        if (db.long("SELECT COUNT(*) FROM sync_states WHERE table_name = 'metadata_v7'") == 0L) {
            db.exec("INSERT OR REPLACE INTO sync_states(table_name, last_synchronized_at) VALUES ('metadata_v7', 'done')")
        }
        onProgress(LocalShareImportStep(STEP_IMPORT_LAYOUT, imported.placements, imported.placements))
        // Locations are non-essential and guarded on their own (Android importLocations).
        try { withAttached(snapshotPath) { importLocations() } } catch (e: Exception) { /* keep the previous directory */ }
        withAttached(snapshotPath) {
            db.query("SELECT table_name, last_synchronized_at FROM src.$MODERN_MARKER_TABLE") { c ->
                val name = c.getString(0)
                val at = c.getString(1)
                if (name != null && at != null) syncStates += name to at
            }
        }
        if (syncStates.isNotEmpty()) {
            handle.database.transaction {
                syncStates.forEach { (name, at) ->
                    db.exec("INSERT OR REPLACE INTO sync_states(table_name, last_synchronized_at) VALUES (?, ?)", name, at)
                }
            }
        }
        LocalShareImportResult.Imported(imported.climbs, imported.stats, imported.placements)
    } catch (e: PeerSourceRejected) {
        LocalShareImportResult.Failed(sourceRejected = true, detail = e.message.orEmpty())
    } catch (e: IllegalArgumentException) {
        LocalShareImportResult.Failed(sourceRejected = true, detail = e.message.orEmpty())
    } catch (e: Exception) {
        LocalShareImportResult.Failed(sourceRejected = false, detail = e.message.orEmpty())
    } finally {
        syncStates.clear()
    }

    private val syncStates = ArrayList<Pair<String, String>>()

    private class Counts(val climbs: Long, val stats: Long, val placements: Long)

    /**
     * Zero-write validation (Android preflightModernSource): every copy statement is compiled
     * with EXPLAIN against the attached source, so a sender whose schema predates a referenced
     * table or column fails here, before the first row is written.
     */
    private fun preflight(includeQuantum: Boolean) {
        try {
            val statements = copyStatements(includeQuantum, protectExistingGeometry = false)
            validateQuantumBridge(includeQuantum)
            for (statement in statements) db.query("EXPLAIN $statement") { }
        } catch (e: PeerSourceRejected) {
            throw e
        } catch (e: Exception) {
            throw PeerSourceRejected("Shared database is structurally incomplete (${e.message})", e)
        }
    }

    private fun importAtomically(
        freshClimbs: Boolean,
        includeQuantum: Boolean,
        onProgress: (LocalShareImportStep) -> Unit,
    ): Counts {
        val statements = copyStatements(includeQuantum, protectExistingGeometry = true)
        val climbColumns = db.columns("climbs", "src")
        val quantumRows = validateQuantumBridge(includeQuantum)
        validateQuantumTargetBridge(quantumRows)
        // Every geometry family this phone already has, captured before ANY peer write: a peer
        // may not add ids to an installed board.
        db.exec("DROP TABLE IF EXISTS temp.peer_existing_geometry_brands")
        db.exec("CREATE TEMP TABLE peer_existing_geometry_brands(board_brand TEXT PRIMARY KEY)")
        for (table in GEOMETRY_TABLES) {
            db.exec("INSERT OR IGNORE INTO temp.peer_existing_geometry_brands SELECT DISTINCT LOWER(TRIM(board_brand)) FROM main.$table")
        }
        try {
            onProgress(LocalShareImportStep(STEP_IMPORT_CLIMBS, 0, 0))
            val climbs = importClimbs(climbColumns, freshClimbs) { scanned, total ->
                onProgress(LocalShareImportStep(STEP_IMPORT_CLIMBS, scanned, total))
            }
            onProgress(LocalShareImportStep(STEP_IMPORT_STATS, 0, 0))
            val stats = importStats(climbColumns) { scanned, total ->
                onProgress(LocalShareImportStep(STEP_IMPORT_STATS, scanned, total))
            }
            onProgress(LocalShareImportStep(STEP_IMPORT_LAYOUT, 0, 0))
            statements.forEach { db.exec(it) }
            val placements = if (db.hasTable("placements", "src")) {
                val filter = if (!includeQuantum && "board_brand" in db.columns("placements", "src")) {
                    " WHERE LOWER(TRIM(COALESCE(board_brand,'kilter')))!='quantum'"
                } else ""
                db.long("SELECT COUNT(*) FROM src.placements$filter")
            } else 0L
            return Counts(climbs, stats, placements)
        } finally {
            db.exec("DROP TABLE IF EXISTS temp.peer_existing_geometry_brands")
        }
    }

    /** Additive: listed, non-draft, non-deleted rows, with peer provenance stripped. */
    private fun importClimbs(srcCols: Set<String>, freshInstall: Boolean, onProgress: (Long, Long) -> Unit): Long {
        val moveCountExpr = if ("move_count" in srcCols) "COALESCE(move_count, 0)" else "0"
        val methodExpr = if ("method" in srcCols) "method" else "NULL"
        val brandExpr = if ("board_brand" in srcCols) "COALESCE(board_brand, 'kilter')" else "'kilter'"
        val deletedGuard = if ("is_deleted" in srcCols) " AND COALESCE(is_deleted, 0) = 0" else ""
        val draftFilter = (if ("source" in srcCols) "AND LOWER(COALESCE(source, 'kilter')) != 'local'" else "") + deletedGuard
        val total = db.long("SELECT COUNT(*) FROM src.climbs WHERE is_listed = 1 $draftFilter")
        val countBefore = if (freshInstall) 0L else db.long("SELECT COUNT(*) FROM climbs")
        onProgress(0, total)
        db.exec("DROP TABLE IF EXISTS temp.chunk_norm")
        db.exec(
            """
            CREATE TEMP TABLE chunk_norm (
                uuid TEXT PRIMARY KEY,
                layout_id INTEGER, setter_username TEXT, name TEXT, frames TEXT,
                frames_count INTEGER, is_listed INTEGER,
                edge_left INTEGER, edge_right INTEGER,
                edge_bottom INTEGER, edge_top INTEGER,
                created_at INTEGER, description TEXT,
                is_nomatch INTEGER, frames_pace INTEGER, hsm INTEGER,
                move_count INTEGER, origin TEXT, created_by_pubkey TEXT,
                board_brand TEXT, method TEXT
            ) WITHOUT ROWID
            """,
        )
        try {
            val minRowid = db.long("SELECT MIN(rowid) FROM src.climbs")
            val maxRowid = db.long("SELECT MAX(rowid) FROM src.climbs")
            var batchStart = minRowid
            var scanned = 0L
            while (batchStart <= maxRowid) {
                val batchEnd = batchStart + BULK_BATCH_SIZE - 1
                db.exec("DELETE FROM chunk_norm")
                // Neither a schema marker nor a peer's origin/pubkey authenticates authorship:
                // origin is 'kilter' and the pubkey NULL whatever the peer claims.
                db.exec(
                    """
                    INSERT OR IGNORE INTO chunk_norm
                    SELECT LOWER(TRIM(uuid)), layout_id, setter_username, name, frames,
                           frames_count, is_listed, edge_left, edge_right,
                           edge_bottom, edge_top, created_at,
                           COALESCE(description, ''), COALESCE(is_nomatch, 0),
                           COALESCE(frames_pace, 0), COALESCE(hsm, 0),
                           $moveCountExpr, 'kilter', NULL, $brandExpr, $methodExpr
                    FROM src.climbs
                    WHERE rowid BETWEEN $batchStart AND $batchEnd
                      $draftFilter
                    """,
                )
                db.exec(
                    """
                    INSERT OR IGNORE INTO climbs(
                        uuid, layout_id, setter_username, name, frames,
                        frames_count, is_listed, edge_left, edge_right,
                        edge_bottom, edge_top, created_at,
                        description, is_nomatch, frames_pace, hsm, move_count,
                        origin, created_by_pubkey, board_brand, method)
                    SELECT uuid, layout_id, setter_username, name, frames,
                           frames_count, is_listed, edge_left, edge_right,
                           edge_bottom, edge_top, created_at,
                           description, is_nomatch, frames_pace, hsm, move_count,
                           origin, created_by_pubkey, board_brand, method
                    FROM chunk_norm
                    WHERE is_listed = 1
                    """,
                )
                scanned = (scanned + (batchEnd - batchStart + 1)).coerceAtMost(total)
                onProgress(scanned, total)
                batchStart = batchEnd + 1
            }
        } finally {
            db.exec("DROP TABLE IF EXISTS temp.chunk_norm")
        }
        return db.long("SELECT COUNT(*) FROM climbs") - countBefore
    }

    /**
     * Only stats whose peer climb passes the same row policy and whose climb on this phone has
     * the same brand; an existing (climb, angle) aggregate is never replaced.
     */
    private fun importStats(peerClimbColumns: Set<String>, onProgress: (Long, Long) -> Unit): Long {
        val sourceBrand = if ("board_brand" in peerClimbColumns) "LOWER(COALESCE(sc.board_brand,'kilter'))" else "'kilter'"
        val eligible = buildString {
            append("sc.is_listed=1")
            if ("source" in peerClimbColumns) append(" AND LOWER(COALESCE(sc.source,'kilter'))!='local'")
            if ("is_deleted" in peerClimbColumns) append(" AND COALESCE(sc.is_deleted,0)=0")
        }
        db.exec("DROP TABLE IF EXISTS temp.$PEER_STAT_CLIMBS")
        db.exec("CREATE TEMP TABLE $PEER_STAT_CLIMBS(uuid TEXT NOT NULL, brand TEXT NOT NULL, PRIMARY KEY(uuid, brand)) WITHOUT ROWID")
        try {
            db.exec(
                "INSERT OR IGNORE INTO temp.$PEER_STAT_CLIMBS(uuid, brand) " +
                    "SELECT LOWER(TRIM(sc.uuid)), $sourceBrand FROM src.climbs sc WHERE sc.uuid IS NOT NULL AND $eligible",
            )
            val peerJoin = """
                JOIN temp.$PEER_STAT_CLIMBS pc ON pc.uuid=LOWER(TRIM(s.climb_uuid))
                JOIN main.climbs tc ON tc.uuid=pc.uuid AND LOWER(tc.board_brand)=pc.brand
            """.trimIndent()
            val total = db.long("SELECT COUNT(*) FROM src.climb_stats s $peerJoin")
            val countBefore = db.long("SELECT COUNT(*) FROM climb_stats")
            onProgress(0, total)
            val minRowid = db.long("SELECT MIN(rowid) FROM src.climb_stats")
            val maxRowid = db.long("SELECT MAX(rowid) FROM src.climb_stats")
            var batchStart = minRowid
            var scanned = 0L
            while (batchStart <= maxRowid) {
                val batchEnd = batchStart + BULK_BATCH_SIZE - 1
                db.exec(
                    """
                    INSERT OR IGNORE INTO climb_stats(
                        climb_uuid, angle, display_difficulty, difficulty_average,
                        quality_average, ascensionist_count, benchmark_difficulty,
                        fa_username, fa_at, layout_id)
                    SELECT LOWER(TRIM(s.climb_uuid)), s.angle, s.display_difficulty, s.difficulty_average,
                           s.quality_average, s.ascensionist_count, s.benchmark_difficulty,
                           s.fa_username, s.fa_at,
                           COALESCE((SELECT c.layout_id FROM climbs c WHERE c.uuid = LOWER(TRIM(s.climb_uuid))), 0)
                    FROM src.climb_stats s
                    $peerJoin
                    WHERE s.rowid BETWEEN $batchStart AND $batchEnd
                    """,
                )
                scanned = (scanned + (batchEnd - batchStart + 1)).coerceAtMost(total)
                onProgress(scanned, total)
                batchStart = batchEnd + 1
            }
            return db.long("SELECT COUNT(*) FROM climb_stats") - countBefore
        } finally {
            db.exec("DROP TABLE IF EXISTS temp.$PEER_STAT_CLIMBS")
        }
    }

    /** Android modernCopyStatements: adapts any historical modern schema to the current target. */
    private fun copyStatements(includeQuantum: Boolean, protectExistingGeometry: Boolean): List<String> {
        val columnCache = HashMap<String, Set<String>>()
        fun columns(table: String) = columnCache.getOrPut(table) { db.columns(table, "src") }
        fun has(table: String) = db.hasTable(table, "src")
        fun requireColumns(table: String, required: Set<String>) {
            if (!has(table)) return
            val missing = required - columns(table)
            require(missing.isEmpty()) { "Shared database table $table is missing required columns: ${missing.sorted()}" }
        }
        fun brand(table: String) = if ("board_brand" in columns(table)) "board_brand" else "'kilter'"
        fun geometryFilter(table: String): String {
            val conditions = buildList {
                if (!includeQuantum && "board_brand" in columns(table)) add("LOWER(TRIM(COALESCE(board_brand,'kilter')))!='quantum'")
                if (protectExistingGeometry) {
                    add("LOWER(TRIM(COALESCE(${brand(table)},'kilter'))) NOT IN (SELECT board_brand FROM temp.peer_existing_geometry_brands)")
                }
            }
            return if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        }

        requireColumns("placements", setOf("placement_id", "hole_id", "set_id", "x", "y"))
        requireColumns("holes", setOf("id", "product_size_id", "x", "y", "mirrored_hole_id"))
        requireColumns("product_sizes", setOf("id", "product_id", "name", "edge_left", "edge_right", "edge_bottom", "edge_top", "image_filename"))
        requireColumns("board_images", setOf("id", "product_size_id", "layout_id", "set_id", "image_filename"))
        requireColumns("leds", setOf("hole_id", "product_size_id", "position"))
        requireColumns("placement_roles", setOf("id", "name", "led_color", "screen_color"))
        requireColumns(
            "climb_beta_links",
            setOf("board_brand", "climb_uuid", "url", "provider", "media_id", "foreign_username", "angle", "thumbnail", "created_at"),
        )
        requireColumns("moonboard_climb_aliases", setOf("alias_uuid", "canonical_uuid", "match_kind"))

        return buildList {
            if (has("placements")) add(
                "INSERT OR REPLACE INTO placements(board_brand,placement_id,hole_id,set_id,x,y) " +
                    "SELECT ${brand("placements")},placement_id,hole_id,set_id,x,y FROM src.placements" + geometryFilter("placements"),
            )
            if (has("holes")) add(
                "INSERT OR REPLACE INTO holes(board_brand,id,product_size_id,x,y,mirrored_hole_id) " +
                    "SELECT ${brand("holes")},id,product_size_id,x,y,mirrored_hole_id FROM src.holes" + geometryFilter("holes"),
            )
            if (has("product_sizes")) add(
                "INSERT OR REPLACE INTO product_sizes(board_brand,id,product_id,name,edge_left,edge_right,edge_bottom,edge_top,image_filename) " +
                    "SELECT ${brand("product_sizes")},id,product_id,name,edge_left,edge_right,edge_bottom,edge_top,image_filename FROM src.product_sizes" +
                    geometryFilter("product_sizes"),
            )
            if (has("board_images")) add(
                "INSERT OR REPLACE INTO board_images(board_brand,id,product_size_id,layout_id,set_id,image_filename) " +
                    "SELECT ${brand("board_images")},id,product_size_id,layout_id,set_id,image_filename FROM src.board_images" +
                    geometryFilter("board_images"),
            )
            if (has("leds")) add(
                "INSERT OR REPLACE INTO leds(board_brand,hole_id,product_size_id,position) " +
                    "SELECT ${brand("leds")},hole_id,product_size_id,position FROM src.leds" + geometryFilter("leds"),
            )
            if (has("placement_roles")) add(
                "INSERT OR REPLACE INTO placement_roles(board_brand,id,name,led_color,screen_color) " +
                    "SELECT ${brand("placement_roles")},id,name,led_color,screen_color FROM src.placement_roles" + geometryFilter("placement_roles"),
            )
            if (has("climb_beta_links")) add(
                """INSERT OR IGNORE INTO climb_beta_links(
                       board_brand,climb_uuid,url,provider,media_id,
                       foreign_username,angle,thumbnail,created_at)
                   SELECT LOWER(TRIM(b.board_brand)),LOWER(TRIM(b.climb_uuid)),TRIM(b.url),
                          LOWER(TRIM(b.provider)),NULLIF(TRIM(b.media_id),''),
                          NULLIF(TRIM(b.foreign_username),''),b.angle,
                          NULLIF(TRIM(b.thumbnail),''),NULLIF(TRIM(b.created_at),'')
                   FROM src.climb_beta_links b
                   JOIN main.climbs c
                     ON c.uuid=LOWER(TRIM(b.climb_uuid))
                    AND LOWER(c.board_brand)=LOWER(TRIM(b.board_brand))
                   WHERE TRIM(b.board_brand)!='' AND TRIM(b.climb_uuid)!=''
                     AND TRIM(b.provider)!=''
                     AND LOWER(TRIM(b.url)) LIKE 'https://%'
                     AND TRIM(b.url) NOT LIKE '% %'
                     AND (b.thumbnail IS NULL OR
                          (LOWER(TRIM(b.thumbnail)) LIKE 'https://%'
                           AND TRIM(b.thumbnail) NOT LIKE '% %'))
                     AND (b.angle IS NULL OR b.angle BETWEEN 0 AND 90)
                     ${if (!includeQuantum) "AND LOWER(TRIM(b.board_brand))!='quantum'" else ""}""".trimIndent(),
            )
            // Without the brand discriminator there is no way to prove both ends are MoonBoard identities.
            if (has("moonboard_climb_aliases") && "board_brand" in columns("climbs")) add(
                """INSERT OR IGNORE INTO moonboard_climb_aliases(alias_uuid,canonical_uuid,match_kind)
                   SELECT LOWER(TRIM(a.alias_uuid)),LOWER(TRIM(a.canonical_uuid)),a.match_kind
                   FROM src.moonboard_climb_aliases a
                   JOIN src.climbs alias_climb ON alias_climb.uuid=LOWER(TRIM(a.alias_uuid))
                   JOIN src.climbs canonical_climb ON canonical_climb.uuid=LOWER(TRIM(a.canonical_uuid))
                   JOIN main.climbs imported_canonical
                     ON imported_canonical.uuid=LOWER(TRIM(a.canonical_uuid))
                    AND LOWER(imported_canonical.board_brand)='moonboard'
                   WHERE TRIM(a.alias_uuid)!='' AND TRIM(a.canonical_uuid)!=''
                     AND LOWER(TRIM(a.alias_uuid))!=LOWER(TRIM(a.canonical_uuid))
                     AND a.match_kind='legacy-exact-duplicate'
                     AND LOWER(alias_climb.board_brand)='moonboard'
                     AND LOWER(canonical_climb.board_brand)='moonboard'
                     AND alias_climb.layout_id=canonical_climb.layout_id
                     AND alias_climb.frames=canonical_climb.frames
                     AND NOT EXISTS (
                       SELECT 1 FROM src.moonboard_climb_aliases chained
                       WHERE chained.alias_uuid=LOWER(TRIM(a.canonical_uuid))
                     )""".trimIndent(),
            )
            if (includeQuantum) addAll(quantumStatements(columns("climbs"), has("quantum_route_refs"), has("quantum_route_metadata")))
        }
    }

    private fun quantumStatements(climbColumns: Set<String>, hasRefs: Boolean, hasMetadata: Boolean): List<String> = buildList {
        require(hasRefs == hasMetadata) { "Quantum share bridge is incomplete" }
        if (hasRefs && "board_brand" in climbColumns) {
            val sourceGuard = buildString {
                append("LOWER(COALESCE(sc.board_brand,'kilter'))='quantum' AND sc.is_listed=1")
                // Only vendor-catalogue Quantum rows carry the controller bridge.
                if ("source" in climbColumns) append(" AND LOWER(COALESCE(sc.source,'kilter'))='quantum'")
                if ("is_deleted" in climbColumns) append(" AND COALESCE(sc.is_deleted,0)=0")
            }
            add(
                """INSERT INTO quantum_route_refs(app_uuid,route_uuid,model)
                   SELECT LOWER(TRIM(r.app_uuid)),LOWER(TRIM(r.route_uuid)),LOWER(TRIM(r.model))
                   FROM src.quantum_route_refs r
                   JOIN main.climbs c ON c.uuid=LOWER(TRIM(r.app_uuid))
                   WHERE LOWER(TRIM(r.app_uuid)) IN (SELECT LOWER(TRIM(sc.uuid)) FROM src.climbs sc WHERE $sourceGuard)
                     AND LOWER(c.board_brand)='quantum'
                     AND LOWER(TRIM(r.app_uuid)) NOT IN (SELECT LOWER(existing.app_uuid) FROM main.quantum_route_refs existing)""".trimIndent(),
            )
            add(
                """INSERT INTO quantum_route_metadata(
                       app_uuid,source_grade,campusing,edge,kickplate,matching,standard,tags)
                   SELECT LOWER(TRIM(m.app_uuid)),COALESCE(m.source_grade,''),
                          COALESCE(m.campusing,0),COALESCE(m.edge,0),
                          COALESCE(m.kickplate,0),COALESCE(m.matching,0),
                          COALESCE(m.standard,0),COALESCE(m.tags,'')
                   FROM src.quantum_route_metadata m
                   JOIN main.climbs c ON c.uuid=LOWER(TRIM(m.app_uuid))
                   WHERE LOWER(TRIM(m.app_uuid)) IN (SELECT LOWER(TRIM(sc.uuid)) FROM src.climbs sc WHERE $sourceGuard)
                     AND LOWER(c.board_brand)='quantum'
                     AND LOWER(TRIM(m.app_uuid)) NOT IN (SELECT LOWER(existing.app_uuid) FROM main.quantum_route_metadata existing)""".trimIndent(),
            )
            // The fully validated official bridge is the one provenance kept, so this phone can
            // pass the same operational route bridge on to a third receiver.
            add(
                """UPDATE main.climbs SET source='quantum'
                   WHERE board_brand='quantum' AND source='kilter' AND uuid IN (
                       SELECT LOWER(TRIM(sc.uuid)) FROM src.climbs sc WHERE $sourceGuard
                   )""".trimIndent(),
            )
        }
        if ("board_brand" in climbColumns && "source" in climbColumns) {
            add(
                """UPDATE main.climbs SET source='nostr'
                   WHERE board_brand='quantum' AND source='kilter' AND uuid IN (
                       SELECT LOWER(TRIM(sc.uuid)) FROM src.climbs sc
                       WHERE LOWER(COALESCE(sc.board_brand,'kilter'))='quantum'
                         AND sc.is_listed=1
                         AND LOWER(COALESCE(sc.source,''))='nostr'
                         ${if ("is_deleted" in climbColumns) "AND COALESCE(sc.is_deleted,0)=0" else ""}
                   )""".trimIndent(),
            )
        }
    }

    private class QuantumBridgeRow(val appUuid: String, val routeUuid: String, val model: String)

    /** Android validateQuantumBridge: source-side shape and bridge completeness. */
    private fun validateQuantumBridge(includeQuantum: Boolean): List<QuantumBridgeRow> {
        fun requireColumns(table: String, required: Set<String>) {
            val missing = required - db.columns(table, "src")
            require(missing.isEmpty()) { "Shared database table $table is missing required columns: ${missing.sorted()}" }
        }
        requireColumns(
            "climbs",
            setOf(
                "uuid", "layout_id", "setter_username", "name", "frames", "frames_count", "is_listed", "edge_left", "edge_right",
                "edge_bottom", "edge_top", "created_at", "description", "is_nomatch", "frames_pace", "hsm",
            ),
        )
        requireColumns(
            "climb_stats",
            setOf(
                "climb_uuid", "angle", "display_difficulty", "difficulty_average", "quality_average", "ascensionist_count",
                "benchmark_difficulty", "fa_username", "fa_at",
            ),
        )
        requireColumns(MODERN_MARKER_TABLE, setOf("table_name", "last_synchronized_at"))
        val climbColumns = db.columns("climbs", "src")
        val deletedFilter = if ("is_deleted" in climbColumns) " AND COALESCE(is_deleted,0)=0" else ""
        fun quantumLayouts(sourceCondition: String): Map<String, Long> {
            val values = ArrayList<Pair<String, Long>>()
            db.query(
                "SELECT LOWER(TRIM(uuid)),layout_id FROM src.climbs " +
                    "WHERE LOWER(COALESCE(board_brand,'kilter'))='quantum' AND is_listed=1$sourceCondition$deletedFilter",
            ) { c -> values += (c.getString(0) ?: "") to (c.getLong(1) ?: 0L) }
            require(values.all { isCanonicalUuid(it.first) }) { "Quantum app UUID is invalid" }
            require(values.map { it.first }.distinct().size == values.size) { "Quantum catalogue has duplicate normalized app UUIDs" }
            return values.toMap()
        }
        val acceptedQuantum = if ("board_brand" !in climbColumns) emptyMap() else {
            quantumLayouts(if ("source" in climbColumns) " AND LOWER(COALESCE(source,'kilter'))!='local'" else "")
        }
        if ("source" in climbColumns && acceptedQuantum.isNotEmpty()) {
            val invalidSource = db.long(
                "SELECT COUNT(*) FROM src.climbs WHERE LOWER(COALESCE(board_brand,'kilter'))='quantum' " +
                    "AND is_listed=1 AND LOWER(COALESCE(source,'kilter'))!='local' " +
                    "AND LOWER(COALESCE(source,'')) NOT IN ('quantum','nostr')$deletedFilter",
            )
            require(invalidSource == 0L) { "Quantum catalogue row has invalid provenance" }
        }
        if (!includeQuantum) {
            require(acceptedQuantum.isEmpty()) { "Legacy v1 share contains Quantum catalogue rows" }
            return emptyList()
        }
        val acceptedLayouts = when {
            "board_brand" !in climbColumns -> emptyMap()
            "source" !in climbColumns -> acceptedQuantum
            else -> quantumLayouts(" AND LOWER(COALESCE(source,'kilter'))='quantum'")
        }
        val accepted = acceptedLayouts.keys
        val hasRefs = db.hasTable("quantum_route_refs", "src")
        val hasMetadata = db.hasTable("quantum_route_metadata", "src")
        require(hasRefs == hasMetadata) { "Quantum share bridge is incomplete" }
        if (accepted.isEmpty()) return emptyList()
        require(hasRefs) { "Quantum catalogue has no controller UUID bridge" }
        requireColumns("quantum_route_refs", setOf("app_uuid", "route_uuid", "model"))
        requireColumns(
            "quantum_route_metadata",
            setOf("app_uuid", "source_grade", "campusing", "edge", "kickplate", "matching", "standard", "tags"),
        )
        val rows = ArrayList<QuantumBridgeRow>()
        val raw = ArrayList<Triple<String, String, String>>()
        db.query("SELECT app_uuid,route_uuid,model FROM src.quantum_route_refs") { c ->
            raw += Triple(
                c.getString(0)?.trim()?.lowercase().orEmpty(),
                c.getString(1)?.trim()?.lowercase().orEmpty(),
                c.getString(2)?.trim()?.lowercase().orEmpty(),
            )
        }
        for ((appUuid, routeUuid, model) in raw) {
            if (appUuid !in accepted) continue
            require(isCanonicalUuid(appUuid) && isCanonicalUuid(routeUuid)) { "Quantum UUID bridge contains an invalid UUID" }
            require(model in QUANTUM_MODELS) { "Quantum UUID bridge has an unknown model" }
            require(QuantumBoardModel.fromWire(model)?.layoutId == acceptedLayouts[appUuid]) {
                "Quantum UUID bridge model does not match the climb layout"
            }
            rows += QuantumBridgeRow(appUuid, routeUuid, model)
        }
        require(rows.map { it.appUuid }.toSet() == accepted && rows.size == accepted.size) {
            "Quantum UUID bridge does not cover the accepted catalogue exactly once"
        }
        require(rows.map { it.routeUuid to it.model }.distinct().size == rows.size) {
            "Quantum UUID bridge has duplicate normalized route/model pairs"
        }
        val metadataApps = db.strings("SELECT app_uuid FROM src.quantum_route_metadata")
            .map { it.trim().lowercase() }.filter { it in accepted }
        require(metadataApps.toSet() == accepted && metadataApps.size == accepted.size) {
            "Quantum metadata does not cover the accepted catalogue exactly once"
        }
        return rows
    }

    /** Existing mappings on this phone are authoritative; a peer may not remap or claim them. */
    private fun validateQuantumTargetBridge(rows: List<QuantumBridgeRow>) {
        if (rows.isEmpty()) return
        val byApp = HashMap<String, Pair<String, String>>()
        val byExternal = HashMap<Pair<String, String>, String>()
        db.query("SELECT app_uuid,route_uuid,model FROM main.quantum_route_refs") { c ->
            val app = c.getString(0).orEmpty().trim().lowercase()
            val external = c.getString(1).orEmpty().trim().lowercase() to c.getString(2).orEmpty().trim().lowercase()
            require(byApp.put(app, external) == null) { "Receiver has duplicate normalized Quantum app UUIDs" }
            require(byExternal.put(external, app) == null) { "Receiver has duplicate normalized Quantum route/model pairs" }
        }
        val targetBrands = HashMap<String, String>()
        rows.map { it.appUuid }.chunked(400).forEach { uuids ->
            val placeholders = uuids.joinToString(",") { "?" }
            db.query("SELECT uuid,board_brand FROM main.climbs WHERE uuid IN ($placeholders)", *uuids.toTypedArray()) { c ->
                targetBrands[c.getString(0).orEmpty().lowercase()] = c.getString(1).orEmpty().lowercase()
            }
        }
        rows.forEach { row ->
            val external = row.routeUuid to row.model
            val targetBrand = targetBrands[row.appUuid]
            require(targetBrand == null || (targetBrand == "quantum" && byApp[row.appUuid] == external)) {
                "Peer attempted to claim an existing climb UUID without its authoritative Quantum mapping"
            }
            require(byApp[row.appUuid]?.let { it == external } != false) { "Peer attempted to replace an authoritative Quantum app mapping" }
            require(byExternal[external]?.let { it == row.appUuid } != false) { "Peer attempted to replace an authoritative Quantum route mapping" }
        }
    }

    /** The sender's gym directory replaces ours only when it is not empty (Android importLocations). */
    private fun importLocations() {
        if (!db.hasTable("kilter_board_location", "src")) return
        if (db.long("SELECT COUNT(*) FROM src.kilter_board_location") > 0L) {
            val cols = db.columns("kilter_board_location", "src")
            val brandExpr = if ("board_brand" in cols) "COALESCE(board_brand, 'kilter')" else "'kilter'"
            val wellpassExpr = if ("wellpass" in cols) "wellpass" else "NULL"
            db.exec("DELETE FROM kilter_board_location")
            db.exec(
                """
                INSERT INTO kilter_board_location(
                    gym_uuid, name, lat, lng, address, city, country_code,
                    phone, email, url, instagram,
                    layout_name, layout_id, size_label, product_size_id,
                    access_type, adjustability, fixed_angle, frame_maker,
                    board_brand, wellpass)
                SELECT gym_uuid, name, lat, lng, address, city, country_code,
                       phone, email, url, instagram,
                       layout_name, layout_id, size_label, product_size_id,
                       COALESCE(access_type, 'UNKNOWN'), COALESCE(adjustability, 'UNKNOWN'),
                       fixed_angle, frame_maker, $brandExpr, $wellpassExpr
                FROM src.kilter_board_location
                """,
            )
        }
        if (db.hasTable("kilter_board_wall", "src") && db.long("SELECT COUNT(*) FROM src.kilter_board_wall") > 0L) {
            db.exec("DELETE FROM kilter_board_wall")
            db.exec(
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
                FROM src.kilter_board_wall
                """,
            )
        }
    }

    /** Only an empty catalogue may drop the hot-path indexes; they are restored either way. */
    private fun <R> withDeferredIndexes(onRebuild: () -> Unit, block: () -> R): R {
        val hasRows = db.long("SELECT EXISTS(SELECT 1 FROM climbs) OR EXISTS(SELECT 1 FROM climb_stats)") != 0L
        if (hasRows) restoreMissingIndexes()
        else handle.database.transaction { CatalogueImporter.HOT_PATH_INDEXES.forEach { (name, _) -> db.exec("DROP INDEX IF EXISTS $name") } }
        try {
            return block()
        } finally {
            try { onRebuild() } finally { if (!hasRows) restoreMissingIndexes() }
        }
    }

    private fun restoreMissingIndexes() {
        val existing = db.strings("SELECT name FROM sqlite_master WHERE type = 'index'").toSet()
        val missing = CatalogueImporter.HOT_PATH_INDEXES.filterNot { it.first in existing }
        if (missing.isNotEmpty()) handle.database.transaction { missing.forEach { (_, ddl) -> db.exec(ddl) } }
    }

    /** ATTACH inside the transaction pins one connection (see [CatalogueImporter]); DETACH after it. */
    private fun <R> withAttached(path: String, block: () -> R): R {
        detachQuietly()
        try {
            return handle.database.transactionWithResult {
                db.exec("ATTACH DATABASE ? AS src", path)
                block()
            }
        } finally {
            detachQuietly()
        }
    }

    private fun detachQuietly() {
        try { db.exec("DETACH DATABASE src") } catch (e: Exception) { /* not attached on this connection */ }
    }

    private fun isCanonicalUuid(value: String): Boolean = CANONICAL_UUID.matches(value)

    companion object {
        const val MODERN_MARKER_TABLE = "sync_states"
        const val STEP_IMPORT_CLIMBS = "importClimbs"
        const val STEP_IMPORT_STATS = "importStats"
        const val STEP_IMPORT_LAYOUT = "importLayout"
        const val STEP_FINALIZING = "finalizing"
        private const val BULK_BATCH_SIZE = 10_000L
        private const val PEER_STAT_CLIMBS = "peer_stat_climbs"
        private val GEOMETRY_TABLES = listOf("placements", "holes", "product_sizes", "board_images", "leds", "placement_roles")
        private val QUANTUM_MODELS = setOf("xl", "l", "m", "s", "belay")
        private val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
