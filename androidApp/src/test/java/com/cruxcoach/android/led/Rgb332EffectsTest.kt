package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import com.cruxcoach.android.ble.LitLed
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * The colour operations the effects are built from, on the byte layout the board
 * actually speaks (`r3 << 5 | g3 << 2 | b2`).
 *
 * The invariants here are the ones the animations quietly assume: that the hue
 * wheel closes, that dimming never crosses into "unlit", and that a shared shift
 * keeps a route's roles apart.
 */
class Rgb332EffectsTest {

    @Test
    fun hueRotation_wrapsAroundTheWheel() {
        val colours = listOf(RED, GREEN, BLUE, WHITE, 0xE1, 0x1B, 0x88, 0x07)
        for (color in colours) {
            for (step in listOf(0, 1, 7, 29)) {
                assertEquals(
                    Rgb332Effects.rotateHue(color, step),
                    Rgb332Effects.rotateHue(color, step + Rgb332Effects.HUE_STEPS),
                    "0x${color.toString(16)} at step $step must survive a whole revolution",
                )
                assertEquals(
                    Rgb332Effects.rotateHue(color, step),
                    Rgb332Effects.rotateHue(color, step - Rgb332Effects.HUE_STEPS),
                    "0x${color.toString(16)}: a negative step must wrap the same way",
                )
            }
        }
    }

    @Test
    fun hueRotation_movesThroughRealHues() {
        // A rotation that lost its fractional part (see the note in
        // `rotateHueUncached`) would snap everything to sector boundaries and give
        // a handful of flat colours instead of a wheel, so check that the path from
        // red towards green is actually walked.
        val red = Rgb332Effects.rotateHue(RED, 0)
        assertEquals(RED, red)
        val seen = (0 until Rgb332Effects.HUE_STEPS).map { Rgb332Effects.rotateHue(RED, it) }
        // RGB332 is coarser than a 30-step wheel, so a few adjacent steps land on
        // the same byte. Losing most of the wheel is the failure mode here.
        assertTrue(
            seen.distinct().size >= 20,
            "the wheel collapsed to ${seen.distinct().size} of ${Rgb332Effects.HUE_STEPS} steps: $seen",
        )
        assertTrue(seen[1] != seen[0] && seen[2] != seen[1], "the first steps do not move: $seen")
        // Rotating red a third of the way round reaches green's neighbourhood, and
        // a further third lands near blue — the wheel has to go somewhere.
        val greenish = Rgb332Effects.rotateHue(RED, 10)
        assertTrue(channel(greenish, 2) > 0, "red never picked up green: 0x${greenish.toString(16)}")
        val blueish = Rgb332Effects.rotateHue(RED, 20)
        assertTrue(channel(blueish, 0) > 0, "red never picked up blue: 0x${blueish.toString(16)}")
        assertEquals(BLUE, Rgb332Effects.rotateHue(BLUE, 0))
        assertEquals(GREEN, Rgb332Effects.rotateHue(GREEN, 0))
    }

    @Test
    fun hueRotation_isDefinedForEveryByteInThePalette() {
        for (byte in 0..0xFF) {
            for (step in listOf(0, 1, 13, 29, -7)) {
                val rotated = Rgb332Effects.rotateHue(byte, step)
                assertTrue(rotated in 0..0xFF, "0x${byte.toString(16)} at $step left the palette")
                assertEquals(
                    rotated,
                    Rgb332Effects.rotateHue(byte, step + Rgb332Effects.HUE_STEPS),
                    "0x${byte.toString(16)} at $step",
                )
            }
        }
    }

    @Test
    fun hueRotation_leavesTheTwoGreysAlone() {
        // RGB332 has exactly two channel-balanced bytes. Bit replication makes r3=7
        // and b2=3 both 255 and r3=0 and b2=0 both 0, and no other level of one
        // channel matches any level of another — so black and white are the only
        // colours without a hue to rotate. Feet are white, and they must stay white.
        for (grey in listOf(0x00, 0xFF)) {
            for (step in 0 until Rgb332Effects.HUE_STEPS) {
                assertEquals(grey, Rgb332Effects.rotateHue(grey, step))
            }
        }
    }

