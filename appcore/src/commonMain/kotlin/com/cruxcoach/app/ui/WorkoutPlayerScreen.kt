package com.cruxcoach.app.ui

import com.cruxcoach.app.athlete.PainAction
import com.cruxcoach.app.athlete.RestTimerState
import com.cruxcoach.app.training.WorkoutEndKind
import com.cruxcoach.app.training.WorkoutPlayerEvent
import com.cruxcoach.app.training.WorkoutPlayerItem
import com.cruxcoach.app.training.WorkoutPlayerPage
import com.cruxcoach.app.training.WorkoutPlayerPhase
import com.cruxcoach.app.training.WorkoutPlayerPresenter
import com.cruxcoach.app.training.WorkoutPlayerRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.ReadinessModifiers
import com.cruxcoach.athlete.logic.SetOutcomes
import com.cruxcoach.athlete.logic.TargetOutcome
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.app.library.TypedWorkoutReader
import com.cruxcoach.app.util.watch
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.Circuits
import com.cruxcoach.athlete.model.RoutineItem
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** One segment of the strip / one row of the overview. */
class WorkoutPageUi(
    val index: Int,
    /** Member names joined with " → " (overview) – the strip's accessibility text uses the same. */
    val names: String,
    val done: Int,
    val total: Int,
    val open: Boolean,
    val shown: Boolean,
    /** Every set is logged per side: [setsDone]/[setsTotal] count sets per side ("0 of 2 sets per side"). */
    val perSide: Boolean,
    val setsDone: Int,
    val setsTotal: Int,
)

/** One typed line as it will be added (Android `WorkoutTextSheet`): a catalogue exercise or a new one. */
class WorkoutTypedLineUi(
    val name: String,
    val summary: TrText,
    val isNew: Boolean,
    val creationHint: TrText,
    /** A circuit member: shown indented under its circuit's line. */
    val inCircuit: Boolean,
    /** The circuit's first member: [circuitHeader] stands above it. */
    val startsCircuit: Boolean,
    /** "Circuit · 3 rounds · 0:15 / 1:30"; empty unless [startsCircuit]. */
    val circuitHeader: TrText,
)

class WorkoutTypedPreviewUi(val title: TrText, val lines: List<WorkoutTypedLineUi>, val applyLabel: TrText)

/**
 * "Type exercises" into the open training (Android `WorkoutTextSheet`): the field's text is read off the main
 * thread, at once the first time and then a moment after the last keystroke; [add] takes exactly what is in
 * the field. Swift makes one per sheet (in `.task`, via [WorkoutPlayerScreenModel.typedReader]) and closes it.
 */
class WorkoutTypedReaderModel internal constructor(
    private val presenter: WorkoutPlayerPresenter,
    private val language: String,
    private val scope: CoroutineScope,
    reader: CoroutineDispatcher,
) {
    private val typed = TypedWorkoutReader(scope, reader, { presenter.catalog() })

    val currentState: WorkoutTypedPreviewUi get() = preview(typed.state.value.result)

    fun watch(onState: (WorkoutTypedPreviewUi) -> Unit): Subscription {
        val watch = typed.state.watch { onState(preview(it.result)) }
        return Subscription { watch.cancel() }
    }

    /** Every change of the field. */
    fun setText(text: String) = typed.read(text)

    /** Adds what is in the field: typed a moment ago and not read yet, it is read now (new names become own exercises). */
    fun add(text: String) = presenter.addTyped(typed.resultFor(text))

    fun close() = scope.cancel()

    private fun preview(result: com.cruxcoach.athlete.logic.WorkoutTextParser.Result): WorkoutTypedPreviewUi = try {
        WorkoutTypedPreviews.preview(result, language, presenter.state.value.profile.units, presenter.catalog())
    } catch (e: Exception) {
        WorkoutTypedPreviews.preview(TypedWorkoutReader.EMPTY, language, UnitSystem.METRIC, presenter.catalog())
    }
}

/** What typed lines become (Android `WorkoutTextSheet`): rows, circuits grouped under one line with rounds and rests. */
internal object WorkoutTypedPreviews {
    fun preview(
        result: com.cruxcoach.athlete.logic.WorkoutTextParser.Result,
        language: String,
        units: UnitSystem,
        catalog: ExerciseCatalog,
    ): WorkoutTypedPreviewUi {
        val items = result.lines.map { it.item }
        return WorkoutTypedPreviewUi(
            title = result.title?.let { Tx.t("trwo_text_name", Tx.s(it)) } ?: Tx.EMPTY,
            lines = result.lines.mapIndexed { i, line ->
                val kind = line.match?.kind ?: line.guess.kind
                val load = line.match?.load ?: line.guess.load
                val circuit = line.circuit
                val starts = circuit != null && result.lines.getOrNull(i - 1)?.circuit != circuit
                WorkoutTypedLineUi(line.match?.name(language) ?: line.name, Tx.routineItemSummary(line.item, kind, load, units),
                    line.isNew, if (line.isNew) Tx.t("trwo_text_new", Tx.k(exerciseKindKey(kind))) else Tx.EMPTY,
                    inCircuit = circuit != null,
                    startsCircuit = starts,
                    circuitHeader = if (circuit != null && starts) circuitHeader(items, circuit, catalog) else Tx.EMPTY)
            },
            applyLabel = if (result.lines.isEmpty()) Tx.t("trwo_text_apply_none") else Tx.p("trwo_text_apply", result.lines.size, Tx.i(result.lines.size)),
        )
    }

    /** Rounds, the rest between the exercises and the rest after a round; the defaults where the text said none. */
    private fun circuitHeader(items: List<RoutineItem>, circuit: Int, catalog: ExerciseCatalog): TrText {
        val members = items.filter { it.circuit == circuit }
        val rounds = Circuits.rounds(items, circuit)
        val between = members.first().circuitRestS ?: Circuits.DEFAULT_CIRCUIT_REST_S
        val round = members.first().roundRestS ?: Circuits.defaultRoundRest(members, catalog)
        return Tx.t("trcirc_group", Tx.x(Tx.p("trcirc_rounds", rounds, Tx.i(rounds))),
            Tx.s(TodayClock.clock(between)), Tx.s(TodayClock.clock(round)))
    }

    private fun exerciseKindKey(k: ExerciseKind) = when (k) {
        ExerciseKind.REPS -> "trx_kind_reps"
        ExerciseKind.LOAD_REPS -> "trx_kind_load_reps"
        ExerciseKind.TIME -> "trx_kind_time"
        ExerciseKind.HANG -> "trx_kind_hang"
        ExerciseKind.INTERVAL -> "trx_kind_interval"
        ExerciseKind.CLIMB -> "trx_kind_climb"
    }
}

/** One exercise of the "add exercise" list. */
class WorkoutExerciseChoiceUi(val slug: String, val name: String, val category: TrText)

/** A value the athlete sets before or corrects after a set: load | reps | seconds | rounds | edge. */
class WorkoutStepperUi(
    val code: String,
    val label: TrText,
    val value: TrText,
    /** Tapping the value types it: the dialog's title and its prefilled text; empty title = no typing. */
    val editTitle: TrText,
    val editInitial: String,
    val decimal: Boolean,
    val allowNegative: Boolean,
)

/**
 * One row of the exercise page. [kind]: `round` (a circuit round's label: [label]), `warmupsDone`
 * (done warm-ups folded into one row: [label], tap to unfold) or `set`.
 * A set row's [stateCode]: done | editing | active | planned.
 */
