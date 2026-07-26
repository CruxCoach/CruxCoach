package com.cruxcoach.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.board.FramesBinaryCodec

expect class BoardDriverFactory {
    fun createDriver(): SqlDriver
}

expect class SecureDriverFactory {
    fun createDriver(dbName: String = "cruxcoach_secure.db"): SqlDriver
}

private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
    override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
    override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
}

/**
 * The board database together with the driver that backs it.
 *
 * The generated [BoardDatabase] does not expose its driver, and the FEAT-044
 * relay climb lookup needs to issue one raw statement (its expression index,
 * created lazily rather than in a migration — building it over the full
 * catalogue takes ~12 s, which is not something to put in front of a cold app
 * start for a feature most sessions never use).
 */
class BoardDatabaseHandle(val database: BoardDatabase, val driver: SqlDriver)

fun createBoardDatabaseHandle(driverFactory: BoardDriverFactory): BoardDatabaseHandle {
    val driver = driverFactory.createDriver()
    return BoardDatabaseHandle(
        database = BoardDatabase(
            driver = driver,
            climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter)
        ),
        driver = driver,
    )
}

fun createBoardDatabase(driverFactory: BoardDriverFactory): BoardDatabase =
    createBoardDatabaseHandle(driverFactory).database

fun createSecureDatabase(driverFactory: SecureDriverFactory, dbName: String = "cruxcoach_secure.db"): SecureDatabase {
    val driver = driverFactory.createDriver(dbName)
    return SecureDatabase(driver = driver)
}
