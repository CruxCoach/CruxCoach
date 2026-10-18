package com.cruxcoach.app.browse

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.gym.GymBoardCatalog
import com.cruxcoach.app.render.BoardHeatmap
import com.cruxcoach.app.ui.StatsHeatmapModel
import com.cruxcoach.app.ui.StatsHeatmapScreenState
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.math.ln
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The statistics' hold heatmap. The option cases are ports of Android's
 * `HeatmapBoardOptionsTest` and `BoardLogbookHeatmapBoardKeyTest`; the rest
 * runs the presenter and facade over a real board and secure database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsHeatmapTest {
    private fun kilter(layoutId: Int) = BoardBrand.KILTER.wireValue to layoutId
    private fun tension(layoutId: Int) = BoardBrand.TENSION.wireValue to layoutId
    private fun moonboard(layoutId: Int) = BoardBrand.MOONBOARD.wireValue to layoutId

    @Test
    fun `no logged boards yields no options`() {
        assertTrue(HeatmapBoards.options(emptySet()).isEmpty())
    }

    @Test
    fun `Kilter with both layouts logged yields Original and Homewall`() {
        val opts = HeatmapBoards.options(setOf(kilter(HeatmapBoards.KILTER_ORIGINAL_LAYOUT), kilter(HeatmapBoards.KILTER_HOMEWALL_LAYOUT)))
        assertEquals(2, opts.size)
        val original = opts.first { it.layoutId == HeatmapBoards.KILTER_ORIGINAL_LAYOUT }
        val homewall = opts.first { it.layoutId == HeatmapBoards.KILTER_HOMEWALL_LAYOUT }
        assertEquals(BoardBrand.KILTER.wireValue, original.brandWire)
        assertEquals(10, original.sizeId)
        assertEquals("Kilter Original", original.displayName)
        assertEquals(21, homewall.sizeId)
        assertEquals("Kilter Homewall", homewall.displayName)
    }

    @Test
    fun `Kilter with only Original logged yields only Original`() {
        val opts = HeatmapBoards.options(setOf(kilter(HeatmapBoards.KILTER_ORIGINAL_LAYOUT)))
        assertEquals(1, opts.size)
        assertEquals(HeatmapBoards.KILTER_ORIGINAL_LAYOUT, opts.single().layoutId)
    }

    @Test
    fun `Tension yields one option per logged variant`() {
        val variants = GymBoardCatalog.auroraVariants(BoardBrand.TENSION)
        val opts = HeatmapBoards.options(variants.map { tension(it.layoutId) }.toSet())
        assertEquals(variants.size, opts.size)
        variants.forEach { v ->
            val match = opts.first { it.layoutId == v.layoutId }
            assertEquals(BoardBrand.TENSION.wireValue, match.brandWire)
            assertEquals(v.defaultSizeId, match.sizeId)
            assertEquals(v.displayName, match.displayName)
        }
        assertEquals(opts.size, opts.map { it.layoutId }.distinct().size)
        val only = HeatmapBoards.options(setOf(tension(variants.first().layoutId)))
        assertEquals(listOf(variants.first().layoutId), only.map { it.layoutId })
    }

    @Test
    fun `single layout Aurora board yields one option on the logged layout and its largest size`() {
        val opt = HeatmapBoards.options(setOf(BoardBrand.GRASSHOPPER.wireValue to 7)).single()
        assertEquals(BoardBrand.GRASSHOPPER.wireValue, opt.brandWire)
        assertEquals("Grasshopper", opt.displayName)
        assertEquals(7, opt.layoutId)
        assertEquals(4, opt.sizeId, "GrandMaster (12 x 12) is Grasshopper's largest bundled size")
    }

    @Test
    fun `MoonBoard is omitted until it can render a heatmap and other brands only when logged`() {
        val moon = HeatmapBoards.options(MoonBoardVariant.entries.map { moonboard(it.layoutId.toInt()) }.toSet())
        assertEquals(BoardBrand.MOONBOARD.hasHeatmap, moon.any { it.brandWire == BoardBrand.MOONBOARD.wireValue })
        val kilterOnly = HeatmapBoards.options(setOf(kilter(1)))
        assertTrue(kilterOnly.all { it.brandWire == BoardBrand.KILTER.wireValue })
    }

    @Test
    fun `logged board key keeps known layouts and only places legacy Kilter rows on Original`() {
        assertNull(HeatmapBoards.loggedBoardKey(BoardBrand.MOONBOARD.wireValue, null))
        assertEquals(BoardBrand.MOONBOARD.wireValue to 5, HeatmapBoards.loggedBoardKey(BoardBrand.MOONBOARD.wireValue, 5L))
        assertEquals(BoardBrand.KILTER.wireValue to 1, HeatmapBoards.loggedBoardKey(BoardBrand.KILTER.wireValue, null))
    }

    @Test
    fun `default selection prefers the active board then its brand then the first`() {
        val opts = HeatmapBoards.options(setOf(kilter(8), tension(9)))
        assertEquals(8, HeatmapBoards.defaultSelection(opts, "kilter", 8)!!.layoutId)
        assertEquals(8, HeatmapBoards.defaultSelection(opts, "kilter", 1)!!.layoutId, "same brand, other layout")
        assertEquals("kilter", HeatmapBoards.defaultSelection(opts, "decoy", 2)!!.brandWire, "first offered")
        assertNull(HeatmapBoards.defaultSelection(emptyList(), "kilter", 1))
        val sized = HeatmapBoards.withActiveSize(opts, "kilter", 8, 23)
        assertEquals(23, sized.first { it.brandWire == "kilter" }.sizeId)
        assertEquals(1, sized.first { it.brandWire == "tension" }.sizeId)
    }

    // ── presenter + facade over real databases ─────────────────────────

    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertProductSize(10, 1, "12x12", 0, 144, 0, 156, null)
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertPlacement(101, 2, 1, 120, 78)
        db.climb("c1", frames = "p100r12p101r14")
        db.climb("c2", frames = "p101r12")
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun model() = StatsHeatmapModel(
        StatsHeatmapPresenter(db.boardRepo, db.personalRepo, BrowsePreferences(MemoryKeyValueStore()), main = dispatcher, io = dispatcher),
        dispatcher,
    )

    private fun StatsHeatmapScreenState.heatOf(id: Int) = holds.firstOrNull { it.placementId == id }

    @Test
    fun `without any logged ascent the section has no board to offer`() = scope.runTest {
        val m = model()
        assertEquals("loading", m.currentState.status)
        m.load(); advanceUntilIdle()
        assertEquals("noBoards", m.currentState.status)
        assertTrue(m.currentState.holds.isEmpty())
        m.close()
    }

    @Test
    fun `my sends all climbs and role modes are drawn on the selected board`() = scope.runTest {
        db.send("c1") // a legacy-style Kilter row without a layout: Kilter Original
        val m = model()
        var last: StatsHeatmapScreenState? = null
        val watch = m.watch { last = it }
        m.load(); advanceUntilIdle()
        var s = last!!
        assertEquals("ready", s.status)
        assertEquals(listOf("kilter:1:10"), s.boards.map { it.key })
        assertEquals("Kilter Original", s.boards.single().name)
        assertEquals("kilter:1:10", s.selectedKey)
        assertEquals("personal", s.modeCode)
        assertEquals(listOf("global", "personal", "start", "hand", "foot", "finish"), s.modeCodes)
        // The send's frames are "p100r12": one hot hold at 24/144, half height.
        assertEquals(1, s.holds.size)
        val hot = s.heatOf(100)!!
        assertEquals(24f / 144f, hot.x, 1e-6f)
        assertEquals(0.5f, hot.y, 1e-6f)
        assertEquals(BoardHeatmap.colorArgb(1f), hot.argb)
        assertEquals(BoardHeatmap.haloAlpha(1f), hot.haloAlpha)
        assertEquals(4.5f / 144f, s.haloRadius, 1e-6f)
        assertEquals(1.8f / 144f, s.coreRadius, 1e-6f)
        assertEquals(144f / 156f, s.aspect, 1e-6f)
        assertEquals("board_images/board_10.webp", s.imagePaths.last(), "the size image is the last fallback")
        assertFalse(s.isEmpty)

        // All climbs of the layout: 101 twice, 100 once (log-scaled like Android).
        m.setMode("global"); advanceUntilIdle()
        s = last!!
        assertEquals(BoardHeatmap.colorArgb(1f), s.heatOf(101)!!.argb)
        assertEquals(BoardHeatmap.colorArgb((ln(2.0) / ln(3.0)).toFloat()), s.heatOf(100)!!.argb)

        // Top holds: role 14 only (101 in c1).
        m.setMode("finish"); advanceUntilIdle()
        s = last!!
        assertEquals(listOf(101), s.holds.map { it.placementId })
        // Foot holds: none in this catalogue, which is Android's "no data" hint.
        m.setMode("foot"); advanceUntilIdle()
        assertTrue(last!!.isEmpty)
        m.setMode("bogus"); advanceUntilIdle()
        assertEquals("foot", last!!.modeCode)

        // "All" hides the grid, an unknown key changes nothing, the board comes back.
        m.selectBoard(""); advanceUntilIdle()
        assertEquals("pickBoard", last!!.status)
        assertTrue(last!!.holds.isEmpty())
        m.selectBoard("tension:9:1"); advanceUntilIdle()
        assertEquals("pickBoard", last!!.status)
        m.selectBoard("kilter:1:10"); advanceUntilIdle()
        assertEquals("ready", last!!.status)
        assertTrue(last!!.isEmpty, "the foot mode is kept across board picks")
        // A reload keeps the pick.
        m.load(); advanceUntilIdle()
        assertEquals("kilter:1:10", last!!.selectedKey)
        watch.cancel()
        m.close()
    }
}
