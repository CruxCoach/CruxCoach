package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.BoardBrowserPresenter
import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
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

/** Android BoardFilterScreen's sort field/direction, Type, Origin and "without a grade" rows, through the facade. */
@OptIn(ExperimentalCoroutinesApi::class)
class BrowserFilterSortTest {
    private lateinit var db: BrowseTestDb
    private val store = MemoryKeyValueStore()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun id(i: Int) = "c" + i.toString().padStart(3, '0')

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        for (i in 1..12) db.climb(id(i), ascensionists = 100L - i)
        db.climb("cc1", ascensionists = 5L, provenance = "cruxcoach")
        db.climb("cc2", ascensionists = 4L, provenance = "cruxcoach")
        db.climb("u1", difficulty = null, ascensionists = 0L)
        db.climb("u2", difficulty = null, ascensionists = 0L)
        db.climb("u3", difficulty = null, ascensionists = 0L)
    }

    @AfterTest
    fun tearDown() = db.close()

    private fun model() = BrowserScreenModel(
        BoardBrowserPresenter(db.boardRepo, db.personalRepo, store, { null }, main = dispatcher, io = dispatcher),
        GradeFormatter(true),
    )

    @Test
    fun `sort field keeps the chosen direction and the direction reverses the list`() = scope.runTest {
        val m = model()
        m.start(); advanceUntilIdle()
        var s = m.currentState
        assertEquals("popular", s.sortField)
        assertTrue(s.sortDescending)
        assertEquals(id(1), s.climbs.first().uuid)

        m.setSortDescending(false); advanceUntilIdle()
        s = m.currentState
        assertFalse(s.sortDescending)
        assertEquals("popular", s.sortField)
        val sends = s.climbs.map { it.sends }
        assertTrue(sends.size > 2)
        assertEquals(sends.sorted(), sends, "fewest sends first")
        assertTrue(s.climbs.first().uuid != id(1))

        // Android updateSortField: a new field does not reset the direction.
        m.setSortField("moves"); advanceUntilIdle()
        s = m.currentState
        assertEquals("moves", s.sortField)
        assertFalse(s.sortDescending)

        m.setSortField("bogus"); advanceUntilIdle()
        assertEquals("moves", m.currentState.sortField)
        assertEquals(listOf("popular", "quality", "qualitySends", "moves", "newest", "random"), m.sortFieldCodes)
    }

    @Test
    fun `origin and type filters reach the results and unknown codes are ignored`() = scope.runTest {
        val m = model()
        m.start(); advanceUntilIdle()
        assertEquals("all", m.currentState.origin)
        assertEquals("boulder", m.currentState.climbType)
        assertTrue(m.currentState.supportsClimbType)
        assertTrue(m.currentState.supportsBoardSeshOrigin)

        m.setOrigin("cruxcoach"); advanceUntilIdle()
        assertEquals("cruxcoach", m.currentState.origin)
        assertEquals(setOf("cc1", "cc2"), m.currentState.climbs.map { it.uuid }.toSet())

        m.setOrigin("nonsense"); advanceUntilIdle()
        assertEquals("cruxcoach", m.currentState.origin)

        m.setClimbType("all"); advanceUntilIdle()
        assertEquals("all", m.currentState.climbType)
        assertEquals("cruxcoach", m.currentState.origin, "a type change keeps the origin")
        m.setClimbType("route"); advanceUntilIdle()
        assertEquals("route", m.currentState.climbType)
        assertTrue(m.currentState.climbs.isEmpty(), "every fixture is a single-frame boulder")
    }

    @Test
    fun `ungraded mode clears ascents and benchmarks and the switch carries its count`() = scope.runTest {
        val m = model()
        m.start(); advanceUntilIdle()
        assertEquals(-1L, m.currentState.ungradedAvailable)
        m.refreshUngradedAvailable(); advanceUntilIdle()
        assertEquals(3L, m.currentState.ungradedAvailable)

        m.applyFilters(minGradeIndex = 0, maxGradeIndex = 16, minAscensionists = 20,
                       benchmarkOnly = true, myClimbsOnly = false, ungradedOnly = false)
        advanceUntilIdle()
        m.setUngradedOnly(true); advanceUntilIdle()
        val s = m.currentState
        assertTrue(s.ungradedOnly)
        assertEquals(0, s.minAscensionists)
        assertFalse(s.benchmarkOnly)
        assertEquals(setOf("u1", "u2", "u3"), s.climbs.map { it.uuid }.toSet())

        m.setUngradedOnly(false); advanceUntilIdle()
        assertFalse(m.currentState.ungradedOnly)
    }

    @Test
    fun `a stale ungraded count for another angle is dropped`() = scope.runTest {
        val m = model()
        m.start(); advanceUntilIdle()
        m.refreshUngradedAvailable()
        m.setAngle(20)
        advanceUntilIdle()
        assertEquals(-1L, m.currentState.ungradedAvailable)
        m.refreshUngradedAvailable(); advanceUntilIdle()
        assertEquals(0L, m.currentState.ungradedAvailable, "the fixtures are graded only at 40 degrees")
    }
}
