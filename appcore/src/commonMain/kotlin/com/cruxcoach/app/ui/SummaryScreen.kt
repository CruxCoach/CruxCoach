package com.cruxcoach.app.ui

import com.cruxcoach.app.training.SummaryRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.app.training.WorkoutSummaryPresenter
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.ProgressionSuggestion
import com.cruxcoach.athlete.logic.ProgressionVerdict
import com.cruxcoach.athlete.logic.TrendKind
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SummaryRecordUi(val slug: String, val name: String, val text: TrText)

class SummarySuggestionUi(
    val slug: String,
    val name: String,
    val verdict: TrText,
    val load: TrText,
    /** The harder (too easy) or easier (too hard) variant to open; "" without. */
    val variantSlug: String,
    val variantText: TrText,
)

/** A pattern across sessions; [actionCode]: open (a variation) | swap (in the own workouts) | deload | "" */
class SummaryTrendUi(
    val slug: String,
    val name: String,
    val text: TrText,
    val actionCode: String,
    val actionSlug: String,
    val actionText: TrText,
    val note: TrText,
)

class SummaryBlockUi(val slug: String, val name: String, val lines: List<TrText>)

class WorkoutSummaryState(
    val loading: Boolean,
    val found: Boolean,
    val headline: TrText,
    val durationValue: TrText,
    val setsValue: Int,
    val thirdLabel: TrText,
    val thirdValue: TrText,
    val hasStreak: Boolean,
    val weekGoal: TrText,
    /** 0…1 */
    val weekProgress: Double,
    val streakLine: TrText,
    val hasKeep: Boolean,
    val keepQuestion: TrText,
    val keepDetails: TrText,
    val hasAfter: Boolean,
    val afterDayForm: TrText,
    val afterFingers: TrText,
    val afterFuel: TrText,
    val records: List<SummaryRecordUi>,
    val ownLoads: List<TrText>,
    val suggestions: List<SummarySuggestionUi>,
    val trends: List<SummaryTrendUi>,
    val hasSideBalance: Boolean,
    val sideValues: TrText,
    val sideDiff: TrText,
    val blocks: List<SummaryBlockUi>,
    val canSaveRoutine: Boolean,
    val routineSaved: Boolean,
    val defaultRoutineName: TrText,
    val deleteText: TrText,
    // ── One-shot results ──
    val messageText: TrText,
    val keepAnswerText: TrText,
    val deleted: Boolean,
)

/** The end of a training (Android `WorkoutSummaryScreen`). */
class WorkoutSummaryScreenModel internal constructor(
    private val presenter: WorkoutSummaryPresenter,
    private val language: String,
) {
    val currentState: WorkoutSummaryState get() = map(presenter.state.value)

    fun watch(onState: (WorkoutSummaryState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun load(workoutId: String) = presenter.load(workoutId)
    fun close() = presenter.close()

    fun answerKeep(keep: Boolean) = presenter.answerKeep(keep)
    fun undoKeep() { presenter.state.value.keepAnswer?.let { presenter.undoKeep(it) } }
    fun consumeKeepAnswer() = presenter.consumeKeepAnswer()
    fun swapInRoutines(from: String, to: String) = presenter.swapInRoutines(from, to)
    fun startDeload() = presenter.startDeload()
    fun undoLastAction() = presenter.undoLastAction()
    fun consumeMessage() = presenter.consumeMessage()
    fun saveRoutine(name: String) { if (name.isNotBlank()) presenter.saveRoutine(name.take(80)) }
    fun delete() = presenter.delete()

    /** Own workouts that contain [from], for the swap question. */
    fun routineCount(from: String): Int = presenter.state.value.routineCounts[from] ?: 0

    /** The swap question's text: "X replaces Y in n of your workouts", or that it is in none. */
    fun swapConfirmText(from: String, to: String): TrText {
        val s = presenter.state.value
        val count = s.routineCounts[from] ?: 0
        return if (count > 0) Tx.t("tre_swap_confirm_text", Tx.s(s.catalog.fallbackFor(to).name(language)),
            Tx.s(s.catalog.fallbackFor(from).name(language)), Tx.i(count)) else Tx.t("tre_swap_none")
    }

    private var lastGood: WorkoutSummaryState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: SummaryRunState): WorkoutSummaryState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(SummaryRunState())
    }

    private fun mapUnsafe(s: SummaryRunState): WorkoutSummaryState = SummaryMapper(s, language).build()
}

