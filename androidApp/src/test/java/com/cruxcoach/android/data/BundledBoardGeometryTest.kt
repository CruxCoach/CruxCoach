package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The geometry bundled in the APK lets community climbs of a board without a
 * catalogue be drawn, fitted and lit. Seeds run against the REAL board schema
 * (created via [BoardDatabase.Schema]), so column drift fails here.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class BundledBoardGeometryTest {
    private lateinit var context: Context
    private lateinit var dbFile: File

    private val bundledBoards = listOf("kilter", "tension", "grasshopper", "decoy", "soill", "touchstone")

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        dbFile = context.getDatabasePath("cruxcoach.db")
        dbFile.parentFile?.mkdirs()
        val driver = app.cash.sqldelight.driver.android.AndroidSqliteDriver(
            schema = BoardDatabase.Schema,
            context = context,
            name = "cruxcoach.db",
        )
        driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
        driver.execute(null, "DROP TABLE IF EXISTS _probe", 0)
        driver.close()
    }

    @After
    fun tearDown() {
        runCatching { dbFile.delete() }
    }

    // Unit tests run with the module dir as working dir; the second candidate
    // covers a repo-root invocation.
    private fun bundle(brand: String): File = listOf(
        File("src/main/assets/board_geometry/$brand.sqlite3"),
        File("androidApp/src/main/assets/board_geometry/$brand.sqlite3"),
    ).firstOrNull { it.exists() } ?: error("$brand.sqlite3 not bundled (cwd=${File(".").absolutePath})")

    private fun count(db: SQLiteDatabase, sql: String, vararg args: String): Int =
        db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun openTarget(): SQLiteDatabase =
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)

    @Test
    fun `every bundled board carries complete geometry of its own brand only`() {
        for (brand in bundledBoards) {
            SQLiteDatabase.openDatabase(bundle(brand).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                for (table in listOf("placements", "product_sizes", "board_images", "leds")) {
                    assertTrue("$brand $table empty", count(db, "SELECT COUNT(*) FROM $table") > 0)
                    assertEquals(
                        "$brand $table has other brands",
                        0,
                        count(db, "SELECT COUNT(*) FROM $table WHERE board_brand != ?", brand),
                    )
                }
            }
        }
    }

    @Test
    fun `a board without geometry is seeded completely`() {
        val bundled = SQLiteDatabase.openDatabase(bundle("kilter").absolutePath, null, SQLiteDatabase.OPEN_READONLY).use {
            listOf("placements", "product_sizes", "board_images", "leds").associateWith { t ->
                count(it, "SELECT COUNT(*) FROM $t")
            }
        }

        openTarget().use { db ->
            val seeded = BundledBoardGeometry.seed(db, bundle("kilter"), "kilter")

            assertEquals(bundled["placements"], seeded)
            for ((table, rows) in bundled) {
                assertEquals(table, rows, count(db, "SELECT COUNT(*) FROM $table WHERE board_brand = 'kilter'"))
            }
            // Only the requested board.
            assertEquals(0, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand != 'kilter'"))
            // Seeding again is a no-op.
            assertEquals(0, BundledBoardGeometry.seed(db, bundle("kilter"), "kilter"))
        }
    }

    @Test
    fun `a board with imported geometry is left alone`() {
        openTarget().use { db ->
            db.execSQL(
                "INSERT INTO placements(board_brand, placement_id, hole_id, set_id, x, y) VALUES ('tension', 1, 1, 1, 999, 999)",
            )

            assertEquals(0, BundledBoardGeometry.seed(db, bundle("tension"), "tension"))

            assertEquals(1, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand = 'tension'"))
            assertEquals(999, count(db, "SELECT x FROM placements WHERE board_brand = 'tension'"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM leds WHERE board_brand = 'tension'"))
        }
    }
}
