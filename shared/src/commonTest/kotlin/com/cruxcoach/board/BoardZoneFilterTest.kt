package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.BoardZoneFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardZoneFilterTest {

    private val xy = mapOf(
        100 to (10L to 10L),
        101 to (20L to 30L),
        102 to (40L to 50L),
        103 to (80L to 90L)
    )

    @Test
    fun zoneFromCornersNormalizesDiagonalOrder() {
        val zone = BoardZoneFilter.zoneFromCorners(40, 10, 10, 50)
        assertEquals(BoardZone(minX = 10, maxX = 40, minY = 10, maxY = 50), zone)
    }

    @Test
    fun zoneContainsIsInclusive() {
        val zone = BoardZone(10, 40, 10, 50)
        assertTrue(zone.contains(10, 10))
        assertTrue(zone.contains(40, 50))
        assertFalse(zone.contains(41, 30))
        assertFalse(zone.contains(30, 9))
    }

    @Test
    fun climbFullyInsideMatches() {
        val zone = BoardZone(10, 40, 10, 50)
        assertTrue(BoardZoneFilter.climbInZone("p100r12p101r13p102r14", xy, zone))
    }

    @Test
    fun climbWithOneHoldOutsideDoesNotMatch() {
        val zone = BoardZone(10, 40, 10, 50)
        assertFalse(BoardZoneFilter.climbInZone("p100r12p103r14", xy, zone))
    }

    @Test
    fun unknownPlacementCountsAsOutside() {
        val zone = BoardZone(0, 1000, 0, 1000)
        assertFalse(BoardZoneFilter.climbInZone("p100r12p999r13", xy, zone))
    }

    @Test
    fun emptyFramesDoNotMatch() {
        val zone = BoardZone(0, 1000, 0, 1000)
        assertFalse(BoardZoneFilter.climbInZone("", xy, zone))
    }
}