    @Test
    fun quantiser_roundsToTheNearestLevelTheBoardDisplays() {
        // Bit replication decodes r3=1 to 36 and b2=1 to 85, so those exact 8-bit
        // values must re-encode to level 1, and the bucket edges must sit at the
        // midpoints between decoded levels rather than at flat 32/64 divisions —
        // a floor encoder switched a quantum late and crushed dim blue to black.
        for (level in 0..7) {
            val v8 = (level shl 5) or (level shl 2) or (level shr 1)
            assertEquals(level, channel(Rgb332Effects.rgb888ToRgb332(v8, 0, 0), 5), "r level $level")
            assertEquals(level, channel(Rgb332Effects.rgb888ToRgb332(0, v8, 0), 2), "g level $level")
        }
        for (level in 0..3) {
            val v8 = (level shl 6) or (level shl 4) or (level shl 2) or level
            assertEquals(level, channel(Rgb332Effects.rgb888ToRgb332(0, 0, v8), 0), "b level $level")
        }
        // The 85/170 boundary sits at 127.5: 127 is still level 1, 128 is level 2.
        assertEquals(1, channel(Rgb332Effects.rgb888ToRgb332(0, 0, 127), 0))
        assertEquals(2, channel(Rgb332Effects.rgb888ToRgb332(0, 0, 128), 0))
        // The crush regression: dim blue a floor encoder used to turn black.
        assertEquals(1, channel(Rgb332Effects.rgb888ToRgb332(0, 0, 43), 0))
    }

    @Test
    fun hueRotation_doesNotSnapDimBlueToBlack() {
        // Pure blue rotated 108° lands on an ideal blue of roughly 50: the floor
        // encoder dropped it to level 0, so mid-rotation a blue hold blinked out
        // and popped back a step later. It must stay dimly lit instead.
        val rotated = Rgb332Effects.rotateHue(BLUE, 9)
        assertTrue(channel(rotated, 0) > 0, "blue went black at step 9: 0x${rotated.toString(16)}")
    }

    @Test
    fun brightnessScaling_isBoundedAndRespectsUnlitChannels() {
        for (color in listOf(RED, GREEN, BLUE, WHITE, 0x1B, 0x88, 0x01)) {
            for (percent in listOf(0, 1, 40, 99, 100, 250)) {
                val scaled = Rgb332Effects.scaleBrightness(color, percent)
                for (shift in CHANNEL_SHIFTS) {
                    val before = channel(color, shift)
                    val after = channel(scaled, shift)
                    if (before == 0) {
                        assertEquals(0, after, "0x${color.toString(16)} at $percent% lit a dark channel")
                    } else {
                        assertTrue(
                            after in 1..before,
                            "0x${color.toString(16)} at $percent% left a channel at $after",
                        )
                    }
                }
            }
        }
        assertEquals(0x00, Rgb332Effects.scaleBrightness(0x00, 70), "black stays black")
        assertEquals(RED, Rgb332Effects.scaleBrightness(RED, 100), "100% is the byte itself")
    }

