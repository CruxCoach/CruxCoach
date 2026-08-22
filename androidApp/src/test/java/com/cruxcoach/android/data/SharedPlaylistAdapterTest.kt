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
import com.cruxcoach.android.boardcell.BoardPlaylistOp
import com.cruxcoach.android.boardcell.BoardPlaylistPendingProjection
import com.cruxcoach.android.boardcell.BoardPlaylistPolicy
import com.cruxcoach.android.boardcell.BoardPlaylistProjectionPendingReason
import com.cruxcoach.android.boardcell.BoardPlaylistRest
import com.cruxcoach.android.boardcell.BoardPlaylistState
import com.cruxcoach.android.boardcell.BoardProjection
import com.cruxcoach.android.boardcell.PhysicalBoardId
import com.cruxcoach.data.repository.BoardRepository
import io.mockk.every
import io.mockk.mockk
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
 * [SessionQueueManager] as the *adapter* for the BoardCell's shared playlist:
 * it mirrors mesh state into the UI/GATT projection and never becomes a second
 * source of truth for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedPlaylistAdapterTest {

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
        every { boardCellManager.snapshot() } answers { snapshots.value }
        every { boardCellManager.localNodeId() } returns localNode
        every { boardCellManager.isPlaylistSynchronized() } returns true
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

    private fun publish(
        playlist: BoardPlaylistState,
        revision: Long = 1,
        members: Set<String> = setOf("controller-npub", localNode, "other-npub"),
        controller: String = "controller-npub",
    ) {
        snapshots.value = BoardCellSnapshot(
            cellId = cell, physicalBoardId = board, epoch = 1, sequence = revision + 1,
            controllerId = controller, controllerTerm = 1, lineageId = "lineage",
            members = members, playlist = playlist, playlistRevision = revision,
        ).withComputedHash()
    }

    /** The shared playlist as the reducer would have produced it. */
    private fun shared(
        entries: List<Triple<String, String, Int>> = listOf(
            Triple("e1", "a", 120), Triple("e2", "b", 0)),
        current: String? = entries.firstOrNull()?.first,
        rest: BoardPlaylistRest? = null,
        pending: BoardPlaylistPendingProjection? = null,
    ): BoardPlaylistState {
        val base = BoardPlaylistPolicy.apply(BoardPlaylistState(sessionId = 7),
            entries.map { (id, climb, restAfter) ->
                BoardPlaylistOp.Add(id, climb, 40, restAfter)
            })
        return BoardPlaylistPolicy.normalize(
            base.copy(selectedEntryId = current, activeRest = rest, pendingProjection = pending))
    }

    // ===== LOCAL_ONLY stays local =====

    @Test fun `a local-only playlist never publishes into the BoardCell`() {
        queueManager.loadPlaylist("Host", listOf(QueueItem("local-a", 40), QueueItem("local-b", 40)))
        queueManager.addClimb("local-c", 40)
        queueManager.nextClimb()
        queueManager.setCurrentClimb(0)
        queueManager.moveClimb(0, 1)
        queueManager.removeClimb(0)

        assertNull(queueManager.state.value.mesh)
        assertEquals(SessionVisibility.LOCAL_ONLY, queueManager.state.value.visibility)
    }

    @Test fun `a shared playlist on a board this device is not in leaves a local one running`() {
        queueManager.loadPlaylist("Host", listOf(QueueItem("local-a", 40)))
        val before = queueManager.state.value

        publish(shared(), members = setOf("controller-npub", "other-npub"))

        val after = queueManager.state.value
        assertNull(after.mesh)
        assertEquals(before.queue, after.queue)
        assertEquals(before.currentIndex, after.currentIndex)
        assertEquals(SessionRole.HOST, after.role)
    }

    // ===== Board membership is playlist participation =====

    @Test fun `a board member mirrors queue, index and rest plan from canonical state`() {
        publish(shared(current = "e2"))

        val state = queueManager.state.value
        assertEquals(listOf("a", "b"), state.queue.map { it.climbUuid })
        assertEquals(listOf(120, 0), state.queue.map { it.restAfterSeconds })
        assertEquals(1, state.currentIndex)
        assertEquals(7, state.sessionId)
        assertEquals(SessionVisibility.JOINABLE, state.visibility)
        assertEquals(3, state.participantCount)
        val mesh = state.mesh!!
        assertEquals(listOf("controller-npub", localNode, "other-npub"), mesh.members)
        assertTrue(mesh.synchronized)
        assertFalse(mesh.localIsController)
    }

    /**
     * There is no join. A device that is in the board group and has never
     * touched the playlist still follows it the moment there is one, which is
     * what "membership automatically adopts the playlist" has to mean.
     */
    @Test fun `a member adopts the shared playlist without ever joining it`() {
        assertFalse(queueManager.state.value.isActive)

        publish(shared())

        assertTrue(queueManager.state.value.isActive)
        assertNotNull(queueManager.state.value.mesh)
    }

    @Test fun `an empty shared playlist is the board's resting state, not a session`() {
        publish(BoardPlaylistState(sessionId = 7))

        assertFalse(queueManager.state.value.isActive)
        assertNull(queueManager.state.value.mesh)
    }

    @Test fun `the first add in an empty BoardCell routes to its canonical playlist`() {
        publish(BoardPlaylistState(sessionId = 7))
        every { boardCellManager.isCellMember() } returns true
        var routed: List<QueueItem>? = null
        queueManager.addToSharedPlaylist = { items -> routed = items; true }

        queueManager.addClimb("first-shared", 45)

        assertEquals(listOf(QueueItem("first-shared", 45)), routed)
        assertTrue("no private queue may be created beside the BoardCell",
            queueManager.state.value.queue.isEmpty())
    }

    @Test fun `the technical controller is projected without any product role`() {
        publish(shared(), controller = localNode)

        val state = queueManager.state.value
        // Everybody is a participant. Nothing in the projection makes the
        // serializer look like a host.
        assertEquals(SessionRole.PARTICIPANT, state.role)
        assertTrue(state.mesh!!.localIsController)
    }

    @Test fun `a canonical update replaces the projection rather than merging into it`() {
        publish(shared())
        publish(shared(entries = listOf(Triple("x1", "x", 0))), revision = 2)

        assertEquals(listOf("x"), queueManager.state.value.queue.map { it.climbUuid })
    }

    @Test fun `leaving the board group ends the mirrored session`() {
        publish(shared())
        assertTrue(queueManager.state.value.isActive)

        publish(shared(), revision = 2, members = setOf("controller-npub", "other-npub"))

        assertFalse(queueManager.state.value.isActive)
        assertNull(queueManager.state.value.mesh)
    }

    @Test fun `clearing the shared playlist clears the mirrored session everywhere`() {
        publish(shared())
        assertTrue(queueManager.state.value.isActive)

        publish(BoardPlaylistState(sessionId = 7, clearGeneration = 1), revision = 2)

        assertFalse(queueManager.state.value.isActive)
    }

    /**
     * Closing the player is local display state and nothing else: no canonical
     * change, no lost editing rights, and it re-arms the moment the group's
     * list changes shape again.
     */
    @Test fun `closing the player stops the mirror without changing anything canonical`() {
        publish(shared())
        assertTrue(queueManager.state.value.isActive)

        queueManager.stopFollowingSharedPlaylist()
        assertFalse(queueManager.state.value.isActive)

        // Further canonical updates do not drag the player back on screen.
        publish(shared(entries = listOf(Triple("e1", "a", 0), Triple("e3", "c", 0))), revision = 2)
        assertFalse(queueManager.state.value.isActive)

        // A clear re-arms it, so the next thing anybody adds is visible again.
        publish(BoardPlaylistState(sessionId = 7, clearGeneration = 1), revision = 3)
        publish(shared(entries = listOf(Triple("e9", "z", 0))), revision = 4)
        assertTrue(queueManager.state.value.isActive)
    }

    @Test fun `acting on the shared playlist brings a closed player back`() {
        publish(shared())
        queueManager.stopFollowingSharedPlaylist()
        assertFalse(queueManager.state.value.isActive)

        // Editing a list you cannot see is not a state worth being in.
        queueManager.resumeFollowingSharedPlaylist()
        // Re-adoption is immediate; opening the focused player must not wait
        // for an unrelated future snapshot to become usable.
        assertTrue(queueManager.state.value.isActive)
        assertEquals(listOf("a", "b"), queueManager.state.value.queue.map { it.climbUuid })
    }

    @Test fun `a controller handover does not touch the queue`() {
        publish(shared())
        val queueBefore = queueManager.state.value.queue

        publish(shared(), revision = 2, controller = localNode)

        assertEquals(queueBefore, queueManager.state.value.queue)
        assertEquals(SessionRole.PARTICIPANT, queueManager.state.value.role)
    }

    /**
     * Two facts, shown as two facts: what the group has selected, and what the
     * board last confirmed. Collapsing them would hide the moment somebody
     * steps through the list while a climb is still lit.
     */
    @Test fun `the selected entry and the confirmed board climb are tracked apart`() {
        publish(shared())
        assertFalse("nothing has been sent yet", queueManager.state.value.mesh!!.selectionOnBoard)

        snapshots.value = snapshots.value!!.copy(
            projection = BoardProjection("a", 40), projectionKnown = true).withComputedHash()
        assertTrue(queueManager.state.value.mesh!!.selectionOnBoard)

        // The group moves on; the wall does not follow by itself.
        publish(shared(current = "e2"), revision = 2)
        snapshots.value = snapshots.value!!.copy(
            projection = BoardProjection("a", 40), projectionKnown = true).withComputedHash()
        assertFalse(queueManager.state.value.mesh!!.selectionOnBoard)
        assertTrue(queueManager.state.value.awaitingExplicitSend)
    }

    @Test fun `canonical updates notify a GATT gateway after initial adoption and clear`() {
        publish(shared())
        var queueChanges = 0
        var currentChanges = 0
        queueManager.onQueueChanged = { queueChanges++ }
        queueManager.onCurrentClimbChanged = { currentChanges++ }

        publish(shared(entries = listOf(
            Triple("e1", "a", 0), Triple("e2", "b", 0), Triple("e3", "c", 0)),
            current = "e2"), revision = 2)

        assertEquals(1, queueChanges)
        assertEquals(1, currentChanges)

        publish(BoardPlaylistState(sessionId = 7, clearGeneration = 1), revision = 3)
        assertEquals("the leaf must be told the canonical list is empty", 2, queueChanges)
        assertEquals(2, currentChanges)

        publish(shared(entries = listOf(Triple("e9", "z", 0))), revision = 4)
        assertEquals("clear must not erase the gateway callbacks", 3, queueChanges)
        assertEquals(3, currentChanges)
    }

    @Test fun `a selection nobody has sent is not reported as an external override`() {
        publish(shared())

        // The resting state of a shared playlist is "selected but not sent",
        // which is not another app having taken the board.
        assertFalse(queueManager.state.value.externalBoardOverride)
    }

    @Test fun `a partition is reported rather than passed off as being in sync`() {
        every { boardCellManager.isPlaylistSynchronized() } returns false

        publish(shared())

        assertFalse(queueManager.state.value.mesh!!.synchronized)
    }

    // ===== Rest: canonical end instant, counted down locally =====

    private fun restEndingIn(
        seconds: Int,
        generation: Long,
        nextEntryId: String = "e1",
        startedAt: Long = startOfTest,
    ) = BoardPlaylistRest(seconds, generation, nextEntryId,
        endsAtEpochMs = startedAt + seconds * 1_000L, startedAtEpochMs = startedAt)

    @Test fun `a new rest generation starts this device's own countdown`() {
        val started = mutableListOf<Int>()
        var cleared = 0
        queueManager.onRestRequested = { started += it }
        queueManager.onRestCleared = { cleared++ }

        publish(shared(rest = restEndingIn(120, 1)))
        // The same generation arriving again — an anti-entropy repair or a
        // reconnect replaying the snapshot — must not restart the countdown.
        publish(shared(rest = restEndingIn(120, 1)), revision = 2)
        publish(shared(current = "e2", rest = restEndingIn(90, 2, nextEntryId = "e2")), revision = 3)

        assertEquals(listOf(120, 90), started)
        assertEquals(0, cleared)
    }

    @Test fun `the canonical rest ending clears the local countdown`() {
        var cleared = 0
        queueManager.onRestRequested = { }
        queueManager.onRestCleared = { cleared++ }

        publish(shared(rest = restEndingIn(120, 1)))
        publish(shared(), revision = 2)

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
        publish(shared(rest = rest))

        assertEquals(listOf(80), started)
        assertEquals(120, queueManager.state.value.mesh!!.activeRest!!.totalSeconds)
        assertEquals(rest.endsAtEpochMs, queueManager.state.value.mesh!!.activeRest!!.endsAtEpochMs)
    }

    @Test fun `a reconnect at a later moment does not restart the same rest`() {
        val started = mutableListOf<Int>()
        queueManager.onRestRequested = { started += it }
        val rest = restEndingIn(120, 7)

        clockNow = startOfTest + 20_000
        publish(shared(rest = rest))
        clockNow = startOfTest + 50_000
        publish(shared(rest = rest), revision = 2)
        clockNow = startOfTest + 80_000
        publish(shared(rest = rest), revision = 3)

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
        publish(shared(rest = restEndingIn(120, 1)), controller = localNode)

        assertTrue("an expired rest must not start a countdown", started.isEmpty())
        // Exactly one device publishes the end. The controller is picked
        // because the group is guaranteed to have precisely one of it, not
        // because it has any say over the playlist.
        assertEquals(1, expired)
        assertEquals(0, cleared)
    }

    @Test fun `an expired rest end callback may re-enter playlist adoption without recursion`() {
        var expired = 0
        queueManager.onCanonicalRestExpired = {
            expired++
            // Mirrors SessionGattBridge.endCanonicalRest(): submitting the
            // command resumes the shared playlist before its coroutine sends.
            queueManager.resumeFollowingSharedPlaylist()
        }

        clockNow = startOfTest + 200_000
        publish(shared(rest = restEndingIn(120, 91)), controller = localNode)

        assertEquals("the same generation is ended exactly once", 1, expired)
    }

    @Test fun `replayed snapshots do not request the same expired rest end again`() {
        var expired = 0
        queueManager.onCanonicalRestExpired = { expired++ }
        val rest = restEndingIn(120, 92)

        clockNow = startOfTest + 200_000
        publish(shared(rest = rest), revision = 1, controller = localNode)
        publish(shared(rest = rest), revision = 2, controller = localNode)
        publish(shared(rest = rest), revision = 3, controller = localNode)

        assertEquals(1, expired)
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
        publish(shared(rest = restEndingIn(3_600, 1, startedAt = startOfTest + aYear)),
            controller = localNode)
        assertTrue(started.isEmpty())

        // A restart re-observes the same state from scratch and still refuses.
        snapshots.value = null
        publish(shared(rest = restEndingIn(3_600, 1, startedAt = startOfTest + aYear)),
            revision = 2, controller = localNode)
        assertTrue("a restart must not start it either", started.isEmpty())
        assertEquals("one device publishes the end instead", 2, expired)
    }

    @Test fun `a rest inside ordinary clock skew is still honoured`() {
        val started = mutableListOf<Int>()
        queueManager.onRestRequested = { started += it }

        // Half a minute of skew between two phones is normal and must not
        // throw the pause away.
        publish(shared(rest = restEndingIn(120, 1, startedAt = startOfTest + 30_000)))

        assertEquals(listOf(120), started)
    }

    @Test fun `a member that is not the controller does not publish the expired rest`() {
        var expired = 0
        queueManager.onRestRequested = { }
        queueManager.onCanonicalRestExpired = { expired++ }

        clockNow = startOfTest + 200_000
        publish(shared(rest = restEndingIn(120, 1)))

        assertEquals(0, expired)
    }

    // ===== Pending projection =====

    @Test fun `a pending send is visible to every member with an honest reason`() {
        publish(shared(pending = BoardPlaylistPendingProjection("e1", "a", 40,
            BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE)))

        val pending = queueManager.state.value.mesh!!.pendingProjection!!
        assertEquals(BoardPlaylistProjectionPendingReason.CLIMB_UNAVAILABLE, pending.reason)
        assertEquals("e1", pending.entryId)
        assertEquals("a", pending.climbUuid)
    }

    @Test fun `a frozen cell surfaces as an error instead of silently stale state`() {
        publish(shared())
        snapshots.value = snapshots.value!!.copy(
            availability = BoardCellAvailability.FROZEN_NEEDS_CONTROLLER).withComputedHash()

        assertEquals("board_cell_controller_unreachable", queueManager.state.value.error)
    }
}
