package com.cruxcoach.android.led

/**
 * The catalogue of available LED animations.
 *
 * **This list is the feature's plug-in point.** To add an animation: write one
 * `LedAnimation` object in `LedAnimations.kt` and add it here, plus its two
 * strings — the settings screen renders [ALL], so no `when (id)` anywhere has to
 * grow. To remove one: delete the entry; a stored pref naming it silently falls
 * back to [DEFAULT] instead of crashing or doing nothing.
 *
 * Order is the order the settings screen shows, so keep "none" first.
 */
object LedAnimationRegistry {

    val ALL: List<LedAnimation> = listOf(
        NoneAnimation,
        PulseAnimation,
        TwoPhaseStrobeAnimation,
        ChaoticStrobeAnimation,
        RainbowWaveAnimation,
    )

    /** Static colours — existing behaviour, and the answer to any unknown id. */
    val DEFAULT: LedAnimation = NoneAnimation

    fun byId(id: String?): LedAnimation = ALL.firstOrNull { it.id == id } ?: DEFAULT

    fun isKnown(id: String?): Boolean = ALL.any { it.id == id }
}
