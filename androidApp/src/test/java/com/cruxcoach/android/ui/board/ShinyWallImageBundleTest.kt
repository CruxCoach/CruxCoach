package com.cruxcoach.android.ui.board

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.graphics.Color
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.ShinyWallVariant
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The bundled Shiny Wall wall images (scripts/build_shinywall_images.py) and
 * the bundled geometry must agree: each image covers exactly its variant's
 * board area, and every hold the app can draw or light sits on a photographed
 * hold rather than on the dark wall. A shift of half a cell already fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShinyWallImageBundleTest {

    private fun asset(path: String): File = listOf(File("src/main/assets/$path"), File("androidApp/src/main/assets/$path"))
        .firstOrNull { it.exists() } ?: error("$path not bundled (cwd=${File(".").absolutePath})")

    @Test fun `the renderer looks for each variant's image where it is bundled`() {
        for (variant in ShinyWallVariant.SUPPORTED) {
            val paths = boardImageCandidatePaths(BoardBrand.SHINYWALL, variant.productSizeId, layoutId = null)
            assertEquals(listOf("board_images/shinywall/board_${variant.productSizeId}.webp"), paths)
            assertTrue(asset(paths.single()).isFile)
        }
    }

    @Test fun `every hold of the geometry sits on a hold of its variant's image`() {
        SQLiteDatabase.openDatabase(asset("board_geometry/shinywall.sqlite3").absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            for (variant in ShinyWallVariant.SUPPORTED) {
                val (left, right, bottom, top) = db.rawQuery(
                    "SELECT edge_left, edge_right, edge_bottom, edge_top FROM product_sizes WHERE id = ?",
                    arrayOf(variant.productSizeId.toString()),
                ).use { c -> c.moveToFirst(); List(4) { c.getLong(it) } }
                val bitmap = BitmapFactory.decodeFile(
                    asset("board_images/shinywall/board_${variant.productSizeId}.webp").absolutePath,
                ) ?: error("${variant.code}: image does not decode")
                // Same area, same proportions, a whole number of pixels per unit.
                assertEquals(0, bitmap.width % (right - left).toInt())
                val scale = bitmap.width / (right - left).toDouble()
                assertEquals("${variant.code} height", ((top - bottom) * scale).toInt(), bitmap.height)
                assertTrue("${variant.code}: corner is not the wall", luminance(bitmap.getPixel(0, 0)) < 60)

                var holds = 0
                db.rawQuery(
                    "SELECT placement_id, x, y FROM placements WHERE placement_id BETWEEN ? AND ?",
                    arrayOf(variant.placementId(0).toString(), variant.placementId(variant.holdCount - 1).toString()),
                ).use { c ->
                    while (c.moveToNext()) {
                        holds++
                        val cx = (c.getLong(1) - left) * scale
                        val cy = (top - c.getLong(2)) * scale
                        val brightness = meanLuminance(bitmap, cx, cy, radius = HOLD_PROBE_UNITS * scale)
                        assertTrue(
                            "${variant.code}: placement ${c.getLong(0)} is on the wall, not a hold ($brightness)",
                            brightness > MIN_HOLD_LUMINANCE,
                        )
                    }
                }
                assertTrue("${variant.code}: no holds", holds > 200)
                bitmap.recycle()
            }
        }
    }

    private fun meanLuminance(bitmap: android.graphics.Bitmap, cx: Double, cy: Double, radius: Double): Double {
        var sum = 0.0
        var n = 0
        val r = radius.toInt()
        for (dx in -r..r) for (dy in -r..r) {
            if (dx * dx + dy * dy > r * r) continue
            val x = (cx + dx).toInt()
            val y = (cy + dy).toInt()
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) continue
            sum += luminance(bitmap.getPixel(x, y))
            n++
        }
        return if (n == 0) 0.0 else sum / n
    }

    private fun luminance(color: Int): Double =
        0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)

    private companion object {
        /** Probe disc: 3 units = 0.15 of a cell, inside even the small holds. */
        const val HOLD_PROBE_UNITS = 3.0
        /** Holds measure 112+ here, the wall about 40; half a cell off, no hold passes 100. */
        const val MIN_HOLD_LUMINANCE = 90.0
    }
}
