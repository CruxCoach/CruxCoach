package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.athlete.data.TodayCoach
import com.cruxcoach.athlete.logic.BlockPhase
import com.cruxcoach.athlete.logic.DayActivity
import com.cruxcoach.athlete.logic.ReadinessEvaluator
import com.cruxcoach.athlete.logic.TrainingBlocks
import com.cruxcoach.athlete.model.Checkin
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.PauseReason
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Workout
import com.cruxcoach.athlete.model.SetType
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Issue #59 moved Today's suggestion input into the shared [TodayCoach] and gave the training service a
 * clock of its own, so the simulation runs the app's logic. These tests pin down that the app behaves as
 * before: real time by default, everything dated by the service's clock, and the input read from the same
 * data with the same windows and rules as Today read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TodayCoachTest : AthleteScreenTest() {

    private val today = LocalDate.parse("2026-03-04")
    private fun ms(day: LocalDate, hour: Int = 18) = day.atStartOfDayIn(service.zone).toEpochMilliseconds() + hour * 3_600_000L
    private fun daysAgo(n: Int) = today.minus(DatePeriod(days = n))

    private fun at(day: LocalDate, hour: Int = 18) { val t = ms(day, hour); service.clock = { t } }

    private fun workout(id: String, day: LocalDate, slug: String) {
        repo.saveWorkout(Workout(id, ms(day), ms(day) + 3_600_000L, day.toString(), updatedAt = ms(day)))
        repo.saveSet(ExerciseSet("$id-s", id, slug, 0, 0, SetType.WORK, reps = 8, completedAt = ms(day) + 600_000L))
    }

    @Test
    fun `the service runs on real time unless a test moves its clock`() {
        assertTrue(abs(service.now() - System.currentTimeMillis()) < 60_000L)
        at(today, 10)
        assertEquals(today, service.today())
        val id = service.startWorkout(null, "t")
        val w = repo.workout(id)!!
        assertEquals(ms(today, 10), w.startedAt)
        assertEquals(today.toString(), w.day)
        service.addExercise(id, "pull.pull_up")
        val set = repo.setsFor(id).first { it.setType == SetType.WORK }
        service.completeSetDetailed(set.copy(reps = 6), startRest = false)
        assertEquals(ms(today, 10), repo.setsFor(id).first { it.id == set.id }.completedAt)
    }

    @Test
    fun `the block starts on the first day the coach runs and tired days bring the deload forward`() {
        at(today)
        val coach = service.todayCoach()
        val first = coach.blockState(repo.profile(), null, today)!!
        assertEquals(BlockPhase.INTRO, first.phase)
        assertEquals(today.toString(), repo.profile().coach.blockStartDay)

        val start = today.minus(DatePeriod(days = 18))
        repo.updateProfile { it.copy(coach = it.coach.copy(blockStartDay = start.toString())) }
        (0..3).forEach { repo.saveCheckin(Checkin(daysAgo(it).toString(), ms(daysAgo(it), 7), sleep = 2, energy = 4)) }
        assertEquals(TrainingBlocks.state(start, null, today, fatigueSignals = 4), coach.blockState(repo.profile(), null, today))
    }

    @Test
    fun `the input reads the athlete's data with Today's windows`() {
        at(today)
        workout("w-recent", daysAgo(3), "pull.pull_up")
        workout("w-old", daysAgo(12), "push.push_up")
        repo.updateProfile { it.copy(weekPlan = mapOf(daysAgo(1).dayOfWeek.ordinal + 1 to "climb:gym_boulder:hard")) }
        val profile = repo.profile()
        val activities = mapOf(today.toString() to DayActivity(today.toString(), climbingMinutes = 60, climbIntensity = ClimbIntensity.HARD))
        val readiness = ReadinessEvaluator.evaluate(null, emptyList())
        fun build(choices: TodayCoach.Choices = TodayCoach.Choices()) = service.todayCoach().build(
            profile, null, emptyList(), repo.pauses(), readiness, activities, null, null, emptyList(), null, null, today, choices).input

        assertEquals(ClimbIntensity.HARD, build().plannedYesterday)
        repo.savePause(PausePeriod("p", PauseReason.HOLIDAY, daysAgo(1).toString(), daysAgo(1).toString()))
        val input = build()
        // Finger load and recovery look at the last nine days; the rest of the history only says when an exercise was last done.
        assertEquals(listOf("pull.pull_up"), input.recentSets.map { it.second.exerciseSlug })
        assertEquals(setOf("pull.pull_up", "push.push_up"), input.lastTrained.keys)
        assertEquals(daysAgo(12), input.lastTrained["push.push_up"])
        assertTrue(input.climbingToday)
        // Yesterday was planned as a climbing day, but a pause covered it.
        assertNull(input.plannedYesterday)
        assertEquals(today, input.today)

        val chosen = build(TodayCoach.Choices(variant = 2, levelShift = -1, dislikedToday = setOf("pull.pull_up"), minutesToday = 30))
        assertEquals(2, chosen.variant)
        assertEquals(-1, chosen.levelShift)
        assertEquals(30, chosen.minutesToday)
        assertEquals(-10.0, chosen.affinity["pull.pull_up"]!!, 1e-9)
        assertNull(build(TodayCoach.Choices(ignoreHabit = true)).habit)
    }
}
