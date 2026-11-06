package com.cruxcoach.domain.yearreview

import com.cruxcoach.data.repository.AscentWithClimb
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClimberTypeTest {

    /** Round thresholds, so the mechanics read plainly whatever the calibrated defaults are. */
    private val rules = ClimberTypeRules(
        flashShare = 0.40,
        projectShare = 0.10,
        projectAverageTries = 4.0,
        mileageSendsPerDay = 10.0,
        steepShare = 0.60,
        clockworkWeekShare = 0.75,
        strengthShare = 0.30,
        comebackBreakDays = 42,
        comebackScore = 1.5,
    )

    private fun pick(facts: ClimberTypeFacts) = ClimberTypes.pick(facts, rules)

    @Test
    fun withoutAnyRuleMetEveryoneIsAnAllRounder() {
        val nothing = pick(ClimberTypeFacts())
        assertEquals(ClimberType.ALL_ROUNDER, nothing.type)
        assertNull(nothing.match)
        assertNull(nothing.also)

        // The All-Rounder's card names the closest type instead.
        val almost = pick(ClimberTypeFacts(firstSends = 40, flashes = 12))
        assertEquals(ClimberType.ALL_ROUNDER, almost.type)
        assertEquals(ClimberType.FLASH_MACHINE, almost.match!!.type)
        assertEquals(0.3, almost.match!!.value, 1e-9)
    }

    @Test
    fun aRuleNeedsItsSample() {
        assertEquals(ClimberType.FLASH_MACHINE, pick(ClimberTypeFacts(firstSends = 20, flashes = 10)).type)
        assertEquals(ClimberType.ALL_ROUNDER, pick(ClimberTypeFacts(firstSends = 19, flashes = 19)).type)
        assertEquals(ClimberType.ALL_ROUNDER, pick(ClimberTypeFacts(sends = 400, boardDays = 19)).type)
        assertEquals(ClimberType.MILEAGE_MONSTER, pick(ClimberTypeFacts(sends = 400, boardDays = 20)).type)
    }

    @Test
    fun theStrongestMatchWinsAndTheNextIsAlso() {
        // Flashes 50 % ÷ 40 % = 1.25; a run of 24 weeks ÷ 16 = 1.5.
        val both = pick(ClimberTypeFacts(firstSends = 30, flashes = 15, longestWeekStreak = 24))
        assertEquals(ClimberType.CLOCKWORK, both.type)
        assertEquals(1, both.match!!.rule)
        assertEquals(ClimberType.FLASH_MACHINE, both.also!!.type)

        // A tie goes to the earlier type in the table.
        val tie = pick(ClimberTypeFacts(firstSends = 20, flashes = 10, longestWeekStreak = 20))
        assertEquals(ClimberType.FLASH_MACHINE, tie.type)
    }

    @Test
    fun theProjectorHasTwoRules() {
        // A fifth of the first sends took 10 tries or more: twice the 10 % of the rule.
        val hardWon = pick(ClimberTypeFacts(firstSends = 20, firstSendTries = List(4) { 12 } + List(16) { 1 }))
        assertEquals(ClimberType.PROJECTOR, hardWon.type)
        assertEquals(0, hardWon.match!!.rule)

        val stubborn = pick(ClimberTypeFacts(firstSends = 15, firstSendTries = List(15) { 5 }))
        assertEquals(ClimberType.PROJECTOR, stubborn.type)
        assertEquals(1, stubborn.match!!.rule)
        assertEquals(5.0, stubborn.match!!.value)

        // Both need 15 first sends.
        assertEquals(ClimberType.ALL_ROUNDER, pick(ClimberTypeFacts(firstSends = 14, firstSendTries = List(14) { 12 })).type)
    }

    @Test
    fun theBoardHopperScoresByItsThirdBoard() {
        val three = pick(ClimberTypeFacts(boardEntries = listOf(60, 25, 15)))
        assertEquals(ClimberType.BOARD_HOPPER, three.type)
        assertEquals(1.5, three.match!!.score, 1e-9)
        assertEquals(ClimberType.ALL_ROUNDER, pick(ClimberTypeFacts(boardEntries = listOf(80, 12, 8))).type)
    }

    @Test
    fun strengthLabNeedsBothItsParts() {
        // 40 workouts, but on only 20 % of the training days.
        assertEquals(ClimberType.ALL_ROUNDER, pick(ClimberTypeFacts(workouts = 40, workoutDays = 40, trainingDays = 200)).type)
        assertEquals(ClimberType.STRENGTH_LAB, pick(ClimberTypeFacts(workouts = 40, workoutDays = 40, trainingDays = 100)).type)
    }

    @Test
    fun aComebackNeedsAFinishedPlanOrALongBreak() {
        assertEquals(ClimberType.COMEBACK_KID, pick(ClimberTypeFacts(comebacksCompleted = 1)).type)
        assertEquals(ClimberType.ALL_ROUNDER, pick(ClimberTypeFacts(breakDays = 30, daysAfterBreak = 40)).type)
        val back = pick(ClimberTypeFacts(breakDays = 60, daysAfterBreak = 12))
        assertEquals(ClimberType.COMEBACK_KID, back.type)
        assertEquals(1, back.match!!.rule)
    }

    // ── From a logbook ───────────────────────────────────────────────

    private var next = 0

    private fun send(day: LocalDate, difficulty: Double, board: String = "kilter", tries: Long = 1) = AscentWithClimb(
        uuid = "e${next++}", climbUuid = "c$next", angle = 40, isMirror = false, bidCount = tries, quality = null,
        difficulty = null, comment = null, climbedAt = "${day}T18:00:00", climbName = "c$next", climbFrames = "",
        difficultyAverage = difficulty, isSend = true, boardBrand = board,
    )

    @Test
    fun aBreakOverTheNewYearEndsInTheNewYear() {
        // Climbing until mid-November, then from 1 February twelve times.
        val before = (0 until 10).map { send(LocalDate(2025, 11, 1).plus(it, DateTimeUnit.DAY), 15.0) }
        val after = (0 until 12).map { send(LocalDate(2026, 2, 1).plus(it * 7, DateTimeUnit.DAY), 15.0) }
        val review = YearReviewComputer.compute(YearReviewComputer.Input(entries = before + after), 2026, LocalDate(2027, 1, 5), TimeZone.UTC)
        val comeback = review.climberType!!.scores.first { it.type == ClimberType.COMEBACK_KID }
        // 10 November to 1 February, and twelve climbing days after it.
        assertEquals(83.0, comeback.value)
        assertEquals(12, comeback.count)
    }

    @Test
    fun withoutLastYearTheLevelComparesTheFirstAndLastThreeMonths() {
        // Two sends a week at 16 from January, at 20 from October, none of them flashed.
        val early = (0 until 12).map { send(LocalDate(2026, 1, 5).plus(it * 3, DateTimeUnit.DAY), 16.0, tries = 2) }
        val late = (0 until 12).map { send(LocalDate(2026, 10, 5).plus(it * 3, DateTimeUnit.DAY), 20.0, tries = 2) }
        val review = YearReviewComputer.compute(YearReviewComputer.Input(entries = early + late), 2026, LocalDate(2027, 1, 5), TimeZone.UTC)
        val rising = review.climberType!!.scores.first { it.type == ClimberType.RISING_STAR }
        assertEquals(1, rising.rule)
        assertEquals(4.0, rising.value)
        assertEquals(2.0, rising.score)
    }

    @Test
    fun belowTheMinimumThereIsNoType() {
        val review = YearReviewComputer.compute(
            YearReviewComputer.Input(entries = listOf(send(LocalDate(2026, 3, 1), 15.0))), 2026, LocalDate(2027, 1, 5), TimeZone.UTC,
        )
        assertNull(review.climberType)
    }
}
