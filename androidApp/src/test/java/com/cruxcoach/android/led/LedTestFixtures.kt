package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import com.cruxcoach.android.ble.LitLed
import com.cruxcoach.domain.board.HoldRole

/**
 * Shared fixtures for the LED animation tests.
 *
 * Colours are RGB332 bytes throughout, exactly as the BLE send path carries them:
 * `r3 << 5 | g3 << 2 | b2`.
 */

internal val RED = 0xE0
internal val GREEN = 0x1C
internal val BLUE = 0x03
internal val WHITE = 0xFF

/** Four hues on four LEDs — start, hand, top, foot, the readable-wall baseline. */
internal fun fourRoleFrame() = LitFrame(
    listOf(
        LitLed(position = 11, roleClass = HoldRole.START, baseColor = RED),
        LitLed(position = 22, roleClass = HoldRole.HAND, baseColor = GREEN),
        LitLed(position = 33, roleClass = HoldRole.FINISH, baseColor = BLUE),
        LitLed(position = 44, roleClass = HoldRole.FOOT, baseColor = WHITE),
    )
)

/** [count] identical hand LEDs — for frame-size and pacing assertions. */
internal fun litFrameOf(count: Int, color: Int = GREEN) =
    LitFrame((1..count).map { LitLed(position = it, roleClass = HoldRole.HAND, baseColor = color) })

internal fun LitFrame.colorAt(position: Int): Int = leds.first { it.position == position }.baseColor

internal fun List<Pair<Int, Int>>.colors(): List<Int> = map { it.second }

internal fun List<Pair<Int, Int>>.positions(): List<Int> = map { it.first }

/** One RGB332 channel: shift 5 = red (3 bits), 2 = green (3 bits), 0 = blue (2 bits). */
internal fun channel(byte: Int, shift: Int): Int = when (shift) {
    5 -> (byte shr 5) and 0x07
    2 -> (byte shr 2) and 0x07
    else -> byte and 0x03
}

internal val CHANNEL_SHIFTS = listOf(5, 2, 0)
