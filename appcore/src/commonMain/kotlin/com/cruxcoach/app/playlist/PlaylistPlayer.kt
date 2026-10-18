package com.cruxcoach.app.playlist

import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import kotlin.random.Random
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One queued climb and the rest that follows it. */
data class PlaybackItem(val climbUuid: String, val angle: Int, val restAfterSeconds: Int = 0)

/** What the player is doing right now. */
sealed class PlaybackPhase {
    /** A climb is on the board, or waiting to be sent. */
    object Climbing : PlaybackPhase()

    /** A planned rest block is counting down. [endsAtEpochSeconds] is what iOS
     *  schedules its local notification for; the countdown itself is derived. */
    data class Resting(
        val secondsRemaining: Int,
        val totalSeconds: Int,
        val endsAtEpochSeconds: Long,
    ) : PlaybackPhase()
}

/**
 * Where a climb goes when the player selects it. The GATT session-participant
 * branches of the Android coordinator are OUT of scope for the iOS port: this
 * seam exists so they can be added later without touching playback logic. The
 * only implementation here is local-only.
 */
interface PlaybackTransport {
    /** True when a board could receive a climb right now. */
    val isBoardConnected: Boolean

    /** Puts the climb on the wall. A local-only build has nowhere to put it. */
    fun sendClimb(climbUuid: String, angle: Int)
}

object LocalOnlyTransport : PlaybackTransport {
    override val isBoardConnected: Boolean get() = false
    override fun sendClimb(climbUuid: String, angle: Int) = Unit
}

/** Virtual time in tests, the wall clock in the app. */
interface PlaybackClock {
    fun epochSeconds(): Long

    /** Suspends about one tick; the rest countdown re-derives from the clock. */
    suspend fun awaitTick()
}

object WallClockTicker : PlaybackClock {
    override fun epochSeconds(): Long = Clock.System.now().epochSeconds
    override suspend fun awaitTick() = delay(1_000)
}

data class PlaylistPlayerState(
    val isActive: Boolean = false,
    val listName: String = "",
    val queue: List<PlaybackItem> = emptyList(),
    val currentIndex: Int = -1,
    val currentClimb: PlaybackItem? = null,
    val currentClimbName: String = "",
    val currentClimbGrade: String = "",
    val phase: PlaybackPhase = PlaybackPhase.Climbing,
    val order: ListPlaybackOrder = ListPlaybackOrder.LIST,
    val advance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
    val defaultRestSeconds: Int = 0,
    val boardConnected: Boolean = false,
    /** The rest ended and the banner has not been acknowledged yet. */
    val restFinished: Boolean = false,
    /** The session just ended through [PlaylistPlayerPresenter.endSession]; its sheet is open. */
    val finished: FinishedSession? = null,
) {
    val isResting: Boolean get() = phase is PlaybackPhase.Resting

    /** During a rest, "next" skips the pause — always available then. */
    val hasNext: Boolean get() = isResting || currentIndex in 0 until queue.size - 1
    val hasPrevious: Boolean get() = currentIndex > 0 || isResting
    val upNext: PlaybackItem? get() = queue.getOrNull(currentIndex + 1)

    /** (try, tries) while the current climb is part of a run of identical
     *  entries — the projecting/4x4 shape. Null for single occurrences. */
    val attemptInfo: Pair<Int, Int>?
        get() {
            val current = currentClimb ?: return null
            if (currentIndex !in queue.indices) return null
            var start = currentIndex
            while (start > 0 && queue[start - 1].climbUuid == current.climbUuid) start--
            var end = currentIndex
            while (end < queue.size - 1 && queue[end + 1].climbUuid == current.climbUuid) end++
            val total = end - start + 1
            return if (total > 1) (currentIndex - start + 1) to total else null
        }
}

/** Climb metadata for the player header; the queue itself carries only uuids. */
data class PlayerClimbInfo(val name: String, val difficultyAverage: Double?)

