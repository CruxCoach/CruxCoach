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
 * Sends wall states one at a time, newest first: while a write is on the
 * way, further states replace each other and only the latest goes out next.
 * A fast game can therefore never queue up writes behind a slow link. A state
 * equal to the last one the wall took is not sent again.
 *
 * [transmit] runs non-cancellable: leaving the screen must not cut a
 * multi-chunk write in half.
 */
internal class WallGameSender(
    scope: CoroutineScope,
    private val transmit: suspend (List<BoardHold>) -> Boolean,
    private val onResult: (Boolean) -> Unit = {},
) {
    private val pending = Channel<List<BoardHold>>(Channel.CONFLATED)
    private var lastSent: List<BoardHold>? = null

    init {
        scope.launch {
            for (holds in pending) {
                if (holds == lastSent) continue
                val ok = try {
                    withContext(NonCancellable) { transmit(holds) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                lastSent = if (ok) holds else null
                onResult(ok)
            }
        }
    }

    fun show(holds: List<BoardHold>) {
        pending.trySend(holds)
    }

    /** Forget what the wall shows, e.g. after reconnecting, so the next state is sent again. */
    fun forget() {
        lastSent = null
    }
}
