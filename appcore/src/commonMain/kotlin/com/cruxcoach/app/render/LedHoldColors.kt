package com.cruxcoach.app.render

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole

/** RGB332 LED byte per hold role (port of Android's data/UserPreferences.kt LedHoldColors). */
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
        // Aurora-family role codes: 1=start, 2=hand, 3=finish, 4=foot.
        1 to start, 2 to hand, 3 to finish, 4 to foot,
    )

    /** Unknown roles light white (0xFF), as on Android. */
    fun colorForRole(roleId: Int): Int = when (HoldRole.roleClass(roleId)) {
        HoldRole.START -> start
        HoldRole.HAND -> hand
        HoldRole.FINISH -> finish
        HoldRole.FOOT -> foot
        else -> 0xFF
    }

    /** Opaque ARGB display colour of a role. */
    fun argbForRole(roleId: Int): Long = Rgb332Palette.toArgb(colorForRole(roleId))
}

enum class ColorFamily { ROT, ORANGE, GELB, GRUEN, CYAN, BLAU, VIOLETT, MAGENTA, NEUTRAL }

/** [nameKey] is the Android string resource name; Swift owns the EN/DE text. */
data class Rgb332Color(val nameKey: String, val byte: Int, val argb: Long, val family: ColorFamily)

/** Hardware-safe RGB332 picker palette (port of ui/theme/Rgb332Palette.kt): byte = r3<<5 | g3<<2 | b2. */
object Rgb332Palette {
    private fun c(name: String, byte: Int, r: Int, g: Int, b: Int, family: ColorFamily) =
        Rgb332Color(name, byte, argb(r, g, b), family)

    private fun argb(r: Int, g: Int, b: Int): Long =
        (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()

    val PALETTE: List<Rgb332Color> = listOf(
        c("color_red", 0xE0, 255, 0, 0, ColorFamily.ROT),
        c("color_dark_red", 0x80, 146, 0, 0, ColorFamily.ROT),
        c("color_wine_red", 0x60, 109, 0, 0, ColorFamily.ROT),
        c("color_salmon_pink", 0xE9, 255, 73, 85, ColorFamily.ROT),
        c("color_coral", 0xED, 255, 109, 85, ColorFamily.ROT),
        c("color_brick_red", 0xA0, 182, 0, 0, ColorFamily.ROT),
        c("color_dark_orange", 0xE8, 255, 73, 0, ColorFamily.ORANGE),
        c("color_orange", 0xEC, 255, 109, 0, ColorFamily.ORANGE),
        c("color_light_orange", 0xF0, 255, 146, 0, ColorFamily.ORANGE),
        c("color_copper", 0xA8, 182, 73, 0, ColorFamily.ORANGE),
        c("color_yellow", 0xFC, 255, 255, 0, ColorFamily.GELB),
        c("color_gold_yellow", 0xF4, 255, 182, 0, ColorFamily.GELB),
        c("color_light_yellow", 0xFD, 255, 255, 85, ColorFamily.GELB),
        c("color_olive_yellow", 0xB4, 182, 182, 0, ColorFamily.GELB),
        c("color_green", 0x1C, 0, 255, 0, ColorFamily.GRUEN),
        c("color_lime_green", 0x9C, 146, 255, 0, ColorFamily.GRUEN),
        c("color_light_green", 0x7D, 109, 255, 85, ColorFamily.GRUEN),
        c("color_grass_green", 0x34, 36, 182, 0, ColorFamily.GRUEN),
        c("color_dark_green", 0x10, 0, 146, 0, ColorFamily.GRUEN),
        c("color_forest_green", 0x0C, 0, 109, 0, ColorFamily.GRUEN),
        c("color_emerald_green", 0x15, 0, 182, 85, ColorFamily.GRUEN),
        c("color_cyan", 0x1F, 0, 255, 255, ColorFamily.CYAN),
        c("color_mint_green", 0x1E, 0, 255, 170, ColorFamily.CYAN),
        c("color_turquoise", 0x1A, 0, 219, 170, ColorFamily.CYAN),
        c("color_teal", 0x12, 0, 146, 170, ColorFamily.CYAN),
        c("color_aquamarine", 0x5F, 73, 255, 255, ColorFamily.CYAN),
        c("color_blue", 0x03, 0, 0, 255, ColorFamily.BLAU),
        c("color_dark_blue", 0x02, 0, 0, 170, ColorFamily.BLAU),
        c("color_azure", 0x0B, 0, 73, 255, ColorFamily.BLAU),
        c("color_sky_blue", 0x57, 73, 182, 255, ColorFamily.BLAU),
        c("color_cornflower_blue", 0x4F, 73, 109, 255, ColorFamily.BLAU),
        c("color_violet", 0x63, 109, 0, 255, ColorFamily.VIOLETT),
        c("color_purple", 0x83, 146, 0, 255, ColorFamily.VIOLETT),
        c("color_indigo", 0x42, 73, 0, 170, ColorFamily.VIOLETT),
        c("color_lilac", 0xB3, 182, 146, 255, ColorFamily.VIOLETT),
        c("color_magenta", 0xE3, 255, 0, 255, ColorFamily.MAGENTA),
        c("color_pink", 0xEA, 255, 73, 170, ColorFamily.MAGENTA),
        c("color_rose", 0xEE, 255, 109, 170, ColorFamily.MAGENTA),
        c("color_raspberry", 0xC2, 219, 0, 170, ColorFamily.MAGENTA),
        c("color_orchid", 0xF3, 255, 146, 255, ColorFamily.MAGENTA),
        c("color_white", 0xFF, 255, 255, 255, ColorFamily.NEUTRAL),
        c("color_light_gray", 0xB6, 182, 182, 170, ColorFamily.NEUTRAL),
    )

    const val CUSTOM_NAME_KEY = "color_custom"

    private val lookup: Map<Int, Rgb332Color> = PALETTE.associateBy { it.byte }

    /** Palette display colour when the byte is a named entry, else the exact 3-3-2 expansion. */
    fun toArgb(byte: Int): Long = lookup[byte]?.argb ?: run {
        val r3 = (byte shr 5) and 0x07
        val g3 = (byte shr 2) and 0x07
        val b2 = byte and 0x03
        argb((r3 * 255) / 7, (g3 * 255) / 7, (b2 * 255) / 3)
    }

    fun nameKey(byte: Int): String = lookup[byte]?.nameKey ?: CUSTOM_NAME_KEY
}
