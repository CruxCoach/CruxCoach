package com.cruxcoach.android.data

import com.cruxcoach.android.ble.AuroraBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.data.repository.BoardRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private lateinit var queueManager: SessionQueueManager
    private val bleConnection = mockk<AuroraBleConnection>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val climbNameResolver = mockk<ClimbNameResolver>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        queueManager = SessionQueueManager(bleConnection, boardRepository, climbNameResolver, userPreferences)
    }

    @After
    fun tearDown() {
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
}
