package com.cruxcoach.android.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.db.board.BoardDatabase
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.ShinyWallVariant
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bundled Shiny Wall geometry is the only source of hold positions for
 * this family, so it must agree with [ShinyWallVariant]: every placement sits
 * on its variant's hold numbers and inside that variant's own board area, and
 * replacing it on an update keeps exactly one copy.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class ShinyWallGeometryBundleTest {
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        dbFile = context.getDatabasePath("cruxcoach.db")
        dbFile.parentFile?.mkdirs()
        app.cash.sqldelight.driver.android.AndroidSqliteDriver(BoardDatabase.Schema, context, "cruxcoach.db").use { driver ->
            driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe (x INTEGER)", 0)
            driver.execute(null, "DROP TABLE IF EXISTS _probe", 0)
        }
    }

    @After fun tearDown() {
        runCatching { dbFile.delete() }
    }

    private fun bundle(): File = listOf(
        File("src/main/assets/board_geometry/shinywall.sqlite3"),
        File("androidApp/src/main/assets/board_geometry/shinywall.sqlite3"),
    ).firstOrNull { it.exists() } ?: error("shinywall.sqlite3 not bundled (cwd=${File(".").absolutePath})")

    private fun count(db: SQLiteDatabase, sql: String, vararg args: String): Int =
        db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Test fun `placements match the variants hold numbers and areas`() {
        SQLiteDatabase.openDatabase(bundle().absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(0, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand != 'shinywall'"))
            for (variant in ShinyWallVariant.SUPPORTED) {
                val ids = mutableListOf<Pair<Int, Pair<Int, Int>>>()
                db.rawQuery(
                    "SELECT placement_id, x, y FROM placements WHERE placement_id BETWEEN ? AND ?",
                    arrayOf(variant.placementId(0).toString(), variant.placementId(variant.holdCount - 1).toString()),
                ).use { c -> while (c.moveToNext()) ids += c.getInt(0) to (c.getInt(1) to c.getInt(2)) }
                assertTrue("${variant.code} has no placements", ids.isNotEmpty())
                // Placeholder indices have no hold, so a few numbers may be missing, never extra.
                assertTrue("${variant.code}: ${ids.size} placements", variant.holdCount - ids.size in 0..3)
                ids.forEach { (id, _) -> assertTrue("${variant.code} $id", variant.indexOf(id) != null) }
                db.rawQuery(
                    "SELECT edge_left, edge_right, edge_bottom, edge_top FROM product_sizes WHERE id = ?",
                    arrayOf(variant.productSizeId.toString()),
                ).use { c ->
                    assertTrue("${variant.code} has no product size", c.moveToFirst())
                    val (left, right, bottom, top) = listOf(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3))
                    ids.forEach { (id, xy) ->
                        assertTrue("${variant.code} $id outside its area", xy.first in left..right && xy.second in bottom..top)
                    }
                    // Backup restores reject coordinates past 10 000.
                    assertTrue(right < 10_000 && top < 10_000 && left >= 0)
                }
            }
        }
    }

    @Test fun `replacing the bundle keeps one copy and the brand's other tables`() {
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO placements(board_brand,placement_id,hole_id,set_id,x,y) VALUES('shinywall',2999999,2999999,2,1,1)")
            db.execSQL("INSERT INTO placements(board_brand,placement_id,hole_id,set_id,x,y) VALUES('kilter',1,1,1,1,1)")
            val first = BundledBoardGeometry.replace(db, bundle(), BoardBrand.SHINYWALL.wireValue)
            val second = BundledBoardGeometry.replace(db, bundle(), BoardBrand.SHINYWALL.wireValue)
            assertEquals(first, second)
            assertEquals(first, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand='shinywall'"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM placements WHERE placement_id=2999999"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM placements WHERE board_brand='kilter'"))
            assertEquals(ShinyWallVariant.SUPPORTED.size, count(db, "SELECT COUNT(*) FROM product_sizes WHERE board_brand='shinywall'"))
        }
    }
}
