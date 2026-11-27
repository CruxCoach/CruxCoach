package com.cruxcoach.android.led

import com.cruxcoach.android.R
import com.cruxcoach.android.ble.LitFrame
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.domain.board.HoldRole

/**
 * The animations a Kilter board can run, one object each.
 *
 * Whole-board colour alternation is capped at [PHOTOSENSITIVE_MIN_FRAME_MS] on
 * purpose: faster than about three changes a second enters the flash rate range
 * associated with photosensitive triggering. The engine enforces the same floor
 * for anything marked [LedAnimation.photosensitive], so an animation asking for
 * less is rounded up rather than trusted. Treat the constant as a safety limit,
 * not a tuning knob.
 */
internal const val PHOTOSENSITIVE_MIN_FRAME_MS = 333L

/**
 * No animation: the static "LED colors" the user picked, i.e. existing
 * behaviour. The engine never starts a loop for it, so this option is
 * byte-for-byte identical to a build without animations — and it is the
 * fallback for any id the registry does not know (a removed animation, a value
 * from a newer install, a corrupt pref).
 */
internal object NoneAnimation : LedAnimation {
    override val id = "none"
    override val labelResId = R.string.settings_led_anim_none
    override val hintResId = R.string.settings_led_anim_none_hint
    override val frameIntervalMillis = 0L

    override fun frameAt(base: LitFrame, tick: Int): List<Pair<Int, Int>> = base.staticFrame()
}

/**
 * The static colours, dimmed and brightened in place.
 *
 * Each LED keeps its own hue, so the role scheme stays readable at the bottom of
 * the breath. The 8-step cycle at 180 ms is a resting 1.4 s per breath;
 * RGB332 only has 8 red/green levels and 4 blue ones, so the blue channel steps
 * visibly — the engine's frame diff means those no-change ticks cost no traffic.
 *
 * Not marked [photosensitive] despite the rate: consecutive frames differ by one
 * level of a monotonic ramp rather than by a full on/off transition, so nothing
 * alternates between two bright states. [ChaoticStrobeAnimation] and
 * [TwoPhaseStrobeAnimation] do, and are.
 */
internal object PulseAnimation : LedAnimation {
    override val id = "pulse"
    override val labelResId = R.string.settings_led_anim_pulse
    override val hintResId = R.string.settings_led_anim_pulse_hint
    override val frameIntervalMillis = 180L

    override fun frameAt(base: LitFrame, tick: Int): List<Pair<Int, Int>> =
        base.leds.map { it.position to Rgb332Effects.pulse(it.baseColor, tick) }
}

/**
 * Alternates the whole board between the CruxCoach Standard and the Kilter
 * Standard colour schemes.
 *
 * Unlike the other animations this one deliberately ignores the user's static
 * colours: the effect *is* the two named presets colliding. Both presets are the
 * ones the "LED colors" section offers as reset buttons, so the option needs no
 * extra setup and reads the same for every user.
 *
 * Colours are keyed by role class, so a hold with an unknown role keeps whatever
 * it was sent with instead of flashing white.
 */
internal object TwoPhaseStrobeAnimation : LedAnimation {
    override val id = "two_phase_strobe"
    override val labelResId = R.string.settings_led_anim_two_phase
    override val hintResId = R.string.settings_led_anim_two_phase_hint
    override val frameIntervalMillis = PHOTOSENSITIVE_MIN_FRAME_MS
    override val photosensitive = true

    private val CRUXCOACH_PHASE = LedHoldColors()
    private val KILTER_PHASE = LedHoldColors.kilterStandard()

    override fun frameAt(base: LitFrame, tick: Int): List<Pair<Int, Int>> {
        val phase = if (Rgb332Effects.wrap(tick, 2) == 0) CRUXCOACH_PHASE else KILTER_PHASE
        return base.leds.map { it.position to phase.colorForRoleClass(it.roleClass, it.baseColor) }
    }

    private fun LedHoldColors.colorForRoleClass(roleClass: Int, fallback: Int): Int = when (roleClass) {
        HoldRole.START -> start
        HoldRole.HAND -> hand
        HoldRole.FINISH -> finish
        HoldRole.FOOT -> foot
        else -> fallback
    }
}

/**
 * Jumpy colour changes that stay legible.
 *
 * "Chaotic" means *deterministic* scramble: the frame's hue offset is
 * `1 + (tick · 7 mod 29)`, so consecutive frames land 84° apart on the colour
 * wheel, every non-zero offset is visited before the pattern repeats, and offset
 * 0 — the static colours — is never reached. The animation is therefore a pure
 * function of the tick: reproducible, testable, and safe to restart mid-effect
 * after a pause.
 *
 * Every colour moves by that one offset, which is what keeps Start, Hand, Top and
 * Foot distinguishable; independent random hues would regularly hand a route's
 * start and finish the same colour.
 */
internal object ChaoticStrobeAnimation : LedAnimation {
    override val id = "chaotic_strobe"
    override val labelResId = R.string.settings_led_anim_chaotic
    override val hintResId = R.string.settings_led_anim_chaotic_hint
    override val frameIntervalMillis = PHOTOSENSITIVE_MIN_FRAME_MS
    override val photosensitive = true

    /**
     * Offsets in play, one fewer than the wheel: excluding offset 0 is what stops
     * a frame from being the unchanged static look.
     */
    private const val OFFSET_COUNT = Rgb332Effects.HUE_STEPS - 1

    /**
     * Coprime with [OFFSET_COUNT], so the offsets are a full permutation of
     * 1..29 rather than a short orbit, and consecutive frames differ by exactly
     * seven steps (84°) — too far apart for the wall to look like it is idling,
     * too orderly to ever show the same frame twice in a row.
     */
    private const val HUE_STRIDE = 7

    override fun frameAt(base: LitFrame, tick: Int): List<Pair<Int, Int>> =
        Rgb332Effects.shiftedFrame(base, hueStep(tick))

    /**
     * The frame's shared hue offset in [Rgb332Effects.HUE_STEPS] steps.
     *
     * Tick 0 is the static colours, which is where a loop starts — the first frame
     * of an animation coming over a lit climb is then a jump away from what was on
     * the wall already, not a repaint of it.
     */
    fun hueStep(tick: Int): Int {
        if (tick <= 0) return 0
        return 1 + (tick.toLong() * HUE_STRIDE % OFFSET_COUNT).toInt()
    }
}

/**
 * A slow revolution of the colour wheel, applied to every colour equally.
 *
 * One shared offset means the four roles keep their mutual distances the whole
 * way round, so the route stays readable while it cycles; the static colours are
 * back exactly every [Rgb332Effects.HUE_STEPS] frames (30 × 150 ms = 4.5 s).
 */
internal object RainbowWaveAnimation : LedAnimation {
    override val id = "rainbow_wave"
    override val labelResId = R.string.settings_led_anim_rainbow
    override val hintResId = R.string.settings_led_anim_rainbow_hint
    override val frameIntervalMillis = 150L

    override fun frameAt(base: LitFrame, tick: Int): List<Pair<Int, Int>> =
        Rgb332Effects.shiftedFrame(base, Rgb332Effects.wrap(tick, Rgb332Effects.HUE_STEPS))
}
