package com.cruxcoach.android.ui.board.games

import com.cruxcoach.domain.board.games.GAME_COUNTDOWN_SECONDS
import com.cruxcoach.domain.board.games.KnotCall
import com.cruxcoach.domain.board.games.KnotCaller
import com.cruxcoach.domain.board.games.KnotColour
import com.cruxcoach.domain.board.games.KnotField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a Knot round stands. [POSITIONS]: the field is lit and the climbers take their start. */
enum class KnotPhase { IDLE, POSITIONS, COUNTDOWN, RUNNING, FINISHED }

/** One climber of a round: [held] counts the calls they held, [out] once they came off. */
data class KnotClimber(val out: Boolean = false, val held: Int = 0)

data class KnotRun(
    val phase: KnotPhase = KnotPhase.IDLE,
    val field: KnotField? = null,
    /** Seconds left in the countdown (5..1). */
    val countdown: Int = 0,
    val climbers: List<KnotClimber> = emptyList(),
    /** The call on now; it stays on the result as the last one. */
    val call: KnotCall? = null,
    /** Time per call; 0 when the next call comes by tap ([KnotRunner.next]). */
    val callMs: Long = 0,
    /** Time left for [call]. */
    val callLeftMs: Long = 0,
    /** The climber left on the wall when the others came off. */
    val winner: Int? = null,
) {
    val active: Boolean get() = phase == KnotPhase.POSITIONS || phase == KnotPhase.COUNTDOWN || phase == KnotPhase.RUNNING
    val stillIn: Set<Int> get() = climbers.indices.filterTo(mutableSetOf()) { !climbers[it].out }
    val byTap: Boolean get() = callMs <= 0
}

/**
 * Plays a round of Knot: the field goes up for the start positions, then the
 * usual countdown, then a call every [KnotRun.callMs] from a [KnotCaller], or
 * each time someone below taps [next]. The called colour's hand or foot spots
 * blink on the wall ([BLINKS] times), and a climber who comes off is marked
 * [out]. With more than one climber the round ends when
 * one is left (the winner); solo, when the climber is out. The field blinks
 * as a whole at the end, then the wall clears.
 *
 * The wall goes to [onWall] (spot → colour; empty clears it), each new call
 * to [onCall], and beeps to [onBeep]. Time comes from coroutine delays, so
 * tests run it on virtual time.
 */
