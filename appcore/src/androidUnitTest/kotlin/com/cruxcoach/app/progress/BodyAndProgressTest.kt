package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.BodyScreenModel
import com.cruxcoach.app.ui.BodyScreenState
import com.cruxcoach.app.ui.ProgressScreenModel
import com.cruxcoach.app.ui.ProgressScreenState
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The body page and the Progress tab on iOS run Android's BodyViewModel / StatsHubViewModel
 * logic (ported presenters) against a real database and the real exercise catalogue. The cases
 * follow Android's TrainingScreensSmokeTest ("body screen renders trend and ape index", "stats
 * hub … with history") at the state level, plus the dialog rules of BodyScreen.kt.
 */
class BodyAndProgressTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private val today get() = kit.db { service.today() }

    private fun body(): Pair<BodyPresenter, BodyScreenModel> {
        val p = BodyPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("body loaded") { !p.state.value.loading }
        return p to BodyScreenModel(p, "en")
    }

    private fun progress(): Pair<ProgressPresenter, ProgressScreenModel> {
        val p = ProgressPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("progress loaded") { !p.state.value.loading }
        return p to ProgressScreenModel(p, "en")
    }

    private fun checkTexts(s: BodyScreenState) {
        val texts = mutableListOf<Pair<String, TrText>>()
        texts += listOf("trend" to s.trend, "direction" to s.direction, "rate" to s.rate, "chart" to s.chartDescription, "ape" to s.apeIndex)
        s.summaries.forEach { texts += listOf("sum label" to it.label, "sum value" to it.value, "sum supporting" to it.supporting, "sum badge" to it.badge) }
        s.recent.forEach { texts += listOf("rec label" to it.label, "rec value" to it.value, "rec supporting" to it.supporting, "rec badge" to it.badge) }
        s.metrics.forEach { texts += listOf("metric" to it.label, "last" to it.lastText) }
        s.strengthSeries.forEach { texts += "best" to it.best }
        texts.forEach { (where, t) -> TrTextCheck.check(t, where) }
        assertTrue(TrTextCheck.checkAll(s) > 0)
    }

    private fun checkTexts(s: ProgressScreenState, model: ProgressScreenModel) {
        val texts = mutableListOf<Pair<String, TrText>>()
        texts += listOf("body" to s.bodyValue, "bench" to s.benchmarksValue, "history" to s.historySupporting, "profile" to s.profileLine,
            "days cd" to s.daysChartDescription, "minutes cd" to s.minutesChartDescription, "calendar cd" to s.calendarDescription)
        s.loadChips.forEach { texts += "load" to it.label }
        s.domains.forEach { texts += listOf("domain" to it.label, "domain cd" to it.description) }
        s.exercises.forEach { texts += "exercise" to it.supporting }
        model.rangeCodes.forEach { texts += "range" to model.rangeLabel(it) }
        texts.forEach { (where, t) -> TrTextCheck.check(t, where) }
        assertTrue(TrTextCheck.checkAll(s) > 0)
    }

    // ── Body ─────────────────────────────────────────────────────────

    @Test
    fun `body shows the trend and the ape index`() {
        kit.db {
            repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, 176.0, "cm", 1))
            repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.ARM_SPAN.key, 181.0, "cm", 1))
        }
        val (p, model) = body()
        kit.until("ape index") { p.state.value.apeIndexCm != null }
        val s = model.currentState
        assertTrue(s.hasWeight)
        assertTrue(s.hasApeIndex)
        assertEquals(5.0, p.state.value.apeIndexCm!!, 1e-9)
        assertEquals("tr_format_cm", s.apeIndex.key)
        assertEquals("arm_span", s.apeEnterMetric)
        assertEquals(1, s.readings)
        assertEquals("trb_direction_unknown", s.direction.key)
        assertEquals(listOf("height", "arm_span"), s.summaries.map { it.metricKey })
        checkTexts(s)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `back-dated weights give a smoothed trend and a weekly rate`() {
        val (p, model) = body()
        val d = today
        // 70 kg today (standard athlete); two earlier readings, entered afterwards.
        assertTrue(model.save("weight", d.minus(DatePeriod(days = 14)).toString(), 72.0, "", ""))
        assertTrue(model.save("weight", d.minus(DatePeriod(days = 7)).toString(), 71.0, "", ""))
        kit.until("three readings") { p.state.value.weight.size == 3 }
        val trend = p.state.value.weight
        assertEquals(72.0, trend.first().trend, 1e-9)
        // Each reading pulls the trend only part of the way (TrendWeight), so it stays above the last raw value.
        assertTrue(trend.last().trend > 70.0 && trend.last().trend < 72.0, "${trend.last().trend}")
        val rate = p.state.value.weeklyRateKg!!
        assertTrue(rate < 0, "$rate")
        val s = model.currentState
        assertEquals(3, s.points.size)
        assertEquals("trb_chart_description", s.chartDescription.key)
        assertTrue(s.direction.key == "trb_direction_down" || s.direction.key == "trb_direction_flat", s.direction.key)
        checkTexts(s)
    }

    @Test
    fun `an entry in the future or outside the plausible range is refused`() {
        val (p, model) = body()
        assertFalse(model.save("weight", today.plus(DatePeriod(days = 1)).toString(), 70.0, "", ""))
        assertFalse(model.save("weight", today.toString(), 500.0, "", ""))
        assertFalse(model.save("body_fat", today.toString(), 1.0, "", ""))
        assertFalse(model.save("height", today.toString(), 90.0, "", ""))
        assertFalse(model.save("waist", today.toString(), 4.0, "", ""))
        assertFalse(model.save("no_such_metric", today.toString(), 50.0, "", ""))
        assertFalse(model.save("weight", "not a date", 70.0, "", ""))
        assertFalse(model.save("weight", today.toString(), Double.NaN, "", ""))
        assertFalse(model.isPlausible("arm_span", 280.0))
        assertTrue(model.isPlausible("arm_span", 180.0))
        // Nothing was written: still the one weight of the standard athlete.
        assertEquals(1, kit.db { repo.series(BodyMetric.WEIGHT.key).size })
        assertTrue(kit.db { repo.series(BodyMetric.BODY_FAT.key) }.isEmpty())
        assertEquals(1, p.state.value.weight.size)
    }

    @Test
    fun `correcting the day of an entry leaves no duplicate behind`() {
        val (p, model) = body()
        val d = today.toString()
        val earlier = today.minus(DatePeriod(days = 3)).toString()
        assertTrue(model.save("waist", d, 80.0, "", ""))
        kit.until("waist") { p.state.value.recent.any { it.metric == "waist" } }
        assertTrue(model.save("waist", earlier, 79.5, d, "waist"))
        kit.until("moved") { repo.series("waist").map { it.day } == listOf(earlier) }
        assertEquals(79.5, kit.db { repo.latest("waist")!!.value }, 1e-9)
        // Changing the metric of an entry removes the old one too.
        assertTrue(model.save("hips", earlier, 95.0, earlier, "waist"))
        kit.until("metric changed") { repo.series("waist").isEmpty() && repo.series("hips").size == 1 }
    }

    @Test
    fun `imperial values are stored in canonical units`() {
        kit.db { repo.updateProfile { it.copy(units = UnitSystem.IMPERIAL) } }
        val (p, model) = body()
        kit.until("imperial") { p.state.value.profile.units == UnitSystem.IMPERIAL }
        assertTrue(model.save("weight", today.minus(DatePeriod(days = 1)).toString(), 150.0, "", ""))
        assertTrue(model.save("waist", today.toString(), 32.0, "", ""))
        kit.until("saved") { repo.series("weight").size == 2 && repo.latest("waist") != null }
        val kg = kit.db { repo.series("weight").first().value }
        assertEquals(68.04, kg, 0.01)
        assertEquals(81.28, kit.db { repo.latest("waist")!!.value }, 0.01)
        val s = model.currentState
        assertEquals("imperial", s.unitsCode)
        assertEquals("lb", s.metrics.first { it.key == "weight" }.unitLabel)
        assertEquals("in", s.metrics.first { it.key == "waist" }.unitLabel)
        val waist = s.summaries.first { it.metricKey == "waist" }
        assertEquals(32.0, waist.displayValue, 1e-6)
        assertEquals("tr_format_in", waist.value.key)
        // 50 lb is 22.7 kg: plausible; 30 lb is not.
        assertTrue(model.isPlausible("weight", 50.0))
        assertFalse(model.isPlausible("weight", 30.0))
        checkTexts(s)
    }

    @Test
    fun `hide numbers covers body image values but not build data`() {
        kit.db {
            repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.HEIGHT.key, 176.0, "cm", 1))
            repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WAIST.key, 80.0, "cm", 1))
        }
        val (p, model) = body()
        model.setHideNumbers(true)
        kit.until("hidden") { p.state.value.profile.hideBodyNumbers }
        val s = model.currentState
        assertTrue(s.hideNumbers)
        val waist = s.summaries.first { it.metricKey == "waist" }
        assertTrue(waist.hidden)
        assertEquals("trb_hidden_value", waist.value.key)
        assertFalse(s.summaries.first { it.metricKey == "height" }.hidden)
        assertTrue(s.recent.first { it.metricKey == "weight" }.hidden)
        assertEquals("trb_chart_description_hidden", s.chartDescription.key)
        // The round's "last …" line stays empty for hidden values.
        assertTrue(s.metrics.first { it.key == "waist" }.lastText.isEmpty)
        assertFalse(s.metrics.first { it.key == "height" }.lastText.isEmpty)
        checkTexts(s)
        model.setHideNumbers(false)
        kit.until("shown") { !p.state.value.profile.hideBodyNumbers }
        assertEquals("tr_format_cm", model.currentState.summaries.first { it.metricKey == "waist" }.value.key)
    }

    @Test
    fun `the measurement round saves several values of one day at once`() {
        val (p, model) = body()
        val day = today.minus(DatePeriod(days = 2)).toString()
        // One implausible value blocks the whole round, as Android disables Save.
        assertEquals(-1, model.saveRound(day, listOf("weight", "waist"), listOf(69.0, 500.0)))
        // Height is not part of the round.
        assertEquals(-1, model.saveRound(day, listOf("height"), listOf(176.0)))
        assertEquals(-1, model.saveRound(today.plus(DatePeriod(days = 1)).toString(), listOf("weight"), listOf(69.0)))
        assertEquals(0, model.saveRound(day, emptyList(), emptyList()))
        assertNull(kit.db { repo.latest("waist") })
        assertEquals(3, model.saveRound(day, listOf("weight", "waist", "body_fat"), listOf(69.0, 81.0, 15.0)))
        kit.until("round saved") { p.state.value.roundSaved == 3 }
        assertEquals(3, model.currentState.roundSaved)
        kit.db {
            assertEquals(day, repo.latest("waist")!!.day)
            assertEquals(day, repo.latest("body_fat")!!.day)
            assertEquals(listOf(day, service.today().toString()), repo.series("weight").map { it.day })
        }
        model.consumeRoundSaved()
        assertEquals(0, model.currentState.roundSaved)
        assertEquals(10, model.currentState.metrics.count { it.inRound })
    }

    @Test
    fun `a deleted entry comes back with undo`() {
        val (p, model) = body()
        val d = today.toString()
        assertTrue(model.save("chest", d, 100.0, "", ""))
        kit.until("chest") { p.state.value.recent.any { it.metric == "chest" } }
        model.delete(d, "chest")
        kit.until("deleted") { repo.latest("chest") == null }
        kit.until("gone from the list") { p.state.value.recent.none { it.metric == "chest" } }
        model.undoDelete()
        kit.until("restored") { repo.latest("chest")?.value == 100.0 }
        kit.until("back in the list") { p.state.value.recent.any { it.metric == "chest" } }
    }

    @Test
    fun `strength to weight comes from max hangs with the body weight of their day`() {
        kit.db {
            val id = service.startWorkout(Routine("r", "Fingers", items = listOf(
                RoutineItem("finger.max_hang", sets = 1, durationS = 10, edgeMm = 20, loadKg = 14.0))), null)
            repo.setsFor(id).filter { it.setType == SetType.WORK }.forEach {
                service.completeSet(it.copy(durationS = 10.0, loadKg = 14.0, bodyweightKg = 70.0), startRest = false)
            }
            service.finishWorkout(id, 7, null)
        }
        val (p, model) = body()
        kit.until("strength") { p.state.value.strength.isNotEmpty() }
        val point = p.state.value.strength.single()
        assertEquals("finger.max_hang", point.slug)
        // (70 + 14) / 70 = 120 % body weight.
        assertTrue(abs(point.percentBodyweight - 120.0) < 1.0, "${point.percentBodyweight}")
        val s = model.currentState
        assertEquals(listOf("finger.max_hang"), s.strengthSeries.map { it.slug })
        assertEquals("trb_strength_best", s.strengthSeries.single().best.key)
        checkTexts(s)
    }

    // ── Progress ─────────────────────────────────────────────────────

    @Test
    fun `progress with no training shows the empty hub`() {
        val (_, model) = progress()
        val s = model.currentState
        assertFalse(s.trained)
        // The standard athlete has one weigh-in: that is data.
        assertTrue(s.hasAnyData)
        assertFalse(s.bodyIsAction)
        assertEquals("tr_format_kg", s.bodyValue.key)
        assertTrue(s.benchmarksIsAction)
        assertEquals("tru_tile_log", s.benchmarksValue.key)
        assertEquals("w12", s.rangeCode)
        assertEquals(12, s.weeks.size)
        assertEquals(ProgressPresenter.CALENDAR_DAYS, s.calendar.size)
        assertEquals(today.toString(), s.calendar.last().isoDate)
        assertEquals(listOf("finger", "shoulder", "elbow", "skin"), s.domains.map { it.code })
        assertEquals("trl_card_empty", s.profileLine.key)
        assertEquals(listOf("w4", "w12", "m6", "y1"), model.rangeCodes)
        checkTexts(s, model)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `progress counts a training day, its exercises and the personal bests`() {
        kit.db {
            val id = service.startWorkout(Routine("r", "Pull", items = listOf(
                RoutineItem("pull.weighted_pull_up", sets = 2, repsMin = 5, repsMax = 5, loadKg = 10.0),
                RoutineItem("finger.max_hang", sets = 1, durationS = 10, edgeMm = 20))), null)
            // The second pull-up set is heavier than the first: a personal best inside the range.
            repo.setsFor(id).filter { it.setType == SetType.WORK }.forEach { set ->
                val done = if (set.exerciseSlug == "pull.weighted_pull_up") {
                    set.copy(reps = 5, loadKg = if (set.setIndex == 1) 15.0 else 10.0)
                } else set
                service.completeSet(done, startRest = false)
            }
            // Real sets are seconds apart; here they can share a millisecond, which leaves their order open.
            repo.setsFor(id).filter { it.exerciseSlug == "pull.weighted_pull_up" && it.setIndex == 1 }
                .forEach { repo.saveSet(it.copy(completedAt = it.completedAt!! + 60_000L)) }
            service.finishWorkout(id, 6, null)
            // A training day needs training minutes; the test's training ends in the same millisecond.
            repo.workout(id)!!.let { repo.saveWorkout(it.copy(endedAt = it.startedAt + 40 * 60_000L)) }
        }
        val (p, model) = progress()
        val s = model.currentState
        assertTrue(s.trained)
        assertEquals(1, p.state.value.daysThisWeek)
        assertEquals(1, s.historyValue)
        assertTrue(p.state.value.recordsInRange >= 1, "${p.state.value.recordsInRange}")
        assertEquals(1, s.weeks.last().trainingDays)
        assertEquals(1, s.weeks.last().offBoardDays)
        assertEquals(40, s.weeks.last().workoutMinutes)
        assertEquals(setOf("pull.weighted_pull_up", "finger.max_hang"), s.exercises.map { it.slug }.toSet())
        assertTrue(s.exercises.all { !it.supporting.isEmpty })
        assertTrue(s.calendar.last().level > 0, "today is a training day")
        assertTrue(s.domains.first { it.code == "finger" }.days.last() >= 1)
        checkTexts(s, model)
        // Another range recomputes; an unknown code changes nothing.
        model.setRange("w4")
        kit.until("4 weeks") { p.state.value.range == ProgressRange.FOUR_WEEKS && !p.state.value.loading }
        assertEquals(4, model.currentState.weeks.size)
        model.setRange("nonsense")
        assertEquals("w4", model.currentState.rangeCode)
    }

    @Test
    fun `a weigh-in from the hub is saved and refused when implausible`() {
        kit.db { repo.deleteMeasurement(service.today().toString(), BodyMetric.WEIGHT.key) }
        val (p, model) = progress()
        assertTrue(model.currentState.bodyIsAction)
        assertFalse(model.logWeight(10.0))
        assertFalse(model.logWeight(Double.POSITIVE_INFINITY))
        assertTrue(model.logWeight(66.5))
        kit.until("weight") { p.state.value.latestWeightKg != null && !p.state.value.loading }
        assertEquals(66.5, p.state.value.latestWeightKg!!, 1e-9)
        assertFalse(model.currentState.bodyIsAction)
    }
}