class WorkoutRowUi(
    val kind: String,
    val id: String,
    val stateCode: String,
    /** "1", "2 L", "W" (or the round / warm-up label for the other kinds). */
    val label: TrText,
    /** The exercise's name in a circuit; empty for a single exercise. */
    val name: String,
    // ── done ──
    val result: TrText,
    /** met | under | over | none */
    val outcomeCode: String,
    val loadLine: TrText,
    val reserveLine: TrText,
    /** The last set done: its reserve question, feedback and adjustment sit under it. */
    val isLatest: Boolean,
    val asksReserve: Boolean,
    /** -1 = no answer. */
    val rir: Int,
    val recordText: TrText,
    val capacityText: TrText,
    val adjustmentText: TrText,
    // ── planned ──
    val summary: TrText,
    /** -1 lowered, +1 raised by an earlier set of this session, 0 untouched. */
    val movedBy: Int,
    // ── active and editing ──
    val target: TrText,
    val percent: TrText,
    val steppers: List<WorkoutStepperUi>,
    val carriedText: TrText,
    val showsGrip: Boolean,
    val gripLabel: TrText,
    /** Rep results around the plan for "6 instead of 8" (active rep work only). */
    val resultChips: List<Int>,
    val typedResultInitial: String,
)

class WorkoutPlayerState(
    /** loading | noWorkout | empty | set | timer | rest | complete */
    val phaseCode: String,
    val title: TrText,
    val hasWorkout: Boolean,
    val pages: List<WorkoutPageUi>,
    val pageIndex: Int,
    val browsing: Boolean,
    // ── The page on screen ──
    val hasPage: Boolean,
    val pageIsCircuit: Boolean,
    val pageOpen: Boolean,
    val exerciseSlug: String,
    val exerciseName: String,
    /** "Pull · Set 2 of 4". */
    val positionLine: TrText,
    val sideBadge: TrText,
    val typeBadge: TrText,
    /** Circuits: "Circuit · round 2 of 3" and its rests. */
    val roundHeader: TrText,
    val circuitRests: TrText,
    val circuitMembers: TrText,
    val dayFormLine: TrText,
    /** The "still learning this value" line on work sets, and its explanation; empty when the value is known. */
    val learningShort: TrText,
    val learningText: TrText,
    val details: TrText,
    val rows: List<WorkoutRowUi>,
    val canSwap: Boolean,
    val canSkip: Boolean,
    val warmupAvailable: Boolean,
    val removeConfirm: TrText,
    val removeWeekly: TrText,
    /** "+ set" or "+ round". */
    val addSetLabel: TrText,
    /** end | skip | "" — the second footer button. */
    val footerEndCode: String,
    val footerEndLabel: TrText,
    // ── The main action ──
    /** done | test | timer | toActive | allDone */
    val mainCode: String,
    val mainLabel: TrText,
    // ── Rest ──
    val restArmed: Boolean,
    val restFinished: Boolean,
    val restRemaining: Int,
    val restTotal: Int,
    /** Epoch seconds the rest ends at, 0 while not resting. */
    val restEndsAtEpochSeconds: Long,
    val restHeadline: TrText,
    val restUpcoming: TrText,
    val restFinishedExercise: Boolean,
    val sideSwitch: Boolean,
    // ── Hang timer (phase timer) ──
    val timerWorkS: Int,
    val timerRestBetweenS: Int,
    val timerReps: Int,
    /** A single hang can be let go early (the seconds held are logged). */
    val timerReleasable: Boolean,
    val timerSide: TrText,
    val timerSound: Boolean,
    val timerVibration: Boolean,
    val timerVoice: Boolean,
    // ── End page ──
    val completeSummary: TrText,
    val anyDone: Boolean,
    val hasOpenSets: Boolean,
    // ── One-shot results ──
    val painText: TrText,
    val painInjuryId: String,
    val endedText: TrText,
    val asPlannedUndo: Boolean,
    /** The finished training's id (open its summary), or "". */
    val finishedWorkoutId: String,
    val discarded: Boolean,
    /** Typed exercises an open injury ruled out, or empty. */
    val skippedText: TrText,
    /**
     * A comeback session (its training id names an injury's plan): the end page and the finish dialog ask
     * `cb_finish_pain` 0–10 (`player_comeback_pain_<n>`) and show the notice (owner 2026-10-20).
     */
    val comeback: Boolean,
)

/**
 * The training player (Android `WorkoutPlayerScreen` + `ExercisePage` + `RestBar` +
 * `WorkoutPlayerViewModel`). Set ids come from the state's rows.
 */
