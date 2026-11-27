package com.cruxcoach.android.data.holdtypes

import android.database.sqlite.SQLiteDatabase
import com.cruxcoach.domain.holdtypes.HoldDirection
import com.cruxcoach.domain.holdtypes.HoldType
import com.cruxcoach.domain.holdtypes.HoldTypeDataset
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The bundled Kilter hold types (#63) against the bundled Kilter geometry. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class HoldTypeAssetTest {

    private fun asset(path: String): File = listOf(File("src/main/assets/$path"), File("androidApp/src/main/assets/$path"))
        .firstOrNull { it.exists() } ?: error("$path not bundled (cwd=${File(".").absolutePath})")

    private val dataset by lazy { HoldTypeDataset.parse(asset("hold_types/kilter.json").readText()) }

    private fun <T> geometry(sql: String, read: (android.database.Cursor) -> T): List<T> {
        val db = SQLiteDatabase.openDatabase(asset("board_geometry/kilter.sqlite3").path, null, SQLiteDatabase.OPEN_READONLY)
        return db.use { it.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) add(read(c)) } } }
    }

    @Test
    fun everyPlacementHasExactlyOneEntry() {
        val placements = geometry("SELECT placement_id FROM placements WHERE board_brand = 'kilter'") { it.getInt(0) }.toSet()
        val labelled = dataset.holds.map { it.placement }
        assertEquals(labelled.size, labelled.toSet().size)
        assertEquals(placements, labelled.toSet())
    }

    @Test
    fun valuesBelongToTheTaxonomy() {
        dataset.holds.forEach { h ->
            h.type?.let { assertNotNull("type $it", HoldType.fromWire(it)) }
            h.direction?.let { assertNotNull("direction $it", HoldDirection.fromWire(it)) }
            // Hands or feet is the use field, never a second use (owner, 2026-11-06).
            assertTrue("second use of ${h.placement}", h.second == null || (h.second != "foot_chip" && h.second != h.type))
            assertTrue(h.size == null || h.size in setOf("small", "medium", "large"))
        }
    }

    @Test
    fun everyViewSizeShowsTheHoldsLayout() {
        val sizeLayouts = geometry("SELECT product_size_id, layout_id FROM board_images WHERE board_brand = 'kilter'") {
            it.getInt(0) to it.getInt(1)
        }.toSet()
        dataset.holds.forEach { h ->
            assertTrue("size ${h.viewSizeId} for ${h.placement}", (h.viewSizeId to h.layoutId) in sizeLayouts)
        }
    }

    @Test
    fun referenceSetIsLabelledAndUnique() {
        assertEquals(153, dataset.referenceSet.size)
        assertEquals(dataset.referenceSet.size, dataset.referenceSet.toSet().size)
        dataset.referenceSet.forEach { assertNotNull("reference $it", dataset[it]?.holdType) }
    }
}
