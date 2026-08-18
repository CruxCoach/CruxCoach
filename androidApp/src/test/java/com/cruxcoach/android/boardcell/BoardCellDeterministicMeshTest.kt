package com.cruxcoach.android.boardcell

import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Two real coordinators connected by a controllable, loss/reorder/duplicate transport. */
class BoardCellDeterministicMeshTest {
    private sealed interface Packet {
        val from: String
        val to: String
        data class Snapshot(override val from: String, override val to: String, val value: BoardCellSnapshot) : Packet
        data class Event(override val from: String, override val to: String, val value: BoardCellEnvelope) : Packet
        data class Ready(override val from: String, override val to: String, val value: HandoverReady) : Packet
    }

    private class Network {
        val coordinators = mutableMapOf<String, BoardCellCoordinator>()
        val packets = mutableListOf<Packet>()
        fun endpoint(id: String) = object : BoardCellTransport {
            override suspend fun publishClaim(claim: BoardCellClaim) = Unit
            override suspend fun publishEvent(envelope: BoardCellEnvelope) {
                coordinators.keys.filter { it != id }.forEach { packets += Packet.Event(id, it, envelope) }
            }
            override suspend fun publishSnapshot(snapshot: BoardCellSnapshot) {
                snapshot.members.filter { it != id }.forEach { packets += Packet.Snapshot(id, it, snapshot) }
            }
            override suspend fun requestSnapshot(cellId: BoardCellId, afterSequence: Long) = Unit
            override suspend fun sendHandoverReady(target: String, ready: HandoverReady) {
                packets += Packet.Ready(id, target, ready)
            }
        }
        suspend fun deliver(packet: Packet, now: Long): BoardCellApplyResult? {
            val target = coordinators.getValue(packet.to)
            return when (packet) {
                is Packet.Snapshot -> target.acceptSnapshot(packet.from, packet.value, now)
                is Packet.Event -> target.acceptEvent(packet.from, packet.value, now)
                is Packet.Ready -> {
                    if (target.acceptTargetReady(packet.from, packet.value, now))
                        target.commitHandover(packet.value.physicalBoardId, packet.value.transferId, now)
                    null
                }
            }
        }
    }

    @Test fun `random duplicate loss and reorder always freezes gap then snapshot converges`() = runTest {
        repeat(32) { seed ->
            BoardCellScopeRegistry.resetForTest()
            val network = Network(); val board = PhysicalBoardId("board-$seed")
            val source = BoardCellCoordinator("source", network.endpoint("source"), settleMs = 0)
            val replica = BoardCellCoordinator("replica", network.endpoint("replica"), settleMs = 0)
            network.coordinators["source"] = source; network.coordinators["replica"] = replica
            source.beginClaim(board, BoardCellId.forPhysical(board), 1); source.settle(board, 1)
            source.joinMember(board, "replica")
            val joinSnapshot = network.packets.filterIsInstance<Packet.Snapshot>().last()
            network.deliver(joinSnapshot, 2)
            network.packets.clear()

            source.project(board, BoardProjection("one", 40), 3, "one") { true }
            source.project(board, BoardProjection("two", 45), 4, "two") { true }
            val events = network.packets.filterIsInstance<Packet.Event>()
            val schedule = (events + events.random(Random(seed))).shuffled(Random(seed))
            val results = schedule.drop(Random(seed).nextInt(schedule.size)).map { network.deliver(it, 5) }
            if (results.any { it is BoardCellApplyResult.NeedSnapshot })
                assertEquals(BoardCellAvailability.FROZEN_NEEDS_SNAPSHOT, replica.snapshot(board)!!.availability)

            val recovery = Packet.Snapshot("source", "replica", source.snapshot(board)!!)
            assertTrue(network.deliver(recovery, 6) is BoardCellApplyResult.Applied)
            assertEquals(source.snapshot(board)!!.stateHash, replica.snapshot(board)!!.stateHash)
            assertEquals("two", replica.snapshot(board)!!.projection!!.climbUuid)
        }
    }
}
