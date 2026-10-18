package com.cruxcoach.app.ui

import com.cruxcoach.app.nearby.NearbyBoard
import com.cruxcoach.app.nearby.NearbyBoardResolver
import com.cruxcoach.app.nearby.OnBoardSource
import com.cruxcoach.app.nearby.OwnSession
import com.cruxcoach.app.sharedsession.ClimbUuidBytes
import com.cruxcoach.app.sharedsession.NearbySessionUi
import com.cruxcoach.app.sharedsession.ParticipantPhase
import com.cruxcoach.app.sharedsession.SharedSessionsPresenter
import com.cruxcoach.app.sharedsession.SharedSessionsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The sharing half of Android's `BleStatusArea` on the board screens (browser, climb detail,
 * logbook): the climb a nearby phone has on the board, an occupied board, shared playlists to
 * join, and the playlist this iPhone joined. Swift renders the chip and the expanded panel.
 */
class NearbyAreaState(
    /** The nearby scan runs (Bluetooth allowed and on, a board screen or a joined playlist wants it). */
    val scanning: Boolean,
    val hasOnBoard: Boolean,
    /** remoteActive | remoteLast | sessionRemote; empty without [hasOnBoard]. */
    val onBoardSourceCode: String,
    /** As advertised. */
    val onBoardUuid: String,
    /** The catalogue's spelling, for opening it; empty when the climb is not in this phone's catalogue. */
    val onBoardOpenUuid: String,
    val onBoardAngle: Int,
    /** Empty when unknown here (Android `ble_unknown_climb`). */
    val onBoardName: String,
    val onBoardGrade: String,
    /** False: the LEDs went out and only the climb's identity remains (Android `isStillProjected`). */
    val onBoardStillProjected: Boolean,
    val boardOccupiedCount: Int,
    /** Someone on the board would hand it over on request; asking needs an Android phone. */
    val disconnectRequestable: Boolean,
    /** Shared playlists nearby, strongest first, without the one joined. */
    val playlists: List<NearbySessionUi>,
    /** No playlist is joined or being joined, so one of [playlists] can be. */
    val canJoin: Boolean,
    /** none | connecting | joined | hostLost */
    val sessionPhaseCode: String,
    /** Empty: Swift shows Android's `relay_host_title`. */
    val sessionHostName: String,
    /** 1-based position of the host's current climb; 0 without one. */
    val sessionPosition: Int,
    val sessionCount: Int,
    val sessionClimbUuid: String,
    /** Empty when unknown here. */
    val sessionClimbName: String,
    val sessionClimbGrade: String,
    val sessionHasNext: Boolean,
    /** An external board app last wrote the host's wall. */
    val externalBoardOverride: Boolean,
) {
    val inSession: Boolean get() = sessionPhaseCode != "none"

    /**
     * Android's `effectiveOnBoard`: on a climb's own detail page ([currentClimbUuid]) that climb
     * is not repeated. Empty [currentClimbUuid] on every other screen.
     */
    fun showsOnBoard(currentClimbUuid: String): Boolean =
        hasOnBoard && !NearbyBoardResolver.sameClimb(onBoardUuid, currentClimbUuid)

    /** Something to show; without it the area takes no space at all. */
    fun hasContent(currentClimbUuid: String): Boolean =
        showsOnBoard(currentClimbUuid) || boardOccupiedCount > 0 || inSession || playlists.isNotEmpty()

    /** The session line's warning: the wall disagrees with the joined queue (compared by id, never by name). */
    fun boardDiffers(currentClimbUuid: String): Boolean =
        showsOnBoard(currentClimbUuid) && onBoardUuid.isNotBlank() &&
            !NearbyBoardResolver.sameClimb(onBoardUuid, sessionClimbUuid)
}

/**
 * App-wide, like Android's `BleShareManager`: the board-state memory must see every update, and
 * one scan serves every board screen. Screens report [screenShown] / [screenHidden]; the scan is
 * asked for only when [scanAllowed] (Bluetooth already permitted), so a board screen never brings
 * up the permission prompt and shows nothing until the user allowed Bluetooth elsewhere.
 */
