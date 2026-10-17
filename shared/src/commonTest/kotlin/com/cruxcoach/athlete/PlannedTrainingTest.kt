package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.BoardPlan
import com.cruxcoach.athlete.logic.DayActivity
import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.FuelTargets
import com.cruxcoach.athlete.logic.PlannedTraining
import com.cruxcoach.athlete.logic.PlannedTraining.Kind
import com.cruxcoach.athlete.logic.SessionSuggestion
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PauseReason
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.domain.playlist.GeneratorType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

/** Holistic audit W4 / P1-4: today's planned training feeds today's nutrition targets until it is done. */
class PlannedTrainingTest {

    private val day = LocalDate.parse("2026-10-17")
    private val weekday = day.dayOfWeek.isoDayNumber

    private fun suggestion(
        focus: SuggestionFocus, reasons: List<SuggestionReason>, minutes: Int = 45,
        board: BoardPlan? = null, plannedEntry: String? = null,
    ) = SessionSuggestion(focus, Routine("r", "today", items = emptyList()), reasons, minutes, plannedEntry, boardPlan = board)

    @Test
    fun aPlannedBoardDayCountsItsBoardMinutesAsHardAsItsGeneratorType() {
        for (type in listOf(GeneratorType.LIMIT, GeneratorType.PYRAMID, GeneratorType.PROJECTING)) {
            val s = suggestion(SuggestionFocus.BOARD_DAY, listOf(SuggestionReason.WEEK_PLAN_BOARD), 15, BoardPlan(type, 60), PLAN_BOARD)
            val planned = PlannedTraining.planned(PlannedTraining.summaryOf(s, day))!!
            assertEquals(Kind.BOARD, planned.kind)
            assertEquals(60, planned.minutes)
            assertEquals(60, planned.activity.climbingMinutes)
            assertEquals(ClimbIntensity.HARD, planned.activity.climbIntensity)
            // 60 min × RPE 8: a hard training day, 6 g/kg carbohydrate instead of a rest day's 3.
            assertEquals(DayLoad.HARD, FuelTargets.dayLoad(planned.activity))
        }
        val volume = suggestion(SuggestionFocus.BOARD_DAY, listOf(SuggestionReason.CLIMBING_DAY), 15, BoardPlan(GeneratorType.VOLUME, 45))
        val planned = PlannedTraining.planned(PlannedTraining.summaryOf(volume, day))!!
        assertEquals(ClimbIntensity.VOLUME, planned.activity.climbIntensity)
        assertEquals(DayLoad.MODERATE, FuelTargets.dayLoad(planned.activity))
    }

    @Test
    fun offTheWallCountsTheEstimatedMinutesAtRpeSix() {
        val s = suggestion(SuggestionFocus.PLANNED, listOf(SuggestionReason.WEEK_PLAN), 50, plannedEntry = "routine-1")
        val planned = PlannedTraining.planned(PlannedTraining.summaryOf(s, day))!!
        assertEquals(Kind.OFF_WALL, planned.kind)
        assertEquals(50, planned.activity.workoutMinutes)
        assertEquals(300, planned.activity.workoutLoad)
        // Chosen for today counts too.
        val chosen = suggestion(SuggestionFocus.PULL_PUSH, listOf(SuggestionReason.CHOSEN_TODAY), 40)
        assertEquals(Kind.OFF_WALL, PlannedTraining.summaryOf(chosen, day).kind)
    }

    @Test
    fun aFreeDaysOfferARestDayAndNoSuggestionPlanNothing() {
        val offer = suggestion(SuggestionFocus.FINGER_STRENGTH, listOf(SuggestionReason.FINGERS_RESTED), 45)
        assertNull(PlannedTraining.summaryOf(offer, day).kind)
        val rest = suggestion(SuggestionFocus.REST, listOf(SuggestionReason.WEEK_PLAN_REST), 10, plannedEntry = PLAN_REST)
        assertNull(PlannedTraining.summaryOf(rest, day).kind)
        assertNull(PlannedTraining.summaryOf(null, day).kind)
        assertNull(PlannedTraining.planned(PlannedTraining.summaryOf(offer, day)))
        assertNull(PlannedTraining.planned(null))
    }

    @Test
    fun theSummaryRoundTripsAndKeepsItsDay() {
        val s = PlannedTraining.Summary(day.toString(), Kind.BOARD, 60, GeneratorType.LIMIT.name)
        assertEquals(s, PlannedTraining.decode(PlannedTraining.encode(s)))
        assertNull(PlannedTraining.decode(""))
        assertNull(PlannedTraining.decode("{broken"))
    }

