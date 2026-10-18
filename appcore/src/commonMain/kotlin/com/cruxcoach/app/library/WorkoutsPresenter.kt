package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.ReturnState
import com.cruxcoach.athlete.logic.ReturnToTraining
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.logic.DayActivity
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.Workout
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt

/** What a day of the current week stands for (Android `WeekDayStatus`). */
enum class LibraryDayStatus { DONE, PLANNED, MISSED, PAUSED, REST, FREE }

/** One day of the current week (Android `WeekDayCell`). */
data class LibraryWeekCell(
    val date: LocalDate,
    /** Week-plan entry: routine id ("builtin:<key>" or own), [PLAN_BOARD], [PLAN_REST] or null. */
    val planned: String?,
    val activity: DayActivity?,
    val workouts: List<Workout>,
    val paused: Boolean,
    val isToday: Boolean,
    val status: LibraryDayStatus,
)

internal object LibraryWeek {
    /** Monday-to-Sunday cells of the week holding [today]: plan, what was done, what was missed. */
    fun build(
        today: LocalDate,
        weekPlan: Map<Int, String>,
        activities: Map<String, DayActivity>,
        workouts: List<Workout>,
        pauses: List<PausePeriod>,
    ): List<LibraryWeekCell> {
        val monday = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
        return (0 until 7).map { offset ->
            val date = monday.plus(DatePeriod(days = offset))
            val key = date.toString()
            val planned = weekPlan[date.dayOfWeek.isoDayNumber]
            val activity = activities[key]
            val dayWorkouts = workouts.filter { it.day == key && !it.isOpen }
            val paused = pauses.any { p ->
                val start = runCatching { LocalDate.parse(p.startDay) }.getOrNull()
                val end = p.endDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
                start != null && date in start..end && date <= today
            }
            val done = activity?.trained == true || dayWorkouts.isNotEmpty()
            val status = when {
                done -> LibraryDayStatus.DONE
                paused -> LibraryDayStatus.PAUSED
                planned == PLAN_REST -> LibraryDayStatus.REST
                planned != null && date < today -> LibraryDayStatus.MISSED
                planned != null -> LibraryDayStatus.PLANNED
                else -> LibraryDayStatus.FREE
            }
            LibraryWeekCell(date, planned, activity, dayWorkouts, paused, date == today, status)
        }
    }

    /** The routine a plan entry stands for; board, rest and free days have none. */
    fun plannedRoutine(entry: String?, routines: List<Routine>): Routine? = entry?.let {
        when {
            it == PLAN_BOARD || it == PLAN_REST -> null
            it.startsWith("builtin:") -> BuiltinRoutines.byKey(it.removePrefix("builtin:"))
            else -> routines.firstOrNull { r -> r.id == it }
        }
    }
}

internal object LibraryRoutines {
    /** Rough duration: work per set plus the rest after it (Android `estimatedMinutes`). */
    fun estimatedMinutes(routine: Routine): Int {
        val seconds = routine.items.sumOf { item ->
            val work = when {
                item.workS != null -> (item.workS!! + (item.restBetweenS ?: 0)) * (item.repsPerSet ?: 1)
                item.durationS != null -> item.durationS!!
                else -> (item.repsMax ?: item.repsMin ?: 8) * 3
            }
            item.sets.coerceAtLeast(1) * (work + (item.restS ?: 60))
        }
        return (seconds / 60.0).roundToInt().coerceAtLeast(1)
    }
}

/** A start waiting for "continue the open training or end it" (Android `PendingStart`). */
data class PendingWorkoutStart(
    val conflict: AthleteService.OpenConflict,
    /** Title of the training about to start; null for an empty one. */
    val newTitle: String?,
    /** Null: a free training. */
    val routine: Routine?,
    /** What the start passes to [AthleteService.startWorkout] as the title. */
    val startTitle: String?,
)

