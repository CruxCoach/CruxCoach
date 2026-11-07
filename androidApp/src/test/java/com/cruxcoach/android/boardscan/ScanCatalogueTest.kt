package com.cruxcoach.android.boardscan

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanCatalogueTest {

    private val geo = ScanGeometry(
        holeIds = intArrayOf(1090, 1100, 1200, 1300, 1400),
        holeX = floatArrayOf(0f, 4f, 8f, 4f, 0f),
        holeY = floatArrayOf(0f, 4f, 8f, 12f, 16f),
        spacing = 4.0, extent = doubleArrayOf(-2.0, 10.0, -2.0, 18.0), metresPerUnit = 0.0254,
    )

    @Test
    fun parsesAFrameIntoHolesAndRoles() {
        val c = assertNotNull(parseScanClimb("u1", "p1090r12p1200r13p1400r14p1100r15", geo.holeOf, 7))
        assertContentEquals(shortArrayOf(0, 2, 4, 1), c.holes)
        assertContentEquals(byteArrayOf(Roles.START.toByte(), Roles.HAND.toByte(), Roles.FINISH.toByte(), Roles.FOOT.toByte()), c.roles)
        assertEquals(7, c.ascents)
    }

    @Test
    fun routeRolesFoldToTheBoulderRoles() {
        val route = assertNotNull(parseScanClimb("u", "p1090r42p1200r43p1400r44", geo.holeOf, 0))
        val boulder = assertNotNull(parseScanClimb("u", "p1090r12p1200r13p1400r14", geo.holeOf, 0))
        assertContentEquals(boulder.holes, route.holes)
        assertContentEquals(boulder.roles, route.roles)
    }

    @Test
    fun theApiFormIsLeftOutBecauseItsNumbersAreHoleIds() {
        assertNull(parseScanClimb("u", "h1090p12h1200p13h1400p14", geo.holeOf, 0))
    }

    @Test
    fun kilterCataloguesDoNotDependOnTheAngleButMoonBoardOnesDo() {
        assertEquals(ScanBoard("kilter", 1, 7, 40).catalogueKey, ScanBoard("kilter", 1, 7, 25).catalogueKey)
        assertTrue(ScanBoard("moonboard", 2, 0, 40).catalogueKey != ScanBoard("moonboard", 2, 0, 25).catalogueKey)
    }

    @Test
    fun aProblemWithAHoldOffThisBoardOrTooFewHoldsIsLeftOut() {
        assertNull(parseScanClimb("u", "p1090r12p9999r13p1400r14", geo.holeOf, 0), "hold not on this size")
        assertNull(parseScanClimb("u", "p1090r12p1400r14", geo.holeOf, 0), "two holds")
        assertNull(parseScanClimb("u", "p1090r12p1200r99p1400r14", geo.holeOf, 0), "unknown role")
        assertNull(parseScanClimb("u", "p1090r12p1200x13", geo.holeOf, 0), "malformed")
    }

    @Test
    fun theIndexPutsTheMostClimbedFirstAndKeepsUuidsAligned() {
        val climbs = listOf(
            assertNotNull(parseScanClimb("rare", "p1090r12p1200r13p1400r14", geo.holeOf, 3)),
            assertNotNull(parseScanClimb("popular", "p1100r12p1300r13p1400r14", geo.holeOf, 900)),
        )
        val (ix, uuids) = buildScanIndex(geo, climbs)
        assertEquals(2, ix.size)
        assertContentEquals(arrayOf("popular", "rare"), uuids)
        assertEquals(900, ix.ascents[0])
        assertTrue(abs(ix.minHoleDist - hypot4()) < 1e-9)
    }

    private fun hypot4() = kotlin.math.hypot(4.0, 4.0)

    @Test
    fun moonBoardHolesSitOnTheFittedGrid() {
        // a synthetic 11 x 18 layout: column c at x = 0.1 + 0.08 c, row r at y = 0.95 - 0.05 r (image fractions)
        val holds = (1..198).map { id ->
            val c = (id - 1) % 11
            val r = (id - 1) / 11
            MoonBoardHold(id, (0.1 + 0.08 * c).toFloat(), (0.95 - 0.05 * r).toFloat(), occupied = (c + r) % 3 == 0)
        }
        val g = moonBoardGeometry(holds, rows = 18, imageW = 1000, imageH = 1400)
        assertEquals(198, g.holeIds.size)
        // hold 1 is column 0, row 0: bottom left; y is up in image pixels
        assertTrue(abs(g.holeX[0] - 100.0) < 0.01)
        assertTrue(abs(g.holeY[0] - (1400 - 0.95 * 1400)) < 0.01)
        assertTrue(abs(g.holeX[12] - 180.0) < 0.01)
        assertTrue(abs(g.spacing - 70.0) < 0.01, "row step 0.05 x 1400 = 70 px is the smaller spacing")
        assertTrue(abs(g.metresPerUnit - 0.20 / 80.0) < 1e-9)
        assertEquals(0, g.holeOf[1])
    }

    @Test
    fun staticMarksParse() {
        val m = parseStaticMarks("613.84 650.95 46.5\n\n683.19 575.95 46.0\n")
        assertEquals(2, m.size)
        assertEquals(46.0, m[1].hue)
    }

    @Test
    fun onlyMeasuredBoardsAreSupported() {
        assertTrue(ScanBoard("kilter", 1, 7, 40).isSupported)
        assertTrue(ScanBoard("moonboard", 2, 0, 40).isSupported)
        assertTrue(ScanBoard("moonboard", 3, 0, 25).isSupported)
        assertTrue(!ScanBoard("moonboard", 4, 0, 40).isSupported, "Masters 2017: no coloured-hold marks yet")
        assertTrue(!ScanBoard("moonboard", 1, 0, 40).isSupported, "MoonBoard 2010 shares layout 1 with Kilter")
        assertTrue(!ScanBoard("tension", 1, 1, 40).isSupported)
    }
}
