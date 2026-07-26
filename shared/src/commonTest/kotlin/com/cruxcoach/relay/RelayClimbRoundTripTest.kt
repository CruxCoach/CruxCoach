package com.cruxcoach.relay

import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.BoardPacketEncoder
import com.cruxcoach.domain.relay.RelayClimbMatcher
import com.cruxcoach.domain.relay.RelayFrameReassembler
import com.cruxcoach.domain.relay.RelayLedDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole FEAT-044 identification chain on real catalogue data: a stored
 * climb becomes LED packets the way any Aurora app would send them, and the
 * relay side has to arrive back at the same hold set.
 *
 * The mapping below is a verbatim slice of the shipped Kilter catalogue
 * (product size 10) — the point of this test is that the two directions agree
 * on REAL numbers, which a made-up map would not prove.
 */
class RelayClimbRoundTripTest {

    private val frames = "p1080r15p1110r15p1131r12p1146r12p1164r13p1202r13p1246r13" +
        "p1250r13p1282r13p1331r13p1351r13p1385r14"

    private val placementToLed = mapOf(
        1080 to 15, 1110 to 143, 1131 to 244, 1146 to 191, 1164 to 203, 1202 to 308,
        1246 to 131, 1250 to 233, 1282 to 179, 1331 to 124, 1351 to 219, 1385 to 221,
    )

    private fun sendAndDecode(apiLevel: Int): List<Int> {
        val holds = BoardClimbParser.parseFrames(frames)
        val packets = BoardPacketEncoder(apiLevel = apiLevel)
            .encodeClimbFromHolds(holds, placementToLed)

        val reassembler = RelayFrameReassembler()
        val complete = packets.flatMap { reassembler.offer(it) }
        assertEquals(1, complete.size)

        val decoded = RelayLedDecoder.decode(complete.first().rawBytes)
        assertNotNull(decoded)
        val ledToPlacement = placementToLed.entries.associate { (p, l) -> l to p }
        return decoded.leds.map { ledToPlacement.getValue(it.position) }
    }

    @Test
    fun api3_send_is_identified_as_the_original_climb() {
        val placements = sendAndDecode(apiLevel = 3).toSet()

        assertEquals(placementToLed.keys, placements)
        assertTrue(RelayClimbMatcher.holdsMatch(frames, placements))
        assertTrue(frames.length in RelayClimbMatcher.frameLengthRange(placements, 1, 2))
    }

    @Test
    fun api2_send_is_identified_as_the_original_climb() {
        // @2 rescales colours and packs the position differently — the holds
        // must survive that untouched, colours are not part of the identity.
        val placements = sendAndDecode(apiLevel = 2).toSet()

        assertEquals(placementToLed.keys, placements)
        assertTrue(RelayClimbMatcher.holdsMatch(frames, placements))
    }

    @Test
    fun a_climb_one_hold_apart_is_not_a_match() {
        val placements = sendAndDecode(apiLevel = 3).toSet()
        val otherClimb = frames.replace("p1385r14", "p1386r14")

        assertTrue(RelayClimbMatcher.holdsMatch(frames, placements))
        assertTrue(!RelayClimbMatcher.holdsMatch(otherClimb, placements))
    }
}
