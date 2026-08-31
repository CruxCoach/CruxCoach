package com.cruxcoach.android.ui.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `candidate filter combines benchmark origin and logbook status`() {
        val sent = setOf("sent")
        val attempted = setOf("open")

        assertTrue(
            playlistCandidateMatchesBrowserFilters(
                origin = "kilter",
                benchmarkDifficulty = 18.0,
                uuid = "open",
                sent = sent,
                attempted = attempted,
                benchmarkOnly = true,
                originFilter = "KILTER",
                statusFilter = "ATTEMPTED",
            )
        )
        assertFalse(
            playlistCandidateMatchesBrowserFilters(
                origin = "kilter",
                benchmarkDifficulty = 0.0,
                uuid = "new",
                sent = sent,
                attempted = attempted,
                benchmarkOnly = true,
                originFilter = "KILTER",
                statusFilter = "NEW",
            )
        )
    }
}
