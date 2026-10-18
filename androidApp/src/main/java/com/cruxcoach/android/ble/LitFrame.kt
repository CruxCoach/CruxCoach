package com.cruxcoach.android.ble

/**
 * One LED that is currently lit on the board.
 *
 * [baseColor] is the colour the climb was actually sent with — the catalogue's
 * `placement_roles.led_color` when the board ships one, otherwise the user's
 * LED-colour settings — so anything deriving from it (the LED animations) works
 * on what the board really shows instead of on a palette the user cannot see.
 *
 * [roleClass] is [com.cruxcoach.domain.board.HoldRole.roleClass]-folded, so
 * Aurora codes 1-4, Kilter 12-15 and the route variants 42-45 all collapse onto
 * one role — the key palette-swapping animations use.
 */
data class LitLed(val position: Int, val roleClass: Int, val baseColor: Int)

/**
 * Everything lit on the board right now, as sent by the last successful
 * [BoardBleConnection.sendClimb].
 *
 * This is also the palette the LED animations transform. A derived frame always
 * carries *all* lit LEDs, because an Aurora packet is the complete new board
 * state (`encodeClear()` is an empty `encodeClimb`) — a frame that omitted an LED
 * would switch it off.
 */
data class LitFrame(val leds: List<LitLed>) {

    /** The frame with no animation applied — the static "LED colors" look. */
    fun staticFrame(): List<Pair<Int, Int>> = leds.map { it.position to it.baseColor }
}
