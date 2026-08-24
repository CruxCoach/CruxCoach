package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardLogbookHeatmapBoardKeyTest {

    @Test
    fun `unknown MoonBoard layout is not assigned to a heatmap generation`() {
        assertNull(heatmapLoggedBoardKey(BoardBrand.MOONBOARD.wireValue, null))
    }

    @Test
    fun `known MoonBoard layout keeps its generation`() {
        assertEquals(
            BoardBrand.MOONBOARD.wireValue to 5,
            heatmapLoggedBoardKey(BoardBrand.MOONBOARD.wireValue, 5L),
        )
    }

    @Test
    fun `legacy Kilter row still falls back to Original`() {
        assertEquals(
            BoardBrand.KILTER.wireValue to BoardConstants.KILTER_ORIGINAL_LAYOUT,
            heatmapLoggedBoardKey(BoardBrand.KILTER.wireValue, null),
        )
    }
}
