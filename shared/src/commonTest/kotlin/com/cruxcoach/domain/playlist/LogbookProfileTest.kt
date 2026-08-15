package com.cruxcoach.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The work anchor.
 *
 * It used to be the second-hardest send — an order statistic at a fixed
 * position, which is the 83rd percentile over six sends and the 99.7th over
 * six hundred. The more someone logged, the harder they were anchored.
 */
class LogbookProfileTest {

    @Test
    fun `default profile is clamped to grades available on the board`() {
        val profile = LogbookProfile(null, null, 0).adaptedToBoardGrades(23.0, 30.0)

        assertEquals(23.0, profile.effectiveMax)
        assertEquals(23.0, profile.effectiveRepeatableMax)
        assertEquals(23.0, profile.effectiveRepeatableFlash)
        assertTrue(!profile.isPersonalized)
    }

    @Test
    fun `board grades never raise a personalized safety ceiling`() {
        val profile = LogbookProfile(18.0, 16.0, 20, anchorDifficulty = 17.0)

        assertEquals(profile, profile.adaptedToBoardGrades(23.0, 30.0))
    }

    private fun send(difficulty: Double, uuid: String = "c$difficulty", at: String = "2026-07-01") =
        LoggedSend(uuid, difficulty, at)

    /** Newest window first, as the platform layer supplies them. */
    private val cutoffs = listOf("2025-07-28", "2024-07-28")

    @Test
    fun `no sends means no anchor`() {
        assertNull(LogbookProfile.anchorOf(emptyList(), cutoffs))
    }

    @Test
    fun `a single send is the anchor, for want of anything better`() {
        assertEquals(22.0, LogbookProfile.anchorOf(listOf(send(22.0)), cutoffs))
    }

    @Test
    fun `a small logbook averages its hardest three`() {
        val sends = listOf(24.0, 22.0, 21.0, 18.0, 17.0, 16.0)
            .mapIndexed { i, d -> send(d, uuid = "c$i") }
        // (24 + 22 + 21) / 3 = 22.33 → 22
        assertEquals(22.0, LogbookProfile.anchorOf(sends, cutoffs))
    }

    @Test
    fun `one lucky send cannot carry a large logbook`() {
        // 200 sends at 20, one fluke at 30. The old estimator took the
        // second-hardest, which here is a 20 — but with a handful of flukes it
        // took those instead. The mean of the top 20 dilutes any single one.
        val sends = (0 until 200).map { send(20.0, uuid = "c$it") } + send(30.0, uuid = "fluke")
        val anchor = LogbookProfile.anchorOf(sends, cutoffs)!!
        // Twenty climbs go in, so a send ten points clear moves the mean by
        // half a point. The old estimator would have handed back the fluke
        // itself as soon as a second one appeared.
        assertTrue(anchor <= 21.0, "one fluke moved the anchor to $anchor")
    }

    @Test
    fun `the count grows with the logbook but stops at the cap`() {
        // 1000 sends spread 10..30. Ten percent would be 100 climbs and would
        // drag the anchor down into everyday terrain; the cap keeps it at 25.
        val sends = (0 until 1000).map { send(10.0 + (it % 21), uuid = "c$it") }
        val anchor = LogbookProfile.anchorOf(sends, cutoffs)!!
        // The hardest 25 of that spread are all 29 or 30.
        assertTrue(anchor >= 29.0, "anchor was $anchor, expected the top of the spread")
    }

    @Test
    fun `repeats of one climb count once`() {
        // A 4x4 session: four problems, four laps each. Without deduplication
        // the sixteen rows would look like sixteen pieces of evidence and the
        // easy laps would dilute a climber's own anchor.
        val session = listOf("a", "b", "c", "d").flatMap { uuid ->
            (0 until 4).map { send(18.0, uuid = uuid) }
        }
        val hard = listOf(24.0, 23.0, 22.0).mapIndexed { i, d -> send(d, uuid = "hard$i") }
        // Seven distinct climbs → k = 3 → the three hard ones.
        assertEquals(23.0, LogbookProfile.anchorOf(session + hard, cutoffs))
    }

    @Test
    fun `an empty recent window widens instead of giving up`() {
        val old = (0 until 10).map { send(22.0, uuid = "c$it", at = "2020-01-01") }
        assertEquals(22.0, LogbookProfile.anchorOf(old, cutoffs))
    }

    @Test
    fun `recent form wins over an older peak`() {
        val recent = (0 until 10).map { send(18.0, uuid = "r$it", at = "2026-06-01") }
        val ancient = (0 until 10).map { send(26.0, uuid = "a$it", at = "2019-01-01") }
        // Ten recent sends fill the first window, so the old peak stays out of
        // the anchor — it still counts as the all-time ceiling elsewhere.
        assertEquals(18.0, LogbookProfile.anchorOf(recent + ancient, cutoffs))
    }

    @Test
    fun `the peak stays the ceiling even when the anchor is lower`() {
        val sends = (0 until 10).map { send(18.0, uuid = "c$it") } + send(26.0, uuid = "peak")
        val profile = LogbookProfile.fromLogbook(sends, emptyList(), emptyList(), cutoffs)
        assertEquals(26.0, profile.maxDifficulty)
        assertTrue(
            profile.effectiveRepeatableMax < profile.effectiveMax,
            "work anchor ${profile.effectiveRepeatableMax} must sit below the peak",
        )
    }
}
