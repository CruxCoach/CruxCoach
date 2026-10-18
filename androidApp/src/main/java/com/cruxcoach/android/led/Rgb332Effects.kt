package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import kotlin.math.roundToInt

/**
 * Colour maths on RGB332 bytes for the LED animations.
 *
 * Every helper takes and returns a packed RGB332 byte (`r3<<5 | g3<<2 | b2`) —
 * the currency of the whole BLE send path — so an animation can never produce a
 * colour the LEDs cannot physically show, and no conversion happens at send
 * time.
 *
 * Hue rotation runs through 8-bit RGB via bit replication and back through
 * [rgb888ToRgb332], a round-to-nearest quantiser matched to the levels the
 * board decodes, quantised to [HUE_STEPS] discrete steps per revolution.
 * Discrete steps are what make the whole design cheap: a frame's colour is a
 * table lookup, and an animation that lands on the same colours as the previous
 * frame costs zero Bluetooth traffic (the engine diffs frames).
 */
object Rgb332Effects {

    /** Distinct hue offsets per full rotation — 12° apart. */
    const val HUE_STEPS: Int = 30

    val HUE_STEP_DEGREES: Int = 360 / HUE_STEPS

    /**
     * Brightness of each step of one [PulseAnimation] cycle, in percent, mirroring
     * around the trough in the middle so the loop has no seam. The bottom sits at
     * 40 %, never 0: a pulsing route must not read as "hold missing".
     */
    val PULSE_LEVELS = intArrayOf(100, 85, 70, 55, 40, 55, 70, 85)

    /**
     * [HUE_STEPS] × 256 hue-rotation table, built on first use. Colour-space
     * conversion is ~20 flops, so this is paid once per process instead of once
     * per LED per frame.
     */
    private val hueLut: IntArray by lazy {
        IntArray(HUE_STEPS * 256).also { table ->
            for (step in 0 until HUE_STEPS) {
                for (byte in 0..0xFF) {
                    table[step * 256 + byte] = rotateHueUncached(byte, step)
                }
            }
        }
    }

    /** Rotate [byte]'s hue by [step] × 12°. [step] wraps, so any Int is safe. */
    fun rotateHue(byte: Int, step: Int): Int =
        hueLut[wrapStep(step) * 256 + (byte and 0xFF)]

    /**
     * Scale [byte]'s brightness to [percent]. A channel that is on stays on at
     * one quantum minimum (a dimmed blue would otherwise blink out at the bottom
     * of a pulse), and nothing is ever brighter than the static colour, so an
     * animation cannot draw more power than an ordinary climb send.
     */
    fun scaleBrightness(byte: Int, percent: Int): Int {
        if (byte == 0 || percent >= 100) return byte
        val p = percent.coerceAtLeast(0)
        val r3 = (byte shr 5) and 0x07
        val g3 = (byte shr 2) and 0x07
        val b2 = byte and 0x03
        val r = if (r3 == 0) 0 else ((r3 * p + 50) / 100).coerceIn(1, r3)
        val g = if (g3 == 0) 0 else ((g3 * p + 50) / 100).coerceIn(1, g3)
        val b = if (b2 == 0) 0 else ((b2 * p + 50) / 100).coerceIn(1, b2)
        return (r shl 5) or (g shl 2) or b
    }

    /** [byte] at the pulse level for [tick]. */
    fun pulse(byte: Int, tick: Int): Int =
        scaleBrightness(byte, PULSE_LEVELS[wrap(tick, PULSE_LEVELS.size)])

