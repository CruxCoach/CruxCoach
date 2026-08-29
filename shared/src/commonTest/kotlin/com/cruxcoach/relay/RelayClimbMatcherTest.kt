package com.cruxcoach.relay

import com.cruxcoach.domain.relay.RelayClimbMatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelayClimbMatcherTest {

    /** A real catalogue row — 12 four-digit placements, two-digit Kilter roles. */
    private val swooped = "p1080r15p1110r15p1131r12p1146r12p1164r13p1202r13p1246r13" +
        "p1250r13p1282r13p1331r13p1351r13p1385r14"
    private val swoopedPlacements = setOf(
        1080, 1110, 1131, 1146, 1164, 1202, 1246, 1250, 1282, 1331, 1351, 1385,
    )

    @Test
    fun frame_length_range_brackets_the_real_string() {
        val range = RelayClimbMatcher.frameLengthRange(swoopedPlacements, 1, 2)

        assertTrue(swooped.length in range, "$range must contain ${swooped.length}")
        // Kilter roles are all two digits, so the upper bound is exact.
        assertEquals(swooped.length, range.last)
    }

    @Test
    fun frame_length_range_is_exact_when_the_role_width_is_known() {
        val range = RelayClimbMatcher.frameLengthRange(swoopedPlacements, 2, 2)

        assertEquals(swooped.length, range.first)
        assertEquals(swooped.length, range.last)
    }

    @Test
    fun frame_length_range_handles_mixed_placement_widths() {
        // "p9r1" (4) + "p10r1" (5) + "p100r1" (6) = 15 with one-digit roles.
        val range = RelayClimbMatcher.frameLengthRange(setOf(9, 10, 100), 1, 1)

        assertEquals(15, range.first)
        assertEquals(15, range.last)
    }

    @Test
    fun holds_match_ignores_roles_but_not_the_hold_set() {
        assertTrue(RelayClimbMatcher.holdsMatch(swooped, swoopedPlacements))
        // Same holds, every role changed — still the same climb on the wall.
        assertTrue(
            RelayClimbMatcher.holdsMatch(swooped.replace("r13", "r12"), swoopedPlacements)
        )
        // One hold short, one hold too many, one hold different.
        assertFalse(RelayClimbMatcher.holdsMatch(swooped, swoopedPlacements - 1385))
        assertFalse(RelayClimbMatcher.holdsMatch(swooped, swoopedPlacements + 999))
        assertFalse(
            RelayClimbMatcher.holdsMatch(swooped, swoopedPlacements - 1385 + 1386)
        )
    }

    @Test
    fun anchor_pattern_cannot_match_a_longer_placement_id() {
        val pattern = RelayClimbMatcher.anchorPattern(108)

        assertEquals("%p108r%", pattern)
        // The trailing "r" is what keeps p108 from matching p1080.
        assertFalse(swooped.contains("p108r"))
    }
}
