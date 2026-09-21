package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.domain.board.BoardBrand
import java.io.File

/**
 * Cuts a verified local-share snapshot down to the board families the receiver chose.
 *
 * A share is ONE database holding every catalogue the sender has. A receiver with a Kilter
 * wall has no use for the sender's 300,000 MoonBoard problems, and importing them anyway cost
 * the storage and the minutes the selection was meant to save.
 *
 * It runs on the receiver's own temporary copy, after the hash check and before the import, so
 * the import SQL — a wire-compatibility and trust boundary of its own — stays exactly as it
 * is: it simply never sees the rows. The file is deleted after the import either way.
 *
 * Only rows that NAME another family are removed. A row with no `board_brand` (older senders
 * wrote Kilter geometry that way) is left for the importer to judge, and a legacy snapshot
 * whose `climbs` table has no brand column is a single-family database and is not touched.
 */
internal object LocalShareSnapshotPruner {

    /** @return the number of climbs removed; 0 when there was nothing to cut. */
    fun prune(snapshot: File, keep: Set<BoardBrand>): Long {
        if (keep.isEmpty()) return 0
        val db = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            val tables = tableNames(db)
            if ("climbs" !in tables || "board_brand" !in columns(db, "climbs")) return 0
            val kept = keep.joinToString(",") { "'${it.wireValue}'" }
            val branded = tables.filter { "board_brand" in columns(db, it) }
            val before = count(db, "climbs")
            db.beginTransaction()
            try {
                branded.forEach { table ->
                    db.execSQL(
                        "DELETE FROM \"$table\" WHERE board_brand IS NOT NULL " +
                            "AND LOWER(TRIM(board_brand)) NOT IN ($kept)"
                    )
                }
                // Everything that hangs off a climb — stats, beta links, the Quantum route
                // bridge and its metadata — goes with the climb, or the importer's own
                // consistency checks would find mappings for climbs that are not there.
                tables.filter { it != "climbs" && "climb_uuid" in columns(db, it) }.forEach { table ->
                    db.execSQL("DELETE FROM \"$table\" WHERE climb_uuid NOT IN (SELECT uuid FROM climbs)")
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