internal class KnotRunner(
    private val scope: CoroutineScope,
    /** Monotonic milliseconds, for telling a second tap on a name from a double tap. */
    private val now: () -> Long,
    private val onWall: (Map<Int, KnotColour>) -> Unit,
    private val onCall: (KnotCall) -> Unit = {},
    private val onBeep: (GameBeep) -> Unit = {},
) {
    private val mutableRun = MutableStateFlow(KnotRun())
    val run: StateFlow<KnotRun> = mutableRun.asStateFlow()

    private var job: Job? = null
    private val taps = Channel<Unit>(Channel.CONFLATED)
    private val outAt = mutableMapOf<Int, Long>()

    /** Lights [field] for [climbers] to take their start positions. */
    fun light(field: KnotField, climbers: Int) {
        job?.cancel()
        outAt.clear()
        mutableRun.value = KnotRun(phase = KnotPhase.POSITIONS, field = field, climbers = List(climbers) { KnotClimber() })
        onWall(field.spots)
    }

    /** Everyone is in place: the countdown, then the calls, [callMs] apart (0: by tap), from [seed]. */
    fun start(callMs: Long, seed: Long) {
        val run = mutableRun.value
        val field = run.field ?: return
        if (run.phase != KnotPhase.POSITIONS) return
        val caller = KnotCaller(seed, run.climbers.size, field.colours)
        mutableRun.update { it.copy(phase = KnotPhase.COUNTDOWN, countdown = GAME_COUNTDOWN_SECONDS, callMs = callMs) }
        job = scope.launch {
            for (left in GAME_COUNTDOWN_SECONDS downTo 1) {
                mutableRun.update { it.copy(countdown = left) }
                onBeep(GameBeep.COUNTDOWN)
                delay(SECOND_MS)
            }
            onBeep(GameBeep.GO)
            mutableRun.update { it.copy(phase = KnotPhase.RUNNING, countdown = 0) }
            while (true) {
                val call = caller.next(mutableRun.value.stillIn) ?: break
                mutableRun.update { it.copy(call = call, callLeftMs = callMs) }
                // A tap before this call was up doesn't skip it.
                while (taps.tryReceive().isSuccess) Unit
                onCall(call)
                var spent = 0L
                // The called spots blink: dark, lit, dark, lit … then stay lit.
                val dark = field.without(call.colour, hands = call.limb.isHand)
                repeat(BLINKS) {
                    onWall(dark)
                    delay(BLINK_MS)
                    onWall(field.spots)
                    delay(BLINK_MS)
                    spent += 2 * BLINK_MS
                    mutableRun.update { it.copy(callLeftMs = (callMs - spent).coerceAtLeast(0)) }
                }
                if (callMs <= 0) taps.receive()
                while (spent < callMs) {
                    val step = minOf(TICK_MS, callMs - spent)
                    delay(step)
                    spent += step
                    mutableRun.update { it.copy(callLeftMs = callMs - spent) }
                }
                // Still on the wall when the time is up: the call is held.
                mutableRun.update { r ->
                    if (r.climbers[call.climber].out) r
                    else r.copy(climbers = r.climbers.mapIndexed { i, c -> if (i == call.climber) c.copy(held = c.held + 1) else c })
                }
            }
        }
    }

    /** Someone below calls the next one (when the calls come by tap). */
    fun next() {
        val run = mutableRun.value
        if (run.phase == KnotPhase.RUNNING && run.byTap) taps.trySend(Unit)
    }

    /**
     * [climber] came off. The last one left wins; solo, the round ends. A climber who is out and taps their name
     * again, not as part of a double tap, is back in: a wrong tap is undone.
     */
    fun out(climber: Int) {
        val run = mutableRun.value
        if (run.phase != KnotPhase.COUNTDOWN && run.phase != KnotPhase.RUNNING) return
        if (climber !in run.climbers.indices) return
        if (run.climbers[climber].out) {
            if (now() - (outAt[climber] ?: 0L) < DOUBLE_TAP_MS) return
            outAt.remove(climber)
            mutableRun.update { r -> r.copy(climbers = r.climbers.mapIndexed { i, c -> if (i == climber) c.copy(out = false) else c }) }
            return
        }
        outAt[climber] = now()
        val climbers = run.climbers.mapIndexed { i, c -> if (i == climber) c.copy(out = true) else c }
        val left = climbers.indices.filter { !climbers[it].out }
        mutableRun.update { it.copy(climbers = climbers) }
        val over = if (climbers.size > 1) left.size <= 1 else left.isEmpty()
        if (over) finish(winner = left.singleOrNull()?.takeIf { climbers.size > 1 })
    }

    /** Ends a round early; nobody wins. Before the countdown it goes back to the start. */
    fun stop() {
        when (mutableRun.value.phase) {
            KnotPhase.COUNTDOWN, KnotPhase.RUNNING -> finish(winner = null)
            KnotPhase.POSITIONS -> reset()
            else -> Unit
        }
    }

    /** Back to the settings; clears the wall. */
    fun reset() {
        job?.cancel()
        job = null
        val wasLit = mutableRun.value.phase != KnotPhase.IDLE
        mutableRun.value = KnotRun()
        if (wasLit) onWall(emptyMap())
    }

    private fun finish(winner: Int?) {
        job?.cancel()
        val field = mutableRun.value.field
        mutableRun.update { it.copy(phase = KnotPhase.FINISHED, winner = winner, callLeftMs = 0) }
        onBeep(GameBeep.GO)
        job = scope.launch {
            // The whole field blinks, then the wall goes dark.
            if (field != null) {
                repeat(BLINKS) {
                    onWall(emptyMap())
                    delay(BLINK_MS)
                    onWall(field.spots)
                    delay(BLINK_MS)
                }
            }
            onWall(emptyMap())
        }
    }

    companion object {
        const val SECOND_MS = 1_000L
        const val TICK_MS = 100L
        const val BLINKS = 3
        const val BLINK_MS = 250L
        /** A second tap on a name within this time is the same tap. */
        const val DOUBLE_TAP_MS = 1_000L
    }
}
