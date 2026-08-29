package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.IntensityZones
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SessionSummaryBuilder] must agree with the honest-stats overhaul: flashes
 * come from the full-history true-flash set (not naive per-session
 * `bidCount <= 1`), and the pyramid groups by the DISPLAYED grade like
 * [BoardStatsComputer.computeGradePyramid].
 */
class SessionSummaryBuilderTest {

    private val zones = IntensityZones(warmUpCeiling = 15.0, optimalCeiling = 22.0, isPersonalized = false)

    private fun ascent(
        uuid: String,
        climbUuid: String = "c-1",
        climbedAt: String = "2026-03-05T18:00:00",
        angle: Long = 40L,
        isSend: Boolean = true,
        bidCount: Long = 1L,
        difficulty: Double? = 18.5,
    ) = AscentWithClimb(
        uuid = uuid,
        userId = 0L,
        climbUuid = climbUuid,
        angle = angle,
        isMirror = false,
        bidCount = bidCount,
        quality = 3L,
        difficulty = difficulty?.toLong(),
        comment = null,
        climbedAt = climbedAt,
        climbName = "Test",
        climbFrames = "p1r12",
        difficultyAverage = difficulty,
        framesCount = 1L,
        isSend = isSend,
        boardBrand = "kilter",
    )

    @Test
    fun `repeat send with prior attempts in an earlier session is not a flash`() {
        // Full history: an attempt on c-1 last month, then today's first-try
        // send — a redpoint/repeat, NOT a flash. Naive bidCount<=1 within the
        // session window would count it.
        val oldAttempt = ascent(uuid = "old-bid", isSend = false, climbedAt = "2026-02-01T18:00:00")
        val todaysSend = ascent(uuid = "send-1", bidCount = 1L, climbedAt = "2026-03-05T18:00:00")
        val history = listOf(oldAttempt, todaysSend)
        val flashUuids = BoardStatsComputer.trueFlashUuids(history)

        val summary = SessionSummaryBuilder.build(
            ascents = listOf(todaysSend), // session window only
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = flashUuids,
        )

        assertEquals(0, summary.flashCount)
        assertEquals(1, summary.totalSends)
    }

    @Test
    fun `first-ever first-try send counts as a flash`() {
        val send = ascent(uuid = "send-1", bidCount = 1L)
        val summary = SessionSummaryBuilder.build(
            ascents = listOf(send),
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = BoardStatsComputer.trueFlashUuids(listOf(send)),
        )
        assertEquals(1, summary.flashCount)
    }

    @Test
    fun `session attempt total uses tries stored on consolidated outcomes`() {
        val summary = SessionSummaryBuilder.build(
            ascents = listOf(
                ascent(uuid = "open", isSend = false, bidCount = 2L),
                ascent(uuid = "send", isSend = true, bidCount = 3L),
            ),
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = emptySet(),
        )
        assertEquals(1, summary.totalSends)
        assertEquals(5, summary.totalAttempts)
    }

    @Test
    fun `pyramid groups by displayed grade, keeping Font grades of one V bucket apart`() {
        // Kilter difficulty 24 = 7b, 25 = 7b+ — both V8. The old V-scale
        // detour merged them into a single "7b+" row.
        val sends = listOf(
            ascent(uuid = "s1", climbUuid = "c-7b", difficulty = 24.0),
            ascent(uuid = "s2", climbUuid = "c-7bp", difficulty = 25.0),
        )
        val summary = SessionSummaryBuilder.build(
            ascents = sends,
            zones = zones,
            gradeScale = GradeScale.FRENCH,
            trueFlashUuids = emptySet(),
        )
        assertEquals(listOf("7b", "7b+"), summary.gradeDistribution.map { it.grade })
    }
}
