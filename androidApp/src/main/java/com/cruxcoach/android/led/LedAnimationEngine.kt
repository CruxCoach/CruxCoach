package com.cruxcoach.android.led

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.ble.LitFrame
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
 * Runs the user's chosen LED effect on the currently lit climb.
 *
 * Everything the engine does is derived from observable inputs — what is lit on
 * the board ([LedFrameSink.litFrame]), whether that board can show effects
 * ([LedFrameSink.availability]) and what the user picked ([LedEffectSettings]) —
 * so no caller has to remember to start, stop or refresh it: sending a climb,
 * editing an LED colour, switching the effect, disconnecting, cleaning holds or
 * clearing the board all just change an input and the loop follows. [pause] /
 * [resume] cover the one thing that is not observable, namely another feature
 * owning the board's frame timing.
 *
 * Traffic shape, which is the whole design constraint: a frame carries only the
 * LEDs of the lit climb, each write is acknowledged before the next goes out,
 * frames identical to the previous one are never sent, a frame gives way to a
 * user-initiated send rather than queueing behind it, and a large climb slows the
 * loop down instead of flooding the link. The look and the rhythm of the effects
 * are exactly the contributed ones (PR #21, pinned by `LedPr21GoldenTest`); what
 * changed is how they fit in. Animation frames do not count as activity: the
 * idle disconnect still releases the board on schedule after the last real send.
 *
 * Limits: an effect runs for at most [MAX_EFFECT_MS] on one lit climb; then, or
 * when it is stopped from the notification, the climb's own colours come back
 * and stay until the next send. Leaving the app ends the effect unless the user
 * chose to keep it running in other apps ([claimBackground], from
 * [LedAnimationService]).
 */
@Singleton
class LedAnimationEngine internal constructor(
    private val sink: LedFrameSink,
    private val settings: Flow<LedEffectSettings>,
    private val dispatcher: CoroutineDispatcher,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        sink: LedFrameSink,
        userPreferences: UserPreferences,
    ) : this(
        sink,
        combine(userPreferences.ledAnimation, userPreferences.ledAnimationInBackground) { id, background ->
            LedEffectSettings(effectId = id, keepRunningInBackground = background)
        },
        Dispatchers.Default,
    )

    internal companion object {
        const val TAG = "LedAnimationEngine"

        /**
         * Lit LEDs per extra slice of pacing. An LED costs 2 bytes on API 2 and 3 on
         * API 3, and `BoardPacketEncoder` splits its output into 20-byte writes, so
         * each further group of 16 lit LEDs adds roughly one round trip, hence
         * [PACE_GROUP_MS].
         */
        const val LEDS_PER_PACE_GROUP = 16
        const val PACE_GROUP_MS = 45L

        /** Lower bound between frames, however fast the animation asks to be. */
        const val MIN_FRAME_GAP_MS = 20L

        /**
         * Above this many lit LEDs the animation is refused rather than sent: the
         * frame would need so many acknowledged writes that a user-initiated climb
         * send would start queueing behind it. It also keeps an API-2 frame inside
         * one packet (127 LEDs), which is all Kilter-built boxes are known to take.
         */
        const val MAX_ANIMATED_LEDS = 120

        /**
         * Stay static after this many consecutive failed writes. A link that is only
         * busy — [FrameSendResult.DEFERRED] — is not a failure and never counts here.
         */
        const val MAX_SEND_FAILURES = 3

        /** How long a loop waits while the board is briefly not animatable. */
        const val IDLE_POLL_MS = 250L

        /**
         * An effect ends after this long on the same lit climb, so a phone left on
         * a wall neither drains its battery nor keeps the radio busy for hours.
         * Sending the climb again starts it afresh.
         */
        const val MAX_EFFECT_MS = 15 * 60 * 1000L
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
    private val _backgroundClaimed = MutableStateFlow(false)

    /** True while [LedAnimationService] may keep the effect going outside the app. */
    val backgroundClaimed: StateFlow<Boolean> = _backgroundClaimed.asStateFlow()

    /** The lit frame whose effect has ended (time limit, or Stop). */
    private val endedFrame = MutableStateFlow<LitFrame?>(null)

    private val _isRunning = MutableStateFlow(false)

    /** True while a frame loop exists. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val mayAnimate = MutableStateFlow(false)

    /**
     * True while there is live work an app switch would interrupt — an effect is
     * selected, a climb is lit on a board that shows effects, and no board owner
     * has the floor — regardless of whether the engine may animate at this moment.
     */
    val wantsToRun: StateFlow<Boolean> = mayAnimate.asStateFlow()

    @Volatile
    private var keepRunningInBackground = false

    /**
     * What the keep-alive hook in `CruxCoachApp` asks on the way out of the app:
     * an effect is live and the user chose to keep it running in other apps.
     */
    fun shouldKeepRunningInBackground(): Boolean = wantsToRun.value && keepRunningInBackground

    private val _status = MutableStateFlow(LedEffectStatus.OFF)

    /** What the effect is doing, for the settings and the climb menu. */
    val status: StateFlow<LedEffectStatus> = _status.asStateFlow()

    private val _availability = MutableStateFlow(LedBoardAvailability.NO_BOARD)

    /** What the connected board means for effects, whichever effect is chosen. */
    val availability: StateFlow<LedBoardAvailability> = _availability.asStateFlow()

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

    /** The latest settings, for the loop's own exit path. */
    @Volatile
    private var lastSettings = LedEffectSettings()

    /** The lit climb the time limit is counting for, and since when. */
    @Volatile
    private var timedFrame: LitFrame? = null

    @Volatile
    private var timedSince = 0L

    /**
     * Called once from `CruxCoachApp.onCreate`: follow the settings and the lit
     * board from then on, and go quiet whenever the app leaves the foreground —
     * unless [LedAnimationService] has claimed the background.
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
        val board = combine(sink.litFrame, sink.availability, endedFrame) { frame, availability, ended ->
            BoardInput(frame, availability, ended)
        }
        watcherJob = scope.launch {
            combine(
                board,
                settings,
                pauseOwners,
                inForeground,
                _backgroundClaimed,
            ) { input, current, paused, front, background ->
                decide(input, current, paused, front, background)
            }.collect { decision ->
                lastSettings = decision.settings
                keepRunningInBackground = decision.settings.keepRunningInBackground
                mayAnimate.value = decision.wouldAnimate
                _status.value = decision.status
                _availability.value = decision.availability
                applyDecision(decision)
            }
        }
    }

    private fun decide(
        input: BoardInput,
        current: LedEffectSettings,
        paused: Set<AnimationPauseOwner>,
        front: Boolean,
        background: Boolean,
    ): RunDecision {
        val effect = LedAnimationRegistry.byId(current.effectId)
        val frame = input.frame
        val ready = input.availability == LedBoardAvailability.READY
        val ended = frame != null && frame === input.ended
        val visible = front || background
        // Live work an app switch would interrupt, whether or not the engine may
        // act on it right now.
        val live = frame != null && ready && paused.isEmpty() && effect !== NoneAnimation && !ended
        val status = when {
            effect === NoneAnimation -> LedEffectStatus.OFF
            input.availability == LedBoardAvailability.NO_BOARD -> LedEffectStatus.NO_BOARD
            input.availability == LedBoardAvailability.UNSUPPORTED -> LedEffectStatus.UNSUPPORTED_BOARD
            input.availability == LedBoardAvailability.CLEANING || paused.isNotEmpty() -> LedEffectStatus.PAUSED
            frame == null -> LedEffectStatus.WAITING_FOR_CLIMB
            ended -> LedEffectStatus.ENDED
            !visible -> LedEffectStatus.BACKGROUND
            else -> LedEffectStatus.RUNNING
        }
        return RunDecision(
            settings = current,
            availability = input.availability,
            animation = if (live && visible) effect else null,
            wouldAnimate = live,
            status = status,
            // A loop that stops leaves its last frame on the wall. Put the climb's
            // own colours back when nothing else is about to paint: the effect was
            // switched off, it ended, or the app left the foreground. A pause must
            // NOT repaint (that feature owns the frames now), nor may anything be
            // written while cleaning owns the wall or the board is gone.
            restoreStatic = frame != null && ready && paused.isEmpty() &&
                (effect === NoneAnimation || ended || !visible),
        )
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
     * app is not visible: the loop runs on as though the app were up. Releasing the
     * claim, by the service's end or the app's return, closes that permission again.
     */
    fun claimBackground() {
        _backgroundClaimed.value = true
    }

    fun releaseBackground() {
        _backgroundClaimed.value = false
    }

    /**
     * End the effect for the climb that is lit now and put its own colours back
     * (the notification's Stop). The setting stays: the next climb sent moves again.
     */
    fun stopForCurrentClimb() {
        sink.litFrame.value?.let { endedFrame.value = it }
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
            // itself, so a new climb or an edited colour needs no restart.
            // Under the lock this cannot catch a loop that has just walked out — its
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
     * Put the static colours back after a live animation stopped, so the wall does
     * not freeze on whatever brightness or hue the last frame had. The link refuses
     * it if another climb was sent in the meantime.
     */
    private fun restoreStaticFrame() {
        scope.launch {
            val frame = sink.litFrame.value ?: return@launch
            if (frame.leds.isEmpty() || !sink.canAnimate) return@launch
            sink.sendFrame(frame, frame.staticFrame())
        }
    }

    /**
     * Put the static colours back when a loop that had already written the wall ends
     * on its own — an over-sized climb, a run of failed writes. Everything is
     * re-checked at this moment: the loop may have stopped because another feature
     * or the background took over, and a repaint from here would overwrite them.
     */
    private fun restoreStaticIfStillMine() {
        if (LedAnimationRegistry.byId(lastSettings.effectId) === NoneAnimation) return
        if (pauseOwners.value.isNotEmpty() || !(inForeground.value || _backgroundClaimed.value)) return
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
                val frameStartedAt = clock()
                val base = sink.litFrame.value
                // A board that is briefly not animatable — a lit frame that has not
                // been republished yet between two climbs — is waited out; the
                // watcher ends this loop when the board is really gone.
                if (base == null || base.leds.isEmpty() || !sink.canAnimate) {
                    delay(IDLE_POLL_MS)
                    continue
                }
                if (timedFrame !== base) {
                    timedFrame = base
                    timedSince = frameStartedAt
                } else if (frameStartedAt - timedSince >= MAX_EFFECT_MS) {
                    Log.i(TAG, "LED animation '${animation.id}' ended after ${MAX_EFFECT_MS / 60_000} min on one climb")
                    // The watcher stops this loop and puts the static colours back.
                    endedFrame.value = base
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
                    when (sink.sendFrame(base, frame)) {
                        FrameSendResult.SENT -> {
                            lastSent = frame
                            failures = 0
                        }
                        // Somebody else is mid-send on the link, or the frame's climb
                        // is no longer lit. Dropping this frame costs one beat.
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
                // a slow radio drops frames rather than building a backlog. This
                // is the contributed rhythm, measured from the start of a frame;
                // FEAT-074 §3 and §10 say why it is kept.
                val elapsed = clock() - frameStartedAt
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
                // A loop that was cancelled was stopped by [applyDecision], which knows
                // what the wall should show. One that walked out on its own leaves a
                // frame of ours on the wall that nobody else will clear.
                if (endedOnPurpose && lastSent != null) restoreStaticIfStillMine()
            }
        }
    }

    /**
     * Milliseconds to wait after a frame, given how many LEDs it carries — the
     * contributed rule, unchanged.
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

    private data class BoardInput(
        val frame: LitFrame?,
        val availability: LedBoardAvailability,
        val ended: LitFrame?,
    )

    private data class RunDecision(
        val settings: LedEffectSettings,
        val availability: LedBoardAvailability,
        val animation: LedAnimation?,
        val wouldAnimate: Boolean,
        val status: LedEffectStatus,
        val restoreStatic: Boolean,
    )
}
