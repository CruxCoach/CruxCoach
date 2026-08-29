package com.cruxcoach.android.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ClimbBleAdvertiser
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.ble.GattCommand
import com.cruxcoach.android.ble.GattConnectionEvent
import com.cruxcoach.android.ble.NearbyClimb
import com.cruxcoach.android.ble.NearbyClimbScanner
import com.cruxcoach.android.ble.NearbySession
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.ble.SessionClientState
import com.cruxcoach.android.ble.SessionGattClient
import com.cruxcoach.android.ble.SessionGattServer
import com.cruxcoach.android.ble.SessionGattUuids
import com.cruxcoach.android.ble.SessionCommand
import com.cruxcoach.android.ble.SessionQueueProtocol
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for host migration in [SessionGattBridge].
 *
 * Covers the host-handover identity and consent boundaries:
 *
 * 1. **Stale session filter used wrong ID** — participants always have `sessionId=0`
 *    in [SessionQueueState]. The filter `it.sessionId != lastHostSessionId` was a no-op
 *    (no session has id=0). Fix: set `lastHostSessionId` from [NearbyClimbScanner.nearbySessions]
 *    (the host's real advertised ID) inside [SessionGattBridge.joinSession].
 *
 * 2. **Unsigned nearby sessions require consent** — an advertisement can identify an
 *    exact live candidate but cannot authenticate membership or trigger GATT by itself.
 *
 * 3. **Host recovery remains authoritative** — Bluetooth restart restores this host
 *    before it offers any explicitly approved switch to another group.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionGattBridgeMigrationTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    // Real queue manager so we can assert its state transitions
    private lateinit var queueManager: SessionQueueManager

    // Mocked dependencies
    // Returns the resource id rather than a string, so these tests keep naming
    // *which* error is expected without pinning a German wording that moved
    // into strings.xml.
    private val mockContext = mockk<Context>(relaxed = true).also {
        every { it.getString(any()) } answers { "res:${firstArg<Int>()}" }
        // The participant label is formatted, so it takes the vararg overload.
        every { it.getString(any(), *anyVararg()) } answers {
            "res:${firstArg<Int>()}:${secondArg<Array<Any>>().joinToString(",")}"
        }
    }
    private val mockGattServer = mockk<SessionGattServer>(relaxed = true)
    private val mockGattClient = mockk<SessionGattClient>(relaxed = true)
    private val mockAdvertiser = mockk<ClimbBleAdvertiser>(relaxed = true)
    private val mockNearbyScanner = mockk<NearbyClimbScanner>(relaxed = true)
    private val mockBleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val mockBoardRepository = mockk<BoardRepository>(relaxed = true)
    private val mockBoardStateManager = mockk<BoardStateManager>(relaxed = true)
    private val mockClimbNameResolver = mockk<ClimbNameResolver>(relaxed = true)
    private val mockUserPreferences = mockk<UserPreferences>(relaxed = true)
    private val mockBoardSessionManager = mockk<BoardSessionManager>(relaxed = true)

    /**
     * The host broadcasts the rest phase, so the bridge now collects this.
     *
     * A real flow, not the relaxed mock's stand-in: `StateFlow.collect`
     * returns `Nothing`, and a relaxed mock of that throws
     * KotlinNothingValueException. Thrown inside the bridge's own
     * `scope.launch` it never reaches the test that caused it — it surfaces as
     * UncaughtExceptionsBeforeTest in whichever tests run next in the same
     * JVM, which is how one missing stub failed nine tests across three
     * unrelated classes.
     */
    private val restTimerFlow = MutableStateFlow(RestTimerState())

    // Controllable flows for SessionGattClient
    private val clientStateFlow = MutableStateFlow(SessionClientState.DISCONNECTED)
    private val sessionInfoFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    private val queueEventsFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    private val queueStateFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    private val participantListFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)
    private val currentClimbFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 4)

    // Controllable flows for NearbyClimbScanner
    private val nearbyClimbsFlow = MutableStateFlow<List<NearbyClimb>>(emptyList())
    private val nearbySessionsFlow = MutableStateFlow<List<NearbySession>>(emptyList())

    // Controllable flows for SessionGattServer
    private val serverCommandsFlow = MutableSharedFlow<GattCommand>(extraBufferCapacity = 4)
    private val serverConnectionEventsFlow =
        MutableSharedFlow<GattConnectionEvent>(extraBufferCapacity = 4)

    // Controllable flow for BoardBleConnection
    private val bleConnectionStateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)

    private lateinit var bridge: SessionGattBridge
    private lateinit var managerScope: CoroutineScope
    private var hostingPermissionsGranted = true

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        managerScope = CoroutineScope(SupervisorJob() + testDispatcher)

        every { mockBleConnection.connectionState } returns bleConnectionStateFlow
        every { mockBleConnection.connectedBoardBrand } returns
            MutableStateFlow<BoardBrand?>(null)

        every { mockBoardSessionManager.restTimer } returns restTimerFlow
        every { mockGattClient.connectionState } returns clientStateFlow
        every { mockGattClient.queueEvents } returns queueEventsFlow
        every { mockGattClient.sessionInfoUpdates } returns sessionInfoFlow
        every { mockGattClient.queueStateUpdates } returns queueStateFlow
        every { mockGattClient.participantListUpdates } returns participantListFlow
        every { mockGattClient.currentClimbUpdates } returns currentClimbFlow
        every { mockGattClient.connect(any()) } just Runs
        every { mockGattClient.disconnect() } just Runs
        coEvery { mockGattClient.sendCommand(any()) } returns true
        coEvery { mockGattClient.readInitialState() } just Runs

        every { mockGattServer.commands } returns serverCommandsFlow
        every { mockGattServer.connectionEvents } returns serverConnectionEventsFlow
        every { mockGattServer.start() } returns true
        every { mockGattServer.getConnectedCount() } returns 0
        every { mockGattServer.isConnected(any()) } returns true
        every {
            mockAdvertiser.advertiseSession(any(), any(), any(), any(), any())
        } returns "started"

        every { mockNearbyScanner.nearbyClimbs } returns nearbyClimbsFlow
        every { mockNearbyScanner.nearbySessions } returns nearbySessionsFlow
        every { mockNearbyScanner.disconnectRequests } returns MutableSharedFlow(extraBufferCapacity = 1)

        queueManager = SessionQueueManager(
            mockBleConnection, mockBoardRepository, mockClimbNameResolver, mockUserPreferences,
            managerScope
        )

        bridge = SessionGattBridge(
            context = mockContext,
            queueManager = queueManager,
            gattServer = mockGattServer,
            gattClient = mockGattClient,
            advertiser = mockAdvertiser,
            nearbyScanner = mockNearbyScanner,
            bleConnection = mockBleConnection,
            boardStateManager = mockBoardStateManager,
            boardSessionManager = mockBoardSessionManager,
            hasHostingPermissions = { hostingPermissionsGranted },
            scope = managerScope,
        )
    }

    @After
    fun tearDown() {
        testDispatcher.scheduler.advanceUntilIdle()
        runBlocking {
            managerScope.coroutineContext[Job]?.cancelAndJoin()
        }
        Dispatchers.resetMain()
    }

    // ===== Helpers =====

    private fun mockDevice(address: String): BluetoothDevice =
        mockk<BluetoothDevice>(relaxed = true).also { every { it.address } returns address }

    private fun makeSession(
        sessionId: Int,
        deviceAddress: String,
        device: BluetoothDevice? = null
    ) = NearbySession(
        sessionId = sessionId,
        participantCount = 2,
        hostName = "TestHost",
        rssi = -60,
        lastSeenMs = System.currentTimeMillis(),
        deviceAddress = deviceAddress,
        device = device
    )

    private fun sentinelBytes(): ByteArray =
        SessionQueueProtocol.encodeSessionInfo("", 0)

    private fun mockConnectedBoard(
        brand: BoardBrand,
        advertisesWhileConnected: Boolean,
        apiLevel: Int = 0,
    ) {
        every { mockBleConnection.connectedBoardBrand } returns MutableStateFlow(brand)
        every { mockBleConnection.connectedBoard } returns DiscoveredBoard(
            displayName = brand.name,
            serial = "test-controller",
            apiLevel = apiLevel,
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -50,
            boardBrand = brand,
            advertisesWhileConnected = advertisesWhileConnected,
        )
    }

    /**
     * Simulates a participant joining a session: triggers joinSession, drives the
     * CONNECTED state through the GATT client flow, and populates the queue so
     * migration has items to work with.
     */
    private fun participantJoinsSession(hostDevice: BluetoothDevice, sessionId: Int) {
        nearbySessionsFlow.value = listOf(makeSession(sessionId, hostDevice.address, hostDevice))
        bridge.joinSession(hostDevice)
        // Trigger the CONNECTED handler — sets role=PARTICIPANT
        clientStateFlow.value = SessionClientState.CONNECTED
        // Add climbs so migration has a non-empty queue
        queueManager.applyRemoteState(0, listOf(
            QueueItem("climb-a", 40),
            QueueItem("climb-b", 40)
        ))
    }

    @Test
    fun `failed initial join ends timer and restores standalone advertising`() =
        runTest(testDispatcher.scheduler) {
            val hostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            nearbySessionsFlow.value = listOf(makeSession(12345, hostDevice.address, hostDevice))
            clientStateFlow.value = SessionClientState.CONNECTING

            bridge.joinSession(hostDevice)
            clientStateFlow.value = SessionClientState.DISCONNECTED

            assertEquals("res:${R.string.ble_error_connect_failed}", queueManager.state.value.error)
            verify { mockAdvertiser.suppressClimbAdvertising = false }
            verify(exactly = 1) { mockBoardSessionManager.endSession() }
        }

    @Test
    fun `solo MoonBoard host keeps physical connection when sharing ends`() {
        mockConnectedBoard(BoardBrand.MOONBOARD, advertisesWhileConnected = true)
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        bleConnectionStateFlow.value = ConnectionState.CONNECTED
        bridge.startSharing()

        bridge.stopSharing()

        verify(exactly = 0) { mockBleConnection.disconnect() }
    }

    @Test
    fun `multi-connect MoonBoard host stays connected for a real successor`() {
        mockConnectedBoard(BoardBrand.MOONBOARD, advertisesWhileConnected = true)
        every { mockGattServer.getConnectedCount() } returns 1
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        queueManager.addParticipant("AA:BB:CC:DD:EE:02", "Teilnehmer 1")
        bleConnectionStateFlow.value = ConnectionState.CONNECTED
        bridge.startSharing()

        bridge.stopSharing()

        verify(exactly = 0) { mockBleConnection.disconnect() }
    }

    @Test
    fun `stale participant count does not release a solo MoonBoard`() {
        mockConnectedBoard(BoardBrand.MOONBOARD, advertisesWhileConnected = true)
        every { mockGattServer.getConnectedCount() } returns 0
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        queueManager.addParticipant("AA:BB:CC:DD:EE:02", "Teilnehmer 1")
        bleConnectionStateFlow.value = ConnectionState.CONNECTED
        bridge.startSharing()

        bridge.stopSharing()

        verify(exactly = 0) { mockBleConnection.disconnect() }
    }

    @Test
    fun `legacy single-connect board is released when sharing ends`() {
        mockConnectedBoard(
            BoardBrand.KILTER,
            advertisesWhileConnected = false,
            apiLevel = 2,
        )
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        bleConnectionStateFlow.value = ConnectionState.CONNECTED
        bridge.startSharing()

        bridge.stopSharing()

        verify(exactly = 1) { mockBleConnection.disconnect() }
    }

    @Test
    fun `failed relay startup can tear down sharing without dropping the board`() {
        mockConnectedBoard(
            BoardBrand.KILTER,
            advertisesWhileConnected = false,
            apiLevel = 2,
        )
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        bleConnectionStateFlow.value = ConnectionState.CONNECTED
        bridge.startSharing()

        bridge.stopSharing(allowBoardRelease = false)

        verify(exactly = 0) { mockBleConnection.disconnect() }
    }

    @Test
    fun `external relay projection is not mislabeled as the queue climb on stop`() {
        mockConnectedBoard(
            BoardBrand.KILTER,
            advertisesWhileConnected = false,
            apiLevel = 2,
        )
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        queueManager.addClimb("305ecf35-4ab5-4c9c-afd5-91af0848004b", 40)
        queueManager.markExternalBoardWrite()
        bleConnectionStateFlow.value = ConnectionState.CONNECTED
        bridge.startSharing()

        bridge.stopSharing()

        verify(exactly = 0) {
            mockBoardStateManager.setLastClimbQuick(any(), any(), any())
        }
    }

    // ===== Test 1: a participant carries the host's session id =====

    /**
     * [SessionGattBridge.joinSession] reads the host's advertised session id
     * from the scan and hands it to [SessionQueueManager.setParticipantRole].
     *
     * It used to pass a literal 0, which left participants with no session
     * identity: the on-board resolver could not tell this session's
     * advertisement from a stranger's, and a member with an empty queue was
     * shown someone else's climb as their own session's.
     *
     * Migration also uses the captured id to ignore the departed host's stale
     * advertisement while it looks for a successor candidate.
     */
    @Test
    fun `a participant carries the host session id`() {
        queueManager.setParticipantRole(4711, "SomeHost")

        assertEquals(
            "the host's id must reach the queue state so the session can be identified",
            4711,
            queueManager.state.value.sessionId
        )
    }

    // ===== Test 2: migration promotes to host when only the old host's session is visible =====

    /**
     * Regression for Bug 1 (stale-session filter was a no-op).
     *
     * When the host ends the session and migration starts, the dying host's BLE advertisement
     * can still be visible for ~5 seconds (NearbyClimbScanner stale timeout). Before the fix,
     * the migration filter used queueManager.state.sessionId (always 0) → filter matched
     * nothing → migration joined the dying host → connection failed → queue lost.
     *
     * After the fix, joinSession() records the host's real advertised session ID from
     * nearbySessions, and migration filters it out correctly.
     */
    @Test
    fun `migration promotes to host when only old host session is visible`() = runTest(testDispatcher.scheduler) {
        val hostDevice = mockDevice("AA:BB:CC:DD:EE:01")
        val hostSessionId = 12345

        // Join the host's session — records lastHostSessionId = 12345
        participantJoinsSession(hostDevice, hostSessionId)

        // During migration, only the old (dying) host's session is still advertising
        nearbySessionsFlow.value = listOf(makeSession(hostSessionId, "AA:BB:CC:DD:EE:01", hostDevice))

        // Host sends session-ended sentinel
        sessionInfoFlow.emit(sentinelBytes())

        // Advance through migration wait: index=0 → waitMs=1000ms, polling every 500ms
        advanceTimeBy(1_100)

        assertEquals(
            "Migration must promote to HOST when the only nearby session is the stale old-host session",
            SessionRole.HOST,
            queueManager.state.value.role
        )
        assertEquals("Queue must be preserved after promotion", 2, queueManager.state.value.queue.size)
        assertTrue(queueManager.state.value.pendingHostVisibilityDecision)
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibilityRequested)
        verify(exactly = 0) { mockGattServer.start() }

        // Publication starts only after the promoted host's explicit answer.
        queueManager.setVisibilityRequested(SessionVisibility.JOINABLE)
        bridge.startSharing()
        assertFalse(queueManager.state.value.pendingHostVisibilityDecision)
        assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibility)
        verify(exactly = 1) { mockGattServer.start() }
    }

    // ===== Test 3: migration requires explicit inbound membership consent =====

    @Test
    fun `first elected successor cannot be diverted by foreign advertisement`() =
        runTest(testDispatcher.scheduler) {
            val oldHostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            val foreignDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            participantJoinsSession(oldHostDevice, 11111)
            queueManager.setParticipantIndex(0)
            nearbySessionsFlow.value = listOf(
                makeSession(11111, "AA:BB:CC:DD:EE:01", oldHostDevice),
                makeSession(99999, "FF:EE:DD:CC:BB:AA", foreignDevice),
            )

            sessionInfoFlow.emit(sentinelBytes())
            advanceTimeBy(1_100)

            verify(exactly = 0) { mockGattClient.connect(foreignDevice) }
            assertNull(bridge.pendingSuccessorJoin.value)
            assertEquals(SessionRole.HOST, queueManager.state.value.role)
            assertTrue(queueManager.state.value.pendingHostVisibilityDecision)
        }

    /**
     * A new advertisement is unsigned. It may be shown as an exact candidate,
     * but it must not open GATT until the user explicitly approves it.
     */
    @Test
    fun `migration stages exact successor and joins only after confirmation`() =
        runTest(testDispatcher.scheduler) {
            val oldHostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            val oldHostSessionId = 11111

            val newHostDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            val newHostSessionId = 22222

            // Join the old host's session
            participantJoinsSession(oldHostDevice, oldHostSessionId)
            queueManager.setParticipantIndex(1)

            // A different session appears alongside the stale old-host advertisement.
            nearbySessionsFlow.value = listOf(
                makeSession(oldHostSessionId, "AA:BB:CC:DD:EE:01", oldHostDevice),
                makeSession(newHostSessionId, "FF:EE:DD:CC:BB:AA", newHostDevice),
            )

            sessionInfoFlow.emit(sentinelBytes())

            // Index 1 waits 4s, allowing index 0 to become the real successor first.
            advanceTimeBy(4_100)

            assertEquals(
                PendingSuccessorJoin(
                    sessionId = newHostSessionId,
                    deviceAddress = "FF:EE:DD:CC:BB:AA",
                    hostName = "TestHost",
                    origin = PendingSuccessorOrigin.HOST_MIGRATION,
                ),
                bridge.pendingSuccessorJoin.value,
            )
            verify(exactly = 0) { mockGattClient.connect(newHostDevice) }
            assertEquals(SessionRole.PARTICIPANT, queueManager.state.value.role)

            bridge.confirmPendingSuccessorJoin()

            verify(exactly = 1) { mockGattClient.connect(newHostDevice) }
            assertNull(bridge.pendingSuccessorJoin.value)
        }

    @Test
    fun `declining unverified successor promotes the preserved queue locally once`() =
        runTest(testDispatcher.scheduler) {
            val oldHostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            val newHostDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            participantJoinsSession(oldHostDevice, 11111)
            queueManager.setParticipantIndex(1)
            nearbySessionsFlow.value = listOf(
                makeSession(22222, "FF:EE:DD:CC:BB:AA", newHostDevice),
            )
            sessionInfoFlow.emit(sentinelBytes())
            advanceTimeBy(4_100)

            bridge.declinePendingSuccessorJoin()
            bridge.declinePendingSuccessorJoin()

            verify(exactly = 0) { mockGattClient.connect(newHostDevice) }
            assertNull(bridge.pendingSuccessorJoin.value)
            assertEquals(SessionRole.HOST, queueManager.state.value.role)
            assertEquals(2, queueManager.state.value.queue.size)
            assertTrue(queueManager.state.value.pendingHostVisibilityDecision)
            assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
        }

    @Test
    fun `confirmation cannot follow a replaced advertisement at the same address`() =
        runTest(testDispatcher.scheduler) {
            val oldHostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            val offeredDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            val replacementDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            participantJoinsSession(oldHostDevice, 11111)
            queueManager.setParticipantIndex(1)
            nearbySessionsFlow.value = listOf(
                makeSession(22222, "FF:EE:DD:CC:BB:AA", offeredDevice),
            )
            sessionInfoFlow.emit(sentinelBytes())
            advanceTimeBy(4_100)

            nearbySessionsFlow.value = listOf(
                makeSession(33333, "FF:EE:DD:CC:BB:AA", replacementDevice),
            )
            bridge.confirmPendingSuccessorJoin()

            verify(exactly = 0) { mockGattClient.connect(offeredDevice) }
            verify(exactly = 0) { mockGattClient.connect(replacementDevice) }
            assertEquals(SessionRole.HOST, queueManager.state.value.role)
            assertTrue(queueManager.state.value.pendingHostVisibilityDecision)
        }

    @Test
    fun `queue teardown clears successor offer so a late answer cannot connect`() =
        runTest(testDispatcher.scheduler) {
            val oldHostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            val offeredDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            participantJoinsSession(oldHostDevice, 11111)
            queueManager.setParticipantIndex(1)
            nearbySessionsFlow.value = listOf(
                makeSession(22222, "FF:EE:DD:CC:BB:AA", offeredDevice),
            )
            sessionInfoFlow.emit(sentinelBytes())
            advanceTimeBy(4_100)
            assertTrue(bridge.pendingSuccessorJoin.value != null)

            bridge.leaveSession()
            bridge.confirmPendingSuccessorJoin()

            verify(exactly = 0) { mockGattClient.connect(offeredDevice) }
            assertNull(bridge.pendingSuccessorJoin.value)
            assertEquals(SessionRole.NONE, queueManager.state.value.role)
        }

    @Test
    fun `explicit leave during election cannot resurrect the queue`() =
        runTest(testDispatcher.scheduler) {
            val oldHostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            participantJoinsSession(oldHostDevice, 11111)
            queueManager.setParticipantIndex(0)
            sessionInfoFlow.emit(sentinelBytes())
            advanceTimeBy(500)

            bridge.leaveSession()
            advanceTimeBy(1_000)

            assertEquals(SessionRole.NONE, queueManager.state.value.role)
            assertFalse(queueManager.state.value.isActive)
            assertNull(bridge.pendingSuccessorJoin.value)
        }

    @Test
    fun `manual join during election cancels delayed promotion`() =
        runTest(testDispatcher.scheduler) {
            val oldHostDevice = mockDevice("AA:BB:CC:DD:EE:01")
            val chosenDevice = mockDevice("11:22:33:44:55:66")
            participantJoinsSession(oldHostDevice, 11111)
            queueManager.setParticipantIndex(0)
            sessionInfoFlow.emit(sentinelBytes())
            advanceTimeBy(500)

            nearbySessionsFlow.value = listOf(
                makeSession(33333, "11:22:33:44:55:66", chosenDevice),
            )
            bridge.joinSession(chosenDevice)
            advanceTimeBy(1_000)

            verify(exactly = 1) { mockGattClient.connect(chosenDevice) }
            assertEquals(SessionRole.PARTICIPANT, queueManager.state.value.role)
            assertEquals(33333, queueManager.state.value.sessionId)
            assertNull(bridge.pendingSuccessorJoin.value)
        }

    // ===== Test 4: DISCONNECTED with isConnecting retries migration, not error =====

    /**
     * Regression for Bug 2 (failed join showed error instead of retrying migration).
     *
     * When migration calls joinSession() and the GATT connection fails immediately
     * (DISCONNECTED fires with isConnecting=true), the participant's queue must not be
     * stranded with an error. Migration must be retried so a successor host can emerge.
     */
    @Test
    fun `failed migration join does not produce error and keeps queue intact`() = runTest(testDispatcher.scheduler) {
        val hostDevice = mockDevice("AA:BB:CC:DD:EE:01")
        val hostSessionId = 99999

        participantJoinsSession(hostDevice, hostSessionId)

        // Only stale session is nearby — migration will find nothing and promote after wait
        nearbySessionsFlow.value = listOf(makeSession(hostSessionId, "AA:BB:CC:DD:EE:01", hostDevice))

        // Sentinel → migration starts
        sessionInfoFlow.emit(sentinelBytes())

        // Simulate a failed join attempt:
        // isConnecting is set true inside joinSession() → setConnecting()
        // DISCONNECTED fires while isConnecting=true
        queueManager.setConnecting()
        clientStateFlow.value = SessionClientState.DISCONNECTED

        // Must NOT show an error
        assertNull(
            "Failed migration join must not set an error on the queue — must retry migration instead",
            queueManager.state.value.error
        )
        // Queue must still be intact
        assertEquals("Queue must be preserved after failed join", 2, queueManager.state.value.queue.size)
    }

    // ===== Test 5: migration ends queue when queue is empty =====

    /**
     * When the host disconnects but the queue is empty, migration should end the queue
     * immediately rather than waiting and self-promoting to host with nothing to play.
     */
    @Test
    fun `migration ends queue immediately when queue is empty`() = runTest(testDispatcher.scheduler) {
        val hostDevice = mockDevice("AA:BB:CC:DD:EE:01")
        nearbySessionsFlow.value = listOf(makeSession(55555, "AA:BB:CC:DD:EE:01", hostDevice))

        bridge.joinSession(hostDevice)
        clientStateFlow.value = SessionClientState.CONNECTED
        queueManager.setParticipantRole(0, "TestHost")
        // Queue stays EMPTY — no applyRemoteState call

        sessionInfoFlow.emit(sentinelBytes())

        assertFalse(
            "With empty queue, migration must end the session (role=NONE), not promote to HOST",
            queueManager.state.value.isActive
        )
        assertEquals(SessionRole.NONE, queueManager.state.value.role)
    }

    // ===== Test 6: lastHostSessionId updated on each joinSession call =====

    /**
     * Regression for Bug 3 (stale session still visible during retry).
     *
     * When the retry path calls joinSession() again (targeting session X), lastHostSessionId
     * must be updated to X so that the NEXT retry filters X correctly.
     * This is verified by confirming migration promotes to host (not re-joining X)
     * when X is the only nearby session across multiple retries.
     */
    @Test
    fun `subsequent migration retries also filter the session that was last tried`() = runTest(testDispatcher.scheduler) {
        val hostDevice = mockDevice("AA:BB:CC:DD:EE:01")
        val hostSessionId = 77777

        participantJoinsSession(hostDevice, hostSessionId)

        // Stale session stays visible throughout
        nearbySessionsFlow.value = listOf(makeSession(hostSessionId, "AA:BB:CC:DD:EE:01", hostDevice))

        // First sentinel → migration starts, filtered → promotes to host
        sessionInfoFlow.emit(sentinelBytes())
        advanceTimeBy(1_100)

        assertEquals(
            "After retry cycle with only stale session visible, must end up as HOST",
            SessionRole.HOST,
            queueManager.state.value.role
        )
    }

    // ===== Session command authorization =====

    @Test
    fun `promoted host without hosting permission does not touch GATT server`() =
        runTest(testDispatcher.scheduler) {
            hostingPermissionsGranted = false
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)

            bridge.startSharing()

            assertTrue(queueManager.state.value.isActive)
            assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibilityRequested)
            assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
            verify(exactly = 0) { mockGattServer.start() }
            verify(exactly = 0) {
                mockAdvertiser.advertiseSession(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `saved playlist cannot open host GATT publication`() =
        runTest(testDispatcher.scheduler) {
            queueManager.loadPlaylist("Private", listOf(QueueItem("climb-a", 40)))

            bridge.startSharing()

            assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
            assertEquals(
                SessionVisibility.LOCAL_ONLY,
                queueManager.state.value.visibilityRequested,
            )
            verify(exactly = 0) { mockGattServer.start() }
            verify(exactly = 0) {
                mockAdvertiser.advertiseSession(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `local host cannot self grant joinable publication by calling startSharing`() =
        runTest(testDispatcher.scheduler) {
            queueManager.startQueue("Local host")

            bridge.startSharing()

            assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
            assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibilityRequested)
            verify(exactly = 0) { mockGattServer.start() }
            verify(exactly = 0) {
                mockAdvertiser.advertiseSession(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `Bluetooth recovery restarts an explicitly joinable host transport`() =
        runTest(testDispatcher.scheduler) {
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)
            bridge.startSharing()

            bridge.recoverAfterBluetoothRestart()

            verify(exactly = 2) { mockGattServer.start() }
            verify(atLeast = 1) { mockGattServer.stop() }
            assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibility)
        }

    @Test
    fun `Bluetooth recovery keeps own host and stages foreign session for consent`() =
        runTest(testDispatcher.scheduler) {
            val foreignDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)
            bridge.startSharing()
            nearbySessionsFlow.value = listOf(
                makeSession(22222, "FF:EE:DD:CC:BB:AA", foreignDevice),
            )

            bridge.recoverAfterBluetoothRestart()

            verify(exactly = 0) { mockGattClient.connect(foreignDevice) }
            verify(exactly = 2) { mockGattServer.start() }
            assertEquals(SessionRole.HOST, queueManager.state.value.role)
            assertEquals(SessionVisibility.JOINABLE, queueManager.state.value.visibility)
            assertEquals(
                PendingSuccessorOrigin.BLUETOOTH_RECOVERY,
                bridge.pendingSuccessorJoin.value?.origin,
            )

            bridge.declinePendingSuccessorJoin()
            bridge.declinePendingSuccessorJoin()

            verify(exactly = 0) { mockGattClient.connect(foreignDevice) }
            verify(exactly = 2) { mockGattServer.start() }
            assertNull(bridge.pendingSuccessorJoin.value)
            assertEquals(SessionRole.HOST, queueManager.state.value.role)
        }

    @Test
    fun `Bluetooth recovery switches groups only after exact live confirmation`() =
        runTest(testDispatcher.scheduler) {
            val foreignDevice = mockDevice("FF:EE:DD:CC:BB:AA")
            every { mockGattServer.getConnectedCount() } returns 1
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)
            bridge.startSharing()
            nearbySessionsFlow.value = listOf(
                makeSession(22222, "FF:EE:DD:CC:BB:AA", foreignDevice),
            )
            bridge.recoverAfterBluetoothRestart()

            bridge.confirmPendingSuccessorJoin()

            verify(exactly = 0) { mockGattClient.connect(foreignDevice) }
            verify(exactly = 1) {
                mockGattServer.notifyAll(
                    SessionGattUuids.SESSION_INFO,
                    any(),
                )
            }
            advanceTimeBy(600)

            verify(exactly = 1) { mockGattClient.connect(foreignDevice) }
            verify(atLeast = 2) { mockGattServer.stop() }
            verifyOrder {
                mockGattServer.notifyAll(SessionGattUuids.SESSION_INFO, any())
                mockGattServer.stop()
                mockGattClient.connect(foreignDevice)
            }
            assertNull(bridge.pendingSuccessorJoin.value)

            clientStateFlow.value = SessionClientState.CONNECTED
            assertEquals(SessionRole.PARTICIPANT, queueManager.state.value.role)
            assertEquals(22222, queueManager.state.value.sessionId)
        }

    @Test
    fun `failed publication keeps the queue running but marks it local-only`() =
        runTest(testDispatcher.scheduler) {
            every {
                mockAdvertiser.advertiseSession(any(), any(), any(), any(), any())
            } returns "no permission"
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)

            bridge.startSharing()

            assertTrue(queueManager.state.value.isActive)
            assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
            assertEquals(
                "res:${R.string.ble_error_publish_failed}",
                queueManager.state.value.error,
            )
            verify { mockGattServer.stop() }
            verify { mockAdvertiser.suppressClimbAdvertising = false }
        }

    @Test
    fun `host drops state-changing command before authorized join`() = runTest(testDispatcher.scheduler) {
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        bridge.startSharing()

        serverCommandsFlow.emit(
            GattCommand(
                "AA:AA:AA:AA:AA:AA",
                SessionQueueProtocol.encodeAdd("550e8400-e29b-41d4-a716-446655440000", 40),
            ),
        )

        assertTrue(queueManager.state.value.queue.isEmpty())
        verify { mockGattServer.cancelDevice("AA:AA:AA:AA:AA:AA") }
    }

    @Test
    fun `open join admits subsequent queue command`() = runTest(testDispatcher.scheduler) {
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        bridge.startSharing()
        val address = "CC:CC:CC:CC:CC:CC"

        serverCommandsFlow.emit(
            GattCommand(address, SessionQueueProtocol.encodeJoin("")),
        )
        // Retries are legal at the transport layer but must be idempotent in
        // the host's participant state.
        serverCommandsFlow.emit(
            GattCommand(address, SessionQueueProtocol.encodeJoin("")),
        )
        serverCommandsFlow.emit(
            GattCommand(
                address,
                SessionQueueProtocol.encodeAdd("550e8400-e29b-41d4-a716-446655440000", 40),
            ),
        )

        assertEquals(1, queueManager.state.value.participants.size)
        assertEquals(
            "res:${R.string.ble_participant_label}:1",
            queueManager.state.value.participants.single().displayName,
        )
        assertEquals(1, queueManager.state.value.queue.size)
    }

    @Test
    fun `context capable peer cannot downgrade a later command to legacy`() =
        runTest(testDispatcher.scheduler) {
            val address = "CE:CE:CE:CE:CE:CE"
            val first = "550e8400-e29b-41d4-a716-446655440000"
            val second = "650e8400-e29b-41d4-a716-446655440001"
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)
            queueManager.addClimb(first, 40)
            queueManager.addClimb(second, 40)
            bridge.startSharing()
            serverCommandsFlow.emit(GattCommand(address, SessionQueueProtocol.encodeJoin("")))

            val state = queueManager.state.value
            val modern = SessionCommand.SetCurrent(0)
            val context = requireNotNull(
                SessionCommandRebaser.context(
                    modern,
                    state.sessionId,
                    state.currentIndex,
                    state.queue,
                ),
            )
            serverCommandsFlow.emit(
                GattCommand(
                    address,
                    SessionQueueProtocol.encodeCommandRequest(7L, modern, context),
                ),
            )
            serverCommandsFlow.emit(
                GattCommand(address, SessionQueueProtocol.encodeRemove(0)),
            )

            assertEquals(listOf(first, second), queueManager.state.value.queue.map { it.climbUuid })
        }

    @Test
    fun `never modern peer retains legacy queue behavior`() = runTest(testDispatcher.scheduler) {
        val address = "AB:AB:AB:AB:AB:AB"
        val first = "550e8400-e29b-41d4-a716-446655440000"
        val second = "650e8400-e29b-41d4-a716-446655440001"
        queueManager.startQueue("Host", SessionVisibility.JOINABLE)
        queueManager.addClimb(first, 40)
        queueManager.addClimb(second, 40)
        bridge.startSharing()
        serverCommandsFlow.emit(GattCommand(address, SessionQueueProtocol.encodeJoin("")))

        serverCommandsFlow.emit(
            GattCommand(address, SessionQueueProtocol.encodeRemove(0)),
        )

        assertEquals(listOf(second), queueManager.state.value.queue.map { it.climbUuid })
    }

    @Test
    fun `join from disconnected address cannot create a phantom participant`() =
        runTest(testDispatcher.scheduler) {
            val address = "DD:DD:DD:DD:DD:DD"
            every { mockGattServer.isConnected(address) } returns false
            queueManager.startQueue("Host", SessionVisibility.JOINABLE)
            bridge.startSharing()

            serverCommandsFlow.emit(
                GattCommand(address, SessionQueueProtocol.encodeJoin("")),
            )

            assertTrue(queueManager.state.value.participants.isEmpty())
            assertEquals(1, queueManager.state.value.participantCount)
            verify { mockGattServer.cancelDevice(address) }
        }
}