data class WorkoutsState(
    val loading: Boolean = true,
    val routines: List<Routine> = emptyList(),
    val profile: AthleteProfile = AthleteProfile(),
    val openWorkout: Workout? = null,
    val catalog: ExerciseCatalog = ExerciseCatalog.EMPTY,
    val today: LocalDate? = null,
    /** Monday to Sunday of the current week: plan and status. */
    val week: List<LibraryWeekCell> = emptyList(),
    /** Re-entry ramp after a break or a healed finger injury. */
    val returnState: ReturnState? = null,
    val pendingStart: PendingWorkoutStart? = null,
    /** One-shot: the training that was just started or continued; null when none is waiting. */
    val startedWorkoutId: String? = null,
    /** The started one was a free training (it has no sets yet). */
    val startedFree: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Port of Android `WorkoutsViewModel`: the athlete's own workouts, the nine
 * starter routines, the week and the open-training conflict on every start.
 */
class WorkoutsPresenter(
    private val service: AthleteService,
    private val io: CoroutineDispatcher,
    private val scope: CoroutineScope = libraryScope(),
) {
    private val _state = MutableStateFlow(WorkoutsState())
    val state: StateFlow<WorkoutsState> = _state.asStateFlow()

    /** Board sessions live in another database; a return to the tab recomputes the week. */
    private val weekTick = MutableStateFlow(0)

    private val fail: (Throwable) -> Unit = { _state.update { s -> s.copy(loading = false, failed = true) } }

    init {
        scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val repo = service.repo
                combine(
                    repo.observeRoutines(),
                    repo.observeProfile(),
                    repo.observeOpenWorkout(),
                    service.catalogStore.catalog,
                ) { routines, profile, open, catalog -> Base(routines, profile, open, catalog) }
                    .collect { b ->
                        // Keeps the week fields, which the second collector owns.
                        _state.update { it.copy(loading = false, routines = b.routines, profile = b.profile, openWorkout = b.open, catalog = b.catalog) }
                    }
            }
        }
        scope.launchGuarded(fail) {
            withContext(io) {
                service.ensureReady()
                val repo = service.repo
                val from = service.today().minus(DatePeriod(days = ACTIVITY_DAYS)).toString()
                combine(
                    repo.observeProfile(),
                    repo.observeRecentWorkouts(30),
                    repo.observeClimbingDaysSince(from),
                    repo.observePauses(),
                    weekTick,
                ) { profile, _, _, pauses, _ -> profile to pauses }
                    .collect { (profile, pauses) -> runCatching { computeWeek(profile, pauses) } }
            }
        }
    }

    private data class Base(val routines: List<Routine>, val profile: AthleteProfile, val open: Workout?, val catalog: ExerciseCatalog)

    fun refreshWeek() { weekTick.update { it + 1 } }

    private fun computeWeek(profile: AthleteProfile, pauses: List<PausePeriod>) {
        val repo = service.repo
        val today = service.today()
        val activities = service.activities(days = ACTIVITY_DAYS)
        val monday = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
        val sunday = monday.plus(DatePeriod(days = 6))
        val week = LibraryWeek.build(today, profile.weekPlan, activities, repo.workoutsBetween(monday.toString(), sunday.toString()), pauses)
        // The week shows even if the re-entry check below fails.
        _state.update { it.copy(today = today, week = week) }
        // Max-finger work before the break decides whether a long ramp's second week allows it again.
        val since = today.minus(DatePeriod(days = 120))
        val workoutDays = repo.workoutsBetween(since.toString(), today.toString()).associate { it.id to it.day }
        val sets = repo.completedSetsSince(nowMs() - 120L * 86_400_000L)
            .mapNotNull { s -> workoutDays[s.workoutId]?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }?.let { it to s } }
        val maxBefore = ReturnToTraining.maxFingerBefore(sets, service.catalog, today)
        val ret = ReturnToTraining.state(activities, pauses, repo.allInjuries(), today, maxBefore)
        _state.update { it.copy(returnState = ret) }
    }

    /** Starts [routine]; with another training open the athlete chooses first (continue or end it). */
    fun start(routine: Routine, title: String?) = io {
        val conflict = service.openConflict(routine.id)
        if (conflict != null) _state.update { it.copy(pendingStart = PendingWorkoutStart(conflict, title ?: routine.name, routine, title)) }
        else begin(routine, title, replace = false)
    }

    /** A training without a routine (device test 2026-10-10: it was only in Today's menu). */
    fun startFree(title: String) = io {
        val conflict = service.openConflict(null)
        if (conflict != null) _state.update { it.copy(pendingStart = PendingWorkoutStart(conflict, title, null, null)) }
        else begin(null, null, replace = false)
    }

    /**
     * true: end the open training and start; false: continue the open one; null: start nothing.
     * The question is taken on the calling thread, so a second answer (a dialog's dismissal right
     * after its button) finds nothing left to resolve.
     */
    fun resolveStart(replace: Boolean?) {
        val pending = _state.value.pendingStart ?: return
        _state.update { it.copy(pendingStart = null) }
        if (replace != null) io { begin(pending.routine, pending.startTitle, replace) }
    }

    private fun begin(routine: Routine?, title: String?, replace: Boolean) {
        if (replace) service.closeOpenWorkout()
        val id = service.startWorkout(routine, title)
        _state.update { it.copy(startedWorkoutId = id, startedFree = routine == null) }
    }

    fun consumeStarted() = _state.update { it.copy(startedWorkoutId = null, startedFree = false) }

    fun duplicate(routine: Routine, copySuffix: String) = io {
        val now = nowMs()
        service.repo.saveRoutine(routine.copy(id = service.repo.newId(), name = routine.name + copySuffix,
            createdAt = now, updatedAt = now, builtinKey = null))
    }

    fun delete(routine: Routine) = io { service.repo.deleteRoutine(routine.id) }

    fun close() = scope.cancel()

    private fun io(block: suspend () -> Unit) {
        scope.launchGuarded(fail) { withContext(io) { service.ensureReady(); block() } }
    }

    private companion object {
        /** Enough history for a 21-day break plus a two-week ramp. */
        const val ACTIVITY_DAYS = 70
    }
}
