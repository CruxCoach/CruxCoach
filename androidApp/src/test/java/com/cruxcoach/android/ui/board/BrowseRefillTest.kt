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
    @Test
    fun `overlapping pages refill to unique depth and preserve source offset`() = runTest {
        val offsets = mutableListOf<Int>()
        val (rows, offset, exhausted) = refillBrowsePages(5) { cursor ->
            offsets += cursor
            when (cursor) {
                0 -> Triple(climbs(0..2), 3, false)
                3 -> Triple(climbs(2..3), 5, false)
                else -> Triple(climbs(3..4), 7, true)
            }
        }
        assertEquals(climbs(0..4), rows)
        assertEquals(listOf(0, 3, 5), offsets)
        assertEquals(7, offset) // Never derive the SQL offset from unique row count.
        assertTrue(exhausted)
    }

    @Test
    fun `duplicates on the first page cannot reach Compose`() = runTest {
        val reportedUuid = "3e99addb94d54e089a1450edc77fd62b"
        val climb = TestClimb.stats(uuid = reportedUuid)
        val (rows, offset, _) = refillBrowsePages(0) {
            Triple(listOf(climb, climb), 2, true)
        }
        assertEquals(listOf(climb), rows)
        assertEquals(2, offset)
    }

    @Test
    fun `append preserves visible identity and ignores repeated incoming rows`() {
        val first = TestClimb.stats(uuid = "0f6d63dbcf74438b82bdbb4c7035d7ec")
        val next = TestClimb.stats(uuid = "next")
        assertEquals(
            listOf(first, next),
            mergeBrowseClimbs(listOf(first), listOf(first.copy(name = "updated"), next, next)),
        )
    }

    @Test
    fun `non advancing duplicate page cannot loop forever`() = runTest {
        var calls = 0
        val (rows, _, _) = refillBrowsePages(5) {
            calls++
            Triple(climbs(0..0), 1, false)
        }
        assertEquals(climbs(0..0), rows)
        assertEquals(2, calls)
    }

}
