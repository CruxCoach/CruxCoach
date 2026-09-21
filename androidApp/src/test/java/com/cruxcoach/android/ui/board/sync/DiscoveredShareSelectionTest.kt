package com.cruxcoach.android.ui.board.sync

import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveredShareSelectionTest {
    private val offered = listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD)

    @Test
    fun `a first run with no choice yet takes everything the sender has`() {
        assertEquals(offered.toSet() to emptySet(), initialShareSelection(offered, saved = null))
        assertEquals(offered.toSet() to emptySet(), initialShareSelection(offered, saved = emptySet()))
    }

    @Test
    fun `an earlier choice decides what comes from the share and what from the internet`() {
        val (fromShare, fromInternet) = initialShareSelection(
            offered, saved = setOf(BoardBrand.KILTER, BoardBrand.TENSION),
        )
        // Not the sender's MoonBoard — nobody asked for it. Tension they cannot supply.
        assertEquals(setOf(BoardBrand.KILTER), fromShare)
        assertEquals(setOf(BoardBrand.TENSION), fromInternet)
    }

    @Test
    fun `a choice the sender cannot serve at all still offers what it has`() {
        val (fromShare, fromInternet) = initialShareSelection(offered, saved = setOf(BoardBrand.TENSION))
        assertEquals(offered.toSet(), fromShare)
        assertEquals(setOf(BoardBrand.TENSION), fromInternet)
    }
}
