package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ListPlaybackAdvance
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [PlaylistPlaybackCoordinator] — the single control surface for playlist
 * playback. Role-aware routing (host mutates locally, participant sends
 * GATT commands) and the combined state snapshot are what every UI relies
 * on, so they get direct coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistPlaybackCoordinatorTest {

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
        scope = CoroutineScope(SupervisorJob() + testDispatcher)
        queueManager = SessionQueueManager(
            bleConnection, boardRepository, climbNameResolver, userPreferences, scope,
        )
        coordinator = PlaylistPlaybackCoordinator(
            queueManager, boardSessionManager, gattBridge, bleShareManager, scope,
        )
    }

    /** combine() funnels through internal coroutines whose resumption
     *  isn't strictly synchronous even on the unconfined dispatcher —
     *  poll briefly instead of racing it. */
    private fun awaitState(
        predicate: (PlaylistPlaybackState) -> Boolean,
    ): PlaylistPlaybackState {
        repeat(100) {
            testDispatcher.scheduler.advanceUntilIdle()
            val s = coordinator.state.value
            if (predicate(s)) return s
            Thread.sleep(5)
        }
        return coordinator.state.value
    }

    @After
    fun tearDown() {
        scope.cancel()
        // The queue manager's name-resolve collector hops through
        // Dispatchers.IO; give that continuation a beat to observe the
        // cancellation BEFORE resetMain(), otherwise it resumes onto a
        // torn-down Main and leaks UncaughtExceptionsBeforeTest into
        // whichever test runs next in this JVM (same race the
        // SessionQueueManagerTest tearDown comment describes).
        Thread.sleep(50)
        Dispatchers.resetMain()
    }

    @Test
    fun `state combines queue, timer and rest phase`() {
        queueManager.loadPlaylist("Playlist", listOf(QueueItem("a", 40), QueueItem("b", 40)))
        sessionState.value = BoardSessionState(isActive = true, elapsedSeconds = 90)

        val s = awaitState { it.isActive && it.queue.size == 2 }
        assertTrue(s.isActive)
        assertTrue(s.isHost)
        assertEquals(2, s.queue.size)
        assertEquals(0, s.currentIndex)
        assertEquals("b", s.upNext?.climbUuid)
        assertEquals(90, s.elapsedSeconds)
        assertTrue(s.phase is PlaybackPhase.Climbing)

        restState.value = RestTimerState(isRunning = true, secondsRemaining = 120, totalSeconds = 180)
        val resting = awaitState { it.isResting }.phase
        assertTrue(resting is PlaybackPhase.Resting)
        assertEquals(120, (resting as PlaybackPhase.Resting).secondsRemaining)
    }

    @Test
    fun `host next-previous mutate the queue locally`() {
        queueManager.loadPlaylist("Playlist", listOf(QueueItem("a", 40), QueueItem("b", 40)))

        coordinator.next()
        assertEquals(1, queueManager.state.value.currentIndex)
        coordinator.previous()
        assertEquals(0, queueManager.state.value.currentIndex)
        verify(exactly = 0) { gattBridge.sendNext() }
        verify(exactly = 0) { gattBridge.sendPrev() }
    }

    @Test
    fun `participant next-previous route via GATT`() {
        queueManager.setParticipantRole(1, "Host")
        queueManager.applyRemoteState(0, listOf(QueueItem("a", 40), QueueItem("b", 40)))

        coordinator.next()
        coordinator.previous()
        coordinator.setCurrent(1)

        verify(exactly = 1) { gattBridge.sendNext() }
        verify(exactly = 1) { gattBridge.sendPrev() }
        verify(exactly = 1) { gattBridge.sendSetCurrent(1) }
        // The local queue must NOT move — the host echoes the change back.
        assertEquals(0, queueManager.state.value.currentIndex)
    }

    @Test
    fun `stop as host ends sharing and queue, stop as participant leaves`() {
        queueManager.loadPlaylist("Playlist", listOf(QueueItem("a", 40)))
        coordinator.stop()
        verify(exactly = 1) { gattBridge.stopSharing() }
        assertFalse(queueManager.state.value.isActive)

        queueManager.setParticipantRole(1, "Host")
        coordinator.stop()
        verify(exactly = 1) { gattBridge.leaveSession() }
    }

    @Test
    fun `play wires the rest hook and starts the session`() {
        every { bleShareManager.uiState } returns MutableStateFlow(
            mockk(relaxed = true) { every { sharingEnabled } returns false }
        )
        coordinator.play("Playlist", listOf(QueueItem("a", 40)))

        verify(exactly = 1) { boardSessionManager.startSession() }
        assertTrue(queueManager.isPlaylistQueue)
        // Rest hook is wired to the session manager's rest timer.
        queueManager.onRestRequested?.invoke(45)
        verify(exactly = 1) { boardSessionManager.startRestTimer(45) }
        // Sharing disabled → no advertising.
        verify(exactly = 0) { gattBridge.startSharing() }
    }

    @Test
    fun `after-send mode advances only after a logged send`() {
        every { bleShareManager.uiState } returns MutableStateFlow(
            mockk(relaxed = true) { every { sharingEnabled } returns false }
        )
        coordinator.play(
            "Training",
            listOf(QueueItem("a", 40), QueueItem("b", 40)),
            ListPlaybackAdvance.AFTER_SEND,
        )

        coordinator.onClimbLogged(isSend = false)
        assertEquals(0, queueManager.state.value.currentIndex)
        coordinator.onClimbLogged(isSend = true)
        assertEquals(1, queueManager.state.value.currentIndex)
    }

    @Test
    fun `after-log mode advances after an attempt`() {
        every { bleShareManager.uiState } returns MutableStateFlow(
            mockk(relaxed = true) { every { sharingEnabled } returns false }
        )
        coordinator.play(
            "Training",
            listOf(QueueItem("a", 40), QueueItem("b", 40)),
            ListPlaybackAdvance.AFTER_LOG,
        )

        coordinator.onClimbLogged(isSend = false)
        assertEquals(1, queueManager.state.value.currentIndex)
    }

    @Test
    fun `skipRest cancels the timer and resumes a paused session`() {
        every { boardSessionManager.state } returns MutableStateFlow(
            BoardSessionState(isActive = true, isPaused = true)
        )
        coordinator.skipRest()
        verify(exactly = 1) { boardSessionManager.cancelRestTimer() }
        verify(exactly = 1) { boardSessionManager.resumeSession() }
    }

    @Test
    fun `initial state reflects an already-running playlist`() {
        // The player reads state.value on its first frame — a blank seed
        // (isActive=false) bounced it straight back out after Play.
        queueManager.loadPlaylist("Playlist", listOf(QueueItem("a", 40)))
        val fresh = PlaylistPlaybackCoordinator(
            queueManager, boardSessionManager, gattBridge, bleShareManager, scope,
        )
        assertTrue(fresh.state.value.isActive)
        assertEquals(1, fresh.state.value.queue.size)
    }

    // ── Phase-aware transport (the "next during rest" bug) ──────

    @Test
    fun `next during a rest skips the pause instead of advancing`() {
        queueManager.loadPlaylist(
            "Playlist",
            listOf(QueueItem("a", 40, restAfterSeconds = 180), QueueItem("a", 40), QueueItem("b", 40)),
        )
        queueManager.nextClimb() // leave attempt 1 → index 1, rest armed
        restState.value = RestTimerState(isRunning = true, secondsRemaining = 170, totalSeconds = 180)
        awaitState { it.isResting }

        coordinator.next()

        // Queue must NOT move — the pause was skipped, the climb stays.
        assertEquals(1, queueManager.state.value.currentIndex)
        verify(exactly = 1) { boardSessionManager.cancelRestTimer() }
    }

    @Test
    fun `previous during a rest undoes the advance and resumes the session clock`() {
        queueManager.loadPlaylist(
            "Playlist",
            listOf(QueueItem("a", 40, restAfterSeconds = 180), QueueItem("b", 40)),
        )
        queueManager.nextClimb() // → index 1, rest armed
        // startRestTimer pauses the session — mirror that in the mocked state.
        sessionState.value = BoardSessionState(isActive = true, isPaused = true)
        restState.value = RestTimerState(isRunning = true, secondsRemaining = 170, totalSeconds = 180)
        awaitState { it.isResting }

        coordinator.previous()

        assertEquals(0, queueManager.state.value.currentIndex)
        verify(exactly = 1) { boardSessionManager.cancelRestTimer() }
        // Same semantics as skipRest: the session clock must NOT stay paused.
        verify(exactly = 1) { boardSessionManager.resumeSession() }
    }

    @Test
    fun `hasNext stays true during a rest even on the last climb`() {
        queueManager.loadPlaylist(
            "Playlist",
            listOf(QueueItem("a", 40, restAfterSeconds = 60), QueueItem("b", 40)),
        )
        queueManager.nextClimb() // → last index, rest armed
        restState.value = RestTimerState(isRunning = true, secondsRemaining = 55, totalSeconds = 60)

        assertTrue(awaitState { it.isResting }.hasNext)
    }

    // ── Attempt indicator ───────────────────────────────────────

    @Test
    fun `attemptInfo reflects position within a same-climb run`() {
        queueManager.loadPlaylist(
            "Playlist",
            listOf(
                QueueItem("a", 40), QueueItem("a", 40), QueueItem("a", 40),
                QueueItem("b", 40),
            ),
        )
        assertEquals(1 to 3, awaitState { it.currentIndex == 0 }.attemptInfo)
        queueManager.nextClimb()
        assertEquals(2 to 3, awaitState { it.currentIndex == 1 }.attemptInfo)
        queueManager.nextClimb()
        assertEquals(3 to 3, awaitState { it.currentIndex == 2 }.attemptInfo)
        queueManager.nextClimb()
        assertNull("single climb has no attempt chip", awaitState { it.currentIndex == 3 }.attemptInfo)
    }
}
