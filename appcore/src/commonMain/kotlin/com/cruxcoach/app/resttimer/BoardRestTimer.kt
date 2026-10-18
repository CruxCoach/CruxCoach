package com.cruxcoach.app.resttimer

import com.cruxcoach.app.athlete.RestTimerState
import com.cruxcoach.app.athlete.TrainingRestTimer
import com.cruxcoach.app.logbook.LogAttemptListener

/**
 * The board rest timer's rules, as Android's climb detail applies them
 * (`RestTimerStartDialog`, `DurationStepper`, `RestTimerBannerSlot`).
 *
 * The countdown itself is [TrainingRestTimer]: Android runs one rest timer in
 * `BoardSessionManager`, shared by the board session and the training, so a
 * rest started on a climb is the same rest the training shows.
 */
object RestTimerRules {
    /** `RestTimerStartDialog`: `DurationStepper(minSeconds = 5, maxSeconds = 60 * 60)`. */
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 60 * 60

    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** `DurationStepper`: a step that would leave the range is disabled, never clamped into it. */
    fun canStep(current: Int, delta: Int): Boolean = (clamp(current) + delta) in MIN_SECONDS..MAX_SECONDS

    fun step(current: Int, delta: Int): Int = clamp(clamp(current) + delta)

    /** Android `formatTimerDuration` / the banner: "1:05" from a minute up, "45s" below. */
    fun countdown(totalSeconds: Int): String {
        val safe = totalSeconds.coerceAtLeast(0)
        val min = safe / 60
        val sec = safe % 60
        return if (min > 0) "$min:${sec.toString().padStart(2, '0')}" else "${sec}s"
    }

    /** The banner colour: finished green, ≤10 s red, ≤30 s yellow, otherwise orange. */
    fun tone(state: RestTimerState): String = when {
        state.isFinished -> "finished"
        state.secondsRemaining <= 10 -> "critical"
        state.secondsRemaining <= 30 -> "warning"
        else -> "normal"
    }

    fun progress(state: RestTimerState): Float =
        if (state.totalSeconds > 0) (state.secondsRemaining.toFloat() / state.totalSeconds).coerceIn(0f, 1f) else 0f

    fun phase(state: RestTimerState): String = when {
        state.isFinished -> "finished"
        state.isRunning -> "running"
        else -> "idle"
    }
}

/**
 * Android `rest_timer_auto_start`: the climb detail's ascent logger starts the
 * rest (with the settings length) after a dialog save, after a quick log, and
 * again when a quick log's undo window closes — the same three hooks
 * `BoardClimbDetailViewModel` wires to `startRestTimer()`.
 */
class RestAutoStart(
    private val timer: TrainingRestTimer,
    private val enabled: () -> Boolean,
    private val durationSeconds: () -> Int,
) : LogAttemptListener {
    override fun onAscentSaved(isSend: Boolean) = startIfEnabled()
    override fun onQuickLogSaved(isSend: Boolean) = startIfEnabled()
    override fun onClimbStatusChanged(climbUuid: String) = Unit

    private fun startIfEnabled() {
        if (!enabled()) return
        timer.startRestTimer(durationSeconds())
    }
}
