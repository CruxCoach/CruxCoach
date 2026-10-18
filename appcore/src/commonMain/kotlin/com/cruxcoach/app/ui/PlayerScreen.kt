package com.cruxcoach.app.ui

import com.cruxcoach.app.playlist.FinishedSession
import com.cruxcoach.app.playlist.PlaybackItem
import com.cruxcoach.domain.board.SessionType
import com.cruxcoach.app.playlist.PlaybackPhase
import com.cruxcoach.app.playlist.PlaylistPlayerPresenter
import com.cruxcoach.app.playlist.PlaylistPlayerState

class QueueItemUi(val climbUuid: String, val angle: Int, val restAfterSeconds: Int)

/**
 * Android `SessionSummarySheet` data. [hasDetails] is false when the logbook
 * holds no row of the session window; the sheet then shows the session's own
 * counters, as Android does without a summary.
 */
class SessionSummaryUi(
    val totalSeconds: Int,
    val activeSeconds: Int,
    val pauseSeconds: Int,
    val sends: Int,
    val attempts: Int,
    val hasDetails: Boolean,
    val flashes: Int,
    val uniqueClimbs: Int,
    /** "" without a graded send. */
    val hardestGrade: String,
    val hardestName: String,
    val gradeDistribution: List<GradeBarUi>,
    val warmupCount: Int,
    val optimalCount: Int,
    val limitCount: Int,
    /** warmup | volume | limit | pyramid */
    val sessionTypeCode: String,
)

class PlayerScreenState(
    val isActive: Boolean,
    val listName: String,
    val queue: List<QueueItemUi>,
    val currentIndex: Int,
    val currentClimbUuid: String,
    val currentClimbName: String,
    val currentClimbGrade: String,
    val currentAngle: Int,
    /** climbing | resting */
    val phaseCode: String,
    val restSecondsRemaining: Int,
    val restTotalSeconds: Int,
    /** Epoch seconds the rest ends at; 0 while climbing. Schedule the local
     *  notification for this, not for a countdown that a suspended app stops. */
    val restEndsAtEpochSeconds: Long,
    val restFinished: Boolean,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
    val upNextClimbUuid: String,
    /** 0 unless the current climb is one of a run of identical entries. */
    val attemptNumber: Int,
    val attemptTotal: Int,
    /** list | shuffle */
    val orderCode: String,
    /** manual | afterSend | afterLog */
    val advanceCode: String,
    val defaultRestSeconds: Int,
    val boardConnected: Boolean,
    /** A board session is being recorded: the session bar shows its clock. */
    val sessionRunning: Boolean,
    /** The run ended and its summary sheet is open. */
    val showSummary: Boolean,
    val summary: SessionSummaryUi,
)

