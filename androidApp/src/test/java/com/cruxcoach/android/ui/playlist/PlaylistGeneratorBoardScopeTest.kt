package com.cruxcoach.android.ui.playlist

import com.cruxcoach.data.repository.AscentWithClimb
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
    fun `minimum ascents offers the usual thresholds and keeps an inherited value`() {
        assertEquals(listOf(0, 5, 20, 100, 500), minAscentOptions(0))
        // 30 came from the board browser's filter: selectable, in its place.
        assertEquals(listOf(0, 5, 20, 30, 100, 500), minAscentOptions(30))
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

    private fun log(climb: String, angle: Long, isSend: Boolean, tries: Long = 1, at: String = "2026-11-20T18:00:00") =
        AscentWithClimb(
            uuid = "$climb-$angle-$isSend-$at", climbUuid = climb, angle = angle, isMirror = false, bidCount = tries,
            quality = null, difficulty = null, comment = null, climbedAt = at, climbName = climb,
            climbFrames = "", difficultyAverage = 18.0, isSend = isSend,
        )

    @Test
    fun `logbook status is read at the session angle`() {
        val logbook = listOf(
            log("sent-at-40", 40, isSend = true),
            log("sent-at-40", 25, isSend = false),
            log("tried-at-40", 40, isSend = false),
        )
        assertEquals(setOf("sent-at-40"), playlistLoggedClimbsAtAngle(logbook, 40, sends = true))
        assertEquals(setOf("tried-at-40"), playlistLoggedClimbsAtAngle(logbook, 40, sends = false))
        // A 40° send leaves the climb unsent at 25°.
        assertEquals(emptySet(), playlistLoggedClimbsAtAngle(logbook, 25, sends = true))
        assertEquals(setOf("sent-at-40"), playlistLoggedClimbsAtAngle(logbook, 25, sends = false))
    }

    @Test
    fun `a send at another angle does not close a project`() {
        val logbook = listOf(
            // Sent at 40°, still worked at 25°.
            log("sent-at-40", 40, isSend = true),
            log("sent-at-40", 25, isSend = false, tries = 4),
            // Sent at 25° after the tries there: closed at 25°.
            log("sent-at-25", 25, isSend = false, tries = 5, at = "2026-11-19T18:00:00"),
            log("sent-at-25", 25, isSend = true),
            // Tries at 40° that led to the 40° send are not open at 30° either.
            log("sent-after-tries", 40, isSend = false, tries = 5, at = "2026-11-19T18:00:00"),
            log("sent-after-tries", 40, isSend = true),
        )
        assertEquals(listOf("sent-at-40"), playlistOpenProjects(logbook, 25, minAttempts = 3))
        assertEquals(emptyList(), playlistOpenProjects(logbook, 40, minAttempts = 3))
        // Off-angle projects stay, behind those at the session's angle.
        assertEquals(listOf("sent-at-40"), playlistOpenProjects(logbook, 30, minAttempts = 3))
    }

    @Test
    fun `projects at the session angle come first`() {
        val logbook = listOf(
            log("off-angle", 25, isSend = false, tries = 9),
            log("on-angle", 40, isSend = false, tries = 3),
        )
        assertEquals(listOf("on-angle", "off-angle"), playlistOpenProjects(logbook, 40, minAttempts = 3))
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
