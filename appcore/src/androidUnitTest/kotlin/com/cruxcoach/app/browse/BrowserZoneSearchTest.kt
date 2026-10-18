package com.cruxcoach.app.browse

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.setup.BoardOption
import com.cruxcoach.app.ui.BrowserScreenModel
import com.cruxcoach.app.ui.BrowserScreenState
import com.cruxcoach.app.ui.GradeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Android's hold search with "Select area": climbs must lie fully inside the dragged box. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserZoneSearchTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        // The browser's default product size 10; its edges strictly contain the climbs' 0..144 x 0..156
        // box, as the browse query's size fit requires: the board is 146 x 158 units from -1/-1.
        db.boardRepo.upsertProductSize(10, 1, "12x12", -1, 145, -1, 157, null)
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertPlacement(101, 2, 1, 120, 78)
        db.boardRepo.upsertPlacement(102, 3, 1, 24, 140)
        db.boardRepo.upsertPlacement(103, 4, 1, 72, 20)
        db.climb("left", frames = "p100r12p102r14")
        db.climb("wide", frames = "p100r12p101r14")
        db.climb("low", frames = "p103r12p100r14")
        db.climb("other", frames = "p101r12p103r14")
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun model() = BrowserScreenModel(
        BoardBrowserPresenter(db.boardRepo, db.personalRepo, MemoryKeyValueStore(), { null }, main = dispatcher, io = dispatcher),
        GradeFormatter(false),
    )

    private fun BrowserScreenState.uuids() = climbs.map { it.uuid }.toSet()

    @Test
    fun `an area keeps only climbs whose holds all lie inside it and combines with chosen holds`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        assertEquals(setOf("left", "wide", "low", "other"), last!!.uuids())
        assertFalse(last!!.zoneActive)

        // Drag over the left 30 % of the board, bottom-right to top-left.
        m.setZone(0.3f, 1f, 0f, 0f); advanceUntilIdle()
        var s = last!!
        assertEquals(setOf("left"), s.uuids())
        assertTrue(s.zoneActive)
        // Placement space: x -1..42 (0.3 × 146 − 1), y the full height; drawn back over the image.
        assertEquals(0f, s.zoneLeft)
        assertEquals(0f, s.zoneTop)
        assertEquals(43f / 146f, s.zoneRight, 1e-6f)
        assertEquals(1f, s.zoneBottom)

        // Chosen holds and the area apply together.
        m.toggleHoldFilter(101); advanceUntilIdle()
        assertTrue(last!!.uuids().isEmpty(), "101 lies outside the area")
        m.toggleHoldFilter(101); m.toggleHoldFilter(100); advanceUntilIdle()
        assertEquals(setOf("left"), last!!.uuids())

        // "Remove area" keeps the holds.
        m.clearZone(); advanceUntilIdle()
        s = last!!
        assertFalse(s.zoneActive)
        assertEquals(0f, s.zoneRight)
        assertEquals(listOf(100), s.holdFilter)
        assertEquals(setOf("left", "wide", "low"), s.uuids())

        // "Reset" drops holds and area.
        m.setZone(0f, 0f, 1f, 1f); advanceUntilIdle()
        assertEquals(setOf("left", "wide", "low"), last!!.uuids())
        m.clearHoldFilter(); advanceUntilIdle()
        s = last!!
        assertFalse(s.zoneActive)
        assertTrue(s.holdFilter.isEmpty())
        assertEquals(setOf("left", "wide", "low", "other"), s.uuids())
        watch.cancel()
        m.close()
    }

    @Test
    fun `invalid drag points are ignored and a board switch drops the area`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        m.setZone(Float.NaN, 0f, 1f, 1f); advanceUntilIdle()
        assertFalse(last!!.zoneActive)
        // Points beyond the image are clamped onto it, as a drag that leaves the board.
        // x -1..86 (0.6 × 146 − 1), y -1..93 (0.6 × 158 − 1): only "low" (holds at 72/20 and 24/78) fits.
        m.setZone(-2f, 0.4f, 0.6f, 3f); advanceUntilIdle()
        assertTrue(last!!.zoneActive)
        assertEquals(0f, last!!.zoneLeft)
        assertEquals(1f, last!!.zoneBottom)
        assertEquals(1f - 94f / 158f, last!!.zoneTop, 1e-6f)
        assertEquals(setOf("low"), last!!.uuids())

        // The same board keeps the area (the presenter keeps its filter too)…
        m.switchBoard(BoardOption("kilter", "Kilter Board", 1, 10, "Original", "12x12"))
        advanceUntilIdle()
        assertTrue(last!!.zoneActive)
        assertEquals(setOf("low"), last!!.uuids())
        // …another board drops it: its coordinates mean nothing there.
        m.switchBoard(BoardOption("tension", "Tension Board", 9, 1, "Tension Board", "Full Wall"))
        advanceUntilIdle()
        assertFalse(last!!.zoneActive)
        assertTrue(last!!.holdFilter.isEmpty())
        watch.cancel()
        m.close()
    }
}
