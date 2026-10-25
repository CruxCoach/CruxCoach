package com.cruxcoach.android.led

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Everything the user decides about LED effects, as the engine reads it. */
data class LedEffectSettings(
    val effectId: String = NoneAnimation.id,
    val keepRunningInBackground: Boolean = false,
)

/** What the board on the link means for an effect right now. */
enum class LedBoardAvailability {
    /** No board connected. */
    NO_BOARD,

    /** A board that shows no LED effects ([LedEffectSupport]). */
    UNSUPPORTED,

    /** Hold cleaning owns the wall. */
    CLEANING,

    /** Frames can go out. */
    READY,
}

/** What an effect is doing, for the settings and the climb menu. */
enum class LedEffectStatus {
    /** "None" is chosen. */
    OFF,

    NO_BOARD,
    UNSUPPORTED_BOARD,

    /** Supported board, nothing lit. */
    WAITING_FOR_CLIMB,

    /** Route playback, the Easter animation, a wall game or hold cleaning has the wall. */
    PAUSED,

    /** The time limit ran out, or it was stopped, for the climb that is lit. */
    ENDED,

    /** The app is in the background and the effect is not kept running there. */
    BACKGROUND,
    RUNNING,
}

/**
 * Android's "Remove animations" (developer and accessibility settings) sets the
 * animator duration scale to 0. People turn it on because motion or flashing
 * bothers them, so choosing a strobe while it is on asks for a confirmation
 * (warn, don't block).
 */
object SystemAnimations {

    fun enabled(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(enabled(context))
            }
        }
        trySend(enabled(context))
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.distinctUntilChanged()
}
