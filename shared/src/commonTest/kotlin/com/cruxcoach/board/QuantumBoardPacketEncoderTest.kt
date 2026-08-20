package com.cruxcoach.board

import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import com.cruxcoach.domain.board.QuantumBoardBroadcastParser
import com.cruxcoach.domain.board.QuantumBroadcast
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuantumBoardPacketEncoderTest {
    @Test fun `crc matches MODBUS check vector`() {
        assertEquals(0x4b37, QuantumBoardPacketEncoder.crc16Modbus("123456789".encodeToByteArray()))
    }

    @Test fun `turn off all matches clean room golden vector`() {
        assertContentEquals(
            byteArrayOf(0x01, 0x45, 0x00, 0x01, 0x00, 0x00, 0xc5.toByte(), 0x9d.toByte()),
            QuantumBoardPacketEncoder.turnOffAll(),
        )
    }

    @Test fun `turn off user carries exact identity and valid crc`() {
        val user = "00112233-4455-6677-8899-aabbccddeeff"
        val frame = QuantumBoardPacketEncoder.turnOffUser(user)
        assertEquals(21, frame.size)
        assertEquals(0x01, frame[0].toInt() and 0xff)
        assertEquals(0x43, frame[1].toInt() and 0xff)
        assertContentEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
                0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()),
            frame.copyOfRange(2, 18),
        )
        assertEquals(0, frame[18].toInt() and 0xff)
        val expectedCrc = ((frame[19].toInt() and 0xff) shl 8) or
            (frame[20].toInt() and 0xff)
        assertEquals(expectedCrc, QuantumBoardPacketEncoder.crc16Modbus(frame.copyOf(19)))
    }

    @Test fun `repeated route projection always releases user before activation`() {
        val user = "00112233-4455-6677-8899-aabbccddeeff"
        val first = QuantumBoardPacketEncoder.replaceUserRoute(
            "11111111-2222-3333-4444-555555555555", user, listOf(1, 2, 3),
        )
        val repeated = QuantumBoardPacketEncoder.replaceUserRoute(
            "11111111-2222-3333-4444-555555555555", user, listOf(4, 5, 6),
        )
        val switched = QuantumBoardPacketEncoder.replaceUserRoute(
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", user, listOf(7, 8, 9),
        )
        listOf(first, repeated, switched).forEach { transition ->
            assertEquals(0x43, transition.first()[1].toInt() and 0xff)
            assertEquals(0x41, transition[1][1].toInt() and 0xff)
            assertContentEquals(
                QuantumBoardPacketEncoder.uuidBytes(user),
                transition.first().copyOfRange(2, 18),
            )
        }
    }

    @Test fun `activate chunks at 92 diodes and every frame has valid crc`() {
        val frames = QuantumBoardPacketEncoder.activate(
            "00112233-4455-6677-8899-aabbccddeeff",
            "ffeeddcc-bbaa-9988-7766-554433221100",
            (1..193).toList(),
        )
        assertEquals(3, frames.size)
        assertEquals(listOf(227, 227, 61), frames.map(ByteArray::size))
        frames.forEach { frame ->
            val expected = ((frame[frame.lastIndex - 1].toInt() and 0xff) shl 8) or
                (frame[frame.lastIndex].toInt() and 0xff)
            assertEquals(expected, QuantumBoardPacketEncoder.crc16Modbus(frame.copyOf(frame.size - 2)))
        }
        assertContentEquals(
            byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte()),
            frames.first().copyOfRange(2, 18),
        )
    }

    @Test fun `request list uses row byte and big endian crc`() {
        assertContentEquals(
            byteArrayOf(0x01, 0x47, 0x00, 0xf0.toByte(), 0x13),
            QuantumBoardPacketEncoder.requestRouteList(),
        )
    }

    @Test fun `swipe uses current opcode and rejects invalid diode addresses`() {
        val swipe = QuantumBoardPacketEncoder.activate(
            QuantumBoardPacketEncoder.ZERO_UUID,
            QuantumBoardPacketEncoder.ZERO_UUID,
            listOf(1001),
            swipe = true,
        ).single()
        assertEquals(0x44, swipe[1].toInt() and 0xff)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            QuantumBoardPacketEncoder.activate(
                QuantumBoardPacketEncoder.ZERO_UUID,
                QuantumBoardPacketEncoder.ZERO_UUID,
                listOf(70_000),
            )
        }
    }

    @Test fun `model ids are stable and disjoint`() {
        assertEquals(5, QuantumBoardModel.entries.map { it.layoutId }.toSet().size)
        assertTrue(QuantumBoardModel.entries.all { it.layoutId in 9101..9105 })
        assertTrue(QuantumBoardModel.entries.all { it.productSizeId in 9201..9205 })
    }

    @Test fun `route list parser preserves four users routes colors and time`() {
        val players = listOf(
            Triple("00112233-4455-6677-8899-aabbccddeeff", "11111111-2222-3333-4444-555555555555", 0x00bcd4),
            Triple("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "12345678-1234-5678-9abc-def012345678", 0xff8c00),
            Triple("fedcba98-7654-3210-fedc-ba9876543210", "87654321-4321-8765-cba9-876543210fed", 0xb56cff),
            Triple("01234567-89ab-cdef-0123-456789abcdef", "99999999-8888-7777-6666-555555555555", 0x4cd964),
        )
        val body = mutableListOf<Byte>(1, 0x47, players.size.toByte(), 0)
        players.forEachIndexed { index, (route, user, color) ->
            body += QuantumBoardPacketEncoder.uuidBytes(route).toList()
            body += QuantumBoardPacketEncoder.uuidBytes(user).toList()
            body += byteArrayOf(0, (30 + index).toByte()).toList()
            body += byteArrayOf(
                (color ushr 16).toByte(),
                (color ushr 8).toByte(),
                color.toByte(),
            ).toList()
        }
        // Byte-identical shape emitted by BoardSimulator and consumed by
        // eWalls 2.0.14 parseBroadcast: broadcasts do not carry command CRC.
        val frame = body.toByteArray()
        assertEquals(frame.size, QuantumBoardBroadcastParser.expectedFrameSize(frame))
        val parsed = QuantumBoardBroadcastParser.parse(frame) as QuantumBroadcast.RouteList
        assertEquals(4, parsed.players.size)
        assertEquals(players.map { it.first }, parsed.players.map { it.routeId })
        assertEquals(players.map { it.second }, parsed.players.map { it.userId })
        assertEquals(players.map { it.third }, parsed.players.map { it.color })
        assertEquals(listOf(30, 31, 32, 33), parsed.players.map { it.remainingSeconds })
    }

    @Test fun `exception broadcasts are typed and malformed shapes fail closed`() {
        val exception = byteArrayOf(1, 0xc1.toByte(), 7)
        assertEquals(
            QuantumBroadcast.Exception(com.cruxcoach.domain.board.QuantumCommand.ACTIVATE_WALL, 7),
            QuantumBoardBroadcastParser.parse(exception),
        )
        assertEquals(null, QuantumBoardBroadcastParser.parse(exception + byteArrayOf(0)))
        assertEquals(null, QuantumBoardBroadcastParser.parse(byteArrayOf(1, 0x47, 1, 0)))
    }

    @Test fun `empty route snapshot matches exact simulator vector`() {
        val frame = byteArrayOf(1, 0x47, 0, 0)
        assertEquals(4, QuantumBoardBroadcastParser.expectedFrameSize(frame))
        assertEquals(
            QuantumBroadcast.RouteList(
                com.cruxcoach.domain.board.QuantumCommand.REQUEST_USER_ROUTE_LIST,
                emptyList(),
            ),
            QuantumBoardBroadcastParser.parse(frame),
        )
    }

    @Test fun `only Quantum opts into four independent layers`() {
        assertEquals(4, BoardBrand.QUANTUM.maxSimultaneousClimbs)
        assertTrue(BoardBrand.QUANTUM.supportsIndependentClimbLayers)
        BoardBrand.entries.filterNot { it == BoardBrand.QUANTUM }.forEach {
            assertEquals(1, it.maxSimultaneousClimbs)
            assertTrue(!it.supportsIndependentClimbLayers)
        }
    }

}
