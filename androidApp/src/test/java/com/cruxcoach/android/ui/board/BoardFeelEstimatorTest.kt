package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.roundToInt

/** Tests for [BoardFeelEstimator] and the tries buckets of [CrossBoardStatsComputer]. */
class BoardFeelEstimatorTest {

    private var id = 0

    private fun firstSend(board: String, difficulty: Double, tries: Int) = CrossBoardStatsComputer.FirstSend(
        AscentWithClimb(
            uuid = "r-${++id}", climbUuid = "c-$id", angle = 40, isMirror = false, bidCount = tries.toLong(),
            quality = null, difficulty = difficulty.toLong(), comment = null, climbedAt = "2026-03-01T18:00:00",
            climbName = "c", climbFrames = "", difficultyAverage = difficulty, isSend = true, boardBrand = board,
        ),
        tries,
    )

    // Tries grow by a third per difficulty step; a small wobble keeps the fit honest.
    private val wobble = listOf(1.0, 1.4, 0.75, 1.2, 0.85)

    private fun board(name: String, harderBy: Double, difficulties: List<Double>) =
        difficulties.mapIndexed { i, d ->
            val tries = 1.5 * exp(0.3 * (d + harderBy - 14)) * wobble[i % wobble.size]
            firstSend(name, d, tries.roundToInt().coerceAtLeast(1))
        }

    private val spread = (0 until 30).map { 14.0 + it % 8 }

    @Test
    fun `a board that takes more tries at the same grades feels harder`() {
        val sends = board("kilter", 0.0, spread) + board("moonboard", 2.0, spread.take(20))
        val feel = BoardFeelEstimator.estimate(sends, GradeScale.FRENCH)
        assertNotNull(feel)
        assertEquals("kilter", feel!!.reference)
        val offset = feel.offsets.getValue("moonboard")
        assertEquals(2.0, offset.grades, 0.6)
        assertTrue(offset.error < BoardFeelEstimator.MAX_ERROR)
    }

    @Test
    fun `a wider grade scale turns the same difficulty into fewer grades`() {
        val sends = board("kilter", 0.0, spread) + board("moonboard", 2.0, spread.take(20))
        val font = BoardFeelEstimator.estimate(sends, GradeScale.FRENCH)!!.offsets.getValue("moonboard")
        val v = BoardFeelEstimator.estimate(sends, GradeScale.V_SCALE)?.offsets?.get("moonboard")
        // Around 6b, two Font grades are about one V grade.
        if (v != null) assertTrue(v.grades < font.grades)
        assertEquals(2.0, BoardFeelEstimator.unitsPerGrade(18.0, GradeScale.V_SCALE), 1e-9)
        assertEquals(1.0, BoardFeelEstimator.unitsPerGrade(18.0, GradeScale.FRENCH), 1e-9)
    }

    @Test
    fun `too few problems or no shared grades say nothing`() {
        val few = board("kilter", 0.0, spread) + board("moonboard", 2.0, spread.take(BoardFeelEstimator.MIN_PROBLEMS - 1))
        assertNull(BoardFeelEstimator.estimate(few, GradeScale.FRENCH))

        val apart = board("kilter", 0.0, (0 until 20).map { 14.0 + it % 3 }) +
            board("moonboard", 0.0, (0 until 20).map { 20.0 + it % 3 })
        assertNull(BoardFeelEstimator.estimate(apart, GradeScale.FRENCH))
    }

    @Test
    fun `tries that do not rise with the grade give no estimate`() {
        // The same tries at the easy and the hard end.
        val pattern = listOf(2, 1, 3, 3, 1, 2)
        val flat = (0 until 24).map { firstSend("kilter", 14.0 + it % 6, pattern[it % 6]) } +
            (0 until 24).map { firstSend("moonboard", 14.0 + it % 6, pattern[it % 6]) }
        assertNull(BoardFeelEstimator.estimate(flat, GradeScale.FRENCH))
    }

    @Test
    fun `first sends fall into flash, 2, 3, 4 to 5 and 6 or more tries`() {
        assertEquals(listOf(0, 1, 2, 3, 3, 4, 4), listOf(1, 2, 3, 4, 5, 6, 12).map { TriesBuckets.bucketOf(it) })
        assertEquals(TriesBuckets(listOf(2, 1, 0, 2, 1)), TriesBuckets.of(listOf(1, 1, 2, 4, 5, 9)))
        assertEquals(6, TriesBuckets.of(listOf(1, 1, 2, 4, 5, 9)).problems)
    }
}
