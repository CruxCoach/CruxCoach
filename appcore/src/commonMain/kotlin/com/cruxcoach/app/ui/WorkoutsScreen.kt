package com.cruxcoach.app.ui

import com.cruxcoach.app.util.watch
import com.cruxcoach.app.library.LibraryDayStatus
import com.cruxcoach.app.library.LibraryKeys
import com.cruxcoach.app.library.LibraryRoutines
import com.cruxcoach.app.library.LibraryWeek
import com.cruxcoach.app.library.LibraryWeekCell
import com.cruxcoach.app.library.WorkoutsPresenter
import com.cruxcoach.app.library.WorkoutsState
import com.cruxcoach.app.library.catalogLanguage
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.ReturnReason
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.isoDayNumber
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Text for the training library ───────────────────────────────────
//
// Kotlin chooses which Android string a line uses and with which values;
// Swift looks the key up in Localizable.strings and formats it. No text is
// spelled out here: [LibraryText.literal] carries only user or catalogue data
// (a workout's own name, an exercise name in the catalogue's language).

/** One format argument. [kind]: text | key | int | decimal | nested. */
class LibraryArg internal constructor(
    val kind: String,
    val text: String,
    val intValue: Long,
    val doubleValue: Double,
    /** Most decimals shown; trailing zeros are dropped (Android `formatNumber`). */
    val decimals: Int,
    /** "+" before a positive number. */
    val signed: Boolean,
    val nested: LibraryText?,
)

/**
 * A localized line: [key] formatted with [args]; when [key] is empty, [parts]
 * joined with [separator], or else [literal]. A [plural] key gets
 * ".one"/".other" by [count], like Android plurals. Swift substitutes every
 * placeholder as text, so an argument's kind never has to match `%@`/`%ld`.
 */
class LibraryText internal constructor(
    val key: String,
    val args: List<LibraryArg>,
    val plural: Boolean,
    val count: Long,
    val literal: String,
    val parts: List<LibraryText>,
    val separator: String,
)

internal object LibraryTexts {
    fun key(key: String, vararg args: LibraryArg) = LibraryText(key, args.toList(), false, 0, "", emptyList(), "")
    fun plural(key: String, count: Int, vararg args: LibraryArg) = LibraryText(key, args.toList(), true, count.toLong(), "", emptyList(), "")
    fun literal(text: String) = LibraryText("", emptyList(), false, 0, text, emptyList(), "")
    fun join(parts: List<LibraryText>, separator: String = " · ") = LibraryText("", emptyList(), false, 0, "", parts, separator)
    val EMPTY = literal("")

    fun text(value: String) = LibraryArg("text", value, 0, 0.0, 0, false, null)
    fun ref(key: String) = LibraryArg("key", key, 0, 0.0, 0, false, null)
    fun int(value: Int) = LibraryArg("int", "", value.toLong(), 0.0, 0, false, null)
    fun decimal(value: Double, decimals: Int = 1, signed: Boolean = false) =
        LibraryArg("decimal", "", 0, value, decimals, signed, null)
    fun nested(text: LibraryText) = LibraryArg("nested", "", 0, 0.0, 0, false, text)

    /** Plate steps like 1.25 kg need two decimals; body weights and loads read best with one. */
    private fun massDecimals(v: Double): Int {
        val quarter = abs(v * 4 - (v * 4).roundToInt()) < 1e-6
        val tenth = abs(v * 10 - (v * 10).roundToInt()) < 1e-6
        return if (quarter && !tenth) 2 else 1
    }

    /** Android `formatMass`. */
    fun mass(kg: Double, units: UnitSystem, signed: Boolean = false): LibraryText {
        val v = Units.massToDisplay(kg, units)
        return key(if (units == UnitSystem.IMPERIAL) "tr_format_lb" else "tr_format_kg", decimal(v, massDecimals(v), signed))
    }

