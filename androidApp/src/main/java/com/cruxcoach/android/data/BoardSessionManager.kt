package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.notification.AppNotificationService
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.Board_sessions
import com.cruxcoach.util.DateTimeUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Why a session clock is stopped — see [BoardSessionState.pauseReason]. */
enum class PauseReason {
    /** The climber stepped away. Board and playlist carry on. */
    MANUAL,

    /** A rest block from the playlist. Ends with the countdown, not with a
     *  play button. */
    PLANNED_REST,
}

data class BoardSessionState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    /**
     * Why the clock is stopped. A single boolean carried two meanings — "the
     * user stepped away" and "the plan calls for a set rest" — and the
     * transport button read it as the first in both cases. Pressing play
     * during a planned rest restarted the session clock without cancelling
     * the countdown, so the remaining rest was booked as training time.
     */
    val pauseReason: PauseReason? = null,
    val elapsedSeconds: Int = 0,
    val pauseSeconds: Int = 0,
    val ascentCount: Int = 0,
    val bidCount: Int = 0,
    val startedAt: String? = null
) {
    val activeSeconds: Int get() = elapsedSeconds - pauseSeconds
}

data class RestTimerState(
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val secondsRemaining: Int = 0,
    val totalSeconds: Int = 180
)

/**
 * Singleton that tracks the active board session AND the rest timer.
 * Both survive navigation between screens and app backgrounding
 * because all timing is based on System.currentTimeMillis() snapshots,
 * not coroutine delay counting.
 *
 * Resilience:
 * - Rest timer alarm via AlarmManager (fires in Doze mode)
 * - Active session persisted to DB immediately (survives process death)
 * - Recovery on init: restores session + rest timer from DB/SharedPreferences
 */
