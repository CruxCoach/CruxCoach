package com.cruxcoach.app.athlete

import com.cruxcoach.app.notify.RestTimerNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Same fields as Android's `RestTimerState` (data/BoardSessionManager.kt). */
data class RestTimerState(
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val secondsRemaining: Int = 0,
    val totalSeconds: Int = 180,
)

/**
 * The rest timer of the training player — the rest-timer half of Android's
 * `BoardSessionManager`, which the training shares with the board session.
 *
 * Like Android, all timing is an absolute end instant, not counted ticks: a
 * suspended app shows the right remainder when it comes back. The ticker only
 * refreshes the countdown; the local notification (scheduled against the same
 * end instant) is what reaches a locked phone.
 */
class TrainingRestTimer(
    private val scope: CoroutineScope,
    /** Read on each use: the host attaches notifications after start-up. */
    private val notifier: () -> RestTimerNotifier?,
    private val clockMs: () -> Long = ::nowMs,
) {
    private val _restTimer = MutableStateFlow(RestTimerState())
    val restTimer: StateFlow<RestTimerState> = _restTimer.asStateFlow()

    private var endMs = 0L
    private var ticker: Job? = null

    fun startRestTimer(durationSeconds: Int) {
        endMs = clockMs() + durationSeconds * 1000L
        _restTimer.value = RestTimerState(
            isRunning = true,
            isFinished = false,
            secondsRemaining = durationSeconds,
            totalSeconds = durationSeconds,
        )
        notifier()?.let { n ->
            // Asked at the first rest, not at app start (iOS shows the prompt once).
            if (!n.permitted) n.requestPermission { }
            n.restChanged(endMs / 1000)
        }
        ensureTicker()
    }

    fun cancelRestTimer() {
        if (!_restTimer.value.isRunning && !_restTimer.value.isFinished) return
        endMs = 0L
        _restTimer.value = RestTimerState(totalSeconds = _restTimer.value.totalSeconds)
        notifier()?.cancel()
        stopTicker()
    }

    fun dismissRestTimerFinished() {
        _restTimer.update { it.copy(isFinished = false) }
        notifier()?.cancel()
        stopTicker()
    }

    /** Recomputes the remainder now (also called by the ticker and when the app returns to the foreground). */
    fun tick() {
        if (!_restTimer.value.isRunning) return
        val remainingMs = endMs - clockMs()
        if (remainingMs <= 0) {
            _restTimer.update { it.copy(isRunning = false, isFinished = true, secondsRemaining = 0) }
            stopTicker()
        } else {
            // Ceil, as on Android: "1" shows until the very end.
            _restTimer.update { it.copy(secondsRemaining = ((remainingMs + 999) / 1000).toInt()) }
        }
    }

    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                tick()
                if (!_restTimer.value.isRunning) break
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    companion object {
        const val TICK_MS = 250L
    }
}
