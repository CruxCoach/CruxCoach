package com.cruxcoach.android.ui.board.games

import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardFrameEncoder
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.ShinyWallFrameEncoder
import com.cruxcoach.domain.board.ShinyWallVariant
import com.cruxcoach.domain.board.games.FloorIsLava
import com.cruxcoach.domain.board.games.HoldsThief
import com.cruxcoach.domain.board.games.WallHold
import com.cruxcoach.domain.board.games.WallRows
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WallGameHoldsTest {

    @Test
    fun moonBoardHoldsSitOnTheirGridCells() {
        val variant = MoonBoardVariant.MOONBOARD_2010
        val holds = WallGameHolds.moonBoard(variant, MoonBoardHoldSets.setIdsFor(variant))
        assertEquals(40, holds.size)
        // Hold 26 is column D (index 3) in row 3 (index 2).
        assertEquals(WallHold(26, 3.0, 2.0), holds.first { it.placementId == 26 })
        val rows = WallRows.of(holds)
        assertTrue(rows.rows.all { row -> row.map { it.y }.toSet().size == 1 })
    }

    @Test
    fun moonBoardWithoutASelectedSetUsesTheWholeGrid() {
        val variant = MoonBoardVariant.MOONBOARD_2016
        assertEquals(11 * 18, WallGameHolds.moonBoard(variant, emptyList()).size)
        val oneSet = MoonBoardHoldSets.setIdsFor(variant).first()
        val mounted = WallGameHolds.moonBoard(variant, listOf(oneSet)).map { it.placementId }.toSet()
        assertEquals(MoonBoardHoldSets.holdIdsFor(variant, oneSet), mounted)
    }

    @Test
    fun moonBoardFramesShowFeetAsHandsAndEncodeForTheBoard() {
        val frames = WallGameHolds.moonBoardFrames(
            listOf(
                BoardHold(5, HoldRole.START),
                BoardHold(50, HoldRole.HAND),
                BoardHold(60, HoldRole.FOOT),
                BoardHold(190, HoldRole.FINISH),
                BoardHold(70, HoldRole.ROUTE_HAND),
            ),
        )
        assertEquals("p5r42p50r43p60r43p190r44p70r43", frames)
        val wire = MoonBoardFrameEncoder.encodeToString(frames, MoonBoardVariant.MOONBOARD_2016)
        assertEquals(5, wire.removePrefix("l#").removeSuffix("#").split(',').size)
    }

    @Test
    fun placementsBecomeWallHoldsAndTheSizeKeepsItsOwn() {
        val size = BoardSize(10, 1, "12 x 12", 0, 144, 0, 156, null, BoardBrand.KILTER)
        val inside = BoardPlacement(1, 1, 1, 8, 4)
        val outside = BoardPlacement(2, 2, 1, 150, 4)
        val edge = BoardPlacement(3, 3, 1, 0, 4)
        assertTrue(WallGameHolds.onBoard(inside, size))
        assertTrue(!WallGameHolds.onBoard(outside, size))
        assertTrue(!WallGameHolds.onBoard(edge, size))
        assertTrue(WallGameHolds.onBoard(outside, null))
        val holds = WallGameHolds.fromPlacements(listOf(inside, outside, edge)) { WallGameHolds.onBoard(it, size) }
        assertEquals(listOf(WallHold(1, 8.0, 4.0)), holds)
    }

    @Test
    fun everyGameFrameOnAShinyWallEncodesForItsVariant() {
        // A Standard wall shaped like the bundled geometry: 11 holds per big
        // row, kickers at the bottom. Every frame a game produces must pass
        // the encoder, which refuses holds of another variant.
        val variant = ShinyWallVariant.ST1
        val holds = (0 until 220).map { index ->
            WallHold(variant.placementId(index), (index % 11) * 20.0, (index / 11) * 20.0)
        }
        val rows = WallRows.of(holds)
        FloorIsLava.timeline(rows, level = 5).frames.forEach { frame ->
            assertNotNull(ShinyWallFrameEncoder.encodeRoute(frame.holds, variant))
        }
        val thief = HoldsThief.route(rows, 40, seed = 9)
        val message = ShinyWallFrameEncoder.encodeRoute(thief, variant)
        assertNotNull(message)
        assertEquals(variant.holdCount + 2, message!!.length)
    }
}

class WallGameFormatTest {

    @Test
    fun intervalsDropTrailingZerosInTheUsersLocale() {
        assertEquals("3", WallGameFormat.seconds(3_000, Locale.ENGLISH))
        assertEquals("0.5", WallGameFormat.seconds(500, Locale.ENGLISH))
        assertEquals("0.25", WallGameFormat.seconds(250, Locale.ENGLISH))
        assertEquals("0,1", WallGameFormat.seconds(100, Locale.GERMAN))
        assertEquals("15", WallGameFormat.seconds(15_000, Locale.GERMAN))
    }

    @Test
    fun theStopwatchShowsMinutesSecondsAndTenths() {
        assertEquals("0:00.0", WallGameFormat.clock(0, Locale.ENGLISH))
        assertEquals("0:07.4", WallGameFormat.clock(7_480, Locale.ENGLISH))
        assertEquals("1:05,3", WallGameFormat.clock(65_300, Locale.GERMAN))
        assertEquals("12:00.0", WallGameFormat.clock(720_000, Locale.ENGLISH))
        assertEquals("0:00.0", WallGameFormat.clock(-5, Locale.ENGLISH))
    }
}
