package com.cruxcoach.app.nutrition

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag

/**
 * Plain SQLite files for the product database, through the same SQLite the
 * app links (SQLCipher). No key is set: the Open Food Facts extract is public
 * data, and SQLCipher opens a keyless connection as ordinary SQLite – the
 * same way the board catalogue is opened.
 */
internal object IosScratchSqlite {
    /** The file carries its own tables; SQLiter only stamps its user_version. */
    private val schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

    /** [scratch]: a build target until it is moved into place, so no fsync. Throws when it cannot be opened. */
    fun open(path: String, scratch: Boolean): SqlDriver {
        val directory = path.substringBeforeLast('/')
        val name = path.substringAfterLast('/')
        return NativeSqliteDriver(
            schema = schema,
            name = name,
            maxReaderConnections = 1,
            onConfiguration = { config ->
                config.copy(
                    // A rollback journal, not WAL: the file is renamed and deleted as a whole.
                    journalMode = JournalMode.DELETE,
                    extendedConfig = config.extendedConfig.copy(
                        basePath = directory,
                        busyTimeout = 5000,
                        synchronousFlag = if (scratch) SynchronousFlag.OFF else SynchronousFlag.NORMAL,
                    ),
                )
            },
        )
    }
}
