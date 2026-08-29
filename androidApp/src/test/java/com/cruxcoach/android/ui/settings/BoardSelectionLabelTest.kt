package com.cruxcoach.android.ui.settings

import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardSelectionLabelTest {
    @Test
    fun `Kilter original always includes product family and size`() {
        assertEquals(
            "Kilter Original · 12x12, with Kickboard",
            boardSelectionLabel(
                BoardBrand.KILTER,
                BoardConstants.KILTER_ORIGINAL_LAYOUT,
                "12x12, with Kickboard",
            ),
        )
    }

    @Test
    fun `Kilter homewall removes redundant Homewall prefix`() {
        assertEquals(
            "Kilter Homewall · 10x7 — Full Ride",
            boardSelectionLabel(
                BoardBrand.KILTER,
                BoardConstants.KILTER_HOMEWALL_LAYOUT,
                "Homewall 10x7 — Full Ride",
            ),
        )
    }

    @Test
    fun `already branded MoonBoard variant is not duplicated`() {
        assertEquals(
            "MoonBoard 2016",
            boardSelectionLabel(BoardBrand.MOONBOARD, 5, "MoonBoard 2016"),
        )
    }

    @Test
    fun `Quantum model receives its brand`() {
        assertEquals(
            "Quantum Board · XL",
            boardSelectionLabel(BoardBrand.QUANTUM, 9101, "XL"),
        )
    }
}
