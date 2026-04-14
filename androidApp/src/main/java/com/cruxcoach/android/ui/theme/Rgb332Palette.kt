package com.cruxcoach.android.ui.theme

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.cruxcoach.android.R

/**
 * Hardware-safe RGB332 color palette for Aurora Climbing board LEDs.
 * Each color is encoded as r3<<5 | g3<<2 | b2 (1 byte per pixel).
 *
 * Colors are grouped by [ColorFamily] for organized display in the picker.
 * RGB values use exact RGB332 expansion: R8 = R3*255/7, G8 = G3*255/7, B8 = B2*255/3.
 */

enum class ColorFamily(@param:StringRes val labelResId: Int) {
    ROT(R.string.color_family_red),
    ORANGE(R.string.color_family_orange),
    GELB(R.string.color_family_yellow),
    GRUEN(R.string.color_family_green),
    CYAN(R.string.color_family_cyan),
    BLAU(R.string.color_family_blue),
    VIOLETT(R.string.color_family_violet),
    MAGENTA(R.string.color_family_magenta),
    NEUTRAL(R.string.color_family_neutral)
}

data class Rgb332Color(
    @param:StringRes val nameResId: Int,
    val byte: Int,
    val displayColor: Color,
    val family: ColorFamily
)

val RGB332_PALETTE: List<Rgb332Color> = listOf(
    // Rot (6)
    Rgb332Color(R.string.color_red, 0xE0, Color(255, 0, 0), ColorFamily.ROT),
    Rgb332Color(R.string.color_dark_red, 0x80, Color(146, 0, 0), ColorFamily.ROT),
    Rgb332Color(R.string.color_wine_red, 0x60, Color(109, 0, 0), ColorFamily.ROT),
    Rgb332Color(R.string.color_salmon_pink, 0xE9, Color(255, 73, 85), ColorFamily.ROT),
    Rgb332Color(R.string.color_coral, 0xED, Color(255, 109, 85), ColorFamily.ROT),
    Rgb332Color(R.string.color_brick_red, 0xA0, Color(182, 0, 0), ColorFamily.ROT),
    // Orange (4)
    Rgb332Color(R.string.color_dark_orange, 0xE8, Color(255, 73, 0), ColorFamily.ORANGE),
    Rgb332Color(R.string.color_orange, 0xEC, Color(255, 109, 0), ColorFamily.ORANGE),
    Rgb332Color(R.string.color_light_orange, 0xF0, Color(255, 146, 0), ColorFamily.ORANGE),
    Rgb332Color(R.string.color_copper, 0xA8, Color(182, 73, 0), ColorFamily.ORANGE),
    // Gelb (4)
    Rgb332Color(R.string.color_yellow, 0xFC, Color(255, 255, 0), ColorFamily.GELB),
    Rgb332Color(R.string.color_gold_yellow, 0xF4, Color(255, 182, 0), ColorFamily.GELB),
    Rgb332Color(R.string.color_light_yellow, 0xFD, Color(255, 255, 85), ColorFamily.GELB),
    Rgb332Color(R.string.color_olive_yellow, 0xB4, Color(182, 182, 0), ColorFamily.GELB),
    // Gruen (7)
    Rgb332Color(R.string.color_green, 0x1C, Color(0, 255, 0), ColorFamily.GRUEN),
    Rgb332Color(R.string.color_lime_green, 0x9C, Color(146, 255, 0), ColorFamily.GRUEN),
    Rgb332Color(R.string.color_light_green, 0x7D, Color(109, 255, 85), ColorFamily.GRUEN),
    Rgb332Color(R.string.color_grass_green, 0x34, Color(36, 182, 0), ColorFamily.GRUEN),
    Rgb332Color(R.string.color_dark_green, 0x10, Color(0, 146, 0), ColorFamily.GRUEN),
    Rgb332Color(R.string.color_forest_green, 0x0C, Color(0, 109, 0), ColorFamily.GRUEN),
    Rgb332Color(R.string.color_emerald_green, 0x15, Color(0, 182, 85), ColorFamily.GRUEN),
    // Cyan/Tuerkis (5)
    Rgb332Color(R.string.color_cyan, 0x1F, Color(0, 255, 255), ColorFamily.CYAN),
    Rgb332Color(R.string.color_mint_green, 0x1E, Color(0, 255, 170), ColorFamily.CYAN),
    Rgb332Color(R.string.color_turquoise, 0x1A, Color(0, 219, 170), ColorFamily.CYAN),
    Rgb332Color(R.string.color_teal, 0x12, Color(0, 146, 170), ColorFamily.CYAN),
    Rgb332Color(R.string.color_aquamarine, 0x5F, Color(73, 255, 255), ColorFamily.CYAN),
    // Blau (5)
    Rgb332Color(R.string.color_blue, 0x03, Color(0, 0, 255), ColorFamily.BLAU),
    Rgb332Color(R.string.color_dark_blue, 0x02, Color(0, 0, 170), ColorFamily.BLAU),
    Rgb332Color(R.string.color_azure, 0x0B, Color(0, 73, 255), ColorFamily.BLAU),
    Rgb332Color(R.string.color_sky_blue, 0x57, Color(73, 182, 255), ColorFamily.BLAU),
    Rgb332Color(R.string.color_cornflower_blue, 0x4F, Color(73, 109, 255), ColorFamily.BLAU),
    // Violett (4)
    Rgb332Color(R.string.color_violet, 0x63, Color(109, 0, 255), ColorFamily.VIOLETT),
    Rgb332Color(R.string.color_purple, 0x83, Color(146, 0, 255), ColorFamily.VIOLETT),
    Rgb332Color(R.string.color_indigo, 0x42, Color(73, 0, 170), ColorFamily.VIOLETT),
    Rgb332Color(R.string.color_lilac, 0xB3, Color(182, 146, 255), ColorFamily.VIOLETT),
    // Magenta/Rosa (5)
    Rgb332Color(R.string.color_magenta, 0xE3, Color(255, 0, 255), ColorFamily.MAGENTA),
    Rgb332Color(R.string.color_pink, 0xEA, Color(255, 73, 170), ColorFamily.MAGENTA),
    Rgb332Color(R.string.color_rose, 0xEE, Color(255, 109, 170), ColorFamily.MAGENTA),
    Rgb332Color(R.string.color_raspberry, 0xC2, Color(219, 0, 170), ColorFamily.MAGENTA),
    Rgb332Color(R.string.color_orchid, 0xF3, Color(255, 146, 255), ColorFamily.MAGENTA),
    // Neutral (2)
    Rgb332Color(R.string.color_white, 0xFF, Color(255, 255, 255), ColorFamily.NEUTRAL),
    Rgb332Color(R.string.color_light_gray, 0xB6, Color(182, 182, 170), ColorFamily.NEUTRAL),
)

