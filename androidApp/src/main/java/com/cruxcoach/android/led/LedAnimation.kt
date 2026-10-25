package com.cruxcoach.android.led

import androidx.annotation.StringRes
import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.ble.LitFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * A board animation: a pure function from (what is lit, which tick) to the frame
 * to send.
 *
 * Implementations must be side-effect free and cheap — [frameAt] runs once per
 * frame on a worker dispatcher — and must return exactly one entry per LED in
 * [base], because a frame that drops an LED switches it off.
 *
 * Adding an animation is: one object implementing this, one entry in
 * [LedAnimationRegistry.ALL], and two strings. Nothing else reads the id.
 */
interface LedAnimation {

    /** Stable, persisted identifier. Changing it orphans stored user choices onto "none". */
    val id: String

    @get:StringRes
    val labelResId: Int

    /** One-line explanation shown under the option in settings. */
    @get:StringRes
    val hintResId: Int

    /**
     * Nominal milliseconds between frames. The engine treats it as a floor to
     * respect, not a guarantee: it stretches it for large climbs (so a 40-hold
     * project cannot saturate the link) and raises it to
     * [PHOTOSENSITIVE_MIN_FRAME_MS] for [photosensitive] effects that ask to be
     * faster than is comfortable. Unused by [NoneAnimation], which never loops.
     */
    val frameIntervalMillis: Long

    /** The complete board frame for [tick], as (ledPosition, rgb332Colour) pairs. */
    fun frameAt(base: LitFrame, tick: Int): List<Pair<Int, Int>>

    /**
     * True when the effect alternates colours and therefore needs a hard floor on
     * its frame rate.
     *
     * A declaration rather than a calculation: `LedAnimationEngine` clamps every
     * animation marked as such to [PHOTOSENSITIVE_MIN_FRAME_MS] no matter what
     * [frameIntervalMillis] says, so adding an effect cannot accidentally ship a
     * flash rate in the range associated with photosensitive triggering.
     */
    val photosensitive: Boolean
        get() = false
}

/**
 * The slice of the BLE link an animation needs, so the engine can be driven by a
 * fake in tests and the animation rules live in one place.
 */
interface LedFrameSink {

    /** What is lit right now, or null when nothing is lit / the board is gone. */
    val litFrame: StateFlow<LitFrame?>

    /**
     * True while frames can actually be pushed — connected, and to a board whose
     * protocol carries a colour per LED. Checked every frame, not just on start.
     *
     * Capability, not momentary availability: it says nothing about whether someone
     * else is writing right now. A link that is mid-send for another feature is
     * still a link the animation may use a moment later, so that case comes back
     * from [sendFrame] as [FrameSendResult.DEFERRED] rather than turning this false
     * — the difference between dropping one frame and ending the loop.
     */
    val canAnimate: Boolean

    /**
     * Push one [frame] made from [basedOn]. Implementations give way to
     * user-initiated traffic instead of queueing behind it and refuse a frame whose
     * climb is no longer lit, so [FrameSendResult.DEFERRED] is an ordinary answer
     * and only [FrameSendResult.FAILED] means the link is going bad.
     */
    suspend fun sendFrame(basedOn: LitFrame, frame: List<Pair<Int, Int>>): FrameSendResult
}

/**
 * Something that owns the board and must not be fought over. While any owner is
 * paused the engine sends nothing; the animation resumes when the last one
 * leaves.
 *
 * The list is deliberately short. A [com.cruxcoach.android.data.SessionQueueManager]
 * session — or a manual step through a route's frames — is *not* an owner: it
 * lights one climb and leaves it alone, so the animation has nothing to collide
 * with, and a queued session is exactly when a lit-up wall is worth having. Only
 * things that write frames on their own timer are here.
 */
enum class AnimationPauseOwner {
    /** Automatic multi-frame route playback — it drives its own frame timing. */
    ROUTE_PLAYBACK,

    /** The Easter animation, which paints its own full-board frames. */
    EASTER_ANIMATION,
}
