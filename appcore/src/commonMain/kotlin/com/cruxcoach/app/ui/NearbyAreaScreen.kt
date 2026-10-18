package com.cruxcoach.app.ui

import com.cruxcoach.app.nearby.LocalBoard
import com.cruxcoach.app.nearby.LocalBoardClimb
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /**
     * remoteActive | remoteLast | sessionRemote | localActive (this phone's climb on the link that is
     * up: Android "Your climb") | localManager (this phone's last climb, the link gone); empty
     * without [hasOnBoard].
     */
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
    /**
     * Holds are being cleaned on the connected board: Android shows only the cleaning banner
     * (`BoardCleaningBanner`) where this area would be.
     */
    val cleaningActive: Boolean,
    /**
     * Android's board-session timer in the joined playlist's mini player: joining starts a board
     * session (it books the climbing time), the playlist ending ends it. Swift reads the seconds
     * live through [NearbyAreaModel.sessionElapsedSeconds].
     */
    val sessionTimerActive: Boolean,
    val sessionTimerPaused: Boolean,
) {
    val inSession: Boolean get() = sessionPhaseCode != "none"

    /**
     * Android's `effectiveOnBoard`: on a climb's own detail page ([currentClimbUuid]) that climb
     * is not repeated, and neither is this phone's live climb (paging through climbs it sends
     * would flash the previous one). Empty [currentClimbUuid] on every other screen.
     */
    fun showsOnBoard(currentClimbUuid: String): Boolean =
        hasOnBoard && !NearbyBoardResolver.sameClimb(onBoardUuid, currentClimbUuid) &&
            !(currentClimbUuid.isNotEmpty() && onBoardSourceCode == "localActive")

    /** Something to show; without it the area takes no space at all. */
    fun hasContent(currentClimbUuid: String): Boolean =
        showsOnBoard(currentClimbUuid) || boardOccupiedCount > 0 || inSession || playlists.isNotEmpty()

    /** The session line's warning: the wall disagrees with the joined queue (compared by id, never by name). */
    fun boardDiffers(currentClimbUuid: String): Boolean =
        showsOnBoard(currentClimbUuid) && onBoardUuid.isNotBlank() &&
            !NearbyBoardResolver.sameClimb(onBoardUuid, sessionClimbUuid)
}

/** Android's `BoardSessionManager` as a joined playlist uses it (AppCore adapts the board-session recorder). */
internal interface BoardSessionClock {
    val isActive: Boolean
    val isPaused: Boolean
    fun start()
    fun end()
    fun elapsedSeconds(): Int
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
    /** This phone's own board: its last send, the link it is live on, hold cleaning. */
    private var ownBoard = OwnBoard(null, null, false)
    /** The local playlist's current climb while one runs (Android: this phone hosts that queue). */
    private var playlistClimb: Pair<String, Int>? = null
    private var sessionClock: BoardSessionClock? = null
    /** This model started the running board session for the joined playlist (and so ends it). */
    private var clockStartedHere = false
    private var latest: SharedSessionsSnapshot = presenter.current()
    private val state = MutableStateFlow(map(latest))
    private var shownScreens = 0
    private var stopJob: Job? = null

    init {
        scope.launch {
            presenter.state.collect {
                latest = it
                state.value = map(it)
            }
        }
    }

    private data class OwnBoard(val climb: LocalBoardClimb?, val linkedBoard: String?, val cleaning: Boolean)

    /**
     * Android's local playlist is a queue this phone hosts: its current climb is the session's, so
     * this phone's own send of it is not repeated under the playlist banner (the suppression).
     */
    internal fun followPlaylist(current: Flow<Pair<String, Int>?>) {
        scope.launch {
            current.collect {
                playlistClimb = it
                state.value = map(latest)
            }
        }
    }

    /** Android `joinNearbySession` starts a board session; leaving or the host ending it ends it. */
    internal fun useBoardSessionClock(clock: BoardSessionClock) {
        sessionClock = clock
        state.value = map(latest)
    }

    /** Seconds since the joined playlist's board session started; 0 while none runs. */
    fun sessionElapsedSeconds(): Int = sessionClock?.takeIf { it.isActive }?.elapsedSeconds() ?: 0

