package com.cruxcoach.board

import com.cruxcoach.domain.board.QuantumBoardModel
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
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
}