/** Grouped palette for the color picker UI. */
val RGB332_PALETTE_BY_FAMILY: Map<ColorFamily, List<Rgb332Color>> =
    RGB332_PALETTE.groupBy { it.family }

private val RGB332_LOOKUP: Map<Int, Color> =
    RGB332_PALETTE.associate { it.byte to it.displayColor }

private val RGB332_NAME_RES_LOOKUP: Map<Int, Int> =
    RGB332_PALETTE.associate { it.byte to it.nameResId }

/**
 * Convert an RGB332 byte value to a Compose Color for display.
 * Decodes the 3-3-2 bit packing back to 8-bit RGB.
 */
fun rgb332ToComposeColor(byte: Int): Color {
    return RGB332_LOOKUP[byte] ?: run {
        val r3 = (byte shr 5) and 0x07
        val g3 = (byte shr 2) and 0x07
        val b2 = byte and 0x03
        Color((r3 * 255) / 7, (g3 * 255) / 7, (b2 * 255) / 3)
    }
}

/** Returns the localized color name for a palette byte, or the "Custom" string for unknown bytes. */
fun rgb332ColorName(context: Context, byte: Int): String {
    val resId = RGB332_NAME_RES_LOOKUP[byte]
    return if (resId != null) context.getString(resId) else context.getString(R.string.color_custom)
}
