package com.cruxcoach.android.ble

import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardLedMode
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumBoardPacketEncoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LED lab's switches start on the release build's behaviour, and its report counts what the frames hold. */
class LedLabTest {
    @After fun reset() = LedLab.reset()

    private fun chunks(bytes: ByteArray) = bytes.toList().chunked(BoardPacketEncoder.BLE_MTU).map { it.toByteArray() }

    @Test fun `every switch starts where the release build is`() {
        val switches = LedLab.switches.value
        assertTrue(switches.release)
        assertEquals(100L, switches.moonGapMs)
        assertFalse(switches.api2SeveralPackets)
        assertFalse(switches.quantumSeveralMessages)
        assertFalse(LedLab.quantumSeveralMessagesNow())
        assertEquals(listOf(100L, 20L, 5L), LedLab.MOON_GAPS_MS)
        assertNull(LedLab.report.value)
        LedLab.change { it.copy(moonGapMs = 20L) }
        assertFalse(LedLab.switches.value.release)
        LedLab.reset()
        assertTrue(LedLab.switches.value.release)
    }

    @Test fun `a MoonBoard frame counts one LED for every entry`() {
        val frames = (1..40).joinToString("") { "p${it}r43" }
        fun counted(mode: MoonBoardLedMode) = LedLab.count(BoardBrand.MOONBOARD,
            listOf(chunks(MoonBoardFrameEncoder.encode(frames, MoonBoardVariant.MOONBOARD_2016, mode))))
        assertEquals(LedLab.Counted(leds = 40, packets = 1, ledPackets = 1), counted(MoonBoardLedMode.BELOW))
        // Both LEDs: the 11 holds of the bottom row have no LED above, the other 29 have two entries.
        assertEquals(LedLab.Counted(leds = 11 + 2 * 29, packets = 1, ledPackets = 1), counted(MoonBoardLedMode.BOTH))
        assertEquals(LedLab.Counted(leds = 0, packets = 1, ledPackets = 0),
            LedLab.count(BoardBrand.MOONBOARD, listOf(listOf("l##".encodeToByteArray()))))
    }

    @Test fun `Aurora packets count their LEDs by the API level of their type`() {
        val api3 = BoardPacketEncoder(3).encodeClimb((1..200).map { it to 0xff })
        assertEquals(LedLab.Counted(leds = 200, packets = 3, ledPackets = 3), LedLab.count(BoardBrand.KILTER, listOf(api3)))
        val api2 = BoardPacketEncoder(2, 2).encodeClimb((1..130).map { it to 0x1c })
        assertEquals(LedLab.Counted(leds = 130, packets = 2, ledPackets = 2), LedLab.count(BoardBrand.KILTER, listOf(api2)))
        // A cleared board is one packet without LEDs.
        assertEquals(LedLab.Counted(leds = 0, packets = 1, ledPackets = 0),
            LedLab.count(BoardBrand.TENSION, listOf(BoardPacketEncoder(3).encodeClear())))
    }

    @Test fun `a Quantum send counts its messages and the diodes of the activation`() {
        val messages = QuantumBoardPacketEncoder.replaceUserRoute("11111111-2222-4333-8444-555555555555",
            "00000000-0000-4000-8000-000000000001", (1..92).toList(), 0x00ff00)
        assertEquals(LedLab.Counted(leds = 92, packets = 2, ledPackets = 1),
            LedLab.count(BoardBrand.QUANTUM, messages.map { listOf(it) }))
    }

