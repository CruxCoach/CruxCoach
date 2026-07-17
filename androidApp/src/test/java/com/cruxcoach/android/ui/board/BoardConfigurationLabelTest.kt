package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardConfigurationLabelTest {
    @Test
    fun `moonboard label includes the exact setup and angle`() {
        assertEquals(
            "MoonBoard Masters 2019 \u00B7 40\u00B0",
            activeBoardConfigurationLabel(
                brand = BoardBrand.MOONBOARD,
                layoutId = MoonBoardVariant.MASTERS_2019.layoutId.toInt(),
                angle = 40,
                boardSize = null,
            ),
        )
    }

    @Test
    fun `kilter label includes its configured physical size`() {
        assertEquals(
            "Kilter \u00B7 12x12, with Kickboard \u00B7 40\u00B0",
            activeBoardConfigurationLabel(
                brand = BoardBrand.KILTER,
                layoutId = 1,
                angle = 40,
                boardSize = BoardSize(
                    id = 10,
                    productId = 1,
                    name = "12 x 12 with kickboard",
                    edgeLeft = 0,
                    edgeRight = 144,
                    edgeBottom = 0,
                    edgeTop = 156,
                    imageFilename = null,
                ),
            ),
        )
    }
}
