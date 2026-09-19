package com.cruxcoach.app.browse

import com.cruxcoach.app.browse.testing.BrowseTestDb
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.QuantumOverlapFilter
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browse pipeline against a real BoardDB + SecureDB: exact counts, no duplicates, nothing lost after 50. */
class BoardBrowsePipelineDbTest {
    private lateinit var db: BrowseTestDb
    private lateinit var pipeline: BoardBrowsePipeline
    private var pubkey: String? = "me"
    private val ctx = BrowseContext(frenchGrades = true)
    private val wide = BrowserFilterState(
        minGradeIndex = 0, maxGradeIndex = 40, sortField = ClimbSortField.NAME, sortDirection = SortDirection.ASC,
    )

    private fun id(i: Int) = "c" + i.toString().padStart(3, '0')

    @BeforeTest
    fun setUp() {
        db = BrowseTestDb()
        pipeline = BoardBrowsePipeline(db.boardRepo, db.personalRepo, { pubkey })
        for (i in 1..130) db.climb(id(i), benchmark = if (i % 10 == 0) 15.0 else null)
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun scrollAll(f: BrowserFilterState, c: BrowseContext = ctx): List<String> {
        var (rows, offset, exhausted) = pipeline.search(f, c)
        var guard = 0
        while (!exhausted) {
            check(guard++ < 100)
            val (more, next, done) = pipeline.fetchFiltered(f, c, offset)
            rows = mergeBrowseClimbs(rows, more); offset = next; exhausted = done
        }
        return rows.map { it.uuid }
    }

    @Test
    fun `plain browse pages by fifty and the count equals the scrolled list`() = runTest {
        val (first, offset, exhausted) = pipeline.search(wide, ctx)
        assertEquals(50, first.size)
        assertEquals(50, offset)
        assertFalse(exhausted)
        val all = scrollAll(wide)
        assertEquals((1..130).map(::id), all)
        assertEquals(130L, pipeline.resolveCount(wide, ctx))
        assertTrue(pipeline.isPlainCount(wide, ctx))
    }

    @Test
    fun `status buckets are disjoint and every combination counts exactly`() = runTest {
        (1..60).forEach { db.send(id(it)) }
        (55..75).forEach { db.attempt(id(it)) } // 55..60 are sent as well -> stay SENT
        fun f(vararg s: ClimbStatusFilter) = wide.copy(statusFilter = s.toSet())

        val sent = scrollAll(f(ClimbStatusFilter.SENT))
        assertEquals((1..60).map(::id), sent, "direct-uuid branch must return all 60, not the first 50")
        assertEquals((61..75).map(::id), scrollAll(f(ClimbStatusFilter.ATTEMPTED)))
        assertEquals((76..130).map(::id), scrollAll(f(ClimbStatusFilter.NEW)))
        assertEquals((61..130).map(::id), scrollAll(f(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED)))
        assertEquals((1..75).map(::id), scrollAll(f(ClimbStatusFilter.SENT, ClimbStatusFilter.ATTEMPTED)))

        assertEquals(60L, pipeline.resolveCount(f(ClimbStatusFilter.SENT), ctx))
        assertEquals(15L, pipeline.resolveCount(f(ClimbStatusFilter.ATTEMPTED), ctx))
        assertEquals(55L, pipeline.resolveCount(f(ClimbStatusFilter.NEW), ctx))
        assertEquals(70L, pipeline.resolveCount(statusFilterWithSentExcluded(emptySet(), true).let { wide.copy(statusFilter = it) }, ctx))
        assertEquals(130L, pipeline.resolveCount(f(*ClimbStatusFilter.entries.toTypedArray()), ctx))
    }

    @Test
    fun `NEW scan returns a continuation after the scan cap instead of reporting the end`() = runTest {
        (1..130).filter { it <= 120 }.forEach { db.send(id(it)) }
        val f = wide.copy(statusFilter = setOf(ClimbStatusFilter.NEW))
        // Page size 10 x MAX_STATUS_SCAN_PAGES 10 = 100 rows scanned, all sent -> nothing yet, not exhausted.
        val (rows, offset, exhausted) = pipeline.fetchFiltered(f, ctx, 0, pageSize = 10)
        assertTrue(rows.isEmpty())
        assertEquals(100, offset)
        assertFalse(exhausted)
        assertEquals((121..130).map(::id), scrollAll(f))
        assertEquals(10L, pipeline.resolveCount(f, ctx))
    }

    @Test
    fun `ignored climbs never show and force the counted path`() = runTest {
        db.personalRepo.toggleIgnored(id(3))
        db.personalRepo.toggleIgnored(id(77))
        pipeline.invalidateUserData()
        assertFalse(pipeline.isPlainCount(wide, ctx))
        val all = scrollAll(wide)
        assertEquals(128, all.size)
        assertFalse(id(3) in all || id(77) in all)
        assertEquals(128L, pipeline.resolveCount(wide, ctx))
    }

    @Test
    fun `benchmark filter uses the fast benchmark count and matches the list`() = runTest {
        val f = wide.copy(benchmarkOnly = true)
        assertEquals((10..130 step 10).map(::id), scrollAll(f))
        assertEquals(13L, pipeline.resolveCount(f, ctx))
    }

    @Test
    fun `name search counts through the search query`() = runTest {
        val f = wide.copy(searchQuery = "c01")
        assertEquals((10..19).map(::id), scrollAll(f))
        assertEquals(10L, pipeline.resolveCount(f, ctx))
    }

    @Test
    fun `grade range and ungraded-only impossible range`() = runTest {
        db.climb("ungraded-1", difficulty = null, ascensionists = 0)
        db.climb("hard", difficulty = 30.0)
        val narrow = wide.copy(minGradeIndex = 0, maxGradeIndex = 16)
        val bounds = pipeline.gradeBounds(narrow, ctx)
        assertFalse(bounds.showUngraded)
        val normal = scrollAll(narrow)
        assertFalse("ungraded-1" in normal, "ungraded climbs never appear in a regular browse")
        assertEquals(bounds.maxDiff >= 30.0, "hard" in normal)

        val only = narrow.copy(ungradedOnly = true)
        assertEquals(BoardBrowsePipeline.GradeBounds(9999.0, -9999.0, true), pipeline.gradeBounds(only, ctx))
        assertEquals(listOf("ungraded-1"), scrollAll(only))
        assertEquals(1L, pipeline.resolveCount(only, ctx))
    }

    @Test
    fun `origin filters return whole sets and keep local drafts on the cruxcoach side`() = runTest {
        for (i in 1..60) db.climb("com-$i", provenance = "cruxcoach", ascensionists = 0)
        db.climb("draft-other-angle", provenance = "local", angle = 25, pubkey = "me")
        db.climb("bs-1", provenance = "boardsesh", difficulty = null, ascensionists = 0)

        val crux = wide.copy(originFilter = OriginFilter.CRUXCOACH)
        val (rows, _, exhausted) = pipeline.search(crux, ctx)
        assertTrue(exhausted)
        // 60 community climbs + the local draft (angle-agnostic, grouped with the cruxcoach side).
        assertEquals(61, rows.size, "whole-set branch must not truncate at 50")
        assertTrue(rows.any { it.uuid == "draft-other-angle" })
        assertEquals(61L, pipeline.resolveCount(crux, ctx))

        assertEquals(listOf("bs-1"), scrollAll(wide.copy(originFilter = OriginFilter.BOARDSESH)))
        val official = scrollAll(wide.copy(originFilter = OriginFilter.KILTER))
        assertEquals(130, official.size)
        assertEquals(130L, pipeline.resolveCount(wide.copy(originFilter = OriginFilter.KILTER), ctx))

        val mine = wide.copy(myClimbsOnly = true)
        assertEquals(listOf("draft-other-angle"), scrollAll(mine), "own drafts stay visible at any angle")
        pubkey = null
        assertEquals(emptyList(), scrollAll(mine))
        assertEquals(0L, pipeline.resolveCount(mine, ctx))
    }

    @Test
    fun `hold filter intersects holds and an empty match set is an exact zero`() = runTest {
        db.climb("h-both", frames = "p500r12p501r13p502r14")
        db.climb("h-one", frames = "p500r12p777r13")
        db.climb("h-prefix-trap", frames = "p5001r12p5011r13")
        val uuids = pipeline.findUuidsMatchingHoldFilter(wide, ctx, setOf(500, 501), null, emptyMap())
        assertEquals(setOf("h-both"), uuids)
        val active = ctx.copy(holdFilterActive = true, holdFilterUuids = uuids)
        assertEquals(listOf("h-both"), scrollAll(wide, active))
        assertEquals(1L, pipeline.resolveCount(wide, active))
        val none = ctx.copy(holdFilterActive = true, holdFilterUuids = emptySet())
        assertEquals(emptyList(), scrollAll(wide, none))
        assertEquals(0L, pipeline.resolveCount(wide, none))
    }

    @Test
    fun `quantum rule mask and overlap filter fail closed and never leak to other brands`() = runTest {
        db.climb("q-all", brand = "quantum", layoutId = 9101, hsm = 0, provenance = "quantum", frames = "p1r12p2r13")
        db.climb("q-standard-missing", brand = "quantum", layoutId = 9101, hsm = 16, provenance = "quantum", frames = "p3r12")
        db.climb("q-overlap", brand = "quantum", layoutId = 9101, hsm = 0, provenance = "quantum", frames = "p10r12p11r13")
        val q = wide.copy(boardBrand = "quantum", layoutId = 9101, climbTypeFilter = ClimbTypeFilter.BOULDER)

        val standard = q.copy(quantumRuleMask = QuantumRuleFilter.STANDARD.bit)
        assertEquals(listOf("q-all", "q-overlap"), scrollAll(standard))
        assertEquals(2L, pipeline.resolveCount(standard, ctx))
        // The same mask on Kilter is inert.
        assertEquals(130L, pipeline.resolveCount(wide.copy(quantumRuleMask = 16L), ctx))

        val lit = ctx.copy(quantumLayers = BrowserQuantumLayerState(litPlacements = setOf(10, 11), layerCount = 1))
        val none = q.copy(quantumOverlapFilter = QuantumOverlapFilter.NONE)
        assertEquals(listOf("q-all", "q-standard-missing"), scrollAll(none, lit))
        assertEquals(2L, pipeline.resolveCount(none, lit))
        assertEquals(listOf("q-all"), scrollAll(none.copy(originFilter = OriginFilter.KILTER, quantumRuleMask = 16L), lit))
        // No lit layers -> the policy turns the filter off instead of hiding everything.
        assertEquals(3, scrollAll(none, ctx).size)
    }

    @Test
    fun `random sort scrolls the whole set without duplicates or gaps and counting does not reroll`() = runTest {
        val f = wide.copy(sortField = ClimbSortField.RANDOM)
        val (first, offset, _) = pipeline.search(f, ctx)
        assertEquals(50, first.size)
        assertEquals(130L, pipeline.resolveCount(f, ctx))
        val (again, _, _) = pipeline.search(f, ctx)
        assertEquals(first.map { it.uuid }, again.map { it.uuid }, "page 1 is cached for one selection")
        val all = scrollAll(f)
        assertEquals(130, all.size)
        assertEquals((1..130).map(::id).toSet(), all.toSet())
        assertEquals(50, offset)
        pipeline.invalidateRandomCache()
    }

    @Test
    fun `random pick honours ignores in plain mode and the loaded list otherwise`() = runTest {
        val tiny = wide.copy(searchQuery = "c00") // c001..c009
        (1..8).forEach { db.personalRepo.toggleIgnored(id(it)) }
        pipeline.invalidateUserData()
        repeat(5) {
            val pick = pipeline.pickRandom(tiny, ctx, emptyList())
            assertTrue(pick != null && pick.startsWith("c00"))
        }
        val filtered = wide.copy(statusFilter = setOf(ClimbStatusFilter.NEW))
        assertNull(pipeline.pickRandom(filtered, ctx, emptyList()))
        val loaded = pipeline.search(filtered, ctx).first
        assertTrue(pipeline.pickRandom(filtered, ctx, loaded) in loaded.map { it.uuid })
    }

    @Test
    fun `supported angles come from the brand-scoped catalogue`() {
        db.climb("t-35", brand = "touchstone", angle = 35)
        db.climb("t-40", brand = "touchstone", angle = 40)
        val supported = db.boardRepo.getSupportedAnglesForLayout(1, "touchstone")
        assertEquals(listOf(35, 40), supported)
        assertEquals(35, BoardAnglePicker.clampAngle(20, supported))
    }
}
