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

/**
 * One player of a round: [held] counts the calls they held, [out] once they came off, during call [outAtCall].
 * [part] is where on the field they climb (or climbed), null while they wait in line.
 */
data class KnotClimber(val out: Boolean = false, val held: Int = 0, val outAtCall: Int? = null, val part: Int? = null) {
    val atWall: Boolean get() = !out && part != null
    val waiting: Boolean get() = !out && part == null
}

data class KnotRun(
    val phase: KnotPhase = KnotPhase.IDLE,
    val field: KnotField? = null,
    /** Seconds left in the countdown (5..1). */
    val countdown: Int = 0,
    /** Every player, in the order of their numbers; those beyond the field's parts wait in line. */
    val climbers: List<KnotClimber> = emptyList(),
    /** The call on now; it stays on the result as the last one. */
    val call: KnotCall? = null,
    /** Time per call; 0 when the next call comes by tap ([KnotRunner.next]). */
    val callMs: Long = 0,
    /** Time left for [call]. */
    val callLeftMs: Long = 0,
    /** The player called to the wall from the line, until their first call. */
    val stepIn: Int? = null,
    /** Whoever won: the last one on the wall, or with a line the most calls held; null on a tie or when stopped. */
    val winner: Int? = null,
    /** Ended with Stop before anyone won. */
    val stopped: Boolean = false,
) {
    val active: Boolean get() = phase == KnotPhase.POSITIONS || phase == KnotPhase.COUNTDOWN || phase == KnotPhase.RUNNING
    val stillIn: Set<Int> get() = climbers.indices.filterTo(mutableSetOf()) { !climbers[it].out }
    val byTap: Boolean get() = callMs <= 0

    /** Climbers the field has parts for. */
    val parts: Int get() = this.field?.climbers ?: climbers.size

    /** More players than parts: the others wait in line, and the most calls held wins. */
    val withLine: Boolean get() = climbers.size > parts

    /** Who waits, in order. */
    val line: List<Int> get() = climbers.indices.filter { climbers[it].waiting }

    /** Who is on each part now: the climber there, or the last one who came off it. */
    val onParts: List<Int?> get() = (0 until parts).map { part ->
        climbers.indices.firstOrNull { climbers[it].atWall && climbers[it].part == part }
            ?: climbers.indices.lastOrNull { climbers[it].part == part }
    }
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
 * More players than the field has parts wait in line: whoever comes off
 * makes room for the next, who is called to that part ([onStepIn]) and gets
 * calls after [STEP_IN_MS] to get there (by tap, with the next call). Then
 * the round ends when the line is empty and the last climber is off, and
 * the most calls held wins.
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
    private val onStepIn: (Int) -> Unit = {},
) {
    private val mutableRun = MutableStateFlow(KnotRun())
    val run: StateFlow<KnotRun> = mutableRun.asStateFlow()

    private var job: Job? = null
    private val taps = Channel<Unit>(Channel.CONFLATED)
    private val outAt = mutableMapOf<Int, Long>()
    /** The round's own clock, from the countdown on: the delays add up, so tests on virtual time see the same. */
    private var clockMs = 0L
    /** When a climber called from the line gets calls: by the round's clock. */
    private val readyAt = mutableMapOf<Int, Long>()

    /** Lights [field] for [players] to take their start positions; those beyond its parts wait in line. */
    fun light(field: KnotField, players: Int) {
        job?.cancel()
        outAt.clear()
        readyAt.clear()
        clockMs = 0
        val climbers = List(players) { KnotClimber(part = it.takeIf { it < field.climbers }) }
        mutableRun.value = KnotRun(phase = KnotPhase.POSITIONS, field = field, climbers = climbers)
        onWall(field.spots)
    }

    /** Everyone is in place: the countdown, then the calls, [callMs] apart (0: by tap), from [seed]. */
    fun start(callMs: Long, seed: Long) {
        val run = mutableRun.value
        val field = run.field ?: return
        if (run.phase != KnotPhase.POSITIONS) return
        val caller = KnotCaller(seed, field.climbers, field.colours)
        mutableRun.update { it.copy(phase = KnotPhase.COUNTDOWN, countdown = GAME_COUNTDOWN_SECONDS, callMs = callMs) }
        suspend fun wait(ms: Long) {
            delay(ms)
            clockMs += ms
        }
        job = scope.launch {
            for (left in GAME_COUNTDOWN_SECONDS downTo 1) {
                mutableRun.update { it.copy(countdown = left) }
                onBeep(GameBeep.COUNTDOWN)
                wait(SECOND_MS)
            }
            onBeep(GameBeep.GO)
            mutableRun.update { it.copy(phase = KnotPhase.RUNNING, countdown = 0) }
            while (true) {
                val climbers = mutableRun.value.climbers
                val ready = climbers.indices
                    .filter { climbers[it].atWall && (readyAt[it] ?: 0L) <= clockMs }
                    .associateBy { climbers[it].part!! }
                val call = caller.next(ready)
                if (call == null) {
                    // Nobody ready yet: the next in line is still on the way to the wall.
                    if (climbers.none { it.atWall }) break
                    wait(TICK_MS)
                    continue
                }
                mutableRun.update { r -> r.copy(call = call, callLeftMs = callMs, stepIn = r.stepIn.takeUnless { it == call.climber }) }
                // A tap before this call was up doesn't skip it.
                while (taps.tryReceive().isSuccess) Unit
                onCall(call)
                // The call's own work on the phone (screen, beep, voice) comes first: a blink that started with it lost
                // its first dark phase on a slow phone (30 ms instead of 250).
                wait(BLINK_LEAD_MS)
                var spent = BLINK_LEAD_MS
                // The called spots blink: dark, lit, dark, lit … then stay lit.
                val dark = field.without(call.colour, hands = call.limb.isHand)
                repeat(BLINKS) {
                    onWall(dark)
                    wait(BLINK_MS)
                    onWall(field.spots)
                    wait(BLINK_MS)
                    spent += 2 * BLINK_MS
                    mutableRun.update { it.copy(callLeftMs = (callMs - spent).coerceAtLeast(0)) }
                }
                if (callMs <= 0) taps.receive()
                while (spent < callMs) {
                    val step = minOf(TICK_MS, callMs - spent)
                    wait(step)
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
     * [climber] came off. The last one left wins; solo, the round ends. With a line, the next in line is called to
     * the part [climber] left, and the round ends when nobody is left on the wall or in line. A climber who is out
     * and taps their name again, not as part of a double tap, is back in: a wrong tap is undone. With a line, only
     * until the next call, and whoever was called up for them goes back to the head of the line.
     */
    fun out(climber: Int) {
        val run = mutableRun.value
        if (run.phase != KnotPhase.COUNTDOWN && run.phase != KnotPhase.RUNNING) return
        if (climber !in run.climbers.indices) return
        val target = run.climbers[climber]
        if (target.waiting) return
        if (target.out) {
            if (now() - (outAt[climber] ?: 0L) < DOUBLE_TAP_MS) return
            if (run.withLine && target.outAtCall != (run.call?.number ?: 0)) return
            outAt.remove(climber)
            // Whoever took the part goes back to the line, first again.
            val stepper = run.climbers.indices.firstOrNull { it != climber && run.climbers[it].atWall && run.climbers[it].part == target.part }
            stepper?.let { readyAt.remove(it) }
            mutableRun.update { r ->
                r.copy(
                    climbers = r.climbers.mapIndexed { i, c ->
                        when (i) {
                            climber -> c.copy(out = false, outAtCall = null)
                            stepper -> c.copy(part = null)
                            else -> c
                        }
                    },
                    stepIn = r.stepIn.takeUnless { it == stepper },
                )
            }
            return
        }
        // A tap meant for the climber before, who was on this part a moment ago, isn't this one's fall.
        if (run.stepIn == climber && now() - (outAt.values.maxOrNull() ?: Long.MIN_VALUE) < DOUBLE_TAP_MS) return
        outAt[climber] = now()
        val next = run.line.firstOrNull()
        val climbers = run.climbers.mapIndexed { i, c ->
            when (i) {
                climber -> c.copy(out = true, outAtCall = run.call?.number ?: 0)
                next -> c.copy(part = target.part)
                else -> c
            }
        }
        if (next != null) readyAt[next] = clockMs + if (run.byTap) 0 else STEP_IN_MS
        mutableRun.update { it.copy(climbers = climbers, stepIn = next ?: it.stepIn.takeUnless { s -> s == climber }) }
        if (next != null) onStepIn(next)
        val left = climbers.indices.filter { !climbers[it].out }
        when {
            run.withLine -> if (left.isEmpty()) finish(winner = mostHeld(climbers))
            climbers.size > 1 -> if (left.size <= 1) finish(winner = left.singleOrNull())
            else -> if (left.isEmpty()) finish(winner = null)
        }
    }

    /** The one who held the most calls; null on a tie. */
    private fun mostHeld(climbers: List<KnotClimber>): Int? {
        val most = climbers.maxOf { it.held }
        return climbers.indices.singleOrNull { climbers[it].held == most }
    }

    /** Ends a round early; nobody wins. Before the countdown it goes back to the start. */
    fun stop() {
        when (mutableRun.value.phase) {
            KnotPhase.COUNTDOWN, KnotPhase.RUNNING -> finish(winner = null, stopped = true)
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

    private fun finish(winner: Int?, stopped: Boolean = false) {
        job?.cancel()
        val field = mutableRun.value.field
        mutableRun.update { it.copy(phase = KnotPhase.FINISHED, winner = winner, stopped = stopped, stepIn = null, callLeftMs = 0) }
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
        /** The blink starts this long after its call, with the voice. */
        const val BLINK_LEAD_MS = 250L
        /** A second tap on a name within this time is the same tap. */
        const val DOUBLE_TAP_MS = 1_000L
        /** Time for the next in line to get to the wall and into position before their first call. */
        const val STEP_IN_MS = 8_000L
    }
}
