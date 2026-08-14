package com.cruxcoach.android.boardcell

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BoardCellCoordinatorTest {
    private class RecordingTransport : BoardCellTransport {
        val events = mutableListOf<BoardCellEnvelope>()
        val snapshots = mutableListOf<BoardCellSnapshot>()
        val requests = mutableListOf<Pair<BoardCellId, Long>>()
        override suspend fun publishClaim(claim: BoardCellClaim) = Unit
        override suspend fun publishEvent(envelope: BoardCellEnvelope) { events += envelope }
        override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) { snapshots += snapshot }
        override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) {
            requests += cellId to afterSequence
        }
    }

    @Test fun `simultaneous claims settle deterministically before first write`() = runTest {
        val transport = RecordingTransport()
        val coordinator = BoardCellCoordinator("node-b", transport, settleMs = 100)
        val board = PhysicalBoardId("kilter:serial:1")
        coordinator.beginClaim(board, BoardCellId("cell-b"), 1_000)
        coordinator.observeClaim(BoardCellClaim(board, BoardCellId("cell-a"), "node-a", 1_000, 1_000))
        assertNull(coordinator.settle(board, 1_099))
        val settled = coordinator.settle(board, 1_100)!!
        assertEquals(BoardCellId("cell-a"), settled.cellId)
        assertEquals("node-a", settled.controllerId)
        assertEquals(setOf("node-a", "node-b"), settled.members)
        assertEquals(settled.stateHash, transport.snapshots.single().stateHash)
    }

    @Test fun `two adjacent boards never share projection state`() = runTest {
        val coordinator = BoardCellCoordinator("n", settleMs = 0)
        val a = PhysicalBoardId("kilter:serial:a")
        val b = PhysicalBoardId("kilter:serial:b")
        coordinator.beginClaim(a, BoardCellId("a"), 10); coordinator.settle(a, 10)
        coordinator.beginClaim(b, BoardCellId("b"), 20); coordinator.settle(b, 20)
        coordinator.project(a, BoardProjection("climb-a", 40), 11) { true }
        assertEquals("climb-a", coordinator.snapshot(a)?.projection?.climbUuid)
        assertNull(coordinator.snapshot(b)?.projection)
    }

    @Test fun `multi connect projections serialize board write then ordered commits`() = runTest {
        val transport = RecordingTransport()
        val coordinator = BoardCellCoordinator("n", transport, settleMs = 0)
        val board = PhysicalBoardId("kilter:serial:multi")
        coordinator.beginClaim(board, BoardCellId("cell"), 100); coordinator.settle(board, 100)
        val physicalWrites = mutableListOf<String>()
        val one = async { coordinator.project(board, BoardProjection("one", 40), 101) {
            delay(10); physicalWrites += "one"; true
        } }
        val two = async { coordinator.project(board, BoardProjection("two", 45), 102) {
            physicalWrites += "two"; true
        } }
        one.await(); two.await()
        assertEquals(listOf("one", "two"), physicalWrites)
        assertEquals(listOf(1L, 2L), transport.events.map { it.sequence })
        assertEquals("two", coordinator.snapshot(board)?.projection?.climbUuid)
    }

    @Test fun `lease loss freezes and cannot elect through partition`() = runTest {
        val coordinator = BoardCellCoordinator("controller", settleMs = 0, leaseMs = 10)
        val board = PhysicalBoardId("moon:ble:AA")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        coordinator.freezeExpiredControllers(12)
        val result = coordinator.project(board, BoardProjection("unsafe", 25), 12) { true }
        assertTrue(result is ProjectionResult.Refused)
        assertEquals(BoardCellAvailability.FROZEN_NEEDS_CONTROLLER,
            coordinator.snapshot(board)?.availability)
    }

    @Test fun `reachable controller orders handover before successor writes`() = runTest {
        val transport = RecordingTransport()
        val controller = BoardCellCoordinator("old", transport, settleMs = 0, leaseMs = 100)
        val board = PhysicalBoardId("board")
        controller.beginClaim(board, BoardCellId("cell"), 1)
        controller.settle(board, 1)
        controller.joinMember(board, "new")
        val transfer = controller.transferController(board, "new", 2)
        assertNotNull(transfer)
        assertEquals("new", controller.snapshot(board)?.controllerId)
        assertTrue(controller.project(board, BoardProjection("old-write", 40), 3) { true }
            is ProjectionResult.Refused)
    }

    @Test fun `explicit joined participant receives canonical membership snapshot`() = runTest {
        val transport = RecordingTransport()
        val coordinator = BoardCellCoordinator("host", transport, settleMs = 0)
        val board = PhysicalBoardId("board")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        assertNotNull(coordinator.joinMember(board, "participant"))
        assertTrue("participant" in coordinator.snapshot(board)!!.members)
        assertTrue("participant" in transport.snapshots.last().members)
    }

    @Test fun `external relay writes are serialized and become committed or unknown`() = runTest {
        val transport = RecordingTransport()
        val coordinator = BoardCellCoordinator("controller", transport, settleMs = 0)
        val board = PhysicalBoardId("kilter:serial:relay")
        coordinator.beginClaim(board, BoardCellId("cell"), 100); coordinator.settle(board, 100)
        val identified = coordinator.projectExternal(board, 101, boardWrite = { true }) {
            BoardProjection("catalogue-climb", 40)
        }
        val unknown = coordinator.projectExternal(board, 102, boardWrite = { true }) { null }
        assertTrue(identified is ProjectionResult.Committed)
        assertTrue(unknown is ProjectionResult.Committed)
        assertTrue(transport.events[0].event is BoardCellEvent.ProjectCommitted)
        assertTrue(transport.events[1].event is BoardCellEvent.ProjectUnknown)
        assertEquals(listOf(1L, 2L), transport.events.map { it.sequence })
        assertFalse(coordinator.snapshot(board)!!.projectionKnown)
        assertNull(coordinator.snapshot(board)!!.projection)
    }

    @Test fun `failed external relay write never becomes canonical`() = runTest {
        val transport = RecordingTransport()
        val coordinator = BoardCellCoordinator("controller", transport, settleMs = 0)
        val board = PhysicalBoardId("moon:relay")
        coordinator.beginClaim(board, BoardCellId("cell"), 1); coordinator.settle(board, 1)
        assertTrue(coordinator.projectExternal(board, 2, boardWrite = { false }) { null }
            is ProjectionResult.BoardWriteFailed)
        assertTrue(transport.events.isEmpty())
        assertEquals(0, coordinator.snapshot(board)!!.sequence)
    }
}
