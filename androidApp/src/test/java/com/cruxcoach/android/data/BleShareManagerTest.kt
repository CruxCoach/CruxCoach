package com.cruxcoach.android.data

import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.NearbyClimb
import com.cruxcoach.android.ble.NearbyClimbScanner
import com.cruxcoach.android.ble.NearbySession
import com.cruxcoach.android.ble.QueueItem
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for [BleShareManager] — the central BLE sharing state coordinator.
 *
 * Covers the 6-level on-board climb priority resolution, suppression logic,
 * bridge behavior, popup generation, and the bugs fixed in FEAT-035:
 * - SESSION_REMOTE must beat LOCAL_MANAGER for participants
 * - SESSION_REMOTE must not be suppressed by redundancy check
 * - No null gap during isConnecting (chip flicker)
 * - distinctUntilChanged ignores RSSI fluctuations
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleShareManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Controllable StateFlows for dependencies
    private val lastClimbFlow = MutableStateFlow<BoardStateManager.LastBoardClimb?>(null)
    private val nearbyClimbsFlow = MutableStateFlow<List<NearbyClimb>>(emptyList())
    private val climbNamesFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    private val climbInfosFlow = MutableStateFlow<Map<String, ClimbDisplayInfo>>(emptyMap())
    private val nearbySessionsFlow = MutableStateFlow<List<NearbySession>>(emptyList())
    private val sharingEnabledFlow = MutableStateFlow(false)
    private val queueStateFlow = MutableStateFlow(SessionQueueState())
    private val currentClimbNameFlow = MutableStateFlow<String?>(null)
    private val currentClimbInfoFlow = MutableStateFlow<ClimbDisplayInfo?>(null)
    private val boardSessionStateFlow = MutableStateFlow(BoardSessionState())
    private val disconnectResponsesFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    // Mocks
    private val boardStateManager = mockk<BoardStateManager>(relaxed = true) {
        every { lastClimb } returns lastClimbFlow
    }
    private val nearbyPresenceManager = mockk<NearbyPresenceManager>(relaxed = true) {
        every { climbs } returns nearbyClimbsFlow
        every { climbNames } returns climbNamesFlow
        every { climbInfos } returns climbInfosFlow
    }
    private val nearbyClimbScanner = mockk<NearbyClimbScanner>(relaxed = true) {
        every { nearbySessions } returns nearbySessionsFlow
        every { disconnectResponses } returns disconnectResponsesFlow
    }
    private val sharingConfig = mockk<SharingConfig>(relaxed = true) {
        every { sharingEnabled } returns sharingEnabledFlow
    }
    private val climbAdvertiser = mockk<ClimbBleAdvertiser>(relaxed = true) {
        every { hasActiveClimb() } returns false
    }
    private val sessionQueueManager = mockk<SessionQueueManager>(relaxed = true) {
        every { state } returns queueStateFlow
        every { currentClimbName } returns currentClimbNameFlow
        every { currentClimbInfo } returns currentClimbInfoFlow
    }
    private val boardSessionManager = mockk<BoardSessionManager>(relaxed = true) {
        every { state } returns boardSessionStateFlow
    }
    private val gradeScaleFlow = MutableStateFlow(GradeScale.FRENCH)
    private val userPreferences = mockk<UserPreferences>(relaxed = true) {
        every { gradeScale } returns gradeScaleFlow
    }
    private val boardCellSnapshots = MutableStateFlow<com.cruxcoach.android.boardcell.BoardCellSnapshot?>(null)
    private val boardCellManager = mockk<com.cruxcoach.android.boardcell.BoardCellManager>(relaxed = true) {
        every { snapshots } returns boardCellSnapshots
        every { snapshot() } answers { boardCellSnapshots.value }
    }

    private lateinit var manager: BleShareManager

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        manager = BleShareManager(
            boardStateManager, nearbyPresenceManager, nearbyClimbScanner,
            sharingConfig, climbAdvertiser, sessionQueueManager, boardSessionManager,
            userPreferences, boardCellManager,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ===== Helper factories =====

    private fun climb(uuid: String = UUID_A, angle: Int = 40) =
        BoardStateManager.LastBoardClimb(uuid, angle, "Test Climb")

    private fun nearbyClimb(
        uuid: String = UUID_A, angle: Int = 40, rssi: Int = -50,
        connectedOnly: Boolean = false, isLastClimb: Boolean = false,
        acceptsDisconnectRequests: Boolean = true,
        projectionSurvivesDisconnect: Boolean = true,
    ) = NearbyClimb(uuid, angle, rssi, System.currentTimeMillis(), "AA:BB:CC:DD:EE:FF",
        connectedOnly, isLastClimb,
        acceptsDisconnectRequests = acceptsDisconnectRequests,
        projectionSurvivesDisconnect = projectionSurvivesDisconnect)

    private fun session(
        sessionId: Int = 12345, hostName: String = "TestHost",
        currentClimbUuid: String? = UUID_A, currentClimbAngle: Int = 40
    ) = NearbySession(sessionId, 1, hostName, -50, System.currentTimeMillis(),
        "11:22:33:44:55:66", null, currentClimbUuid, currentClimbAngle)

    // ===== 1. Canonical shared-mesh projection is highest =====

    @Test
    fun `shared mesh projection immediately updates nearby board state`() = runTest {
        climbInfosFlow.value = mapOf(UUID_C to ClimbDisplayInfo("Mesh Climb", 5.0))
        boardCellSnapshots.value = com.cruxcoach.android.boardcell.BoardCellSnapshot(
            cellId = com.cruxcoach.android.boardcell.BoardCellId("cell"),
            physicalBoardId = com.cruxcoach.android.boardcell.PhysicalBoardId("board"),
            epoch = 1,
            sequence = 2,
            controllerId = "controller",
            lineageId = "lineage",
            members = setOf("controller", "participant"),
            projection = com.cruxcoach.android.boardcell.BoardProjection(UUID_C, 45),
        )
        advanceUntilIdle()

        val onBoard = manager.uiState.value.onBoardClimb
        assertEquals(OnBoardSource.MESH_ACTIVE, onBoard?.source)
        assertEquals(UUID_C, onBoard?.climbUuid)
        assertEquals("Mesh Climb", onBoard?.name)
        verify { nearbyPresenceManager.resolveMeshProjection(UUID_C, 45) }
    }

    @Test
    fun `controller keeps canonical mesh projection when last remote member is transiently absent`() = runTest {
        boardCellSnapshots.value = com.cruxcoach.android.boardcell.BoardCellSnapshot(
            cellId = com.cruxcoach.android.boardcell.BoardCellId("cell"),
            physicalBoardId = com.cruxcoach.android.boardcell.PhysicalBoardId("board"),
            epoch = 1,
            sequence = 3,
            controllerId = "controller",
            lineageId = "lineage",
            members = setOf("controller"),
            projection = com.cruxcoach.android.boardcell.BoardProjection(UUID_C, 40),
        )
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_A))
        advanceUntilIdle()

        assertEquals(OnBoardSource.MESH_ACTIVE, manager.uiState.value.onBoardClimb?.source)
        assertEquals(UUID_C, manager.uiState.value.onBoardClimb?.climbUuid)
    }

    @Test
    fun `playlist advertisement is never treated as board proof inside active mesh`() = runTest {
        boardCellSnapshots.value = com.cruxcoach.android.boardcell.BoardCellSnapshot(
            cellId = com.cruxcoach.android.boardcell.BoardCellId("cell"),
            physicalBoardId = com.cruxcoach.android.boardcell.PhysicalBoardId("board"),
            epoch = 1,
            sequence = 1,
            controllerId = "controller",
            lineageId = "lineage",
            members = setOf("controller", "participant"),
        )
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_A))
        advanceUntilIdle()

        assertNull(manager.uiState.value.onBoardClimb)
    }

    @Test
    fun `legacy disconnect request is not advertised while BoardCell owns board`() = runTest {
        sharingEnabledFlow.value = true
        boardCellSnapshots.value = com.cruxcoach.android.boardcell.BoardCellSnapshot(
            cellId = com.cruxcoach.android.boardcell.BoardCellId("cell"),
            physicalBoardId = com.cruxcoach.android.boardcell.PhysicalBoardId("board"),
            epoch = 1,
            sequence = 1,
            controllerId = "controller",
            lineageId = "lineage",
            members = setOf("controller", "participant"),
        )

        manager.requestDisconnect()

        assertFalse(manager.uiState.value.isRequestingDisconnect)
        verify(exactly = 0) { climbAdvertiser.advertiseDisconnectRequest() }
    }

    @Test
    fun `resolve - REMOTE_ACTIVE beats everything`() = runTest {
        lastClimbFlow.value = climb(UUID_B)
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A))
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_C))
        advanceUntilIdle()

        val state = manager.uiState.value
        assertEquals(OnBoardSource.REMOTE_ACTIVE, state.onBoardClimb?.source)
        assertEquals(UUID_A, state.onBoardClimb?.climbUuid)
    }

    // ===== 2. Priority: REMOTE_LAST =====

    @Test
    fun `resolve - REMOTE_LAST when no active climb`() = runTest {
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A, isLastClimb = true))
        advanceUntilIdle()

        val state = manager.uiState.value
        assertEquals(OnBoardSource.REMOTE_LAST, state.onBoardClimb?.source)
        assertEquals(UUID_A, state.onBoardClimb?.climbUuid)
    }

    @Test
    fun `resolve - volatile REMOTE_LAST is labelled as not projected`() = runTest {
        nearbyClimbsFlow.value = listOf(nearbyClimb(
            UUID_A,
            isLastClimb = true,
            projectionSurvivesDisconnect = false,
        ))
        advanceUntilIdle()

        val state = manager.uiState.value
        assertEquals(OnBoardSource.REMOTE_LAST, state.onBoardClimb?.source)
        assertFalse(state.onBoardClimb?.isStillProjected ?: true)
    }

    @Test
    fun `resolve - REMOTE_LAST skipped when we have active climb`() = runTest {
        every { climbAdvertiser.hasActiveClimb() } returns true
        lastClimbFlow.value = climb(UUID_B)
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A, isLastClimb = true))
        advanceUntilIdle()

        val state = manager.uiState.value
        // Should fall through to LOCAL_ACTIVE (our own climb is authoritative)
        assertEquals(OnBoardSource.LOCAL_ACTIVE, state.onBoardClimb?.source)
        assertEquals(UUID_B, state.onBoardClimb?.climbUuid)
    }

    // ===== 3. Priority: SESSION_REMOTE for PARTICIPANT (suppressed in on-board) =====

    @Test
    fun `resolve - SESSION_REMOTE suppressed for participant when queue shows same climb`() = runTest {
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.PARTICIPANT,
            queue = listOf(QueueItem(UUID_A, 40)), currentIndex = 0
        )
        lastClimbFlow.value = climb(UUID_B)
        advanceUntilIdle()

        val state = manager.uiState.value
        // SESSION_REMOTE is resolved internally but suppressed in onBoardClimb
        // because the queue banner already shows the current climb
        assertNull(state.onBoardClimb)
    }

    @Test
    fun `resolve - SESSION_REMOTE suppressed for participant even with LOCAL_ACTIVE`() = runTest {
        every { climbAdvertiser.hasActiveClimb() } returns true
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.PARTICIPANT,
            queue = listOf(QueueItem(UUID_A, 40)), currentIndex = 0
        )
        lastClimbFlow.value = climb(UUID_B)
        advanceUntilIdle()

        val state = manager.uiState.value
        // Suppressed: queue banner handles the display
        assertNull(state.onBoardClimb)
    }

    // ===== 4. Priority: LOCAL_ACTIVE =====

    @Test
    fun `resolve - LOCAL_ACTIVE when connected and sending`() = runTest {
        every { climbAdvertiser.hasActiveClimb() } returns true
        lastClimbFlow.value = climb(UUID_A)
        advanceUntilIdle()

        val state = manager.uiState.value
        assertEquals(OnBoardSource.LOCAL_ACTIVE, state.onBoardClimb?.source)
    }

    // ===== 5. Priority: SESSION_REMOTE for non-participant =====

    @Test
    fun `resolve - SESSION_REMOTE beats LOCAL_MANAGER for non-participant`() = runTest {
        // role = NONE (not in any session), but a session is visible nearby
        lastClimbFlow.value = climb(UUID_B)
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_A))
        advanceUntilIdle()

        val state = manager.uiState.value
        assertEquals(OnBoardSource.SESSION_REMOTE, state.onBoardClimb?.source)
        assertEquals(UUID_A, state.onBoardClimb?.climbUuid)
    }

    @Test
    fun `resolve - LOCAL_ACTIVE beats SESSION_REMOTE for non-participant`() = runTest {
        // When user is actively connected and sending, their climb is authoritative
        every { climbAdvertiser.hasActiveClimb() } returns true
        lastClimbFlow.value = climb(UUID_A)
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_B))
        advanceUntilIdle()

        val state = manager.uiState.value
        // LOCAL_ACTIVE wins over non-participant SESSION_REMOTE
        assertEquals(OnBoardSource.LOCAL_ACTIVE, state.onBoardClimb?.source)
        assertEquals(UUID_A, state.onBoardClimb?.climbUuid)
    }

    // ===== 6. Priority: LOCAL_MANAGER (lowest) =====

    @Test
    fun `resolve - LOCAL_MANAGER as fallback`() = runTest {
        lastClimbFlow.value = climb(UUID_A)
        advanceUntilIdle()

        val state = manager.uiState.value
        assertEquals(OnBoardSource.LOCAL_MANAGER, state.onBoardClimb?.source)
        assertEquals(UUID_A, state.onBoardClimb?.climbUuid)
    }

    @Test
    fun `resolve - null when nothing available`() = runTest {
        advanceUntilIdle()

        val state = manager.uiState.value
        assertNull(state.onBoardClimb)
    }

    // ===== Suppression logic =====

    @Test
    fun `suppression - HOST with queue suppresses on-board to null`() = runTest {
        val item = QueueItem(UUID_A, 40)
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.HOST, queue = listOf(item), currentIndex = 0
        )
        lastClimbFlow.value = climb(UUID_A)
        advanceUntilIdle()

        val state = manager.uiState.value
        // HOST with active queue → on-board suppressed (queue banner shows the climb)
        assertNull(state.onBoardClimb)
    }

    @Test
    fun `suppression - REMOTE_ACTIVE never suppressed`() = runTest {
        val item = QueueItem(UUID_A, 40)
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.HOST, queue = listOf(item), currentIndex = 0
        )
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A))
        advanceUntilIdle()

        val state = manager.uiState.value
        // REMOTE_ACTIVE should never be suppressed, even if UUID matches
        assertEquals(OnBoardSource.REMOTE_ACTIVE, state.onBoardClimb?.source)
    }

    @Test
    fun `suppression - SESSION_REMOTE for member is suppressed when queue has current climb`() = runTest {
        val item = QueueItem(UUID_A, 40)
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.PARTICIPANT, queue = listOf(item), currentIndex = 0
        )
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_A))
        advanceUntilIdle()

        val state = manager.uiState.value
        // SESSION_REMOTE is suppressed for session members (queue banner shows the climb)
        assertNull(state.onBoardClimb)
    }

    @Test
    fun `suppression - skipped during isConnecting to prevent null gap`() = runTest {
        val item = QueueItem(UUID_A, 40)
        // isConnecting=true, role=NONE — the temporal gap during GATT join
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.NONE, isConnecting = true,
            queue = listOf(item), currentIndex = 0
        )
        lastClimbFlow.value = climb(UUID_A)
        advanceUntilIdle()

        val state = manager.uiState.value
        // LOCAL_MANAGER should NOT be suppressed during connecting
        assertNotNull("on-board must not be null during isConnecting", state.onBoardClimb)
        assertEquals(OnBoardSource.LOCAL_MANAGER, state.onBoardClimb?.source)
    }

    @Test
    fun `suppression - skipped when role is NONE and not connecting`() = runTest {
        val item = QueueItem(UUID_A, 40)
        // role=NONE, isConnecting=false, but queue has data (edge case after endQueue)
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.NONE, queue = listOf(item), currentIndex = 0
        )
        lastClimbFlow.value = climb(UUID_A)
        advanceUntilIdle()

        val state = manager.uiState.value
        // Suppression requires role != NONE, so LOCAL_MANAGER stays
        assertNotNull(state.onBoardClimb)
        assertEquals(OnBoardSource.LOCAL_MANAGER, state.onBoardClimb?.source)
    }

    @Test
    fun `suppression - different UUID HOST queue still suppresses on-board`() = runTest {
        val item = QueueItem(UUID_B, 40)
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.HOST, queue = listOf(item), currentIndex = 0
        )
        lastClimbFlow.value = climb(UUID_A)
        advanceUntilIdle()

        val state = manager.uiState.value
        // HOST with active queue → on-board suppressed even with different UUID
        // (queue banner shows the current climb)
        assertNull(state.onBoardClimb)
    }

    // ===== Bridge logic =====

    @Test
    fun `bridge - remote ClimbData updates boardStateManager`() = runTest {
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A, angle = 40))
        advanceUntilIdle()

        coVerify { boardStateManager.setLastClimb(UUID_A, 40) }
    }

    @Test
    fun `bridge - remote ClimbData clears own active climb`() = runTest {
        every { climbAdvertiser.hasActiveClimb() } returns true
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A))
        advanceUntilIdle()

        coVerify { climbAdvertiser.clearActiveClimb() }
    }

    @Test
    fun `bridge - remote LastClimb accepted when no active climb`() = runTest {
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A, isLastClimb = true))
        advanceUntilIdle()

        coVerify { boardStateManager.setLastClimb(UUID_A, 40) }
    }

    @Test
    fun `bridge - remote LastClimb ignored when we have active climb`() = runTest {
        every { climbAdvertiser.hasActiveClimb() } returns true
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A, isLastClimb = true))
        advanceUntilIdle()

        // Should NOT update boardStateManager for LastClimb when we have active
        coVerify(exactly = 0) { boardStateManager.setLastClimb(any(), any()) }
    }

    @Test
    fun `bridge - connectedOnly entries ignored`() = runTest {
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A, connectedOnly = true))
        advanceUntilIdle()

        coVerify(exactly = 0) { boardStateManager.setLastClimb(any(), any()) }
    }

    // ===== Board occupied count =====

    @Test
    fun `boardOccupiedCount counts connectedOnly entries`() = runTest {
        nearbyClimbsFlow.value = listOf(
            nearbyClimb("", connectedOnly = true),
            nearbyClimb("", connectedOnly = true),
            nearbyClimb(UUID_A, connectedOnly = false)
        )
        advanceUntilIdle()

        assertEquals(2, manager.uiState.value.boardOccupiedCount)
    }

    // ===== Session mapping =====

    @Test
    fun `nearbySessions mapped correctly`() = runTest {
        nearbySessionsFlow.value = listOf(
            session(sessionId = 111, hostName = "Alice", currentClimbUuid = UUID_A)
        )
        climbInfosFlow.value = mapOf(UUID_A to ClimbDisplayInfo("Cool Boulder", null))
        advanceUntilIdle()

        val sessions = manager.uiState.value.nearbySessions
        assertEquals(1, sessions.size)
        assertEquals("Alice", sessions[0].hostName)
        assertEquals(111, sessions[0].sessionId)
        assertEquals(UUID_A, sessions[0].currentClimbUuid)
        assertEquals("Cool Boulder", sessions[0].currentClimbName)
    }

    @Test
    fun `nearbySessions empty hostName becomes Unbekannt`() = runTest {
        nearbySessionsFlow.value = listOf(session(hostName = ""))
        advanceUntilIdle()

        assertEquals("Unbekannt", manager.uiState.value.nearbySessions[0].hostName)
    }

    // ===== Own session state =====

    @Test
    fun `ownSession null when role is NONE and not connecting`() = runTest {
        queueStateFlow.value = SessionQueueState(role = SessionRole.NONE)
        advanceUntilIdle()

        assertNull(manager.uiState.value.ownSession)
    }

    @Test
    fun `ownSession present when isConnecting even if role is NONE`() = runTest {
        queueStateFlow.value = SessionQueueState(role = SessionRole.NONE, isConnecting = true)
        advanceUntilIdle()

        assertNotNull("ownSession should exist during isConnecting", manager.uiState.value.ownSession)
    }

    @Test
    fun `ownSession reflects HOST role`() = runTest {
        queueStateFlow.value = SessionQueueState(role = SessionRole.HOST, participantCount = 3)
        advanceUntilIdle()

        val own = manager.uiState.value.ownSession
        assertNotNull(own)
        assertTrue(own!!.isHost)
        assertEquals(3, own.participantCount)
    }

    @Test
    fun `ownSession reflects PARTICIPANT role`() = runTest {
        queueStateFlow.value = SessionQueueState(role = SessionRole.PARTICIPANT, participantCount = 2)
        advanceUntilIdle()

        val own = manager.uiState.value.ownSession
        assertNotNull(own)
        assertFalse(own!!.isHost)
    }

    // ===== canRequestDisconnect =====

    @Test
    fun `canRequestDisconnect true when sharing enabled and active remote`() = runTest {
        sharingEnabledFlow.value = true
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A))
        advanceUntilIdle()

        assertTrue(manager.uiState.value.canRequestDisconnect)
    }

    @Test
    fun `canRequestDisconnect false when sharing disabled`() = runTest {
        sharingEnabledFlow.value = false
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A))
        advanceUntilIdle()

        assertFalse(manager.uiState.value.canRequestDisconnect)
    }

    @Test
    fun `canRequestDisconnect false when only LastClimb entries`() = runTest {
        sharingEnabledFlow.value = true
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A, isLastClimb = true))
        advanceUntilIdle()

        assertFalse(manager.uiState.value.canRequestDisconnect)
    }

    @Test
    fun `canRequestDisconnect false when active sender rejects handover`() = runTest {
        sharingEnabledFlow.value = true
        nearbyClimbsFlow.value = listOf(
            nearbyClimb(UUID_A, acceptsDisconnectRequests = false),
        )
        advanceUntilIdle()

        assertFalse(manager.uiState.value.canRequestDisconnect)
    }

    // ===== Name resolution =====

    @Test
    fun `climb name resolved from climbInfos map`() = runTest {
        climbInfosFlow.value = mapOf(UUID_A to ClimbDisplayInfo("Named Boulder", null))
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A))
        advanceUntilIdle()

        assertEquals("Named Boulder", manager.uiState.value.onBoardClimb?.name)
    }

    @Test
    fun `climb name null when not in climbInfos map`() = runTest {
        climbInfosFlow.value = emptyMap()
        nearbyClimbsFlow.value = listOf(nearbyClimb(UUID_A))
        advanceUntilIdle()

        assertNull(manager.uiState.value.onBoardClimb?.name)
    }

    // ===== Session climb updates (FEAT-035 core scenario) =====

    @Test
    fun `a participant without a known session id still sees the host climb`() = runTest {
        // Joining without a scan entry leaves the id at 0. Treating that as
        // "no match" would hide the host's climb — worse than the bug it
        // guards against, and the case that made an earlier version of this
        // filter wrong in production while its test passed.
        queueStateFlow.value = SessionQueueState(role = SessionRole.PARTICIPANT, sessionId = 0)
        nearbySessionsFlow.value = listOf(session(sessionId = 777, currentClimbUuid = UUID_A))
        advanceUntilIdle()

        assertEquals(UUID_A, manager.uiState.value.onBoardClimb?.climbUuid)
    }

    @Test
    fun `a stranger's session climb is not shown to a host`() = runTest {
        // Own queue empty, so stages 3 and 4 pass. Before the session-id check
        // the first foreign advertisement won stage 5 and was labelled
        // "session climb" beside the member's own queue banner.
        queueStateFlow.value =
            SessionQueueState(role = SessionRole.HOST, sessionId = 12345)
        nearbySessionsFlow.value = listOf(session(sessionId = 999, currentClimbUuid = UUID_C))
        advanceUntilIdle()

        assertNull(manager.uiState.value.onBoardClimb?.climbUuid)
    }

    @Test
    fun `session climb updates when host navigates queue`() = runTest {
        queueStateFlow.value = SessionQueueState(role = SessionRole.PARTICIPANT)
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_A))
        advanceUntilIdle()
        assertEquals(UUID_A, manager.uiState.value.onBoardClimb?.climbUuid)

        // Host navigates to different climb
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_B))
        advanceUntilIdle()
        assertEquals(UUID_B, manager.uiState.value.onBoardClimb?.climbUuid)
        assertEquals(OnBoardSource.SESSION_REMOTE, manager.uiState.value.onBoardClimb?.source)
    }

    // ===== Full join flow regression (the bug scenario) =====

    @Test
    fun `full join flow - no null gap between LOCAL_MANAGER and SESSION_REMOTE`() = runTest {
        // Step 1: Before join — LOCAL_MANAGER visible
        lastClimbFlow.value = climb(UUID_A)
        advanceUntilIdle()
        assertEquals(OnBoardSource.LOCAL_MANAGER, manager.uiState.value.onBoardClimb?.source)

        // Step 2: Session discovered
        nearbySessionsFlow.value = listOf(session(currentClimbUuid = UUID_A))
        advanceUntilIdle()
        // Non-participant: SESSION_REMOTE should win over LOCAL_MANAGER
        assertEquals(OnBoardSource.SESSION_REMOTE, manager.uiState.value.onBoardClimb?.source)

        // Step 3: User clicks "Beitreten" → isConnecting, queue state arrives
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.NONE, isConnecting = true,
            queue = listOf(QueueItem(UUID_A, 40)), currentIndex = 0
        )
        advanceUntilIdle()
        // Must NOT be null — isConnecting prevents suppression
        assertNotNull("on-board must not be null during connecting", manager.uiState.value.onBoardClimb)

        // Step 4: GATT connected → role = PARTICIPANT
        queueStateFlow.value = SessionQueueState(
            role = SessionRole.PARTICIPANT, isConnecting = false,
            queue = listOf(QueueItem(UUID_A, 40)), currentIndex = 0
        )
        advanceUntilIdle()
        // SESSION_REMOTE is resolved internally but suppressed in onBoardClimb
        // because the queue banner now shows the current climb
        assertNull("on-board suppressed for participant with queue", manager.uiState.value.onBoardClimb)
        // ownSession should be present with the session info
        assertNotNull("ownSession must be present for participant", manager.uiState.value.ownSession)
    }

    companion object {
        private const val UUID_A = "AC9FCF7F01FC44BD835CFC41CB2224DA"
        private const val UUID_B = "D0E5387D5B974D38B4E93FC4DFD61EF6"
        private const val UUID_C = "36E949A6395D4290AF08FDFBCC6010C1"
    }
}
