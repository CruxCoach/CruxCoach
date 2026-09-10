package com.cruxcoach.android.ui.playlist

import com.cruxcoach.domain.playlist.PlaylistCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistGeneratorBoardScopeTest {
    @Test
    fun `generation snapshot is filtered in memory by plan and browser ranges`() {
        val candidates = (10..24).map { difficulty ->
            PlaylistCandidate("climb-$difficulty", difficulty.toDouble())
        }

        val selected = playlistCandidatesInBand(
            candidates = candidates,
            minDifficulty = 12.0,
            maxDifficulty = 22.0,
            targetMinDifficulty = 14.0,
            targetMaxDifficulty = 20.0,
            browserMinDifficulty = 16.0,
            browserMaxDifficulty = 24.0,
            limit = 3,
        )

        assertEquals(listOf(16.0, 17.0, 18.0), selected.map { it.difficulty })
    }

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
