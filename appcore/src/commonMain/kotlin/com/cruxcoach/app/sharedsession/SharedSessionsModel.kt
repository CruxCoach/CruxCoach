package com.cruxcoach.app.sharedsession

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.nearby.NearbyClimb
import com.cruxcoach.app.ui.Subscription
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One Android host advertising a joinable session (Android `SessionHostItem`). */
class NearbySessionUi(
    val identifier: String,
    /** Empty: Swift shows Android's `relay_host_title`. */
    val hostName: String,
    val participantCount: Int,
    val hasClimb: Boolean,
    /** Empty when the climb is not in this phone's catalogue (`board_ble_unknown_climb`). */
    val climbName: String,
    val climbGrade: String,
    val climbAngle: Int,
)

/** Android's `NearbyActiveClimbCard`: someone climbing nearby, or a board occupied without a climb. */
class NearbyClimbUi(
    val key: String,
    /** active | occupied */
    val kindCode: String,
    val uuid: String,
    /** Empty when unknown here. */
    val name: String,
    val grade: String,
    val angle: Int,
    /** In this phone's catalogue, so it can be opened. */
    val known: Boolean,
)

class SharedQueueRowUi(
    val index: Int,
    val uuid: String,
    /** The catalogue name, else the first 8 characters of the id (as Android). */
    val name: String,
    val grade: String,
    val angle: Int,
    val isCurrent: Boolean,
)

/**
 * The iPhone as a participant in an Android-hosted shared session, plus "someone is climbing
 * nearby". Lives in its own package (owned area); the shape follows the `com.cruxcoach.app.ui`
 * facade rules: codes, plain fields, no enums.
 */
class SharedSessionsState(
    /** unknown | off | unauthorized | unsupported | on */
    val bluetoothCode: String,
    val scanning: Boolean,
    val sessions: List<NearbySessionUi>,
    val climbs: List<NearbyClimbUi>,
    /** idle | connecting | joined | hostLost | ended */
    val phaseCode: String,
    /** none | connectFailed | hostLeft | endedByHost */
    val endCode: String,
    val hostName: String,
    /** Includes the host, as Android counts it. */
    val participantCount: Int,
    val participants: List<String>,
    val rows: List<SharedQueueRowUi>,
    /** 0-based; -1 without a current climb. */
    val currentIndex: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    /** Seconds left in the host's rest; 0 while none runs. */
    val restSeconds: Int,
    val pendingCommands: Int,
    /** none | conflict | unavailable | failed — show once per [feedbackSeq]. */
    val feedbackCode: String,
    val feedbackSeq: Int,
    val hasSuccessor: Boolean,
    val successorHostName: String,
    /** The host advances without lighting the wall until someone sends explicitly. */
    val awaitingExplicitSend: Boolean,
    /** An external board app last wrote the host's wall. */
    val externalBoardOverride: Boolean,
    /** Joined: the queue may be changed through the host. */
    val canEdit: Boolean,
)

class SharedSessionsModel internal constructor(private val presenter: SharedSessionsPresenter) {
    val currentState: SharedSessionsState get() = map(presenter.current())

