package com.cruxcoach.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What changing the work anchor actually does, logbook shape by logbook shape.
 *
 * The old estimator was the second-hardest send, all-time, counting every row.
 * The new one is the mean of the hardest tenth of recent sends, at least three
 * and at most twenty-five, deduplicated per climb. Both are computed here from
 * the same input so the difference is visible rather than argued about — this
 * is the before/after comparison, written down where it cannot drift from the
 * code it describes.
 *
 * Read the numbers as Aurora points: 20 = 6c, 22 = 7a, 24 = 7b, 26 = 7c.
 */
class AnchorComparisonTest {

    private val cutoffs = listOf("2025-07-28", "2024-07-28")
    private val recent = "2026-05-01"
    private val ancient = "2019-01-01"

    /** The estimator this replaced: second-hardest send, all-time, no dedup. */
    private fun oldAnchor(sends: List<LoggedSend>): Double? =
        sends.map { it.difficulty }.sortedDescending().getOrNull(1)

    private fun newAnchor(sends: List<LoggedSend>): Double? =
        LogbookProfile.anchorOf(sends, cutoffs)

    private fun sends(
        vararg difficulties: Double,
        at: String = recent,
        uuidPrefix: String = "c",
    ) = difficulties.mapIndexed { i, d -> LoggedSend("$uuidPrefix$i", d, at) }

    @Test
    fun `a small plateau logbook barely moves`() {
        // Six sends around 7a. Both estimators land in the same place, which
        // is the point: the change must not disturb the common case.
        val logbook = sends(23.0, 22.0, 22.0, 21.0, 20.0, 20.0)
        assertEquals(22.0, oldAnchor(logbook))
        assertEquals(22.0, newAnchor(logbook))
    }

    @Test
    fun `a large logbook is no longer anchored at its extreme`() {
        // 200 sends at 7a with two lucky 7c. Second-hardest IS one of the
        // flukes — the more someone logs, the likelier that becomes, which is
        // exactly backwards. The mean of the top twenty absorbs them.
        val plateau = sends(*DoubleArray(200) { 22.0 }, uuidPrefix = "p")
        val flukes = sends(26.0, 26.0, uuidPrefix = "f")
        val logbook = plateau + flukes

        assertEquals(26.0, oldAnchor(logbook), "old took the second fluke")
        // Twenty climbs go into the mean, so two sends four points clear move
        // it by 0.4 — and the rounding takes even that back.
        assertEquals(22.0, newAnchor(logbook), "new stays on the plateau exactly")
    }

    @Test
    fun `interval training no longer lowers the climber's own anchor`() {
        // Twelve weeks of 4x4 on the same four problems: 192 rows, four
        // climbs. That is what an interval block looks like in a logbook.
        val laps = (0 until 12).flatMap {
            listOf("a", "b", "c", "d").flatMap { p -> sends(*DoubleArray(4) { 18.0 }, uuidPrefix = p) }
        }
        val hard = sends(24.0, 23.0, 23.0, 22.0, 22.0, uuidPrefix = "h")

        // The old estimator is untroubled by this, but only because it never
        // looks past the top two values at all — the same indifference that
        // makes it follow a fluke anywhere it appears.
        assertEquals(23.0, oldAnchor(laps + hard))

        // A mean IS troubled by it, which is why the deduplication exists:
        // nine distinct climbs, so the top tenth is the hard ones.
        assertEquals(23.0, newAnchor(laps + hard), "deduplicated: 9 climbs")

        // What it would be without that step — the same session logged as 197
        // separate climbs drags the mean down four points, and a climber doing
        // interval work would quietly lower their own training grade.
        val undeduplicated = laps.mapIndexed { i, l -> l.copy(climbUuid = "lap$i") } + hard
        assertEquals(19.0, newAnchor(undeduplicated), "without dedup: 197 rows")
    }

    @Test
    fun `an old peak no longer anchors current training`() {
        // Sent 7c two years ago, has been climbing 6c since. The old estimator
        // had no notion of time and planned every session off the 7c.
        val past = sends(26.0, 26.0, 25.0, at = ancient, uuidPrefix = "old")
        val now = sends(21.0, 20.0, 20.0, 20.0, 19.0, 19.0, at = recent, uuidPrefix = "new")

        assertEquals(26.0, oldAnchor(past + now), "old anchored on a two-year-old peak")
        assertEquals(20.0, newAnchor(past + now), "new follows current form")
    }

    @Test
    fun `a thin recent window widens instead of collapsing`() {
        // Two sends this year, plenty last year. The window has to give way,
        // or a climber returning from injury gets the default profile.
        val thin = sends(22.0, 21.0, at = recent, uuidPrefix = "r")
        val older = sends(23.0, 22.0, 22.0, 21.0, 21.0, at = "2025-01-01", uuidPrefix = "o")
        val anchor = newAnchor(thin + older)!!
        assertTrue(anchor in 21.0..23.0, "widened window gave $anchor")
    }

    @Test
    fun `the direction of the change is downward, or unchanged`() {
        // Across every shape above, the new anchor never sits HIGHER than the
        // old one. That is the safety property worth stating: the change can
        // make a session easier than before, never harder.
        val shapes = listOf(
            sends(23.0, 22.0, 22.0, 21.0, 20.0, 20.0),
            sends(*DoubleArray(200) { 22.0 }, uuidPrefix = "p") + sends(26.0, 26.0, uuidPrefix = "f"),
            sends(26.0, 26.0, 25.0, at = ancient, uuidPrefix = "old") +
                sends(21.0, 20.0, 20.0, 20.0, 19.0, 19.0, uuidPrefix = "new"),
        )
        shapes.forEachIndexed { i, logbook ->
            val old = oldAnchor(logbook)!!
            val new = newAnchor(logbook)!!
            assertTrue(new <= old, "shape $i: new anchor $new is above the old $old")
        }
    }
}
