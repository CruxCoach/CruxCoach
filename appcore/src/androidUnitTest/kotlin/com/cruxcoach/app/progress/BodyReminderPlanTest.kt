package com.cruxcoach.app.progress

import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.AthleteSettingsScreenModel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Body reminders: Android's next-occurrence rules, scheduled ahead on the one-shot host scheduler. */
class BodyReminderPlanTest {
    private val utc = TimeZone.UTC
    // 2026-10-14 is a Wednesday (ISO 3).
    private val wedMorning = LocalDateTime(LocalDate(2026, 10, 14), LocalTime(7, 0))

    @Test
    fun `the next weigh-in is the next chosen weekday at the chosen time`() {
        // Wednesday 07:00, reminder 07:30 on Mon and Wed: today.
        assertEquals(LocalDateTime(LocalDate(2026, 10, 14), LocalTime(7, 30)),
            BodyReminderPlan.nextWeigh(wedMorning, setOf(1, 3), 7 * 60 + 30, utc))
        // Past today's time: next Monday.
        val wedNoon = LocalDateTime(LocalDate(2026, 10, 14), LocalTime(12, 0))
        assertEquals(LocalDateTime(LocalDate(2026, 10, 19), LocalTime(7, 30)),
            BodyReminderPlan.nextWeigh(wedNoon, setOf(1, 3), 7 * 60 + 30, utc))
        // Within 30 s counts as now, not later: next week.
        val almost = LocalDateTime(LocalDate(2026, 10, 14), LocalTime(7, 29, 45))
        assertEquals(LocalDate(2026, 10, 21), BodyReminderPlan.nextWeigh(almost, setOf(3), 7 * 60 + 30, utc)!!.date)
        assertNull(BodyReminderPlan.nextWeigh(wedMorning, setOf(0, 8), 450, utc))
        assertNull(BodyReminderPlan.nextWeigh(wedMorning, emptySet(), 450, utc))
    }

    @Test
    fun `the measuring round is on its day of this or next month`() {
        assertEquals(LocalDateTime(LocalDate(2026, 10, 20), LocalTime(8, 0)),
            BodyReminderPlan.nextMeasure(wedMorning, 20, 8 * 60, utc))
        assertEquals(LocalDateTime(LocalDate(2026, 11, 1), LocalTime(8, 0)),
            BodyReminderPlan.nextMeasure(wedMorning, 1, 8 * 60, utc))
        // Day 31 is clamped to 28 so every month has it; December rolls into January.
        val dec = LocalDateTime(LocalDate(2026, 12, 30), LocalTime(9, 0))
        assertEquals(LocalDate(2027, 1, 28), BodyReminderPlan.nextMeasure(dec, 31, 8 * 60, utc).date)
    }

    @Test
    fun `occurrences ahead skip a day already logged`() {
        val ahead = BodyReminderPlan.upcomingWeigh(wedMorning, (1..7).toSet(), 7 * 60 + 30, utc, 8,
            setOf(LocalDate(2026, 10, 14)))
        assertEquals(8, ahead.size)
        assertEquals(LocalDate(2026, 10, 15), ahead.first().date)
        assertEquals((15..22).map { if (it <= 31) LocalDate(2026, 10, it) else LocalDate(2026, 11, it - 31) }, ahead.map { it.date })
        val months = BodyReminderPlan.upcomingMeasure(wedMorning, 14, 8 * 60, utc, 2, emptySet())
        assertEquals(listOf(LocalDate(2026, 10, 14), LocalDate(2026, 11, 14)), months.map { it.date })
    }

    private class Recording : NotificationScheduler {
        val scheduled = linkedMapOf<String, Pair<Int, String>>()
        val cancelled = mutableSetOf<String>()
        var grant = true
        override fun schedule(id: String, afterSeconds: Int, titleKey: String, bodyKey: String) {
            scheduled[id] = afterSeconds to titleKey; cancelled -= id
        }
        override fun cancel(id: String) { scheduled -= id; cancelled += id }
        override fun requestPermission(onResult: (Boolean) -> Unit) = onResult(grant)
    }

