package com.cruxcoach.app.trainingreminder

import com.cruxcoach.app.notify.NotificationRoutes
import com.cruxcoach.app.notify.NotificationScheduler
import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.app.ui.TrainingReminderSettingsModel
import com.cruxcoach.app.ui.TrainingRemindersModel
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.PauseReason
import com.cruxcoach.athlete.model.Routine
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Training-day reminders: Android's rules (TrainingReminders + WeekPlanReminderWorker), scheduled ahead. */
class TrainingReminderPlanTest {
    private val utc = TimeZone.UTC
    // 2026-10-14 is a Wednesday (ISO 3).
    private val wedMorning = LocalDateTime(LocalDate(2026, 10, 14), LocalTime(7, 0))
    private val builtin = "builtin:" + BuiltinRoutines.all.first { it.builtinKey != null }.builtinKey

    private fun profile(plan: Map<Int, String>, board: Boolean = true, enabled: Boolean = true) = AthleteProfile(
        weekPlan = plan, trainingReminderEnabled = enabled, trainingReminderMinutes = 17 * 60, trainingReminderOnClimbingDays = board,
    )

    @Test
    fun `the planned entry skips rest days and board days unless wanted`() {
        val p = profile(mapOf(1 to builtin, 3 to PLAN_BOARD, 5 to PLAN_REST, 6 to "own1"))
        assertEquals(builtin, TrainingReminderPlan.plannedEntry(p, 1))
        assertNull(TrainingReminderPlan.plannedEntry(p, 2))
        assertEquals(PLAN_BOARD, TrainingReminderPlan.plannedEntry(p, 3))
        assertNull(TrainingReminderPlan.plannedEntry(p, 5))
        assertEquals("own1", TrainingReminderPlan.plannedEntry(p, 6))
        assertNull(TrainingReminderPlan.plannedEntry(p.copy(trainingReminderOnClimbingDays = false), 3))
    }

    @Test
    fun `the next occurrence is today while ahead and otherwise tomorrow`() {
        assertEquals(LocalDateTime(LocalDate(2026, 10, 14), LocalTime(17, 0)),
            TrainingReminderPlan.nextOccurrence(wedMorning, 17 * 60, utc))
        val evening = LocalDateTime(LocalDate(2026, 10, 14), LocalTime(18, 0))
        assertEquals(LocalDateTime(LocalDate(2026, 10, 15), LocalTime(17, 0)),
            TrainingReminderPlan.nextOccurrence(evening, 17 * 60, utc))
        // Within 30 s counts as now: tomorrow.
        val almost = LocalDateTime(LocalDate(2026, 10, 14), LocalTime(16, 59, 45))
        assertEquals(LocalDate(2026, 10, 15), TrainingReminderPlan.nextOccurrence(almost, 17 * 60, utc).date)
        // Out-of-range minutes are clamped like Android.
        assertEquals(LocalTime(23, 59), TrainingReminderPlan.nextOccurrence(wedMorning, 99_999, utc).time)
    }

