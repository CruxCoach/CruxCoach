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

fun createBoardDatabase(driverFactory: BoardDriverFactory): BoardDatabase {
    val driver = driverFactory.createDriver()
    return BoardDatabase(
        driver = driver,
        climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter)
    )
}

fun createSecureDatabase(driverFactory: SecureDriverFactory, dbName: String = "cruxcoach_secure.db"): SecureDatabase {
    val driver = driverFactory.createDriver(dbName)
    return SecureDatabase(driver = driver)
}
