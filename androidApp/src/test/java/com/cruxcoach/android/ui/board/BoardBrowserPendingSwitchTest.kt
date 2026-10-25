package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Device 2026-10-21: Tension Board 2 → Kilter Original while the Tension catalogue imported. The picker saved
 * Kilter, but the browser applied a switch only after its first database read, which waited minutes behind the
 * import – the old board's header and climbs stayed on screen. The switch now shows before any read.
 */
class BoardBrowserPendingSwitchTest {

    private fun climb(uuid: String) = ClimbWithStats(
        uuid = uuid, layoutId = 10, setterUsername = null, name = uuid, frames = "", framesCount = 1,
        difficultyAverage = 10.0, qualityAverage = null, ascensionistCount = 1,
    )

    private val tb2Size = BoardSize(6, 5, "12 high x 12 wide", -68, 68, 0, 144, null, BoardBrand.TENSION)

    private val onTension = BoardBrowserState(
        isLoading = false,
        climbs = listOf(climb("masquerade"), climb("putty")),
        canLoadMore = true,
        boardSize = tb2Size,
        hsmExcludedMask = 4L,
        holdSearch = HoldSearchState(selectedHolds = setOf(1, 2)),
        filter = BrowserFilterState(angle = 40, layoutId = 10, boardBrand = "tension", angleChips = listOf(0, 20, 40)),
    )

    @Test
    fun `the new board shows at once with an empty loading list`() {
        val shown = onTension.showPendingBoard(
            boardBrand = BoardBrand.KILTER.wireValue, layoutId = BoardConstants.KILTER_ORIGINAL_LAYOUT, angle = 40,
        )

        assertEquals("kilter", shown.filter.boardBrand)
        assertEquals(BoardConstants.KILTER_ORIGINAL_LAYOUT, shown.filter.layoutId)
        assertEquals(40, shown.filter.angle)
        // Kilter browses with the slider; Tension's chips must not survive the switch.
        assertEquals(emptyList(), shown.filter.angleChips)
        assertEquals(emptyList(), shown.climbs, "no Tension climbs under a Kilter header")
        assertTrue(shown.isLoading)
        assertEquals(false, shown.canLoadMore)
        assertNull(shown.boardSize, "the old board's size must not pair with the new layout")
        assertEquals(0L, shown.hsmExcludedMask)
        assertEquals(emptySet(), shown.holdSearch.selectedHolds)
        assertEquals("Kilter Original", boardBrowserHeaderContext(shown.filter.boardBrand, shown.filter.layoutId, shown.boardSize).title)
    }

    @Test
    fun `a MoonBoard switch snaps the angle to its variant without the database`() {
        val variant = MoonBoardVariant.MOONBOARD_2016
        val shown = onTension.showPendingBoard(boardBrand = "moonboard", layoutId = variant.layoutId.toInt(), angle = 35)

        assertEquals(variant.angles, shown.filter.angleChips)
        assertTrue(shown.filter.angle in variant.angles)
    }

    @Test
    fun `a catalogue board keeps the saved angle until its angles are read`() {
        val onKilter = onTension.showPendingBoard("kilter", BoardConstants.KILTER_ORIGINAL_LAYOUT, 45)
        val shown = onKilter.showPendingBoard(boardBrand = "tension", layoutId = 10, angle = 45)

        assertEquals(45, shown.filter.angle)
        assertEquals(emptyList(), shown.filter.angleChips)
    }
}
