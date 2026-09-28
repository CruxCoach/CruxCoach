package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Hold geometry shipped in the APK: `assets/board_geometry/<brand>.sqlite3`,
 * built by `scripts/build_board_geometry_assets.py` from the same signed
 * catalogue manifests the app downloads, with the importer's own SQL.
 *
 * Community climbs arrive live over Nostr for every board and carry only
 * their frames (placement ids and roles). Drawing them, fitting them to a
 * board size and lighting them on the wall all need the board's geometry,
 * which used to arrive only with the full catalogue: 0.2.2 always downloaded
 * every board, 0.2.3 lets the user skip or delete boards. A board without
 * geometry is seeded from here; its next catalogue import replaces the rows.
 */
object BundledBoardGeometry {
    const val ASSET_DIR = "board_geometry"

    fun assetFileName(brand: String): String = "$brand.sqlite3"

    /**
     * Seed statements for a bundle ATTACHed as `geo`, each bound to one brand.
     * INSERT OR IGNORE: a seed never overwrites a row a catalogue import wrote.
     */
    val SEED_STATEMENTS: List<String> = listOf(
        """INSERT OR IGNORE INTO main.placements(board_brand, placement_id, hole_id, set_id, x, y)
           SELECT board_brand, placement_id, hole_id, set_id, x, y FROM geo.placements WHERE board_brand = ?""",
        """INSERT OR IGNORE INTO main.product_sizes(board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename)
           SELECT board_brand, id, product_id, name, edge_left, edge_right, edge_bottom, edge_top, image_filename FROM geo.product_sizes WHERE board_brand = ?""",
        """INSERT OR IGNORE INTO main.board_images(board_brand, id, product_size_id, layout_id, set_id, image_filename)
           SELECT board_brand, id, product_size_id, layout_id, set_id, image_filename FROM geo.board_images WHERE board_brand = ?""",
        """INSERT OR IGNORE INTO main.leds(board_brand, hole_id, product_size_id, position)
           SELECT board_brand, hole_id, product_size_id, position FROM geo.leds WHERE board_brand = ?""",
        """INSERT OR IGNORE INTO main.placement_roles(board_brand, id, name, led_color, screen_color)
           SELECT board_brand, id, name, led_color, screen_color FROM geo.placement_roles WHERE board_brand = ?""",
    ).map { it.trimIndent() }

    /**
     * Seeds [brand] from the bundle at [source] into [db] when the board has
     * no placements yet, in one transaction.
     *
     * @return placements seeded; 0 when the board already had geometry.
     */
    fun seed(db: SQLiteDatabase, source: File, brand: String): Int {
        db.execSQL("ATTACH DATABASE ? AS geo", arrayOf(source.absolutePath))
        try {
            db.beginTransaction()
            try {
                if (placementCount(db, brand) > 0) return 0
                for (sql in SEED_STATEMENTS) db.execSQL(sql, arrayOf(brand))
                val seeded = placementCount(db, brand)
                db.setTransactionSuccessful()
                return seeded
            } finally {
                db.endTransaction()
            }
        } finally {
            runCatching { db.execSQL("DETACH DATABASE geo") }
        }
    }

    private fun placementCount(db: SQLiteDatabase, brand: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM main.placements WHERE board_brand = ?", arrayOf(brand)).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
}
