package com.cruxcoach.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.SynchronousFlag
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase

/**
 * iOS board catalogue driver. Same file name and PRAGMAs as Android; the file
 * lives in SQLiter's default location (Application Support/databases).
 *
 * BoardDB is unencrypted on every platform (see docs/en/CORE_CONCEPTS.md).
 * When the app links SQLCipher, a connection without `PRAGMA key` still opens
 * plain SQLite files, so both databases can share one SQLite library.
 */
actual class BoardDriverFactory {
    actual fun createDriver(): SqlDriver {
        val driver = NativeSqliteDriver(
            schema = BoardDatabase.Schema,
            name = BOARD_DB_NAME,
            maxReaderConnections = 2,
            onConfiguration = { config ->
                config.copy(
                    journalMode = JournalMode.WAL,
                    extendedConfig = config.extendedConfig.copy(
                        busyTimeout = 5000,
                        synchronousFlag = SynchronousFlag.NORMAL,
                    ),
                    lifecycleConfig = DatabaseConfiguration.Lifecycle(
                        onCreateConnection = { connection ->
                            connection.rawExecSql("PRAGMA mmap_size = 268435456")
                            connection.rawExecSql("PRAGMA cache_size = -8000")
                            connection.rawExecSql("PRAGMA temp_store = MEMORY")
                        },
                    ),
                )
            },
        )
        ensureHotPathIndexes(driver)
        return driver
    }

    // Same self-heal as Android: a bulk import drops these indexes and a
    // process kill before the rebuild would otherwise leave full-table scans.
    private fun ensureHotPathIndexes(driver: SqlDriver) {
        try {
            for (ddl in BoardHotPathIndexes.DDL) driver.execute(null, ddl, 0)
        } catch (e: Exception) {
            println("DatabaseFactory: ensureHotPathIndexes failed: ${e.message}")
        }
    }

    companion object {
        const val BOARD_DB_NAME = "cruxcoach.db"
    }
}

/**
 * iOS personal-data driver. Requires SQLCipher to be the SQLite library the
 * app links (the shared framework is built with `linkSqlite = false`).
 *
 * [dbKey] is 32 random bytes held in the Keychain by the app. It is applied as
 * a raw SQLCipher key (`x'…'`), so no passphrase derivation is involved.
 *
 * Fails closed: if the linked SQLite is not SQLCipher, `PRAGMA key` would be a
 * silent no-op and personal data would land in a plaintext file. That is
 * detected through `PRAGMA cipher_version` and refused.
 */
actual class SecureDriverFactory(private val dbKey: ByteArray) {
    init { require(dbKey.size == KEY_BYTES) { "A $KEY_BYTES-byte encryption key is required" } }

    actual fun createDriver(dbName: String): SqlDriver {
        val rawKey = "x'" + dbKey.toHex() + "'"
        val driver = NativeSqliteDriver(
            schema = SecureDatabase.Schema,
            name = dbName,
            maxReaderConnections = 1,
            onConfiguration = { config ->
                config.copy(
                    journalMode = JournalMode.WAL,
                    extendedConfig = config.extendedConfig.copy(busyTimeout = 5000),
                    encryptionConfig = DatabaseConfiguration.Encryption(key = rawKey),
                    lifecycleConfig = DatabaseConfiguration.Lifecycle(
                        onCreateConnection = { connection ->
                            connection.rawExecSql("PRAGMA cache_size = -64000")
                        },
                    ),
                )
            },
        )
        val cipherVersion = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA cipher_version",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            },
            parameters = 0,
        ).value
        if (cipherVersion.isNullOrBlank()) {
            driver.close()
            throw IllegalStateException(
                "SQLCipher is not linked: refusing to open $dbName without encryption"
            )
        }
        return driver
    }

    companion object {
        const val KEY_BYTES = 32
    }
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(digits[v ushr 4]).append(digits[v and 0x0f])
    }
    return out.toString()
}