class WorkoutPlayerScreenModel internal constructor(
    private val presenter: WorkoutPlayerPresenter,
    private val language: String,
) {
    private var finished = ""
    private var discarded = false
    private var skipped: List<String> = emptyList()
    /** Warm-up groups the athlete unfolded, by page key. */
    private val unfolded = mutableSetOf<String>()
    private var stateListener: (() -> Unit)? = null

    val currentState: WorkoutPlayerState get() = map(presenter.state.value, presenter.restTimer.value)

    fun watch(onState: (WorkoutPlayerState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch {
            presenter.events.collect { e ->
                when (e) {
                    is WorkoutPlayerEvent.Finished -> finished = e.workoutId
                    WorkoutPlayerEvent.Discarded -> discarded = true
                    is WorkoutPlayerEvent.SkippedForInjury -> skipped = e.slugs
                }
                onState(currentState)
            }
        }
        scope.launch {
            combine(presenter.state, presenter.restTimer) { s, t -> s to t }.collect { (s, t) -> onState(map(s, t)) }
        }
        stateListener = { onState(currentState) }
        return Subscription { stateListener = null; scope.cancel() }
    }

    fun close() = presenter.close()

    // ── Moving around ──
    fun showPage(index: Int) = presenter.showPage(index)
    fun backToActive() = presenter.backToActive()
    fun focus(setId: String) = presenter.focus(setId)
    fun edit(setId: String) = presenter.edit(setId)
    fun closeEdit() = presenter.closeEdit()
    fun reopen(setId: String) = presenter.reopen(setId)
    fun unfoldWarmups() {
        presenter.state.value.page?.let { unfolded += it.key.joinToString(",") }
        stateListener?.invoke()
    }

    // ── Logging ──
    fun completeCurrent() = presenter.completeCurrent()
    fun completeAs(value: Int) = presenter.completeAs(value.toDouble())
    fun completeHeld(heldSeconds: Double) = presenter.completeCurrent(heldSeconds)
    fun startTimer() = presenter.startTimer()
    fun cancelTimer() = presenter.cancelTimer()
    /** -1 takes the answer back. */
    fun setReserve(setId: String, rir: Int) = presenter.setReserve(setId, rir.takeIf { it >= 0 })

    // ── Values (Android `SetSteppers`) ──
    fun stepLoad(setId: String, direction: Int) = withSet(setId) { set, def, st ->
        val step = st.profile.smallestIncrementKg.coerceAtLeast(0.25)
        val minLoad = if (def.load == LoadMode.BODYWEIGHT_PLUS) -200.0 else 0.0
        val load = set.loadKg ?: 0.0
        if (direction < 0) set.copy(loadKg = snap(load - step, step).coerceAtLeast(minLoad))
        else set.copy(loadKg = snap(load + step, step).coerceAtMost(500.0))
    }

    /** [display] in the athlete's units. */
    fun setLoad(setId: String, display: Double) = withSet(setId) { set, def, st ->
        val minLoad = if (def.load == LoadMode.BODYWEIGHT_PLUS) -200.0 else 0.0
        if (!display.isFinite() || (display < 0 && def.load != LoadMode.BODYWEIGHT_PLUS)) null
        else set.copy(loadKg = Units.massFromDisplay(display, st.profile.units).coerceIn(minLoad, 500.0))
    }

    fun stepMain(setId: String, direction: Int) = withSet(setId) { set, def, _ ->
        when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.CLIMB -> {
                val reps = set.reps ?: 0
                set.copy(reps = (reps + direction).coerceIn(0, 999))
            }
            ExerciseKind.TIME, ExerciseKind.HANG -> {
                val seconds = (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble() ?: 10.0).roundToInt()
                val step = if (seconds >= 30) 5 else 1
                set.copy(durationS = (seconds + direction * step).coerceIn(1, 3600).toDouble())
            }
            ExerciseKind.INTERVAL -> null
        }
    }

    fun setMain(setId: String, value: Double) = withSet(setId) { set, def, _ ->
        if (!value.isFinite() || value < 0) null else when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.CLIMB -> set.copy(reps = value.roundToInt().coerceIn(0, 999))
            ExerciseKind.TIME, ExerciseKind.HANG -> set.copy(durationS = value.roundToInt().coerceIn(1, 3600).toDouble())
            ExerciseKind.INTERVAL -> null
        }
    }

    fun stepEdge(setId: String, direction: Int) = withSet(setId) { set, _, _ ->
        set.edgeMm?.let { edge -> set.copy(edgeMm = (edge.roundToInt() + direction).coerceIn(3, 80).toDouble()) }
    }

    /** Grip name (Grip enum) for hangs. */
    fun setGrip(setId: String, code: String) = withSet(setId) { set, _, _ ->
        Grip.entries.firstOrNull { it.name == code }?.let { set.copy(grip = it) }
    }

    val gripCodes: List<String> get() = Grip.entries.map { it.name }
    fun gripLabel(code: String): TrText = Grip.entries.firstOrNull { it.name == code }?.let { Tx.t(Tx.gripKey(it)) } ?: Tx.EMPTY

    private fun withSet(setId: String, change: (ExerciseSet, ExerciseDefinition, WorkoutPlayerRunState) -> ExerciseSet?) {
        val st = presenter.state.value
        val set = st.sets.firstOrNull { it.id == setId } ?: return
        val def = st.def(set.exerciseSlug) ?: return
        change(set, def, st)?.takeIf { it != set }?.let { presenter.update(it) }
    }

    // ── The exercise as a whole ──
    fun alternatives(): List<TodayAlternativeUi> = try {
        presenter.alternatives().map { TodayScreenModel.alternativeUi(it, language) }
    } catch (e: Exception) {
        emptyList()
    }
    fun swapExercise(to: String) = presenter.swapExercise(to)
    fun skipExercise() = presenter.skipExercise()
    fun endExercise() = presenter.endExercise()
    fun undoEnd() = presenter.undoEnd()
    fun consumeEnded() = presenter.consumeEnded()
    fun addSet() = presenter.addSet()
    fun addWarmups() = presenter.addWarmups()
    fun removeExercise() = presenter.removeExercise()
    fun addExercise(slug: String) = presenter.addExercise(slug)

    /** The catalogue (own exercises included) for "add exercise": names in [language], best matches first. */
    fun searchExercises(query: String, limit: Int): List<WorkoutExerciseChoiceUi> = try {
        presenter.catalog().search(query.take(80), language)
            .filter { it.category != ExerciseCategoryV2.WARMUP || query.isNotBlank() }
            .take(limit.coerceIn(1, 200))
            .map { WorkoutExerciseChoiceUi(it.slug, it.name(language), Tx.t(Tx.categoryKey(it.category))) }
    } catch (e: Exception) {
        emptyList()
    }
    fun asPlanned() = presenter.asPlanned()
    fun undoAsPlanned() = presenter.undoAsPlanned()
    fun consumeAsPlannedUndo() = presenter.consumeAsPlannedUndo()

    // ── It hurts (Android `PainSheet`) ──
    val painRegionCodes: List<String> get() = InjuryRegion.entries.map { it.name }
    val painSideCodes: List<String> get() = InjurySide.entries.map { it.name }
    fun regionLabel(code: String): TrText = InjuryRegion.entries.firstOrNull { it.name == code }?.let { Tx.t(Tx.regionKey(it)) } ?: Tx.EMPTY
    fun injurySideLabel(code: String): TrText = InjurySide.entries.firstOrNull { it.name == code }?.let { Tx.t(Tx.injurySideKey(it)) } ?: Tx.EMPTY
    /** The side the pain sheet starts with: the active set's side, "" without. */
    val painStartSide: String get() = when (activeOnPage()?.side) {
        Side.LEFT -> InjurySide.LEFT.name
        Side.RIGHT -> InjurySide.RIGHT.name
        null -> ""
    }
    /** Pain from this level on pauses climbing when remembered (Android `PAIN_PAUSE_SEVERITY`). */
    val painPauseSeverity: Int get() = com.cruxcoach.app.athlete.AthleteService.PAIN_PAUSE_SEVERITY

    /** action: swap | lighter | end; side "" = none. */
    fun reportPain(regionCode: String, sideCode: String, severity: Int, actionCode: String, remember: Boolean) {
        val region = InjuryRegion.entries.firstOrNull { it.name == regionCode } ?: return
        val side = InjurySide.entries.firstOrNull { it.name == sideCode }
        val action = when (actionCode) {
            "swap" -> PainAction.SWAP
            "lighter" -> PainAction.LIGHTER
            "end" -> PainAction.END_EXERCISE
            else -> return
        }
        presenter.reportPain(region, side, severity.coerceIn(0, 9), action, remember)
    }
    fun consumePainResult() = presenter.consumePainResult()
    fun forgetInjury(id: String) = presenter.forgetInjury(id)

    // ── Rest ──
    fun adjustRest(deltaSeconds: Int) = presenter.adjustRest(deltaSeconds)
    fun endRest() = presenter.endRest()

    // ── Finish ──
    /** rpe 0 = not answered. */
    fun finish(rpe: Int, notes: String) = finish(rpe, notes, -1)
    /** A comeback session's end: [comebackPain] 0–10 is the highest pain at the injured spot, -1 = not answered. */
    fun finish(rpe: Int, notes: String, comebackPain: Int) = presenter.finish(rpe.takeIf { it in 1..10 },
        notes.trim().take(2000).ifBlank { null }, comebackPain.takeIf { it in 0..10 })
    fun discard() = presenter.discard()
    fun consumeFinished() { finished = ""; discarded = false }
    fun consumeSkipped() { skipped = emptyList(); stateListener?.invoke() }

    /**
     * The typed-entry sheet's reader: off the main thread with Android's debounce (one per sheet; Swift closes it).
     * Nothing a preview shows is read on the main thread any more, except the text "Add" finds unread.
     */
    fun typedReader(): WorkoutTypedReaderModel = typedReaderOn(MainScope(), Dispatchers.Default)

    internal fun typedReaderOn(scope: CoroutineScope, reader: CoroutineDispatcher): WorkoutTypedReaderModel =
        WorkoutTypedReaderModel(presenter, language, scope, reader)

    /** What typed lines become, read at once (tests; the sheet uses [typedReader]). */
    fun previewTyped(text: String): WorkoutTypedPreviewUi =
        WorkoutTypedPreviews.preview(parseTyped(text), language, presenter.state.value.profile.units, presenter.catalog())

    /** Adds the typed exercises (new names become own exercises); one an injury rules out is reported in [WorkoutPlayerState.skippedText]. */
    fun addTyped(text: String) = presenter.addTyped(parseTyped(text))

    private fun parseTyped(text: String) = try {
        com.cruxcoach.athlete.logic.WorkoutTextParser.parse(text.take(com.cruxcoach.athlete.logic.WorkoutTextParser.MAX_CHARS), presenter.catalog())
    } catch (e: Exception) {
        com.cruxcoach.athlete.logic.WorkoutTextParser.Result(null, emptyList())
    }

    private fun activeOnPage(): ExerciseSet? {
        val st = presenter.state.value
        val page = st.page ?: return null
        return st.current?.set?.takeIf { c -> !c.isCompleted && c.blockIndex in page.blockIndexes } ?: page.sets.firstOrNull { !it.isCompleted }
    }

    private var lastGood: WorkoutPlayerState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(st: WorkoutPlayerRunState, timer: RestTimerState): WorkoutPlayerState = try {
        mapUnsafe(st, timer).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(WorkoutPlayerRunState(), RestTimerState())
    }

    private fun mapUnsafe(st: WorkoutPlayerRunState, timer: RestTimerState): WorkoutPlayerState =
        PlayerMapper(st, timer, language, presenter.canSwap(), presenter.warmupAvailable(),
            st.page?.key?.joinToString(",") in unfolded, finished, discarded,
            skipped.joinToString(", ") { presenter.catalog().fallbackFor(it).name(language) }) { presenter.weeklyFor(it) }.build()

    companion object {
        internal fun snap(value: Double, step: Double): Double = (value / step).roundToInt() * step
    }
}

