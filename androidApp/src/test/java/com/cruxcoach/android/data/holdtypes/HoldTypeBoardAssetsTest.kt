package com.cruxcoach.android.data.holdtypes

import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.holdtypes.HoldDirection
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeDataset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Every bundled hold-type file (#63) other than Kilter's, against the board geometry the app ships. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class HoldTypeBoardAssetsTest {

    private fun asset(path: String): File = listOf(File("src/main/assets/$path"), File("androidApp/src/main/assets/$path"))
        .firstOrNull { it.exists() } ?: error("$path not bundled (cwd=${File(".").absolutePath})")

    private fun boards(): List<String> = asset(HoldTypeAssets.DIR).list().orEmpty()
        .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }

    private fun dataset(board: String) = HoldTypeDataset.parse(asset("${HoldTypeAssets.DIR}/$board.json").readText())

    private fun <T> geometry(board: String, sql: String, read: (android.database.Cursor) -> T): List<T> {
        val db = SQLiteDatabase.openDatabase(asset("board_geometry/$board.sqlite3").path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use { it.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } } }
    }

    @Test
    fun everyFileNamesItsBoardAndUsesTheTaxonomy() {
        assertTrue("kilter" in boards())
        boards().forEach { board ->
            val data = dataset(board)
            assertEquals(board, data.board)
            val keys = data.holds.map { it.layoutId to it.placement }
            assertEquals("$board: one entry per hold", keys.size, keys.toSet().size)
            data.holds.forEach { h ->
                h.type?.let { assertNotNull("$board type $it", HoldType.fromWire(it)) }
                h.direction?.let { assertNotNull("$board direction $it", HoldDirection.fromWire(it)) }
                assertTrue("$board second use of ${h.placement}", h.second == null || (h.second != "foot_chip" && h.second != h.type))
                assertTrue(h.size == null || h.size in setOf("small", "medium", "large"))
                assertTrue(h.confidence.type in 0.0..1.0 && h.confidence.direction in 0.0..1.0)
            }
        }
    }

    @Test
    fun onlyKilterHasABlindRoundIn025() {
        // 0.2.5 (owner, 2026-11-08): only the Kilter card can be unlocked; the other boards' rounds follow later.
        boards().filter { it != "kilter" }.forEach { board -> assertNull("$board blind round", dataset(board).blindRound) }
    }

    @Test
    fun aBoardWithABlindRoundHasOneCalibratedConfidencePerType() {
        boards().map { dataset(it) }.filter { it.blindRound != null }.forEach { data ->
            data.holds.filter { it.type != null }.groupBy { it.type }.forEach { (type, holds) ->
                assertEquals("${data.board} $type", 1, holds.map { it.confidence.type }.toSet().size)
            }
        }
    }

    @Test
    fun everyReferenceHoldIsLabelledAndListedOnce() {
        boards().forEach { board ->
            val data = dataset(board)
            assertEquals("$board reference set", data.referenceSet.size, data.referenceSet.toSet().size)
            data.referenceSet.forEach { key -> assertNotNull("$board reference $key", data.referenceEntry(key)?.holdType) }
        }
    }

    @Test
    fun moonBoardCoversExactlyTheCellsOfEveryGeneration() {
        if ("moonboard" !in boards()) return
        val data = dataset("moonboard")
        MoonBoardVariant.entries.forEach { variant ->
            val layout = variant.layoutId.toInt()
            val cells = MoonBoardHoldSets.cellSets(variant)
            val labelled = data.holds.filter { it.layoutId == layout }.associate { it.placement to it.setId.toLong() }
            assertEquals("cells of ${variant.name}", cells, labelled)
        }
    }

    @Test
    fun auroraBoardsCoverEveryPlacementOfTheirGeometry() {
        boards().filter { it != "kilter" && it != "moonboard" }.forEach { board ->
            val data = dataset(board)
            val placements = geometry(board, "SELECT placement_id, set_id FROM placements WHERE board_brand = '$board'") {
                it.getInt(0) to it.getInt(1)
            }.toMap()
            assertEquals("$board placements", placements, data.holds.associate { it.placement to it.setId })
            val views = geometry(board, "SELECT product_size_id, layout_id FROM board_images WHERE board_brand = '$board'") {
                it.getInt(0) to it.getInt(1)
            }.toSet()
            // Shiny Wall has no per-layout board images: its view is the size image alone.
            if (views.isEmpty()) return@forEach
            data.holds.filter { it.viewSizeId > 0 }.forEach { h ->
                assertTrue("$board size ${h.viewSizeId} for ${h.placement}", (h.viewSizeId to h.layoutId) in views)
            }
        }
    }
}
