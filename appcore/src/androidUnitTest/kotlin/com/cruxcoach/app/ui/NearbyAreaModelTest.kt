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
