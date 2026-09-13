package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase

/** Owns the index lifecycle shared by the Kilter and MoonBoard bulk importers. */
internal class BoardImportIndexes(
    private val openDatabase: () -> SQLiteDatabase,
    private val indexes: List<Pair<String, String>>,
) {
    fun <R> duringImport(onRebuild: () -> Unit, import: () -> R): R {
        val deferred = openDatabase().use { db ->
            // All boards share these tables and indexes. Even a first import
            // of one brand must preserve another brand's live browse queries.
            val hasCatalogueRows = db.rawQuery(
                "SELECT EXISTS(SELECT 1 FROM climbs) OR EXISTS(SELECT 1 FROM climb_stats)",
                null,
            ).use { it.moveToFirst(); it.getInt(0) != 0 }
            if (hasCatalogueRows) {
                // Recover an interrupted older import without needing an app
                // restart. With a healthy catalogue this performs no DDL.
                restoreMissingIndexes(db)
                false
            } else {
                // A lock/error halfway through preparation must roll back ALL
                // drops. Previously the restore finally had not been entered
                // yet, leaving the already dropped indexes permanently absent.
                transaction(db) {
                    indexes.forEach { (name, _) -> db.execSQL("DROP INDEX IF EXISTS $name") }
                }
                true
            }
        }

        var importFailure: Throwable? = null
        try {
            return import()
        } catch (failure: Throwable) {
            importFailure = failure
            throw failure
        } finally {
            try {
                // A failing progress observer must not prevent restoration.
                try {
                    onRebuild()
                } finally {
                    if (deferred) openDatabase().use(::restoreMissingIndexes)
                }
            } catch (restoreFailure: Throwable) {
                val original = importFailure
                if (original == null) throw restoreFailure
                original.addSuppressed(restoreFailure)
            }
        }
    }

    private fun restoreMissingIndexes(db: SQLiteDatabase) {
        val existing = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        val missing = indexes.filterNot { it.first in existing }
        if (missing.isNotEmpty()) {
            transaction(db) { missing.forEach { (_, ddl) -> db.execSQL(ddl) } }
        }
    }

    private fun transaction(db: SQLiteDatabase, block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
