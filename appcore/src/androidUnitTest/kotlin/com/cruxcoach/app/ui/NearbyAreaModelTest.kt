package com.cruxcoach.app.ui

import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.nearby.NearbyClimbProtocol
import com.cruxcoach.app.sharedsession.ClimbLabel
import com.cruxcoach.app.sharedsession.ClimbLookup
import com.cruxcoach.app.sharedsession.QueueItem
import com.cruxcoach.app.sharedsession.SessionCommand
import com.cruxcoach.app.sharedsession.SessionGattUuids
import com.cruxcoach.app.sharedsession.SessionQueueProtocol
import com.cruxcoach.app.sharedsession.SharedSessionParticipantTest
import com.cruxcoach.app.sharedsession.SharedSessionsPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The board screens' nearby area over the real shared-session presenter and a fake radio. */
class NearbyAreaModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val central = FakeBleCentral { scheduler.currentTime }
    private var allowed = true
    private val catalogue = mapOf(
        A to ClimbLabel("Alpha", "6a", A_CATALOGUE),
        B to ClimbLabel("Bravo", "6b", B_CATALOGUE),
        C to ClimbLabel("Charlie", "6c", C_CATALOGUE),
    )
    private val presenter = SharedSessionsPresenter(
        central, scope, dispatcher, { scheduler.currentTime },
        ClimbLookup { uuid, _ -> catalogue[uuid.replace("-", "").uppercase()] },
    )
    private val model = NearbyAreaModel(presenter, { allowed }, scope)
    private val items = listOf(QueueItem(A, 40), QueueItem(B, 40), QueueItem(C, 35))

    init {
        central.services = SharedSessionParticipantTest.SESSION_SERVICES
        central.reads = mutableMapOf<String, ByteArray?>(
            SessionGattUuids.SESSION_INFO to SessionQueueProtocol.encodeSessionInfo("Host", 2),
            SessionGattUuids.QUEUE_STATE to SessionQueueProtocol.encodeQueueState(1, items),
            SessionGattUuids.CURRENT_CLIMB to SessionQueueProtocol.encodeQueueState(1, listOf(items[1])),
            SessionGattUuids.PARTICIPANT_LIST to SessionQueueProtocol.encodeParticipantList(listOf("Participant 1")),
        )
    }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun settle() = scheduler.runCurrent()

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    private fun crux(identifier: String, payload: ByteArray, rssi: Int = -55) {
        central.listener!!.onManufacturerData(identifier, NearbyClimbProtocol.COMPANY_ID, payload, rssi)
        settle()
    }

    private fun shown() {
        model.screenShown()
        settle()
    }

    @Test
    fun `without Bluetooth permission a board screen neither scans nor asks`() {
        allowed = false
        shown()
        assertEquals(0, central.activations, "no manager, so no permission prompt")
        assertFalse(central.scanning)
        assertFalse(model.currentState.scanning)
        assertFalse(model.currentState.hasContent(""))
    }

    @Test
    fun `Bluetooth allowed while a board screen shows starts the scan there`() {
        allowed = false
        shown()
        allowed = true
        model.permissionMayHaveChanged()
        settle()
        assertTrue(central.scanning)
        // Without a visible board screen nothing starts.
        model.screenHidden()
        advance(NearbyAreaModel.STOP_GRACE_MS)
        assertFalse(central.scanning)
        model.permissionMayHaveChanged()
        settle()
        assertFalse(central.scanning)
    }

    @Test
    fun `nothing nearby leaves the area empty`() {
        shown()
        assertTrue(central.scanning)
        assertTrue(model.currentState.scanning)
        assertFalse(model.currentState.hasContent(""))
    }

    @Test
    fun `someone climbing nearby is named and opens under the catalogue's id`() {
        shown()
        crux("phone", NearbyClimbProtocol.encodeClimbData(B, 30))
        val s = model.currentState
        assertTrue(s.hasContent(""))
        assertTrue(s.showsOnBoard(""))
        assertEquals("remoteActive", s.onBoardSourceCode)
        assertEquals("Bravo", s.onBoardName)
        assertEquals("6b", s.onBoardGrade)
        assertEquals(30, s.onBoardAngle)
        assertEquals(B_CATALOGUE, s.onBoardOpenUuid)
        assertTrue(s.disconnectRequestable)
        // On that climb's own detail page it is not repeated.
        assertFalse(s.showsOnBoard(B_CATALOGUE))
        assertFalse(s.hasContent(B_CATALOGUE))
    }

    @Test
    fun `an unknown climb cannot be opened`() {
        shown()
        crux("phone", NearbyClimbProtocol.encodeClimbData(UNKNOWN, 25))
        val s = model.currentState
        assertTrue(s.hasOnBoard)
        assertEquals("", s.onBoardName)
        assertEquals("", s.onBoardOpenUuid)
    }

    @Test
    fun `the climb turns into the board's last climb when its phone goes quiet`() {
        shown()
        crux("phone", NearbyClimbProtocol.encodeClimbData(B, 30))
        advance(4_000)
        val s = model.currentState
        assertEquals("remoteLast", s.onBoardSourceCode)
        assertTrue(s.onBoardStillProjected)
        assertFalse(s.disconnectRequestable)
    }

    @Test
    fun `an occupied board alone shows the area`() {
        shown()
        crux("other", NearbyClimbProtocol.encodeBoardConnected(senderToken = 7))
        val s = model.currentState
        assertFalse(s.hasOnBoard)
        assertEquals(1, s.boardOccupiedCount)
        assertTrue(s.hasContent(""))
    }

    @Test
    fun `the scan stops shortly after the last board screen went away`() {
        shown()
        shown()
        crux("phone", NearbyClimbProtocol.encodeClimbData(B, 30))
        model.screenHidden()
        advance(NearbyAreaModel.STOP_GRACE_MS)
        assertTrue(central.scanning, "another board screen is still visible")
        // Browser to detail: one hides before the other appears; the scan and its climb stay.
        model.screenHidden()
        advance(NearbyAreaModel.STOP_GRACE_MS - 1)
        shown()
        advance(NearbyAreaModel.STOP_GRACE_MS)
        assertTrue(central.scanning)
        assertTrue(model.currentState.hasOnBoard)

        model.screenHidden()
        advance(NearbyAreaModel.STOP_GRACE_MS)
        assertFalse(central.scanning)
        assertFalse(model.currentState.hasContent(""))
        // An unbalanced extra call is ignored.
        model.screenHidden()
        shown()
        assertTrue(central.scanning)
    }

    @Test
    fun `a shared playlist nearby is offered and joined from the area`() {
        shown()
        crux(HOST, NearbyClimbProtocol.encodeSessionAdvertisement(SESSION_ID, 2, "Anna"), rssi = -60)
        central.listener!!.onManufacturerData(HOST, NearbyClimbProtocol.SESSION_CLIMB_COMPANY_ID,
            NearbyClimbProtocol.encodeClimbData(B, 40), -60)
        settle()
        var s = model.currentState
        assertTrue(s.canJoin)
        assertEquals(listOf("Anna"), s.playlists.map { it.hostName })
        assertEquals("Bravo", s.playlists[0].climbName)
        // A non-participant sees the host's climb as the session climb.
        assertEquals("sessionRemote", s.onBoardSourceCode)
        assertFalse(s.inSession)

        model.join(HOST)
        settle()
        s = model.currentState
        assertEquals("joined", s.sessionPhaseCode)
        assertTrue(s.inSession)
        assertFalse(s.canJoin)
        assertEquals("Host", s.sessionHostName)
        assertEquals(2, s.sessionPosition)
        assertEquals(3, s.sessionCount)
        assertEquals("Bravo", s.sessionClimbName)
        assertTrue(s.sessionHasNext)
        // The joined host is not offered again, and its climb is the session row's, not repeated.
        assertTrue(s.playlists.isEmpty())
        assertFalse(s.hasOnBoard)
        assertFalse(s.boardDiffers(""))

        // Someone else writes the wall during the playlist: shown, and flagged as differing.
        crux("phone", NearbyClimbProtocol.encodeClimbData(C, 35))
        s = model.currentState
        assertEquals("remoteActive", s.onBoardSourceCode)
        assertTrue(s.boardDiffers(""))

        // The participant's Next goes to the host as a command.
        central.writes.clear()
        model.next()
        settle()
        assertEquals(1, central.writes.size)
        assertEquals(SessionGattUuids.QUEUE_COMMAND, central.writes.single().characteristic)
        val next = SessionQueueProtocol.decodeCommandRequest(central.writes.single().bytes.toByteArray())
        assertEquals(SessionCommand.Next, next?.command)

        // The detail's "+" asks the host to append the climb on screen.
        central.writes.clear()
        assertTrue(model.canAdd(C_CATALOGUE))
        assertFalse(model.canAdd("not-a-uuid"))
        model.add(C_CATALOGUE, 35)
        settle()
        val add = SessionQueueProtocol.decodeCommandRequest(central.writes.single().bytes.toByteArray())
        assertEquals(SessionCommand.Add(C, 35), add?.command)
    }

    @Test
    fun `nothing can be added without a joined playlist`() {
        shown()
        assertFalse(model.canAdd(C_CATALOGUE))
        model.add(C_CATALOGUE, 35)
        settle()
        assertTrue(central.writes.isEmpty())
    }

    @Test
    fun `joining is refused while a playlist is already joined`() {
        shown()
        crux(HOST, NearbyClimbProtocol.encodeSessionAdvertisement(SESSION_ID, 2, "Anna"), rssi = -60)
        crux(OTHER_HOST, NearbyClimbProtocol.encodeSessionAdvertisement(SESSION_ID + 1, 1, "Ben"), rssi = -70)
        model.join(HOST)
        settle()
        val connects = central.connects.size
        model.join(OTHER_HOST)
        settle()
        assertEquals(connects, central.connects.size)
        assertEquals(listOf("Ben"), model.currentState.playlists.map { it.hostName })
    }

    @Test
    fun `this phone's live climb is its own on the board and the area gives way to hold cleaning`() {
        val climb = MutableStateFlow<com.cruxcoach.app.nearby.LocalBoardClimb?>(null)
        val linked = MutableStateFlow<String?>("board-1")
        val cleaning = MutableStateFlow(false)
        model.followOwnBoard(climb, linked, cleaning)
        shown()
        assertFalse(model.currentState.hasOnBoard)

        climb.value = com.cruxcoach.app.nearby.LocalBoardClimb(A, 40, "Stored name", 1L, true, "board-1")
        settle()
        var s = model.currentState
        assertEquals("localActive", s.onBoardSourceCode)
        assertEquals("Alpha", s.onBoardName, "the catalogue label, looked up like a scanned climb")
        assertEquals("6a", s.onBoardGrade)
        assertEquals(A_CATALOGUE, s.onBoardOpenUuid)
        assertTrue(s.showsOnBoard(""))
        assertFalse(s.showsOnBoard(B_CATALOGUE), "the detail never repeats this phone's live climb")

        // The link went down: the last climb on this phone, still shown on the detail.
        linked.value = null
        settle()
        s = model.currentState
        assertEquals("localManager", s.onBoardSourceCode)
        assertTrue(s.onBoardStillProjected)
        assertTrue(s.showsOnBoard(B_CATALOGUE))
        assertFalse(s.showsOnBoard(A_CATALOGUE))

        // A climb outside this phone's catalogue keeps the name it was sent with.
        climb.value = com.cruxcoach.app.nearby.LocalBoardClimb(UNKNOWN, 40, "Stored name", 2L, false, "")
        settle()
        s = model.currentState
        assertEquals("Stored name", s.onBoardName)
        assertEquals(UNKNOWN, s.onBoardOpenUuid)
        assertFalse(s.onBoardStillProjected)

        assertFalse(s.cleaningActive)
        cleaning.value = true
        settle()
        assertTrue(model.currentState.cleaningActive)
    }

    @Test
    fun `a running local playlist owns the wall so this phone's send of it is not repeated`() {
        val climb = MutableStateFlow<com.cruxcoach.app.nearby.LocalBoardClimb?>(
            com.cruxcoach.app.nearby.LocalBoardClimb(A, 40, "", 1L, true, "board-1"),
        )
        val playlist = MutableStateFlow<Pair<String, Int>?>(null)
        model.followOwnBoard(climb, MutableStateFlow<String?>("board-1"), MutableStateFlow(false))
        model.followPlaylist(playlist)
        shown()
        assertEquals("localActive", model.currentState.onBoardSourceCode)

        playlist.value = A_CATALOGUE to 40
        settle()
        assertFalse(model.currentState.hasOnBoard, "the playlist banner already names it")
        assertFalse(model.currentState.inSession, "a local playlist is not a joined one")

        // Someone else writing the wall still shows (Android never suppresses REMOTE_ACTIVE).
        crux("phone", NearbyClimbProtocol.encodeClimbData(C, 35))
        assertEquals("remoteActive", model.currentState.onBoardSourceCode)

        playlist.value = null
        settle()
        assertEquals("remoteActive", model.currentState.onBoardSourceCode)
    }

    private class FakeSessionClock : BoardSessionClock {
        override var isActive = false
        override var isPaused = false
        var starts = 0
        var ends = 0
        override fun start() {
            starts++
            isActive = true
        }
        override fun end() {
            ends++
            isActive = false
        }
        override fun elapsedSeconds(): Int = if (isActive) 75 else 0
    }

    @Test
    fun `joining a playlist starts a board session and leaving it ends it`() {
        val clock = FakeSessionClock()
        model.useBoardSessionClock(clock)
        shown()
        crux(HOST, NearbyClimbProtocol.encodeSessionAdvertisement(SESSION_ID, 2, "Anna"), rssi = -60)
        assertFalse(model.currentState.sessionTimerActive)
        assertEquals(0, clock.starts)

        model.join(HOST)
        settle()
        var s = model.currentState
        assertEquals("joined", s.sessionPhaseCode)
        assertEquals(1, clock.starts, "Android joinNearbySession starts the board session")
        assertTrue(s.sessionTimerActive)
        assertFalse(s.sessionTimerPaused)
        assertEquals(75, model.sessionElapsedSeconds())

        presenter.participant.leave()
        settle()
        s = model.currentState
        assertFalse(s.inSession)
        assertEquals(1, clock.ends)
        assertFalse(s.sessionTimerActive)
        assertEquals(0, model.sessionElapsedSeconds())
    }

    @Test
    fun `a board session that already runs is neither taken over nor ended`() {
        val clock = FakeSessionClock().apply { isActive = true }
        model.useBoardSessionClock(clock)
        shown()
        crux(HOST, NearbyClimbProtocol.encodeSessionAdvertisement(SESSION_ID, 2, "Anna"), rssi = -60)
        model.join(HOST)
        settle()
        assertEquals(0, clock.starts)
        assertTrue(model.currentState.sessionTimerActive)
        presenter.participant.leave()
        settle()
        assertEquals(0, clock.ends, "a playlist run's session is the playlist's to end")
        assertTrue(clock.isActive)
    }

    companion object {
        const val HOST = "11111111-1111-1111-1111-111111111111"
        const val OTHER_HOST = "33333333-3333-3333-3333-333333333333"
        const val SESSION_ID = 1234
        const val A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        const val C = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"
        const val UNKNOWN = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD"
        const val A_CATALOGUE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val B_CATALOGUE = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val C_CATALOGUE = "cccccccc-cccc-cccc-cccc-cccccccccccc"
    }
}
