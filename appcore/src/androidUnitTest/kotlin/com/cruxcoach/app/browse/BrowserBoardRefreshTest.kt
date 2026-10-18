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
 * The guided board fix confirmed outside the browser (climb detail, Quantum rack) stores the
 * board; the browser reloads it when it shows again and drops the old board's hold search.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserBoardRefreshTest {
    private lateinit var db: BrowseTestDb
    private val store = MemoryKeyValueStore()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        db.boardRepo.upsertProductSize(10, 1, "12x12", -1, 145, -1, 157, null)
        db.boardRepo.upsertPlacement(100, 1, 1, 24, 78)
        db.boardRepo.upsertPlacement(101, 2, 1, 120, 78)
        db.climb("left", frames = "p100r12p101r14")
        db.climb("other", frames = "p101r12")
    }

    @AfterTest
    fun tearDown() = db.close()

    @Test
    fun `a board stored elsewhere reloads the browser and drops its hold search`() = scope.runTest {
        val presenter = BoardBrowserPresenter(db.boardRepo, db.personalRepo, store, { null }, main = dispatcher, io = dispatcher)
        val m = BrowserScreenModel(presenter, GradeFormatter(false))
        var last: BrowserScreenState? = null
        val watch = m.watch { last = it }
        m.start(); advanceUntilIdle()
        val sheet = HoldSearchScreenModel(m, dispatcher)
        sheet.toggleHold(100); advanceUntilIdle()
        assertTrue(sheet.apply()); advanceUntilIdle()
        assertTrue(last!!.holdFilterActive)
        assertEquals(setOf("left"), last!!.climbs.map { it.uuid }.toSet())

        // Nothing changed: showing the browser again keeps everything.
        m.refreshIfBoardChanged(); advanceUntilIdle()
        assertTrue(last!!.holdFilterActive)
        assertEquals(listOf(100), last!!.holdFilter)

        // The detail's guided fix stored Kilter Homewall (AppCore.applyBoardChoice).
        BrowsePreferences(store).setBoardSelection("kilter", 8, 17, null)
        m.refreshIfBoardChanged(); advanceUntilIdle()
        val s = last!!
        assertEquals(8, presenter.state.value.filter.layoutId)
        assertFalse(presenter.storedBoardDiffers())
        assertFalse(s.holdFilterActive)
        assertTrue(s.holdFilter.isEmpty())
        assertTrue(HoldSearchScreenModel(m, dispatcher).currentState.selectedHolds.isEmpty(),
            "the sheet does not reopen with the old board's holds")
        watch.cancel()
        sheet.close(); m.close()
    }
}