    @Test
    fun pulse_isASeamlessBreathThatNeverGoesDark() {
        val levels = Rgb332Effects.PULSE_LEVELS
        assertEquals(100, levels.first(), "a breath starts from the colours the climb was sent with")
        assertTrue(levels.all { it <= 100 }, "nothing is ever brighter than the static colour")
        assertTrue(levels.all { it >= 25 }, "the bottom of the breath is ${levels.toList()}")
        for (index in levels.indices) {
            val next = levels[(index + 1) % levels.size]
            assertTrue(
                abs(levels[index] - next) <= 15,
                "the loop jumps from ${levels[index]}% to $next%",
            )
        }
        assertEquals(RED, Rgb332Effects.pulse(RED, 0))
        assertEquals(Rgb332Effects.pulse(RED, 3), Rgb332Effects.pulse(RED, 3 + levels.size))
        assertNotEquals(Rgb332Effects.pulse(RED, 3), Rgb332Effects.pulse(RED, 4))
        // A hold that goes fully dark for a frame reads as a hold that is not part
        // of the route, so even the dimmest blue has to stay lit.
        for (tick in 0 until levels.size) {
            assertTrue(channel(Rgb332Effects.pulse(BLUE, tick), 0) > 0, "blue went dark at tick $tick")
            assertTrue(channel(Rgb332Effects.pulse(WHITE, tick), 5) > 0, "white went dark at tick $tick")
        }
    }

    @Test
    fun wrap_handlesNegativeTicks() {
        assertEquals(0, Rgb332Effects.wrap(0, 8))
        assertEquals(7, Rgb332Effects.wrap(-1, 8))
        assertEquals(0, Rgb332Effects.wrap(-8, 8))
        assertEquals(6, Rgb332Effects.wrap(-10, 8))
        assertEquals(3, Rgb332Effects.wrap(3, 8))
    }

    @Test
    fun shiftedFrame_keepsTheClimbAndSeparatesItsRoles() {
        val base = fourRoleFrame()
        for (step in 0 until Rgb332Effects.HUE_STEPS) {
            val frame = Rgb332Effects.shiftedFrame(base, step)
            assertEquals(base.leds.map { it.position }, frame.positions(), "step $step")
            assertEquals(
                base.leds.size,
                frame.colors().distinct().size,
                "step $step collapsed two roles onto one colour",
            )
        }
    }

    @Test
    fun shiftedFrame_movesIdenticalColoursIdentically() {
        // Two holds of the same role share a base colour, so they must share a
        // frame colour — otherwise one hand looks like a different role than the
        // other hand of the same sequence.
        val base = LitFrame(
            listOf(
                LitLed(position = 1, roleClass = HoldRole.HAND, baseColor = 0xC0),
                LitLed(position = 2, roleClass = HoldRole.HAND, baseColor = 0xC0),
                LitLed(position = 3, roleClass = HoldRole.FINISH, baseColor = 0xA0),
            )
        )
        for (step in 0 until Rgb332Effects.HUE_STEPS) {
            val frame = Rgb332Effects.shiftedFrame(base, step)
            assertEquals(frame[0].second, frame[1].second, "step $step split a pair apart")
            assertEquals(frame, Rgb332Effects.shiftedFrame(base, step), "step $step was not deterministic")
        }
    }

    @Test
    fun shiftedFrame_pushesNearbyColoursApartRatherThanMergingThem() {
        // Red, orange and amber are one hue step apart at full brightness: after a
        // shared rotation they quantise onto each other, and a start that lands on
        // the same byte as a hand disappears into the route.
        val base = LitFrame(
            listOf(
                LitLed(position = 1, roleClass = HoldRole.START, baseColor = 0xE0),
                LitLed(position = 2, roleClass = HoldRole.HAND, baseColor = 0xF0),
                LitLed(position = 3, roleClass = HoldRole.FINISH, baseColor = 0xF8),
                LitLed(position = 4, roleClass = HoldRole.FOOT, baseColor = WHITE),
            )
        )
        for (step in 0 until Rgb332Effects.HUE_STEPS) {
            val frame = Rgb332Effects.shiftedFrame(base, step)
            assertEquals(
                base.leds.size,
                frame.colors().distinct().size,
                "step $step merged $frame",
            )
            for ((_, color) in frame) {
                assertTrue(color in 0..0xFF, "step $step produced a byte outside the palette")
            }
        }
    }

    @Test
    fun shiftedFrame_leavesAnEmptyClimbEmpty() {
        assertEquals(emptyList(), Rgb332Effects.shiftedFrame(LitFrame(emptyList()), 5))
    }
}
