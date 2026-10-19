package com.cruxcoach.app.dayplan

import com.cruxcoach.app.library.LibraryDayStatus
import com.cruxcoach.app.library.LibraryWeek
import com.cruxcoach.app.library.WeekPlanPresenter
import com.cruxcoach.app.library.WorkoutsPresenter
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.LibraryText
import com.cruxcoach.app.ui.WeekPlanScreenModel
import com.cruxcoach.app.ui.WorkoutsScreenModel
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_FREE
import com.cruxcoach.athlete.model.PLAN_REST
import java.io.File
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The standard week offers climbing days, and the calendar shows a date's own plan over the standard week (Android
 * 570820eee `WeekPlanScreen`, `WeekCalendar.buildWeek`, `WorkoutsScreen`).
 */
class DayPlanLibraryTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service
    private val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private val strings: Set<String> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "iosApp/CruxCoach/Resources").isDirectory }
        listOf("en", "de").map { lang ->
            File(root, "iosApp/CruxCoach/Resources/$lang.lproj/Localizable.strings").readLines()
                .mapNotNull { Regex("^\"([^\"]+)\"\\s*=").find(it)?.groupValues?.get(1) }.toSet()
        }.reduce { a, b -> a intersect b }
    }

    /** The keys a [LibraryText] names, each checked against the en and de tables. */
    private fun keys(t: LibraryText): List<String> {
        val own = if (t.key.isEmpty()) emptyList() else listOf(t.key)
        own.forEach { assertTrue(it in strings, "missing string $it") }
        return own + t.parts.flatMap { keys(it) } + t.args.mapNotNull { it.nested }.flatMap { keys(it) } +
            t.args.filter { it.kind == "key" }.map { it.text }.onEach { assertTrue(it in strings, "missing string $it") }
    }

    @Test
    fun `the week shows a date's own plan over the standard week`() {
        val wednesday = LocalDate.parse("2026-10-14")
        val plan = mapOf(1 to "builtin:finger_basics", 3 to PLAN_BOARD, 5 to "builtin:core_climber")
        // Monday no time: rest; Wednesday a limit day instead of the board; Friday free; Saturday moved here.
        val datePlan = mapOf("2026-10-12" to PLAN_REST, "2026-10-14" to limit, "2026-10-16" to PLAN_FREE, "2026-10-17" to "builtin:core_climber")
        val week = LibraryWeek.build(wednesday, plan, emptyMap(), emptyList(), emptyList(), datePlan = datePlan)
        assertEquals(listOf(PLAN_REST, null, limit, null, null, "builtin:core_climber", null), week.map { it.planned })
        assertEquals(listOf(LibraryDayStatus.REST, LibraryDayStatus.FREE, LibraryDayStatus.PLANNED, LibraryDayStatus.FREE,
            LibraryDayStatus.FREE, LibraryDayStatus.PLANNED, LibraryDayStatus.FREE), week.map { it.status })
        // A climbing day is no routine to start or adapt.
        assertNull(LibraryWeek.plannedRoutine(limit, emptyList()))
        assertNull(LibraryWeek.plannedRoutine(PLAN_FREE, emptyList()))
        assertEquals("builtin:core_climber", LibraryWeek.plannedRoutine(week[5].planned, emptyList())?.id)
        // Without date plans the standard week stands.
        assertEquals(listOf("builtin:finger_basics", null, PLAN_BOARD, null, "builtin:core_climber", null, null),
            LibraryWeek.build(wednesday, plan, emptyMap(), emptyList(), emptyList()).map { it.planned })
    }

    @Test
    fun `the workouts week card names a planned climbing day by its intensity`() {
        val today = kit.db { service.today() }
        kit.db { repo.updateProfile { p -> DayPlans.withDay(p, today, limit, today) } }
        val presenter = WorkoutsPresenter(service, kit.dispatcher, kit.scope())
        val model = WorkoutsScreenModel(presenter, "en")
        kit.until("week") { presenter.state.value.week.any { it.date == today && it.planned == limit } }
        val day = model.currentState.week.first { it.isToday }
        assertTrue(day.plannedClimb)
        assertFalse(day.plannedBoard)
        assertEquals("planned", day.statusCode)
        assertEquals(listOf("trl_intensity_limit"), keys(day.shortPlanLabel))
        assertEquals(listOf("trl_kind_gym_boulder", "trl_intensity_limit"), keys(day.planLabel))
        assertFalse(day.canStart)
        assertFalse(day.canAdapt)
    }

    @Test
    fun `the standard week chooser plans a climbing day and refuses unknown codes`() {
        val presenter = WeekPlanPresenter(service, kit.dispatcher, kit.scope())
        val model = WeekPlanScreenModel(presenter)
        kit.until("loaded") { !presenter.state.value.loading }
        val ui = model.currentState
        assertEquals(listOf("gym_boulder", "gym_rope", "outdoor", "other"), ui.climbKinds.map { it.code })
        assertEquals(listOf("light", "volume", "hard", "limit"), ui.climbIntensities.map { it.code })
        (ui.climbKinds + ui.climbIntensities).forEach { keys(it.label) }
        assertFalse(ui.days[1].climbSelected)
        assertEquals("gym_boulder", ui.days[1].climbKind)
        assertEquals("volume", ui.days[1].climbIntensity)

        model.setDayClimb(2, "outdoor", "limit")
        model.setDayClimb(2, "other_board", "hard")
        model.setDayClimb(9, "outdoor", "hard")
        val rock = DayPlans.Climb(ClimbingDayKind.OUTDOOR, ClimbIntensity.LIMIT).entry
        kit.until("rock on Tuesday") { repo.profile().weekPlan[2] == rock }
        kit.until("shown") { model.currentState.days[1].climbSelected }
        assertEquals(mapOf(2 to rock), kit.db { repo.profile().weekPlan })
        val tuesday = model.currentState.days[1]
        assertEquals("outdoor", tuesday.climbKind)
        assertEquals("limit", tuesday.climbIntensity)
        assertEquals(listOf("trl_kind_outdoor", "trl_intensity_limit"), keys(tuesday.label))
        assertTrue(tuesday.options.none { it.selected })
        // The weekday's plan counts for the coach as a planned climbing day.
        val nextTuesday = generateSequence(kit.db { service.today() }) { it.plus(kotlinx.datetime.DatePeriod(days = 1)) }
            .first { it.dayOfWeek.isoDayNumber == 2 }
        assertTrue(kit.db { DayPlans.climbingPlanned(repo.profile(), nextTuesday) })
    }
}
