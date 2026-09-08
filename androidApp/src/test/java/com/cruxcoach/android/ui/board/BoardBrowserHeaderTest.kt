package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardBrowserHeaderTest {
    @Test
    fun `kilter context exposes model and physical subtype`() {
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
        )

        assertEquals("Kilter Original", context.title)
        assertEquals("12x12, with Kickboard", context.subtitle)
    }

    @Test
    fun `homewall subtype does not repeat the model name`() {
        val context = boardBrowserHeaderContext(
            boardBrand = BoardBrand.KILTER.wireValue,
            layoutId = BoardConstants.KILTER_HOMEWALL_LAYOUT,
            boardSize = BoardSize(
                id = 17,
                productId = 7,
                name = "Homewall 10x7 — Full Ride",
                edgeLeft = 0,
                edgeRight = 0,
                edgeBottom = 0,
                edgeTop = 0,
                imageFilename = null,
            ),
        )

        assertEquals("Kilter Homewall", context.title)
        assertEquals("10x7 — Full Ride", context.subtitle)
    }
}
