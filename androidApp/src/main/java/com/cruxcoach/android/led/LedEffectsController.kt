package com.cruxcoach.android.led

import android.content.Context
import com.cruxcoach.android.data.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What the LED effects settings and the climb menu show. */
data class LedEffectsState(
    val effectId: String = NoneAnimation.id,
    val keepRunningInBackground: Boolean = false,
    /** Android's "Remove animations" is on: a strobe is turned on only after a confirmation. */
    val systemAnimationsRemoved: Boolean = false,
    val strobeNoticeSeen: Boolean = false,
    val status: LedEffectStatus = LedEffectStatus.OFF,
    /** A board that shows effects is connected, whatever is chosen. */
    val boardShowsEffects: Boolean = false,
)

/**
 * The LED effects settings, shared by the settings page and the climb menu's
 * quick pick, so both read and change them the same way. Choosing is all it
 * takes: the engine follows the preferences, so a change shows on the wall at
 * once.
 */
@Singleton
class LedEffectsController @Inject constructor(
    private val userPreferences: UserPreferences,
    private val engine: LedAnimationEngine,
    @param:ApplicationContext private val context: Context,
) {
    val state: Flow<LedEffectsState> by lazy {
        combine(
            combine(
                userPreferences.ledAnimation,
                userPreferences.ledAnimationInBackground,
                userPreferences.ledStrobeNoticeSeen,
            ) { id, background, noticeSeen ->
                LedEffectsState(
                    effectId = id,
                    keepRunningInBackground = background,
                    strobeNoticeSeen = noticeSeen,
                )
            },
            SystemAnimations.observe(context),
            engine.status,
            engine.availability,
        ) { prefs, animations, status, availability ->
            prefs.copy(
                systemAnimationsRemoved = !animations,
                status = status,
                boardShowsEffects = availability != LedBoardAvailability.NO_BOARD &&
                    availability != LedBoardAvailability.UNSUPPORTED,
            )
        }
    }

    suspend fun select(id: String) = userPreferences.setLedAnimation(id)

    suspend fun setKeepRunningInBackground(enabled: Boolean) =
        userPreferences.setLedAnimationInBackground(enabled)

    suspend fun strobeNoticeSeen() = userPreferences.setLedStrobeNoticeSeen()
}
