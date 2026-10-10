package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardCleaningEncoderTest {
    @Test fun moonBoardCleaningUsesUniquePhysicalHoldsAndRejectsInvalidFrames() {
        val positions = MoonBoardFrameEncoder.cleaningPositions(
            "p1r42p1r44p2r43p199r43p0r42p3r99", MoonBoardVariant.MOONBOARD_2016,
        )
        assertEquals(setOf(0, 35), positions)
        assertEquals("l#P0,P35#", MoonBoardFrameEncoder.encodeCleaning(positions).decodeToString())
        assertEquals(setOf(0, 23), MoonBoardFrameEncoder.cleaningPositions(
            "p1r42p2r44p133r43", MoonBoardVariant.MINI_2020,
        ))
    }

    @Test fun accumulatedDayLargerThanSinglePacketRetainsEveryPosition() {
        for (api in listOf(2, 3)) {
            val encoder = BoardPacketEncoder(api)
            val chunks = encoder.encodeClimb((0 until 200).map { it to BoardPacketEncoder.COLOR_HAND })
            assertTrue(chunks.all { it.size <= BoardPacketEncoder.BLE_MTU })
            val stream = chunks.flatMap { it.toList() }
            val positions = mutableListOf<Int>()
            var offset = 0
            while (offset < stream.size) {
                assertEquals(1, stream[offset].toInt())
                val length = stream[offset + 1].toInt() and 0xff
                val type = stream[offset + 4]
                assertTrue(type in if (api == 3) listOf(BoardPacketEncoder.API3_FIRST, BoardPacketEncoder.API3_MIDDLE, BoardPacketEncoder.API3_LAST)
                    else listOf(BoardPacketEncoder.API2_FIRST, BoardPacketEncoder.API2_MIDDLE, BoardPacketEncoder.API2_LAST))
                val width = if (api == 3) 3 else 2
                for (index in offset + 5 until offset + 4 + length step width) {
                    val highMask = if (api == 3) 255 else 3
                    positions += (stream[index].toInt() and 255) or ((stream[index + 1].toInt() and highMask) shl 8)
                }
                offset += length + 5
            }
            assertEquals((0 until 200).toList(), positions)
        }
    }
}