    /**
     * Shift *every* colour in [base] by the same hue [step] and return the frame.
     *
     * This is the shared core of the "start from the static colours and shift
     * them all by the same amount" animations ([ChaoticStrobeAnimation],
     * [RainbowWaveAnimation]): a rigid rotation preserves the angular distance
     * between colours, so Start, Hand, Top and Foot stay visually distinct for
     * the whole animation instead of converging the way independent random hues
     * would.
     *
     * RGB332 is coarse enough that two shifted colours can still quantise onto
     * each other (a red and an orange a step apart land on the same byte), so a
     * colliding colour is deterministically nudged up the byte space until free.
     * Determinism matters: the same tick must always produce the same frame.
     */
    fun shiftedFrame(base: LitFrame, step: Int): List<Pair<Int, Int>> {
        val remap = HashMap<Int, Int>(6)
        val taken = HashSet<Int>(6)
        val frame = ArrayList<Pair<Int, Int>>(base.leds.size)
        for (led in base.leds) {
            val mapped = remap[led.baseColor] ?: run {
                var candidate = rotateHue(led.baseColor, step)
                var guard = 0
                while (!taken.add(candidate) && guard < 0xFF) {
                    candidate = (candidate + 1) and 0xFF
                    guard++
                }
                remap[led.baseColor] = candidate
                candidate
            }
            frame += led.position to mapped
        }
        return frame
    }

    /** Non-negative modulo for possibly negative ticks/indices. */
    fun wrap(value: Int, modulus: Int): Int = ((value % modulus) + modulus) % modulus

    private fun wrapStep(step: Int): Int = wrap(step, HUE_STEPS)

    /**
     * Bit-replicating RGB332 → 8-bit decode. Mirrors the shared encoder's
     * `rgb332ToRgb888`, which is `internal` to `:shared` and so not visible here.
     */
    private fun rgb332ToRgb888(byte: Int): Triple<Int, Int, Int> {
        val r3 = (byte shr 5) and 0x07
        val g3 = (byte shr 2) and 0x07
        val b2 = byte and 0x03
        val r8 = (r3 shl 5) or (r3 shl 2) or (r3 shr 1)
        val g8 = (g3 shl 5) or (g3 shl 2) or (g3 shr 1)
        val b8 = (b2 shl 6) or (b2 shl 4) or (b2 shl 2) or b2
        return Triple(r8, g8, b8)
    }

    /**
     * Re-quantise an 8-bit colour to the RGB332 byte whose decoded channels sit
     * nearest the requested brightness.
     *
     * `BoardPacketEncoder.encodeColor` floor-divides into flat 32/64 buckets
     * that do not line up with the bit-replicated levels the board decodes
     * (blue 0/85/170/255, red/green 0/36/…/255): every channel switch landed a
     * quantum late, and an ideal blue of 43–63 encoded as outright black — mid
     * rotation a dimming blue snapped out and popped back, most visibly on the
     * two-bit blue channel where one quantum is a third of full brightness.
     * Rounding to the nearest decoded level halves the error and removes the
     * snap. The shared encoder is deliberately left alone: the static send path
     * and hex parsing must keep the exact bytes they produce today.
     */
    internal fun rgb888ToRgb332(r: Int, g: Int, b: Int): Int =
        ((r * 7 + 127) / 255 shl 5) or ((g * 7 + 127) / 255 shl 2) or ((b * 3 + 127) / 255)

    private fun rotateHueUncached(byte: Int, step: Int): Int {
        val (r, g, b) = rgb332ToRgb888(byte)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        // Greys and black carry no hue — rotating them would invent one out of
        // rounding noise, and white feet/magenta must not drift.
        if (delta == 0) return byte

        // The divisions here must be floating point: `(b - r) / delta` with two
        // Ints truncates toward zero, which would collapse every hue in a sector
        // onto its boundary and turn a rotation into a handful of flat colours.
        var hue = when (max) {
            r -> 60.0 * ((g - b).toDouble() / delta)
            g -> 60.0 * ((b - r).toDouble() / delta + 2.0)
            else -> 60.0 * ((r - g).toDouble() / delta + 4.0)
        }
        if (hue < 0) hue += 360.0
        hue = (hue + step * HUE_STEP_DEGREES) % 360.0

        val saturation = delta.toDouble() / max
        val sectorFloat = hue / 60.0
        val sector = sectorFloat.toInt() % 6
        val within = sectorFloat - sectorFloat.toInt()
        val v = max.toDouble()
        val p = v * (1.0 - saturation)
        val q = v * (1.0 - saturation * within)
        val t = v * (1.0 - saturation * (1.0 - within))
        val (nr, ng, nb) = when (sector) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return rgb888ToRgb332(nr.roundToInt(), ng.roundToInt(), nb.roundToInt())
    }
}