class BoardSessionManager(
    private val personalBoardRepo: PersonalBoardRepository,
    private val notificationService: AppNotificationService,
    private val alarmScheduler: RestTimerAlarmScheduler
) {
    companion object {
        private const val TAG = "BoardSessionManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null

    // --- Session timing (timestamp-based) ---
    private var sessionStartTimeMs: Long = 0L
    private var pauseStartTimeMs: Long = 0L
    private var accumulatedPauseMs: Long = 0L

    // --- Rest timer (timestamp-based) ---
    private var restTimerEndMs: Long = 0L
    private var restTimerDurationMs: Long = 0L

    // --- DB persistence ---
    private var activeSessionId: Long? = null

    private val _state = MutableStateFlow(BoardSessionState())
    val state: StateFlow<BoardSessionState> = _state.asStateFlow()

    private val _restTimer = MutableStateFlow(RestTimerState())
    val restTimer: StateFlow<RestTimerState> = _restTimer.asStateFlow()

    init {
        recoverState()
    }

    // --- Session controls ---

    fun startSession() {
        if (_state.value.isActive) return
        val now = System.currentTimeMillis()
        sessionStartTimeMs = now
        accumulatedPauseMs = 0L
        val startedAt = DateTimeUtil.nowIso()
        _state.update { BoardSessionState(
            isActive = true,
            startedAt = startedAt
        ) }

        // Persist immediately with ended_at = NULL
        try {
            activeSessionId = personalBoardRepo.insertBoardSession(
                startedAt = startedAt,
                endedAt = null,
                totalDurationSeconds = 0L,
                pauseDurationSeconds = 0L,
                ascentCount = 0L,
                bidCount = 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist session start", e)
        }

        ensureTickerRunning()
    }

    fun endSession(): Board_sessions? {
        if (!_state.value.isActive) return null

        // If currently paused, finalize pause
        if (_state.value.isPaused) {
            accumulatedPauseMs += System.currentTimeMillis() - pauseStartTimeMs
        }

        val s = _state.value
        val totalSeconds = ((System.currentTimeMillis() - sessionStartTimeMs) / 1000).toInt()
        val pauseSeconds = (accumulatedPauseMs / 1000).toInt()
        val endedAt = DateTimeUtil.nowIso()

        // Persist to DB
        var savedSession: Board_sessions? = null
        try {
            val sessionId = activeSessionId
            if (sessionId != null) {
                personalBoardRepo.endBoardSession(
                    id = sessionId,
                    endedAt = endedAt,
                    totalDurationSeconds = totalSeconds.toLong(),
                    pauseDurationSeconds = pauseSeconds.toLong(),
                    ascentCount = s.ascentCount.toLong(),
                    bidCount = s.bidCount.toLong()
                )
                savedSession = Board_sessions(
                    id = sessionId,
                    startedAt = s.startedAt ?: endedAt,
                    endedAt = endedAt,
                    totalDurationSeconds = totalSeconds.toLong(),
                    pauseDurationSeconds = pauseSeconds.toLong(),
                    ascentCount = s.ascentCount.toLong(),
                    bidCount = s.bidCount.toLong()
                )
            } else {
                // Fallback: no activeSessionId (shouldn't happen, but be safe)
                val id = personalBoardRepo.insertBoardSession(
                    startedAt = s.startedAt ?: endedAt,
                    endedAt = endedAt,
                    totalDurationSeconds = totalSeconds.toLong(),
                    pauseDurationSeconds = pauseSeconds.toLong(),
                    ascentCount = s.ascentCount.toLong(),
                    bidCount = s.bidCount.toLong()
                )
                savedSession = Board_sessions(
                    id = id,
                    startedAt = s.startedAt ?: endedAt,
                    endedAt = endedAt,
                    totalDurationSeconds = totalSeconds.toLong(),
                    pauseDurationSeconds = pauseSeconds.toLong(),
                    ascentCount = s.ascentCount.toLong(),
                    bidCount = s.bidCount.toLong()
                )
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to save session", e)
        }

        // Cancel rest timer too
        cancelRestTimer()
        activeSessionId = null
        _state.update { BoardSessionState() }
        stopTickerIfIdle()
        return savedSession
    }

    fun pauseSession(reason: PauseReason = PauseReason.MANUAL) {
        if (!_state.value.isActive || _state.value.isPaused) return
        pauseStartTimeMs = System.currentTimeMillis()
        _state.update { it.copy(isPaused = true, pauseReason = reason) }
        persistSessionUpdate()
    }

    /**
     * Restart the session clock.
     *
     * A planned rest can only be resumed by ending the rest — otherwise the
     * countdown keeps running while the clock counts again, and the remainder
     * of the rest is recorded as training. [cancelRestTimer] is the way out of
     * that state; it calls this with [PauseReason.PLANNED_REST].
     */
    fun resumeSession(from: PauseReason = PauseReason.MANUAL) {
        if (!_state.value.isActive || !_state.value.isPaused) return
        if (_state.value.pauseReason != from) {
            Log.d(TAG, "resumeSession($from) ignored — paused for ${_state.value.pauseReason}")
            return
        }
        accumulatedPauseMs += System.currentTimeMillis() - pauseStartTimeMs
        _state.update { it.copy(isPaused = false, pauseReason = null) }
        persistSessionUpdate()
    }

    fun recordAscent() {
        if (!_state.value.isActive) return
        _state.update { it.copy(ascentCount = it.ascentCount + 1) }
        persistSessionUpdate()
    }

    fun recordBid() {
        if (!_state.value.isActive) return
        _state.update { it.copy(bidCount = it.bidCount + 1) }
        persistSessionUpdate()
    }

    // --- Rest timer controls ---

    fun startRestTimer(durationSeconds: Int) {
        val durationMs = durationSeconds * 1000L
        restTimerEndMs = System.currentTimeMillis() + durationMs
        restTimerDurationMs = durationMs
        _restTimer.value = RestTimerState(
            isRunning = true,
            isFinished = false,
            secondsRemaining = durationSeconds,
            totalSeconds = durationSeconds
        )
        // Schedule Doze-safe alarm as backup
        alarmScheduler.schedule(restTimerEndMs)
        // Pause session during rest
        pauseSession(PauseReason.PLANNED_REST)
        ensureTickerRunning()
    }

    fun cancelRestTimer() {
        if (!_restTimer.value.isRunning && !_restTimer.value.isFinished) return
        restTimerEndMs = 0L
        _restTimer.value = RestTimerState(totalSeconds = _restTimer.value.totalSeconds)
        notificationService.cancelRestTimer()
        alarmScheduler.cancel()
        // Ending the rest is the only way out of a PLANNED_REST pause.
        resumeSession(PauseReason.PLANNED_REST)
        stopTickerIfIdle()
    }

    fun dismissRestTimerFinished() {
        _restTimer.update { it.copy(isFinished = false) }
        notificationService.cancelRestTimer()
        stopTickerIfIdle()
    }

    // --- Ticker (shared for session + rest timer) ---

    private fun ensureTickerRunning() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                tick()
                delay(500L) // 500ms for responsive UI updates
            }
        }
    }

    private fun stopTickerIfIdle() {
        val needsTicker = _state.value.isActive || _restTimer.value.isRunning
        if (!needsTicker) {
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()

        // Update session timer
        if (_state.value.isActive) {
            val totalElapsed = ((now - sessionStartTimeMs) / 1000).toInt()
            val currentPauseMs = if (_state.value.isPaused) {
                accumulatedPauseMs + (now - pauseStartTimeMs)
            } else {
                accumulatedPauseMs
            }
            _state.update { it.copy(
                elapsedSeconds = totalElapsed,
                pauseSeconds = (currentPauseMs / 1000).toInt()
            ) }
        }

        // Update rest timer
        if (_restTimer.value.isRunning) {
            val remainingMs = restTimerEndMs - now
            if (remainingMs <= 0) {
                _restTimer.update { it.copy(
                    isRunning = false,
                    isFinished = true,
                    secondsRemaining = 0
                ) }
                // Notify user (notification + vibration) — also handled by AlarmReceiver as backup
                notificationService.notifyRestTimerFinished()
                alarmScheduler.cleanup()
                // Resume session when rest timer finishes
                resumeSession()
            } else {
                _restTimer.update { it.copy(
                    secondsRemaining = ((remainingMs + 999) / 1000).toInt() // ceil
                ) }
            }
        }
    }

    // --- Persistence helpers ---

    private fun persistSessionUpdate() {
        val sessionId = activeSessionId ?: return
        val s = _state.value
        val now = System.currentTimeMillis()
        val totalSeconds = ((now - sessionStartTimeMs) / 1000)
        val currentPauseMs = if (s.isPaused) {
            accumulatedPauseMs + (now - pauseStartTimeMs)
        } else {
            accumulatedPauseMs
        }
        try {
            personalBoardRepo.updateActiveSession(
                id = sessionId,
                ascentCount = s.ascentCount.toLong(),
                bidCount = s.bidCount.toLong(),
                pauseDurationSeconds = currentPauseMs / 1000,
                totalDurationSeconds = totalSeconds
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist session update", e)
        }
    }

    // --- Recovery ---

    private fun recoverState() {
        recoverSession()
        recoverRestTimer()
    }

    private fun recoverSession() {
        try {
            val session = personalBoardRepo.getActiveSession() ?: return
            val now = System.currentTimeMillis()

            // Parse startedAt to millis for elapsed time calculation
            val startedAtMs = DateTimeUtil.isoToEpochMs(session.startedAt)
            if (startedAtMs == null) {
                Log.e(TAG, "Failed to parse session startedAt: ${session.startedAt}")
                return
            }

            // Auto-end sessions older than 12 hours
            val twelveHoursMs = 12 * 60 * 60 * 1000L
            if (now - startedAtMs > twelveHoursMs) {
                Log.w(TAG, "Auto-ending stale session (>12h old)")
                personalBoardRepo.endBoardSession(
                    id = session.id,
                    endedAt = DateTimeUtil.nowIso(),
                    totalDurationSeconds = (now - startedAtMs) / 1000,
                    pauseDurationSeconds = session.pauseDurationSeconds,
                    ascentCount = session.ascentCount,
                    bidCount = session.bidCount
                )
                return
            }

            // Restore active session
            activeSessionId = session.id
            sessionStartTimeMs = startedAtMs
            accumulatedPauseMs = session.pauseDurationSeconds * 1000L

            _state.update { BoardSessionState(
                isActive = true,
                startedAt = session.startedAt,
                ascentCount = session.ascentCount.toInt(),
                bidCount = session.bidCount.toInt(),
                elapsedSeconds = ((now - startedAtMs) / 1000).toInt(),
                pauseSeconds = session.pauseDurationSeconds.toInt()
            ) }

            ensureTickerRunning()
            Log.i(TAG, "Recovered active session ${session.id}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to recover session", e)
        }
    }

    private fun recoverRestTimer() {
        val savedEndMs = alarmScheduler.getPersistedEndMs()
        if (savedEndMs <= 0L) return

        val now = System.currentTimeMillis()
        if (alarmScheduler.isAlarmScheduled() && savedEndMs > now) {
            // Timer still running — restore UI
            restTimerEndMs = savedEndMs
            val remainingSeconds = ((savedEndMs - now + 999) / 1000).toInt()
            _restTimer.value = RestTimerState(
                isRunning = true,
                secondsRemaining = remainingSeconds,
                totalSeconds = remainingSeconds
            )
            ensureTickerRunning()
            Log.i(TAG, "Recovered rest timer with ${remainingSeconds}s remaining")
        } else {
            // Timer expired or alarm gone — clean up
            alarmScheduler.cleanup()
        }
    }
}
