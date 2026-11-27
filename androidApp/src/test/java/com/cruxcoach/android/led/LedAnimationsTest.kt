package com.cruxcoach.android.led

import com.cruxcoach.android.ble.LitFrame
import com.cruxcoach.android.ble.LitLed
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.domain.board.HoldRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The animation maths as the board will see it: RGB332 bytes, one entry per lit
 * LED, and role colours that stay distinguishable — the properties that make an
 * effect both sendable and readable on a wall.
 */
class LedAnimationsTest {

    private val climb = fourRoleFrame()
    private val animating = LedAnimationRegistry.ALL.filterNot { it === NoneAnimation }

    @Test
    fun everyAnimation_returnsOneEntryPerLedInOrder() {
        for (animation in LedAnimationRegistry.ALL) {
            for (tick in 0..40) {
                val frame = animation.frameAt(climb, tick)
                assertEquals(climb.leds.size, frame.size, "${animation.id} @ tick $tick")
                assertEquals(
                    listOf(11, 22, 33, 44),
                    frame.positions(),
                    "${animation.id} @ tick $tick dropped or reordered an LED",
                )
            }
        }
    }

    @Test
    fun everyAnimation_emitsOnlyPaletteBytes() {
        for (animation in animating) {
            for (tick in 0..40) {
                for ((_, color) in animation.frameAt(climb, tick)) {
                    assertTrue(
                        color in 0..0xFF,
                        "${animation.id} @ tick $tick produced $color, outside RGB332",
                    )
                }
            }
        }
    }

    @Test
    fun noneAnimation_isTheStaticFrameAtEveryTick() {
        for (tick in listOf(0, 1, 7, 99)) {
            assertEquals(climb.staticFrame(), NoneAnimation.frameAt(climb, tick))
        }
        assertEquals(0L, NoneAnimation.frameIntervalMillis)
        assertFalse(NoneAnimation.photosensitive)
    }

    // ── Pulse ────────────────────────────────────────────────────

    @Test
    fun pulse_startsAtTheStaticColour() {
        assertEquals(climb.staticFrame(), PulseAnimation.frameAt(climb, 0))
    }

    @Test
    fun pulse_onlyEverDims() {
        for (tick in 0..16) {
            for ((position, color) in PulseAnimation.frameAt(climb, tick)) {
                val base = climb.colorAt(position)
                for (shift in CHANNEL_SHIFTS) {
                    assertTrue(
                        channel(color, shift) <= channel(base, shift),
                        "a channel brightened at tick $tick: 0x${color.toString(16)} vs " +
                            "static 0x${base.toString(16)}",
                    )
                }
            }
        }
    }

    @Test
    fun pulse_neverDimsALitChannelToOff() {
        // A dim hold must not read as "no hold here" at the bottom of a breath.
        for (tick in 0..8) {
            for ((position, color) in PulseAnimation.frameAt(climb, tick)) {
                val base = climb.colorAt(position)
                for (shift in CHANNEL_SHIFTS) {
                    val lit = channel(base, shift) > 0
                    assertEquals(
                        lit,
                        channel(color, shift) > 0,
                        "LED $position channel $shift changed which channels are lit at tick $tick",
                    )
                }
            }
        }
    }

    @Test
    fun pulse_breathIsEightFramesAndNotFlat() {
        assertEquals(PulseAnimation.frameAt(climb, 3), PulseAnimation.frameAt(climb, 11))
        assertNotEquals(PulseAnimation.frameAt(climb, 3), PulseAnimation.frameAt(climb, 4))
        assertNotEquals(PulseAnimation.frameAt(climb, 0), PulseAnimation.frameAt(climb, 4))
    }

    // ── Two-phase strobe ─────────────────────────────────────────

    @Test
    fun twoPhase_alternatesTheTwoNamedPresets() {
        val cruxcoach = LedHoldColors()
        val kilter = LedHoldColors.kilterStandard()
        val even = TwoPhaseStrobeAnimation.frameAt(climb, 0)
        val odd = TwoPhaseStrobeAnimation.frameAt(climb, 1)

        assertEquals(listOf(cruxcoach.start, cruxcoach.hand, cruxcoach.finish, cruxcoach.foot), even.colors())
        assertEquals(listOf(kilter.start, kilter.hand, kilter.finish, kilter.foot), odd.colors())
        assertEquals(even, TwoPhaseStrobeAnimation.frameAt(climb, 2))
    }

    @Test
    fun twoPhase_ignoresTheUsersStaticColoursOnPurpose() {
        // The effect *is* the two presets; the "LED colors" the user picked are
        // deliberately not part of it. Locked here so a "fix" cannot silently
        // make the option depend on a setting its label never mentions.
        val custom = LitFrame(
            listOf(LitLed(position = 5, roleClass = HoldRole.HAND, baseColor = 0x07))
        )
        val kilter = LedHoldColors.kilterStandard()
        assertEquals(listOf(kilter.hand), TwoPhaseStrobeAnimation.frameAt(custom, 1).colors())
    }

    @Test
    fun twoPhase_keepsColoursItCannotRemap() {
        val unknown = LitFrame(listOf(LitLed(position = 7, roleClass = 99, baseColor = GREEN)))
        assertEquals(
            listOf(7 to GREEN),
            TwoPhaseStrobeAnimation.frameAt(unknown, 1),
            "an unmapped role must keep the colour the climb was sent with",
        )
    }

