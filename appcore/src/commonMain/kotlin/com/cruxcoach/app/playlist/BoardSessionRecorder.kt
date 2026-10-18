package com.cruxcoach.app.playlist

import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.util.DateTimeUtil
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Why a session clock is stopped (Android `PauseReason`). */
enum class SessionPauseReason {
    /** The climber stepped away. */
    MANUAL,

    /** A rest block from the playlist; it ends with the countdown, not with a play button. */
    PLANNED_REST,
}

/** What an ended session recorded (Android returns its `Board_sessions` row). */
data class RecordedSession(
    val id: Long,
    val startedAt: String,
    val endedAt: String,
    val totalSeconds: Int,
    val pauseSeconds: Int,
    val ascents: Int,
    val bids: Int,
) {
    val activeSeconds: Int get() = totalSeconds - pauseSeconds
}

/**
 * The board-session half of Android's `BoardSessionManager`: one session per
 * playlist run, written to the `board_sessions` table the moment it starts and
 * kept current, so the training area counts the board time as a climbing day
 * exactly as on Android (`ClimbingDaysReader` reads that table).
 *
 * All timing is wall-clock snapshots, never counted ticks, so a suspended app
 * books the real time. A planned rest pauses the clock and only the end of
 * that rest resumes it (Android M-097). A session left open by a killed
 * process is ended where it was last recorded on the next start, as Android's
 * recovery does, instead of booking the whole night as training.
 */
class BoardSessionRecorder(
    private val repository: PersonalBoardRepository,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val nowIso: () -> String = { DateTimeUtil.nowIso() },
) {
    private var activeId: Long? = null
    private var startedAt: String? = null
    private var startMs = 0L
    private var pauseStartMs = 0L
    private var accumulatedPauseMs = 0L
    private var pauseReason: SessionPauseReason? = null
    private var ascents = 0
    private var bids = 0

    val isActive: Boolean get() = startedAt != null
    val isPaused: Boolean get() = pauseReason != null

    init {
        endLeftover()
    }

    fun start() {
        if (isActive) return
        startMs = nowMs()
        accumulatedPauseMs = 0L
        pauseReason = null
        ascents = 0
        bids = 0
        val at = nowIso()
        startedAt = at
        activeId = runCatching {
            repository.insertBoardSession(at, null, 0L, 0L, 0L, 0L)
        }.getOrNull()
    }

    fun pause(reason: SessionPauseReason) {
        if (!isActive || isPaused) return
        pauseStartMs = nowMs()
        pauseReason = reason
        persist()
    }

    /** Only the reason that paused the clock restarts it. */
    fun resume(from: SessionPauseReason) {
        if (!isActive || pauseReason != from) return
        accumulatedPauseMs += nowMs() - pauseStartMs
        pauseReason = null
        persist()
    }

    fun recordAscent() {
        if (!isActive) return
        ascents++
        persist()
    }

    fun recordBid() {
        if (!isActive) return
        bids++
        persist()
    }

    /** Seconds since the session started, read live; 0 while none runs. */
    fun elapsedSeconds(): Int = if (!isActive) 0 else ((nowMs() - startMs) / 1000).toInt()

    /** Seconds the clock has stood still so far, a running pause included. */
    fun pausedSeconds(): Int {
        if (!isActive) return 0
        val running = if (isPaused) nowMs() - pauseStartMs else 0L
        return ((accumulatedPauseMs + running) / 1000).toInt()
    }

    fun end(): RecordedSession? {
        val begun = startedAt ?: return null
        if (isPaused) accumulatedPauseMs += nowMs() - pauseStartMs
        val totalSeconds = ((nowMs() - startMs) / 1000).toInt()
        val pauseSeconds = (accumulatedPauseMs / 1000).toInt()
        val endedAt = nowIso()
        val id = activeId
        val saved = runCatching {
            val rowId = if (id != null) {
                repository.endBoardSession(id, endedAt, totalSeconds.toLong(), pauseSeconds.toLong(), ascents.toLong(), bids.toLong())
                id
            } else {
                repository.insertBoardSession(begun, endedAt, totalSeconds.toLong(), pauseSeconds.toLong(), ascents.toLong(), bids.toLong())
            }
            RecordedSession(rowId, begun, endedAt, totalSeconds, pauseSeconds, ascents, bids)
        }.getOrNull()
        activeId = null
        startedAt = null
        pauseReason = null
        return saved
    }

    private fun persist() {
        val id = activeId ?: return
        val now = nowMs()
        val pauseMs = accumulatedPauseMs + if (isPaused) now - pauseStartMs else 0L
        runCatching {
            repository.updateActiveSession(id, ascents.toLong(), bids.toLong(), pauseMs / 1000, (now - startMs) / 1000)
        }
    }

    private fun endLeftover() {
        runCatching {
            val session = repository.getActiveSession() ?: return
            val endedAt = DateTimeUtil.isoToEpochMs(session.startedAt)
                ?.let { start ->
                    Instant.fromEpochMilliseconds(start + session.totalDurationSeconds * 1000L)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).toString()
                }
                ?: nowIso()
            repository.endBoardSession(
                session.id, endedAt, session.totalDurationSeconds, session.pauseDurationSeconds,
                session.ascentCount, session.bidCount,
            )
        }
    }
}