    private fun syncSessionClock(phase: ParticipantPhase) {
        val clock = sessionClock ?: return
        val inSession = phase == ParticipantPhase.CONNECTING || phase == ParticipantPhase.JOINED ||
            phase == ParticipantPhase.HOST_LOST
        if (inSession && !clockStartedHere && !clock.isActive) {
            clock.start()
            clockStartedHere = clock.isActive
        } else if (!inSession && clockStartedHere) {
            clockStartedHere = false
            clock.end()
        }
    }

    /**
     * Android's BleShareManager reads this phone's own board state too: what it last lit
     * ([climb]), the board its link is up to ([linkedBoard], null while none) and hold cleaning.
     * AppCore feeds the shared board connection in.
     */
    internal fun followOwnBoard(connection: com.cruxcoach.app.ble.BoardConnectionPresenter) = followOwnBoard(
        climb = connection.localBoardClimb.state,
        linkedBoard = connection.state.map { s ->
            val up = s.connection == com.cruxcoach.app.ble.ConnectionState.CONNECTED ||
                s.connection == com.cruxcoach.app.ble.ConnectionState.SENDING
            s.connectedBoard?.identifier?.takeIf { up }
        },
        cleaning = connection.cleaningState.map { it.active },
    )

    internal fun followOwnBoard(climb: Flow<LocalBoardClimb?>, linkedBoard: Flow<String?>, cleaning: Flow<Boolean>) {
        scope.launch {
            combine(climb, linkedBoard, cleaning) { c, b, k -> OwnBoard(c, b, k) }
                .distinctUntilChanged()
                .collect { own ->
                    ownBoard = own
                    own.climb?.let { presenter.wantLabel(it.uuid, it.angle) }
                    state.value = map(latest)
                }
        }
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
        syncSessionClock(p.phase)
        val member = p.phase == ParticipantPhase.JOINED || p.phase == ParticipantPhase.HOST_LOST
        val connecting = p.phase == ParticipantPhase.CONNECTING
        val current = p.currentClimb
        val hosted = playlistClimb
        val own = if (member || connecting) {
            OwnSession(member, connecting, p.sessionId, current?.climbUuid, current?.angle ?: 0)
        } else if (hosted != null) {
            // Android role HOST resolves and suppresses like a member (steps 3 and the suppression).
            OwnSession(member = true, connecting = false, sessionId = 0, currentClimbUuid = hosted.first, currentClimbAngle = hosted.second)
        } else {
            null
        }
        val sent = ownBoard.climb
        val local = sent?.let {
            LocalBoard(
                climbUuid = it.uuid,
                angle = it.angle,
                projectionSurvivesDisconnect = it.projectionSurvivesDisconnect,
                live = it.liveOnBoard.isNotEmpty() && it.liveOnBoard == ownBoard.linkedBoard,
                sentAtMs = it.sentAtMs,
            )
        }
        val board: NearbyBoard = resolver.resolve(s.climbs, s.sessions, own, local)
        fun label(uuid: String, angle: Int) = s.labels[SharedSessionsPresenter.labelKey(uuid, angle)]
        val onBoard = board.onBoard
        val onBoardLabel = onBoard?.let { label(it.climbUuid, it.angle) }
        val isLocal = onBoard?.source == OnBoardSource.LOCAL_ACTIVE || onBoard?.source == OnBoardSource.LOCAL_MANAGER
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
                OnBoardSource.LOCAL_ACTIVE -> "localActive"
                OnBoardSource.LOCAL_MANAGER -> "localManager"
            },
            onBoardUuid = onBoard?.climbUuid ?: "",
            // This phone sent it from its own catalogue: it can be opened before its label is read.
            onBoardOpenUuid = when {
                onBoard == null -> ""
                onBoardLabel != null -> onBoardLabel.uuid.ifEmpty { onBoard.climbUuid }
                isLocal -> onBoard.climbUuid
                else -> ""
            },
            onBoardAngle = onBoard?.angle ?: 0,
            onBoardName = onBoardLabel?.name ?: sent?.name?.takeIf { isLocal } ?: "",
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
            cleaningActive = ownBoard.cleaning,
            sessionTimerActive = (member || connecting) && sessionClock?.isActive == true,
            sessionTimerPaused = (member || connecting) && sessionClock?.isPaused == true,
        )
    }
}