    // ── Chaotic strobe ───────────────────────────────────────────

    @Test
    fun chaotic_neverRepeatsThePreviousFrame() {
        for (tick in 0..60) {
            assertNotEquals(
                ChaoticStrobeAnimation.frameAt(climb, tick),
                ChaoticStrobeAnimation.frameAt(climb, tick + 1),
                "tick ${tick + 1} landed on the same colours as tick $tick",
            )
        }
    }

    @Test
    fun chaotic_keepsRoleColoursDistinguishable() {
        for (tick in 0..60) {
            assertDistinctColors(ChaoticStrobeAnimation.frameAt(climb, tick), tick)
        }
    }

    @Test
    fun chaotic_startsOnTheStaticLookThenNeverReturnsToIt() {
        assertEquals(0, ChaoticStrobeAnimation.hueStep(0))
        for (tick in 1..400) {
            assertNotEquals(0, ChaoticStrobeAnimation.hueStep(tick), "tick $tick")
        }
    }

    @Test
    fun chaotic_visitsEveryNonZeroHueOffsetOncePerCycle() {
        val cycle = Rgb332Effects.HUE_STEPS - 1
        assertEquals(
            (1 until Rgb332Effects.HUE_STEPS).toSet(),
            (1..cycle).map { ChaoticStrobeAnimation.hueStep(it) }.toSet(),
        )
    }

    @Test
    fun chaotic_consecutiveHueOffsetsDiffer() {
        for (tick in 1..60) {
            assertNotEquals(ChaoticStrobeAnimation.hueStep(tick), ChaoticStrobeAnimation.hueStep(tick + 1))
        }
    }

    // ── Rainbow wave ─────────────────────────────────────────────

    @Test
    fun rainbow_wrapsAfterOneFullRevolution() {
        val period = Rgb332Effects.HUE_STEPS
        assertEquals(RainbowWaveAnimation.frameAt(climb, 0), RainbowWaveAnimation.frameAt(climb, period))
        assertEquals(RainbowWaveAnimation.frameAt(climb, 7), RainbowWaveAnimation.frameAt(climb, 7 + period))
        assertNotEquals(RainbowWaveAnimation.frameAt(climb, 0), RainbowWaveAnimation.frameAt(climb, 1))
    }

    @Test
    fun rainbow_keepsRoleColoursDistinguishable() {
        for (tick in 0 until Rgb332Effects.HUE_STEPS) {
            assertDistinctColors(RainbowWaveAnimation.frameAt(climb, tick), tick)
        }
    }

    @Test
    fun shiftedAnimations_applyOneOffsetToFitTheWholeWall() {
        // Two holds of the same role must never drift apart mid-animation.
        val twins = LitFrame(
            listOf(
                LitLed(position = 1, roleClass = HoldRole.HAND, baseColor = GREEN),
                LitLed(position = 2, roleClass = HoldRole.HAND, baseColor = GREEN),
            )
        )
        for (animation in listOf(ChaoticStrobeAnimation, RainbowWaveAnimation)) {
            for (tick in 0..20) {
                val frame = animation.frameAt(twins, tick)
                assertEquals(frame[0].second, frame[1].second, "${animation.id} @ tick $tick")
            }
        }
    }

    // ── Safety floor ─────────────────────────────────────────────

    @Test
    fun flashingAnimations_declareThePhotosensitiveFloorTheyAskFor() {
        val flashing = LedAnimationRegistry.ALL.filter { it.photosensitive }
            assertTrue(flashing.isNotEmpty(), "the flash-rate floor has nothing to floor")
        for (animation in flashing) {
            assertTrue(
                animation.frameIntervalMillis >= PHOTOSENSITIVE_MIN_FRAME_MS,
                "${animation.id} asks for ${animation.frameIntervalMillis} ms",
            )
        }
    }

    // ── Registry ─────────────────────────────────────────────────

    @Test
    fun registry_idsAreUniqueAndUnknownOnesFallBackToStatic() {
        val ids = LedAnimationRegistry.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals("none", ids.first(), "the settings screen shows static first")
        assertSame(LedAnimationRegistry.DEFAULT, LedAnimationRegistry.byId("no_such_animation"))
        assertSame(LedAnimationRegistry.DEFAULT, LedAnimationRegistry.byId(null))
        assertSame(LedAnimationRegistry.DEFAULT, LedAnimationRegistry.byId(""))
        assertTrue(LedAnimationRegistry.isKnown("pulse"))
        assertFalse(LedAnimationRegistry.isKnown("pulse "))
    }

    @Test
    fun registry_everyAnimationCarriesItsOwnStrings() {
        val labels = LedAnimationRegistry.ALL.map { it.labelResId }
        val hints = LedAnimationRegistry.ALL.map { it.hintResId }
        assertTrue(labels.all { it != 0 } && hints.all { it != 0 }, "a string resource id was left unset")
        assertEquals(labels.size, labels.distinct().size, "two animations share a label")
        assertEquals(hints.size, hints.distinct().size, "two animations share a hint")
    }

    private fun assertDistinctColors(frame: List<Pair<Int, Int>>, tick: Int) {
        assertEquals(
            frame.size,
            frame.colors().distinct().size,
            "tick $tick collapsed two role colours onto one byte",
        )
    }
}
