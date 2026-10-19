// Port of androidApp/.../ui/training/workouts/DayPlanner.kt (`DayPlannerViewModel`, 570820eee + e2bc7c731) for the
// iOS app. Kept close to the Android file; the platform seams differ: an injected scope and IO dispatcher instead
// of viewModelScope/Dispatchers.IO.
package com.cruxcoach.app.dayplan

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_FREE
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Routine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/** How far ahead the planner shows days (owner 2026-10-19: "two days later", also over a weekend). */
internal const val PLANNER_DAYS = 14

/** Swaps are offered within this many days either way (device check 2026-10-19: the chooser stays short). */
internal const val SWAP_RANGE_DAYS = 6

internal data class DayPlannerState(
    val loading: Boolean = true,
    val today: LocalDate? = null,
    val profile: AthleteProfile = AthleteProfile(),
    val routines: List<Routine> = emptyList(),
    /** Today and the next days: the plan in force and how the coach fits the free days around the climbing. */
    val days: List<DayPlans.Day> = emptyList(),
    val warnings: List<DayPlans.Warning> = emptyList(),
    /** A training that gave way to the last change, with the free day the coach found for it. */
    val moveOffer: MoveOffer? = null,
    /** The last change, for "Rückgängig". */
    val lastChange: PlanChange? = null,
)

internal data class MoveOffer(val entry: String, val from: LocalDate, val to: LocalDate)

/** [entry] as written on [date] (null = back to the standard week); [before] is every date plan before it. */
internal data class PlanChange(val date: LocalDate, val entry: String?, val before: Map<String, String>)

/**
 * Plans single days (owner 2026-10-19): "today no time – rest; a volume boulder day tomorrow, a limit day two days
 * later". Each change is this date's own entry over the standard week; the coach shapes the free days around it.
 */
internal class DayPlannerPresenter(
    private val service: AthleteService,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(DayPlannerState())
    val state: StateFlow<DayPlannerState> = _state.asStateFlow()
    private val pausesNow = MutableStateFlow<List<PausePeriod>>(emptyList())

    init {
        scope.launch(io) {
            service.ensureReady()
            val repo = service.repo
            val from = service.today().minus(DatePeriod(days = 3)).toString()
            combine(repo.observeProfile(), repo.observeRoutines(), repo.observePauses(), service.observeActivityChanges(from)) { p, r, pauses, _ ->
                Triple(p, r, pauses)
            }.collect { (profile, routines, pauses) ->
                pausesNow.value = pauses
                runCatching { compute(profile, routines, pauses) }
            }
        }
    }

    private fun compute(profile: AthleteProfile, routines: List<Routine>, pauses: List<PausePeriod>) {
        val today = service.today()
        val logged = service.activities(days = 3).mapNotNull { (day, a) ->
            if (a.climbingMinutes <= 0 && a.climbingEfforts <= 0) null
            else runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to a.climbIntensity }
        }.toMap<LocalDate, ClimbIntensity?>()
        val days = DayPlans.preview(profile, today, PLANNER_DAYS, today, pauses, logged)
        val warnings = DayPlans.warnings(days, today) { fingerWork(it, routines) }
        _state.update { it.copy(loading = false, today = today, profile = profile, routines = routines, days = days, warnings = warnings) }
    }

    /** A planned training trains the fingers (a hangboard or block exercise beyond the warm-up). */
    private fun fingerWork(entry: String, routines: List<Routine>): Boolean {
        val routine = when {
            entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))
            else -> routines.firstOrNull { it.id == entry }
        } ?: return false
        return routine.items.any { !it.warmup && LoadDomain.FINGER in service.catalog.fallbackFor(it.slug).domains }
    }

    private fun isTraining(entry: String?) =
        entry != null && entry != PLAN_REST && entry != PLAN_BOARD && entry != PLAN_FREE && !DayPlans.isClimb(entry)

    /** Plans [date] as [entry] ([PLAN_FREE] = nothing, null = as in the standard week). */
    fun set(date: LocalDate, entry: String?) = io {
        val today = service.today()
        var before: Map<String, String> = emptyMap()
        var displaced: String? = null
        var after: AthleteProfile? = null
        service.repo.updateProfile { p ->
            before = p.datePlan
            displaced = DayPlans.entry(p, date)
            DayPlans.withDay(p, date, entry, today).also { after = it }
        }
        val now = after ?: return@io
        val gone = displaced?.takeIf { isTraining(it) && DayPlans.entry(now, date) != it }
        // The training that had to give way: the coach offers the next free day that fits it; nothing moves unasked.
        val offer = gone?.let { e ->
            DayPlans.freeDayFor(now, date, today, fingerWork(e, _state.value.routines), pausesNow.value)?.let { MoveOffer(e, date, it) }
        }
        _state.update { it.copy(lastChange = PlanChange(date, entry, before), moveOffer = offer) }
    }

    /** Swaps what [a] and [b] plan ("move this day"). */
    fun swap(a: LocalDate, b: LocalDate) = io {
        val today = service.today()
        var before: Map<String, String> = emptyMap()
        service.repo.updateProfile { p -> before = p.datePlan; DayPlans.swap(p, a, b, today) }
        _state.update { it.copy(lastChange = PlanChange(b, null, before), moveOffer = null) }
    }

    fun acceptMove() {
        val offer = _state.value.moveOffer ?: return
        io {
            val today = service.today()
            var before: Map<String, String> = emptyMap()
            service.repo.updateProfile { p -> before = p.datePlan; DayPlans.withDay(p, offer.to, offer.entry, today) }
            _state.update { it.copy(lastChange = PlanChange(offer.to, offer.entry, before), moveOffer = null) }
        }
    }

    fun dismissMove() = _state.update { it.copy(moveOffer = null) }

    fun undo() {
        val change = _state.value.lastChange ?: return
        io {
            service.repo.updateProfile { it.copy(datePlan = change.before) }
            _state.update { it.copy(lastChange = null, moveOffer = null) }
        }
    }

    /** Ends the presenter's work; the scope belongs to it. */
    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launch(io) { service.ensureReady(); block() }
    }
}
