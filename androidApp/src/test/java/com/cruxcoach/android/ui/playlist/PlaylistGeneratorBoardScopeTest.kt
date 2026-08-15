package com.cruxcoach.android.ui.playlist

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistGeneratorBoardScopeTest {
    @Test
    fun `moonboard ignores stale aurora product size`() {
        assertEquals(0, playlistProductSizeFilter("moonboard", 12))
    }

    @Test
    fun `aurora boards retain their selected product size`() {
        assertEquals(12, playlistProductSizeFilter("kilter", 12))
        assertEquals(7, playlistProductSizeFilter("tension", 7))
    }
}