private class PlayerMapper(
    val st: WorkoutPlayerRunState,
    val timer: RestTimerState,
    val language: String,
    val canSwap: Boolean,
    val warmupAvailable: Boolean,
    val warmupsUnfolded: Boolean,
    val finished: String,
    val discarded: Boolean,
    /** Typed exercises an injury ruled out, by name; "" without. */
    val skippedNames: String,
    val weeklyFor: (String) -> com.cruxcoach.athlete.logic.AreaProgress?,
) {
    val units: UnitSystem = st.profile.units
    val page: WorkoutPlayerPage? = st.page
    val active: ExerciseSet? = page?.let { p -> st.current?.set?.takeIf { c -> !c.isCompleted && c.blockIndex in p.blockIndexes } }

    fun name(slug: String): String = st.def(slug)?.name(language) ?: slug

    fun build(): WorkoutPlayerState {
        val first = page?.sets?.firstOrNull()
        val def = first?.let { st.def(it.exerciseSlug) }
        val armed = st.restArmed
        val remaining = if (armed) timer.secondsRemaining else 0
        val timed = st.current
        return WorkoutPlayerState(
            phaseCode = when (st.phase) {
                WorkoutPlayerPhase.LOADING -> "loading"
                WorkoutPlayerPhase.NO_WORKOUT -> "noWorkout"
                WorkoutPlayerPhase.EMPTY -> "empty"
                WorkoutPlayerPhase.SET -> "set"
                WorkoutPlayerPhase.TIMER -> "timer"
                WorkoutPlayerPhase.REST -> "rest"
                WorkoutPlayerPhase.COMPLETE -> "complete"
            },
            title = st.workout?.let { Tx.workoutTitle(it) } ?: Tx.t("trp_title"),
            hasWorkout = st.workout != null,
            pages = st.pages.mapIndexed { i, p ->
                WorkoutPageUi(i, members(p).joinToString(" → ") { name(it.exerciseSlug) }, p.done, p.total, p.open, i == st.pageIndex,
                    p.perSide, p.setsDone, p.setsTotal)
            },
            pageIndex = st.pageIndex,
            browsing = st.browsing,
            hasPage = page != null,
            pageIsCircuit = page?.circuit != null,
            pageOpen = page?.open ?: false,
            exerciseSlug = def?.slug ?: "",
            exerciseName = def?.name(language) ?: "",
            positionLine = positionLine(),
            sideBadge = active?.side?.let { Tx.t(Tx.sideKey(it)) } ?: Tx.EMPTY,
            typeBadge = if (active != null && def != null && active.setType != SetType.WORK &&
                !(active.setType == SetType.WARMUP && def.category == ExerciseCategoryV2.WARMUP)) Tx.t(Tx.setTypeKey(active.setType)) else Tx.EMPTY,
            roundHeader = roundHeader(),
            circuitRests = circuitRests(),
            circuitMembers = if (page?.circuit != null) Tx.plain(members(page).joinToString(" → ") { name(it.exerciseSlug) }) else Tx.EMPTY,
            dayFormLine = dayFormLine(),
            learningShort = learning()?.first ?: Tx.EMPTY,
            learningText = learning()?.second ?: Tx.EMPTY,
            details = details(),
            rows = rows(),
            canSwap = canSwap,
            canSkip = page != null && page.open && page.sets.none { it.isCompleted },
            warmupAvailable = warmupAvailable,
            removeConfirm = def?.let { Tx.t("trw_remove_exercise_confirm", Tx.s(it.name(language))) } ?: Tx.EMPTY,
            removeWeekly = def?.let { weeklyFor(it.slug) }?.let { Tx.weeklyHint(it) } ?: Tx.EMPTY,
            addSetLabel = Tx.t(if (page?.circuit != null) "trpg_add_round" else "trw_add_set"),
            footerEndCode = footerEnd(),
            footerEndLabel = when (footerEnd()) {
                "end" -> Tx.t(if (page?.circuit != null) "trpg_end_circuit" else "trpg_end_exercise")
                "skip" -> Tx.t("trp_skip_exercise")
                else -> Tx.EMPTY
            },
            mainCode = mainCode(),
            mainLabel = mainLabel(),
            restArmed = armed,
            restFinished = st.restFinished,
            restRemaining = remaining,
            restTotal = if (armed) timer.totalSeconds.coerceAtLeast(1) else 1,
            restEndsAtEpochSeconds = if (armed && timer.isRunning) nowMs() / 1000 + timer.secondsRemaining else 0L,
            restHeadline = restHeadline(),
            restUpcoming = restUpcoming(),
            restFinishedExercise = finishedExercise(),
            sideSwitch = st.sideSwitch,
            timerWorkS = timed?.let { timerWork(it) } ?: 0,
            timerRestBetweenS = timed?.let { if (it.def.kind == ExerciseKind.INTERVAL)
                (it.set.restBetweenS ?: it.def.defaults.restBetweenS?.toDouble() ?: 3.0).roundToInt() else 0 } ?: 0,
            timerReps = timed?.let { if (it.def.kind == ExerciseKind.INTERVAL) it.set.repsPerSet ?: it.def.defaults.repsPerSet ?: 6 else 1 } ?: 1,
            timerReleasable = timed != null && timed.def.kind != ExerciseKind.INTERVAL,
            timerSide = timed?.set?.side?.let { Tx.t(Tx.sideKey(it)) } ?: Tx.EMPTY,
            timerSound = st.profile.timerSound,
            timerVibration = st.profile.timerVibration,
            timerVoice = st.profile.timerVoice,
            completeSummary = completeSummary(),
            anyDone = st.sets.any { it.isCompleted },
            hasOpenSets = st.sets.any { !it.isCompleted },
            painText = painText(),
            painInjuryId = st.painResult?.injuryId ?: "",
            endedText = endedText(),
            asPlannedUndo = st.asPlannedUndo != null,
            finishedWorkoutId = finished,
            discarded = discarded,
            skippedText = if (skippedNames.isEmpty()) Tx.EMPTY else Tx.t("trw_typed_skipped", Tx.s(skippedNames)),
            comeback = com.cruxcoach.athlete.logic.Comeback.parseRoutineId(st.workout?.routineId) != null,
        )
    }

    private fun members(p: WorkoutPlayerPage): List<ExerciseSet> = p.blockIndexes.mapNotNull { b -> p.sets.firstOrNull { it.blockIndex == b } }

    private fun timerWork(item: WorkoutPlayerItem): Int {
        val set = item.set
        val def = item.def
        return (if (def.kind == ExerciseKind.INTERVAL) (set.workS ?: def.defaults.workS?.toDouble() ?: 7.0).roundToInt()
            else (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble() ?: 10.0).roundToInt()).coerceAtLeast(1)
    }

    private fun workNumberOf(p: WorkoutPlayerPage, set: ExerciseSet): Int? {
        if (set.setType == SetType.WARMUP) return null
        val indexes = p.sets.filter { it.blockIndex == set.blockIndex && it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().sorted()
        return indexes.indexOf(set.setIndex).takeIf { it >= 0 }?.plus(1)
    }

    /** "1", "2 L", "W" (`rowLabel`). */
    private fun rowLabel(p: WorkoutPlayerPage, set: ExerciseSet): TrText {
        val side = set.side?.let { Tx.t(if (it == Side.LEFT) "trw_side_short_left" else "trw_side_short_right") }
        val base = when {
            set.setType == SetType.WARMUP -> Tx.t("trw_warmup_short")
            p.circuit != null -> null
            else -> Tx.plain((workNumberOf(p, set) ?: 1).toString())
        }
        return Tx.join(listOfNotNull(base, side), " ")
    }

    /** "Pull · Set 2 of 4" under the name (ExerciseHeader). */
    private fun positionLine(): TrText {
        val p = page ?: return Tx.EMPTY
        val def = p.sets.firstOrNull()?.let { st.def(it.exerciseSlug) } ?: return Tx.EMPTY
        val position = active?.let { set ->
            if (set.setType == SetType.WARMUP) Tx.t("trpg_warmup_row",
                Tx.i(p.sets.filter { it.setType == SetType.WARMUP }.indexOfFirst { it.id == set.id } + 1),
                Tx.i(p.sets.count { it.setType == SetType.WARMUP }))
            else Tx.t("trp_set_of", Tx.i(workNumberOf(p, set) ?: 1),
                Tx.i(p.sets.filter { it.setType != SetType.WARMUP && it.blockIndex == set.blockIndex }.map { it.setIndex }.distinct().size))
        }
        val category = Tx.t(Tx.categoryKey(def.category)).takeIf {
            !(def.category == ExerciseCategoryV2.WARMUP && active?.setType == SetType.WARMUP)
        }
        return Tx.join(listOfNotNull(category, position))
    }

    private fun roundHeader(): TrText {
        val p = page?.takeIf { it.circuit != null } ?: return Tx.EMPTY
        val work = p.sets.filter { it.setType != SetType.WARMUP }
        val rounds = work.map { it.setIndex }.distinct().sorted()
        val round = (active?.let { a -> rounds.indexOf(a.setIndex) } ?: rounds.indexOfLast { r -> work.any { it.setIndex == r && it.isCompleted } })
            .coerceAtLeast(0) + 1
        return Tx.t("trpg_round_header", Tx.i(round), Tx.i(rounds.size.coerceAtLeast(1)))
    }

    private fun restText(seconds: Int): TrText = if (seconds >= 60) Tx.plain(TodayClock.clock(seconds)) else Tx.seconds(seconds)

    private fun circuitRests(): TrText {
        val p = page?.takeIf { it.circuit != null } ?: return Tx.EMPTY
        val firstWork = p.sets.firstOrNull { it.setType != SetType.WARMUP } ?: return Tx.EMPTY
        return Tx.t("trpg_circuit_rests", Tx.x(restText(firstWork.restS ?: 0)), Tx.x(restText(firstWork.roundRestS ?: 0)))
    }

    /** Why this exercise is lighter today, only on pages it changed (DayFormLine). */
    private fun dayFormLine(): TrText {
        val p = page ?: return Tx.EMPTY
        val m = st.dayForm ?: return Tx.EMPTY
        val defs = p.sets.filter { it.setType == SetType.WORK }.mapNotNull { st.def(it.exerciseSlug) }.distinctBy { it.slug }
        val touched = defs.filter { ReadinessModifiers.affects(it, m) }
        if (touched.isEmpty()) return Tx.EMPTY
        val parts = buildList {
            if (m.setsDelta < 0) add(Tx.t(if (p.circuit != null) "trpg_dayform_round_less" else "trpg_dayform_set_less"))
            if (m.dropMaxFinger && touched.any { ReadinessModifiers.isMaxFinger(it) }) add(Tx.t("trpg_dayform_finger_cap"))
            // Only where a load is planned (device test 2026-10-17: a body-weight pull-up said "load −5 %").
            val loaded = p.sets.filter { it.setType == SetType.WORK && (it.targetLoadKg ?: 0.0) != 0.0 }
                .mapNotNull { st.def(it.exerciseSlug) }.distinctBy { it.slug }
            loaded.maxOfOrNull { ReadinessModifiers.loadCutPercent(it, m) }?.takeIf { it > 0 }?.let { add(Tx.t("trpg_dayform_load", Tx.i(it))) }
        }
        if (parts.isEmpty()) return Tx.EMPTY
        val why = m.reason?.let { Tx.t(Tx.readinessReasonShortKey(it)) } ?: Tx.t("trpg_dayform_climbed")
        return Tx.t("trpg_dayform", Tx.x(why), Tx.x(Tx.join(parts)))
    }

    /** Android `LearningBadge`: own load first, then holds, then the learning state; only on an active work set. */
    private fun learning(): Pair<TrText, TrText>? {
        val set = active?.takeIf { it.setType == SetType.WORK } ?: return null
        val l = st.learning[set.exerciseSlug] ?: return null
        val known = l.state == com.cruxcoach.athlete.logic.LearningState.State.Known
        return when {
            l.ownLoad -> Tx.t("trc_learning_own_short") to Tx.t("trc_learning_own")
            l.hold && !known -> Tx.t("trc_learning_hold_short") to Tx.t("trc_learning_hold")
            else -> when (val s = l.state) {
                com.cruxcoach.athlete.logic.LearningState.State.Known -> null
                com.cruxcoach.athlete.logic.LearningState.State.None ->
                    if (l.guessed) Tx.t("trc_learning_guess_short") to Tx.t("trc_learning_guess")
                    else Tx.t("trc_learning_none_short") to Tx.t("trc_learning_none")
                com.cruxcoach.athlete.logic.LearningState.State.Estimated -> Tx.t("trc_learning_estimated_short") to Tx.t("trc_learning_estimated")
                is com.cruxcoach.athlete.logic.LearningState.State.Learning ->
                    Tx.t("trc_learning_progress_short", Tx.i(s.sessions), Tx.i(s.needed)) to Tx.t("trc_learning_progress", Tx.i(s.sessions), Tx.i(s.needed))
            }
        }
    }

    /** Per-block load and interval rhythm (PageIntro). */
    private fun details(): TrText {
        val p = page ?: return Tx.EMPTY
        val set = active ?: p.sets.firstOrNull() ?: return Tx.EMPTY
        val def = st.def(set.exerciseSlug) ?: return Tx.EMPTY
        return Tx.join(listOfNotNull(
            Tx.perBlockText(set, def, units),
            if (def.kind == ExerciseKind.INTERVAL) Tx.t("trw_interval_format",
                Tx.i((set.workS ?: def.defaults.workS?.toDouble() ?: 7.0).roundToInt()),
                Tx.i((set.restBetweenS ?: def.defaults.restBetweenS?.toDouble() ?: 3.0).roundToInt()),
                Tx.i(set.repsPerSet ?: def.defaults.repsPerSet ?: 6)) else null,
        ))
    }

    private fun footerEnd(): String {
        val p = page ?: return ""
        val anyDone = p.sets.any { it.isCompleted && it.setType != SetType.WARMUP }
        return when {
            p.open && anyDone -> "end"
            p.open && p.circuit != null -> "skip"
            else -> ""
        }
    }

    private fun rows(): List<WorkoutRowUi> {
        val p = page ?: return emptyList()
        val withName = p.circuit != null
        val warmups = p.sets.filter { it.setType == SetType.WARMUP }
        return buildList {
            if (warmups.isNotEmpty()) {
                val folded = warmups.all { it.isCompleted } && !warmupsUnfolded && warmups.none { it.id == st.editingId }
                if (folded) add(labelRow("warmupsDone", "warmups", Tx.t("trpg_warmup_row", Tx.i(warmups.size), Tx.i(warmups.size))))
                else warmups.forEach { set -> setRow(set, p, withName)?.let { add(it) } }
            }
            if (p.circuit != null) {
                val rounds = p.sets.filter { it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().sorted()
                rounds.forEachIndexed { r, setIndex ->
                    add(labelRow("round", "round_$setIndex", Tx.t("trpg_round", Tx.i(r + 1))))
                    p.sets.filter { it.setType != SetType.WARMUP && it.setIndex == setIndex }.forEach { set -> setRow(set, p, true)?.let { add(it) } }
                }
            } else p.sets.filter { it.setType != SetType.WARMUP }.forEach { set -> setRow(set, p, false)?.let { add(it) } }
        }
    }

    private fun labelRow(kind: String, id: String, label: TrText) = WorkoutRowUi(
        kind, id, "", label, "", Tx.EMPTY, "none", Tx.EMPTY, Tx.EMPTY, false, false, -1, Tx.EMPTY, Tx.EMPTY, Tx.EMPTY,
        Tx.EMPTY, 0, Tx.EMPTY, Tx.EMPTY, emptyList(), Tx.EMPTY, false, Tx.EMPTY, emptyList(), "",
    )

    private fun setRow(set: ExerciseSet, p: WorkoutPlayerPage, withName: Boolean): WorkoutRowUi? {
        val def = st.def(set.exerciseSlug) ?: return null
        val stateCode = when {
            set.isCompleted && st.editingId == set.id -> "editing"
            set.isCompleted -> "done"
            set.id == active?.id && st.editingId == null -> "active"
            else -> "planned"
        }
        val outcome = if (set.isCompleted) SetOutcomes.of(def, set).main else null
        val latest = set.isCompleted && st.lastDone?.set?.id == set.id
        val reserveKinds = setOf(ExerciseKind.REPS, ExerciseKind.LOAD_REPS, ExerciseKind.HANG)
        val asks = when (stateCode) {
            "done" -> latest && set.setType == SetType.WORK && def.kind in reserveKinds
            "editing" -> set.setType != SetType.WARMUP && def.kind in reserveKinds
            else -> false
        }
        val fb = st.feedback?.takeIf { latest && it.def.slug == def.slug }
        val steppers = when (stateCode) {
            "active" -> activeSteppers(set, def)
            "editing" -> steppers(set, def, showMain = true)
            else -> emptyList()
        }
        val carried = if (stateCode != "active" || set.loadKg == set.targetLoadKg) 0 else st.sets.count {
            !it.isCompleted && it.blockIndex == set.blockIndex && it.side == set.side && it.setType == set.setType &&
                it.setIndex > set.setIndex && it.loadKg == set.loadKg
        }
        val chips = if (stateCode == "active") resultChips(set, def) else emptyList()
        return WorkoutRowUi(
            kind = "set",
            id = set.id,
            stateCode = stateCode,
            label = rowLabel(p, set),
            name = if (withName) def.name(language) else "",
            result = if (set.isCompleted) resultText(set, def) ?: Tx.EMPTY else Tx.EMPTY,
            outcomeCode = when (outcome) {
                TargetOutcome.MET -> "met"
                TargetOutcome.UNDER -> "under"
                TargetOutcome.OVER -> "over"
                null -> "none"
            },
            loadLine = if (set.isCompleted) Tx.loadText(set, def, units)
                ?.takeIf { def.load != LoadMode.BODYWEIGHT_PLUS || (set.loadKg ?: 0.0) != 0.0 } ?: Tx.EMPTY else Tx.EMPTY,
            reserveLine = set.rir?.takeIf { set.isCompleted }?.let { Tx.t("trw_rir_short", Tx.i(it)) } ?: Tx.EMPTY,
            isLatest = latest,
            asksReserve = asks,
            rir = set.rir ?: -1,
            recordText = fb?.record?.let { Tx.t("trp_record", Tx.x(Tx.recordValueText(fb.def, it, units))) } ?: Tx.EMPTY,
            capacityText = fb?.raisedCapacity?.let {
                Tx.t(if (fb.firstValue) "trp_benchmark_first" else "trp_benchmark_raised", Tx.x(Tx.capacityText(fb.def, it, units)))
            } ?: Tx.EMPTY,
            adjustmentText = if (latest) st.adjustment?.let { Tx.adjustmentText(it, units, set.side) } ?: Tx.EMPTY else Tx.EMPTY,
            summary = if (stateCode == "planned") Tx.targetSummary(set, def, null, units) ?: Tx.EMPTY else Tx.EMPTY,
            movedBy = if (!set.isCompleted) movedBy(set) else 0,
            target = if (stateCode == "active") targetLine(set, def) ?: Tx.EMPTY else Tx.EMPTY,
            percent = if (stateCode == "active") Tx.percentText(set, def, st.bodyweight) ?: Tx.EMPTY else Tx.EMPTY,
            steppers = steppers,
            carriedText = if (carried > 0) Tx.p("trp_load_carried", carried, Tx.i(carried)) else Tx.EMPTY,
            showsGrip = stateCode == "active" && (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL),
            gripLabel = set.grip?.let { Tx.t(Tx.gripKey(it)) } ?: Tx.t("trw_grip_none"),
            resultChips = chips,
            typedResultInitial = (set.reps ?: set.targetReps ?: 0).toString(),
        )
    }

    /** "8/8", "7/10 s", "8" without a target (`resultText`). */
    private fun resultText(set: ExerciseSet, def: ExerciseDefinition): TrText? = when (def.kind) {
        ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> set.reps?.let { reps ->
            Tx.plain(set.targetReps?.takeIf { set.setType != SetType.TEST }?.let { "$reps/$it" } ?: reps.toString())
        }
        ExerciseKind.TIME, ExerciseKind.HANG -> set.durationS?.roundToInt()?.let { held ->
            val target = set.targetDurationS?.roundToInt()?.takeIf { set.setType != SetType.TEST }
            if (target != null) Tx.t("trpg_seconds_of", Tx.i(held), Tx.i(target)) else Tx.seconds(held)
        }
        ExerciseKind.CLIMB -> set.reps?.let { Tx.p("trp_rounds", it, Tx.i(it)) }
        ExerciseKind.INTERVAL -> null
    }

    /** "Target 8 reps", "Target 10 s" (`targetLine`). */
    private fun targetLine(set: ExerciseSet, def: ExerciseDefinition): TrText? = when (def.kind) {
        ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> if (set.setType == SetType.TEST) null
            else (set.targetReps ?: set.reps)?.let { Tx.t("trpg_target", Tx.x(Tx.p("trp_reps_big", it, Tx.i(it)))) }
        ExerciseKind.TIME, ExerciseKind.HANG -> if (set.setType == SetType.TEST) null
            else (set.targetDurationS ?: set.durationS ?: def.defaults.durationS?.toDouble())?.roundToInt()?.let { Tx.t("trw_target_seconds", Tx.i(it)) }
        ExerciseKind.CLIMB, ExerciseKind.INTERVAL -> null
    }

    /** Rep results around the plan, for "6 instead of 8"; none for tests, holds, intervals and climbing. */
    private fun resultChips(set: ExerciseSet, def: ExerciseDefinition): List<Int> {
        if (set.setType == SetType.TEST) return emptyList()
        if (def.kind != ExerciseKind.REPS && def.kind != ExerciseKind.LOAD_REPS) return emptyList()
        val target = set.reps ?: set.targetReps ?: return emptyList()
        return listOf(target - 2, target - 1, target + 1, target + 2).filter { it in 0..999 }
    }

    /** The values set before the set (`ActiveValues`): reps only for tests, timed and climbing work. */
    private fun activeSteppers(set: ExerciseSet, def: ExerciseDefinition): List<WorkoutStepperUi> {
        val loaded = def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL
        val timed = def.kind == ExerciseKind.TIME || def.kind == ExerciseKind.HANG
        val needs = loaded || timed || def.kind == ExerciseKind.CLIMB || set.setType == SetType.TEST ||
            def.kind == ExerciseKind.INTERVAL && set.edgeMm != null
        if (!needs) return emptyList()
        return steppers(set, def, showMain = set.setType == SetType.TEST || timed || def.kind == ExerciseKind.CLIMB)
    }

    /** `SetSteppers`. */
    private fun steppers(set: ExerciseSet, def: ExerciseDefinition, showMain: Boolean): List<WorkoutStepperUi> = buildList {
        if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
            val load = set.loadKg ?: 0.0
            val display = Units.massToDisplay(load, units)
            add(WorkoutStepperUi("load", Tx.t("trw_load_label"), Tx.loadText(set, def, units) ?: Tx.mass(load, units),
                Tx.t("trw_load_input", Tx.s(Units.massUnit(units))), plainNumber(display, 2), true, def.load == LoadMode.BODYWEIGHT_PLUS))
        }
        if (showMain) when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                val reps = set.reps ?: 0
                add(WorkoutStepperUi("reps", Tx.t("trw_reps_label"), Tx.plain(reps.toString()), Tx.t("trw_reps_label"), reps.toString(), false, false))
            }
            ExerciseKind.TIME, ExerciseKind.HANG -> {
                val seconds = (set.durationS ?: set.targetDurationS ?: def.defaults.durationS?.toDouble() ?: 10.0).roundToInt()
                add(WorkoutStepperUi("seconds", Tx.t("trw_seconds_label"), Tx.seconds(seconds), Tx.t("trw_seconds_label"), seconds.toString(), false, false))
            }
            ExerciseKind.CLIMB -> {
                val rounds = set.reps ?: 0
                add(WorkoutStepperUi("rounds", Tx.t("trw_rounds_label"), Tx.plain(rounds.toString()), Tx.EMPTY, "", false, false))
            }
            ExerciseKind.INTERVAL -> Unit
        }
        if (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) {
            set.edgeMm?.let { edge ->
                add(WorkoutStepperUi("edge", Tx.t("trw_edge_label"), Tx.t("tr_format_mm", Tx.s(edge.roundToInt().toString())), Tx.EMPTY, "", false, false))
            }
        }
    }

    /** "12.5" with a dot, for a text field the athlete edits (parsed back with comma or dot). */
    private fun plainNumber(value: Double, decimals: Int): String {
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        val rounded = (value * factor).roundToInt() / factor
        if (kotlin.math.abs(rounded - rounded.roundToInt()) < 1e-9) return rounded.roundToInt().toString()
        return rounded.toString().trimEnd('0').trimEnd('.')
    }

    /** −1/+1 when an earlier set of this session moved this one's plan (same block and side). */
    private fun movedBy(set: ExerciseSet): Int {
        val cause = st.sets.filter { d ->
            d.isCompleted && d.blockIndex == set.blockIndex && d.setIndex < set.setIndex && (d.side == null || d.side == set.side) &&
                st.adjustments.containsKey(d.id)
        }.maxByOrNull { it.setIndex } ?: return 0
        val adj = st.adjustments[cause.id] ?: return 0
        val sum = adj.loadDeltaKg + adj.repsDelta + adj.durationDeltaS
        return when { sum < -1e-9 -> -1; sum > 1e-9 -> 1; else -> 0 }
    }

    private fun mainCode(): String = when (st.phase) {
        WorkoutPlayerPhase.COMPLETE -> "allDone"
        else -> {
            val a = st.current?.takeIf { c -> !c.set.isCompleted && page != null && c.set.blockIndex in page.blockIndexes }
            when {
                a != null -> {
                    val timed = a.def.kind == ExerciseKind.INTERVAL || a.def.kind == ExerciseKind.HANG || a.def.kind == ExerciseKind.TIME
                    when {
                        a.set.setType == SetType.TEST && !timed -> "test"
                        timed -> "timer"
                        else -> "done"
                    }
                }
                st.current != null -> "toActive"
                else -> "allDone"
            }
        }
    }

    private fun mainLabel(): TrText {
        val a = st.current ?: return Tx.t("trpg_all_done")
        return when (mainCode()) {
            "test" -> Tx.t("trpg_log_test")
            "timer" -> {
                val seconds = if (a.def.kind == ExerciseKind.INTERVAL) null
                    else (a.set.durationS ?: a.set.targetDurationS ?: a.def.defaults.durationS?.toDouble())?.roundToInt()
                seconds?.let { Tx.t("trpg_start_seconds", Tx.i(it)) } ?: Tx.t("trp_start_timer")
            }
            "done" -> Tx.mainValue(a.set, a.def)?.let { Tx.t("trpg_done_planned", Tx.x(it)) } ?: Tx.t("trp_done_set")
            "toActive" -> Tx.t("trpg_to_next")
            else -> Tx.t("trpg_all_done")
        }
    }

    /** The exercise is finished and the next set is another one (RestBar). */
    private fun finishedExercise(): Boolean {
        val done = st.lastDone ?: return false
        val next = st.next ?: st.current ?: return false
        return done.set.blockIndex != next.set.blockIndex &&
            st.sets.none { !it.isCompleted && it.blockIndex == done.set.blockIndex } && st.pages.none { p ->
                done.set.blockIndex in p.blockIndexes && next.set.blockIndex in p.blockIndexes
            }
    }

    private fun restHeadline(): TrText {
        val done = st.lastDone
        val next = st.next ?: st.current
        return when {
            st.restFinished -> Tx.t("trp_rest_go")
            finishedExercise() && done != null -> {
                val work = st.sets.filter { it.blockIndex == done.set.blockIndex && it.setType == SetType.WORK }
                val onTarget = work.count { s -> SetOutcomes.of(done.def, s).main.let { it == TargetOutcome.MET || it == TargetOutcome.OVER } }
                // A warm-up exercise has no work sets: just "done".
                if (work.isEmpty()) Tx.t("trpg_exercise_done_plain", Tx.s(done.def.name(language)))
                else Tx.t("trpg_exercise_done", Tx.s(done.def.name(language)), Tx.i(onTarget), Tx.i(work.size))
            }
            st.sideSwitch -> st.next?.set?.side?.let { Tx.join(listOf(Tx.t("trp_side_switch"), Tx.t(Tx.sideKey(it))), " → ") }
                ?: Tx.t("trp_side_switch")
            // A circuit: the short rest names the next exercise, the long one the round just done.
            done != null && next != null && done.set.circuit != null && next.set.circuit == done.set.circuit ->
                if (next.set.setIndex == done.set.setIndex) Tx.t("trpg_next_member", Tx.s(next.def.name(language)))
                else {
                    val rounds = st.sets.filter { it.circuit == done.set.circuit && it.setType != SetType.WARMUP }
                        .map { it.setIndex }.distinct().sorted()
                    Tx.t("trpg_round_rest", Tx.i(rounds.indexOf(done.set.setIndex) + 1))
                }
            else -> Tx.t("trp_rest_title")
        }
    }

    private fun restUpcoming(): TrText {
        val next = st.next ?: st.current ?: return Tx.EMPTY
        if (!finishedExercise()) return Tx.EMPTY
        val parts = listOfNotNull(Tx.plain(next.def.name(language)), Tx.targetSummary(next.set, next.def, null, units))
        return Tx.t("trp_next_after", Tx.x(Tx.join(parts)))
    }

    private fun completeSummary(): TrText {
        val doneSets = st.sets.count { it.isCompleted && it.setType != SetType.WARMUP }
        val exercises = st.sets.filter { it.isCompleted }.map { it.blockIndex }.distinct().size
        val minutes = st.workout?.let { ((nowMs() - it.startedAt) / 60_000L).toInt().coerceAtLeast(0) } ?: 0
        return Tx.join(listOf(Tx.p("trpg_sets_count", doneSets, Tx.i(doneSets)), Tx.p("trpg_exercises_count", exercises, Tx.i(exercises)),
            Tx.t("trpg_minutes", Tx.i(minutes))))
    }

    private fun painText(): TrText {
        val r = st.painResult ?: return Tx.EMPTY
        val head = when (r.action) {
            PainAction.SWAP -> Tx.t("trp2_pain_done_swap", Tx.s(st.painNewDef?.name(language) ?: ""))
            PainAction.LIGHTER -> Tx.t("trp2_pain_done_lighter")
            PainAction.END_EXERCISE -> Tx.t("trp2_pain_done_end")
        }
        return if (r.injuryId != null) Tx.join(listOf(head, Tx.t("trp2_pain_saved")), " ") else head
    }

    private fun endedText(): TrText {
        val e = st.endedUndo ?: return Tx.EMPTY
        val names = e.slugs.joinToString(", ") { name(it) }
        // A circuit: "Circuit ended" (the list of four names filled three lines).
        val base = when (e.kind) {
            WorkoutEndKind.SKIPPED -> Tx.t("trpg_skipped", Tx.s(names))
            WorkoutEndKind.REMOVED -> Tx.t("trpg_removed", Tx.s(names))
            WorkoutEndKind.ENDED -> if (e.circuit) Tx.t("trpg_circuit_ended") else Tx.t("trpg_ended", Tx.s(names))
        }
        // Left out or removed: where the week stands for it, as a hint (comparison review #13).
        val weekly = if (e.kind == WorkoutEndKind.ENDED) null else e.slugs.singleOrNull()?.let { weeklyFor(it) }
        return if (weekly == null) base else Tx.join(listOf(base, Tx.weeklyHint(weekly)))
    }
}