private class SummaryMapper(val s: SummaryRunState, val language: String) {
    val units: UnitSystem = s.units

    fun build(): WorkoutSummaryState {
        val workout = s.workout
        val summary = s.summary
        val st = s.streak
        val ownSlugs = s.ownLoads.map { it.exerciseSlug }.toSet()
        val side = sideBalance()
        val changes = s.keep
        val swapped = changes.count { it.chosen != null }
        val left = changes.size - swapped
        return WorkoutSummaryState(
            loading = s.loading,
            found = workout != null && summary != null,
            headline = workout?.let { Tx.join(listOf(Tx.workoutTitle(it), TrText("", listOf(Tx.date(it.day)), -1, ""))) } ?: Tx.EMPTY,
            durationValue = summary?.durationMinutes?.let { Tx.p("trw_minutes", it, Tx.i(it)) } ?: Tx.plain("–"),
            setsValue = summary?.completedSets ?: 0,
            thirdLabel = when {
                s.volumeKg > 0 -> Tx.t("trp2_tile_volume")
                s.hangSeconds > 0 -> Tx.t("trp2_tile_hang")
                else -> Tx.t("trw_summary_exercises")
            },
            thirdValue = when {
                s.volumeKg > 0 -> Tx.mass(s.volumeKg, units)
                s.hangSeconds > 0 -> Tx.plain(TodayClock.clock(s.hangSeconds))
                else -> Tx.plain((summary?.exercises ?: 0).toString())
            },
            hasStreak = st != null,
            weekGoal = st?.let { if (it.currentWeekDone) Tx.t("trp2_week_goal_reached") else Tx.t("trp2_week_goal", Tx.i(it.currentWeekDays), Tx.i(it.goal)) } ?: Tx.EMPTY,
            weekProgress = st?.let { if (it.goal > 0) (it.currentWeekDays.toDouble() / it.goal).coerceIn(0.0, 1.0) else 0.0 } ?: 0.0,
            streakLine = st?.weeks?.takeIf { it > 0 }?.let { Tx.p("trp2_streak_weeks", it, Tx.i(it)) } ?: Tx.EMPTY,
            hasKeep = changes.isNotEmpty(),
            keepQuestion = if (changes.isEmpty()) Tx.EMPTY else Tx.t("trk_question", Tx.x(when {
                swapped > 0 && left > 0 -> Tx.join(listOf(Tx.p("trk_swapped", swapped, Tx.i(swapped)), Tx.p("trk_left_out_more", left, Tx.i(left))), ", ")
                swapped > 0 -> Tx.p("trk_swapped", swapped, Tx.i(swapped))
                else -> Tx.p("trk_left_out", left, Tx.i(left))
            })),
            keepDetails = Tx.join(changes.map { c -> Tx.keptChangeText(c.replaced, c.chosen, s.catalog, language) }),
            hasAfter = s.fingersFreeOn != null || s.fuelLeft != null || s.dayForm != null,
            afterDayForm = s.dayForm?.takeIf { it.setsDelta < 0 }?.let { m ->
                Tx.t("tre_after_dayform", Tx.x(m.reason?.let { Tx.t(Tx.readinessReasonShortKey(it)) } ?: Tx.t("trpg_dayform_climbed")))
            } ?: Tx.EMPTY,
            afterFingers = s.fingersFreeOn?.let { Tx.t("tre_after_fingers", Tx.weekday(it.dayOfWeek.ordinal + 1)) } ?: Tx.EMPTY,
            afterFuel = s.fuelLeft?.let { f ->
                val day = Tx.k(Tx.dayLoadKey(f.dayLoad))
                if (f.carbsG == 0 && f.proteinG == 0) Tx.t("tre_after_fuel_done", day) else Tx.t("tre_after_fuel", day, Tx.i(f.carbsG), Tx.i(f.proteinG))
            } ?: Tx.EMPTY,
            records = summary?.records.orEmpty().map { (slug, record) ->
                val def = s.catalog.fallbackFor(slug)
                val now = Tx.recordValueText(def, record, units)
                val before = if (record.previous > 0) Tx.recordValueText(def, record.copy(value = record.previous), units) else null
                SummaryRecordUi(slug, def.name(language),
                    if (before != null) Tx.t("trp2_record_from_to", Tx.x(before), Tx.x(now)) else Tx.t("trp2_record_first", Tx.x(now)))
            },
            ownLoads = s.ownLoads.map { b ->
                val def = s.catalog.fallbackFor(b.exerciseSlug)
                val load = b.loadKg?.let { Tx.mass(it, units, signed = def.load == LoadMode.BODYWEIGHT_PLUS) }
                Tx.join(listOfNotNull(Tx.plain(def.name(language)), b.side?.let { Tx.t(Tx.sideKey(it)) }, load))
            },
            suggestions = summary?.suggestions.orEmpty().filter { (slug, _) -> slug !in ownSlugs }.map { (slug, sg) -> suggestion(s.catalog.fallbackFor(slug), sg) },
            trends = s.trends.map { (slug, t) ->
                val def = s.catalog.fallbackFor(slug)
                val name = def.name(language)
                fun other(x: String) = s.catalog.fallbackFor(x).name(language)
                when (t.kind) {
                    TrendKind.STALL -> {
                        val v = t.variationSlug
                        SummaryTrendUi(slug, name, Tx.t("tre_trend_stall", Tx.i(t.sessions.coerceAtMost(6))),
                            if (v != null) "open" else "", v ?: "", v?.let { Tx.t("tre_trend_stall_try", Tx.s(other(it))) } ?: Tx.EMPTY,
                            if (v == null) Tx.t("tre_trend_stall_generic") else Tx.EMPTY)
                    }
                    TrendKind.DECLINE -> SummaryTrendUi(slug, name, Tx.t("tre_trend_decline"), "deload", "", Tx.t("tre_deload_action"), Tx.EMPTY)
                    TrendKind.TOO_EASY_TWICE -> SummaryTrendUi(slug, name, Tx.t("tre_trend_easy"), if (t.harderSlug != null) "swap" else "",
                        t.harderSlug ?: "", t.harderSlug?.let { Tx.t("tre_swap_harder", Tx.s(other(it))) } ?: Tx.EMPTY, Tx.EMPTY)
                    TrendKind.TOO_HARD_TWICE -> SummaryTrendUi(slug, name, Tx.t("tre_trend_hard"), if (t.easierSlug != null) "swap" else "",
                        t.easierSlug ?: "", t.easierSlug?.let { Tx.t("tre_swap_easier", Tx.s(other(it))) } ?: Tx.EMPTY, Tx.EMPTY)
                    TrendKind.NONE, TrendKind.PROGRESSING -> SummaryTrendUi(slug, name, Tx.EMPTY, "", "", Tx.EMPTY, Tx.EMPTY)
                }
            }.filter { !it.text.isEmpty },
            hasSideBalance = side != null,
            sideValues = side?.let { (l, r) -> Tx.t("trw_side_values", Tx.i(l), Tx.i(r)) } ?: Tx.EMPTY,
            sideDiff = side?.takeIf { (l, r) -> abs(l - r) > 10 }?.let { (l, r) ->
                Tx.t("trw_side_diff", Tx.k(Tx.sideKey(if (l < r) Side.LEFT else Side.RIGHT)), Tx.i(abs(l - r)))
            } ?: Tx.EMPTY,
            blocks = s.blocks.map { (def, sets) -> SummaryBlockUi(def.slug, def.name(language), sets.map { setLine(def, it) }) },
            canSaveRoutine = !s.routineSaved && s.blocks.isNotEmpty(),
            routineSaved = s.routineSaved,
            defaultRoutineName = workout?.let { Tx.workoutTitle(it) } ?: Tx.EMPTY,
            deleteText = workout?.let { Tx.t("trw_delete_training_text", Tx.x(Tx.workoutTitle(it)), Tx.date(it.day)) } ?: Tx.EMPTY,
            messageText = when (val m = s.message) {
                null -> Tx.EMPTY
                -1 -> Tx.t("tre_deload_started")
                else -> Tx.t("tre_swap_done", Tx.i(m))
            },
            keepAnswerText = s.keepAnswer?.let { a ->
                if (a.until != null) Tx.t("trk_kept_until", Tx.date(a.until.toString())) else Tx.t("trk_today_only_done")
            } ?: Tx.EMPTY,
            deleted = s.deleted,
        )
    }

