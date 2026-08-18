package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.QueueItem
import com.cruxcoach.android.boardcell.BoardCellAvailability
import com.cruxcoach.android.boardcell.BoardCellId
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.boardcell.BoardCellScopeRegistry
import com.cruxcoach.android.boardcell.BoardCellSnapshot
import com.cruxcoach.android.boardcell.BoardCellWriteGateway
import com.cruxcoach.android.boardcell.BoardPlaylistPendingProjection
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
import com.cruxcoach.android.boardcell.BoardPlaylistRest
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.data.repository.BoardRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * [SessionQueueManager] as the *adapter* for the canonical joinable playlist:
 * it mirrors mesh state into the UI/GATT projection and never becomes a second
 * source of truth for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JoinablePlaylistAdapterTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var managerScope: CoroutineScope
    private lateinit var queueManager: SessionQueueManager

    private val bleConnection = mockk<BoardBleConnection>(relaxed = true)
    private val boardRepository = mockk<BoardRepository>(relaxed = true)
    private val climbNameResolver = mockk<ClimbNameResolver>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true).also {
        every { it.singleConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
        every { it.multiConnectionBoardSendMode } returns flowOf(BoardSendMode.AUTOMATIC)
    }
    private val boardCellManager = mockk<BoardCellManager>(relaxed = true)
    private val snapshots = MutableStateFlow<BoardCellSnapshot?>(null)

    private val localNode = "local-npub"
    private val board = PhysicalBoardId("board-adapter")
    private val cell = BoardCellId.forPhysical(board)

    /** 2026-08-17T12:00:00Z, moved forward explicitly instead of sleeping. */
    private val startOfTest = 1_786_968_000_000L
    private var clockNow = startOfTest

    @Before fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { bleConnection.connectionState } returns MutableStateFlow(ConnectionState.DISCONNECTED)
        every { boardCellManager.snapshots } returns snapshots
        every { boardCellManager.localNodeId() } returns localNode
        managerScope = CoroutineScope(SupervisorJob() + testDispatcher)
        queueManager = SessionQueueManager(
            bleConnection, boardRepository, climbNameResolver, userPreferences, managerScope,
            boardCellManager = boardCellManager,
            boardCellWriteGateway = BoardCellWriteGateway { _, write -> write() },
            nowEpochMs = { clockNow },
        )
    }

    @After fun tearDown() {
        // cancelAndJoin, not cancel: the manager's own collector hops through
        // withContext(Dispatchers.IO), and a continuation still in flight past
        // resetMain() surfaces as an uncaught exception in whichever test runs
        // next in this JVM.
        runBlocking { managerScope.coroutineContext[Job]?.cancelAndJoin() }
        // The cell/board selection is process-global; leaving one bound would
        // reach whichever test runs next in this JVM.
        BoardCellScopeRegistry.resetForTest()
        Dispatchers.resetMain()
    }

    private fun publish(playlist: BoardPlaylistState, revision: Long = 1) {
        snapshots.value = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = revision + 1,
            controllerId = "controller-npub", controllerTerm = 1, lineageId = "lineage",
            members = setOf("controller-npub", localNode, "other-npub"),
            playlist = playlist, playlistRevision = revision,
        ).withComputedHash()
    }

    private fun joinable(
        items: List<Pair<String, Int>> = listOf("a" to 40, "b" to 45),
        rests: List<Int> = listOf(120, 0),
        index: Int = 0,
        host: String = localNode,
        members: List<String> = listOf(localNode),
        rest: BoardPlaylistRest? = null,
        pending: BoardPlaylistPendingProjection? = null,
    ) = BoardPlaylistPolicy.normalize(BoardPlaylistState(
        sessionId = 7, currentIndex = index, items = items, restAfterSeconds = rests,
        hostId = host, members = members, activeRest = rest, pendingProjection = pending))

    // ===== LOCAL_ONLY stays local =====

    @Test fun `a local-only playlist never publishes into the BoardCell`() {
        queueManager.loadPlaylist("Host", listOf(QueueItem("local-a", 40), QueueItem("local-b", 40)))
        queueManager.addClimb("local-c", 40)
        queueManager.nextClimb()
        queueManager.setCurrentClimb(0)
        queueManager.moveClimb(0, 1)
        queueManager.removeClimb(0)

        // The old adapter mirrored every HOST queue change into the canonical
        // playlist, which both leaked local sessions into the mesh and — on a
        // device that was not the controller — silently dropped every write.
        coVerify(exactly = 0) { boardCellManager.replacePlaylist(any(), any(), any()) }
        assertNull(queueManager.state.value.mesh)
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
    }

    @Test fun `a joinable playlist this device did not join leaves a local one running`() {
        queueManager.loadPlaylist("Host", listOf(QueueItem("local-a", 40)))
        val before = queueManager.state.value

        // Mesh membership makes the playlist discoverable, nothing more; this
        // device never joined it, so its own local playlist carries on.
        publish(joinable(host = "other-npub", members = listOf("other-npub")))

        val after = queueManager.state.value
        assertNull(after.mesh)
        assertEquals(before.queue, after.queue)
        assertEquals(before.currentIndex, after.currentIndex)
        assertEquals(SessionRole.HOST, after.role)
    }

    // ===== Canonical state drives the projection =====

    @Test fun `a playlist member mirrors queue, index and rest plan from canonical state`() {
        publish(joinable(index = 1, members = listOf(localNode, "other-npub")))

        val state = queueManager.state.value
        assertEquals(listOf("a", "b"), state.queue.map { it.climbUuid })
        assertEquals(listOf(120, 0), state.queue.map { it.restAfterSeconds })
        assertEquals(1, state.currentIndex)
        assertEquals(7, state.sessionId)
        assertEquals(SessionVisibility.JOINABLE, state.visibility)
        assertEquals(2, state.participantCount)
        val mesh = state.mesh!!
        assertTrue(mesh.isHost)
        assertTrue(mesh.isMember)
        assertEquals(listOf(localNode, "other-npub"), mesh.members)
    }

    @Test fun `a member that is not the playlist host projects as participant`() {
        publish(joinable(host = "other-npub", members = listOf("other-npub", localNode)))

        val state = queueManager.state.value
        assertEquals(SessionRole.PARTICIPANT, state.role)
        assertFalse(state.mesh!!.isHost)
        assertEquals("other-npub", state.mesh!!.hostId)
    }

    @Test fun `ending is only offered to the last remaining member`() {
        publish(joinable(members = listOf(localNode, "other-npub")))
        assertFalse(queueManager.state.value.mesh!!.canEnd)

        publish(joinable(members = listOf(localNode)), revision = 2)
        assertTrue(queueManager.state.value.mesh!!.canEnd)
    }

    @Test fun `a canonical update replaces the projection rather than merging into it`() {
        publish(joinable())
        publish(joinable(items = listOf("x" to 40), rests = listOf(0)), revision = 2)

        assertEquals(listOf("x"), queueManager.state.value.queue.map { it.climbUuid })
    }

    @Test fun `losing playlist membership ends the mirrored session`() {
        publish(joinable(members = listOf(localNode, "other-npub")))
        assertTrue(queueManager.state.value.isActive)

        publish(joinable(host = "other-npub", members = listOf("other-npub")), revision = 2)

        assertFalse(queueManager.state.value.isActive)
        assertNull(queueManager.state.value.mesh)
    }

    @Test fun `the playlist ending clears the mirrored session`() {
        publish(joinable())
        assertTrue(queueManager.state.value.isActive)

        publish(BoardPlaylistState(), revision = 2)

        assertFalse(queueManager.state.value.isActive)
    }

    @Test fun `a host handover moves the local role without touching the queue`() {
        publish(joinable(members = listOf(localNode, "other-npub")))
        val queueBefore = queueManager.state.value.queue

        publish(joinable(host = "other-npub", members = listOf("other-npub", localNode)), revision = 2)

        assertEquals(SessionRole.PARTICIPANT, queueManager.state.value.role)
        assertEquals(queueBefore, queueManager.state.value.queue)
    }

    // ===== Rest: canonical end instant, counted down locally =====

    private fun restEndingIn(
        seconds: Int,
        generation: Long,
        nextIndex: Int = 0,
        startedAt: Long = startOfTest,
    ) = BoardPlaylistRest(seconds, generation, nextIndex,
        endsAtEpochMs = startedAt + seconds * 1_000L, startedAtEpochMs = startedAt)

    @Test fun `a new rest generation starts this device's own countdown`() {
        val started = mutableListOf<Int>()
        var cleared = 0
        queueManager.onRestRequested = { started += it }
        queueManager.onRestCleared = { cleared++ }

        publish(joinable(rest = restEndingIn(120, 1)))
        // The same generation arriving again — an anti-entropy repair or a
        // reconnect replaying the snapshot — must not restart the countdown.
        publish(joinable(rest = restEndingIn(120, 1)), revision = 2)
        publish(joinable(rest = restEndingIn(90, 2, nextIndex = 1), index = 1), revision = 3)

        assertEquals(listOf(120, 90), started)
        assertEquals(0, cleared)
    }

    @Test fun `the canonical rest ending clears the local countdown`() {
        var cleared = 0
        queueManager.onRestRequested = { }
        queueManager.onRestCleared = { cleared++ }

        publish(joinable(rest = restEndingIn(120, 1)))
        publish(joinable(), revision = 2)

        assertEquals(1, cleared)
    }

    @Test fun `a device arriving part-way through counts down what is left`() {
        val started = mutableListOf<Int>()
        queueManager.onRestRequested = { started += it }

        // The rest was armed at the start of the test and this device only
        // sees it 40 s later. It has to show 80 s: restarting the full two
        // minutes left a late joiner resting while the group was already back
        // on the wall, which is exactly what the duration-only rest did.
        val rest = restEndingIn(120, 1)
        clockNow = startOfTest + 40_000
        publish(joinable(rest = rest))

        assertEquals(listOf(80), started)
        assertEquals(120, queueManager.state.value.mesh!!.activeRest!!.totalSeconds)
        assertEquals(rest.endsAtEpochMs, queueManager.state.value.mesh!!.activeRest!!.endsAtEpochMs)
    }

    @Test fun `a reconnect at a later moment does not restart the same rest`() {
        val started = mutableListOf<Int>()
        queueManager.onRestRequested = { started += it }
        val rest = restEndingIn(120, 7)

        clockNow = startOfTest + 20_000
        publish(joinable(rest = rest))
        clockNow = startOfTest + 50_000
        publish(joinable(rest = rest), revision = 2)
        clockNow = startOfTest + 80_000
        publish(joinable(rest = rest), revision = 3)

        assertEquals("only the first observation starts a countdown", listOf(100), started)
    }

    @Test fun `an already expired rest is not shown as a fresh one and is ended canonically`() {
        val started = mutableListOf<Int>()
        var cleared = 0
        var expired = 0
        queueManager.onRestRequested = { started += it }
        queueManager.onRestCleared = { cleared++ }
        queueManager.onCanonicalRestExpired = { expired++ }

        clockNow = startOfTest + 200_000
        publish(joinable(rest = restEndingIn(120, 1)))

        assertTrue("an expired rest must not start a countdown", started.isEmpty())
        // This device is the playlist host, so it is the one that publishes the
        // end rather than every member racing to send the same command.
        assertEquals(1, expired)
        assertEquals(0, cleared)
    }

    @Test fun `a rest stamped in the future is refused rather than started again`() {
        val started = mutableListOf<Int>()
        var expired = 0
        queueManager.onRestRequested = { started += it }
        queueManager.onCanonicalRestExpired = { expired++ }

        // A controller with a badly wrong clock stamped a self-consistent
        // 60-minute pause a year from now. Every process restart used to see
        // "an hour left" and begin the whole thing again; a rest that has not
        // begun by this device's clock is not one to join.
        val aYear = 365L * 24 * 3_600 * 1_000
        publish(joinable(rest = restEndingIn(3_600, 1, startedAt = startOfTest + aYear)))
        assertTrue(started.isEmpty())

        // A restart re-observes the same state from scratch and still refuses.
        observedFreshly(rest = restEndingIn(3_600, 1, startedAt = startOfTest + aYear))
        assertTrue("a restart must not start it either", started.isEmpty())
        assertEquals("the host publishes the end instead", 2, expired)
    }

    /** Re-observes canonical state the way a fresh process would. */
    private fun observedFreshly(rest: BoardPlaylistRest) {
        snapshots.value = null
        publish(joinable(rest = rest), revision = 2)
    }

    @Test fun `a rest inside ordinary clock skew is still honoured`() {
        val started = mutableListOf<Int>()
        queueManager.onRestRequested = { started += it }

        // Half a minute of skew between two phones is normal and must not
        // throw the pause away.
        publish(joinable(rest = restEndingIn(120, 1, startedAt = startOfTest + 30_000)))

        assertEquals(listOf(120), started)
    }

    @Test fun `a member that is not the playlist host does not publish the expired rest`() {
        var expired = 0
        queueManager.onRestRequested = { }
        queueManager.onCanonicalRestExpired = { expired++ }

        clockNow = startOfTest + 200_000
        publish(joinable(rest = restEndingIn(120, 1),
            host = "other-npub", members = listOf("other-npub", localNode)))

        assertEquals(0, expired)
    }

    // ===== Pending projection =====

    @Test fun `a pending send is visible to every member with an honest reason`() {
        publish(joinable(members = listOf("other-npub", localNode), host = "other-npub",
            pending = BoardPlaylistPendingProjection("a", 40,
                BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE)))

        val pending = queueManager.state.value.mesh!!.pendingProjection!!
        assertEquals(BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE, pending.reason)
        assertEquals("a", pending.climbUuid)
    }

    @Test fun `a frozen cell surfaces as an error instead of silently stale state`() {
        publish(joinable())
        snapshots.value = snapshots.value!!.copy(
            availability = BoardCellAvailability.FROZEN_NEEDS_CONTROLLER).withComputedHash()

        assertEquals("board_cell_controller_unreachable", queueManager.state.value.error)
    }
}