    /** Built-in routines carry a key; their names live in resources (Android `routineName`). */
    fun routineName(r: Routine): LibraryText =
        r.builtinKey?.let { key(LibraryKeys.builtinName(it)) } ?: literal(r.name)

    /** Android `routineDescription`; null when the routine has none. */
    fun routineDescription(r: Routine): LibraryText? =
        r.builtinKey?.let { key(LibraryKeys.builtinDescription(it)) } ?: r.notes?.takeIf { it.isNotBlank() }?.let { literal(it) }

    /** Android `workoutTitle`: built-in routines are stored by key. */
    fun workoutTitle(workout: Workout): LibraryText {
        val builtin = workout.routineId?.takeIf { it.startsWith("builtin:") }?.removePrefix("builtin:")?.let { BuiltinRoutines.byKey(it) }
        return when {
            builtin != null -> routineName(builtin)
            !workout.title.isNullOrBlank() -> literal(workout.title!!)
            else -> key("trw_workout_default")
        }
    }

    /** Localized name of a week-plan entry (Android `planEntryLabel`). */
    fun planEntry(entry: String?, routines: List<Routine>): LibraryText = when {
        entry == null -> key("trwo_plan_free")
        entry == PLAN_REST -> key("trwo_plan_rest")
        entry == PLAN_BOARD -> key("trwo_plan_board")
        entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))?.let { routineName(it) }
            ?: key("trwo_plan_unknown")
        else -> routines.firstOrNull { it.id == entry }?.let { literal(it.name) } ?: key("trwo_plan_unknown")
    }

    /** Plan label short enough for a seventh of the screen (Android `shortPlanLabel`). */
    fun shortPlanEntry(entry: String, routines: List<Routine>): LibraryText = when (entry) {
        PLAN_BOARD -> key("trw_short_board")
        PLAN_REST -> key("trw_short_rest")
        else -> planEntry(entry, routines)
    }

    fun dayStatusKey(s: LibraryDayStatus) = "trw_status_" + LibraryKeys.code(s.name)
}

// ── Workouts tab ────────────────────────────────────────────────────

class RoutineCardUi(
    /** Own routine id, or "builtin:<key>" for a starter routine. */
    val id: String,
    /** "" for an own routine. */
    val builtinKey: String,
    val isOwn: Boolean,
    val title: LibraryText,
    /** Starter routines: their description; own ones show none. */
    val hasSubtitle: Boolean,
    val subtitle: LibraryText,
    val exerciseCount: Int,
    val minutes: Int,
    /** "Pull-up · Plank · …": the distinct exercise names. */
    val preview: String,
)

class WeekWorkoutUi(val title: LibraryText, /** -1 while unknown. */ val minutes: Int)

class WeekDayUi(
    val dateIso: String,
    /** 1 = Monday … 7 = Sunday. */
    val weekday: Int,
    val isToday: Boolean,
    val isPast: Boolean,
    /** done | planned | missed | paused | rest | free */
    val statusCode: String,
    val statusKey: String,
    val hasPlan: Boolean,
    val planLabel: LibraryText,
    val shortPlanLabel: LibraryText,
    /** The plan entry is the board ("board"); other entries are routines or rest. */
    val plannedBoard: Boolean,
    val climbingMinutes: Int,
    val climbingEfforts: Int,
    val workouts: List<WeekWorkoutUi>,
    /** A planned workout can be started today. */
    val canStart: Boolean,
    /** A planned workout today or later can be adapted in the editor. */
    val canAdapt: Boolean,
    /** For [canStart]/[canAdapt]: own routine id, or "builtin:<key>". */
    val plannedRoutineId: String,
    /** Name of the planned routine, for the start (built-ins: its resource key). */
    val plannedTitle: LibraryText,
)

class ReturnBannerUi(
    /** break | illness | finger */
    val reasonCode: String,
    val week: Int,
    val weeksTotal: Int,
    val daysOff: Int,
    val percent: Int,
    val fingerOnly: Boolean,
    val noMaxFinger: Boolean,
)