    private lateinit var kit: TrainingKit

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    @Test
    fun `switching reminders on schedules them ahead and off cancels them`() {
        val host = Recording()
        val zone = TimeZone.currentSystemDefault()
        val fixedNow = LocalDateTime(LocalDate(2026, 10, 14), LocalTime(6, 0)).toInstant(zone).toEpochMilliseconds()
        val reminders = BodyReminderScheduler(kit.service, host) { fixedNow }
        val p = AthleteSettingsPresenter(kit.service, kit.scope(), kit.dispatcher, reminders, host)
        kit.until("loaded") { p.state.value.loaded }
        val model = AthleteSettingsScreenModel(p, "en")
        assertTrue(model.currentState.rows.single { it.code == "reminders" }.available)
        model.setWeighReminder(true)
        kit.until("weigh scheduled") { host.scheduled.keys.count { it.startsWith(BodyReminderPlan.WEIGH_ID) } == BodyReminderPlan.WEIGH_AHEAD }
        // Default: Mondays at 07:30; the first one is next Monday, 2026-10-19.
        val first = host.scheduled.getValue(BodyReminderPlan.WEIGH_ID + "0")
        assertEquals("trr_notify_weigh_title", first.second)
        val expected = LocalDateTime(LocalDate(2026, 10, 19), LocalTime(7, 30)).toInstant(zone).toEpochMilliseconds()
        assertEquals(((expected - fixedNow) / 1000).toInt(), first.first)
        model.toggleWeighDay(3)
        kit.until("wednesday added") { 3 in kit.repo.profile().weighReminderDays }
        // Today (Wednesday) 07:30 is the first now.
        kit.until("rescheduled") { host.scheduled[BodyReminderPlan.WEIGH_ID + "0"]?.first ==
            ((LocalDateTime(LocalDate(2026, 10, 14), LocalTime(7, 30)).toInstant(zone).toEpochMilliseconds() - fixedNow) / 1000).toInt() }
        model.setMeasureReminder(true)
        model.setMeasureDay(40)
        kit.until("measure") { kit.repo.profile().measureReminderDayOfMonth == 28 && host.scheduled.containsKey(BodyReminderPlan.MEASURE_ID + "0") }
        // The page follows the stored profile once the presenter has re-read it.
        kit.until("page state") { p.state.value.profile.let { it.weighReminderEnabled && it.measureReminderEnabled && it.measureReminderDayOfMonth == 28 } }
        val s = model.currentState
        assertTrue(s.weighReminder && s.measureReminder)
        assertEquals(28, s.measureDay)
        assertEquals("trr_time", s.reminderTimeText.key)
        assertEquals("07:30", s.reminderTimeText.args.single().text)
        TrTextCheck.check(s.measureDayText, "measure day")
        s.weighDays.forEach { TrTextCheck.check(it.label, "weekday") }
        model.setWeighReminder(false)
        model.setMeasureReminder(false)
        kit.until("all cancelled") { host.scheduled.isEmpty() }
        assertFalse(model.currentState.remindersDenied)
    }

    @Test
    fun `a refused permission keeps the setting and says so and a weighed day is skipped`() {
        val host = Recording().apply { grant = false }
        val zone = TimeZone.currentSystemDefault()
        val today = kit.db { kit.service.today() }
        val fixedNow = LocalDateTime(today, LocalTime(0, 5)).toInstant(zone).toEpochMilliseconds()
        val reminders = BodyReminderScheduler(kit.service, host) { fixedNow }
        val p = AthleteSettingsPresenter(kit.service, kit.scope(), kit.dispatcher, reminders, host)
        kit.until("loaded") { p.state.value.loaded }
        val model = AthleteSettingsScreenModel(p, "en")
        // Every day; today's weight is already logged (the standard athlete).
        kit.db { kit.repo.updateProfile { it.copy(weighReminderDays = (1..7).toSet()) } }
        model.setWeighReminder(true)
        kit.until("denied") { p.state.value.remindersDenied }
        kit.until("saved anyway") { kit.repo.profile().weighReminderEnabled }
        kit.until("scheduled") { host.scheduled.size == BodyReminderPlan.WEIGH_AHEAD }
        // The first reminder is tomorrow, not today.
        assertTrue(host.scheduled.getValue(BodyReminderPlan.WEIGH_ID + "0").first > 24 * 3600 - 10 * 60)
        assertTrue(model.currentState.remindersDenied)
    }
}