    private fun suggestion(def: ExerciseDefinition, sg: ProgressionSuggestion): SummarySuggestionUi {
        val signed = def.load == LoadMode.BODYWEIGHT_PLUS
        val easy = sg.verdict == ProgressionVerdict.TOO_EASY
        val load = sg.nextLoadKg?.let { next ->
            val text = if (signed && next == 0.0) Tx.t("tr_bodyweight")
                else if (signed && next < 0) Tx.t("trw_load_assisted", Tx.x(Tx.mass(-next, units)))
                else Tx.mass(next, units, signed = signed)
            Tx.t(if (easy) "trw_sugg_more_load" else "trw_sugg_less_load", Tx.x(text))
        } ?: Tx.EMPTY
        val variant = if (easy) sg.harderSlug else sg.easierSlug
        return SummarySuggestionUi(def.slug, def.name(language), Tx.t(if (easy) "trw_too_easy" else "trw_too_hard"), load,
            variant ?: "", variant?.let { Tx.t(if (easy) "trw_sugg_try" else "trw_sugg_easier_variant", Tx.s(s.catalog.fallbackFor(it).name(language))) }
                ?: Tx.EMPTY)
    }

    /** Left/right as % of the stronger side, or null without both sides. */
    private fun sideBalance(): Pair<Int, Int>? {
        val summary = s.summary ?: return null
        val left = summary.sideLoad[Side.LEFT] ?: return null
        val right = summary.sideLoad[Side.RIGHT] ?: return null
        val max = maxOf(left, right).takeIf { it > 0 } ?: return null
        return (left / max * 100).roundToInt() to (right / max * 100).roundToInt()
    }

