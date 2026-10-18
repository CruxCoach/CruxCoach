package com.cruxcoach.app.sharedsession

import com.cruxcoach.app.ble.BleServiceInfo
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.ble.FakeBleCentral.Companion.characteristic
import com.cruxcoach.app.nearby.NearbyClimbProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fake-central tests of the iPhone participant: the host side is played by Android's own
 * encoders (verified by the ported protocol tests), the radio by [FakeBleCentral].
 */
class SharedSessionParticipantTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val central = FakeBleCentral { scheduler.currentTime }
    private val catalogue = mapOf(
        A to ClimbLabel("Alpha", "6a"),
        B to ClimbLabel("Bravo", "6b"),
        C to ClimbLabel("Charlie", "6c"),
    )
    private val presenter = SharedSessionsPresenter(
        central, scope, dispatcher, { scheduler.currentTime },
        ClimbLookup { uuid, _ -> catalogue[uuid.replace("-", "").uppercase()] },
    )
    private val model = SharedSessionsModel(presenter)
    private val items = listOf(QueueItem(A, 40), QueueItem(B, 40), QueueItem(C, 35))

    init {
        central.services = SESSION_SERVICES
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

    private fun advertise(identifier: String, sessionId: Int, host: String, rssi: Int = -60) {
        central.listener!!.onManufacturerData(identifier, NearbyClimbProtocol.COMPANY_ID,
            NearbyClimbProtocol.encodeSessionAdvertisement(sessionId, 2, host), rssi)
        settle()
    }

    private fun notify(uuid: String, bytes: ByteArray) {
        central.listener!!.onNotification(HOST, uuid, bytes)
        settle()
    }

    private fun joined() {
        model.startScan()
        settle()
        advertise(HOST, SESSION_ID, "Anna")
        model.join(HOST)
        settle()
        assertEquals("joined", model.currentState.phaseCode)
        central.operations.clear()
        central.writes.clear()
    }

    private fun lastRequest(): SessionCommandRequest =
        assertNotNull(SessionQueueProtocol.decodeCommandRequest(central.writes.last().bytes.toByteArray()))

    @Test
    fun `scan lists an Android session with the climb of its scan response`() {
        model.startScan()
        settle()
        assertTrue(central.scanning)
        advertise(HOST, SESSION_ID, "Anna")
        // CoreBluetooth may deliver the 0xFFFE scan response in its own callback.
        central.listener!!.onManufacturerData(HOST, NearbyClimbProtocol.SESSION_CLIMB_COMPANY_ID,
            NearbyClimbProtocol.encodeClimbData(A, 40), -60)
        settle()
        // Below Android's -82 dBm floor: ignored.
        advertise("weak", 77, "Far", rssi = -90)
        settle()
        val sessions = model.currentState.sessions
        assertEquals(1, sessions.size)
        assertEquals("Anna", sessions[0].hostName)
        assertEquals(2, sessions[0].participantCount)
        assertTrue(sessions[0].hasClimb)
        assertEquals("Alpha", sessions[0].climbName)
        assertEquals(40, sessions[0].climbAngle)
    }

    @Test
    fun `join subscribes sends JOIN reads the state and becomes a participant`() {
        model.startScan()
        settle()
        advertise(HOST, SESSION_ID, "Anna")
        model.join(HOST)
        assertEquals("connecting", model.currentState.phaseCode)
        settle()

        val notifies = central.operations.filter { it.startsWith("notify:") }
        assertEquals(5, notifies.size)
        val joinIndex = central.operations.indexOfFirst { it == "write:${SessionGattUuids.QUEUE_COMMAND}" }
        assertTrue(joinIndex > central.operations.indexOf(notifies.last()), "JOIN only after the subscriptions")
        assertEquals(SessionQueueProtocol.encodeJoin("").toList(), central.writes.first().bytes)
        assertTrue(central.writes.first().withResponse)
        val reads = central.operations.filter { it.startsWith("read:") }
        assertEquals(
            listOf(SessionGattUuids.SESSION_INFO, SessionGattUuids.QUEUE_STATE, SessionGattUuids.CURRENT_CLIMB,
                SessionGattUuids.PARTICIPANT_LIST).map { "read:$it" },
            reads,
        )
        assertTrue(central.operations.indexOf(reads.first()) > joinIndex)

        val s = model.currentState
        assertEquals("joined", s.phaseCode)
        assertEquals("Host", s.hostName)
        assertEquals(2, s.participantCount)
        assertEquals(listOf("Participant 1"), s.participants)
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), s.rows.map { it.name })
        assertEquals(1, s.currentIndex)
        assertTrue(s.rows[1].isCurrent)
        assertTrue(s.hasPrevious && s.hasNext && s.canEdit)
        // The joined host is on screen, not offered again.
        assertTrue(s.sessions.isEmpty())
        assertEquals(SESSION_ID, presenter.participant.state.value.sessionId)
    }

    @Test
    fun `next carries the semantic context and a conflict result clears the pending command`() {
        joined()
        model.next()
        settle()
        val request = lastRequest()
        assertEquals(SessionCommand.Next, request.command)
        val context = assertNotNull(request.context)
        assertEquals(SESSION_ID, context.sessionId)
        assertEquals(SessionItemRef(B, 40, 0, 1), context.expectedCurrent)
        assertEquals(SessionItemRef(C, 35, 0, 1), context.expectedTarget)
        assertEquals(1, model.currentState.pendingCommands)

        notify(SessionGattUuids.QUEUE_EVENT,
            SessionQueueProtocol.encodeEventCommandResult(assertNotNull(request.requestId), SessionCommandResult.CONFLICT))
        assertEquals(0, model.currentState.pendingCommands)
        assertEquals("conflict", model.currentState.feedbackCode)
        assertEquals(1, model.currentState.feedbackSeq)
    }

    @Test
    fun `a result an old host never sends ages out after five seconds without a retry`() {
        joined()
        model.remove(0)
        settle()
        assertEquals(1, model.currentState.pendingCommands)
        advance(SharedSessionParticipant.COMMAND_RESULT_TIMEOUT_MS)
        assertEquals(0, model.currentState.pendingCommands)
        assertEquals("none", model.currentState.feedbackCode)
        assertEquals(1, central.writes.size)
    }

    @Test
    fun `a failed write says the host is unavailable`() {
        joined()
        central.writeSucceeds = false
        model.select(2)
        settle()
        assertEquals("unavailable", model.currentState.feedbackCode)
        assertEquals(0, model.currentState.pendingCommands)
    }

    @Test
    fun `a link too small for the context sends the legacy command`() {
        joined()
        central.maxWriteLength = 20
        model.select(2)
        settle()
        assertEquals(SessionQueueProtocol.encodeSetCurrent(2).toList(), central.writes.last().bytes)
        assertEquals(0, model.currentState.pendingCommands)
    }

    @Test
    fun `a move to an index the queue does not have is a local conflict`() {
        joined()
        model.move(0, 9)
        settle()
        assertTrue(central.writes.isEmpty())
        assertEquals("conflict", model.currentState.feedbackCode)
    }

    @Test
    fun `add sends the climb and a non uuid id is refused`() {
        joined()
        assertTrue(model.canAdd("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(model.canAdd("12345"))
        model.add("550e8400-e29b-41d4-a716-446655440000", 45)
        settle()
        assertEquals(SessionCommand.Add("550E8400E29B41D4A716446655440000", 45), lastRequest().command)
        model.add("12345", 40)
        settle()
        assertEquals(1, central.writes.size)
        assertEquals("failed", model.currentState.feedbackCode)
    }

    @Test
    fun `resend asks the host to light the current climb again`() {
        joined()
        model.resend()
        settle()
        val request = lastRequest()
        assertEquals(SessionCommand.Resend, request.command)
        assertEquals(SessionCommandContext(SESSION_ID), request.context)
    }

    @Test
    fun `a command before the join completed is not sent`() {
        central.onConnect = { }
        model.startScan()
        settle()
        model.join(HOST)
        settle()
        model.next()
        settle()
        assertTrue(central.writes.isEmpty())
        assertEquals("unavailable", model.currentState.feedbackCode)
    }

    @Test
    fun `a paged queue is applied only once every page arrived`() {
        joined()
        val long = (0 until 40).map { QueueItem(A, it) }
        notify(SessionGattUuids.QUEUE_STATE, SessionQueueProtocol.encodeQueueState(5, long, page = 0))
        assertEquals(3, model.currentState.rows.size)
        notify(SessionGattUuids.QUEUE_STATE, SessionQueueProtocol.encodeQueueState(5, long, page = 1))
        assertEquals(40, model.currentState.rows.size)
        assertEquals(5, model.currentState.currentIndex)
    }

    @Test
    fun `a notification cut at the MTU is recovered by one long read`() {
        joined()
        val four = items + QueueItem(A, 20)
        central.reads[SessionGattUuids.QUEUE_STATE] = SessionQueueProtocol.encodeQueueState(3, four)
        val cut = SessionQueueProtocol.encodeQueueState(3, four).copyOf(4 + 17)
        notify(SessionGattUuids.QUEUE_STATE, cut)
        settle()
        assertEquals(listOf("read:${SessionGattUuids.QUEUE_STATE}"), central.operations.filter { it.startsWith("read:") })
        assertEquals(4, model.currentState.rows.size)
        assertEquals(3, model.currentState.currentIndex)
        // A second cut frame right away does not loop reads.
        notify(SessionGattUuids.QUEUE_STATE, cut)
        assertEquals(1, central.operations.count { it.startsWith("read:") })
    }

    @Test
    fun `current climb notifications follow the host and drop unknown indices`() {
        joined()
        notify(SessionGattUuids.CURRENT_CLIMB, SessionQueueProtocol.encodeQueueState(2, listOf(items[2])))
        assertEquals(2, model.currentState.currentIndex)
        notify(SessionGattUuids.CURRENT_CLIMB, byteArrayOf(9, 0, 1, 0))
        assertEquals(2, model.currentState.currentIndex)
        notify(SessionGattUuids.CURRENT_CLIMB, byteArrayOf(0xFF.toByte(), 1))
        assertTrue(model.currentState.externalBoardOverride)
        notify(SessionGattUuids.QUEUE_EVENT, SessionQueueProtocol.encodeEventRemoved(0))
        assertEquals(listOf("Bravo", "Charlie"), model.currentState.rows.map { it.name })
        assertEquals(1, model.currentState.currentIndex)
    }

    @Test
    fun `the host rest phase counts down and ends`() {
        joined()
        notify(SessionGattUuids.QUEUE_EVENT, SessionQueueProtocol.encodeEventRestStarted(30, 2))
        assertEquals(2, model.currentState.currentIndex)
        assertEquals(30, model.currentState.restSeconds)
        advance(1000)
        assertEquals(29, model.currentState.restSeconds)
        notify(SessionGattUuids.QUEUE_EVENT, SessionQueueProtocol.encodeEventRestEnded())
        assertEquals(0, model.currentState.restSeconds)
    }

    @Test
    fun `the final sentinel ends the playlist for everyone`() {
        joined()
        notify(SessionGattUuids.SESSION_INFO, SessionQueueProtocol.encodeSessionEnded(migrate = false))
        val s = model.currentState
        assertEquals("ended", s.phaseCode)
        assertEquals("endedByHost", s.endCode)
        assertTrue(s.rows.isEmpty())
        assertEquals(1, central.cancels.size)
        model.dismissEnded()
        assertEquals("idle", model.currentState.phaseCode)
    }

    @Test
    fun `a handover offers the strongest other session and joins it only on consent`() {
        joined()
        advertise(SUCCESSOR, 4242, "Ben ")
        notify(SessionGattUuids.SESSION_INFO, SessionQueueProtocol.encodeSessionEnded(migrate = true))
        assertEquals("hostLost", model.currentState.phaseCode)
        assertEquals(1, central.cancels.size)
        advance(SharedSessionParticipant.MIGRATION_BASE_DELAY_MS - 1)
        assertFalse(model.currentState.hasSuccessor)
        advance(1)
        assertTrue(model.currentState.hasSuccessor)
        assertEquals("Ben", model.currentState.successorHostName)
        assertEquals(1, central.connects.size, "never joins on its own")

        model.acceptSuccessor()
        settle()
        assertEquals(2, central.connects.size)
        assertEquals("joined", model.currentState.phaseCode)
        assertEquals(4242, presenter.participant.state.value.sessionId)
    }

    @Test
    fun `losing the host without a successor ends with the queue kept on screen`() {
        joined()
        central.listener!!.onDisconnected(HOST)
        settle()
        assertEquals("hostLost", model.currentState.phaseCode)
        advance(SharedSessionParticipant.MIGRATION_BASE_DELAY_MS)
        val s = model.currentState
        assertEquals("ended", s.phaseCode)
        assertEquals("hostLeft", s.endCode)
        assertEquals(3, s.rows.size)
        assertFalse(s.canEdit)
    }

    @Test
    fun `a declined successor ends the session`() {
        joined()
        advertise(SUCCESSOR, 4242, "Ben")
        central.listener!!.onDisconnected(HOST)
        settle()
        advance(SharedSessionParticipant.MIGRATION_BASE_DELAY_MS)
        model.declineSuccessor()
        settle()
        assertEquals("hostLeft", model.currentState.endCode)
        assertEquals(1, central.connects.size)
    }

    @Test
    fun `leave sends LEAVE and disconnects after the grace period`() {
        joined()
        model.leave()
        assertEquals("idle", model.currentState.phaseCode)
        settle()
        assertEquals(SessionQueueProtocol.encodeLeave().toList(), central.writes.last().bytes)
        assertTrue(central.cancels.isEmpty())
        advance(SharedSessionParticipant.LEAVE_GRACE_MS)
        assertEquals(1, central.cancels.size)
        assertEquals("idle", model.currentState.phaseCode)
    }

    @Test
    fun `a failed connection ends with connect failed`() {
        central.onConnect = { central.listener?.onDisconnected(it) }
        model.startScan()
        settle()
        advertise(HOST, SESSION_ID, "Anna")
        model.join(HOST)
        settle()
        assertEquals("ended", model.currentState.phaseCode)
        assertEquals("connectFailed", model.currentState.endCode)
    }

    @Test
    fun `a peripheral without the session service is not joined`() {
        central.services = FakeBleCentral.AURORA_SERVICES
        model.startScan()
        settle()
        model.join(HOST)
        settle()
        assertEquals("connectFailed", model.currentState.endCode)
        assertEquals(1, central.cancels.size)
        assertTrue(central.writes.isEmpty())
    }

    @Test
    fun `a host that never answers times out after fifteen seconds`() {
        central.onConnect = { }
        model.startScan()
        settle()
        model.join(HOST)
        settle()
        advance(SessionGattClient.CONNECTION_TIMEOUT_MS - 1)
        assertEquals("connecting", model.currentState.phaseCode)
        advance(1)
        assertEquals("connectFailed", model.currentState.endCode)
        assertEquals(1, central.cancels.size)
    }

    @Test
    fun `someone climbing nearby and an occupied board are listed until they go`() {
        model.startScan()
        settle()
        central.listener!!.onManufacturerData("phone", NearbyClimbProtocol.COMPANY_ID, NearbyClimbProtocol.encodeClimbData(B, 30), -50)
        central.listener!!.onManufacturerData("other", NearbyClimbProtocol.COMPANY_ID,
            NearbyClimbProtocol.encodeBoardConnected(senderToken = 7), -55)
        settle()
        val climbs = model.currentState.climbs
        assertEquals(listOf("active", "occupied"), climbs.map { it.kindCode })
        assertEquals("Bravo", climbs[0].name)
        assertTrue(climbs[0].known)
        assertEquals(30, climbs[0].angle)

        central.listener!!.onManufacturerData("other", NearbyClimbProtocol.COMPANY_ID, NearbyClimbProtocol.encodeGone(), -55)
        settle()
        assertEquals(listOf("active"), model.currentState.climbs.map { it.kindCode })
        // Advertising stopped without GONE: after 3 s the climb is only the board's last climb.
        advance(4000)
        assertTrue(model.currentState.climbs.isEmpty())
    }

    @Test
    fun `leaving the screen stops the scan unless a session is joined`() {
        model.startScan()
        settle()
        model.stopScan()
        settle()
        assertFalse(central.scanning)
        assertFalse(model.currentState.scanning)
        joined()
        model.stopScan()
        settle()
        assertTrue(central.scanning)
    }

    companion object {
        const val HOST = "11111111-1111-1111-1111-111111111111"
        const val SUCCESSOR = "22222222-2222-2222-2222-222222222222"
        const val SESSION_ID = 1234
        const val A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"
        const val C = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC"

        val SESSION_SERVICES = listOf(
            BleServiceInfo(
                SessionGattUuids.SERVICE,
                listOf(
                    characteristic(SessionGattUuids.SESSION_INFO, read = true, notify = true),
                    characteristic(SessionGattUuids.CURRENT_CLIMB, read = true, notify = true),
                    characteristic(SessionGattUuids.QUEUE_STATE, read = true, notify = true),
                    characteristic(SessionGattUuids.QUEUE_EVENT, read = true, notify = true),
                    characteristic(SessionGattUuids.PARTICIPANT_LIST, read = true, notify = true),
                    characteristic(SessionGattUuids.QUEUE_COMMAND, write = true),
                ),
            ),
        )
    }
}
