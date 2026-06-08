package com.cruxcoach.android.data

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardPacketEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Regression for the Aurora-family BLE-send colour fallback.
 *
 * When a synced catalogue chunk omits `placement_roles`, the send path resolves
 * each hold via `standardFor(brand).toRoleColorMap()[roleId] ?:
 * BoardPacketEncoder.roleToColor(roleId)`, and the on-screen render via
 * `ledColors.colorForRole(roleId)`. Aurora-family frames carry role codes 1-4;
 * before the fix none of these resolvers recognised them, so every Aurora hold
 * lit white (0xFF). These tests lock in that Aurora codes now resolve to a real
 * colour.
 */
class AuroraFallbackColorsTest {

    private val white = 0xFF

    @Test
    fun standardForFallbackMap_coversAuroraRoleCodes() {
        val colors = LedHoldColors.standardFor(BoardBrand.TENSION)
        val map = colors.toRoleColorMap()
        // 1=start, 2=middle/hand, 3=finish, 4=foot → the Aurora palette.
        assertEquals(colors.start, map[1])
        assertEquals(colors.hand, map[2])
        assertEquals(colors.finish, map[3])
        assertEquals(colors.foot, map[4])
        listOf(1, 2, 3, 4).forEach { assertNotEquals(white, map[it]) }
    }

    @Test
    fun sendFallback_resolvesAuroraHoldsToColour_notWhite() {
        // Models BoardBleConnection.sendClimb resolution with placement_roles absent.
        val fallback = LedHoldColors.standardFor(BoardBrand.TENSION).toRoleColorMap()
        for (role in 1..4) {
            val resolved = fallback[role] ?: BoardPacketEncoder.roleToColor(role)
            assertNotEquals("Aurora role $role must not light white", white, resolved)
        }
    }

    @Test
    fun onScreenRender_resolvesAuroraHoldsToColour_notWhite() {
        // KilterBoardVisualization renders Aurora boards via colorForRole(roleId).
        val palette = LedHoldColors.kilterStandard()
        assertEquals(palette.start, palette.colorForRole(1)) // start
        assertEquals(palette.foot, palette.colorForRole(4))  // foot
        assertNotEquals(white, palette.colorForRole(1))
        assertNotEquals(white, palette.colorForRole(4))
    }
}
