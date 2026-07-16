package com.cruxcoach.data

import android.content.Context
import android.util.Log
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import androidx.sqlite.db.SupportSQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase

actual class BoardDriverFactory(private val context: Context) {
    actual fun createDriver(): SqlDriver {
        // Return the driver WITHOUT calling execute() — this keeps the internal
        // lazy database delegate unopened. The actual DB open (and any pending
        // schema migrations) will happen on the first query, which should come
        // from a coroutine on Dispatchers.IO, avoiding main-thread ANR.
        return AndroidSqliteDriver(
            schema = BoardDatabase.Schema,
            context = context,
            name = "cruxcoach.db",
            callback = object : AndroidSqliteDriver.Callback(BoardDatabase.Schema) {
                // Android framework SQLite requires PRAGMAs via query(), not execSQL()
                fun SupportSQLiteDatabase.pragma(stmt: String) { query(stmt).close() }

                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.pragma("PRAGMA busy_timeout = 5000")
                }
                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    Log.i(DATABASE_TAG, "event=db_migration_start db=board from=$oldVersion to=$newVersion")
                    try {
                        super.onUpgrade(db, oldVersion, newVersion)
                        Log.i(
                            DATABASE_TAG,
                            "event=db_migration_done db=board from=$oldVersion to=$newVersion " +
                                "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
                        )
                    } catch (e: Exception) {
                        Log.e(
                            DATABASE_TAG,
                            "event=db_migration_failed db=board from=$oldVersion to=$newVersion " +
                                "type=${e.javaClass.simpleName}",
                        )
                        throw e
                    }
                }
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.pragma("PRAGMA journal_mode = WAL")
                    db.pragma("PRAGMA synchronous = NORMAL")
                    db.pragma("PRAGMA mmap_size = 268435456")
                    db.pragma("PRAGMA cache_size = -8000")
                    db.pragma("PRAGMA temp_store = MEMORY")
                    ensureHotPathIndexes(db)
                }
            }
        )
    }

    /**
     * Self-heal for hot-path indexes that are temporarily dropped during bulk
     * board imports (see BoardDatabaseImporter.withDeferredIndexes). If the
     * process is killed between DROP and CREATE, the indexes would stay gone
     * forever and every browse query would fall back to a full table scan.
     * Running `CREATE INDEX IF NOT EXISTS` on every open is idempotent and
     * cheap (SQLite returns immediately when the index exists).
     *
     * The DDL lives here and in BoardDatabaseImporter — keep both in sync.
     */
    private fun ensureHotPathIndexes(db: SupportSQLiteDatabase) {
        try {
            for (ddl in HOT_PATH_INDEX_DDL) db.execSQL(ddl)
        } catch (e: Exception) {
            // Tables may not exist yet on brand-new installs; schema callback
            // will (re-)create the indexes when the schema runs. Don't block
            // DB open on a self-heal failure.
            Log.w("DatabaseFactory", "ensureHotPathIndexes failed", e)
        }
    }

    companion object {
        /**
         * Hot-path index DDLs — must stay byte-identical to the index list
         * rebuilt in [com.cruxcoach.android.data.BoardDatabaseImporter]'s
         * `withDeferredIndexes`. Drift between the two locations means
         * indexes that were dropped during a bulk import won't get
         * recreated on the next app open and every browse query will fall
         * back to a full-table scan.
         *
         * The HotPathIndexDriftTest in androidUnitTest asserts both lists
         * stay in lock-step.
         */
        @Suppress("MaxLineLength")
        val HOT_PATH_INDEX_DDL: List<String> = listOf(
            "CREATE INDEX IF NOT EXISTS idx_climbs_listed ON climbs(is_listed)",
            "CREATE INDEX IF NOT EXISTS idx_climbs_frames_count ON climbs(is_listed, frames_count, uuid)",
            // FEAT-003 + 0.1.4 community-climb indexes. Mirror the set in
            // BoardDatabaseImporter.CLIMB_INDEXES — both lists are dropped
            // before bulk import and rebuilt afterwards, and are also
            // self-healed on app start if SQLite ever drops them. Without
            // these entries here, a mid-import process kill would leave
            // the device with no community-climb indexes and every
            // origin/source/pubkey-filtered query would full-scan.
            "CREATE INDEX IF NOT EXISTS idx_climbs_source ON climbs(source)",
            "CREATE INDEX IF NOT EXISTS idx_climbs_frames_hash ON climbs(frames_hash)",
            "CREATE INDEX IF NOT EXISTS idx_climbs_pubkey ON climbs(created_by_pubkey)",
            "CREATE INDEX IF NOT EXISTS idx_climbs_origin ON climbs(origin)",
            "CREATE INDEX IF NOT EXISTS idx_climbs_kilter_status ON climbs(kilter_status)",
            "CREATE INDEX IF NOT EXISTS idx_climbs_nostr_via ON climbs(nostr_publish_via)",
            "CREATE INDEX IF NOT EXISTS idx_climb_stats_angle ON climb_stats(angle)",
            "CREATE INDEX IF NOT EXISTS idx_climb_stats_browse ON climb_stats(angle, difficulty_average, quality_average, ascensionist_count, benchmark_difficulty, climb_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_climb_stats_by_popularity ON climb_stats(angle, ascensionist_count, difficulty_average, climb_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_climb_stats_count_cover ON climb_stats(angle, ascensionist_count, difficulty_average, benchmark_difficulty, climb_uuid)",
        )
    }

}

actual class SecureDriverFactory(
    private val context: Context,
    private val dbKey: ByteArray
) {
    init { require(dbKey.isNotEmpty()) { "Encryption key required" } }

    actual fun createDriver(dbName: String): SqlDriver {
        val factory = net.zetetic.database.sqlcipher.SupportOpenHelperFactory(dbKey)
        val driver = AndroidSqliteDriver(
            schema = SecureDatabase.Schema,
            context = context,
            name = dbName,
            factory = factory,
            callback = object : AndroidSqliteDriver.Callback(SecureDatabase.Schema) {
                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    val startedAt = android.os.SystemClock.elapsedRealtime()
                    Log.i(DATABASE_TAG, "event=db_migration_start db=secure from=$oldVersion to=$newVersion")
                    try {
                        super.onUpgrade(db, oldVersion, newVersion)
                        Log.i(
                            DATABASE_TAG,
                            "event=db_migration_done db=secure from=$oldVersion to=$newVersion " +
                                "durationMs=${android.os.SystemClock.elapsedRealtime() - startedAt}",
                        )
                    } catch (e: Exception) {
                        Log.e(
                            DATABASE_TAG,
                            "event=db_migration_failed db=secure from=$oldVersion to=$newVersion " +
                                "type=${e.javaClass.simpleName}",
                        )
                        throw e
                    }
                }
            },
        )
        // SQLCipher requires PRAGMAs to go through query path, not execute
        fun SqlDriver.pragma(stmt: String) {
            executeQuery(null, stmt, { cursor -> cursor.next(); QueryResult.Value(Unit) }, 0)
        }
        driver.pragma("PRAGMA journal_mode = WAL")
        driver.pragma("PRAGMA cache_size = -64000")
        driver.pragma("PRAGMA cipher_memory_security = OFF")
        driver.pragma("PRAGMA busy_timeout = 5000")
        return driver
    }
}

private const val DATABASE_TAG = "DatabaseFactory"
