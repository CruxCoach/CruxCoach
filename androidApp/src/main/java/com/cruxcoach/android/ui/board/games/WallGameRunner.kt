package com.cruxcoach.android.ui.board.games

import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.games.GameTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GamePhase { IDLE, COUNTDOWN, RUNNING, FINISHED }

/** Sounds a running game asks for. */
enum class GameBeep { COUNTDOWN, GO }

/** Where a timed game stands. */
data class GameRun(
    val phase: GamePhase = GamePhase.IDLE,
    /** Seconds left in the countdown (5..1). */
    val countdown: Int = 0,
    /** Time since the start; frozen once the game finished. */
    val elapsedMs: Long = 0,
    /** Revealed rows, groups or packets, and how many there are. */
    val step: Int = 0,
    val steps: Int = 0,
    /** The holds the wall shows now. */
    val lit: List<BoardHold> = emptyList(),
    /** True once the last frame is up (all lava rows, the whole climb). */
    val timelineDone: Boolean = false,
)

/**
 * Plays a [GameTimeline]: countdown, then every frame at its time, with a
 * stopwatch. Frames go to [onFrame] as they fall due; when the caller fell
 * behind, the newest due frame is shown and the missed ones are skipped.
 * Time comes from [now] (monotonic milliseconds), so tests can run it on
 * virtual time.
 */
internal class WallGameRunner(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val onFrame: (List<BoardHold>) -> Unit,
    private val onBeep: (GameBeep) -> Unit = {},
) {
    private val mutableRun = MutableStateFlow(GameRun())
    val run: StateFlow<GameRun> = mutableRun.asStateFlow()

    private var job: Job? = null
    private var startedAt = 0L

    /**
     * Starts [timeline] after [countdownSeconds]. With [finishWithTimeline]
     * the game ends when the last frame is up (the lava reached the top);
     * otherwise the stopwatch runs until [stop].
     */
    fun start(timeline: GameTimeline, countdownSeconds: Int, finishWithTimeline: Boolean) {
        job?.cancel()
        mutableRun.value = GameRun(phase = GamePhase.COUNTDOWN, countdown = countdownSeconds, steps = timeline.steps)
        job = scope.launch {
            for (left in countdownSeconds downTo 1) {
                mutableRun.update { it.copy(countdown = left) }
                onBeep(GameBeep.COUNTDOWN)
                delay(SECOND_MS)
            }
            onBeep(GameBeep.GO)
            startedAt = now()
            mutableRun.update { it.copy(phase = GamePhase.RUNNING, countdown = 0) }
            var shown = -1
            while (true) {
                val elapsed = now() - startedAt
                val due = timeline.indexAt(elapsed)
                if (due > shown) {
                    shown = due
                    val frame = timeline.frames[due]
                    mutableRun.update {
                        it.copy(lit = frame.holds, step = frame.step, timelineDone = due == timeline.frames.lastIndex)
                    }
                    onFrame(frame.holds)
                }
                val done = shown == timeline.frames.lastIndex
                if (done && finishWithTimeline) {
                    val end = timeline.frames.lastOrNull()?.atMs ?: elapsed
                    mutableRun.update { it.copy(phase = GamePhase.FINISHED, elapsedMs = end, timelineDone = true) }
                    return@launch
                }
                mutableRun.update { it.copy(elapsedMs = elapsed) }
                val nextAt = timeline.frames.getOrNull(shown + 1)?.atMs
                val wait = if (nextAt == null) TICK_MS else (nextAt - elapsed).coerceIn(1, TICK_MS)
                delay(wait)
            }
        }
    }

    /** Ends the game: a running stopwatch keeps its time, a countdown goes back to the start. */
    fun stop() {
        val wasRunning = mutableRun.value.phase == GamePhase.RUNNING
        job?.cancel()
        job = null
        mutableRun.update {
            when {
                wasRunning -> it.copy(phase = GamePhase.FINISHED, elapsedMs = now() - startedAt)
                it.phase == GamePhase.FINISHED -> it
                else -> GameRun()
            }
        }
    }

    /** Back to the start screen. */
    fun reset() {
        job?.cancel()
        job = null
        mutableRun.value = GameRun()
    }

    val isActive: Boolean
        get() = mutableRun.value.phase == GamePhase.COUNTDOWN || mutableRun.value.phase == GamePhase.RUNNING

    companion object {
        const val SECOND_MS = 1_000L
        /** Stopwatch refresh: tenths of a second. */
        const val TICK_MS = 100L
    }
}

/**
 * How a write of a wall state went: [SENT]; [FAILED] on the way, worth
 * another try; [REFUSED] before it started (another session holds the board,
 * no connection, more holds than the board shows), which another try won't
 * change.
 */
internal enum class WallWrite { SENT, FAILED, REFUSED }

/**
 * Sends wall states one at a time, newest first: while a write is on the
 * way, further states replace each other and only the latest goes out next.
 * A fast game can therefore never queue up writes behind a slow link. A state
 * equal to the last one the wall took is not sent again, unless [show] asks
 * for it. A failed write goes out again after [RETRY_MS], up to [RETRIES]
 * times, while no newer state waits.
 *
 * [transmit] runs non-cancellable: leaving the screen must not cut a
 * multi-chunk write in half.
 */
internal class WallGameSender<T>(
    scope: CoroutineScope,
    private val transmit: suspend (T) -> WallWrite,
    private val onResult: (Boolean) -> Unit = {},
) {
    private val pending = Channel<T>(Channel.CONFLATED)
    private var lastSent: T? = null
    /** The next state goes out even if the wall took it last. */
    private var again = false

    init {
        scope.launch {
            for (newest in pending) {
                var state = newest
                var retries = 0
                while (state != lastSent || again) {
                    again = false
                    val write = try {
                        withContext(NonCancellable) { transmit(state) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        WallWrite.FAILED
                    }
                    val ok = write == WallWrite.SENT
                    lastSent = if (ok) state else null
                    onResult(ok)
                    if (write != WallWrite.FAILED || retries == RETRIES) break
                    // A newer state goes instead, at once and with its own retries; else this one again after a pause.
                    val newer = pending.tryReceive().getOrNull() ?: run {
                        delay(RETRY_MS)
                        pending.tryReceive().getOrNull()
                    }
                    if (newer != null) {
                        state = newer
                        retries = 0
                    } else {
                        retries++
                    }
                }
            }
        }
    }

    /**
     * Puts [state] on the wall. [again]: even if the wall took it last, for a write the board may have dropped
     * although it went out.
     */
    fun show(state: T, again: Boolean = false) {
        if (again) this.again = true
        pending.trySend(state)
    }

    /** Forget what the wall shows, e.g. after reconnecting, so the next state is sent again. */
    fun forget() {
        lastSent = null
    }

    companion object {
        /** A failed write goes out again after this pause … */
        const val RETRY_MS = 200L
        /** … at most this many times. */
        const val RETRIES = 3
    }
}