    @Test fun `more than 92 diodes of a Quantum color are several messages with the same head, as eWalls splits them`() {
        val route = "11111111-2222-4333-8444-555555555555"
        val user = "00000000-0000-4000-8000-000000000001"
        val messages = QuantumBoardPacketEncoder.replaceUserRoute(route, user, (1..150).toList(), 0x00ff00)
        assertEquals("switch the user off, then two activations", 3, messages.size)
        val (first, second) = messages.drop(1)
        // 01, command, route id, user id, color, duration, animation: 40 bytes that say whose LEDs these are.
        assertEquals(first.take(40), second.take(40))
        assertEquals(0x41, first[1].toInt())
        // Then the length in bytes and the diodes, two bytes each, high byte first, in ascending slices of 92.
        fun diodes(message: ByteArray): List<Int> {
            val length = message[40].toInt() and 0xff
            assertEquals("length, diodes and CRC fill the message", 41 + length + 2, message.size)
            return (0 until length / 2).map { i ->
                ((message[41 + 2 * i].toInt() and 0xff) shl 8) or (message[42 + 2 * i].toInt() and 0xff)
            }
        }
        assertEquals((1..92).toList(), diodes(first))
        assertEquals((93..150).toList(), diodes(second))
        for (message in listOf(first, second)) {
            val crc = QuantumBoardPacketEncoder.crc16Modbus(message.copyOfRange(0, message.size - 2))
            assertEquals(crc, ((message[message.size - 2].toInt() and 0xff) shl 8) or (message.last().toInt() and 0xff))
        }
        assertEquals(LedLab.Counted(leds = 150, packets = 3, ledPackets = 2),
            LedLab.count(BoardBrand.QUANTUM, messages.map { listOf(it) }))
        // Up to 92 diodes it is one activation, byte for byte what the release head e89875335 built.
        val one = QuantumBoardPacketEncoder.replaceUserRoute(route, user, (1..92).toList(), 0x00ff00)
        assertEquals(2, one.size)
        assertEquals("b335de41be421c3f64d025325d5785ca4800316f5debb42901f8496aa2d96ec9",
            sha256(one.flatMap { it.toList() }.toByteArray()))
    }

    @Test fun `the connection lets several Quantum messages out only for a PaintBox send with the switch on`() {
        fun holds(count: Int) = (1..count).map { BoardHold(it, HoldRole.HAND) }
        assertTrue(quantumActivationAllowed(holds(92), labSeveralMessages = false))
        assertFalse(quantumActivationAllowed(holds(93), labSeveralMessages = false))
        assertTrue(quantumActivationAllowed(holds(93), labSeveralMessages = true))

        // The switch alone changes nothing for a climb, a list or a wall game: no PaintBox send is under way.
        LedLab.change { it.copy(quantumSeveralMessages = true) }
        assertFalse(LedLab.quantumSeveralMessagesNow())
        val recording = LedLab.begin()
        assertTrue(LedLab.quantumSeveralMessagesNow())
        LedLab.end(recording, BoardBrand.QUANTUM, boardAnswer = "ROUTE_IN_USE")
        assertFalse(LedLab.quantumSeveralMessagesNow())
        assertEquals("ROUTE_IN_USE", LedLab.report.value!!.boardAnswer)
        // And without the switch not even a PaintBox send may.
        LedLab.change { it.copy(quantumSeveralMessages = false) }
        val plain = LedLab.begin()
        assertFalse(LedLab.quantumSeveralMessagesNow())
        LedLab.end(plain, BoardBrand.QUANTUM)
    }

    @Test fun `a recording reports writes, bytes, time and the first failed write`() {
        var now = 1_000L
        LedLab.clock = { now }
        val frame = BoardPacketEncoder(3).encodeClimb((1..30).map { it to 0xff })
        assertTrue("a frame of several writes", frame.size >= 4)
        val recording = LedLab.begin()
        LedLab.frame(frame)
        repeat(2) { now += 100; LedLab.wrote(null) }
        now += 50
        LedLab.wrote(LedLab.FailureKind.STATUS, 133)
        LedLab.end(recording, BoardBrand.KILTER)
        val report = LedLab.report.value!!
        assertEquals(30, report.leds)
        assertEquals(frame.sumOf { it.size }, report.bytes)
        assertEquals(1, report.packets)
        assertEquals(2, report.writes)
        assertEquals(frame.size, report.writesPlanned)
        assertEquals(250L, report.durationMs)
        assertEquals(LedLab.Failure(write = 3, of = frame.size, kind = LedLab.FailureKind.STATUS, status = 133), report.failure)
        assertFalse(report.nothingWritten)
    }

    @Test fun `only a PaintBox send is recorded, and a refused one says that nothing was written`() {
        // No send begun: a climb or hold cleaning writes without a report.
        LedLab.frame(BoardPacketEncoder(3).encodeClear())
        LedLab.wrote(null)
        assertNull(LedLab.report.value)
        LedLab.end(LedLab.begin(), BoardBrand.MOONBOARD)
        val report = LedLab.report.value!!
        assertTrue(report.nothingWritten)
        assertEquals(0, report.writes)
        assertNull(report.failure)
        // The send is over: later writes do not change its report.
        LedLab.frame(BoardPacketEncoder(3).encodeClear())
        assertTrue(LedLab.report.value!!.nothingWritten)
    }
}
