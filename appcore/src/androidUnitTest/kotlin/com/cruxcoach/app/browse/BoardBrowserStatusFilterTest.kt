package com.cruxcoach.app.browse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Persistence cases are copied from Android's BoardBrowserStatusFilterTest; the switch cases mirror BoardStatusFilterTest. */
class BoardBrowserStatusFilterTest {
    // MUST migrate, or a user's saved filter silently breaks on upgrade.

    @Test
    fun `empty and ALL parse to no constraint`() {
        assertEquals(emptySet(), parseStatusFilter(""))
        assertEquals(emptySet(), parseStatusFilter("ALL"))
        assertEquals(emptySet(), parseStatusFilter("   "))
    }

    @Test
    fun `legacy UNSENT migrates to NEW plus ATTEMPTED`() {
        assertEquals(
            setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED),
            parseStatusFilter("UNSENT")
        )
    }

    @Test
    fun `legacy single-select tokens parse to their bucket`() {
        assertEquals(setOf(ClimbStatusFilter.SENT), parseStatusFilter("SENT"))
        assertEquals(setOf(ClimbStatusFilter.NEW), parseStatusFilter("NEW"))
        assertEquals(setOf(ClimbStatusFilter.ATTEMPTED), parseStatusFilter("ATTEMPTED"))
    }

    @Test
    fun `comma-joined multi-select parses to a union`() {
        assertEquals(
            setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.SENT),
            parseStatusFilter("NEW,SENT")
        )
        // tolerate stray whitespace
        assertEquals(
            setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.SENT),
            parseStatusFilter("NEW, SENT")
        )
    }

    @Test
    fun `unknown tokens are ignored, not crashed on`() {
        assertEquals(setOf(ClimbStatusFilter.NEW), parseStatusFilter("NEW,BOGUS"))
        assertEquals(emptySet(), parseStatusFilter("BOGUS"))
    }

    @Test
    fun `serialize empty set yields empty string`() {
        assertEquals("", serializeStatusFilter(emptySet()))
    }

    @Test
    fun `serialize then parse round-trips every combination`() {
        val all = ClimbStatusFilter.entries.toSet()
        // power set
        for (mask in 0 until (1 shl all.size)) {
            val subset = all.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }.toSet()
            assertEquals(subset, parseStatusFilter(serializeStatusFilter(subset)))
        }
    }

    @Test
    fun `exclude sent edits the one status set`() {
        val n = ClimbStatusFilter.NEW; val a = ClimbStatusFilter.ATTEMPTED; val s = ClimbStatusFilter.SENT
        assertFalse(statusFilterExcludesSent(emptySet()))
        assertEquals(setOf(n, a), statusFilterWithSentExcluded(emptySet(), true))
        assertTrue(statusFilterExcludesSent(setOf(n, a)))
        assertEquals(setOf(n, a), statusFilterWithSentExcluded(setOf(s), true), "only SENT selected must not reset to all")
        assertEquals(setOf(a), statusFilterWithSentExcluded(setOf(a, s), true), "narrower unsent choice is preserved")
        assertEquals(emptySet(), statusFilterWithSentExcluded(setOf(n, a), false), "all three buckets collapse to no constraint")
        assertEquals(setOf(a, s), statusFilterWithSentExcluded(setOf(a), false))
        assertFalse(statusFilterExcludesSent(setOf(a, s)))
    }
}
