package com.cruxcoach.app.browse

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.app.ui.BrowserScreenModel
import com.cruxcoach.app.ui.BrowserScreenState
import com.cruxcoach.app.ui.GradeFormatter
import com.cruxcoach.app.ui.HoldSearchScreenModel
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

/**
 * Android's hold search sheet: taps only recount (hold matches alone, not the browser's
 * total), "Filter" applies and closes, "Reset" drops the filter everywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HoldSearchSheetTest {
    private lateinit var db: BrowseTestDb
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
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
    fun `taps recount without filtering and Filter applies the selection`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        val sheet = HoldSearchScreenModel(m, dispatcher)
        assertFalse(sheet.currentState.hasSelection)
        assertFalse(sheet.currentState.canApply)

        sheet.toggleHold(100)
        assertTrue(sheet.currentState.searching)
        advanceUntilIdle()
        var s = sheet.currentState
        assertFalse(s.searching)
        assertEquals(3, s.matchCount)
        assertTrue(s.canApply)
        assertEquals(setOf("left", "wide", "low", "other"), last!!.uuids(), "a tap alone does not filter")
        assertTrue(last!!.holdFilter.isEmpty())

        sheet.toggleHold(103); advanceUntilIdle()
        assertEquals(1, sheet.currentState.matchCount)
        assertTrue(sheet.apply(), "Filter closes the sheet")
        advanceUntilIdle()
        assertEquals(setOf("low"), last!!.uuids())
        assertEquals(listOf(100, 103), last!!.holdFilter)

        // Reopened over the active filter: the selection and its count come back.
        val again = HoldSearchScreenModel(m, dispatcher)
        assertEquals(listOf(100, 103), again.currentState.selectedHolds)
        advanceUntilIdle()
        assertEquals(1, again.currentState.matchCount)

        // A selection nothing matches cannot be applied.
        again.toggleHold(102); advanceUntilIdle()
        s = again.currentState
        assertEquals(0, s.matchCount)
        assertFalse(s.canApply)

        again.reset(); advanceUntilIdle()
        assertFalse(again.currentState.hasSelection)
        assertTrue(last!!.holdFilter.isEmpty())
        assertEquals(setOf("left", "wide", "low", "other"), last!!.uuids())
        watch.cancel()
        sheet.close(); again.close(); m.close()
    }

    @Test
    fun `the count is hold matches alone not the browser total`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        m.setSearch("wide"); advanceUntilIdle()
        assertEquals(1L, last!!.totalCount)

        val sheet = HoldSearchScreenModel(m, dispatcher)
        sheet.toggleHold(100); advanceUntilIdle()
        assertEquals(3, sheet.currentState.matchCount, "the search text does not narrow the hold count")
        watch.cancel()
        sheet.close(); m.close()
    }

    @Test
    fun `the banner counts the applied hold matches and Clear removes it`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        m.setSearch("wide"); advanceUntilIdle()
        assertFalse(last!!.holdFilterActive)

        val sheet = HoldSearchScreenModel(m, dispatcher)
        sheet.toggleHold(100); advanceUntilIdle()
        assertFalse(last!!.holdFilterActive, "a pending selection shows no banner")
        assertTrue(sheet.apply()); advanceUntilIdle()
        var s = last!!
        assertTrue(s.holdFilterActive)
        assertEquals(3, s.holdFilterMatches, "Android's holdFilterUuids.size")
        // Android's hold-filter query path does not apply the search text either.
        assertEquals(3L, s.totalCount)
        assertEquals(listOf(100), s.holdFilter)

        // An area alone ("Area → M climbs").
        sheet.toggleHold(100)
        sheet.setZone(0.3f, 1f, 0f, 0f); advanceUntilIdle()
        assertTrue(sheet.apply()); advanceUntilIdle()
        s = last!!
        assertTrue(s.holdFilterActive)
        assertTrue(s.holdFilter.isEmpty())
        assertTrue(s.zoneActive)
        assertEquals(1, s.holdFilterMatches)

        // "Clear" (Android clearHoldSelection).
        m.clearHoldFilter(); advanceUntilIdle()
        s = last!!
        assertFalse(s.holdFilterActive)
        assertEquals(0, s.holdFilterMatches)
        assertFalse(s.zoneActive)
        assertEquals(setOf("wide"), s.uuids())
        watch.cancel()
        sheet.close(); m.close()
    }

    @Test
    fun `an area is pending until Filter and removing it re-filters an active search`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        val sheet = HoldSearchScreenModel(m, dispatcher)
        sheet.setZone(0.3f, 1f, 0f, 0f); advanceUntilIdle()
        var s = sheet.currentState
        assertTrue(s.zoneActive)
        assertEquals(43f / 146f, s.zoneRight, 1e-6f)
        assertEquals(1, s.matchCount)
        assertFalse(last!!.zoneActive, "the area is not applied by drawing it")
        sheet.setZone(Float.NaN, 0f, 1f, 1f)
        assertEquals(43f / 146f, sheet.currentState.zoneRight, 1e-6f)

        // Not yet filtered: removing the area only recounts.
        assertFalse(sheet.clearZone())
        advanceUntilIdle()
        assertFalse(sheet.currentState.zoneActive)
        assertEquals(0, sheet.currentState.matchCount)

        sheet.toggleHold(100)
        sheet.setZone(0.3f, 1f, 0f, 0f); advanceUntilIdle()
        assertTrue(sheet.apply()); advanceUntilIdle()
        assertEquals(setOf("left"), last!!.uuids())
        assertTrue(last!!.zoneActive)

        // Filtered: Android applies the remaining holds at once.
        assertTrue(sheet.clearZone()); advanceUntilIdle()
        s = sheet.currentState
        assertFalse(s.zoneActive)
        assertFalse(last!!.zoneActive)
        assertEquals(listOf(100), last!!.holdFilter)
        assertEquals(setOf("left", "wide", "low"), last!!.uuids())
        watch.cancel()
        sheet.close(); m.close()
    }
}
