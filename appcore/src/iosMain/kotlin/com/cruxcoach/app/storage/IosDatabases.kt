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
     * [secureDbKey] is the 32-byte per-identity key; [secureDbName] follows
     * Android's `cruxcoach_secure_<pubkey-prefix>.db` naming.
     */
    fun open(secureDbKey: ByteArray, secureDbName: String): DatabaseOpenResult {
        if (secureDbKey.size != SecureDriverFactory.KEY_BYTES) {
            return DatabaseOpenResult(null, null, null, DatabaseFailure.BAD_KEY, "key must be 32 bytes")
        }
        return try {
            val board = createBoardDatabaseHandle(BoardDriverFactory())
            val secureDriver = SecureDriverFactory(secureDbKey).createDriver(secureDbName)
            val cipherVersion = secureDriver.executeQuery(
                null, "PRAGMA cipher_version",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 0,
            ).value
            DatabaseOpenResult(board, SecureDatabase(secureDriver), cipherVersion, null, null)
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
