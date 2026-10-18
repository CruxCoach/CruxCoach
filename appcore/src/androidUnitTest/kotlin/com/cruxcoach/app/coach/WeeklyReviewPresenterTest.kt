package com.cruxcoach.app.coach

import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.NutritionTexts
import com.cruxcoach.app.ui.WeeklyReviewScreenModel
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Checkin
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Sex
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weekly review on iOS runs Android's WeeklyReviewViewModel (ported) against a real database. */
class WeeklyReviewPresenterTest {
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

    private fun screen(): Pair<WeeklyReviewPresenter, WeeklyReviewScreenModel> {
        val p = WeeklyReviewPresenter(service, kit.scope(), kit.dispatcher, null)
        kit.until("loaded") { !p.state.value.loading }
        return p to WeeklyReviewScreenModel(p, "en")
    }

    /** Port of Android `WeeklyReviewTargetsTest` (holistic audit P2-18 b). */
    @Test
    fun `the review uses the nutrition screen's protein carbohydrate and calorie targets`() {
        kit.db {
            val today = service.today().toString()
            repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 70.0, "kg", 1))
            repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, 176.0, "cm", 1))
            repo.updateProfile {
                it.copy(fuelIntroAccepted = true, sex = Sex.MALE, birthYear = service.today().year - 30, proteinPerKg = 2.2,
                    goal = AthleteGoal.LOSE_WEIGHT, targetWeightKg = 66.0, weeklyLossKg = 0.5, ownCarbsG = 300)
            }
            repo.saveFoodLog(FoodLogEntry("f1", today, 1, Meal.BREAKFAST, name = "Oats", kcal = 600.0, proteinG = 25.0, carbsG = 90.0))
            repo.saveFoodLog(FoodLogEntry("f2", today, 2, Meal.LUNCH, name = "Rice bowl", kcal = 800.0, proteinG = 40.0, carbsG = 110.0))
        }
        val (p, model) = screen()
        val s = p.state.value
        val (targets, need, calorieTarget) = kit.db {
            val profile = repo.profile()
            val activity = service.activities(days = 1)[service.today().toString()]
            val need = service.energyNeed(profile, activity)!!
            Triple(service.fuelTargets(activity, profile)!!, need,
                NutritionTexts.energyTarget(need, service.weightPlan(profile, activity), profile)!!)
        }
        // The same numbers as the rings: protein from the chosen 2.2 g/kg, the own carbohydrate target, and energy
        // against the calorie target while losing weight – not against the need.
        assertEquals(targets.proteinG, s.proteinTarget)
        assertEquals(154, s.proteinTarget)
        assertEquals(300, s.carbsTargetAvg)
        assertEquals(calorieTarget, s.kcalTargetAvg)
        assertEquals(need.totalKcal, s.kcalNeedAvg)
        assertNotEquals(s.kcalNeedAvg, s.kcalTargetAvg)

        val ui = model.currentState
        assertTrue(ui.hasFood)
        assertTrue(ui.hasEnergy)
        // "Energy on logged days: 1400 kcal (target about … kcal, estimated need about … kcal)".
        assertEquals("trn_review_energy_target", ui.energyText.key)
        assertEquals(listOf(1400.0, calorieTarget.toDouble(), need.totalKcal.toDouble()), ui.energyText.args.map { it.number })
        assertEquals("tra_review_protein", ui.proteinText.key)
        assertEquals(listOf(65.0, 154.0), ui.proteinText.args.map { it.number })
        assertEquals("tra_review_carbs", ui.carbsText.key)
        assertEquals(listOf(200.0, 300.0), ui.carbsText.args.map { it.number })
        TrTextCheck.checkAll(ui)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `calories switched off hide the energy line and no food hides the nutrition section`() {
        kit.db { repo.updateProfile { it.copy(showCalories = false) } }
        val (_, model) = screen()
        val empty = model.currentState
        assertFalse(empty.hasFood)
        assertFalse(empty.hasEnergy)
        kit.db { repo.saveFoodLog(FoodLogEntry("f1", service.today().toString(), 1, Meal.LUNCH, name = "Pasta", kcal = 700.0, proteinG = 30.0, carbsG = 100.0)) }
        val (_, again) = screen()
        assertTrue(again.currentState.hasFood)
        assertFalse(again.currentState.hasEnergy)
        assertTrue(again.currentState.energyText.isEmpty)
    }

    @Test
    fun `work sets records areas and check-ins of the week, warm-ups not counted`() {
        kit.db {
            val now = nowMs()
            val today = service.today()
            val lastWeek = today.minus(DatePeriod(days = 7))
            // Last week: the baseline of five pull-ups the new eight beat.
            workout("w0", lastWeek.toString(), now - 7 * DAY_MS)
            repo.saveSet(set("s0", "w0", 0, SetType.WORK, reps = 5, at = now - 7 * DAY_MS + 60_000))
            workout("w1", today.toString(), now - 3_600_000)
            repo.saveSet(set("s1", "w1", -1, SetType.WARMUP, reps = 12, at = now - 3_000_000))
            repo.saveSet(set("s2", "w1", 0, SetType.WORK, reps = 8, at = now - 2_000_000))
            repo.saveSet(set("s3", "w1", 1, SetType.WORK, reps = 8, at = now - 1_500_000))
            repo.saveSet(set("s4", "w1", 2, SetType.WORK, reps = 7, at = now - 1_000_000))
            repo.saveCheckin(Checkin(today.toString(), now, sleep = 4, energy = 2))
        }
        val (p, model) = screen()
        val s = p.state.value
        assertEquals(0, s.weekOffset)
        assertEquals(3, s.sets)
        assertEquals(1, s.workoutDays)
        assertEquals(60, s.workoutMinutes)
        assertEquals(listOf(ExerciseCategoryV2.PULL to 3), s.setsByCategory)
        assertEquals(1, s.domainDays.toMap()[LoadDomain.SHOULDER])
        assertEquals(1, s.domainDays.toMap()[LoadDomain.ELBOW])
        val record = s.records.single()
        assertEquals("pull.pull_up", record.first.slug)
        assertEquals(RecordKind.REPS, record.second.kind)
        assertEquals(8.0, record.second.value)
        assertEquals(listOf("trt_q_sleep" to 4.0, "trt_q_energy" to 2.0), s.checkinAverages)

        val ui = model.currentState
        assertFalse(ui.canGoNext)
        assertEquals("tra_review_week_of", ui.weekTitle.key)
        assertEquals("tra_reps", ui.records.single().text.key)
        assertEquals("tra_review_domain_days", ui.domainDays.first().key)
        assertEquals(listOf("PULL"), ui.categories.map { it.code })
        assertEquals(listOf("trt_q_sleep", "trt_q_energy"), ui.checkins.map { it.code })
        TrTextCheck.checkAll(ui)

        // Last week: its one set, no record (nothing before it), and "next week" leads back.
        model.previous()
        kit.until("last week") { !p.state.value.loading && p.state.value.weekOffset == 1 }
        val last = p.state.value
        assertEquals(1, last.sets)
        assertEquals(1, last.workoutDays)
        assertTrue(last.records.isEmpty())
        assertTrue(last.checkinAverages.isEmpty())
        assertTrue(model.currentState.canGoNext)
        assertEquals(service.today().minus(DatePeriod(days = 7)).let { com.cruxcoach.athlete.logic.ConsistencyStreak.weekStart(it) }, last.weekStart)

        model.next()
        kit.until("this week") { p.state.value.weekOffset == 0 && p.state.value.sets == 3 }
        // Never past this week.
        model.next()
        kit.until("still this week") { p.state.value.weekOffset == 0 && p.state.value.sets == 3 }
        assertFalse(model.currentState.canGoNext)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `the goal line says reached or open against the weekly goal`() {
        kit.db { repo.updateProfile { it.copy(weeklyGoal = 1) } }
        val (_, open) = screen()
        assertEquals("tra_review_goal_open", open.currentState.goalText.key)
        assertNull(open.currentState.records.firstOrNull())
        kit.db {
            workout("w1", service.today().toString(), nowMs() - 1_800_000)
            repo.saveSet(set("s1", "w1", 0, SetType.WORK, reps = 6, at = nowMs() - 600_000))
        }
        val (_, reached) = screen()
        assertTrue(reached.currentState.goalReached)
        assertEquals("tra_review_goal_reached", reached.currentState.goalText.key)
        assertEquals(listOf(1.0, 1.0), reached.currentState.goalText.args.map { it.number })
        TrTextCheck.checkAll(reached.currentState)
    }

    private fun workout(id: String, day: String, startedAt: Long) =
        repo.saveWorkout(Workout(id, startedAt, startedAt + 3_600_000, day, null, null, updatedAt = startedAt))

    private fun set(id: String, workoutId: String, index: Int, type: SetType, reps: Int, at: Long) = ExerciseSet(
        id = id, workoutId = workoutId, exerciseSlug = "pull.pull_up", blockIndex = 0, setIndex = index,
        setType = type, reps = reps, bodyweightKg = 70.0, completedAt = at,
    )

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
