package com.cruxcoach.app.climber

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.ClimberProfileScreenModel
import com.cruxcoach.athlete.logic.ClimbingLoad
import com.cruxcoach.athlete.logic.GradeStrengthNorms
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The climber profile (Android `ClimberProfileScreen`, smoke test "climber profile renders and
 * explains what is missing"): what an empty profile says, and a coach grade with a max hang placed
 * against the orientation band.
 */
class ClimberProfilePresenterTest {
    private lateinit var kit: TrainingKit

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun screen(): Pair<ClimberProfilePresenter, ClimberProfileScreenModel> {
        val p = ClimberProfilePresenter(kit.service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        return p to ClimberProfileScreenModel(p)
    }

    @Test
    fun `a climbing day alone renders and explains what is missing`() {
        kit.db { kit.service.saveClimbingDay(ClimbingDayEntry("cd1", kit.service.today().toString(), ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.HARD)) }
        val (_, model) = screen()
        val s = model.currentState
        assertFalse(s.loading)
        assertFalse(s.failed)
        assertTrue(s.isEmpty)
        assertEquals(listOf("finger", "pull"), s.positions.map { it.metricCode })
        assertEquals(listOf("noValues", "noValues"), s.positions.map { it.stateCode })
        assertEquals("trl_position_no_finger", s.positions[0].emptyText.key)
        assertEquals("trl_position_no_pull", s.positions[1].emptyText.key)
        assertEquals(listOf("–", "–", "–", "–"), s.tiles.map { it.value.args.single().text })
        assertTrue(s.timelineEmpty)
        assertFalse(s.hasGap)
        assertFalse(s.hasPickup)
        assertEquals("trl_corr_waiting", s.correlationText.key)
        // The hard climbing day counts as finger load: three structures over the eight shown weeks.
        assertTrue(s.hasLoad)
        assertEquals(listOf("finger", "skin", "shoulder"), s.loads.map { it.code })
        s.loads.forEach { assertEquals(ClimbingLoad.SERIES_DAYS, it.days.size) }
        val finger = s.loads.first()
        assertTrue(finger.days.last().value > 0.0)
        assertTrue(finger.days.last().acute > 0.0)
        assertTrue(finger.explanation.key.startsWith("trl_trend_"))
        // Every area names why it has no bar.
        assertEquals(listOf("finger", "pull", "power", "endurance", "core"), s.bottlenecks.map { it.code })
        assertTrue(s.bottlenecks.all { !it.hasScore })
        assertEquals("trl_reason_no_finger", s.bottlenecks.first().reason.key)
        assertTrue(TrTextCheck.checkAll(s) > 0)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `a coach grade and a max hang sit against the orientation band`() {
        kit.db {
            kit.repo.updateProfile { it.copy(coach = it.coach.copy(currentGrade = "7a", currentFlashGrade = "6c")) }
            kit.service.saveBenchmark(Benchmark("b1", "finger.max_hang", edgeMm = 20.0, loadKg = 20.0, durationS = 10.0,
                bodyweightKg = 70.0, source = BenchmarkSource.MANUAL, measuredAt = System.currentTimeMillis()))
        }
        val (p, model) = screen()
        val s = model.currentState
        assertFalse(s.isEmpty)
        val seven = LogbookSummaries.difficultyOf("7a")!!
        assertEquals("7A", s.tiles[0].value.args.single().text)
        assertEquals("trl_tile_from_coach", s.tiles[0].supporting.key)
        assertEquals("6C", s.tiles[1].value.args.single().text)
        val finger = s.positions[0]
        assertEquals("chart", finger.stateCode)
        val point = finger.points.single()
        assertEquals(seven, point.difficulty)
        assertTrue(point.newest)
        assertEquals(1.0, point.opacity)
        assertFalse(point.estimated)
        val pct = p.state.value.profile!!.finger.last().pctBw
        assertEquals(pct, point.pctBw)
        // The window holds the point and at least six grades; the band follows the norm line.
        assertTrue(finger.xMin <= seven - 2 && finger.xMax >= finger.xMin + 6)
        assertTrue(finger.yMin < pct && finger.yMax > pct)
        val mid = finger.band[finger.band.size / 2]
        assertEquals(GradeStrengthNorms.band(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, mid.difficulty)!!.center, mid.center)
        assertTrue(finger.gradeTicks.any { it.label == "7A" })
        assertEquals("trl_position_you", finger.youLabel.key)
        val expected = when (GradeStrengthNorms.position(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, seven, pct)) {
            GradeStrengthNorms.Position.BELOW -> "trl_reading_below"
            GradeStrengthNorms.Position.ABOVE -> "trl_reading_above"
            else -> "trl_reading_within"
        }
        assertEquals(expected, finger.reading.key)
        assertFalse(finger.showEstimatedNote)
        // Pulling has no value yet.
        assertEquals("noValues", s.positions[1].stateCode)
        // Flash 6c against working 7a: a gap of two steps, and the gauge marks all known grades.
        assertTrue(s.hasGap)
        assertEquals(listOf("flash", "working"), s.gapMarks.map { it.code })
        assertTrue(s.gapBand)
        assertTrue(s.gapHi - s.gapLo >= 4)
        assertEquals("trl_gap_normal", s.gapText.key)
        // The finger value starts the timeline; no correlation without six paired weeks.
        assertFalse(s.timelineEmpty)
        assertEquals(listOf("grade", "finger"), s.timeline.map { it.code })
        assertEquals(1, s.timeline[1].points.size)
        assertEquals(s.timeline[1].points.single().isoDate, s.timelineStart)
        assertEquals(s.timelineStart, s.timelineEnd)
        assertFalse(s.hasCorrelation)
        val fingerArea = s.bottlenecks.first { it.code == "finger" }
        assertTrue(fingerArea.hasScore)
        assertEquals(expected.replace("trl_reading_", "trl_reason_"), fingerArea.reason.key)
        assertTrue(TrTextCheck.checkAll(s) > 0)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `an estimate is drawn hollow and the reload follows a new value`() {
        kit.db {
            kit.repo.updateProfile { it.copy(coach = it.coach.copy(currentGrade = "6b")) }
            kit.service.saveBenchmark(Benchmark("e1", "finger.max_hang", edgeMm = 20.0, loadKg = 0.0, durationS = 10.0,
                bodyweightKg = 70.0, source = BenchmarkSource.ESTIMATE, measuredAt = System.currentTimeMillis()))
        }
        val (p, model) = screen()
        val finger = model.currentState.positions[0]
        assertEquals("chart", finger.stateCode)
        assertTrue(finger.points.single().estimated)
        assertTrue(finger.showEstimatedNote)
        // Estimates stay out of the timeline (Android filters them).
        assertTrue(model.currentState.timeline[1].points.isEmpty())
        kit.db {
            kit.service.saveBenchmark(Benchmark("w1", "pull.weighted_pull_up", loadKg = 20.0, reps = 1, bodyweightKg = 70.0,
                source = BenchmarkSource.MANUAL, measuredAt = System.currentTimeMillis()))
        }
        model.reload()
        kit.until("pull value") { p.state.value.profile?.pull?.isNotEmpty() == true }
        val pull = model.currentState.positions[1]
        assertEquals("chart", pull.stateCode)
        assertTrue(pull.reading.key.startsWith("trl_reading_"))
        assertTrue(TrTextCheck.checkAll(model.currentState) > 0)
        assertEquals(0, model.mapFailures)
    }
}
