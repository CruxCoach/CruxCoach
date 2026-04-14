package com.cruxcoach.board

import com.cruxcoach.domain.board.IntensityZone
import com.cruxcoach.domain.board.IntensityZoneEngine
import com.cruxcoach.domain.board.SessionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntensityZoneEngineTest {

    @Test
    fun emptyListReturnsFallbackZones() {
        val zones = IntensityZoneEngine.computeZones(emptyList())
        assertFalse(zones.isPersonalized)
    }

    @Test
    fun smallListReturnsFallbackZones() {
        val zones = IntensityZoneEngine.computeZones(listOf(15.0, 18.0, 20.0))
        assertFalse(zones.isPersonalized)
    }

    @Test
    fun fiveDataPointsReturnsPersonalized() {
        val zones = IntensityZoneEngine.computeZones(listOf(10.0, 15.0, 18.0, 22.0, 26.0))
        assertTrue(zones.isPersonalized)
    }

    @Test
    fun percentilesCorrectFor20DataPoints() {
        // 20 evenly-spaced values from 10 to 29
        val diffs = (10..29).map { it.toDouble() }
        val zones = IntensityZoneEngine.computeZones(diffs)
        assertTrue(zones.isPersonalized)
        // p25 of 0..19 indices: rank = 0.25 * 19 = 4.75 → 10+4.75 = 14.75
        assertEquals(14.75, zones.warmUpCeiling, 0.01)
        // p75: rank = 0.75 * 19 = 14.25 → 10+14.25 = 24.25
        assertEquals(24.25, zones.optimalCeiling, 0.01)
    }

    @Test
    fun classifyAtBoundaries() {
        val zones = IntensityZoneEngine.computeZones(
            listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0)
        )
        // p25: rank=2.25 → 14.5, p75: rank=6.75 → 23.5
        assertEquals(IntensityZone.WARMUP, zones.classify(10.0))
        assertEquals(IntensityZone.WARMUP, zones.classify(14.5)) // at ceiling
        assertEquals(IntensityZone.OPTIMAL, zones.classify(20.0))
        assertEquals(IntensityZone.OPTIMAL, zones.classify(23.5)) // at ceiling
        assertEquals(IntensityZone.LIMIT, zones.classify(28.0))
    }

    @Test
    fun fallbackWithKnownGrade() {
        val zones = IntensityZoneEngine.computeFallbackZones("V5")
        // V5 → difficulty 21
        assertFalse(zones.isPersonalized)
        assertEquals(15.0, zones.warmUpCeiling, 0.01) // 21 - 6
        assertEquals(19.0, zones.optimalCeiling, 0.01) // 21 - 2
    }

    @Test
    fun fallbackWithNullGrade() {
        val zones = IntensityZoneEngine.computeFallbackZones(null)
        assertFalse(zones.isPersonalized)
        assertEquals(14.0, zones.warmUpCeiling, 0.01) // 20 - 6
        assertEquals(18.0, zones.optimalCeiling, 0.01) // 20 - 2
    }

    @Test
    fun sessionTypeWarmup() {
        val zones = IntensityZoneEngine.computeZones(
            listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0)
        )
        // All in warmup zone
        val diffs = listOf(10.0, 11.0, 12.0, 13.0, 14.0)
        assertEquals(SessionType.WARMUP_SESSION, IntensityZoneEngine.classifySession(diffs, zones))
    }

    @Test
    fun sessionTypeVolume() {
        val zones = IntensityZoneEngine.computeZones(
            listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0)
        )
        // Mostly in optimal zone
        val diffs = listOf(16.0, 18.0, 20.0, 22.0, 24.0, 10.0)
        assertEquals(SessionType.VOLUME_SESSION, IntensityZoneEngine.classifySession(diffs, zones))
    }

    @Test
    fun sessionTypeLimit() {
        val zones = IntensityZoneEngine.computeZones(
            listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0)
        )
        // >40% in limit zone
        val diffs = listOf(26.0, 27.0, 28.0, 20.0, 10.0)
        assertEquals(SessionType.LIMIT_SESSION, IntensityZoneEngine.classifySession(diffs, zones))
    }

    @Test
    fun sessionTypePyramid() {
        val zones = IntensityZoneEngine.computeZones(
            listOf(10.0, 12.0, 14.0, 16.0, 18.0, 20.0, 22.0, 24.0, 26.0, 28.0)
        )
        // Balanced across zones
        val diffs = listOf(10.0, 12.0, 18.0, 20.0, 26.0, 28.0)
        assertEquals(SessionType.PYRAMID_SESSION, IntensityZoneEngine.classifySession(diffs, zones))
    }

    @Test
    fun emptySessionClassifiesAsWarmup() {
        val zones = IntensityZoneEngine.computeFallbackZones(null)
        assertEquals(SessionType.WARMUP_SESSION, IntensityZoneEngine.classifySession(emptyList(), zones))
    }
}