    /** One logged set (`setLine`). */
    private fun setLine(def: ExerciseDefinition, set: ExerciseSet): TrText {
        val parts = mutableListOf<TrText>()
        parts += when (set.setType) {
            SetType.WARMUP -> Tx.t(Tx.setTypeKey(SetType.WARMUP))
            SetType.TEST -> Tx.t(Tx.setTypeKey(SetType.TEST))
            SetType.WORK -> Tx.t("trw_set_cd", Tx.i(set.setIndex + 1))
        }
        set.side?.let { parts += Tx.t(Tx.sideKey(it)) }
        when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.CLIMB -> set.reps?.let { parts += Tx.p("trw_reps", it, Tx.i(it)) }
            ExerciseKind.TIME, ExerciseKind.HANG -> set.durationS?.let { parts += Tx.seconds(it.roundToInt()) }
            ExerciseKind.INTERVAL -> parts += Tx.t("trw_interval_format",
                Tx.i((set.workS ?: 0.0).roundToInt()), Tx.i((set.restBetweenS ?: 0.0).roundToInt()), Tx.i(set.repsPerSet ?: 0))
        }
        if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
            set.loadKg?.let { load ->
                parts += when {
                    def.load == LoadMode.BODYWEIGHT_PLUS && load == 0.0 -> Tx.t("tr_bodyweight")
                    def.load == LoadMode.BODYWEIGHT_PLUS && load < 0 -> Tx.t("trw_load_assisted", Tx.x(Tx.mass(-load, units)))
                    else -> Tx.mass(load, units, signed = def.load == LoadMode.BODYWEIGHT_PLUS)
                }
            }
        }
        set.edgeMm?.let { parts += Tx.mm(it) }
        set.grip?.let { parts += Tx.t(Tx.gripKey(it)) }
        set.rir?.let { parts += Tx.t("trw_rir_short", Tx.i(it)) }
        set.note?.takeIf { it.isNotBlank() }?.let { parts += Tx.plain(it) }
        return Tx.join(parts)
    }
}
