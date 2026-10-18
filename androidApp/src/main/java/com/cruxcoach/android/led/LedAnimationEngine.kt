package com.cruxcoach.android.led

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.data.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs the user's chosen LED animation on the currently lit climb.
 *
 * Everything the engine does is derived from two observable inputs — what is lit
 * on the board ([LedFrameSink.litFrame]) and what the user picked
 * ([UserPreferences.ledAnimation]) — so no caller has to remember to start, stop
 * or refresh it: sending a climb, editing an LED colour, switching the animation,
 * disconnecting or clearing the board all just change an input and the loop
 * follows. [pause] / [resume] cover the one thing that is not observable, namely
 * another feature owning the board's frame timing.
 *
 * Traffic shape, which is the whole design constraint: a frame carries only the
 * LEDs of the lit climb (typically 12-40 on a Kilter, i.e. a handful of 20-byte
 * writes), each write is acknowledged before the next goes out, frames identical
 * to the previous one are never sent, a frame gives way to a user-initiated send
 * rather than queueing behind it, and a large climb slows the loop down instead of
 * flooding the link. With the animation on "none" — or nothing lit, or the app in
 * the background without a [claimBackground] from [LedAnimationService], or another
 * feature owning the board — the engine emits nothing at all.
 */
@Singleton
class LedAnimationEngine internal constructor(
    private val sink: LedFrameSink,
    private val animationIds: Flow<String>,
    private val dispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    @Inject
    constructor(sink: LedFrameSink, userPreferences: UserPreferences) :
        this(sink, userPreferences.ledAnimation, Dispatchers.Default)

    private companion object {
        const val TAG = "LedAnimationEngine"

        /**
         * Lit LEDs per extra slice of pacing. An LED costs 2 bytes and
         * `BoardPacketEncoder` splits its output into 20-byte writes, so 16 lit
         * LEDs are two to three acknowledged writes; each further group of 16 adds
         * another slice of that round trip, hence [PACE_GROUP_MS].
         */
        const val LEDS_PER_PACE_GROUP = 16
        const val PACE_GROUP_MS = 45L

        /** Lower bound between frames, however fast the animation asks to be. */
        const val MIN_FRAME_GAP_MS = 20L

        /**
         * Above this many lit LEDs the animation is refused rather than sent: the
         * frame would need so many acknowledged writes that a user-initiated
         * climb send would start queueing behind it. It also bounds the frame
         * interval from above — [MAX_ANIMATED_LEDS] / [LEDS_PER_PACE_GROUP] ×
         * [PACE_GROUP_MS], i.e. 360 ms — so no accepted climb can animate so
         * slowly that it reads as a stuck board.
         */
        const val MAX_ANIMATED_LEDS = 120

        /**
         * Stay static after this many consecutive failed writes. A link that is only
         * busy — [FrameSendResult.DEFERRED], someone else mid-send — is not a failure
         * and never counts here, because an editor sync would otherwise end the
         * animation.
         */
        const val MAX_SEND_FAILURES = 3

        /**
         * How long an idle loop waits before looking at the board again. Only
         * reached while the board is briefly not animatable; a board that stays
         * away is cancelled by the watcher long before this cadence matters.
         */
        const val IDLE_POLL_MS = 250L
    }

    private val scope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Uncaught exception in LED animation engine", e)
        },
    )

    private val pauseOwners = MutableStateFlow<Set<AnimationPauseOwner>>(emptySet())

    /**
     * Per-call claims on [pause]/[resume], counted under this lock. The set flow
     * the watcher combines over is republished from here, so every holder is
     * released only by its own matching [resume].
     */
    private val pauseCounts = mutableMapOf<AnimationPauseOwner, Int>()
    private val inForeground = MutableStateFlow(false)
    private val backgroundClaimed = MutableStateFlow(false)

    private val _isRunning = MutableStateFlow(false)

    /** True while frames are being pushed. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val mayAnimate = MutableStateFlow(false)

    /**
     * True while there is live work an app switch would interrupt — an effect is
     * selected, a climb is lit, and no board owner has the floor — regardless of
     * whether the engine may animate at this moment. What the keep-alive decision
     * in `CruxCoachApp` consults on the way out of the foreground, since a
     * backgrounded engine that [isRunning] has already stopped says nothing about
     * whether it was mid-effect.
     */
    val wantsToRun: StateFlow<Boolean> = mayAnimate.asStateFlow()

    private var started = false

    /** The input watcher. Lives for the whole process once [start] ran. */
    private var watcherJob: Job? = null

    /** The current frame loop, if one is running. Owned under [loopLock]: the
     * watcher's decisions and the loop's own exit both change them, from whatever
     * worker the [scope]'s dispatcher happens to run each of them on. */
    private var loopJob: Job? = null
    private var runningId: String? = null

    /**
     * Identity token for the live loop, handed to [runLoop] as a parameter and
     * stamped into [runningToken] *before* the launch (the job object only exists
     * after it). A loop whose token no longer matches has been superseded or
     * cancelled by [applyDecision], which already tidied the state it would touch.
     */
    private var runningToken: Any? = null

    /** Guards [loopJob]/[runningId]/[runningToken] against the watcher-vs-loop-exit
     * interleaving. [pauseCounts] has its own monitor and never nests inside this
     * one, so no lock-order cycle exists between the two. */
    private val loopLock = Any()

    /**
     * The animation id the watcher last saw, "none" included. A loop that ends by
     * itself needs it to tell "the user still wants this effect" from "they moved on
     * to none", and [animationIds] is a cold flow that cannot be read on demand.
     * Written on the watcher's thread, read on the loop's, hence volatile.
     */
    @Volatile
    private var lastRequestedId: String? = null

    /**
     * Called once from `CruxCoachApp.onCreate`: follow the animation setting and
     * the lit board from then on, and go quiet whenever the app leaves the
     * foreground — unless [LedAnimationService] has claimed the background.
     */
    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        observeSelection()
    }

    /**
     * The reactive half of [start], split out so the loop logic can be driven in
     * tests without a process lifecycle.
     */
    internal fun observeSelection() {
        if (watcherJob != null) return
        watcherJob = scope.launch {
            combine(
                sink.litFrame,
                animationIds,
                pauseOwners,
                inForeground,
                backgroundClaimed,
            ) { frame, id, paused, front, background ->
                val effect = LedAnimationRegistry.byId(id)
                // True whenever there is live work an app switch would interrupt,
                // whether or not the engine may act on it right now.
                val climbIsAnimatable = frame != null && paused.isEmpty() && effect !== NoneAnimation
                RunDecision(
                    requestedId = id,
                    animation = if (climbIsAnimatable && (front || background)) effect else null,
                    wouldAnimate = climbIsAnimatable,
                    // "Switched back to None while the board is still lit" — the
                    // only case that needs a repaint, because the last animation
                    // frame would otherwise stay on the wall. A pause (route
                    // playback, session) must NOT repaint: that feature owns the
                    // frames now, and a background nobody has claimed must not
                    // write at all.
                    restoreStatic = (front || background) && frame != null &&
                        paused.isEmpty() && effect === NoneAnimation,
                )
            }.collect { decision ->
                // Kept for the loop's own exit path, which runs on another thread and
                // cannot consult [animationIds] itself.
                lastRequestedId = decision.requestedId
                mayAnimate.value = decision.wouldAnimate
                applyDecision(decision)
            }
        }
    }

    /** Another feature took the board; the animation stops until it is done. */
    fun pause(owner: AnimationPauseOwner) = synchronized(pauseCounts) {
        pauseCounts[owner] = (pauseCounts[owner] ?: 0) + 1
        publishPauseOwners()
    }

    /**
     * Hands the board back from one [pause]. Counted per call, not per owner:
     * the Easter animation in two screens, or a re-triggered one, each hold their
     * own claim, and the last one out releases the animation. A resume with no
     * matching pause (a stop that never started) clamps at zero and is a no-op.
     */
    fun resume(owner: AnimationPauseOwner) = synchronized(pauseCounts) {
        val remaining = (pauseCounts[owner] ?: 0) - 1
        if (remaining > 0) pauseCounts[owner] = remaining else pauseCounts.remove(owner)
        publishPauseOwners()
    }

    private fun publishPauseOwners() {
        pauseOwners.value = pauseCounts.keys.toSet()
    }

    /**
     * [LedAnimationService] has taken responsibility for the frame timing while the
     * app is not visible: the loop runs on as though the app were up, because the
     * service — not the cached process — is what keeps the link writing. Releasing
     * the claim, by the service's death or the app's return, closes that permission
     * again.
     */
    fun claimBackground() {
        backgroundClaimed.value = true
    }

    fun releaseBackground() {
        backgroundClaimed.value = false
    }

    /**
     * Cancel the watcher and the loop. The engine is a process-lifetime singleton,
     * so production never calls this — it exists so a test can leave its scheduler
     * idle when it finishes.
     */
    internal fun shutdown() {
        synchronized(loopLock) {
            loopJob?.cancel()
            loopJob = null
            runningId = null
            runningToken = null
        }
        watcherJob?.cancel()
        watcherJob = null
        scope.coroutineContext.cancelChildren()
    }

    override fun onStart(owner: LifecycleOwner) = setForeground(true)

    override fun onStop(owner: LifecycleOwner) = setForeground(false)

    internal fun setForeground(active: Boolean) {
        inForeground.value = active
    }

    private fun applyDecision(decision: RunDecision) {
        val animation = decision.animation
        if (animation == null || animation === NoneAnimation) {
            val wasRunning: Boolean
            synchronized(loopLock) {
                wasRunning = loopJob?.isActive == true
                loopJob?.cancel()
                loopJob = null
                runningId = null
                runningToken = null
                _isRunning.value = false
            }
            if (wasRunning && decision.restoreStatic) restoreStaticFrame()
            return
        }
        synchronized(loopLock) {
            // Already looping this animation: the loop re-reads the lit board
            // itself, so a new climb or an edited colour needs no restart. Under
            // the lock this cannot catch a loop that has just walked out — its
            // exit path clears `runningId` behind the same lock first.
            if (animation.id == runningId && loopJob?.isActive == true) return
            loopJob?.cancel()
            runningId = animation.id
            val token = Any()
            runningToken = token
            loopJob = scope.launch { runLoop(animation, token) }
        }
    }

    /**
     * Put the static colours back after a live animation was switched off, so the
     * wall does not freeze on whatever brightness or hue the last frame had.
     */
    private fun restoreStaticFrame() {
        scope.launch {
            val frame = sink.litFrame.value ?: return@launch
            if (frame.leds.isEmpty() || !sink.canAnimate) return@launch
            sink.sendFrame(frame.staticFrame())
        }
    }

    /**
     * Put the static colours back when a loop that had already written the wall ends
     * on its own — an over-sized climb, a run of failed writes. Unlike
     * the switch-to-none case in [applyDecision], nothing else is going to repaint
     * here, so the wall would otherwise freeze on the last animation frame.
     *
     * Everything is re-checked at this moment rather than trusted from earlier: the
     * loop may have stopped because another feature or the background took over,
     * and a repaint from here would overwrite them. A loop that
     * never got a frame out (`lastSent == null`) leaves the static colours it found,
     * so it is not called for one.
     */
    private fun restoreStaticIfStillMine() {
        val requestedId = lastRequestedId ?: return
        if (LedAnimationRegistry.byId(requestedId) === NoneAnimation) return
        if (pauseOwners.value.isNotEmpty() || !(inForeground.value || backgroundClaimed.value)) return
        restoreStaticFrame()
    }

    private suspend fun runLoop(animation: LedAnimation, token: Any) {
        var tick = 0
        var lastSent: List<Pair<Int, Int>>? = null
        var failures = 0
        var endedOnPurpose = false
        // If [applyDecision] replaced or cancelled this loop between the launch and
        // this first suspension point, it already tidied the state this loop owns;
        // claiming `isRunning` now would stomp on it.
        val started = synchronized(loopLock) {
            if (runningToken === token) {
                _isRunning.value = true
                true
            } else {
                false
            }
        }
        if (!started) return
        try {
            while (true) {
                val frameStartedAt = SystemClock.elapsedRealtime()
                val base = sink.litFrame.value
                // A board that is briefly not animatable — a lit frame that has not
                // been republished yet between two climbs, a link whose brand gate
                // dipped — is waited out, not treated as an ending. The watcher
                // cancels this loop when the board is really gone; breaking on a
                // poll here could otherwise end the effect on a `null` the watcher
                // never even saw (StateFlow conflation), silently leaving a
                // selected animation with no loop.
                if (base == null || base.leds.isEmpty() || !sink.canAnimate) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                val interval = frameIntervalMillis(animation, base.leds.size)
                if (interval <= 0L) {
                    Log.w(
                        TAG,
                        "LED animation '${animation.id}' not started: ${base.leds.size} lit LEDs " +
                            "exceed $MAX_ANIMATED_LEDS — staying on static colours",
                    )
                    break
                }

                val frame = animation.frameAt(base, tick)
                // RGB332 is coarse, so pulse and hue-cycle frames repeat often.
                // Skipping identical frames is where the traffic budget lives.
                if (frame != lastSent) {
                    when (sink.sendFrame(frame)) {
                        FrameSendResult.SENT -> {
                            lastSent = frame
                            failures = 0
                        }
                        // Somebody else is mid-send on the link. Dropping this frame
                        // costs one beat of the effect; counting it as a failure would
                        // end the animation on an editor sync, and waiting would land
                        // it after the send it was waiting for.
                        FrameSendResult.DEFERRED -> Unit
                        FrameSendResult.FAILED -> {
                            failures++
                            if (failures >= MAX_SEND_FAILURES) {
                                Log.w(
                                    TAG,
                                    "LED animation '${animation.id}' stopped after $failures " +
                                        "consecutive write failures",
                                )
                                break
                            }
                        }
                    }
                }
                tick++

                // Spend what the send took, and never fall behind by queueing:
                // a slow radio drops frames rather than building a backlog.
                val elapsed = SystemClock.elapsedRealtime() - frameStartedAt
                delay((interval - elapsed).coerceAtLeast(MIN_FRAME_GAP_MS))
            }
            endedOnPurpose = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A BLE, encoder or database throw must not poison the parent scope
            // (the lesson from the Easter animation playback).
            Log.w(TAG, "LED animation loop '${animation.id}' failed", e)
        } finally {
            val mine = synchronized(loopLock) {
                if (runningToken === token) {
                    runningToken = null
                    runningId = null
                    loopJob = null
                    _isRunning.value = false
                    true
                } else {
                    false
                }
            }
            if (mine) {
                // A loop that was cancelled was stopped by [applyDecision] (switched
                // to none) or by a pause — both of which know what the wall should
                // show. One that walked out on its own leaves a frame of ours on the
                // wall that nobody else will clear.
                if (endedOnPurpose && lastSent != null) restoreStaticIfStillMine()
            }
        }
    }

    /**
     * Milliseconds to wait after a frame, given how many LEDs it carries.
     *
     * One BLE write is acknowledged before the next is issued, so a frame's cost
     * grows with its LED count: the animation's own tempo is the starting point,
     * stretched by [PACE_GROUP_MS] per [LEDS_PER_PACE_GROUP] lit LEDs. The
     * [MAX_ANIMATED_LEDS] cutoff keeps that stretch under half a second.
     *
     * Last, and never negotiable: [PHOTOSENSITIVE_MIN_FRAME_MS] wins over all of
     * it for a [LedAnimation.photosensitive] effect, so an animation cannot pace
     * itself into a hazard. 0 means the frame is too big to animate at all.
     */
    internal fun frameIntervalMillis(animation: LedAnimation, ledCount: Int): Long {
        if (ledCount <= 0 || ledCount > MAX_ANIMATED_LEDS) return 0L
        val groups = (ledCount + LEDS_PER_PACE_GROUP - 1) / LEDS_PER_PACE_GROUP
        val floor = if (animation.photosensitive) PHOTOSENSITIVE_MIN_FRAME_MS else MIN_FRAME_GAP_MS
        return maxOf(animation.frameIntervalMillis, groups.toLong() * PACE_GROUP_MS)
            .coerceAtLeast(floor)
    }

    private data class RunDecision(
        val requestedId: String,
        val animation: LedAnimation?,
        val wouldAnimate: Boolean,
        val restoreStatic: Boolean,
    )
}
