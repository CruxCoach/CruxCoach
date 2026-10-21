package com.cruxcoach.android.data

import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * Extra connections to the live board database (`cruxcoach.db`) besides the app's driver.
 *
 * The board DB runs in WAL mode so browse reads go on while an import writes. A connection opened with the
 * framework's defaults asks for the TRUNCATE journal: while the app's driver has the file open in WAL that request
 * fails harmlessly, but alone it switches the whole file back to rollback mode, where one writer locks every reader
 * out. So every writer puts WAL back right after opening.
 *
 * It stays ONE connection on purpose: asking the framework for WAL at open time gives a pool of several connections
 * on Android 13+, and then an `ATTACH` and the next statement can land on different connections ("unknown database"),
 * and connection-scoped PRAGMAs (busy timeout, cache size, auto-checkpoint) would miss the statements they tune.
 */
internal object BoardDbConnection {
    /** Once checkpointed, the WAL file shrinks back to this size (64 MiB) instead of keeping an import's size. */
    const val WAL_SIZE_LIMIT_BYTES = 64L * 1024 * 1024

    fun openForWrite(file: File): SQLiteDatabase {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            // A PRAGMA cursor must be stepped: journal_mode changes only when the statement runs.
            db.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA journal_size_limit = $WAL_SIZE_LIMIT_BYTES", null).use { it.moveToFirst() }
        } catch (failure: Exception) {
            db.close()
            throw failure
        }
        return db
    }
}
