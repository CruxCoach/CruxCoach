package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.domain.board.BoardBrand
import java.io.File

/**
 * Cuts a verified local-share snapshot down to what a receiver should take from a peer.
 *
 * Two cuts, both on the receiver's own temporary copy after the hash check and before the
 * import, so the import SQL — a wire-compatibility and trust boundary of its own — stays
 * exactly as it is: it simply never sees the rows. The file is deleted after the import.
 *
 *  - **CruxCoach-native climbs** (`source` nostr or local) never cross. They reach a receiver
 *    through Nostr, with their author's signature; a peer cannot vouch for that authorship,
 *    so the importer strips it — and a stripped community climb became a catalogue row. Five
 *    such climbs made a receiver's Kilter board look "loaded" without its catalogue.
 *  - **Board families the receiver did not choose.** A share is ONE database holding every
 *    catalogue the sender has; a receiver with a Kilter wall has no use for its MoonBoard.
 *
 * Only rows that NAME another family are removed by the second cut. A row with no
 * `board_brand` (older senders wrote Kilter geometry that way) is left for the importer to
 * judge, and a legacy snapshot without the columns a cut needs is not touched by that cut.
 */
internal object LocalShareSnapshotPruner {

    /**
     * @param keep the families to take; empty means "no choice was made" and keeps them all.
     * @return the number of climbs removed.
     */
    fun prune(snapshot: File, keep: Set<BoardBrand>): Long {
        val db = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val tables = tableNames(db)
            if ("climbs" !in tables) return 0
            val climbColumns = columns(db, "climbs")
            val cutNative = "source" in climbColumns
            val cutFamilies = keep.isNotEmpty() && "board_brand" in climbColumns
            if (!cutNative && !cutFamilies) return 0
            val before = count(db, "climbs")
            db.beginTransaction()
            try {
                if (cutNative) {
                    db.execSQL("DELETE FROM climbs WHERE LOWER(TRIM(COALESCE(source,''))) IN ('nostr','local')")
                }
                if (cutFamilies) {
                    val kept = keep.joinToString(",") { "'${it.wireValue}'" }
                    tables.filter { "board_brand" in columns(db, it) }.forEach { table ->
                        db.execSQL(
                            "DELETE FROM \"$table\" WHERE board_brand IS NOT NULL " +
                                "AND LOWER(TRIM(board_brand)) NOT IN ($kept)"
                        )
                    }
                }
                // Everything that hangs off a climb — stats, beta links, the Quantum route
                // bridge and its metadata — goes with the climb, or the importer's own
                // consistency checks would find mappings for climbs that are not there.
                // Compared canonically, as the importer does: a differently cased uuid is
                // the same climb.
                tables.filter { it != "climbs" && "climb_uuid" in columns(db, it) }.forEach { table ->
                    db.execSQL(
                        "DELETE FROM \"$table\" WHERE LOWER(TRIM(climb_uuid)) NOT IN " +
                            "(SELECT LOWER(TRIM(uuid)) FROM climbs)"
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return before - count(db, "climbs")
        } finally {
            db.close()
        }
    }

    private fun tableNames(db: SQLiteDatabase): List<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun columns(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(1)) }
        }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