    @Test
    fun thePlanCountsUntilItIsDoneAndOnlyWhileItWeighsMore() {
        val board = PlannedTraining.planned(PlannedTraining.Summary(day.toString(), Kind.BOARD, 60, GeneratorType.LIMIT.name))!!
        // Nothing done yet: the plan.
        assertEquals(board, PlannedTraining.forTargets(null, board))
        assertEquals(board, PlannedTraining.forTargets(DayActivity(day.toString()), board))
        // A short mobility session in the morning does not end a board day; the bigger plan still counts.
        assertEquals(board, PlannedTraining.forTargets(DayActivity(day.toString(), workoutMinutes = 15, workoutLoad = 60), board))
        // Climbing logged: the board day is done, the real day counts.
        assertNull(PlannedTraining.forTargets(DayActivity(day.toString(), climbingMinutes = 30, climbIntensity = ClimbIntensity.LIGHT), board))
        assertNull(PlannedTraining.forTargets(DayActivity(day.toString(), climbingEfforts = 12), board))
        // An off-wall plan is done with a finished training.
        val offWall = PlannedTraining.planned(PlannedTraining.Summary(day.toString(), Kind.OFF_WALL, 45))!!
        assertNull(PlannedTraining.forTargets(DayActivity(day.toString(), workoutMinutes = 20, workoutLoad = 120), offWall))
        // Climbing that weighs more than the off-wall plan: the real day.
        assertNull(PlannedTraining.forTargets(DayActivity(day.toString(), climbingMinutes = 90, climbIntensity = ClimbIntensity.HARD), offWall))
    }

    @Test
    fun tomorrowIsHardOnAPlannedBoardDayOrAUsualClimbingDay() {
        val tomorrow = (weekday % 7) + 1
        assertTrue(PlannedTraining.tomorrowHard(AthleteProfile(weekPlan = mapOf(tomorrow to PLAN_BOARD)), day))
        assertTrue(PlannedTraining.tomorrowHard(AthleteProfile(coach = CoachProfile(climbingDays = setOf(tomorrow))), day))
        // Today's board day, a planned rest tomorrow, a routine tomorrow or nothing: no evening hint.
        assertFalse(PlannedTraining.tomorrowHard(AthleteProfile(weekPlan = mapOf(weekday to PLAN_BOARD)), day))
        assertFalse(PlannedTraining.tomorrowHard(
            AthleteProfile(weekPlan = mapOf(tomorrow to PLAN_REST), coach = CoachProfile(climbingDays = setOf(tomorrow))), day))
        assertFalse(PlannedTraining.tomorrowHard(AthleteProfile(weekPlan = mapOf(tomorrow to "builtin:x")), day))
        assertFalse(PlannedTraining.tomorrowHard(AthleteProfile(), day))
        // On a pause nothing is hard tomorrow.
        val pause = PausePeriod("p", PauseReason.ILLNESS, startDay = day.toString())
        assertFalse(PlannedTraining.tomorrowHard(AthleteProfile(weekPlan = mapOf(tomorrow to PLAN_BOARD)), day, listOf(pause)))
        val ended = pause.copy(endDay = day.toString())
        assertTrue(PlannedTraining.tomorrowHard(AthleteProfile(weekPlan = mapOf(tomorrow to PLAN_BOARD)), day, listOf(ended)))
    }

    @Test
    fun withoutTodaysSuggestionTheWeekPlanSaysWhatIsPlanned() {
        val catalog = ExerciseCatalog.EMPTY
        val board = AthleteProfile(weekPlan = mapOf(weekday to PLAN_BOARD))
        assertEquals(PlannedTraining.Summary(day.toString(), Kind.BOARD, PlannedTraining.BOARD_DEFAULT_MINUTES),
            PlannedTraining.fromWeekPlan(board, day, emptyList(), catalog))
        val climbingDay = AthleteProfile(coach = CoachProfile(climbingDays = setOf(weekday)))
        assertEquals(Kind.BOARD, PlannedTraining.fromWeekPlan(climbingDay, day, emptyList(), catalog).kind)
        val rest = AthleteProfile(weekPlan = mapOf(weekday to PLAN_REST), coach = CoachProfile(climbingDays = setOf(weekday)))
        assertNull(PlannedTraining.fromWeekPlan(rest, day, emptyList(), catalog).kind)
        assertNull(PlannedTraining.fromWeekPlan(AthleteProfile(), day, emptyList(), catalog).kind)
    }
}