class WorkoutsScreenState(
    val isLoading: Boolean,
    val failed: Boolean,
    /** "" when no training is open. */
    val openWorkoutId: String,
    val own: List<RoutineCardUi>,
    val builtins: List<RoutineCardUi>,
    val week: List<WeekDayUi>,
    val weekPlanEmpty: Boolean,
    val weekDone: Int,
    val weekPlanned: Int,
    val hasReturnBanner: Boolean,
    val returnBanner: ReturnBannerUi,
    // "Continue the open training or end it?"
    val hasPendingStart: Boolean,
    val pendingOpenTitle: LibraryText,
    val pendingDoneSets: Int,
    val pendingTotalSets: Int,
    /** False: the replace button reads "end it and start an empty one". */
    val pendingHasNewTitle: Boolean,
    val pendingNewTitle: LibraryText,
    /** One-shot: navigate to this training, then call [WorkoutsScreenModel.consumeStarted]; "" when none. */
    val startedWorkoutId: String,
)

/** The Workouts half of the Training tab (Android `WorkoutsScreen`). */
class WorkoutsScreenModel internal constructor(
    private val presenter: WorkoutsPresenter,
    language: String,
) {
    private val lang = catalogLanguage(language)

    val currentState: WorkoutsScreenState get() = map(presenter.state.value)

    fun watch(onState: (WorkoutsScreenState) -> Unit): Subscription {
        val watch = presenter.state.watch { onState(map(it)) }
        return Subscription { watch.cancel() }
    }

    fun refreshWeek() = presenter.refreshWeek()

    /** [id]: own routine id or "builtin:<key>"; [title] the localized name of a starter routine, "" for own ones. */
    fun startRoutine(id: String, title: String) {
        val routine = routineFor(id) ?: return
        presenter.start(routine, title.ifEmpty { null }.takeIf { routine.builtinKey != null })
    }

    /** [title]: the localized "free training", shown in the open-training question. */
    fun startFree(title: String) = presenter.startFree(title)

    /** Starts the routine planned for [dateIso] (the day sheet's "Start"); [title] is its localized name. */
    fun startPlanned(dateIso: String, title: String) {
        val s = presenter.state.value
        val cell = s.week.firstOrNull { it.date.toString() == dateIso } ?: return
        val routine = LibraryWeek.plannedRoutine(cell.planned, s.routines) ?: return
        presenter.start(routine, title.ifEmpty { null })
    }

    fun replaceOpenAndStart() = presenter.resolveStart(true)
    fun continueOpen() = presenter.resolveStart(false)
    fun dismissPendingStart() = presenter.resolveStart(null)
    fun consumeStarted() = presenter.consumeStarted()

    /** [copySuffix]: the localized " (copy)". */
    fun duplicate(routineId: String, copySuffix: String) {
        presenter.state.value.routines.firstOrNull { it.id == routineId }?.let { presenter.duplicate(it, copySuffix) }
    }

    fun delete(routineId: String) {
        presenter.state.value.routines.firstOrNull { it.id == routineId }?.let { presenter.delete(it) }
    }

    fun close() = presenter.close()

    private fun routineFor(id: String): Routine? =
        if (id.startsWith("builtin:")) BuiltinRoutines.byKey(id.removePrefix("builtin:"))
        else presenter.state.value.routines.firstOrNull { it.id == id }

    private fun map(s: WorkoutsState): WorkoutsScreenState {
        val pending = s.pendingStart
        val ret = s.returnState
        val today = s.today
        return WorkoutsScreenState(
            isLoading = s.loading,
            failed = s.failed,
            openWorkoutId = s.openWorkout?.id ?: "",
            own = s.routines.map { card(it, s.catalog) },
            builtins = BuiltinRoutines.all.map { card(it, s.catalog) },
            week = s.week.map { day(it, s, today) },
            weekPlanEmpty = s.profile.weekPlan.isEmpty(),
            weekDone = s.week.count { it.status == LibraryDayStatus.DONE },
            weekPlanned = s.week.count { it.planned != null && it.planned != PLAN_REST },
            hasReturnBanner = ret != null,
            returnBanner = ReturnBannerUi(
                reasonCode = when (ret?.reason) {
                    ReturnReason.AFTER_ILLNESS -> "illness"
                    ReturnReason.FINGER_INJURY_HEALED -> "finger"
                    else -> "break"
                },
                week = ret?.week ?: 0,
                weeksTotal = ret?.weeksTotal ?: 0,
                daysOff = ret?.daysOff ?: 0,
                percent = ((ret?.setsFactor ?: 0.0) * 100).roundToInt(),
                fingerOnly = ret?.fingerOnly ?: false,
                noMaxFinger = ret?.noMaxFinger ?: false,
            ),
            hasPendingStart = pending != null,
            pendingOpenTitle = pending?.conflict?.open?.title?.let { LibraryTexts.literal(it) } ?: LibraryTexts.key("trw_open_untitled"),
            pendingDoneSets = pending?.conflict?.doneSets ?: 0,
            pendingTotalSets = pending?.conflict?.totalSets ?: 0,
            pendingHasNewTitle = pending?.newTitle != null,
            pendingNewTitle = LibraryTexts.literal(pending?.newTitle ?: ""),
            startedWorkoutId = s.startedWorkoutId ?: "",
        )
    }

    private fun card(r: Routine, catalog: ExerciseCatalog): RoutineCardUi {
        val description = if (r.builtinKey != null) LibraryTexts.routineDescription(r) else null
        return RoutineCardUi(
            id = r.builtinKey?.let { "builtin:$it" } ?: r.id,
            builtinKey = r.builtinKey ?: "",
            isOwn = r.builtinKey == null,
            title = LibraryTexts.routineName(r),
            hasSubtitle = description != null,
            subtitle = description ?: LibraryTexts.literal(""),
            exerciseCount = r.items.size,
            minutes = LibraryRoutines.estimatedMinutes(r),
            preview = r.items.map { catalog.fallbackFor(it.slug).name(lang) }.distinct().joinToString(" · "),
        )
    }

    private fun day(cell: LibraryWeekCell, s: WorkoutsState, today: kotlinx.datetime.LocalDate?): WeekDayUi {
        val planned = LibraryWeek.plannedRoutine(cell.planned, s.routines)
        val isToday = today != null && cell.date == today
        val past = today != null && cell.date < today
        val a = cell.activity
        return WeekDayUi(
            dateIso = cell.date.toString(),
            weekday = cell.date.dayOfWeek.isoDayNumber,
            isToday = cell.isToday,
            isPast = past,
            statusCode = LibraryKeys.code(cell.status.name),
            statusKey = LibraryTexts.dayStatusKey(cell.status),
            hasPlan = cell.planned != null,
            planLabel = LibraryTexts.planEntry(cell.planned, s.routines),
            shortPlanLabel = cell.planned?.let { LibraryTexts.shortPlanEntry(it, s.routines) } ?: LibraryTexts.literal(""),
            plannedBoard = cell.planned == PLAN_BOARD,
            climbingMinutes = a?.climbingMinutes ?: 0,
            climbingEfforts = a?.climbingEfforts ?: 0,
            workouts = cell.workouts.map { WeekWorkoutUi(LibraryTexts.workoutTitle(it), it.durationMinutes ?: -1) },
            canStart = planned != null && isToday,
            canAdapt = planned != null && (isToday || cell.status == LibraryDayStatus.PLANNED),
            // Starter routines are ids "builtin:<key>" themselves; the editor copies them.
            plannedRoutineId = planned?.id ?: "",
            plannedTitle = planned?.let { LibraryTexts.routineName(it) } ?: LibraryTexts.literal(""),
        )
    }
}
