package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.AreaTarget
import com.cruxcoach.athlete.logic.BlockPhase
import com.cruxcoach.athlete.logic.BlockState
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.logic.WeeklyVolume
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The athlete's level ([DayPlans.tier], owner 2026-11-22) in every rule that reads it: what the last four weeks show,
 * the scale for women, the weekly targets, the planner's warnings – and "train anyway" as a habit, which the same
 * change taught the coach. Recovery after climbing by level is in [CoachAdvisesTest].
 */
class LevelTierTest {
    private val monday = LocalDate.parse("2026-10-19")
    private fun day(n: Int) = monday.plus(DatePeriod(days = n))
    private val fingers = "builtin:finger_basics"
    private val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry

    /** Advanced: more than five years, an adult, four days a week, 7A. */
    private val pro = CoachProfile(experience = ExperienceBand.OVER_5, ageBand = AgeBand.Y18_39, trainingDaysPerWeek = 4, currentGrade = "7A")
    private val home = setOf(EquipmentV2.NONE, EquipmentV2.MAT, EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.BANDS, EquipmentV2.DUMBBELL)
    private val profile = AthleteProfile(equipment = home, equipmentConfigured = true, sessionMinutes = 60)

    private fun targets(coach: CoachProfile, days: Int = 4, observed: Int? = null, sex: Sex? = null, block: BlockState? = null) =
        WeeklyVolume.targets(profile.copy(coach = coach, sex = sex), block, emptyList(), days, observed)

    private fun target(list: List<AreaTarget>, area: VolumeArea) = list.single { it.area == area }

    // ── What the last four weeks show ────────────────────────────────

    @Test
    fun observedDaysAreTheFourWeeksBeforeTodayRoundedDown() {
        val today = day(40)
        fun ago(n: Int) = today.minus(DatePeriod(days = n))
        fun observed(days: List<Int>) = DayPlans.observedDays(days.map { ago(it) }, today)
        // Four days in four weeks are one a week, three are none yet.
        assertEquals(1, observed(listOf(1, 2, 3, 4)))
        assertEquals(0, observed(listOf(1, 2, 3)))
        // The window: the day four weeks ago counts, the day before it does not, and neither does today.
        assertEquals(1, observed(listOf(28, 27, 26, 25)))
        assertEquals(0, observed(listOf(29, 27, 26, 25)))
        assertEquals(0, observed(listOf(0, 1, 2, 3)))
        // Rounded down: 19 days are four a week, 20 are five; every day of four weeks is seven.
        assertEquals(4, observed((1..19).toList()))
        assertEquals(5, observed((1..20).toList()))
        assertEquals(7, observed((1..28).toList()))
        // A day that comes twice (a training and climbing) counts once.
        assertEquals(1, observed(listOf(1, 2, 3, 4, 1, 2, 3, 4)))
        assertEquals(0, DayPlans.observedDays(emptyList(), today))
    }

