package com.cruxcoach.android.ui.settings

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SettingsBoardGroupingTest {
    @Test
    fun `custom hold-role LED colors are exposed only for Kilter`() {
        assertTrue(showsKilterLedColors(BoardBrand.KILTER))
        BoardBrand.entries.filterNot { it == BoardBrand.KILTER }.forEach { brand ->
            assertFalse(showsKilterLedColors(brand), "LED colors leaked into ${brand.wireValue}")
        }
    }

    @Test
    fun `board hub exposes every interactive board and marks only the active one`() {
        val cards = boardSettingsCards(BoardBrand.QUANTUM)

        assertEquals(BoardBrand.entries.filter { it.isInteractive }, cards.map { it.brand })
        assertEquals(listOf(BoardBrand.QUANTUM), cards.filter { it.isActive }.map { it.brand })
    }
}
