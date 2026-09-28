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
    fun `board hub only exposes families with special settings`() {
        val cards = boardSettingsCards(BoardBrand.QUANTUM)

        assertEquals(listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD), cards.map { it.brand })
        assertTrue(cards.none { it.isActive })
        assertEquals(listOf(BoardBrand.MOONBOARD), boardSettingsCards(BoardBrand.MOONBOARD)
            .filter { it.isActive }.map { it.brand })
    }
}