    fun watch(onState: (SharedSessionsState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    /** The screen is visible (first use shows the Bluetooth prompt). */
    fun startScan() = presenter.startScan()

    /** The screen went away; a joined session keeps its link and its scan. */
    fun stopScan() = presenter.stopScan()

    fun join(identifier: String) = presenter.participant.join(identifier)
    fun leave() = presenter.participant.leave()
    fun dismissEnded() = presenter.participant.dismiss()
    fun next() = presenter.participant.next()
    fun previous() = presenter.participant.previous()
    fun select(index: Int) = presenter.participant.setCurrentClimb(index)
    fun remove(index: Int) = presenter.participant.removeClimb(index)
    fun move(from: Int, to: Int) = presenter.participant.moveClimb(from, to)

    /** Asks the host to put the current climb back on the wall (Android `resendCurrentClimb`). */
    fun resend() = presenter.participant.resend()

    /** Asks the host to add [uuid]; only UUID climb ids travel in this protocol. */
    fun add(uuid: String, angle: Int) = presenter.participant.addClimb(uuid, angle)

    /** Joined, and [uuid] is an id the session protocol can carry. */
    fun canAdd(uuid: String): Boolean =
        presenter.participant.state.value.phase == ParticipantPhase.JOINED && ClimbUuidBytes.parse(uuid) != null

    fun acceptSuccessor() = presenter.participant.acceptSuccessor()
    fun declineSuccessor() = presenter.participant.declineSuccessor()

    private fun map(s: SharedSessionsSnapshot): SharedSessionsState {
        val p = s.participant
        fun label(uuid: String, angle: Int) = s.labels[SharedSessionsPresenter.labelKey(uuid, angle)]
        val rows = p.queue.mapIndexed { i, item ->
            val known = label(item.climbUuid, item.angle)
            SharedQueueRowUi(i, item.climbUuid, known?.name ?: item.climbUuid.take(8), known?.grade ?: "",
                item.angle, i == p.currentIndex)
        }
        return SharedSessionsState(
            bluetoothCode = when (s.adapter) {
                BleAdapterState.POWERED_ON -> "on"
                BleAdapterState.POWERED_OFF -> "off"
                BleAdapterState.UNAUTHORIZED -> "unauthorized"
                BleAdapterState.UNSUPPORTED -> "unsupported"
                else -> "unknown"
            },
            scanning = s.scanning,
            sessions = s.sessions
                // The joined host is the session on screen, not one to join again.
                .filter { p.phase == ParticipantPhase.IDLE || p.phase == ParticipantPhase.ENDED || it.identifier != p.hostIdentifier }
                .sortedByDescending { it.rssi }
                .map { session ->
                    val uuid = session.currentClimbUuid
                    val known = uuid?.let { label(it, session.currentClimbAngle) }
                    NearbySessionUi(session.identifier, session.hostName.trim(), session.participantCount,
                        uuid != null, known?.name ?: "", known?.grade ?: "", session.currentClimbAngle)
                },
            climbs = s.climbs.filter { !it.isLastClimb }.sortedByDescending { it.rssi }.map { climb -> climbUi(climb, s) },
            phaseCode = when (p.phase) {
                ParticipantPhase.IDLE -> "idle"
                ParticipantPhase.CONNECTING -> "connecting"
                ParticipantPhase.JOINED -> "joined"
                ParticipantPhase.HOST_LOST -> "hostLost"
                ParticipantPhase.ENDED -> "ended"
            },
            endCode = when (p.endReason) {
                ParticipantEndReason.NONE -> "none"
                ParticipantEndReason.CONNECT_FAILED -> "connectFailed"
                ParticipantEndReason.HOST_LEFT -> "hostLeft"
                ParticipantEndReason.ENDED_BY_HOST -> "endedByHost"
            },
            hostName = p.hostName.trim(),
            participantCount = p.participantCount,
            participants = p.participants,
            rows = rows,
            currentIndex = p.currentIndex,
            hasPrevious = p.currentIndex > 0,
            hasNext = p.currentIndex >= 0 && p.currentIndex < p.queue.size - 1,
            restSeconds = p.restRemainingSeconds,
            pendingCommands = p.pendingCommands,
            feedbackCode = when (p.feedback) {
                null -> "none"
                CommandFeedbackKind.CONFLICT -> "conflict"
                CommandFeedbackKind.UNAVAILABLE -> "unavailable"
                CommandFeedbackKind.FAILED -> "failed"
            },
            feedbackSeq = p.feedbackSeq,
            hasSuccessor = p.successor != null,
            successorHostName = p.successor?.hostName ?: "",
            awaitingExplicitSend = p.awaitingExplicitSend,
            externalBoardOverride = p.externalBoardOverride,
            canEdit = p.phase == ParticipantPhase.JOINED,
        )
    }

    private fun climbUi(climb: NearbyClimb, s: SharedSessionsSnapshot): NearbyClimbUi {
        if (climb.connectedOnly) {
            return NearbyClimbUi("occupied:${climb.senderToken ?: climb.identifier}", "occupied", "", "", "", 0, false)
        }
        val known = s.labels[SharedSessionsPresenter.labelKey(climb.climbUuid, climb.angle)]
        return NearbyClimbUi("climb:${climb.climbUuid}", "active", climb.climbUuid, known?.name ?: "",
            known?.grade ?: "", climb.angle, known != null)
    }
}
