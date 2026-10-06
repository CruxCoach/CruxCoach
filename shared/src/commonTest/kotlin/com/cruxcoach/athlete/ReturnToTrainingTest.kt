package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReturnToTrainingTest {

    private val today = LocalDate.parse("2026-10-06")

    private fun daysAgo(n: Int) = today.minus(DatePeriod(days = n))

    private fun trained(vararg ago: Int): Map<String, DayActivity> =
        ago.associate { n -> daysAgo(n).toString() to DayActivity(daysAgo(n).toString(), workoutMinutes = 45) }

    private fun climbed(vararg ago: Int): Map<String, DayActivity> =
        ago.associate { n -> daysAgo(n).toString() to DayActivity(daysAgo(n).toString(), climbingMinutes = 60) }

    private fun def(slug: String, domains: List<LoadDomain>, kind: ExerciseKind = ExerciseKind.REPS,
                    load: LoadMode = LoadMode.BODYWEIGHT, tags: List<String> = emptyList()) =
        ExerciseDefinition(slug, ExerciseCategoryV2.FINGER, kind, load, false, listOf(EquipmentV2.NONE), domains,
            defaults = Prescription(sets = 5), tags = tags, i18n = mapOf("en" to ExerciseText(slug)))

    private val maxHang = def("finger.max_hang", listOf(LoadDomain.FINGER), ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS, listOf("strength"))
    private val pullUp = def("pull.pull_up", listOf(LoadDomain.ELBOW, LoadDomain.SHOULDER))

    @Test
    fun noRampForSteadyTrainingOrANewAthlete() {
        assertNull(ReturnToTraining.state(trained(1, 3, 5, 8), emptyList(), emptyList(), today))
        assertNull(ReturnToTraining.state(emptyMap(), emptyList(), emptyList(), today))
    }

    @Test
    fun nineDaysOffIsNotABreakTenAre() {
        assertNull(ReturnToTraining.state(trained(10, 12), emptyList(), emptyList(), today))
        val s = assertNotNull(ReturnToTraining.state(trained(11, 13), emptyList(), emptyList(), today))
        assertEquals(ReturnReason.BREAK_SHORT, s.reason)
        assertEquals(10, s.daysOff)
        assertEquals(1, s.week)
        assertEquals(1, s.weeksTotal)
        assertEquals(0.7, s.setsFactor)
        assertTrue(s.noMaxFinger)
        assertFalse(s.fingerOnly)
        assertEquals(today, s.returnDay)
    }

    @Test
    fun boardClimbingCountsAsTraining() {
        // A board session three days ago splits what would otherwise be 12 days off.
        assertNotNull(ReturnToTraining.state(trained(13), emptyList(), emptyList(), today))
        assertNull(ReturnToTraining.state(trained(13) + climbed(3), emptyList(), emptyList(), today))
    }

    @Test
    fun longBreakRampsOverTwoWeeks() {
        // Back three days ago after 30 days off, one training since.
        val activities = trained(3, 34, 36)
        val s = assertNotNull(ReturnToTraining.state(activities, emptyList(), emptyList(), today))
        assertEquals(ReturnReason.BREAK_LONG, s.reason)
        assertEquals(30, s.daysOff)
        assertEquals(1, s.week)
        assertEquals(0.6, s.setsFactor)
        assertTrue(s.noMaxFinger)
        assertEquals(1, s.trainingDaysSinceReturn)

        // Second week: 80 %, max finger only when it was part of the training before.
        val later = trained(9, 40)
        val w2 = assertNotNull(ReturnToTraining.state(later, emptyList(), emptyList(), today))
        assertEquals(2, w2.week)
        assertEquals(0.8, w2.setsFactor)
        assertTrue(w2.noMaxFinger)
        assertFalse(assertNotNull(ReturnToTraining.state(later, emptyList(), emptyList(), today, maxFingerBefore = true)).noMaxFinger)

        // After two weeks of training again the ramp is over (gaps under 10 days start no new break).
        assertNull(ReturnToTraining.state(trained(1, 8, 15, 50), emptyList(), emptyList(), today))
        // Training once after the break and then nothing for two weeks is a new break of its own.
        assertEquals(ReturnReason.BREAK_SHORT, ReturnToTraining.state(trained(15, 50), emptyList(), emptyList(), today)?.reason)
    }

    @Test
    fun fourTrainingDaysEndTheRampEarly() {
        val three = trained(1, 2, 4, 5, 30)
        assertNotNull(ReturnToTraining.state(trained(2, 4, 5, 30), emptyList(), emptyList(), today))
        assertNull(ReturnToTraining.state(three, emptyList(), emptyList(), today))
    }

    @Test
    fun anOpenPauseIsNotARamp() {
        val pause = PausePeriod("p", PauseReason.ILLNESS, daysAgo(12).toString())
        assertNull(ReturnToTraining.state(trained(14), listOf(pause), emptyList(), today))
    }

    @Test
    fun aLongerIllnessRampsEvenWithoutTenDaysOff() {
        val pause = PausePeriod("p", PauseReason.ILLNESS, daysAgo(9).toString(), daysAgo(2).toString())
        val s = assertNotNull(ReturnToTraining.state(trained(1, 10), listOf(pause), emptyList(), today))
        assertEquals(ReturnReason.AFTER_ILLNESS, s.reason)
        assertEquals(2, s.weeksTotal)
        assertEquals(daysAgo(1), s.returnDay)

        // A short illness (three to six days) ramps for one week; a one-day cold not at all.
        val short = PausePeriod("p", PauseReason.ILLNESS, daysAgo(6).toString(), daysAgo(3).toString())
        assertEquals(1, assertNotNull(ReturnToTraining.state(trained(8), listOf(short), emptyList(), today)).weeksTotal)
        val cold = PausePeriod("p", PauseReason.ILLNESS, daysAgo(2).toString(), daysAgo(2).toString())
        assertNull(ReturnToTraining.state(trained(1, 3), listOf(cold), emptyList(), today))
        // A holiday is not an illness.
        val holiday = PausePeriod("p", PauseReason.HOLIDAY, daysAgo(9).toString(), daysAgo(2).toString())
        assertNull(ReturnToTraining.state(trained(1, 10), listOf(holiday), emptyList(), today))
    }

    @Test
    fun healedFingerInjuryKeepsOnlyTheFingersOffTheLimit() {
        val healed = Injury("i", InjuryRegion.FINGER, InjurySide.LEFT, severity = 3, startedOn = daysAgo(40).toString(),
            resolvedOn = daysAgo(10).toString())
        val s = assertNotNull(ReturnToTraining.state(trained(1, 2, 3), emptyList(), listOf(healed), today))
        assertEquals(ReturnReason.FINGER_INJURY_HEALED, s.reason)
        assertTrue(s.fingerOnly)
        assertTrue(s.noMaxFinger)
        assertEquals(2, s.week)
        // Pull-ups untouched, max hangs dropped, other finger work keeps its sets.
        assertEquals(4, ReturnToTraining.scaleSets(4, s, pullUp))
        assertNull(ReturnToTraining.applyToItem(RoutineItem("finger.max_hang", sets = 5), maxHang, s))
        assertFalse(ReturnToTraining.applies(s, pullUp))

        // Older than four weeks, or still open: no finger ramp.
        val old = healed.copy(resolvedOn = daysAgo(28).toString())
        assertNull(ReturnToTraining.state(trained(1, 2), emptyList(), listOf(old), today))
        val open = healed.copy(resolvedOn = null)
        assertNull(ReturnToTraining.state(trained(1, 2), emptyList(), listOf(open), today))
    }

    @Test
    fun breakAndHealedFingerTogetherUseTheWholeBodyRamp() {
        val healed = Injury("i", InjuryRegion.FINGER, severity = 2, startedOn = daysAgo(60).toString(),
            resolvedOn = daysAgo(20).toString())
        val s = assertNotNull(ReturnToTraining.state(trained(45), emptyList(), listOf(healed), today, maxFingerBefore = true))
        assertEquals(ReturnReason.BREAK_LONG, s.reason)
        assertFalse(s.fingerOnly)
        assertTrue(s.noMaxFinger)
    }

    @Test
    fun itemsScaleWithTheRamp() {
        val s = assertNotNull(ReturnToTraining.state(trained(15), emptyList(), emptyList(), today))
        assertEquals(3, ReturnToTraining.applyToItem(RoutineItem("pull.pull_up", sets = 4), pullUp, s)?.sets)
        // Warm-ups and tests stay as planned; one set is the floor.
        assertEquals(4, ReturnToTraining.applyToItem(RoutineItem("pull.pull_up", sets = 4, warmup = true), pullUp, s)?.sets)
        assertEquals(1, ReturnToTraining.scaleSets(1, s, pullUp))
        assertFalse(ReturnToTraining.allowsMaxFinger(s))
        assertTrue(ReturnToTraining.allowsMaxFinger(null))
        // A future planned day is irrelevant for the ramp.
        assertNull(ReturnToTraining.state(trained(1) + mapOf(today.plus(DatePeriod(days = 3)).toString() to
            DayActivity(today.plus(DatePeriod(days = 3)).toString(), workoutMinutes = 30)), emptyList(), emptyList(), today))
    }

    @Test
    fun maxFingerBeforeLooksAtCompletedWorkSetsBeforeTheBreak() {
        val catalog = ExerciseCatalog(1, listOf(maxHang, pullUp))
        fun set(slug: String, ago: Int, type: SetType = SetType.WORK, done: Boolean = true) = daysAgo(ago) to
            ExerciseSet(id = "$slug-$ago", workoutId = "w", exerciseSlug = slug, blockIndex = 0, setIndex = 0,
                setType = type, completedAt = if (done) 1L else null)
        assertTrue(ReturnToTraining.maxFingerBefore(listOf(set("finger.max_hang", 40)), catalog, daysAgo(10)))
        // Pull-ups, warm-ups, unfinished sets and sets after the return do not count.
        assertFalse(ReturnToTraining.maxFingerBefore(listOf(set("pull.pull_up", 40)), catalog, daysAgo(10)))
        assertFalse(ReturnToTraining.maxFingerBefore(listOf(set("finger.max_hang", 40, SetType.WARMUP)), catalog, daysAgo(10)))
        assertFalse(ReturnToTraining.maxFingerBefore(listOf(set("finger.max_hang", 40, done = false)), catalog, daysAgo(10)))
        assertFalse(ReturnToTraining.maxFingerBefore(listOf(set("finger.max_hang", 5)), catalog, daysAgo(10)))
        // Too long ago.
        assertFalse(ReturnToTraining.maxFingerBefore(listOf(set("finger.max_hang", 200)), catalog, daysAgo(10)))
    }
}
