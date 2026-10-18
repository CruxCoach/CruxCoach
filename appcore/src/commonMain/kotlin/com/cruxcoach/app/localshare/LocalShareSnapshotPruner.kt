package com.cruxcoach.app.localshare

import app.cash.sqldelight.db.SqlDriver

/**
 * Android's `LocalShareSnapshotPruner`: cuts a VERIFIED local-share snapshot (the receiver's
 * own temporary copy, after the hash check, before the import) down to what this receiver
 * takes from a peer, so the import SQL never sees the rest.
 *
 *  - CruxCoach-native climbs (`source` nostr or local) never cross: they reach a receiver
 *    through Nostr with their author's signature, which a peer cannot vouch for.
 *  - Board families the receiver did not choose are cut. Only rows that NAME another family
 *    go; a row without `board_brand` is left for the importer to judge.
 */
object LocalShareSnapshotPruner {

    /** [keep] are board wire values; empty means "no choice was made" and keeps every family. Returns climbs removed. */
    fun prune(snapshot: SqlDriver, keep: Set<String>): Long {
        val db = SqlOps(snapshot)
        val tables = db.tables()
        if ("climbs" !in tables) return 0
        val climbColumns = db.columns("climbs")
        val cutNative = "source" in climbColumns
        val cutFamilies = keep.isNotEmpty() && "board_brand" in climbColumns
        if (!cutNative && !cutFamilies) return 0
        val before = db.long("SELECT COUNT(*) FROM climbs")
        val dependents = tables.filter { it != "climbs" && "climb_uuid" in db.columns(it) }
        val branded = tables.filter { "board_brand" in db.columns(it) }
        val indexes = (listOf("climbs") + dependents).flatMap { table ->
            db.strings("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL", table)
        }
        // Wire values are validated by the manifest parser ([a-z0-9_-]); checked again because they become SQL.
        val kept = keep.map { brand ->
            require(BRAND.matches(brand)) { "Invalid board brand" }
            "'$brand'"
        }.joinToString(",")
        db.transaction {
            // The importer reads this copy by full scan, rowid range or primary key; the browse
            // indexes of the sender only cost time per deleted row. Autoindexes (sql IS NULL) stay.
            runCatching { db.exec("PRAGMA cache_size=-65536") }
            // An inherited auto_vacuum=FULL relocates every freed page at COMMIT, for a file that
            // is deleted after the import; INCREMENTAL needs no VACUUM and is a no-op on NONE.
            runCatching { db.exec("PRAGMA auto_vacuum=INCREMENTAL") }
            indexes.forEach { index -> db.exec("DROP INDEX \"$index\"") }
            if (cutNative) {
                db.exec("DELETE FROM climbs WHERE LOWER(TRIM(COALESCE(source,''))) IN ('nostr','local')")
            }
            if (cutFamilies) {
                branded.forEach { table ->
                    db.exec("DELETE FROM \"$table\" WHERE board_brand IS NOT NULL AND LOWER(TRIM(board_brand)) NOT IN ($kept)")
                }
            }
            // Everything hanging off a climb goes with it, compared canonically like the importer.
            dependents.forEach { table ->
                db.exec("DELETE FROM \"$table\" WHERE LOWER(TRIM(climb_uuid)) NOT IN (SELECT LOWER(TRIM(uuid)) FROM climbs)")
            }
        }
        return before - db.long("SELECT COUNT(*) FROM climbs")
    }

    private val BRAND = Regex("[a-z0-9_-]{1,32}")
}
