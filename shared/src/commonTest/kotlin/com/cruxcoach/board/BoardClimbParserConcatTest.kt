package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardClimbParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full Kilter `climbConcat` wire form is
 * `h{holeId}p{roleId}[s{startFrame}][e{endFrame}]`.
 *
 * The everyday parser ignores the optional `s`/`e` suffixes — harmless for the
 * single-frame climbs that make up virtually all of a logbook, but a
 * multi-frame route parsed that way collapses into one frame and loses its
 * sequence. The cron-side ingest has always read them
 * (`update_board_db.convert_climb_concat`); this is the app-side counterpart.
 */
class BoardClimbParserConcatTest {

    @Test
    fun singleFrameConcat_parsesHoldsWithDefaultRange() {
        val holds = BoardClimbParser.parseClimbConcat("h1174p12h1284p13h1416p14")
        assertEquals(3, holds.size)
        assertEquals(listOf(1174, 1284, 1416), holds.map { it.holeId })
        assertEquals(listOf(12, 13, 14), holds.map { it.roleId })
        // Ohne Suffix: ab Frame 1, kein Ende.
        assertTrue(holds.all { it.startFrame == 1 && it.endFrame == null })
    }

    @Test
    fun frameRangeSuffixes_areRead() {
        val holds = BoardClimbParser.parseClimbConcat("h10p12s1e2h20p13s2h30p14s3e3")
        assertEquals(3, holds.size)
        assertEquals(1 to 2, holds[0].startFrame to holds[0].endFrame)
        assertEquals(2 to null, holds[1].startFrame to holds[1].endFrame)
        assertEquals(3 to 3, holds[2].startFrame to holds[2].endFrame)
    }

    @Test
    fun suffixDigitsAreNotMistakenForAnotherHold() {
        // Der alltägliche Parser überspringt "s2e3" als unverstandene Bytes.
        // Das ist gutartig, darf aber nicht dazu führen, dass eine der Zahlen
        // als eigener Hold auftaucht.
        val holds = BoardClimbParser.parseClimbConcat("h10p12s2e3h20p13")
        assertEquals(listOf(10, 20), holds.map { it.holeId })
    }

    @Test
    fun routeRoles_areNormalizedLikeEverywhereElse() {
        // 42-45 sind die Route-Varianten von 12-15.
        val holds = BoardClimbParser.parseClimbConcat("h1p42h2p43h3p44h4p45")
        assertEquals(listOf(12, 13, 14, 15), holds.map { it.roleId })
    }

    @Test
    fun deltaFormatIsNotTreatedAsConcat() {
        // "p…r…" ist das Aurora-Format; hier darf nichts herauskommen.
        assertTrue(BoardClimbParser.parseClimbConcat("p1125r15p1140r12").isEmpty())
        assertTrue(BoardClimbParser.parseClimbConcat("").isEmpty())
    }
}
