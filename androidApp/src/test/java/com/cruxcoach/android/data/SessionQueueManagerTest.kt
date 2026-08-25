package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardLedMode
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.flow.flowOf
import com.cruxcoach.android.data.BoardSendMode
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
    // These two decide whether an advance sends at all. A relaxed mock gives
    // back nothing usable, and the resolution then falls back — which would
    // make these tests pass without exercising the path they are about.
    private val userPreferences = mockk<UserPreferences>(relaxed = true).also {
        every { it.singleConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
        every { it.multiConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
        every { it.moonBoardLedMode } returns flowOf(MoonBoardLedMode.BELOW)
    }

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
        queueManager.onSessionInfoChanged = { }
        queueManager.remoteAddClimb = { _, _ -> }

        queueManager.endQueue()

        assertNull("onQueueChanged must be null after endQueue", queueManager.onQueueChanged)
        assertNull("onCurrentClimbChanged must be null after endQueue", queueManager.onCurrentClimbChanged)
        assertNull("onParticipantsChanged must be null after endQueue", queueManager.onParticipantsChanged)
        assertNull("onSessionInfoChanged must be null after endQueue", queueManager.onSessionInfoChanged)
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

    @Test
    fun `visibility is per session and resets when the queue ends`() {
        queueManager.startQueue("Local host")
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)

        queueManager.endQueue()
        queueManager.startQueue("Published host", SessionVisibility.JOINABLE)
        assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibility)

        queueManager.endQueue()
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)

        queueManager.setParticipantRole(0, "Remote host")
        assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibility)
    }

    @Test
    fun `promoted host stays local until one explicit visibility decision`() {
        queueManager.setParticipantRole(77, "Old host")
        queueManager.applyRemoteState(
            currentIndex = 0,
            items = listOf(QueueItem("kept", 40)),
        )

        queueManager.promoteToHost("New host")

        val promoted = queueManager.state.value
        assertEquals(SessionRole.HOST, promoted.role)
        assertEquals(listOf("kept"), promoted.queue.map { it.climbUuid })
        assertEquals(SessionVisibility.LOCAL_ONLY, promoted.visibility)
        assertEquals(SessionVisibility.LOCAL_ONLY, promoted.visibilityRequested)
        assertTrue(promoted.pendingHostVisibilityDecision)

        queueManager.setVisibilityRequested(SessionVisibility.JOINABLE)
        assertFalse(queueManager.state.value.pendingHostVisibilityDecision)
        assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibilityRequested)
        assertEquals(
            "request alone must not claim that publication is already active",
            SessionVisibility.LOCAL_ONLY,
            queueManager.state.value.visibility,
        )
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
    fun `participant never resolves or writes current climb to physical board`() {
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        queueManager.setParticipantRole(0, "Host")
        queueManager.applyRemoteState(
            currentIndex = 0,
            items = listOf(QueueItem("remote-climb", 40)),
        )

        queueManager.sendCurrentClimbToBoard()

        verify(exactly = 0) { boardRepository.getClimbByUuid(any(), any()) }
        coVerify(exactly = 0) { bleConnection.sendClimb(any(), any(), any()) }
        coVerify(exactly = 0) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
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
     * The participant's session id is the HOST's, read from the advertisement
     * at join time. It used to be a hardcoded 0, which meant a participant had
     * no way to recognise their own session.
     *
     * Migration does not read this field — it keeps its own copy captured at
     * join — so the two remain independent.
     */
    @Test
    fun `setParticipantRole carries the host session id into the state`() {
        queueManager.setParticipantRole(4711, "Host")
        assertEquals(
            "the participant must be able to identify their own session",
            4711,
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
        val uuid = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { bleConnection.connectedBoardBrand } returns MutableStateFlow(connectedBrand)
        every { boardRepository.getClimbByUuid(any(), any()) } returns moonBoardClimb(uuid)
        coEvery { bleConnection.sendMoonBoardClimb(any(), any(), any()) } returns true
        queueManager.startQueue("Host")
        queueManager.addClimb(uuid, 40)
    }

    @Test
    fun `sendCurrentClimbToBoard skips when climb brand differs from connected board`() {
        // MoonBoard climb in the queue, but a Kilter board is still on the link
        // (e.g. the active board was switched in Settings without disconnecting).
        setupConnectedSendScenario(connectedBrand = BoardBrand.KILTER)

        queueManager.sendCurrentClimbToBoard()

        coVerify(exactly = 0) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
        coVerify(exactly = 0) { bleConnection.sendClimb(any(), any(), any()) }
    }

    @Test
    fun `sendCurrentClimbToBoard sends when climb brand matches connected board`() {
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)

        queueManager.sendCurrentClimbToBoard()

        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
    }

    @Test
    fun `sendCurrentClimbToBoard sends when connected board brand is unknown`() {
        // Legacy behavior preserved: with no connected-brand information the
        // guard must not block (matches pre-guard semantics).
        setupConnectedSendScenario(connectedBrand = null)

        queueManager.sendCurrentClimbToBoard()

        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
    }

    @Test
    fun `remote resend respects explicit mode while local lamp remains authorized`() {
        every { userPreferences.singleConnectionBoardSendMode } returns
            flowOf(BoardSendMode.EXPLICIT)
        every { userPreferences.multiConnectionBoardSendMode } returns
            flowOf(BoardSendMode.EXPLICIT)
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)

        // Adding the first climb and a peer resend both lack the host user's
        // explicit wall-write authority.
        queueManager.requestRemoteResend()

        coVerify(exactly = 0) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
        assertTrue(queueManager.state.value.awaitingExplicitSend)

        // The host's own lamp action is explicit and force-bypasses dedup.
        queueManager.resendCurrentClimb()

        coVerify(exactly = 1) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
        assertFalse(queueManager.state.value.awaitingExplicitSend)
    }

    @Test
    fun `remote resend force-writes the same climb only in automatic mode`() {
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)

        // The first item was projected automatically. A peer resend is still
        // a real resend in AUTOMATIC mode, despite the matching dedup key.
        queueManager.requestRemoteResend()

        coVerify(exactly = 2) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
        assertFalse(queueManager.state.value.awaitingExplicitSend)
    }

    @Test
    fun `single-connect host keeps automatic mode when participants join`() {
        every { userPreferences.singleConnectionBoardSendMode } returns
            flowOf(BoardSendMode.AUTOMATIC)
        every { userPreferences.multiConnectionBoardSendMode } returns
            flowOf(BoardSendMode.EXPLICIT)
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)
        queueManager.addParticipant("AA:BB:CC:DD:EE:01", "Participant")
        queueManager.addClimb("second", 40)

        queueManager.nextClimb()

        coVerify(exactly = 2) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
        assertFalse(queueManager.state.value.awaitingExplicitSend)
    }

    @Test
    fun `participant applies host explicit-send state`() {
        queueManager.setParticipantRole(0, "Host")

        queueManager.updateSessionInfo(
            hostName = "Host",
            participantCount = 2,
            awaitingExplicitSend = true,
        )

        assertTrue(queueManager.state.value.awaitingExplicitSend)
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

    // ===== Playlist playback (loadPlaylist + rest arming) =====

    @Test
    fun `loadPlaylist starts a host queue and marks it as playlist`() {
        queueManager.loadPlaylist(
            "Host",
            listOf(QueueItem("a", 40), QueueItem("b", 40)),
        )

        val s = queueManager.state.value
        assertEquals(SessionRole.HOST, s.role)
        assertEquals(2, s.queue.size)
        assertEquals(0, s.currentIndex)
        assertTrue("playlist flag must be set", queueManager.isPlaylistQueue)
    }

    @Test
    fun `loadPlaylist always starts local only even when joinable is requested`() {
        queueManager.loadPlaylist(
            "Host",
            listOf(QueueItem("a", 40)),
            SessionVisibility.JOINABLE,
        )

        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibilityRequested)
    }

    @Test
    fun `saved playlist rejects later attempts to become joinable`() {
        queueManager.loadPlaylist(
            "Private",
            listOf(QueueItem("a", 40)),
        )

        queueManager.setVisibility(SessionVisibility.JOINABLE)
        queueManager.setVisibilityRequested(SessionVisibility.JOINABLE)

        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibilityRequested)
        assertTrue(queueManager.state.value.isPlaylist)
    }

    @Test
    fun `loadPlaylist cannot repurpose an active joinable session`() {
        queueManager.startQueue("Shared host", SessionVisibility.JOINABLE)
        queueManager.addClimb("shared", 40)

        queueManager.loadPlaylist("Private", listOf(QueueItem("private", 30)))

        assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibility)
        assertEquals(listOf("shared"), queueManager.state.value.queue.map { it.climbUuid })
        assertFalse(queueManager.state.value.isPlaylist)
        assertFalse(queueManager.isPlaylistQueue)
    }

    @Test
    fun `loadPlaylist replaces an existing ad-hoc queue`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("old", 40)

        queueManager.loadPlaylist("Host", listOf(QueueItem("new", 45)))

        val s = queueManager.state.value
        assertEquals(listOf("new"), s.queue.map { it.climbUuid })
        assertEquals(0, s.currentIndex)
    }

    @Test
    fun `loadPlaylist with empty items is a no-op`() {
        queueManager.loadPlaylist("Host", emptyList())
        assertFalse(queueManager.state.value.isActive)
        assertFalse(queueManager.isPlaylistQueue)
    }

    @Test
    fun `nextClimb arms the rest timer for the climb it leaves`() {
        val rests = mutableListOf<Int>()
        queueManager.onRestRequested = { rests.add(it) }
        queueManager.loadPlaylist(
            "Host",
            listOf(
                QueueItem("a", 40, restAfterSeconds = 270),
                QueueItem("b", 40),
                QueueItem("c", 40, restAfterSeconds = 60),
            ),
        )
        // Hook is set AFTER loadPlaylist in production (play() sets it before);
        // re-set here because loadPlaylist doesn't clear it.
        queueManager.onRestRequested = { rests.add(it) }

        queueManager.nextClimb() // leave a (rest 270)
        queueManager.nextClimb() // leave b (no rest)
        queueManager.nextClimb() // at end — no-op

        assertEquals(listOf(270), rests)
        assertEquals(2, queueManager.state.value.currentIndex)
    }

    @Test
    fun `setCurrentClimb jump does not arm the rest timer`() {
        val rests = mutableListOf<Int>()
        queueManager.loadPlaylist(
            "Host",
            listOf(
                QueueItem("a", 40, restAfterSeconds = 300),
                QueueItem("b", 40),
                QueueItem("c", 40),
            ),
        )
        queueManager.onRestRequested = { rests.add(it) }

        queueManager.setCurrentClimb(2) // manual jump skips pacing

        assertTrue("jumping must not start a rest", rests.isEmpty())
    }

    @Test
    fun `rest metadata follows the item through reorder`() {
        queueManager.loadPlaylist(
            "Host",
            listOf(
                QueueItem("a", 40, restAfterSeconds = 100),
                QueueItem("b", 40),
            ),
        )

        queueManager.moveClimb(0, 1)

        val s = queueManager.state.value
        assertEquals(listOf("b", "a"), s.queue.map { it.climbUuid })
        assertEquals(100, s.queue[1].restAfterSeconds)
    }

    @Test
    fun `endQueue clears playlist flag and rest hook`() {
        queueManager.loadPlaylist("Host", listOf(QueueItem("a", 40)))
        queueManager.onRestRequested = { }

        queueManager.endQueue()

        assertFalse(queueManager.isPlaylistQueue)
        assertNull(queueManager.onRestRequested)
    }

    @Test
    fun `addClimb during playlist keeps playlist flag`() {
        // Participants/host may append extra climbs mid-session; the queue
        // stays a "playlist queue" (nearby auto-import remains suppressed).
        queueManager.loadPlaylist("Host", listOf(QueueItem("a", 40)))
        queueManager.addClimb("extra", 40)
        assertTrue(queueManager.isPlaylistQueue)
        assertEquals(2, queueManager.state.value.queue.size)
    }

    // ===== External board-app override =====

    @Test
    fun `empty queue is not mistaken for an external board override`() {
        queueManager.startQueue("Host")

        val encoded = queueManager.encodeCurrentClimb()

        assertEquals(0xFF, encoded[0].toInt() and 0xFF)
        assertFalse(SessionQueueManager.isExternalBoardOverride(encoded))
    }

    @Test
    fun `host external write is broadcast as a dedicated current-climb sentinel`() {
        queueManager.startQueue("Host")
        queueManager.addClimb("uuid0", 40)
        var currentChanged = 0
        queueManager.onCurrentClimbChanged = { currentChanged++ }

        queueManager.markExternalBoardWrite()

        assertTrue(queueManager.state.value.externalBoardOverride)
        val encoded = queueManager.encodeCurrentClimb()
        assertEquals(0xFF, encoded[0].toInt() and 0xFF)
        assertTrue(SessionQueueManager.isExternalBoardOverride(encoded))
        assertEquals(1, currentChanged)
    }

    @Test
    fun `participant applies external override without writing the physical board`() {
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.CONNECTED)
        queueManager.setParticipantRole(0, "Host")
        queueManager.applyRemoteState(0, listOf(QueueItem("remote-climb", 40)))

        queueManager.applyRemoteExternalBoardWrite()
        queueManager.sendCurrentClimbToBoard()

        assertTrue(queueManager.state.value.externalBoardOverride)
        coVerify(exactly = 0) { bleConnection.sendClimb(any(), any(), any()) }
        coVerify(exactly = 0) { bleConnection.sendMoonBoardClimb(any(), any(), any()) }
    }

    @Test
    fun `successful host resend restores queue projection and clears external override`() {
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)
        queueManager.markExternalBoardWrite()
        coEvery { bleConnection.sendMoonBoardClimb(any(), any(), any()) } returns true

        queueManager.sendCurrentClimbToBoard()

        assertFalse(queueManager.state.value.externalBoardOverride)
        assertEquals(0, queueManager.encodeCurrentClimb()[0].toInt() and 0xFF)
    }

    @Test
    fun `failed host resend keeps external override honest`() {
        setupConnectedSendScenario(connectedBrand = BoardBrand.MOONBOARD)
        queueManager.markExternalBoardWrite()
        coEvery { bleConnection.sendMoonBoardClimb(any(), any(), any()) } returns false

        queueManager.sendCurrentClimbToBoard()

        assertTrue(queueManager.state.value.externalBoardOverride)
    }

    @Test
    fun `valid remote queue index clears external override`() {
        queueManager.setParticipantRole(0, "Host")
        queueManager.applyRemoteState(0, listOf(QueueItem("a", 40), QueueItem("b", 40)))
        queueManager.applyRemoteExternalBoardWrite()

        queueManager.applyRemoteCurrentIndex(1)

        assertFalse(queueManager.state.value.externalBoardOverride)
        assertEquals(1, queueManager.state.value.currentIndex)
    }
}
