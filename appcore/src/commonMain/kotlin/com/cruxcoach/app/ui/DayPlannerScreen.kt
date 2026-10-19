package com.cruxcoach.app.ui

import com.cruxcoach.app.dayplan.DayPlannerPresenter
import com.cruxcoach.app.dayplan.DayPlannerState
import com.cruxcoach.app.dayplan.SWAP_RANGE_DAYS
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_FREE
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import kotlin.math.abs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** A change to the day plan that one tap takes back (Today's "Rest day today" snackbar). */
class DayPlanUndo internal constructor(private val restorer: () -> Unit) {
    fun restore() = restorer()
}

/** A climbing day's place or intensity in the chooser: [code] is the lower-case enum name ("gym_boulder", "limit"). */
class DayPlanChoiceUi(val code: String, val label: TrText)

/** A training the chooser offers: [entry] is an own routine id or "builtin:<key>". */
class DayPlanOptionUi(val entry: String, val label: TrText)

/** One day of "Plan days" (Android `DayPlannerSheet` row) with what its chooser needs. */
class DayPlanRowUi(
    val isoDate: String,
    val isToday: Boolean,
    /** What the day plans: an entry's name, "Usual climbing day" or "Free". */
    val label: TrText,
    /** Something is planned there (also a usual climbing day): the label stands out; a free day is grey. */
    val emphasized: Boolean,
    /** "this day only · Coach: …"; empty when there is nothing to add. */
    val detail: TrText,
    /** The coach's hint changes something there (accent colour); a pause reads grey. */
    val detailAccent: Boolean,
    /** The date has its own entry instead of the standard week's. */
    val overridden: Boolean,
    /** The entry in force: own routine id, "builtin:<key>", "board", "rest" or "climb:<kind>:<intensity>"; "" = free. */
    val entry: String,
    /** A climbing day is planned there (the chooser's climbing row shows a check). */
    val climbSelected: Boolean,
    /** The chooser's preselected place and intensity: the planned climbing day's, else gym bouldering / volume. */
    val climbKind: String,
    val climbIntensity: String,
    /** "As in the standard week: …" when [overridden]; empty otherwise. */
    val standardLabel: TrText,
    /** The days this one can swap with: within a week either way (ISO dates). */
    val swapTargets: List<String>,
)

/** advice, nothing locked: [kindCode] backToBack | fingersBefore; [otherIso] the earlier day, [dateIso] the later. */
class DayPlanWarningUi(val kindCode: String, val otherIso: String, val dateIso: String)

class DayPlannerScreenState(
    val loading: Boolean,
    /** For "Today"/"Tomorrow" in the day labels; other days show weekday and date. */
    val todayIso: String,
    val tomorrowIso: String,
    val days: List<DayPlanRowUi>,
    val warnings: List<DayPlanWarningUi>,
    /** "Plan changed." with "Undo". */
    val hasChange: Boolean,
    /** "<from>: <training> is off. Move it to <to>?" */
    val hasMoveOffer: Boolean,
    val moveEntryLabel: TrText,
    val moveFromIso: String,
    val moveToIso: String,
    val climbKinds: List<DayPlanChoiceUi>,
    val climbIntensities: List<DayPlanChoiceUi>,
    /** Own routines, then the starter routines. */
    val trainings: List<DayPlanOptionUi>,
)

/**
 * "Tage planen" (Android `DayPlannerSheet` + `DayPlannerViewModel`): today and the next two weeks, one tap per day to
 * plan or swap it; the free days show how the coach fits them around the climbing. Every decision is the ported
 * presenter's; this maps its state to plain values and [TrText]s and checks what Swift passes in.
 */
class DayPlannerScreenModel internal constructor(private val presenter: DayPlannerPresenter) {

    val currentState: DayPlannerScreenState get() = map(presenter.state.value)

    fun watch(onState: (DayPlannerScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    /** [entry] "" = free (nothing that day), else "rest", "board" or a training of [DayPlannerScreenState.trainings]. */
    fun setDay(isoDate: String, entry: String) {
        val date = shownDate(isoDate) ?: return
        val s = presenter.state.value
        val value = when {
            entry.isEmpty() -> PLAN_FREE
            entry == PLAN_REST || entry == PLAN_BOARD -> entry
            trainingEntries(s.routines).contains(entry) -> entry
            else -> return
        }
        presenter.set(date, value)
    }

    /** A climbing day: [kind] and [intensity] as in [DayPlannerScreenState.climbKinds] / [DayPlannerScreenState.climbIntensities]. */
    fun setClimb(isoDate: String, kind: String, intensity: String) {
        val date = shownDate(isoDate) ?: return
        val entry = climbEntry(kind, intensity) ?: return
        presenter.set(date, entry)
    }

    /** Back to the standard week for this date. */
    fun setStandard(isoDate: String) {
        val date = shownDate(isoDate) ?: return
        presenter.set(date, null)
    }

    /** Swaps what the two dates plan; [otherIso] must be one of the row's swap targets. */
    fun swap(isoDate: String, otherIso: String) {
        val date = shownDate(isoDate) ?: return
        val other = shownDate(otherIso) ?: return
        if (date == other || abs(date.daysUntil(other)) > SWAP_RANGE_DAYS) return
        presenter.swap(date, other)
    }

    fun acceptMove() = presenter.acceptMove()
    fun dismissMove() = presenter.dismissMove()
    fun undo() = presenter.undo()

    /** Only dates the planner shows are planned from Swift. */
    private fun shownDate(iso: String): LocalDate? {
        val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return null
        return date.takeIf { d -> presenter.state.value.days.any { it.date == d } }
    }

    private var lastGood: DayPlannerScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: DayPlannerState): DayPlannerScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        lastGood ?: mapUnsafe(DayPlannerState())
    }

    private fun mapUnsafe(s: DayPlannerState): DayPlannerScreenState {
        val today = s.today
        val offer = s.moveOffer
        return DayPlannerScreenState(
            loading = s.loading || today == null,
            todayIso = today?.toString() ?: "",
            tomorrowIso = today?.plus(DatePeriod(days = 1))?.toString() ?: "",
            days = if (today == null) emptyList() else s.days.map { row(it, s, today) },
            warnings = s.warnings.map { w ->
                DayPlanWarningUi(
                    when (w.kind) {
                        DayPlans.WarningKind.HARD_BACK_TO_BACK -> "backToBack"
                        DayPlans.WarningKind.FINGERS_BEFORE_HARD -> "fingersBefore"
                    },
                    w.other.toString(), w.date.toString(),
                )
            },
            hasChange = s.lastChange != null,
            hasMoveOffer = offer != null,
            moveEntryLabel = offer?.let { DayPlanTexts.entryLabel(it.entry, s.routines) } ?: Tx.EMPTY,
            moveFromIso = offer?.from?.toString() ?: "",
            moveToIso = offer?.to?.toString() ?: "",
            climbKinds = DayPlanTexts.CLIMB_KINDS.map { DayPlanChoiceUi(it.name.lowercase(), Tx.t(Tx.climbKindKey(it))) },
            climbIntensities = ClimbIntensity.entries.map { DayPlanChoiceUi(it.name.lowercase(), Tx.t(Tx.climbLevelKey(it))) },
            trainings = trainingEntries(s.routines).map { DayPlanOptionUi(it, DayPlanTexts.entryLabel(it, s.routines)) },
        )
    }

    private fun row(day: DayPlans.Day, s: DayPlannerState, today: LocalDate): DayPlanRowUi {
        val entry = day.entry
        val climb = DayPlans.climbOf(entry)
        val label = when {
            entry != null -> DayPlanTexts.entryLabel(entry, s.routines)
            day.hint == DayPlans.Hint.CLIMB -> Tx.t("trdp_usual_climb")
            else -> Tx.t("trdp_free_short")
        }
        val detail = listOfNotNull(
            if (day.overridden) Tx.t("trdp_only_this_day") else null,
            DayPlanTexts.hint(day, s.profile),
        )
        val standard = s.profile.weekPlan[day.date.dayOfWeek.isoDayNumber]
        return DayPlanRowUi(
            isoDate = day.date.toString(),
            isToday = day.date == today,
            label = label,
            emphasized = entry != null || day.hint == DayPlans.Hint.CLIMB,
            detail = if (detail.isEmpty()) Tx.EMPTY else Tx.join(detail),
            detailAccent = day.hint != DayPlans.Hint.OPEN && day.hint != DayPlans.Hint.PAUSED,
            overridden = day.overridden,
            entry = entry ?: "",
            climbSelected = climb != null,
            climbKind = (climb?.kind ?: ClimbingDayKind.GYM_BOULDER).name.lowercase(),
            climbIntensity = (climb?.intensity ?: ClimbIntensity.VOLUME).name.lowercase(),
            standardLabel = if (day.overridden) Tx.t("trdp_standard", Tx.x(DayPlanTexts.entryLabel(standard, s.routines))) else Tx.EMPTY,
            swapTargets = s.days.map { it.date }.filter { it != day.date && abs(day.date.daysUntil(it)) <= SWAP_RANGE_DAYS }.map { it.toString() },
        )
    }

    private fun trainingEntries(routines: List<Routine>): List<String> =
        routines.map { it.id } + BuiltinRoutines.all.map { "builtin:" + it.builtinKey }

    companion object {
        /** "climb:<kind>:<intensity>" for valid codes; null otherwise. */
        internal fun climbEntry(kind: String, intensity: String): String? {
            val k = DayPlanTexts.CLIMB_KINDS.firstOrNull { it.name.lowercase() == kind } ?: return null
            val i = ClimbIntensity.entries.firstOrNull { it.name.lowercase() == intensity } ?: return null
            return DayPlans.Climb(k, i).entry
        }
    }
}

/** Texts of planned days (Android `planEntryLabel`, `hintText`), shared by the planner and Today. */
internal object DayPlanTexts {
    /** The places a planned climbing day offers (Android's chooser leaves "another board" out). */
    val CLIMB_KINDS = listOf(ClimbingDayKind.GYM_BOULDER, ClimbingDayKind.GYM_ROPE, ClimbingDayKind.OUTDOOR, ClimbingDayKind.OTHER)

    /** Android `planEntryLabel`: free, rest, board, a climbing day, a starter or own routine. */
    fun entryLabel(entry: String?, routines: List<Routine>): TrText = when {
        entry == null || entry == PLAN_FREE -> Tx.t("trwo_plan_free")
        entry == PLAN_REST -> Tx.t("trwo_plan_rest")
        entry == PLAN_BOARD -> Tx.t("trwo_plan_board")
        DayPlans.isClimb(entry) -> Tx.climbPlanLabel(DayPlans.climbOf(entry)!!)
        entry.startsWith("builtin:") -> BuiltinRoutines.byKey(entry.removePrefix("builtin:"))?.let { Tx.routineName(it) }
            ?: Tx.t("trwo_plan_unknown")
        else -> routines.firstOrNull { it.id == entry }?.let { Tx.plain(it.name) } ?: Tx.t("trwo_plan_unknown")
    }

    /** Android `hintText` (e2bc7c731: a free day without anything around it needs no second line). */
    fun hint(day: DayPlans.Day, profile: AthleteProfile): TrText? = when (day.hint) {
        DayPlans.Hint.AFTER_HARD -> Tx.t("trdp_hint_after_hard")
        DayPlans.Hint.BEFORE_LIMIT -> Tx.t("trdp_hint_before_limit")
        DayPlans.Hint.BEFORE_HARD -> Tx.t("trdp_hint_before_hard")
        DayPlans.Hint.OPEN -> null
        DayPlans.Hint.PAUSED -> Tx.t("trdp_hint_paused")
        DayPlans.Hint.CLIMB, DayPlans.Hint.BOARD ->
            Tx.t(if (profile.coach.addOnAfterClimbing == false) "trdp_hint_climb_only" else "trdp_hint_climb")
        DayPlans.Hint.REST, DayPlans.Hint.ROUTINE -> null
    }
}
