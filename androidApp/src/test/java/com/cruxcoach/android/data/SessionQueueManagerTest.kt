package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [SessionQueueManager] state machine invariants.
 *
 * These tests protect against regressions in:
 * - Callback lifecycle (endQueue must clear all callbacks)
 * - Participant count consistency (single source of truth)
 * - Re-join deduplication (same device address)
 * - Thread-safety annotations (@Volatile callbacks)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionQueueManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var managerScope: CoroutineScope
    private lateinit var queueManager: SessionQueueManager
    private val bleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val climbNameResolver = mockk<ClimbNameResolver>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        managerScope = CoroutineScope(SupervisorJob() + testDispatcher)
        queueManager = SessionQueueManager(
            bleConnection, boardRepository, climbNameResolver, userPreferences, managerScope
        )
    }

    @After
    fun tearDown() {
        // Cancel the manager's scope BEFORE resetMain so any in-flight `withContext`
        // hops (notably state.collect → withContext(Dispatchers.IO) at line 106)
        // don't resume onto a torn-down Main and leak as UncaughtExceptionsBeforeTest
        // into the next test in the same JVM.
        managerScope.cancel()
        Dispatchers.resetMain()
    }

    // ===== endQueue clears all callbacks =====

    @Test
    fun `endQueue clears all callback references`() {
        queueManager.startQueue("Host")
        queueManager.onQueueChanged = { }
        queueManager.onCurrentClimbChanged = { }
        queueManager.onParticipantsChanged = { }
        queueManager.remoteAddClimb = { _, _ -> }

        queueManager.endQueue()

        assertNull("onQueueChanged must be null after endQueue", queueManager.onQueueChanged)
        assertNull("onCurrentClimbChanged must be null after endQueue", queueManager.onCurrentClimbChanged)
        assertNull("onParticipantsChanged must be null after endQueue", queueManager.onParticipantsChanged)
        assertNull("remoteAddClimb must be null after endQueue", queueManager.remoteAddClimb)
    }

    @Test
    fun `endQueue resets state to NONE`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid1", 40)

        queueManager.endQueue()

        val state = queueManager.state.value
        assertEquals(SessionRole.NONE, state.role)
        assertTrue(state.queue.isEmpty())
        assertEquals(0, state.participantCount)
        assertEquals(-1, state.currentIndex)
        assertFalse(state.isActive)
    }

    // ===== Participant count consistency =====

    @Test
    fun `addParticipant increases count by 1 including host`() {
        queueManager.startQueue("Host")
        assertEquals(1, queueManager.state.value.participantCount) // host only

        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "Teilnehmer 1")
        assertEquals(2, queueManager.state.value.participantCount) // host + 1

        queueManager.addParticipant("AA:BB:CC:DD:EE:02", "Teilnehmer 2")
        assertEquals(3, queueManager.state.value.participantCount) // host + 2
    }

    @Test
    fun `removeParticipant decreases count`() {
        queueManager.startQueue("Host")
        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "Teilnehmer 1")
        queueManager.addParticipant("AA:BB:CC:DD:EE:02", "Teilnehmer 2")
        assertEquals(3, queueManager.state.value.participantCount)

        queueManager.removeParticipant("AA:BB:CC:DD:EE:01")
        assertEquals(2, queueManager.state.value.participantCount) // host + 1

        queueManager.removeParticipant("AA:BB:CC:DD:EE:02")
        assertEquals(1, queueManager.state.value.participantCount) // host only
    }

    @Test
    fun `removeParticipant with unknown address does not change count`() {
        queueManager.startQueue("Host")
        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "Teilnehmer 1")
        assertEquals(2, queueManager.state.value.participantCount)

        queueManager.removeParticipant("FF:FF:FF:FF:FF:FF") // unknown
        assertEquals(2, queueManager.state.value.participantCount) // unchanged
    }

    // ===== Re-join deduplication =====

    @Test
    fun `addParticipant with same address is re-join, not duplicate`() {
        queueManager.startQueue("Host")
        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "Teilnehmer 1")
        assertEquals(2, queueManager.state.value.participantCount)
        assertEquals(1, queueManager.state.value.participants.size)

        // Same device re-joins — count must NOT increase
        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "Teilnehmer 2")
        assertEquals(2, queueManager.state.value.participantCount)
        assertEquals(1, queueManager.state.value.participants.size)
        // Name updated
        assertEquals("Teilnehmer 2", queueManager.state.value.participants[0].displayName)
    }

    @Test
    fun `re-join preserves participant index`() {
        queueManager.startQueue("Host")
        val index1 = queueManager.addParticipant("AA:BB:CC:DD:EE:01", "First")
        val index2 = queueManager.addParticipant("AA:BB:CC:DD:EE:01", "First-Rejoined")
        assertEquals(index1, index2)
    }

    // ===== applyRemoteParticipants must NOT update participantCount =====

    @Test
    fun `applyRemoteParticipants does not change participantCount`() {
        queueManager.startQueue("Host")
        queueManager.setParticipantRole(0, "HostName")
        queueManager.updateSessionInfo("HostName", 5) // authoritative count = 5

        queueManager.applyRemoteParticipants(listOf("A", "B", "C"))

        // Count must stay at 5 (from updateSessionInfo), not 4 (3 names + 1 host)
        assertEquals(5, queueManager.state.value.participantCount)
    }

    @Test
    fun `updateSessionInfo is sole authority for participantCount on participant side`() {
        queueManager.setParticipantRole(0, "Host")

        queueManager.updateSessionInfo("Host", 3)
        assertEquals(3, queueManager.state.value.participantCount)

        // applyRemoteParticipants must NOT override
        queueManager.applyRemoteParticipants(listOf("X"))
        assertEquals(3, queueManager.state.value.participantCount)

        // Only updateSessionInfo changes it
        queueManager.updateSessionInfo("Host", 7)
        assertEquals(7, queueManager.state.value.participantCount)
    }

    // ===== Session-ended sentinel =====

    @Test
    fun `participantCount 0 is valid sentinel for session ended`() {
        // Protocol: host sends participantCount=0 to signal session end
        val encoded = com.cruxcoach.android.ble.SessionQueueProtocol.encodeSessionInfo("", 0)
        val decoded = com.cruxcoach.android.ble.SessionQueueProtocol.decodeSessionInfo(encoded)!!
        assertEquals(0, decoded.participantCount)
        assertEquals("", decoded.hostName)
    }

    // ===== Queue operations =====

    @Test
    fun `addClimb as participant routes via remoteAddClimb`() {
        queueManager.setParticipantRole(0, "Host")
        var remoteCalled = false
        queueManager.remoteAddClimb = { _, _ -> remoteCalled = true }

        queueManager.addClimb("uuid1", 40)

        assertTrue("PARTICIPANT addClimb must route via remoteAddClimb", remoteCalled)
        assertTrue("Queue must stay empty on participant side", queueManager.state.value.queue.isEmpty())
    }

    @Test
    fun `addClimb as host adds to local queue`() {
        queueManager.startQueue("Host")

        queueManager.addClimb("uuid1", 40)

        assertEquals(1, queueManager.state.value.queue.size)
        assertEquals(0, queueManager.state.value.currentIndex)
    }

    @Test
    fun `moveClimb adjusts currentIndex to follow current climb`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("A", 40)
        queueManager.addClimb("B", 40)
        queueManager.addClimb("C", 40)
        queueManager.setCurrentClimb(0) // current = A

        queueManager.moveClimb(0, 2) // move A from 0 to 2

        assertEquals(2, queueManager.state.value.currentIndex) // follows A
        assertEquals("A", queueManager.state.value.queue[2].climbUuid)
    }

    // ===== Callback notifications =====

    @Test
    fun `onParticipantsChanged fires on addParticipant`() {
        queueManager.startQueue("Host")
        var fired = false
        queueManager.onParticipantsChanged = { fired = true }

        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "T1")

        assertTrue("onParticipantsChanged must fire on addParticipant", fired)
    }

    @Test
    fun `onParticipantsChanged fires on removeParticipant`() {
        queueManager.startQueue("Host")
        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "T1")
        var fired = false
        queueManager.onParticipantsChanged = { fired = true }

        queueManager.removeParticipant("AA:BB:CC:DD:EE:01")

        assertTrue("onParticipantsChanged must fire on removeParticipant", fired)
    }

    // ===== Migration invariants =====

    /**
     * Regression guard: the participant's local sessionId is always 0 because
     * SessionGattBridge.joinSession() calls setParticipantRole(0, "").
     *
     * If someone were to "fix" this by passing the real ID, migration code in
     * SessionGattBridge would also need updating — the test documents the contract.
     */
    @Test
    fun `setParticipantRole with id 0 yields sessionId 0 — migration must not use it as stale filter`() {
        queueManager.setParticipantRole(0, "Host")
        assertEquals(
            "Participant sessionId is 0 by design; SessionGattBridge reads the real host session " +
                "ID from NearbyClimbScanner.nearbySessions instead",
            0,
            queueManager.state.value.sessionId
        )
    }

    @Test
    fun `promoteToHost generates a fresh non-zero sessionId`() {
        queueManager.setParticipantRole(0, "OldHost")
        queueManager.promoteToHost("NewHost")
        assertNotEquals(
            "promoteToHost must generate a new non-zero sessionId",
            0,
            queueManager.state.value.sessionId
        )
    }

    /**
     * Regression: during host migration, GATT is disconnected but role is still PARTICIPANT.
     * If remoteAddClimb is null (cleared by handleSessionEndedByHost), addClimb() must
     * fall through to local add instead of silently dropping the climb.
     */
    @Test
    fun `addClimb as participant with null remoteAddClimb falls through to local add`() {
        queueManager.setParticipantRole(0, "Host")
        // remoteAddClimb is null (GATT disconnected during migration)
        queueManager.remoteAddClimb = null

        queueManager.addClimb("uuid-during-migration", 40)

        assertEquals(
            "Climb must be added locally when remoteAddClimb is null (migration window)",
            1,
            queueManager.state.value.queue.size
        )
        assertEquals("uuid-during-migration", queueManager.state.value.queue[0].climbUuid)
    }

    @Test
    fun `addClimb as participant with remoteAddClimb set routes via GATT, not local`() {
        queueManager.setParticipantRole(0, "Host")
        var remoteCalled = false
        queueManager.remoteAddClimb = { _, _ -> remoteCalled = true }

        queueManager.addClimb("uuid1", 40)

        assertTrue("Must route via remoteAddClimb when set", remoteCalled)
        assertTrue("Queue must stay empty (routed remotely)", queueManager.state.value.queue.isEmpty())
    }

    @Test
    fun `promoteToHost resets participants and sets count to 1`() {
        queueManager.setParticipantRole(0, "OldHost")
        queueManager.addClimb("uuid1", 40) // won't add (participant), but let's set state manually
        queueManager.promoteToHost("NewHost")

        val state = queueManager.state.value
        assertEquals(SessionRole.HOST, state.role)
        assertEquals(1, state.participantCount) // just the new host
        assertTrue(state.participants.isEmpty())
        assertEquals("NewHost", state.hostName)
    }

    // ===== Queue-content operations (remove/next/previous/clear/applyRemote) =====

    @Test
    fun `removeClimb at index before currentIndex shifts currentIndex down`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)
        queueManager.addClimb("uuid2", 40)
        queueManager.setCurrentClimb(2)
        assertEquals(2, queueManager.state.value.currentIndex)

        queueManager.removeClimb(0) // remove before the current

        val state = queueManager.state.value
        assertEquals(2, state.queue.size)
        assertEquals(1, state.currentIndex) // shifted down to still point at uuid2
        assertEquals("uuid2", state.queue[state.currentIndex].climbUuid)
    }

    @Test
    fun `removeClimb at currentIndex keeps index stable when there is a successor`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)
        queueManager.addClimb("uuid2", 40)
        queueManager.setCurrentClimb(1)

        queueManager.removeClimb(1) // drop the current; index should stay at 1 pointing to uuid2

        val state = queueManager.state.value
        assertEquals(2, state.queue.size)
        assertEquals(1, state.currentIndex)
        assertEquals("uuid2", state.queue[state.currentIndex].climbUuid)
    }

    @Test
    fun `removeClimb at currentIndex at end coerces to last valid index`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)
        queueManager.setCurrentClimb(1) // pointing at uuid1, the last one

        queueManager.removeClimb(1) // last item gone

        val state = queueManager.state.value
        assertEquals(1, state.queue.size)
        assertEquals(0, state.currentIndex) // coerced to last valid index
    }

    @Test
    fun `removeClimb at index after currentIndex leaves currentIndex alone`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)
        queueManager.addClimb("uuid2", 40)
        queueManager.setCurrentClimb(0)

        queueManager.removeClimb(2)

        assertEquals(0, queueManager.state.value.currentIndex)
        assertEquals(2, queueManager.state.value.queue.size)
    }

    @Test
    fun `removeClimb emptying queue sets currentIndex to -1`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        assertEquals(0, queueManager.state.value.currentIndex)

        queueManager.removeClimb(0)

        val state = queueManager.state.value
        assertTrue(state.queue.isEmpty())
        assertEquals(-1, state.currentIndex)
    }

    @Test
    fun `removeClimb out-of-bounds index is a no-op`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        val before = queueManager.state.value

        queueManager.removeClimb(-1)
        queueManager.removeClimb(5)

        assertEquals(before.queue, queueManager.state.value.queue)
        assertEquals(before.currentIndex, queueManager.state.value.currentIndex)
    }

    @Test
    fun `nextClimb advances currentIndex when room`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)
        assertEquals(0, queueManager.state.value.currentIndex)

        queueManager.nextClimb()
        assertEquals(1, queueManager.state.value.currentIndex)
    }

    @Test
    fun `nextClimb at end is a no-op`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)
        queueManager.setCurrentClimb(1)

        queueManager.nextClimb()
        assertEquals(1, queueManager.state.value.currentIndex)
    }

    @Test
    fun `previousClimb decrements when possible`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)
        queueManager.setCurrentClimb(1)

        queueManager.previousClimb()
        assertEquals(0, queueManager.state.value.currentIndex)
    }

    @Test
    fun `previousClimb at start is a no-op`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)

        queueManager.previousClimb()
        assertEquals(0, queueManager.state.value.currentIndex)
    }

    @Test
    fun `setCurrentClimb out-of-bounds leaves currentIndex unchanged`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)

        queueManager.setCurrentClimb(-1)
        assertEquals(0, queueManager.state.value.currentIndex)
        queueManager.setCurrentClimb(99)
        assertEquals(0, queueManager.state.value.currentIndex)
    }

    @Test
    fun `clearQueue empties list and resets currentIndex`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        queueManager.addClimb("uuid1", 40)

        queueManager.clearQueue()

        val state = queueManager.state.value
        assertTrue(state.queue.isEmpty())
        assertEquals(-1, state.currentIndex)
    }

    @Test
    fun `clearQueue fires onCurrentClimbChanged only when there was a current climb`() {
        queueManager.startQueue("Host")
        var ccFires = 0
        queueManager.onCurrentClimbChanged = { ccFires++ }

        // Empty queue → no current climb → no fire
        queueManager.clearQueue()
        assertEquals(0, ccFires)

        queueManager.addClimb("uuid0", 40)
        ccFires = 0 // reset after addClimb's own fire

        queueManager.clearQueue()
        assertEquals(1, ccFires)
    }

    @Test
    fun `applyRemoteState replaces queue and currentIndex atomically`() {
        queueManager.setParticipantRole(0, "Host")
        queueManager.applyRemoteState(
            currentIndex = 2,
            items = listOf(
                QueueItem("a", 40),
                QueueItem("b", 40),
                QueueItem("c", 40),
            ),
        )

        val state = queueManager.state.value
        assertEquals(3, state.queue.size)
        assertEquals(2, state.currentIndex)
        assertEquals("c", state.queue[state.currentIndex].climbUuid)
    }

    // ===== Connected-board brand guard on sendCurrentClimbToBoard =====

    private fun moonBoardClimb(uuid: String) = ClimbWithStats(
        uuid = uuid,
        layoutId = 1L,
        setterUsername = null,
        name = "MB climb",
        frames = "p1r12p2r13",
        framesCount = 1,
        difficultyAverage = null,
        qualityAverage = null,
        ascensionistCount = null,
        boardBrand = "moonboard",
    )

    private fun setupConnectedSendScenario(connectedBrand: BoardBrand?) {
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { bleConnection.connectedBoardBrand } returns MutableStateFlow(connectedBrand)
        every { boardRepository.getClimbByUuid(any(), any()) } returns moonBoardClimb("uuid-mb")
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid-mb", 40)
    }

    @Test
    fun `sendCurrentClimbToBoard skips when climb brand differs from connected board`() {
        // MoonBoard climb in the queue, but a Kilter board is still on the link
        // (e.g. the active board was switched in Settings without disconnecting).
        setupConnectedSendScenario(connectedBrand = BoardBrand.KILTER)

        queueManager.sendCurrentClimbToBoard()

        coVerify(exactly = 0) { bleConnection.sendMoonBoardClimb(any(), any()) }
        coVerify(exactly = 0) { bleConnection.sendClimb(any(), any(), any()) }
    }

    @Test
    fun `sendCurrentClimbToBoard sends when climb brand matches connected board`() {
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)

        queueManager.sendCurrentClimbToBoard()

        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any()) }
    }

    @Test
    fun `sendCurrentClimbToBoard sends when connected board brand is unknown`() {
        // Legacy behavior preserved: with no connected-brand information the
        // guard must not block (matches pre-guard semantics).
        setupConnectedSendScenario(connectedBrand = null)

        queueManager.sendCurrentClimbToBoard()

        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any()) }
    }

    @Test
    fun `concurrent send requests produce one physical board write`() {
        val firstSendEntered = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        coEvery { bleConnection.sendMoonBoardClimb(any(), any()) } coAnswers {
            firstSendEntered.complete(Unit)
            releaseFirstSend.await()
            true
        }
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)
        assertTrue("the automatic first-climb send must be in flight", firstSendEntered.isCompleted)

        // This second request reaches the manager while the first BLE write is
        // suspended. It must wait for the same critical section, then observe
        // the completed send's dedup key instead of writing another frame.
        queueManager.sendCurrentClimbToBoard()
        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any()) }

        releaseFirstSend.complete(Unit)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any()) }
    }

    @Test
    fun `applyRemoteCurrentIndex ignores out-of-range values`() {
        queueManager.setParticipantRole(0, "Host")
        queueManager.applyRemoteState(
            currentIndex = 0,
            items = listOf(QueueItem("a", 40), QueueItem("b", 40)),
        )

        queueManager.applyRemoteCurrentIndex(99) // invalid
        assertEquals(0, queueManager.state.value.currentIndex)

        queueManager.applyRemoteCurrentIndex(1) // valid
        assertEquals(1, queueManager.state.value.currentIndex)

        queueManager.applyRemoteCurrentIndex(-1) // invalid
        assertEquals(1, queueManager.state.value.currentIndex)
    }
}
