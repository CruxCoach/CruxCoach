package com.cruxcoach.app.storage

import app.cash.sqldelight.db.QueryResult
import com.cruxcoach.data.BoardDatabaseHandle
import com.cruxcoach.data.BoardDriverFactory
import com.cruxcoach.data.SecureDriverFactory
import com.cruxcoach.data.createBoardDatabaseHandle
import com.cruxcoach.db.secure.SecureDatabase

enum class DatabaseFailure { BAD_KEY, ENCRYPTION_UNAVAILABLE, OPEN_FAILED }

/** Either both databases, or a failure code. Never throws into Swift. */
class DatabaseOpenResult(
    val board: BoardDatabaseHandle?,
    val secure: SecureDatabase?,
    val cipherVersion: String?,
    val failure: DatabaseFailure?,
    val detail: String?,
)

object IosDatabases {
    /**
     * One handle per database file per process.
     *
     * SQLite/SQLCipher is opened through SQLiter, and opening the same file a
     * second time in one process does not re-apply the encryption key: the
     * second open fails with "file is not a database". The app opens once, but
     * caching makes a second [open] call safe instead of destructive.
     *
     * Not synchronised: [open] is a start-up call from the main thread.
     */
    private val opened = mutableMapOf<String, DatabaseOpenResult>()
    private var boardHandle: BoardDatabaseHandle? = null

    /**
     * [secureDbKey] is the 32-byte per-identity key; [secureDbName] follows
     * Android's `cruxcoach_secure_<pubkey-prefix>.db` naming.
     */
    fun open(secureDbKey: ByteArray, secureDbName: String): DatabaseOpenResult {
        if (secureDbKey.size != SecureDriverFactory.KEY_BYTES) {
            return DatabaseOpenResult(null, null, null, DatabaseFailure.BAD_KEY, "key must be 32 bytes")
        }
        opened[secureDbName]?.let { return it }
        return try {
            val board = boardHandle ?: createBoardDatabaseHandle(BoardDriverFactory()).also { boardHandle = it }
            val secureDriver = SecureDriverFactory(secureDbKey).createDriver(secureDbName)
            val cipherVersion = secureDriver.executeQuery(
                null, "PRAGMA cipher_version",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0,
            ).value
            DatabaseOpenResult(board, SecureDatabase(secureDriver), cipherVersion, null, null)
                .also { opened[secureDbName] = it }
        } catch (e: IllegalStateException) {
            val unencrypted = e.message?.contains("SQLCipher is not linked") == true
            DatabaseOpenResult(
                null, null, null,
                if (unencrypted) DatabaseFailure.ENCRYPTION_UNAVAILABLE else DatabaseFailure.OPEN_FAILED,
                e.message,
            )
        } catch (e: Exception) {
            DatabaseOpenResult(null, null, null, DatabaseFailure.OPEN_FAILED, e.message)
        }
    }
}
