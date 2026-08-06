package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.data.repository.BoardRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A participant must not resolve a rest by itself.
 *
 * From the two-device test on 2026-08-06: the host counts down while driving
 * the wall, so the pause is shared state, not personal pacing. Anything a
 * participant decides locally — skipping the rest, advancing past it — leaves
 * this device climbing a wall that is still resting, and the two screens then
 * disagree with no way back.
 *
 * Every control therefore asks the host, and the participant's own timer is
 * driven purely by what the host broadcasts back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistParticipantRestTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var scope: CoroutineScope
    private lateinit var queueManager: SessionQueueManager
    private lateinit var coordinator: PlaylistPlaybackCoordinator

    private val bleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val climbNameResolver = mockk<ClimbNameResolver>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val boardSessionManager = mockk<BoardSessionManager>(relaxed = true)
    private val gattBridge = mockk<SessionGattBridge>(relaxed = true)
    private val bleShareManager = mockk<BleShareManager>(relaxed = true)

    private val sessionState = MutableStateFlow(BoardSessionState())
    private val restState = MutableStateFlow(RestTimerState())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { boardSessionManager.state } returns sessionState
        every { boardSessionManager.restTimer } returns restState
        every { gattBridge.onRemoteNext = any() } answers { }
        every { gattBridge.onRemotePrev = any() } answers { }
        scope = CoroutineScope(SupervisorJob() + testDispatcher)
        queueManager = SessionQueueManager(
            bleConnection, boardRepository, climbNameResolver, userPreferences, scope,
        )
        coordinator = PlaylistPlaybackCoordinator(
            queueManager, boardSessionManager, gattBridge, bleShareManager,
            bleConnection, scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        Thread.sleep(50)
        Dispatchers.resetMain()
    }

    /** Queue loaded, role switched to participant, host currently resting. */
    private fun participantInRest() {
        queueManager.loadPlaylist("L", listOf(QueueItem("a", 40), QueueItem("b", 40)))
        queueManager.setParticipantRole(sessionId = 7, hostName = "Host")
        restState.value = RestTimerState(isRunning = true, secondsRemaining = 20, totalSeconds = 30)
        repeat(50) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (coordinator.state.value.isResting && coordinator.state.value.isParticipant) return
            Thread.sleep(5)
        }
    }

    @Test
    fun `skipping a rest asks the host instead of cancelling locally`() {
        participantInRest()
        assertTrue("precondition: participant is resting", coordinator.state.value.isResting)

        coordinator.skipRest()

        // The host's next() skips its own rest and broadcasts RestEnded, which
        // is what stops this device's countdown. Cancelling here would end the
        // rest on this screen only, while the wall keeps the countdown and the
        // upcoming climb lit.
        verify(exactly = 1) { gattBridge.sendNext() }
        verify(exactly = 0) { boardSessionManager.cancelRestTimer() }
    }

    @Test
    fun `next during a rest asks the host instead of resolving locally`() {
        participantInRest()

        coordinator.next()

        verify(exactly = 1) { gattBridge.sendNext() }
        verify(exactly = 0) { boardSessionManager.cancelRestTimer() }
    }

    @Test
    fun `previous during a rest asks the host too`() {
        participantInRest()

        coordinator.previous()

        verify(exactly = 1) { gattBridge.sendPrev() }
        verify(exactly = 0) { boardSessionManager.cancelRestTimer() }
    }

    @Test
    fun `a resting participant still offers next — the button is what skips`() {
        participantInRest()
        // Before rests were transmitted the participant sat at the end of a
        // shorter queue with a dead button, which is what "weiterklicken
        // funktioniert nicht" actually was.
        assertTrue(coordinator.state.value.hasNext)
    }

    @Test
    fun `the host still resolves its own rest without any GATT traffic`() {
        // The guard must not cost the host its local behaviour: it owns the
        // wall and the countdown, so it decides directly.
        queueManager.loadPlaylist("L", listOf(QueueItem("a", 40), QueueItem("b", 40)))
        restState.value = RestTimerState(isRunning = true, secondsRemaining = 20, totalSeconds = 30)
        repeat(50) {
            testDispatcher.scheduler.advanceUntilIdle()
            if (coordinator.state.value.isResting) return@repeat
            Thread.sleep(5)
        }

        coordinator.skipRest()

        verify(exactly = 1) { boardSessionManager.cancelRestTimer() }
        verify(exactly = 0) { gattBridge.sendNext() }
    }
}
