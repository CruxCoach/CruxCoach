package com.cruxcoach.app.ui

import com.cruxcoach.app.playlist.PlaybackItem
import com.cruxcoach.app.playlist.PlaybackPhase
import com.cruxcoach.app.playlist.PlaylistPlayerPresenter
import com.cruxcoach.app.playlist.PlaylistPlayerState

class QueueItemUi(val climbUuid: String, val angle: Int, val restAfterSeconds: Int)

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
)

/** Playlist player. Local playback only: no session sharing, no participants. */
class PlayerScreenModel(private val presenter: PlaylistPlayerPresenter) {

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
    }

    fun next() = presenter.next()
    fun previous() = presenter.previous()
    fun setCurrent(index: Int) = presenter.setCurrent(index)
    fun skipRest() = presenter.skipRest()
    fun acknowledgeRestFinished() = presenter.acknowledgeRestFinished()
    fun resendCurrentClimb() = presenter.resendCurrentClimb()
    fun refreshConnection() = presenter.refreshConnection()

    /** Call after every log so a send can drop the remaining tries. */
    fun climbLogged(isSend: Boolean) = presenter.onClimbLogged(isSend)

    fun stop() = presenter.stop()
    fun close() = presenter.close()

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
        )
    }
}
