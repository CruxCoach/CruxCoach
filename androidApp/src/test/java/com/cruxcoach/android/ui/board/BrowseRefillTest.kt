package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.TestClimb
import com.cruxcoach.data.repository.ClimbWithStats
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain-JVM tests for [refillBrowsePages] — the same-query refresh path that
 * keeps the browse list at its previously loaded depth when the user returns
 * from the climb detail / editor (a status refresh used to truncate the list
 * back to one page, clamping the restored scroll position).
 */
class BrowseRefillTest {

    private fun climbs(range: IntRange): List<ClimbWithStats> =
        range.map { TestClimb.stats(uuid = "uuid-$it") }

    /** Paged fake source: pages of [pageSize] rows over [total] climbs,
     *  counting how often it is hit. Mirrors fetchFiltered's triple shape. */
    private class FakeSource(val total: Int, val pageSize: Int = 50) {
        var calls = 0
        suspend fun fetch(offset: Int): Triple<List<ClimbWithStats>, Int, Boolean> {
            calls++
            val page = (offset until minOf(offset + pageSize, total))
                .map { TestClimb.stats(uuid = "uuid-$it") }
            return Triple(page, offset + page.size, offset + page.size >= total)
        }
    }

    @Test
    fun `targetSize zero fetches exactly one page`() = runTest {
        val source = FakeSource(total = 500)
        val (results, offset, exhausted) = refillBrowsePages(0) { source.fetch(it) }
        assertEquals(1, source.calls)
        assertEquals(50, results.size)
        assertEquals(50, offset)
        assertFalse(exhausted)
    }

    @Test
    fun `refill keeps appending pages until previous depth is reached`() = runTest {
        // User had loaded 3 pages (150 rows) before opening the detail.
        val source = FakeSource(total = 500)
        val (results, offset, exhausted) = refillBrowsePages(150) { source.fetch(it) }
        assertEquals(3, source.calls)
        assertEquals(150, results.size)
        assertEquals(150, offset)
        assertFalse(exhausted)
        // List identity: same rows in the same order as sequential loadMore.
        assertEquals(climbs(0 until 150).map { it.uuid }, results.map { it.uuid })
    }

    @Test
    fun `refill stops at exhaustion when fewer rows remain than the target`() = runTest {
        // The result set shrank below the previous depth (e.g. the logged
        // climb dropped out of a status-filtered list) — must not loop.
        val source = FakeSource(total = 120)
        val (results, _, exhausted) = refillBrowsePages(150) { source.fetch(it) }
        assertEquals(120, results.size)
        assertTrue(exhausted)
        assertEquals(3, source.calls)
    }

    @Test
    fun `refill breaks on a non-advancing empty fetcher instead of looping`() = runTest {
        // Belt-and-braces guard: a fetcher that reports neither rows nor
        // progress nor exhaustion must not spin forever.
        var calls = 0
        val (results, _, exhausted) = refillBrowsePages(100) { _ ->
            calls++
            Triple(emptyList(), 0, false)
        }
        assertTrue(results.isEmpty())
        assertFalse(exhausted)
        assertEquals(2, calls) // initial fetch + one refill probe, then break
    }

    @Test
    fun `target below page size degenerates to the single-page fetch`() = runTest {
        val source = FakeSource(total = 500)
        val (results, _, _) = refillBrowsePages(50) { source.fetch(it) }
        assertEquals(1, source.calls)
        assertEquals(50, results.size)
    }
}
