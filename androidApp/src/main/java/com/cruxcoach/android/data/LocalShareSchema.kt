package com.cruxcoach.android.data

/**
 * SQL for importing a MODERN CruxCoach source DB — the in-app offline share
 * serves the sender's own `cruxcoach.db`, which uses OUR schema, not the
 * Kilter-APK schema (`layouts`/`shared_syncs`/`product_sizes.is_listed`) and
 * not the pre-rename legacy schema (`aurora_*`) that [BoardDatabaseImporter]'s
 * finalization historically understood. Importing such a source through the
 * legacy branches deterministically threw at the very end of the import
 * ("no such column: p.id" / "no such table: aurora_sync_state").
 *
 * Kept free of Android imports so LocalShareModernSchemaTest can execute
 * every statement against the real SQLDelight-generated schema on the JVM —
 * column drift on either side then fails the build instead of the next
 * offline share.
 */
object LocalShareSchema {

    /** Table that exists ONLY in the modern CruxCoach schema: Kilter APK DBs
     *  carry `shared_syncs`, pre-rename CruxCoach DBs `aurora_sync_state`. */
    const val MODERN_MARKER_TABLE = "sync_states"

    /**
     * Geometry/metadata bulk copies for a source ATTACHed as `src`.
     * Explicit column lists (robust against columns added later on either
     * side) and brand-aware — the legacy import path dropped `board_brand`,
     * which would collapse every brand's geometry onto 'kilter'.
     */
    val MODERN_GEOMETRY_COPY: List<String> = listOf(
        """INSERT OR REPLACE INTO placements(board_brand, placement_id, hole_id, set_id, x, y)
           SELECT board_brand, placement_id, hole_id, set_id, x, y FROM src.placements""",
        """INSERT OR REPLACE INTO holes(board_brand, id, product_size_id, x, y, mirrored_hole_id)
           SELECT board_brand, id, product_size_id, x, y, mirrored_hole_id FROM src.holes""",
        """INSERT OR REPLACE INTO product_sizes(board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename)
           SELECT board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename FROM src.product_sizes""",
        """INSERT OR REPLACE INTO board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename)
           SELECT board_brand, id, product_size_id, layout_id, set_id, image_filename FROM src.board_images""",
        """INSERT OR REPLACE INTO leds(board_brand, hole_id, product_size_id, position)
           SELECT board_brand, hole_id, product_size_id, position FROM src.leds""",
        """INSERT OR REPLACE INTO placement_roles(board_brand, id, name, led_color, screen_color)
           SELECT board_brand, id, name, led_color, screen_color FROM src.placement_roles""",
    ).map { it.trimIndent() }

    /**
     * Zero-row probes derived mechanically from [MODERN_GEOMETRY_COPY]'s
     * SELECT halves. Run against the ATTACHed source BEFORE any write:
     * a source produced by an older app whose schema predates any table /
     * column these copies reference fails here with a clean error instead
     * of aborting the geometry transaction AFTER climbs/stats already
     * committed (a partial import). Deriving from the same constants means
     * the probe set can never drift from the copies it guards.
     */
    val MODERN_GEOMETRY_SOURCE_PROBES: List<String> = MODERN_GEOMETRY_COPY.map { stmt ->
        "SELECT * FROM (${stmt.substring(stmt.indexOf("SELECT"))}) LIMIT 0"
    }

    /**
     * Privacy scrub executed on the SENDER's serve-time snapshot (never on
     * the live DB). The board DB carries the sender's own unpublished
     * drafts (`source='local'`, identity-linked via created_by_pubkey) and
     * the per-account Kilter publish-attempt audit log — neither belongs on
     * the wire. Receiver-side import filters drafts too (defence in depth),
     * but the guarantee must hold for ANY client that fetches /board.db.
     *
     * Order matters: stats reference the draft uuids, so they go first.
     * The caller must VACUUM afterwards — DELETE alone leaves the row
     * images recoverable from free pages.
     */
    val SNAPSHOT_SCRUB: List<String> = listOf(
        """DELETE FROM climb_stats WHERE climb_uuid IN
           (SELECT uuid FROM climbs WHERE COALESCE(source, 'kilter') = 'local')""",
        """DELETE FROM climbs WHERE COALESCE(source, 'kilter') = 'local'""",
        """DELETE FROM kilter_publish_attempts""",
    ).map { it.trimIndent() }
}
