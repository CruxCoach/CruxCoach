package com.cruxcoach.app.ble

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole

/** RGB332 role colours; port of Android's LedHoldColors (data/UserPreferences.kt). */
data class LedHoldColors(
    val start: Int = CRUXCOACH_START,
    val hand: Int = CRUXCOACH_HAND,
    val finish: Int = CRUXCOACH_FINISH,
    val foot: Int = CRUXCOACH_FOOT,
) {
    companion object {
        const val CRUXCOACH_START: Int = 0xE3
        const val CRUXCOACH_HAND: Int = 0x03
        const val CRUXCOACH_FINISH: Int = 0x1C
        const val CRUXCOACH_FOOT: Int = 0xE0

        const val KILTER_START: Int = 0x1C
        const val KILTER_HAND: Int = 0x1F
        const val KILTER_FINISH: Int = 0xE3
        const val KILTER_FOOT: Int = 0xF4

        const val AURORA_START: Int = 0x1C
        const val AURORA_HAND: Int = 0x03
        const val AURORA_FINISH: Int = 0xE0
        const val AURORA_FOOT: Int = 0xE3

        const val SOILL_HAND: Int = 0xE3
        const val SOILL_FINISH: Int = 0xFF
        const val SOILL_FOOT: Int = 0x1F

        fun kilterStandard() = LedHoldColors(KILTER_START, KILTER_HAND, KILTER_FINISH, KILTER_FOOT)

        fun standardFor(brand: BoardBrand): LedHoldColors = when (brand) {
            BoardBrand.KILTER -> kilterStandard()
            BoardBrand.TENSION, BoardBrand.GRASSHOPPER, BoardBrand.DECOY, BoardBrand.TOUCHSTONE ->
                LedHoldColors(AURORA_START, AURORA_HAND, AURORA_FINISH, AURORA_FOOT)
            BoardBrand.SOILL ->
                LedHoldColors(start = AURORA_START, hand = SOILL_HAND, finish = SOILL_FINISH, foot = SOILL_FOOT)
            BoardBrand.MOONBOARD -> LedHoldColors(start = 0x1C, hand = 0x03, finish = 0xE0, foot = 0x03)
            else -> LedHoldColors()
        }
    }

    fun toRoleColorMap(): Map<Int, Int> = mapOf(
        HoldRole.START to start,
        HoldRole.HAND to hand,
        HoldRole.FINISH to finish,
        HoldRole.FOOT to foot,
        HoldRole.ROUTE_START to start,
        HoldRole.ROUTE_HAND to hand,
        HoldRole.ROUTE_FINISH to finish,
        HoldRole.ROUTE_FOOT to foot,
        1 to start, 2 to hand, 3 to finish, 4 to foot,
    )
}

/**
 * Role colours in Android's priority order: the board's own
 * placement_roles.led_color map when present, else the user's Kilter colours on
 * Kilter, else the brand's standard palette.
 */
fun resolveRoleColors(
    brand: BoardBrand,
    catalogueRoleColors: Map<Int, Int>,
    kilterUserColors: LedHoldColors?,
): Map<Int, Int> = catalogueRoleColors.ifEmpty {
    (if (brand == BoardBrand.KILTER) kilterUserColors ?: LedHoldColors() else LedHoldColors.standardFor(brand))
        .toRoleColorMap()
}
