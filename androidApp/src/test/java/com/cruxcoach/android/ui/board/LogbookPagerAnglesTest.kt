package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ui.navigation.ClimbNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The logbook pager handed every neighbour the opened entry's angle: open a 40° entry, swipe to a neighbour
 * logged at 25°, and it opened at 40° (a quick log stored 40°). Each climb now carries its entry's angle.
 */
class LogbookPagerAnglesTest {

    @Test
    fun `each neighbour opens at the angle it was logged at`() {
        val nav = ClimbNavigationState()
        nav.setClimbsWithAngles(
            entries = listOf("a" to 40, "b" to 25, "c" to 30),
            openedUuid = "a",
            openedAngle = 40,
        )

        assertEquals(listOf("a", "b", "c"), nav.climbUuids)
        assertEquals(mapOf("a" to 40, "b" to 25, "c" to 30), nav.climbAngles)
        assertEquals(40, nav.angle)
    }

    @Test
    fun `a climb logged at several angles is listed once and opens at the tapped entry`() {
        val nav = ClimbNavigationState()
        nav.setClimbsWithAngles(
            entries = listOf("a" to 25, "b" to 30, "a" to 40, "b" to 35),
            openedUuid = "a",
            openedAngle = 40,
        )

        assertEquals(listOf("a", "b"), nav.climbUuids, "the pager keys pages by uuid: no duplicates")
        assertEquals(40, nav.climbAngles["a"], "the tapped entry wins for the tapped climb")
        assertEquals(30, nav.climbAngles["b"], "a neighbour opens at its first entry in the list")
    }

    @Test
    fun `a source without per-climb angles does not inherit the logbook's`() {
        val nav = ClimbNavigationState()
        nav.setClimbsWithAngles(listOf("a" to 40, "b" to 25), openedUuid = "a", openedAngle = 40)

        nav.climbUuids = listOf("b", "x")
        nav.angle = 35

        assertEquals(emptyMap<String, Int>(), nav.climbAngles)
    }
}