/** `formatClock`: "1:30", "1:00:05". */
internal object TodayClock {
    fun clock(seconds: Int): String {
        val sec = seconds.coerceAtLeast(0)
        fun two(v: Int) = v.toString().padStart(2, '0')
        return if (sec >= 3600) "${sec / 3600}:${two((sec / 60) % 60)}:${two(sec % 60)}" else "${sec / 60}:${two(sec % 60)}"
    }
}

/** Where the hang timer stands at one moment. [phaseCode]: prepare | work | rest | setRest | done. */
class WorkoutHangTick(
    val phaseCode: String,
    val segmentIndex: Int,
    val remainingSeconds: Int,
    /** 0-based rep and set of the segment. */
    val rep: Int,
    val set: Int,
    /** 0…1 of the whole timer. */
    val progress: Double,
    val done: Boolean,
    val segmentStartMs: Long,
)

/**
 * The hang / interval timer's schedule (Android `HangTimerContent`): prepare → work → rest … → set rest
 * → done. Swift runs the wall clock (start anchor + accumulated time, so a stall never stretches a hang)
 * and asks [at] where it stands; beeps, haptics and speech follow the segment changes there.
 */
class WorkoutHangTimer(workS: Int, restBetweenS: Int, reps: Int, sets: Int, restBetweenSetsS: Int) {
    private class Segment(val phase: String, val durationMs: Long, val rep: Int, val set: Int, val startMs: Long) {
        val endMs: Long get() = startMs + durationMs
    }

