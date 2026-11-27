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

    private fun hex(text: String): ByteArray =
        text.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val player = "3f2a9c1e-7b4d-4e8a-9c21-5d6e7f8a9b0c"

    @Test fun `turn off user matches the golden vector`() {
        assertContentEquals(
            hex("01 43 3f2a9c1e7b4d4e8a9c215d6e7f8a9b0c 00 ae3a"),
            QuantumBoardPacketEncoder.turnOffUser(player),
        )
    }

    @Test fun `play is one BOARD_SWIPE that matches the golden XL vector`() {
        // XL/M "Vector 1", 12 holds, green, 31 minutes.
        val frame = QuantumBoardPacketEncoder.play(
            routeId = "d44b3cc3-ab86-4ed5-8509-640b252a17c2",
            userId = player,
            diodes = listOf(2010, 3012, 1046, 1048, 2039, 2045, 3023, 3033, 6110, 6122, 3052, 6131),
            color = 0x00ff00,
        )
        assertContentEquals(
            hex(
                "01 44 d44b3cc3ab864ed58509640b252a17c2 3f2a9c1e7b4d4e8a9c215d6e7f8a9b0c 00ff00 001f 00 18" +
                    "07da 0bc4 0416 0418 07f7 07fd 0bcf 0bd9 17de 17ea 0bec 17f3 5ae3",
            ),
            frame,
        )
    }

    @Test fun `play matches the golden Belay vector`() {
        // Belay "First Easy", 23 holds on strings 4, 5, 14 and 15.
        val frame = QuantumBoardPacketEncoder.play(
            routeId = "3d265119-5911-42c3-9559-3acdf77963cb",
            userId = player,
            diodes = listOf(
                4013, 4031, 4067, 4073, 4091, 4098, 5038, 5046, 5057, 5082, 14013, 14028,
                14039, 14046, 14057, 14064, 14068, 14073, 14081, 15009, 15027, 15093, 14095,
            ),
            color = 0x00ff00,
            minutes = 31,
        )
        assertContentEquals(
            hex(
                "01 44 3d265119591142c395593acdf77963cb 3f2a9c1e7b4d4e8a9c215d6e7f8a9b0c 00ff00 001f 00 2e" +
                    "0fad 0fbf 0fe3 0fe9 0ffb 1002 13ae 13b6 13c1 13da 36bd 36cc 36d7 36de 36e9 36f0" +
                    "36f4 36f9 3701 3aa1 3ab3 3af5 370f 7300",
            ),
            frame,
        )
    }

    @Test fun `play lasts the controller's auto-off time in whole minutes`() {
        assertEquals(31, QuantumBoardPacketEncoder.playMinutes(null))
        assertEquals(31, QuantumBoardPacketEncoder.playMinutes(0))
        assertEquals(1, QuantumBoardPacketEncoder.playMinutes(59))
        assertEquals(30, QuantumBoardPacketEncoder.playMinutes(1800))
        assertEquals(31, QuantumBoardPacketEncoder.playMinutes(1801))
        val frame = QuantumBoardPacketEncoder.play(
            QuantumBoardPacketEncoder.ZERO_UUID, player, listOf(1), 0xff00ff,
            minutes = QuantumBoardPacketEncoder.playMinutes(1800),
        )
        assertContentEquals(byteArrayOf(0, 30), frame.copyOfRange(37, 39))
    }

    @Test fun `play refuses more diodes than one frame holds`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            QuantumBoardPacketEncoder.play(QuantumBoardPacketEncoder.ZERO_UUID, player, (1..93).toList(), 0x00ff00)
        }
        assertEquals(227, QuantumBoardPacketEncoder.play(QuantumBoardPacketEncoder.ZERO_UUID, player, (1..92).toList(), 0x00ff00).size)
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
        assertContentEquals(
            byteArrayOf(0xff.toByte(), 0xff.toByte()),
            frames.first().copyOfRange(37, 39),
            "ACTIVATE_WALL defaults to lit until removed",
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
        assertEquals(null, QuantumBoardBroadcastParser.parse(byteArrayOf(1, 0x47, 1, 0)))
        assertEquals(null, QuantumBoardBroadcastParser.parse(byteArrayOf(2, 0x47, 0, 0)))
        assertEquals(null, QuantumBoardBroadcastParser.parse(byteArrayOf(1, 0x47, 5, 0) + ByteArray(5 * 37)))
    }

    @Test fun `answers longer than their frame still parse`() {
        // Firmware that appends a CRC or padding must not make every answer unreadable.
        val exception = byteArrayOf(1, 0xc4.toByte(), 7)
        assertEquals(
            QuantumBroadcast.Exception(com.cruxcoach.domain.board.QuantumCommand.BOARD_SWIPE, 7),
            QuantumBoardBroadcastParser.parse(exception + byteArrayOf(0x12, 0x34)),
        )
        val body = byteArrayOf(1, 0x47, 1, 0) +
            QuantumBoardPacketEncoder.uuidBytes("11111111-2222-3333-4444-555555555555") +
            QuantumBoardPacketEncoder.uuidBytes(player) +
            byteArrayOf(0x07, 0x08, 0, 0xff.toByte(), 0)
        val list = QuantumBoardBroadcastParser.parse(body + byteArrayOf(0x55, 0x66)) as QuantumBroadcast.RouteList
        assertEquals(player, list.players.single().userId)
        assertEquals(0x0708, list.players.single().remainingSeconds)
        assertEquals(0x00ff00, list.players.single().color)
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
        assertEquals(
            null,
            QuantumBoardBroadcastParser.parse(byteArrayOf(1, 0x47, 0, 1)),
        )
        // An echo of our own route list request is no empty board.
        assertEquals(null, QuantumBoardBroadcastParser.parse(QuantumBoardPacketEncoder.requestRouteList()))
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