/**
 * Local playlist playback, ported from Android `PlaylistPlaybackCoordinator`
 * plus the queue advance rules of `SessionQueueManager`. Phase-aware transport:
 * during a rest the queue already sits on the upcoming climb, so "next" means
 * "skip the pause", not "skip a climb nobody has tried".
 */
class PlaylistPlayerPresenter(
    private val transport: PlaybackTransport = LocalOnlyTransport,
    private val clock: PlaybackClock = WallClockTicker,
    /** Resolves the display name and grade of a queued climb. */
    private val climbInfo: (climbUuid: String) -> PlayerClimbInfo? = { null },
    private val gradeLabel: (Double?) -> String = { "" },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val random: Random = Random.Default,
    /** Records the run as a board session (Android: PlaylistPlaybackCoordinator → BoardSessionManager). */
    private val sessions: BoardSessionRecorder? = null,
    /** Builds the end-of-session sheet (Android `SessionSummaryBuilder`); it reads the logbook. */
    private val summarize: ((RecordedSession) -> FinishedSession)? = null,
    private val summaryDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(PlaylistPlayerState())
    val state: StateFlow<PlaylistPlayerState> = _state.asStateFlow()

    private var restJob: Job? = null

    fun watch(onState: (PlaylistPlayerState) -> Unit): StateWatch = scope.watchState(state, onState)

    /** Starts a private local playlist. Playlists never publish a joinable session. */
    fun play(
        listName: String,
        items: List<PlaybackItem>,
        order: ListPlaybackOrder = ListPlaybackOrder.LIST,
        advance: ListPlaybackAdvance = ListPlaybackAdvance.MANUAL,
        defaultRestSeconds: Int = 0,
    ) {
        if (items.isEmpty()) return
        cancelRest()
        val queue = if (order == ListPlaybackOrder.SHUFFLE) items.shuffled(random) else items
        _state.value = PlaylistPlayerState(
            isActive = true,
            listName = listName,
            queue = queue,
            order = order,
            advance = advance,
            defaultRestSeconds = defaultRestSeconds,
            boardConnected = transport.isBoardConnected,
        )
        sessions?.start()
        moveTo(0, armRest = false)
    }

    fun stop() {
        cancelRest()
        sessions?.end()
        _state.value = PlaylistPlayerState()
    }

    /**
     * Ends the run and opens the summary (Android: ending the playlist shows
     * `SessionSummarySheet`). The clock stops now; the logbook-derived part
     * follows once it has been read.
     */
    fun endSession() {
        cancelRest()
        val recorded = sessions?.end()
        _state.value = PlaylistPlayerState()
        if (recorded == null) return
        val build = summarize
        if (build == null) {
            _state.update { it.copy(finished = FinishedSession(recorded, null)) }
            return
        }
        scope.launch {
            val finished = try {
                withContext(summaryDispatcher) { build(recorded) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FinishedSession(recorded, null)
            }
            _state.update { it.copy(finished = finished) }
        }
    }

    fun dismissSummary() = _state.update { it.copy(finished = null) }

    /** Live session clock for the session bar: (elapsed, paused) seconds; (0, 0) without a session. */
    fun sessionElapsedSeconds(): Int = sessions?.elapsedSeconds() ?: 0

    fun sessionPausedSeconds(): Int = sessions?.pausedSeconds() ?: 0

    val isSessionRunning: Boolean get() = sessions?.isActive == true

    fun next() {
        // A rest was armed while advancing, so the queue already points at the
        // climb to come: "next" ends the pause instead of advancing again.
        if (_state.value.isResting) {
            skipRest()
            return
        }
        val s = _state.value
        if (s.currentIndex in 0 until s.queue.size - 1) moveTo(s.currentIndex + 1, armRest = true)
    }

    fun previous() {
        if (_state.value.isResting) skipRest()
        val s = _state.value
        if (s.currentIndex > 0) moveTo(s.currentIndex - 1, armRest = false)
    }

    /** A jump is a user override, so it never arms the rest of the climb left behind. */
    fun setCurrent(index: Int) {
        if (_state.value.isResting) cancelRest()
        if (index in _state.value.queue.indices) moveTo(index, armRest = false)
    }

    fun skipRest() {
        if (!_state.value.isResting) return
        cancelRest()
    }

    /**
     * Android `SessionQueueManager.removeClimb` (queue sheet): the current climb
     * keeps its place; removing it makes the next one (or the new last) current
     * and puts that on the wall. An emptied queue has no current climb.
     */
    fun removeAt(index: Int) {
        val s = _state.value
        if (!s.isActive || index !in s.queue.indices) return
        val queue = s.queue.toMutableList().apply { removeAt(index) }
        val newIndex = when {
            queue.isEmpty() -> -1
            index < s.currentIndex -> s.currentIndex - 1
            index == s.currentIndex -> s.currentIndex.coerceAtMost(queue.size - 1)
            else -> s.currentIndex
        }
        val current = queue.getOrNull(newIndex)
        val changed = index == s.currentIndex
        val info = if (changed && current != null) climbInfo(current.climbUuid) else null
        _state.update {
            it.copy(
                queue = queue,
                currentIndex = newIndex,
                currentClimb = current,
                currentClimbName = if (changed) info?.name ?: "" else it.currentClimbName,
                currentClimbGrade = if (changed) gradeLabel(info?.difficultyAverage) else it.currentClimbGrade,
            )
        }
        if (changed && current != null) transport.sendClimb(current.climbUuid, current.angle)
    }

    /**
     * Android `SessionQueueManager.addClimb` (the browser's "+" while a playlist
     * runs): appended without a rest; into an empty queue it becomes current
     * and goes on the wall.
     */
    fun addClimb(climbUuid: String, angle: Int) {
        val s = _state.value
        if (!s.isActive || climbUuid.isBlank()) return
        _state.update { it.copy(queue = it.queue + PlaybackItem(climbUuid, angle, 0)) }
        if (s.currentIndex < 0) moveTo(0, armRest = false)
    }

    /** Android `SessionQueueManager.moveClimb`: the current climb is followed, nothing is sent. */
    fun move(from: Int, to: Int) {
        val s = _state.value
        if (!s.isActive || from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val queue = s.queue.toMutableList().apply { add(to, removeAt(from)) }
        val newIndex = when (s.currentIndex) {
            from -> to
            in minOf(from, to)..maxOf(from, to) -> if (from < to) s.currentIndex - 1 else s.currentIndex + 1
            else -> s.currentIndex
        }
        _state.update { it.copy(queue = queue, currentIndex = newIndex, currentClimb = queue.getOrNull(newIndex)) }
    }

    /**
     * Starts a rest of [seconds] right now — Android's rest-timer auto-start
     * after a logged attempt (`rest_timer_duration_seconds`). A rest that is
     * already running wins; an auto-start must never cut a planned pause short.
     */
    fun startRestNow(seconds: Int) {
        val current = _state.value
        if (!current.isActive || current.isResting || seconds <= 0) return
        startRest(seconds)
    }

    fun acknowledgeRestFinished() = _state.update { it.copy(restFinished = false) }

    /** Puts the current climb back on the wall. */
    fun resendCurrentClimb() {
        val current = _state.value.currentClimb ?: return
        transport.sendClimb(current.climbUuid, current.angle)
        refreshConnection()
    }

    fun refreshConnection() = _state.update { it.copy(boardConnected = transport.isBoardConnected) }

    /**
     * A log never moves the playlist by itself unless the list asked for it.
     * A SEND does end the work on that problem: the remaining queued tries of
     * the same climb are dropped, and the rest that separated two tries is
     * replaced by the one that closed the dropped block — the gap now spans
     * the way to a different problem.
     */
    fun onClimbLogged(isSend: Boolean) {
        val s = _state.value
        if (!s.isActive) return
        if (isSend) sessions?.recordAscent() else sessions?.recordBid()
        if (isSend) skipRemainingAttemptsOfCurrentClimb()
        val shouldAdvance = when (s.advance) {
            ListPlaybackAdvance.MANUAL -> false
            ListPlaybackAdvance.AFTER_SEND -> isSend
            ListPlaybackAdvance.AFTER_LOG -> true
        }
        if (shouldAdvance) next()
    }

    private fun skipRemainingAttemptsOfCurrentClimb() {
        val s = _state.value
        val current = s.currentClimb ?: return
        var next = s.currentIndex + 1
        while (next < s.queue.size && s.queue[next].climbUuid == current.climbUuid) next++
        if (next - s.currentIndex - 1 <= 0) return
        val carriedRest = s.queue[next - 1].restAfterSeconds
        val queue = s.queue.toMutableList()
        queue.subList(s.currentIndex + 1, next).clear()
        queue[s.currentIndex] = queue[s.currentIndex].copy(restAfterSeconds = carriedRest)
        _state.update { it.copy(queue = queue, currentClimb = queue.getOrNull(it.currentIndex)) }
    }

    private fun moveTo(index: Int, armRest: Boolean) {
        val s = _state.value
        val leaving = if (armRest) s.currentClimb else null
        val item = s.queue.getOrNull(index) ?: return
        val info = climbInfo(item.climbUuid)
        _state.update {
            it.copy(
                currentIndex = index,
                currentClimb = item,
                currentClimbName = info?.name ?: "",
                currentClimbGrade = gradeLabel(info?.difficultyAverage),
                boardConnected = transport.isBoardConnected,
            )
        }
        transport.sendClimb(item.climbUuid, item.angle)
        val restSeconds = leaving?.restAfterSeconds ?: 0
        if (restSeconds > 0) startRest(restSeconds)
    }

    private fun startRest(seconds: Int) {
        restJob?.cancel()
        if (seconds <= 0) return
        // The planned rest stops the session clock; only its end restarts it.
        sessions?.pause(SessionPauseReason.PLANNED_REST)
        val endsAt = clock.epochSeconds() + seconds
        _state.update {
            it.copy(
                phase = PlaybackPhase.Resting(seconds, seconds, endsAt),
                restFinished = false,
            )
        }
        restJob = scope.launch {
            // Derived from the clock, not from a tick count: a suspended app
            // must resume with the time that actually passed. The tick budget
            // only bounds a clock that stands still, which would otherwise
            // leave this loop running for as long as the app does.
            var ticksLeft = seconds + REST_TICK_GRACE
            while (isActive && ticksLeft-- > 0) {
                clock.awaitTick()
                val remaining = (endsAt - clock.epochSeconds()).coerceAtLeast(0L).toInt()
                val phase = _state.value.phase
                if (phase !is PlaybackPhase.Resting || phase.endsAtEpochSeconds != endsAt) return@launch
                if (remaining <= 0 || ticksLeft <= 0) {
                    _state.update { it.copy(phase = PlaybackPhase.Climbing, restFinished = true) }
                    sessions?.resume(SessionPauseReason.PLANNED_REST)
                    return@launch
                }
                _state.update {
                    val current = it.phase
                    if (current is PlaybackPhase.Resting && current.endsAtEpochSeconds == endsAt) {
                        it.copy(phase = current.copy(secondsRemaining = remaining))
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun cancelRest() {
        restJob?.cancel()
        restJob = null
        _state.update { if (it.isResting) it.copy(phase = PlaybackPhase.Climbing) else it }
        sessions?.resume(SessionPauseReason.PLANNED_REST)
    }

    fun close() {
        restJob?.cancel()
        sessions?.end()
        scope.cancel()
    }

    private companion object {
        /** Ticks a countdown may take beyond its own length before it gives up. */
        const val REST_TICK_GRACE = 2
    }
}
