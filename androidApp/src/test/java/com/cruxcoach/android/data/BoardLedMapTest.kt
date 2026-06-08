package com.cruxcoach.android.data

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.db.board.Climbs
import com.cruxcoach.domain.board.FramesBinaryCodec
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Brand-scoping regression for `getPlacementLedMap` (FEAT-031). The query JOINs
 * placements ⋈ leds ON (hole_id, board_brand); without the board_brand half of
 * the join, a hole_id shared across two boards would cross-map LEDs and the BLE
 * send would light the WRONG holds. This proves a shared hole_id stays isolated
 * per brand — the correctness property that was previously never exercised
 * against a real SQLite engine.
 */
class BoardLedMapTest {

    private lateinit var dbFile: java.io.File
    private lateinit var driver: SqlDriver
    private lateinit var db: BoardDatabase

    private val framesAdapter = object : ColumnAdapter<String, ByteArray> {
        override fun decode(databaseValue: ByteArray): String = FramesBinaryCodec.decode(databaseValue)
        override fun encode(value: String): ByteArray = FramesBinaryCodec.encode(value)
    }

    @BeforeTest
    fun setUp() {
        val tmp = Files.createTempDirectory("cruxcoach-ledmap-")
        dbFile = tmp.resolve("board.db").toFile()
        driver = JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        BoardDatabase.Schema.create(driver)
        db = BoardDatabase(driver, climbsAdapter = Climbs.Adapter(framesAdapter = framesAdapter))
    }

    @AfterTest
    fun tearDown() {
        driver.close()
        dbFile.delete()
        dbFile.parentFile?.delete()
    }

    @Test
    fun `getPlacementLedMap stays scoped to its brand even when a hole_id is shared`() {
        // Same hole_id (100) + product_size_id (10) seeded on two boards.
        db.boardQueries.upsertPlacement("kilter", 1L, 100L, 1L, 0L, 0L)
        db.boardQueries.upsertLed("kilter", 100L, 10L, 5L)
        db.boardQueries.upsertPlacement("tension", 2L, 100L, 1L, 0L, 0L)
        db.boardQueries.upsertLed("tension", 100L, 10L, 9L)

        val kilter = db.boardQueries.getPlacementLedMap(10L, "kilter").executeAsList()
        assertEquals(1, kilter.size, "kilter query must NOT pick up tension's row on the shared hole_id")
        assertEquals(1L, kilter[0].placement_id)
        assertEquals(5L, kilter[0].led_position)

        val tension = db.boardQueries.getPlacementLedMap(10L, "tension").executeAsList()
        assertEquals(1, tension.size, "tension query must NOT pick up kilter's row on the shared hole_id")
        assertEquals(2L, tension[0].placement_id)
        assertEquals(9L, tension[0].led_position)
    }
}
