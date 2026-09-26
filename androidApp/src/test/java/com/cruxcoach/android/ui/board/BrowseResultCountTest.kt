package com.cruxcoach.android.ui.board

import com.cruxcoach.android.fakes.TestClimb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrowseResultCountTest {
    @Test
    fun `whole-set filters retain every matching problem beyond the first fifty`() = runTest {
        val source = (0 until 125).map { TestClimb.stats(uuid = "c-$it") }
        val (rows, offset, exhausted) = completeBrowseResults(source + source.take(5))
        assertEquals(source, rows)
        assertEquals(125, offset)
        assertTrue(exhausted)
        assertEquals(125L, countBrowseMatches { completeBrowseResults(source) })
    }

    @Test
    fun `count follows displayed identities through empty and overlapping pages`() = runTest {
        val calls = mutableListOf<Int>()
        val total = countBrowseMatches { offset ->
            calls += offset
            when (offset) {
                0 -> Triple(listOf(TestClimb.stats(uuid = "a")), 50, false)
                50 -> Triple(emptyList(), 100, false) // Every raw row was filtered out.
                else -> Triple(listOf(TestClimb.stats(uuid = "a"), TestClimb.stats(uuid = "b")), 102, true)
            }
        }
        assertEquals(2L, total)
        assertEquals(listOf(0, 50, 100), calls)
    }

    @Test
    fun `zero matches are an exact zero and stalled scans cannot report a false total`() = runTest {
        assertEquals(0L, countBrowseMatches { Triple(emptyList(), 0, true) })
        assertFailsWith<IllegalStateException> {
            countBrowseMatches { Triple(emptyList(), 0, false) }
        }
    }

    @Test
    fun `cancelled count cannot publish a partial total`() = runTest {
        assertFailsWith<CancellationException> {
            countBrowseMatches { offset ->
                if (offset > 0) throw CancellationException("filter changed")
                Triple(listOf(TestClimb.stats(uuid = "a")), 1, false)
            }
        }
    }
}