    @Test
    fun theLevelTakesTheHigherOfTheSettingAndWhatTheWeeksShow() {
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(pro.copy(trainingDaysPerWeek = 2), observedDays = 4))
        assertEquals(DayPlans.Tier.STANDARD, DayPlans.tier(pro.copy(trainingDaysPerWeek = 2), observedDays = 3))
        // Fewer days lately take nothing away from the setting.
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(pro, observedDays = 1))
        // Days alone never make the level: 6C+ on seven days stays as everyone.
        assertEquals(DayPlans.Tier.STANDARD, DayPlans.tier(pro.copy(currentGrade = "6C+", trainingDaysPerWeek = 7), observedDays = 7))
        // 7C on four days is advanced: elite needs five.
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(pro.copy(currentGrade = "7C")))
        assertEquals(DayPlans.Tier.ELITE, DayPlans.tier(pro.copy(currentGrade = "7C"), observedDays = 5))
        // An age not given counts as an adult; a grade the scale does not know as none.
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(pro.copy(ageBand = null)))
        assertEquals(DayPlans.Tier.STANDARD, DayPlans.tier(pro.copy(currentGrade = "V5")))
        assertEquals(DayPlans.Tier.STANDARD, DayPlans.tier(pro.copy(ageBand = AgeBand.UNDER_16)))
        // From 40 on the level stays – the planner follows it; recovery and the weekly room follow the age (below).
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(pro.copy(ageBand = AgeBand.Y40_54)))
    }

    @Test
    fun theScaleForWomenAppliesOnlyWhereTheProfileSaysSo() {
        // 7A+ on five days: elite on the scale for women, advanced on the other.
        val climber = pro.copy(currentGrade = "7A+", trainingDaysPerWeek = 5)
        assertEquals(DayPlans.Tier.ELITE, DayPlans.tier(climber, sex = Sex.FEMALE))
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(climber, sex = Sex.MALE))
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(climber, sex = Sex.OTHER))
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(climber, sex = null))
        // The days are the same for everyone: 7A+ on four days is advanced for a woman too.
        assertEquals(DayPlans.Tier.ADVANCED, DayPlans.tier(climber.copy(trainingDaysPerWeek = 4), sex = Sex.FEMALE))
        // The weekly targets read the scale from the profile.
        val sixBPlus = pro.copy(currentGrade = "6B+")
        assertEquals(17, target(targets(sixBPlus, sex = Sex.FEMALE), VolumeArea.PULL).maxSets)
        assertEquals(14, target(targets(sixBPlus, sex = Sex.MALE), VolumeArea.PULL).maxSets)
        assertEquals(14, target(targets(sixBPlus), VolumeArea.PULL).maxSets)
    }

    // ── Weekly targets ───────────────────────────────────────────────

    @Test
    fun anAdvancedClimberGetsRoomAtTheTopNotAHigherGoal() {
        val standard = targets(pro.copy(currentGrade = "6C+"))
        val advanced = targets(pro)
        // Four days: 6–12 sets of pulling are 7–14, and the upper end opens by a quarter to 17.
        assertEquals(AreaTarget(VolumeArea.PULL, 7, 14, 1), target(standard, VolumeArea.PULL))
        assertEquals(AreaTarget(VolumeArea.PULL, 7, 17, 1), target(advanced, VolumeArea.PULL))
        assertEquals(9, target(advanced, VolumeArea.MOBILITY).maxSets)
        VolumeArea.entries.filter { it != VolumeArea.FINGER }.forEach { area ->
            assertEquals(target(standard, area).minSets, target(advanced, area).minSets, "$area: the coach asks for no more")
            assertEquals(target(standard, area).minSessions, target(advanced, area).minSessions, "$area")
            assertTrue(target(advanced, area).maxSets > target(standard, area).maxSets, "$area: ${target(advanced, area)}")
        }
        // Finger work is planned in sessions: two, with the same cap of sets off the wall.
        assertEquals(target(standard, VolumeArea.FINGER), target(advanced, VolumeArea.FINGER))
        assertEquals(2, target(advanced, VolumeArea.FINGER).minSessions)
        // What the last four weeks show counts like the setting: three days set, four trained.
        val threeSet = pro.copy(trainingDaysPerWeek = 3)
        assertEquals(12, target(targets(threeSet, days = 3), VolumeArea.PULL).maxSets)
        assertEquals(12, target(targets(threeSet, days = 3, observed = 3), VolumeArea.PULL).maxSets)
        assertEquals(15, target(targets(threeSet, days = 3, observed = 4), VolumeArea.PULL).maxSets)
    }

    @Test
    fun anEliteClimberGetsTheSameRoomAndThreeFingerSessions() {
        val advanced = targets(pro.copy(trainingDaysPerWeek = 5), days = 5)
        val elite = targets(pro.copy(currentGrade = "8A", trainingDaysPerWeek = 5), days = 5)
        // Not more room than advanced: nothing is gained beyond it.
        VolumeArea.entries.filter { it != VolumeArea.FINGER }.forEach { area -> assertEquals(target(advanced, area), target(elite, area), "$area") }
        // Three finger sessions whatever the goal, the sets a week stay the same.
        assertEquals(2, target(advanced, VolumeArea.FINGER).minSessions)
        assertEquals(target(advanced, VolumeArea.FINGER).copy(minSessions = 3), target(elite, VolumeArea.FINGER))
        // A lighter week keeps its one finger session for everyone.
        val deload = targets(pro.copy(currentGrade = "8A", trainingDaysPerWeek = 5), days = 5, block = BlockState(BlockPhase.DELOAD, 1, 1, null))
        assertEquals(1, target(deload, VolumeArea.FINGER).minSessions)
    }

    @Test
    fun from40OnTheWeeklyTargetsAreAsForEveryone() {
        val forty = pro.copy(ageBand = AgeBand.Y40_54)
        assertEquals(targets(forty.copy(currentGrade = "6C+")), targets(forty))
        val eliteForty = forty.copy(currentGrade = "8A", trainingDaysPerWeek = 5)
        assertEquals(targets(eliteForty.copy(currentGrade = "6C+"), days = 5), targets(eliteForty, days = 5))
        assertEquals(2, target(targets(eliteForty, days = 5), VolumeArea.FINGER).minSessions)
        // And so they are for a young climber, whatever the grade.
        val young = pro.copy(ageBand = AgeBand.Y16_17)
        assertEquals(targets(young.copy(currentGrade = "6C+")), targets(young))
    }

    // ── The planner ──────────────────────────────────────────────────

    @Test
    fun fingerTrainingBeforeAHardDayIsPointedOutByLevel() {
        var p = DayPlans.withDay(AthleteProfile(), day(1), fingers, monday)
        p = DayPlans.withDay(p, day(2), limit, monday)
        p = DayPlans.withDay(p, day(4), fingers, monday)
        p = DayPlans.withDay(p, day(5), PLAN_BOARD, monday)
        val days = DayPlans.preview(p, monday, 7, monday)
        val beforeLimit = DayPlans.Warning(DayPlans.WarningKind.FINGERS_BEFORE_HARD, day(2), day(1))
        val beforeBoard = DayPlans.Warning(DayPlans.WarningKind.FINGERS_BEFORE_HARD, day(5), day(4))
        assertEquals(listOf(beforeLimit, beforeBoard), DayPlans.warnings(days, monday) { it == fingers })
        // An advanced climber is told before a limit day only, an elite climber nothing.
        assertEquals(listOf(beforeLimit), DayPlans.warnings(days, monday, DayPlans.Tier.ADVANCED) { it == fingers })
        assertTrue(DayPlans.warnings(days, monday, DayPlans.Tier.ELITE) { it == fingers }.isEmpty())
    }

    // ── "Train anyway" as a habit ────────────────────────────────────

    private fun anyway(
        id: String, day: LocalDate, at: Long = 1_000, focus: String = PreferenceLearning.TRAIN_ANYWAY,
        kind: SuggestionEventKind = SuggestionEventKind.FEEDBACK,
    ) = SuggestionEvent(id, day.toString(), at, kind, focus)

    @Test
    fun trainingPastTheWeeksDaysCountsWeeksAndCanBeForgotten() {
        val today = day(20)
        val twoWeeks = listOf(anyway("a", day(5)), anyway("b", day(13)))
        assertTrue(PreferenceLearning.trainsPastWeekDays(twoWeeks, today))
        // A Sunday and the Monday after it are two weeks; two days of one week are one.
        assertTrue(PreferenceLearning.trainsPastWeekDays(listOf(anyway("a", day(6)), anyway("b", day(7))), today))
        assertTrue(!PreferenceLearning.trainsPastWeekDays(listOf(anyway("a", day(7)), anyway("b", day(13))), today))
        // "Forget everything" forgets this too; forgetting one weekday or one exercise does not.
        assertTrue(!PreferenceLearning.trainsPastWeekDays(twoWeeks, today, mapOf(PreferenceLearning.RESET_ALL to 1_000L)))
        assertTrue(PreferenceLearning.trainsPastWeekDays(twoWeeks, today, mapOf(PreferenceLearning.RESET_ALL to 999L)))
        assertTrue(PreferenceLearning.trainsPastWeekDays(twoWeeks, today,
            mapOf(PreferenceLearning.weekdayKey(1) to 5_000L, PreferenceLearning.exerciseKey("pull.pull_up") to 5_000L)))
        // Only "train anyway" counts: not "anyway" for a kind the coach held back, nor another event with that name.
        assertTrue(!PreferenceLearning.trainsPastWeekDays(listOf(anyway("a", day(5)), anyway("b", day(13), focus = PreferenceLearning.INSIST)), today))
        assertTrue(!PreferenceLearning.trainsPastWeekDays(listOf(anyway("a", day(5)), anyway("b", day(13), kind = SuggestionEventKind.NEXT)), today))
        // Six weeks back still counts, a day more does not.
        fun ago(n: Int) = today.minus(DatePeriod(days = n))
        assertTrue(PreferenceLearning.trainsPastWeekDays(listOf(anyway("a", ago(PreferenceLearning.WINDOW_DAYS)), anyway("b", ago(35))), today))
        assertTrue(!PreferenceLearning.trainsPastWeekDays(listOf(anyway("a", ago(PreferenceLearning.WINDOW_DAYS + 1)), anyway("b", ago(35))), today))
    }
}
