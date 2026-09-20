package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.BoardBrowserPresenter
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.SortDirection
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
 * The facade is the only surface SwiftUI sees, and Swift cannot be compiled on
 * this host, so its mapping is pinned here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiFacadeTest {
    private lateinit var db: BrowseTestDb
    private val store = MemoryKeyValueStore()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun id(i: Int) = "c" + i.toString().padStart(3, '0')

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        for (i in 1..60) db.climb(id(i), name = "Problem $i", difficulty = 10.0 + i % 10, ascensionists = 500L - i)
        (1..20).forEach { db.send(id(it)) }
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun model(french: Boolean = true) = BrowserScreenModel(
        BoardBrowserPresenter(db.boardRepo, db.personalRepo, store, { null }, main = dispatcher, io = dispatcher),
        GradeFormatter(french),
    )

    @Test
    fun `browser state reaches Swift with codes, grades and the exact count`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        val subscription = m.watch { last = it }
        m.start()
        advanceUntilIdle()
        val state = requireNotNull(last)
        assertEquals("ready", state.loadState)
        assertEquals(60L, state.totalCount)
        assertEquals(50, state.climbs.size)
        assertEquals("popular", state.sortCode)
        assertTrue(state.hasCatalogue)
        assertEquals("kilter", state.brandWire)
        val row = state.climbs.first()
        assertEquals("Problem 1", row.name)
        assertTrue(row.grade.isNotEmpty(), "grade label must be rendered for Swift")
        assertFalse(state.excludesSent)
        subscription.cancel()
    }

    @Test
    fun `v-scale and french formatters differ and an ungraded climb renders empty`() {
        assertEquals("", GradeFormatter(true).label(null))
        val french = GradeFormatter(true).label(20.0)
        val vScale = GradeFormatter(false).label(20.0)
        assertTrue(french.isNotEmpty() && vScale.isNotEmpty())
        assertTrue(french != vScale)
    }

    @Test
    fun `status codes toggle the same buckets Android uses`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        m.watch { last = it }
        m.start(); advanceUntilIdle()
        m.toggleStatus("sent"); advanceUntilIdle()
        assertTrue(requireNotNull(last).statusSent)
        assertFalse(requireNotNull(last).statusNew)
        // An unknown code must not change the selection.
        m.toggleStatus("nonsense"); advanceUntilIdle()
        assertTrue(requireNotNull(last).statusSent)
        m.setExcludeSent(true); advanceUntilIdle()
        assertTrue(requireNotNull(last).excludesSent)
        assertFalse(requireNotNull(last).statusSent)
    }

    @Test
    fun `sort codes map both ways and unknown codes are ignored`() = scope.runTest {
        for (code in listOf("popular", "quality", "qualitySends", "hardest", "easiest", "name", "newest", "random")) {
            val sort = requireNotNull(UiCodes.sort(code)) { code }
            assertEquals(code, UiCodes.sortCode(sort.first, sort.second), code)
        }
        val m = model()
        var last: BrowserScreenState? = null
        m.watch { last = it }
        m.start(); advanceUntilIdle()
        m.setSort("name"); advanceUntilIdle()
        assertEquals("name", requireNotNull(last).sortCode)
        m.setSort("does-not-exist"); advanceUntilIdle()
        assertEquals("name", requireNotNull(last).sortCode)
        assertEquals("hardest", UiCodes.sortCode(ClimbSortField.DIFFICULTY, SortDirection.DESC))
        assertEquals("easiest", UiCodes.sortCode(ClimbSortField.DIFFICULTY, SortDirection.ASC))
    }

    @Test
    fun `applying the filter form keeps untouched filters and reaches the results`() = scope.runTest {
        val m = model()
        var last: BrowserScreenState? = null
        m.watch { last = it }
        m.start(); advanceUntilIdle()
        m.applyFilters(minGradeIndex = 3, maxGradeIndex = 9, minAscensionists = 480,
                       benchmarkOnly = false, myClimbsOnly = false, ungradedOnly = false)
        advanceUntilIdle()
        val state = requireNotNull(last)
        assertEquals(3, state.minGradeIndex)
        assertEquals(9, state.maxGradeIndex)
        assertEquals(480, state.minAscensionists)
        assertTrue(state.climbs.size < 50, "the ascent floor must narrow the list")
        assertTrue(state.climbs.isNotEmpty())
    }

    @Test
    fun `settings keep Android key names and reject unknown values`() {
        val settings = SettingsModel(store)
        assertEquals("FRENCH", settings.gradeScale)
        assertTrue(settings.usesFrenchGrades)
        settings.gradeScale = "V_SCALE"
        assertEquals("V_SCALE", store.values[SettingsModel.GRADE_SCALE])
        assertFalse(settings.usesFrenchGrades)
        settings.gradeScale = "nonsense"
        assertEquals("V_SCALE", settings.gradeScale)
        settings.moonBoardLedMode = "ABOVE"
        assertEquals("ABOVE", store.values[SettingsModel.MOONBOARD_LED_MODE])
        settings.moonBoardLedMode = "sideways"
        assertEquals("ABOVE", settings.moonBoardLedMode)
        settings.bleAutoDisconnectSeconds = 99999
        assertEquals(3600, settings.bleAutoDisconnectSeconds)
    }
}
