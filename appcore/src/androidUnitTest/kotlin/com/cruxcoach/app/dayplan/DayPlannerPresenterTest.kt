package com.cruxcoach.app.dayplan

import com.cruxcoach.app.training.TodayPresenter
import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.DayPlannerScreenModel
import com.cruxcoach.app.ui.TodayScreenModel
import com.cruxcoach.app.ui.TrText
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_REST
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner 2026-10-19: plan climbing days, build the rest around them, move days – "no time today: rest; a volume
 * boulder day tomorrow, a limit day two days later" – and the rest adjusts. Ports Android's `DayPlannerTest` (and
 * the planned-day cases of `TrainingUiReviewTest`) at the presenter and facade level, against a real database.
 */
class DayPlannerPresenterTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service
    private val fingers = "builtin:" + BuiltinRoutines.FINGER_BASICS

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun today() = kit.db { service.today() }

    private fun planner(): Pair<DayPlannerPresenter, DayPlannerScreenModel> {
        val vm = DayPlannerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("planner loaded") { !vm.state.value.loading && vm.state.value.days.size == PLANNER_DAYS }
        return vm to DayPlannerScreenModel(vm)
    }

    private fun keys(t: TrText): List<String> = listOf(t.key) + t.args.mapNotNull { it.nested }.flatMap { keys(it) }

    @Test
    fun `the owner's example is three taps per day and the days around it follow`() {
        val today = today()
        val d1 = today.plus(DatePeriod(days = 1))
        val d3 = today.plus(DatePeriod(days = 3))
        kit.db { repo.updateProfile { it.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to fingers)) } }
        val (vm, model) = planner()

        model.setDay(today.toString(), PLAN_REST)
        kit.until("rest today") { repo.profile().datePlan[today.toString()] == PLAN_REST }
        // The finger training that gave way gets a free day offered; nothing moves unasked.
        kit.until("move offer") { vm.state.value.moveOffer != null }
        assertEquals(fingers, vm.state.value.moveOffer!!.entry)
        assertTrue(model.currentState.hasMoveOffer)

        model.setClimb(d1.toString(), "gym_boulder", "volume")
        val volume = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.VOLUME).entry
        kit.until("volume tomorrow") { repo.profile().datePlan[d1.toString()] == volume }

        model.setClimb(d3.toString(), "gym_boulder", "limit")
        val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
        kit.until("limit in three days") { repo.profile().datePlan[d3.toString()] == limit }

        // The free day before the limit day keeps fingers and pulling fresh; the day after it recovers.
        kit.until("before limit") { vm.state.value.days.getOrNull(2)?.hint == DayPlans.Hint.BEFORE_LIMIT }
        assertEquals(DayPlans.Hint.AFTER_HARD, vm.state.value.days[4].hint)
        val ui = model.currentState
        assertTrue("trdp_hint_before_limit" in keys(ui.days[2].detail), "${keys(ui.days[2].detail)}")
        assertTrue(ui.days[2].detailAccent)
        assertEquals(listOf("trl_kind_gym_boulder", "trl_intensity_limit"), keys(ui.days[3].label).filter { it.isNotEmpty() })
        assertTrue(ui.days[3].overridden && ui.days[3].climbSelected)
        assertEquals("limit", ui.days[3].climbIntensity)
        // A date of its own says so, and its chooser offers the standard week back.
        assertTrue("trdp_only_this_day" in keys(ui.days[3].detail))
        assertEquals("trdp_standard", ui.days[3].standardLabel.key)
        // Swaps stay within a week either way.
        assertEquals(6, ui.days[0].swapTargets.size)
        assertTrue(ui.days[3].swapTargets.none { it == ui.days[3].isoDate })
        assertEquals(9, ui.days[3].swapTargets.size)
        assertTrue(TrTextCheck.checkAll(ui) > 20)

        // "Undo" takes back the last change only.
        assertTrue(model.currentState.hasChange)
        model.undo()
        kit.until("undone") { repo.profile().datePlan[d3.toString()] == null }
        assertEquals(volume, kit.db { repo.profile().datePlan[d1.toString()] })
        kit.until("change gone") { !model.currentState.hasChange }
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `a training that gave way moves on one tap, and a swap moves both days`() {
        val today = today()
        kit.db { repo.updateProfile { it.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to fingers)) } }
        val (vm, model) = planner()
        model.setDay(today.toString(), PLAN_REST)
        kit.until("move offer") { vm.state.value.moveOffer != null }
        val target = vm.state.value.moveOffer!!.to
        assertEquals(target.toString(), model.currentState.moveToIso)
        assertEquals(today.toString(), model.currentState.moveFromIso)
        model.acceptMove()
        kit.until("moved") { DayPlans.entry(repo.profile(), target) == fingers }
        assertFalse(model.currentState.hasMoveOffer)

        val later = target.plus(DatePeriod(days = 1))
        kit.until("planner shows the move") { vm.state.value.days.any { it.date == target && it.entry == fingers } }
        model.swap(target.toString(), later.toString())
        kit.until("swapped") { DayPlans.entry(repo.profile(), later) == fingers }
        assertNull(kit.db { DayPlans.entry(repo.profile(), target) })
    }

    @Test
    fun `leaving the offer out moves nothing`() {
        val today = today()
        kit.db { repo.updateProfile { it.copy(weekPlan = mapOf(today.dayOfWeek.isoDayNumber to fingers)) } }
        val (vm, model) = planner()
        model.setDay(today.toString(), PLAN_REST)
        kit.until("move offer") { vm.state.value.moveOffer != null }
        val plan = kit.db { repo.profile().datePlan }
        model.dismissMove()
        kit.until("offer gone") { vm.state.value.moveOffer == null }
        assertEquals(plan, kit.db { repo.profile().datePlan })
    }

    @Test
    fun `what Swift passes in is checked before anything is planned`() {
        val today = today()
        val (vm, model) = planner()
        val far = today.plus(DatePeriod(days = PLANNER_DAYS + 3))
        model.setDay(far.toString(), PLAN_REST)
        model.setDay("not a date", PLAN_REST)
        model.setDay(today.toString(), "builtin:no_such_routine")
        model.setDay(today.toString(), "climb:gym_boulder:limit")
        model.setClimb(today.toString(), "other_board", "limit")
        model.setClimb(today.toString(), "gym_boulder", "maximal")
        // Ten days apart is beyond a week either way.
        model.swap(today.toString(), today.plus(DatePeriod(days = 10)).toString())
        // A valid change afterwards proves the refused ones were not just slow.
        model.setDay(today.plus(DatePeriod(days = 2)).toString(), "board")
        kit.until("board planned") { repo.profile().datePlan.isNotEmpty() }
        assertEquals(mapOf(today.plus(DatePeriod(days = 2)).toString() to "board"), kit.db { repo.profile().datePlan })
        assertNull(vm.state.value.moveOffer)
        // "Free" over nothing planned needs no entry of its own.
        model.setDay(today.plus(DatePeriod(days = 2)).toString(), "")
        kit.until("free again") { repo.profile().datePlan.isEmpty() }
    }

    @Test
    fun `rest day today from the card, with Undo`() {
        kit.db { repo.updateProfile { it.copy(fuelIntroAccepted = true, checkinEnabled = false) } }
        val vm = TodayPresenter(service, kit.scope(), kit.dispatcher)
        val model = TodayScreenModel(vm, "en")
        kit.until("suggestion") { vm.state.value.suggestion != null }
        assertTrue(model.currentState.canRestToday)
        model.restToday()
        val today = today().toString()
        kit.until("rest planned") { repo.profile().datePlan[today] == PLAN_REST }
        kit.until("rest card") { vm.state.value.suggestion?.focus == SuggestionFocus.REST }
        kit.until("undo offered") { model.currentState.hasRestTodayUndo }
        val ui = model.currentState
        assertTrue(ui.hasRestTodayUndo)
        assertFalse(ui.canRestToday)
        // Planned for this date only: not "your week plan" (device check 2026-10-19).
        assertEquals("trdp_reason_day_rest", ui.firstReason.key)
        TrTextCheck.checkAll(ui)
        val undo = assertNotNull(model.takeRestTodayUndo())
        assertNull(model.takeRestTodayUndo(), "the undo is handed out once")
        undo.restore()
        kit.until("rest undone") { repo.profile().datePlan[today] == null }
        kit.until("training back") { vm.state.value.suggestion?.focus != SuggestionFocus.REST }
    }

    @Test
    fun `a planned climbing day's card is its warm-up, named and without the time picker`() {
        val today = today()
        val limit = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.LIMIT).entry
        kit.db {
            repo.updateProfile { p ->
                DayPlans.withDay(p.copy(fuelIntroAccepted = true, checkinEnabled = false), today, limit, today)
            }
        }
        val vm = TodayPresenter(service, kit.scope(), kit.dispatcher)
        val model = TodayScreenModel(vm, "en")
        kit.until("climbing card") { vm.state.value.suggestion?.plannedEntry == limit }
        val ui = model.currentState
        assertTrue(ui.climbDay)
        assertFalse(ui.timeAdjustable)
        assertEquals(listOf("trl_kind_gym_boulder", "trl_intensity_limit"), keys(ui.suggestionTitle).filter { it.isNotEmpty() })
        assertEquals("trdp_reason_planned_climb", ui.firstReason.key)
        // The week strip shows the planned climbing on today and says so to screen readers.
        val day = ui.week.first { it.isToday }
        assertEquals("climb", day.planCode)
        assertEquals("trw_day_planned", day.plannedText.key)
        TrTextCheck.checkAll(ui)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `a hard day planned yesterday without a log asks to log it`() {
        val today = today()
        val yesterday = today.plus(DatePeriod(days = -1))
        val hard = DayPlans.Climb(ClimbingDayKind.GYM_BOULDER, ClimbIntensity.HARD).entry
        kit.db {
            repo.updateProfile { p ->
                DayPlans.withDay(p.copy(fuelIntroAccepted = true, checkinEnabled = false), yesterday, hard, today)
            }
        }
        val vm = TodayPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("suggestion") { vm.state.value.suggestion != null }
        assertTrue(SuggestionReason.PLANNED_CLIMB_NOT_LOGGED in vm.state.value.suggestion!!.reasons, "${vm.state.value.suggestion!!.reasons}")
        assertTrue(TodayScreenModel(vm, "en").currentState.reasons.any { it.key == "trdp_reason_not_logged" })
    }
}