    val repCount: Int = reps.coerceAtLeast(1)
    val setCount: Int = sets.coerceAtLeast(1)

    private val segments: List<Segment> = buildList {
        var t = 0L
        fun add(phase: String, seconds: Int, rep: Int, set: Int) {
            if (seconds <= 0) return
            add(Segment(phase, seconds * 1000L, rep, set, t))
            t += seconds * 1000L
        }
        add("prepare", PREPARE_SECONDS, 0, 0)
        val work = workS.coerceAtLeast(1)
        for (s in 0 until setCount) {
            for (r in 0 until repCount) {
                add("work", work, r, s)
                if (r < repCount - 1) add("rest", restBetweenS.coerceAtLeast(0), r, s)
            }
            if (s < setCount - 1) add("setRest", restBetweenSetsS.coerceAtLeast(0), repCount - 1, s)
        }
    }

    val totalMs: Long = segments.last().endMs

    fun at(elapsedMs: Long): WorkoutHangTick {
        val e = elapsedMs.coerceIn(0L, totalMs)
        if (e >= totalMs) return WorkoutHangTick("done", segments.size, 0, repCount - 1, setCount - 1, 1.0, true, totalMs)
        val index = segments.indexOfLast { it.startMs <= e }.coerceAtLeast(0)
        val seg = segments[index]
        return WorkoutHangTick(seg.phase, index, ((seg.endMs - e + 999) / 1000).toInt(), seg.rep, seg.set,
            e.toDouble() / totalMs, false, seg.startMs)
    }

    /** "Skip": where the next segment starts (the end when there is none). */
    fun nextStartMs(segmentIndex: Int): Long = segments.getOrNull(segmentIndex + 1)?.startMs ?: totalMs

    companion object {
        const val PREPARE_SECONDS = 5
    }
}
