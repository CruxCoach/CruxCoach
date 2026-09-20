package com.cruxcoach.android.ui.playlist

import com.cruxcoach.domain.playlist.PlaylistCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistGeneratorBoardScopeTest {
    @Test
    fun `generation snapshot is filtered in memory by displayed grade`() {
        val candidates = listOf(15.4, 15.5, 16.0, 18.4, 18.5).map { difficulty ->
            PlaylistCandidate("climb-$difficulty", difficulty)
        }

        val selected = playlistCandidatesInBand(
            candidates = candidates,
            minDifficulty = 16.0,
            maxDifficulty = 18.0,
        )

        // 6a…6b is every climb the app shows as 6a, 6a+ or 6b: 15.5 rounds up into the band,
        // 18.5 rounds up out of it. The board browser's grade filter is not part of it.
        assertEquals(listOf(15.5, 16.0, 18.4), selected.map { it.difficulty })
    }

    @Test
    fun `candidates are loaded per whole grade of a band`() {
        assertEquals(listOf(15, 16, 17, 18), playlistGradesInBand(15.0, 18.0))
        assertEquals(listOf(17), playlistGradesInBand(16.5, 17.5))
        assertEquals(emptyList(), playlistGradesInBand(16.2, 16.8))
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