class NearbyAreaModel internal constructor(
    private val presenter: SharedSessionsPresenter,
    private val scanAllowed: () -> Boolean,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Moving between two board screens hides one before the next appears; keep the scan (and its memory). */
        const val STOP_GRACE_MS = 2_000L
    }

    private val resolver = NearbyBoardResolver()
    private val state = MutableStateFlow(map(presenter.current()))
    private var shownScreens = 0
    private var stopJob: Job? = null

    init {
        scope.launch { presenter.state.collect { state.value = map(it) } }
    }

    val currentState: NearbyAreaState get() = state.value

    fun watch(onState: (NearbyAreaState) -> Unit): Subscription {
        val watcher = MainScope()
        watcher.launch { state.collect { onState(it) } }
        return Subscription { watcher.cancel() }
    }

    /** A board screen with the area appeared. */
    fun screenShown() {
        shownScreens += 1
        stopJob?.cancel()
        stopJob = null
        if (scanAllowed()) presenter.startPassiveScan()
    }

    /** A board screen with the area went away; the scan stops shortly after the last one. */
    fun screenHidden() {
        if (shownScreens == 0) return
        shownScreens -= 1
        if (shownScreens > 0) return
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(STOP_GRACE_MS)
            if (shownScreens == 0) presenter.stopPassiveScan()
        }
    }

    /**
     * Bluetooth may just have been allowed (the board sheet's prompt, the Settings app): a
     * visible board screen starts scanning now instead of on its next appearance (Android's
     * `retryScan`).
     */
    fun permissionMayHaveChanged() {
        if (shownScreens > 0 && scanAllowed()) presenter.startPassiveScan()
    }

    /** Joins a row of [NearbyAreaState.playlists] (Android's `SessionHostItem` tap). */
    fun join(identifier: String) {
        val phase = presenter.participant.state.value.phase
        if (phase == ParticipantPhase.IDLE || phase == ParticipantPhase.ENDED) presenter.participant.join(identifier)
    }

    /** The mini player's one shortcut: asks the host for the next climb. */
    fun next() = presenter.participant.next()

    /** Joined, and [uuid] is an id the session protocol can carry (Android's "+" on the detail). */
    fun canAdd(uuid: String): Boolean =
        presenter.participant.state.value.phase == ParticipantPhase.JOINED && ClimbUuidBytes.parse(uuid) != null

    /** Asks the host to append the climb on screen; the host decides and broadcasts the queue. */
    fun add(uuid: String, angle: Int) {
        if (canAdd(uuid)) presenter.participant.addClimb(uuid, angle)
    }

    private fun map(s: SharedSessionsSnapshot): NearbyAreaState {
        val p = s.participant
        val member = p.phase == ParticipantPhase.JOINED || p.phase == ParticipantPhase.HOST_LOST
        val connecting = p.phase == ParticipantPhase.CONNECTING
        val current = p.currentClimb
        val own = if (member || connecting) {
            OwnSession(member, connecting, p.sessionId, current?.climbUuid, current?.angle ?: 0)
        } else {
            null
        }
        val board: NearbyBoard = resolver.resolve(s.climbs, s.sessions, own)
        fun label(uuid: String, angle: Int) = s.labels[SharedSessionsPresenter.labelKey(uuid, angle)]
        val onBoard = board.onBoard
        val onBoardLabel = onBoard?.let { label(it.climbUuid, it.angle) }
        val sessionLabel = current?.let { label(it.climbUuid, it.angle) }
        val joinable = p.phase == ParticipantPhase.IDLE || p.phase == ParticipantPhase.ENDED
        return NearbyAreaState(
            scanning = s.scanning,
            hasOnBoard = onBoard != null,
            onBoardSourceCode = when (onBoard?.source) {
                null -> ""
                OnBoardSource.REMOTE_ACTIVE -> "remoteActive"
                OnBoardSource.REMOTE_LAST -> "remoteLast"
                OnBoardSource.SESSION_REMOTE -> "sessionRemote"
            },
            onBoardUuid = onBoard?.climbUuid ?: "",
            onBoardOpenUuid = if (onBoard != null && onBoardLabel != null) onBoardLabel.uuid.ifEmpty { onBoard.climbUuid } else "",
            onBoardAngle = onBoard?.angle ?: 0,
            onBoardName = onBoardLabel?.name ?: "",
            onBoardGrade = onBoardLabel?.grade ?: "",
            onBoardStillProjected = onBoard?.isStillProjected ?: false,
            boardOccupiedCount = board.boardOccupiedCount,
            disconnectRequestable = board.disconnectRequestable,
            playlists = s.sessions
                .filter { joinable || it.identifier != p.hostIdentifier }
                .sortedByDescending { it.rssi }
                .map { session ->
                    val uuid = session.currentClimbUuid
                    val known = uuid?.let { label(it, session.currentClimbAngle) }
                    NearbySessionUi(session.identifier, session.hostName.trim(), session.participantCount,
                        uuid != null, known?.name ?: "", known?.grade ?: "", session.currentClimbAngle)
                },
            canJoin = joinable,
            sessionPhaseCode = when (p.phase) {
                ParticipantPhase.CONNECTING -> "connecting"
                ParticipantPhase.JOINED -> "joined"
                ParticipantPhase.HOST_LOST -> "hostLost"
                else -> "none"
            },
            sessionHostName = p.hostName.trim(),
            sessionPosition = if (current != null) p.currentIndex + 1 else 0,
            sessionCount = p.queue.size,
            sessionClimbUuid = current?.climbUuid ?: "",
            sessionClimbName = sessionLabel?.name ?: "",
            sessionClimbGrade = sessionLabel?.grade ?: "",
            sessionHasNext = p.phase == ParticipantPhase.JOINED && p.currentIndex >= 0 && p.currentIndex < p.queue.size - 1,
            externalBoardOverride = p.externalBoardOverride,
        )
    }
}