/** Playlist player. Local playback only: no session sharing, no participants. */
class PlayerScreenModel(
    private val presenter: PlaylistPlayerPresenter,
    /**
     * Mirrors each rest into a local notification, so a rest that ends on a
     * locked phone is still heard (Android: exact alarm). Read on each use: the
     * host attaches notifications after start-up; null = none attached.
     */
    private val restNotifier: () -> com.cruxcoach.app.notify.RestTimerNotifier? = { null },
) {
    private var restWatch: Subscription? = null

    val currentState: PlayerScreenState get() = map(presenter.state.value)

    fun watch(onState: (PlayerScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    /** [orderCode]/[advanceCode] come from the list's playback settings. */
    fun play(
        listName: String,
        climbUuids: List<String>,
        angles: List<Int>,
        restsAfter: List<Int>,
        orderCode: String,
        advanceCode: String,
        defaultRestSeconds: Int,
    ) {
        if (climbUuids.size != angles.size || climbUuids.size != restsAfter.size) return
        presenter.play(
            listName = listName,
            items = climbUuids.mapIndexed { index, uuid -> PlaybackItem(uuid, angles[index], restsAfter[index]) },
            order = ListCodes.order(orderCode) ?: com.cruxcoach.data.repository.ListPlaybackOrder.LIST,
            advance = ListCodes.advance(advanceCode) ?: com.cruxcoach.data.repository.ListPlaybackAdvance.MANUAL,
            defaultRestSeconds = defaultRestSeconds,
        )
        restNotifier()?.let { notifier -> if (!notifier.permitted) notifier.requestPermission { } }
        if (restWatch == null) {
            val watch = presenter.watch { state -> restNotifier()?.restChanged(map(state).restEndsAtEpochSeconds) }
            restWatch = Subscription { watch.cancel() }
        }
    }

    fun next() = presenter.next()
    fun previous() = presenter.previous()
    fun setCurrent(index: Int) = presenter.setCurrent(index)
    fun skipRest() = presenter.skipRest()

    /** Rest-timer auto-start after a log; ignored while a planned rest runs. */
    fun startRest(seconds: Int) = presenter.startRestNow(seconds)
    fun acknowledgeRestFinished() = presenter.acknowledgeRestFinished()
    fun resendCurrentClimb() = presenter.resendCurrentClimb()
    fun refreshConnection() = presenter.refreshConnection()

    /** Call after every log so a send can drop the remaining tries. */
    fun climbLogged(isSend: Boolean) = presenter.onClimbLogged(isSend)

    fun stop() {
        presenter.stop()
        endRestNotifications()
    }

    /** Ends the run and opens the end-of-session summary. */
    fun endSession() {
        presenter.endSession()
        endRestNotifications()
    }

    fun dismissSummary() = presenter.dismissSummary()

    /** Live: read each second by the session bar, not pushed through [watch]. */
    fun sessionElapsedSeconds(): Int = presenter.sessionElapsedSeconds()

    fun sessionActiveSeconds(): Int =
        (presenter.sessionElapsedSeconds() - presenter.sessionPausedSeconds()).coerceAtLeast(0)

    fun close() {
        presenter.close()
        endRestNotifications()
    }

    private fun endRestNotifications() {
        restWatch?.cancel()
        restWatch = null
        restNotifier()?.cancel()
    }

    private fun map(state: PlaylistPlayerState): PlayerScreenState {
        val resting = state.phase as? PlaybackPhase.Resting
        val attempt = state.attemptInfo
        return PlayerScreenState(
            isActive = state.isActive,
            listName = state.listName,
            queue = state.queue.map { QueueItemUi(it.climbUuid, it.angle, it.restAfterSeconds) },
            currentIndex = state.currentIndex,
            currentClimbUuid = state.currentClimb?.climbUuid ?: "",
            currentClimbName = state.currentClimbName,
            currentClimbGrade = state.currentClimbGrade,
            currentAngle = state.currentClimb?.angle ?: -1,
            phaseCode = if (resting != null) "resting" else "climbing",
            restSecondsRemaining = resting?.secondsRemaining ?: 0,
            restTotalSeconds = resting?.totalSeconds ?: 0,
            restEndsAtEpochSeconds = resting?.endsAtEpochSeconds ?: 0L,
            restFinished = state.restFinished,
            hasNext = state.hasNext,
            hasPrevious = state.hasPrevious,
            upNextClimbUuid = state.upNext?.climbUuid ?: "",
            attemptNumber = attempt?.first ?: 0,
            attemptTotal = attempt?.second ?: 0,
            orderCode = ListCodes.order(state.order),
            advanceCode = ListCodes.advance(state.advance),
            defaultRestSeconds = state.defaultRestSeconds,
            boardConnected = state.boardConnected,
            sessionRunning = state.isActive && presenter.isSessionRunning,
            showSummary = state.finished != null,
            summary = summary(state.finished),
        )
    }

    private fun summary(finished: FinishedSession?): SessionSummaryUi {
        val session = finished?.session
        val details = finished?.summary
        return SessionSummaryUi(
            totalSeconds = session?.totalSeconds ?: 0,
            activeSeconds = session?.activeSeconds ?: 0,
            pauseSeconds = session?.pauseSeconds ?: 0,
            sends = session?.ascents ?: 0,
            // Same definition as the logbook stats: every logged row counts at least one try.
            attempts = details?.totalAttempts ?: session?.bids ?: 0,
            hasDetails = details != null,
            flashes = details?.flashCount ?: 0,
            uniqueClimbs = details?.uniqueClimbs ?: 0,
            hardestGrade = details?.hardestSendGrade ?: "",
            hardestName = details?.hardestSendName ?: "",
            gradeDistribution = details?.gradeDistribution?.map { GradeBarUi(it.grade, it.difficultyInt, it.count) }
                ?: emptyList(),
            warmupCount = details?.warmupCount ?: 0,
            optimalCount = details?.optimalCount ?: 0,
            limitCount = details?.limitCount ?: 0,
            sessionTypeCode = when (details?.sessionType ?: SessionType.PYRAMID_SESSION) {
                SessionType.WARMUP_SESSION -> "warmup"
                SessionType.VOLUME_SESSION -> "volume"
                SessionType.LIMIT_SESSION -> "limit"
                SessionType.PYRAMID_SESSION -> "pyramid"
            },
        )
    }
}
