package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardBrowserHeaderTest {
    @Test
    fun `kilter context exposes model size and angle`() {
        val context = boardBrowserHeaderContext(
            boardBrand = BoardBrand.KILTER.wireValue,
            layoutId = BoardConstants.KILTER_ORIGINAL_LAYOUT,
            boardSize = BoardSize(
                id = 10,
                productId = 1,
                name = "12x12",
                edgeLeft = 0,
                edgeRight = 0,
                edgeBottom = 0,
                edgeTop = 0,
                imageFilename = null,
            ),
            angle = 40,
        )

        assertEquals("Kilter Original", context.title)
        assertEquals("12x12, with Kickboard · 40°", context.subtitle)
    }

    @Test
    fun `header still has useful context while board size loads`() {
        val context = boardBrowserHeaderContext(
            boardBrand = BoardBrand.KILTER.wireValue,
            layoutId = BoardConstants.KILTER_HOMEWALL_LAYOUT,
            boardSize = null,
            angle = 30,
        )

        assertEquals("Kilter Homewall", context.title)
        assertEquals("30°", context.subtitle)
    }
}