    @Test
    fun `planned days ahead leave out skipped days and deleted routines`() {
        // Mon builtin, Wed board, Fri rest, Sat own routine, Sun a routine deleted since.
        val p = profile(mapOf(1 to builtin, 3 to PLAN_BOARD, 5 to PLAN_REST, 6 to "own1", 7 to "gone"))
        val ahead = TrainingReminderPlan.upcoming(wedMorning, p, utc, 8, emptySet(), setOf("own1"))
        assertEquals(listOf(14, 17, 19, 21, 24, 26).map { LocalDate(2026, 10, it) }, ahead.map { it.at.date })
        assertTrue(ahead.all { it.at.time == LocalTime(17, 0) })
        assertEquals(listOf(PLAN_BOARD, "own1", builtin), ahead.take(3).map { it.entry })
        // Today already trained: from Saturday on; without board days Wednesdays drop.
        assertEquals(LocalDate(2026, 10, 17),
            TrainingReminderPlan.upcoming(wedMorning, p, utc, 8, setOf(LocalDate(2026, 10, 14)), setOf("own1")).first().at.date)
        assertTrue(TrainingReminderPlan.upcoming(wedMorning, p.copy(trainingReminderOnClimbingDays = false), utc, 8, emptySet(), setOf("own1"))
            .none { it.entry == PLAN_BOARD })
        // At most the requested count, and nothing while switched off or without a plan.
        val daily = profile((1..7).associateWith { builtin })
        assertEquals(8, TrainingReminderPlan.upcoming(wedMorning, daily, utc, 8, emptySet(), emptySet()).size)
        assertTrue(TrainingReminderPlan.upcoming(wedMorning, daily.copy(trainingReminderEnabled = false), utc, 8, emptySet(), emptySet()).isEmpty())
        assertTrue(TrainingReminderPlan.upcoming(wedMorning, profile(emptyMap()), utc, 8, emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun `the title names the routine as Android does and a deleted one gets none`() {
        val own = listOf(Routine(id = "own1", name = "Fingers at home", items = emptyList()))
        // Board day: "Today: Board session".
        val board = TrainingReminderPlan.title(PLAN_BOARD, own)!!
        assertEquals("trw_notify_title", board.key)
        assertEquals("trw_notify_board", board.args.single().nested!!.key)
        // A built-in routine: its localized name (Android `builtinRoutineNameRes`).
        val key = builtin.removePrefix("builtin:")
        val named = TrainingReminderPlan.title(builtin, own)!!
        assertEquals("tr_routine_$key", named.args.single().nested!!.key)
        // An own routine: its stored name as it is.
        val mine = TrainingReminderPlan.title("own1", own)!!.args.single().nested!!
        assertEquals("", mine.key)
        assertEquals("Fingers at home", mine.args.single().text)
        assertEquals("text", mine.args.single().kind)
        // Deleted since it was planned, or an unknown built-in: say nothing rather than something wrong.
        assertNull(TrainingReminderPlan.title("gone", own))
        assertNull(TrainingReminderPlan.title("builtin:no_such_routine", own))
        listOf(board, named, TrainingReminderPlan.title("own1", own)!!).forEach { TrTextCheck.check(it, "reminder title") }
        assertTrue(NotificationRoutes.isKnown(NotificationRoutes.TODAY))
        assertFalse(NotificationRoutes.isKnown("board_sync?localDbUrl=x"))
        assertFalse(NotificationRoutes.isKnown(""))
    }

    private class Recording : NotificationScheduler {
        val scheduled = linkedMapOf<String, Triple<Int, String, String>>()
        val titles = mutableMapOf<String, TrText>()
        val routes = mutableMapOf<String, String>()
        var plain = 0
        var grant = true
        override fun schedule(id: String, afterSeconds: Int, titleKey: String, bodyKey: String) {
            plain++
            scheduled[id] = Triple(afterSeconds, titleKey, bodyKey)
        }
        override fun scheduleText(id: String, afterSeconds: Int, title: TrText, body: TrText, route: String) {
            scheduled[id] = Triple(afterSeconds, title.key, body.key)
            titles[id] = title
            routes[id] = route
        }
        override fun cancel(id: String) { scheduled -= id; titles -= id; routes -= id }
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
    fun `the scheduler fills the slots and a pause or switching off clears them`() {
        val host = Recording()
        val zone = TimeZone.currentSystemDefault()
        val today = kit.db { kit.service.today() }
        val now = LocalDateTime(today, LocalTime(6, 0))
        val nowMs = now.toInstant(zone).toEpochMilliseconds()
        val scheduler = TrainingReminderScheduler(kit.service, host) { nowMs }
        val plan = (1..7).associateWith { builtin }
        kit.db { kit.repo.updateProfile { it.copy(weekPlan = plan, trainingReminderEnabled = true, trainingReminderMinutes = 17 * 60) } }
        kit.db { scheduler.reschedule(kit.repo.profile()) }
        assertEquals((0 until TrainingReminderPlan.AHEAD).map { TrainingReminderPlan.ID + it }.toSet(), host.scheduled.keys)
        val first = host.scheduled.getValue(TrainingReminderPlan.ID + "0")
        assertEquals(11 * 3600, first.first)
        assertEquals("trw_notify_title", first.second)
        assertEquals("trw_notify_text", first.third)
        // "Today: <routine>" with the built-in routine's name; a tap opens Today.
        val title = host.titles.getValue(TrainingReminderPlan.ID + "0")
        assertEquals("tr_routine_" + builtin.removePrefix("builtin:"), title.args.single().nested!!.key)
        TrTextCheck.check(title, "scheduled title")
        assertTrue(host.routes.values.all { it == NotificationRoutes.TODAY })
        assertEquals(TrainingReminderPlan.AHEAD, host.routes.size)
        assertEquals(0, host.plain)
        val second = host.scheduled.getValue(TrainingReminderPlan.ID + "1")
        assertEquals(((LocalDateTime(today.plus(DatePeriod(days = 1)), LocalTime(17, 0)).toInstant(zone).toEpochMilliseconds() - nowMs) / 1000).toInt(),
            second.first)
        // A training running today: today is left out.
        kit.db { kit.service.startWorkout(null, null) }
        kit.db { scheduler.reschedule(kit.repo.profile()) }
        assertTrue(host.scheduled.getValue(TrainingReminderPlan.ID + "0").first > 24 * 3600)
        // An open pause: nothing until it ends.
        kit.db { kit.repo.savePause(PausePeriod("p1", PauseReason.ILLNESS, today.toString())) }
        kit.db { scheduler.reschedule(kit.repo.profile()) }
        assertTrue(host.scheduled.isEmpty())
    }

    @Test
    fun `the settings section stores schedules and keeps a refused permission`() {
        val host = Recording().apply { grant = false }
        val zone = TimeZone.currentSystemDefault()
        val today = kit.db { kit.service.today() }
        val nowMs = LocalDateTime(today, LocalTime(6, 0)).toInstant(zone).toEpochMilliseconds()
        val scheduler = TrainingReminderScheduler(kit.service, host) { nowMs }
        val p = TrainingReminderSettingsPresenter(kit.service, kit.scope(), kit.dispatcher, scheduler, host)
        kit.until("loaded") { p.state.value.loaded }
        val model = TrainingReminderSettingsModel(p)
        assertFalse(model.currentState.enabled)
        assertFalse(model.currentState.hasWeekPlan)
        assertEquals(17 * 60, model.currentState.minutes)
        assertEquals("17:00", model.currentState.timeText.args.single().text)
        model.setEnabled(true)
        kit.until("saved anyway") { kit.repo.profile().trainingReminderEnabled }
        kit.until("denied") { p.state.value.denied }
        // No week plan yet: nothing to remind of.
        assertTrue(host.scheduled.isEmpty())
        kit.db { kit.repo.updateProfile { it.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to PLAN_BOARD)) } }
        model.setMinutes(7 * 60 + 5)
        kit.until("scheduled") { host.scheduled.containsKey(TrainingReminderPlan.ID + "0") }
        assertEquals(65 * 60, host.scheduled.getValue(TrainingReminderPlan.ID + "0").first)
        kit.until("page state") { p.state.value.profile.trainingReminderMinutes == 7 * 60 + 5 }
        val s = model.currentState
        assertTrue(s.enabled && s.hasWeekPlan && s.denied && s.onBoardDays)
        assertEquals("trr_time", s.timeText.key)
        assertEquals("07:05", s.timeText.args.single().text)
        TrTextCheck.check(s.timeText, "time")
        model.setBoardDays(false)
        kit.until("board days off") { host.scheduled.isEmpty() }
        model.setBoardDays(true)
        kit.until("back") { host.scheduled.isNotEmpty() }
        model.setEnabled(false)
        kit.until("all cancelled") { host.scheduled.isEmpty() && !kit.repo.profile().trainingReminderEnabled }
    }

    @Test
    fun `following the plan reschedules when the week plan or a pause changes`() {
        val host = Recording()
        val zone = TimeZone.currentSystemDefault()
        val today = kit.db { kit.service.today() }
        val nowMs = LocalDateTime(today, LocalTime(6, 0)).toInstant(zone).toEpochMilliseconds()
        kit.db { kit.repo.updateProfile { it.copy(trainingReminderEnabled = true) } }
        val model = TrainingRemindersModel(kit.service, TrainingReminderScheduler(kit.service, host) { nowMs }, kit.scope(), kit.dispatcher)
        val subscription = model.follow()
        // A week plan saved anywhere (here straight into the profile) schedules the days ahead.
        kit.db { kit.repo.updateProfile { it.copy(weekPlan = (1..7).associateWith { builtin }) } }
        kit.until("scheduled") { host.scheduled.size == TrainingReminderPlan.AHEAD }
        kit.db { kit.repo.savePause(PausePeriod("p1", PauseReason.HOLIDAY, today.toString())) }
        kit.until("paused") { host.scheduled.isEmpty() }
        // The pause removed again: the days come back.
        kit.db { kit.repo.deletePause("p1") }
        kit.until("resumed") { host.scheduled.size == TrainingReminderPlan.AHEAD }
        subscription.cancel()
    }
}
