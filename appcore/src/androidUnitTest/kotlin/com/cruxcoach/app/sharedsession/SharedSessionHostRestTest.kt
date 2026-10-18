package com.cruxcoach.app.sharedsession

import com.cruxcoach.app.athlete.TrainingRestTimer
import com.cruxcoach.app.ble.FakeBleCentral
import com.cruxcoach.app.nearby.NearbyClimbProtocol
import com.cruxcoach.app.sharedsession.SharedSessionParticipantTest.Companion.A
import com.cruxcoach.app.sharedsession.SharedSessionParticipantTest.Companion.B
import com.cruxcoach.app.sharedsession.SharedSessionParticipantTest.Companion.C
import com.cruxcoach.app.sharedsession.SharedSessionParticipantTest.Companion.HOST
import com.cruxcoach.app.sharedsession.SharedSessionParticipantTest.Companion.SESSION_ID
import com.cruxcoach.app.sharedsession.SharedSessionParticipantTest.Companion.SESSION_SERVICES
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

/**
 * Android's participant drives its OWN rest timer from the host's rest
 * (`SessionGattBridge.applyRemoteEvent`): RestStarted starts it with the seconds the host had
 * left, RestEnded cancels it, leaving or the host ending the session cancels it, a lost host
 * does not. The iPhone's own timer feeds the app-wide rest banner.
 */
class SharedSessionHostRestTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val central = FakeBleCentral { scheduler.currentTime }
    private val timer = TrainingRestTimer(scope, { null }, { scheduler.currentTime })
    private val presenter = SharedSessionsPresenter(
        central, scope, dispatcher, { scheduler.currentTime }, ClimbLookup { _, _ -> null },
        hostRest = RestTimerHostRest(timer),
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

    private fun notify(uuid: String, bytes: ByteArray) {
        central.listener!!.onNotification(HOST, uuid, bytes)
        settle()
    }

    private fun joined() {
        model.startScan()
        settle()
        central.listener!!.onManufacturerData(HOST, NearbyClimbProtocol.COMPANY_ID,
            NearbyClimbProtocol.encodeSessionAdvertisement(SESSION_ID, 2, "Anna"), -60)
        settle()
        model.join(HOST)
        settle()
        assertEquals("joined", model.currentState.phaseCode)
    }

    private fun hostRest(seconds: Int) =
        notify(SessionGattUuids.QUEUE_EVENT, SessionQueueProtocol.encodeEventRestStarted(seconds, 2))

    @Test
    fun `the host rest runs on the phone's own rest timer and ends with the host`() {
        joined()
        assertFalse(timer.restTimer.value.isRunning)
        hostRest(90)
        var rest = timer.restTimer.value
        assertTrue(rest.isRunning)
        assertEquals(90, rest.secondsRemaining)
        assertEquals(90, rest.totalSeconds)
        advance(10_000)
        assertEquals(80, timer.restTimer.value.secondsRemaining)
        assertEquals(80, model.currentState.restSeconds)

        notify(SessionGattUuids.QUEUE_EVENT, SessionQueueProtocol.encodeEventRestEnded())
        rest = timer.restTimer.value
        assertFalse(rest.isRunning)
        assertFalse(rest.isFinished)
    }

    @Test
    fun `a host rest of zero seconds does not start the timer`() {
        joined()
        hostRest(0)
        assertFalse(timer.restTimer.value.isRunning)
    }

    @Test
    fun `leaving the session ends the host rest`() {
        joined()
        hostRest(60)
        model.leave()
        settle()
        assertFalse(timer.restTimer.value.isRunning)
    }

    @Test
    fun `the host ending the session for everyone ends the rest`() {
        joined()
        hostRest(60)
        notify(SessionGattUuids.SESSION_INFO, SessionQueueProtocol.encodeSessionEnded(migrate = false))
        assertEquals("endedByHost", model.currentState.endCode)
        assertFalse(timer.restTimer.value.isRunning)
    }

    @Test
    fun `a lost host leaves the rest counting as on Android`() {
        joined()
        hostRest(60)
        central.listener!!.onDisconnected(HOST)
        settle()
        advance(SharedSessionParticipant.MIGRATION_BASE_DELAY_MS)
        assertEquals("hostLeft", model.currentState.endCode)
        assertTrue(timer.restTimer.value.isRunning)
        assertEquals(59, timer.restTimer.value.secondsRemaining)
    }

    @Test
    fun `leaving without a host rest leaves the phone's own rest alone`() {
        timer.startRestTimer(120)
        joined()
        model.leave()
        settle()
        assertTrue(timer.restTimer.value.isRunning, "only a rest the host started ends with the session")
    }
}
