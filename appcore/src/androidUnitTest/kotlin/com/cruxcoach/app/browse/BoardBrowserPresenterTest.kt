package com.cruxcoach.app.browse

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.app.browse.testing.MemoryKeyValueStore
import com.cruxcoach.data.repository.BoardRepository
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

@OptIn(ExperimentalCoroutinesApi::class)
class BoardBrowserPresenterTest {
    private lateinit var db: BrowseTestDb
    private val store = MemoryKeyValueStore()
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun id(i: Int) = "c" + i.toString().padStart(3, '0')

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        for (i in 1..120) db.climb(id(i), ascensionists = 1000L - i)
        (1..70).forEach { db.send(id(it)) }
    }

    @AfterTest
    fun tearDown() = db.close()

    private var failSearch = false

    private inner class FlakyRepo(d: BoardRepository) : BoardRepository by d {
        private val real = d
        override fun searchClimbsByName(
            query: String, angle: Int, layoutId: Int, boardBrand: String, sortField: ClimbSortField,
            sortDirection: SortDirection, limit: Int, offset: Int, climbType: com.cruxcoach.data.repository.ClimbTypeFilter,
            selProductSizeId: Int, hsmExcludedMask: Long,
        ) = if (failSearch) throw IllegalStateException("database is locked") else real.searchClimbsByName(
            query, angle, layoutId, boardBrand, sortField, sortDirection, limit, offset, climbType, selProductSizeId, hsmExcludedMask,
        )
    }

    private fun presenter(pubkey: () -> String? = { null }) =
        BoardBrowserPresenter(FlakyRepo(db.boardRepo), db.personalRepo, store, pubkey, main = dispatcher, io = dispatcher)

    @Test
    fun `defaults load a page then the debounced exact count and scrolling never duplicates`() = scope.runTest {
        val p = presenter()
        p.start()
        advanceUntilIdle()
        var s = p.state.value
        assertEquals(BrowseLoadState.READY, s.loadState)
        assertEquals(BrowserFilterState().copy(), s.filter.copy())
        assertEquals((1..50).map(::id), s.climbs.map { it.uuid })
        assertEquals(120L, s.totalCount)
        assertTrue(s.canLoadMore)
        p.loadMore(); advanceUntilIdle()
        p.loadMore(); advanceUntilIdle()
        p.loadMore(); advanceUntilIdle()
        s = p.state.value
        assertEquals(120, s.climbs.size)
        assertEquals(120, s.climbs.map { it.uuid }.toSet().size)
        assertFalse(s.canLoadMore)
        assertEquals(120L, s.totalCount)
        p.close()
    }

    @Test
    fun `exclude sent shares the status set persists with Android keys and counts exactly`() = scope.runTest {
        val p = presenter()
        p.start(); advanceUntilIdle()
        p.setExcludeSent(true); advanceUntilIdle()
        var s = p.state.value
        assertEquals(setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED), s.filter.statusFilter)
        assertTrue(s.excludesSent)
        assertEquals("NEW,ATTEMPTED", store.getString("board_status_filter"))
        assertEquals((71..120).map(::id), s.climbs.map { it.uuid }.sorted())
        assertEquals(50L, s.totalCount)

        p.toggleStatus(ClimbStatusFilter.NEW); p.toggleStatus(ClimbStatusFilter.ATTEMPTED); p.toggleStatus(ClimbStatusFilter.SENT)
        advanceUntilIdle()
        s = p.state.value
        assertEquals(70, s.climbs.size, "direct-uuid SENT set must not be truncated at 50")
        assertEquals(70L, s.totalCount)
        assertFalse(s.canLoadMore)

        p.setExcludeSent(true); advanceUntilIdle()
        assertEquals(setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED), p.state.value.filter.statusFilter)
        p.setExcludeSent(false); advanceUntilIdle()
        assertEquals(emptySet(), p.state.value.filter.statusFilter)
        assertEquals("", store.getString("board_status_filter"))
        p.close()
    }

    @Test
    fun `a stale page from a replaced query is rejected`() = scope.runTest {
        val p = presenter()
        p.start(); advanceUntilIdle()
        p.loadMore()                 // page 2 of the unfiltered query is now in flight
        p.setSearch("c11")           // replaces the query before it lands
        advanceUntilIdle()
        val s = p.state.value
        assertEquals((110..119).map(::id), s.climbs.map { it.uuid }.sorted())
        assertEquals(10L, s.totalCount)
        assertEquals(BrowseLoadState.READY, s.loadState)
        p.close()
    }

    @Test
    fun `persisted filters are restored and a board switch clamps them and zeroes the mask`() = scope.runTest {
        store.putString("board_sort_field", "NAME")
        store.putString("board_sort_direction", "ASC")
        store.putString("board_status_filter", "UNSENT")
        store.putString("board_origin_filter", "BOARDSESH")
        store.putString("board_benchmark_only", "true")
        store.putString("board_brand", "quantum")
        store.putString("board_layout_id", "9101")
        val p = presenter()
        p.start(); advanceUntilIdle()
        val f = p.state.value.filter
        assertEquals(ClimbSortField.NAME, f.sortField)
        assertEquals(SortDirection.ASC, f.sortDirection)
        assertEquals(setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED), f.statusFilter)
        assertEquals(OriginFilter.ALL, f.originFilter, "Quantum has no BoardSesh provenance")
        assertFalse(f.benchmarkOnly)
        assertEquals(0L, p.state.value.core.hsmExcludedMask)
        assertFalse(p.state.value.board.hasCatalogue)

        p.switchBoard("kilter", 1, 10, 40); advanceUntilIdle()
        val s = p.state.value
        assertEquals("kilter", s.filter.boardBrand)
        assertTrue(s.board.hasCatalogue)
        assertEquals((71..120).map(::id), s.climbs.map { it.uuid })
        assertEquals("kilter", store.getString("board_brand"))
        p.close()
    }

    @Test
    fun `repository failure becomes state and pickRandom publishes distinct events`() = scope.runTest {
        val p = presenter { throw IllegalStateException("no signer") }
        p.start(); advanceUntilIdle()
        p.pickRandom(); advanceUntilIdle()
        val first = p.state.value.randomClimb
        p.pickRandom(); advanceUntilIdle()
        val second = p.state.value.randomClimb
        assertTrue(first != null && second != null && first.id != second.id)
        p.setFilters(
            BrowseFilterEdit(0, 16, 0, com.cruxcoach.data.repository.ClimbTypeFilter.BOULDER, false, OriginFilter.ALL,
                myClimbsOnly = true, ungradedOnly = false, quantumRuleMask = 7, quantumOverlapFilter = com.cruxcoach.domain.board.QuantumOverlapFilter.NONE),
        )
        advanceUntilIdle()
        val s = p.state.value
        assertEquals(BrowseLoadState.READY, s.loadState)
        assertEquals(0, s.climbs.size)
        assertEquals(0L, s.totalCount)
        assertEquals(0L, s.filter.quantumRuleMask, "Quantum-only state cannot leak onto Kilter")
        p.clearAllFilters(); advanceUntilIdle() // my-climbs never reaches the name search
        failSearch = true
        p.setSearch("x"); advanceUntilIdle()
        assertEquals(BrowseLoadState.FAILED, p.state.value.loadState)
        assertEquals(BrowseError.QUERY_FAILED, p.state.value.error)
        p.close()
    }
}
