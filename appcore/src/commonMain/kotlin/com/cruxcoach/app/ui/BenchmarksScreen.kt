package com.cruxcoach.app.ui

import com.cruxcoach.app.athlete.nowMs
import com.cruxcoach.app.progress.BenchmarkValuesRow
import com.cruxcoach.app.progress.BenchmarksPresenter
import com.cruxcoach.app.progress.BenchmarksRunState
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.BenchmarkMath
import com.cruxcoach.athlete.logic.CapacityKind
import com.cruxcoach.athlete.logic.GradeStrengthNorms
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.LearningState
import com.cruxcoach.athlete.logic.LoadPrescriber
import com.cruxcoach.athlete.logic.LoadTarget
import com.cruxcoach.athlete.logic.LogbookSummaries
import com.cruxcoach.athlete.logic.ProgressStats
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.logic.WorkoutPlanner
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

/** The newest value of one side ("" = two-handed) of an exercise. */
class BenchmarkSideUi(
    /** left | right | "" */
    val sideCode: String,
    /** "Left: " / "Right: " or empty. */
    val prefix: TrText,
    val hasValue: Boolean,
    /** The value's id, for deleting it; empty without. */
    val id: String,
    /** "+20 kg × 5 · est. 1RM +26 kg" or "not set yet". */
    val summary: TrText,
    /** "entered · 12 Oct 2026"; empty without a value. */
    val meta: TrText,
    /** "≈ 120 % of body weight · typical for about 7A"; empty without a norm or while learning. */
    val plausibility: TrText,
)

class BenchmarkGripUi(val code: String, val label: TrText)

/** One exercise with its values and what its entry dialog asks. */
class BenchmarkRowUi(
    val slug: String,
    val name: String,
    /** "estimated" / "learning 1/2" / "known"; empty for none. */
    val learning: TrText,
    val sides: List<BenchmarkSideUi>,
    /** Paused while an injury heals: no test. */
    val paused: Boolean,
    // ── Entry dialog ──
    /** e1rm | ten | reps | seconds */
    val kindCode: String,
    val unilateral: Boolean,
    /** Only this side may be entered (one-sided injury): left | right | "". */
    val allowedSide: String,
    val needsLoad: Boolean,
    /** Added weight (body weight plus), else the total load. */
    val loadIsAdded: Boolean,
    val loadLabel: TrText,
    val showEdge: Boolean,
    val defaultEdge: String,
    val defaultSeconds: String,
    /** HALF_CRIMP on edge hangs, else "". */
    val defaultGrip: String,
    val hintSets: TrText,
    val hintMax: TrText,
)

class BenchmarkPreviewUi(
    val valid: Boolean,
    val summary: TrText,
    /** "Planned: 5 × 10 s · +8 kg"; empty without. */
    val planned: TrText,
    val plausibility: TrText,
)

class BenchmarksScreenState(
    val loading: Boolean,
    val unitsCode: String,
    val needsWeight: Boolean,
    val key: List<BenchmarkRowUi>,
    val others: List<BenchmarkRowUi>,
    val grips: List<BenchmarkGripUi>,
    /** A test training was started: open the player (then call consumeTestStarted). */
    val testStarted: Boolean,
)

/**
 * Performance values (Android `BenchmarksScreen`): the key exercises and every other one with a
 * value, per hand where it matters; enter ("+20 kg × 5", "5 × 5 at +20 kg", "32 kg for 10 s on
 * 20 mm"), delete, start a test. The entry fields arrive as typed and are parsed and bounded as
 * Android's dialog does.
 */
class BenchmarksScreenModel internal constructor(
    private val presenter: BenchmarksPresenter,
    private val language: String,
) {
    val currentState: BenchmarksScreenState get() = map(presenter.state.value)

    fun watch(onState: (BenchmarksScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    fun deleteValue(id: String) { if (id.isNotEmpty()) presenter.delete(id) }

    fun startTest(slug: String) {
        val row = row(slug) ?: return
        if (row.injury.verdict != InjuryVerdict.AVOID) presenter.startTest(slug)
    }

    fun consumeTestStarted() = presenter.consumeTestStarted()

    /** [display] in the athlete's units; implausible values are refused (false). */
    fun logWeight(display: Double): Boolean {
        val kg = Units.massFromDisplay(display, presenter.state.value.profile.units)
        if (!kg.isFinite() || kg < ProgressScreenModel.PLAUSIBLE_MIN_KG || kg > ProgressScreenModel.PLAUSIBLE_MAX_KG) return false
        presenter.logWeight(kg)
        return true
    }

    /** The dialog's live preview of what the typed value means. */
    fun preview(slug: String, several: Boolean, sets: String, load: String, reps: String, seconds: String,
                edge: String, gripCode: String, sideCode: String): BenchmarkPreviewUi = try {
        val s = presenter.state.value
        val def = row(slug)?.def
        val draft = def?.let { draft(it, several, sets, load, reps, seconds, edge, gripCode, sideCode) }
        if (def == null || draft == null) BenchmarkPreviewUi(false, Tx.EMPTY, Tx.EMPTY, Tx.EMPTY)
        else {
            val target = LoadPrescriber.prescribe(def, WorkoutPlanner.itemFor(def),
                BenchmarkMath.capacity(def, draft, s.bodyweight), s.bodyweight, s.profile.smallestIncrementKg)
            BenchmarkPreviewUi(
                valid = true,
                summary = summary(def, draft, s.profile, s.bodyweight),
                planned = target?.let { prescriptionText(def, it, s.profile.units) } ?: Tx.EMPTY,
                plausibility = BenchmarksPresenter.normValue(def, draft, s.bodyweight)
                    ?.let { (metric, pct) -> plausibilityText(metric, pct, s.workingDifficulty) } ?: Tx.EMPTY,
            )
        }
    } catch (e: Exception) {
        BenchmarkPreviewUi(false, Tx.EMPTY, Tx.EMPTY, Tx.EMPTY)
    }

    /** Saves the typed value; false when it is not valid (Android keeps "Save" disabled then). */
    fun saveEntry(slug: String, several: Boolean, sets: String, load: String, reps: String, seconds: String,
                  edge: String, gripCode: String, sideCode: String): Boolean {
        val def = row(slug)?.def ?: return false
        val draft = draft(def, several, sets, load, reps, seconds, edge, gripCode, sideCode) ?: return false
        presenter.save(draft.copy(id = presenter.newId(), measuredAt = nowMs()))
        return true
    }

    private fun row(slug: String): BenchmarkValuesRow? {
        val s = presenter.state.value
        return (s.key + s.others).firstOrNull { it.def.slug == slug }
    }

    /**
     * Android `BenchmarkEntryDialog`'s draft and its `valid` rule, field filters included
     * (digits only for sets and reps, the same maximum lengths); null = not valid.
     */
    private fun draft(def: ExerciseDefinition, several: Boolean, setsText: String, loadText: String, repsText: String,
                      secondsText: String, edgeText: String, gripCode: String, sideCode: String): Benchmark? {
        val s = presenter.state.value
        val units = s.profile.units
        val kind = BenchmarkMath.capacityKind(def) ?: return null
        val allowedSide = row(def.slug)?.injury?.takeIf { it.verdict == InjuryVerdict.ONE_SIDE_ONLY }?.allowedSide
        val side: Side? = if (!def.unilateral) null else when (sideCode) {
            "left" -> Side.LEFT
            "right" -> Side.RIGHT
            else -> allowedSide ?: Side.RIGHT
        }
        if (allowedSide != null && side != null && side != allowedSide) return null
        val grip = Grip.entries.firstOrNull { it.name == gripCode }
        val loadKg = parseDecimal(loadText.take(8))?.let { Units.massFromDisplay(it, units) }
        val reps = repsText.filter(Char::isDigit).take(3).toIntOrNull()
        val sets = setsText.filter(Char::isDigit).take(2).toIntOrNull()?.takeIf { several }
        val seconds = parseDecimal(secondsText.take(5))
        val edge = parseDecimal(edgeText.take(4))
        val needsLoad = kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.TEN_SECOND_MAX
        val draft = Benchmark(
            id = "", exerciseSlug = def.slug, side = side,
            edgeMm = edge.takeIf { kind == CapacityKind.TEN_SECOND_MAX },
            grip = grip.takeIf { kind == CapacityKind.TEN_SECOND_MAX },
            loadKg = if (needsLoad) (loadKg ?: if (def.load == LoadMode.BODYWEIGHT_PLUS) 0.0 else null) else null,
            reps = reps.takeIf { kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.MAX_REPS },
            durationS = seconds.takeIf { kind == CapacityKind.TEN_SECOND_MAX || kind == CapacityKind.MAX_SECONDS },
            bodyweightKg = s.bodyweight, source = BenchmarkSource.MANUAL, measuredAt = nowMs(),
            sets = sets,
        )
        val valid = (!several || (sets ?: 0) in 1..20) && when (kind) {
            CapacityKind.E1RM_TOTAL -> draft.loadKg != null && (reps ?: 0) in 1..30
            CapacityKind.TEN_SECOND_MAX -> draft.loadKg != null && (seconds ?: 0.0) in 3.0..60.0
            CapacityKind.MAX_REPS -> (reps ?: 0) in 1..500
            CapacityKind.MAX_SECONDS -> (seconds ?: 0.0) in 1.0..3600.0
        } && !(def.load == LoadMode.EXTERNAL && (draft.loadKg ?: 0.0) <= 0.0)
        return draft.takeIf { valid }
    }

    private var lastGood: BenchmarksScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: BenchmarksRunState): BenchmarksScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(BenchmarksRunState())
    }

    private fun mapUnsafe(s: BenchmarksRunState) = BenchmarksScreenState(
        loading = s.loading,
        unitsCode = BodyScreenModel.unitsCode(s.profile.units),
        needsWeight = s.bodyweight == null,
        key = s.key.map { rowUi(it, s) },
        others = s.others.map { rowUi(it, s) },
        grips = listOf(Grip.HALF_CRIMP, Grip.OPEN_HAND, Grip.FULL_CRIMP, Grip.THREE_FINGER_DRAG)
            .map { BenchmarkGripUi(it.name, Tx.t(Tx.gripKey(it))) },
        testStarted = s.testStarted,
    )

    private fun rowUi(row: BenchmarkValuesRow, s: BenchmarksRunState): BenchmarkRowUi {
        val def = row.def
        val units = s.profile.units
        val kind = BenchmarkMath.capacityKind(def)
        val sides: List<Side?> = if (def.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
        val zone = TimeZone.currentSystemDefault()
        val allowed = row.injury.takeIf { it.verdict == InjuryVerdict.ONE_SIDE_ONLY }?.allowedSide
        return BenchmarkRowUi(
            slug = def.slug,
            name = def.name(language),
            learning = learningText(row.learning),
            sides = sides.map { side ->
                val b = row.values[side]
                BenchmarkSideUi(
                    sideCode = sideCode(side),
                    prefix = side?.let { TrText("", listOf(Tx.k(Tx.sideKey(it)), Tx.s(": ")), -1, "") } ?: Tx.EMPTY,
                    hasValue = b != null,
                    id = b?.id ?: "",
                    summary = b?.let { summary(def, it, s.profile, s.bodyweight) } ?: Tx.t("trbm_none"),
                    meta = b?.let { Tx.t("trbm_meta", Tx.x(Tx.t(sourceKey(it.source))), Tx.date(ProgressStats.dayOf(it.measuredAt, zone).toString())) }
                        ?: Tx.EMPTY,
                    // While the value is still being learned it is no verdict on the athlete's level.
                    plausibility = b?.takeIf { row.learning !is LearningState.State.Learning }
                        ?.let { BenchmarksPresenter.normValue(def, it, s.bodyweight) }
                        ?.let { (metric, pct) -> plausibilityText(metric, pct, s.workingDifficulty) } ?: Tx.EMPTY,
                )
            },
            paused = row.injury.verdict == InjuryVerdict.AVOID,
            kindCode = when (kind) {
                CapacityKind.E1RM_TOTAL -> "e1rm"
                CapacityKind.TEN_SECOND_MAX -> "ten"
                CapacityKind.MAX_REPS -> "reps"
                CapacityKind.MAX_SECONDS -> "seconds"
                null -> ""
            },
            unilateral = def.unilateral,
            allowedSide = sideCode(allowed),
            needsLoad = kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.TEN_SECOND_MAX,
            loadIsAdded = def.load == LoadMode.BODYWEIGHT_PLUS,
            loadLabel = Tx.t(if (def.load == LoadMode.BODYWEIGHT_PLUS) "trbm_load_added" else "trbm_load_total", Tx.s(Units.massUnit(units))),
            showEdge = kind == CapacityKind.TEN_SECOND_MAX && def.defaults.edgeMm != null,
            defaultEdge = def.defaults.edgeMm?.toString() ?: "",
            defaultSeconds = if (kind == CapacityKind.TEN_SECOND_MAX) "10" else "",
            defaultGrip = if (kind == CapacityKind.TEN_SECOND_MAX && def.defaults.edgeMm != null) Grip.HALF_CRIMP.name else "",
            hintSets = Tx.t(when (kind) {
                CapacityKind.E1RM_TOTAL -> "trbm_hint_sets_e1rm"
                CapacityKind.TEN_SECOND_MAX -> "trbm_hint_sets_hang"
                CapacityKind.MAX_REPS -> "trbm_hint_sets_reps"
                CapacityKind.MAX_SECONDS, null -> "trbm_hint_sets_seconds"
            }),
            hintMax = Tx.t(when (kind) {
                CapacityKind.E1RM_TOTAL -> "trbm_hint_e1rm"
                CapacityKind.TEN_SECOND_MAX -> "trbm_hint_hang"
                CapacityKind.MAX_REPS -> "trbm_hint_reps"
                CapacityKind.MAX_SECONDS, null -> "trbm_hint_seconds"
            }),
        )
    }

    internal companion object {
        fun sideCode(side: Side?) = when (side) {
            Side.LEFT -> "left"
            Side.RIGHT -> "right"
            null -> ""
        }

        /** Android `parseDecimal`: comma or dot decimals; null when empty or invalid. */
        fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

        fun sourceKey(s: BenchmarkSource) = when (s) {
            BenchmarkSource.MANUAL -> "trbm_source_manual"
            BenchmarkSource.TEST -> "trbm_source_test"
            BenchmarkSource.AUTO -> "trbm_source_auto"
            BenchmarkSource.ESTIMATE -> "trbm_source_estimate"
        }

        /** Android `learningLabel` (LearningBadge.kt). */
        fun learningText(state: LearningState.State): TrText = when (state) {
            LearningState.State.None -> Tx.EMPTY
            LearningState.State.Estimated -> Tx.t("trc_badge_estimated")
            is LearningState.State.Learning -> Tx.t("trc_badge_learning", Tx.i(state.sessions), Tx.i(state.needed))
            LearningState.State.Known -> Tx.t("trc_badge_known")
        }

        /** Android `PlausibilityHint` (QuickEstimateSheet.kt). */
        fun plausibilityText(metric: GradeStrengthNorms.Metric, pct: Double, difficulty: Double?): TrText {
            val parts = mutableListOf(Tx.t("trc_estimate_pct", Tx.i(pct.roundToInt())))
            if (difficulty != null) {
                val grade = LogbookSummaries.fontOf(difficulty) ?: ""
                when (GradeStrengthNorms.plausibility(metric, difficulty, pct)) {
                    GradeStrengthNorms.Plausibility.UNUSUALLY_HIGH -> parts += Tx.t("trc_estimate_high", Tx.s(grade))
                    GradeStrengthNorms.Plausibility.UNUSUALLY_LOW -> parts += Tx.t("trc_estimate_low", Tx.s(grade))
                    GradeStrengthNorms.Plausibility.PLAUSIBLE -> {
                        val typical = LogbookSummaries.fontOf(GradeStrengthNorms.typicalDifficulty(metric, pct))
                        if (typical != null) parts += Tx.t("trc_estimate_typical", Tx.s(typical))
                    }
                }
            }
            return Tx.join(parts)
        }

        /** Android `loadText` (BenchmarksScreen.kt). */
        fun loadText(def: ExerciseDefinition, loadKg: Double, units: UnitSystem): TrText = when {
            def.load == LoadMode.BODYWEIGHT_PLUS && loadKg == 0.0 -> Tx.t("tr_bodyweight")
            def.load == LoadMode.BODYWEIGHT_PLUS && loadKg < 0 -> Tx.t("trbm_assisted", Tx.x(Tx.mass(-loadKg, units)))
            def.load == LoadMode.BODYWEIGHT_PLUS -> Tx.mass(loadKg, units, signed = true)
            else -> Tx.mass(loadKg, units)
        }

        private fun cat(vararg parts: TrArg) = TrText("", parts.toList(), -1, "")

        /** Android `benchmarkSummary`. */
        fun summary(def: ExerciseDefinition, b: Benchmark, profile: AthleteProfile, bodyweight: Double?): TrText {
            val units = profile.units
            val cap = BenchmarkMath.capacity(def, b, bodyweight)
            val pieces = mutableListOf<TrText>()
            val sets = b.sets
            when (BenchmarkMath.capacityKind(def)) {
                CapacityKind.E1RM_TOTAL -> {
                    val load = loadText(def, b.loadKg ?: 0.0, units)
                    pieces += if (sets != null) cat(Tx.s("$sets × ${b.reps ?: 0} · "), Tx.x(load))
                        else cat(Tx.x(load), Tx.s(" × " + (b.reps ?: 0)))
                    if (cap != null) {
                        val bw = b.bodyweightKg ?: bodyweight
                        val shown = if (def.load == LoadMode.BODYWEIGHT_PLUS && bw != null) cap.value - bw else cap.value
                        pieces += Tx.t("trbm_e1rm", Tx.x(if (def.load == LoadMode.BODYWEIGHT_PLUS) Tx.mass(shown, units, signed = true) else Tx.mass(shown, units)))
                    }
                }
                CapacityKind.TEN_SECOND_MAX -> {
                    val seconds = Tx.t("tr_format_seconds", Tx.i((b.durationS ?: 10.0).roundToInt()))
                    val load = loadText(def, b.loadKg ?: 0.0, units)
                    pieces += if (sets != null) cat(Tx.s("$sets × "), Tx.x(seconds), Tx.s(" · "), Tx.x(load))
                        else cat(Tx.x(load), Tx.s(" · "), Tx.x(seconds))
                    b.edgeMm?.let { pieces += Tx.mm(it) }
                    b.grip?.let { pieces += Tx.t(Tx.gripKey(it)) }
                    val bw = b.bodyweightKg ?: bodyweight
                    if (cap != null && bw != null && bw > 0) {
                        pieces += Tx.t("trbm_ten_second_max", Tx.x(Tx.mass(cap.value, units)), Tx.i((cap.value / bw * 100).roundToInt()))
                    }
                }
                CapacityKind.MAX_REPS -> if (sets != null) {
                    pieces += Tx.t("trbm_sets_reps", Tx.i(sets), Tx.i(b.reps ?: 0))
                    cap?.let { pieces += Tx.t("trbm_max_reps_about", Tx.i(it.value.roundToInt())) }
                } else pieces += Tx.t("trbm_max_reps", Tx.i(b.reps ?: 0))
                CapacityKind.MAX_SECONDS -> if (sets != null) {
                    pieces += cat(Tx.s("$sets × "), Tx.x(Tx.t("tr_format_seconds", Tx.i((b.durationS ?: 0.0).roundToInt()))))
                    cap?.let { pieces += Tx.t("trbm_max_seconds_about", Tx.i(it.value.roundToInt())) }
                } else pieces += Tx.t("trbm_max_seconds", Tx.i((b.durationS ?: 0.0).roundToInt()))
                null -> Unit
            }
            return Tx.join(pieces)
        }

        /** "Planned: 5 × 10 s · +4 kg" — what the value turns into (Android `prescriptionText`). */
        fun prescriptionText(def: ExerciseDefinition, t: LoadTarget, units: UnitSystem): TrText {
            val sets = def.defaults.sets ?: 3
            val reps = t.reps
            val duration = t.durationS
            val amount = when {
                reps != null -> cat(Tx.s("$sets × $reps"))
                duration != null -> cat(Tx.s("$sets × "), Tx.x(Tx.t("tr_format_seconds", Tx.i(duration))))
                else -> cat(Tx.s("$sets ×"))
            }
            val full = t.loadKg?.let { cat(Tx.x(amount), Tx.s(" · "), Tx.x(loadText(def, it, units))) } ?: amount
            return Tx.t("trbm_planned", Tx.x(full))
        }
    }
}

/** One answer chip of the quick estimate. */
class QuickEstimateChoiceUi(val code: String, val label: TrText)

class QuickEstimateScreenState(
    val loading: Boolean,
    val unitsCode: String,
    val massUnit: String,
    val needsWeight: Boolean,
    val pullChoices: List<QuickEstimateChoiceUi>,
    val hangChoices: List<QuickEstimateChoiceUi>,
    val hangAllowed: Boolean,
    /** One-arm pick-ups are asked (a block somewhere, no injury against it). */
    val showPickup: Boolean,
    /** left | right while a one-sided injury allows only that hand; "" = both. */
    val pickupSide: String,
    /** The answers were saved: close the sheet. */
    val saved: Boolean,
)

/**
 * The quick estimate in two questions (Android `QuickEstimateSheet`): pull-ups, a two-hand hang
 * and, with a block, one-arm pick-ups per hand. Answers are passed as typed (codes and texts) and
 * become performance values marked "estimated".
 */
class QuickEstimateScreenModel internal constructor(private val presenter: com.cruxcoach.app.progress.QuickEstimatePresenter) {
    val currentState: QuickEstimateScreenState get() = map(presenter.state.value)

    fun watch(onState: (QuickEstimateScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun close() = presenter.close()

    /** [display] in the athlete's units; Android's plausible range, else false. */
    fun logWeight(display: Double): Boolean {
        val kg = Units.massFromDisplay(display, presenter.state.value.units)
        if (!kg.isFinite() || kg < ProgressScreenModel.PLAUSIBLE_MIN_KG || kg > ProgressScreenModel.PLAUSIBLE_MAX_KG) return false
        presenter.logWeight(kg)
        return true
    }

    /** "≈ 95 % of body weight · typical for about 6B" for the pull-up answer; empty without. */
    fun pullHint(pullCode: String, weightedKg: String, weightedReps: String): TrText {
        val s = presenter.state.value
        val pct = com.cruxcoach.app.progress.QuickEstimatePresenter.pullPct(pull(pullCode), kg(weightedKg), reps(weightedReps), s.bodyweight)
            ?: return Tx.EMPTY
        return BenchmarksScreenModel.plausibilityText(GradeStrengthNorms.Metric.PULL_UP_1RM, pct, s.workingDifficulty)
    }

    /** The same for the hang answer. */
    fun hangHint(hangCode: String, hangKg: String): TrText {
        val s = presenter.state.value
        val pct = com.cruxcoach.app.progress.QuickEstimatePresenter.fingerPct(hang(hangCode), kg(hangKg), s.bodyweight, s.increment)
            ?: return Tx.EMPTY
        return BenchmarksScreenModel.plausibilityText(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, pct, s.workingDifficulty)
    }

    /** Android enables "Save" once anything was answered. */
    fun canSave(pullCode: String, hangCode: String, leftKg: String, rightKg: String): Boolean {
        val pull = pull(pullCode)
        val hang = hang(hangCode)
        return (pull != null && pull != com.cruxcoach.app.progress.PullAnswer.NONE) ||
            (hang != null && hang != com.cruxcoach.app.progress.HangAnswer.CANNOT) ||
            (kg(leftKg) ?: 0.0) > 0 || (kg(rightKg) ?: 0.0) > 0
    }

    /** Saves the answers; false when nothing was answered. */
    fun save(pullCode: String, weightedKg: String, weightedReps: String, hangCode: String, hangKg: String,
             leftKg: String, rightKg: String): Boolean {
        if (!canSave(pullCode, hangCode, leftKg, rightKg)) return false
        presenter.save(pull(pullCode), kg(weightedKg), reps(weightedReps), hang(hangCode), kg(hangKg), kg(leftKg), kg(rightKg))
        return true
    }

    private fun pull(code: String) = com.cruxcoach.app.progress.PullAnswer.entries.firstOrNull { it.name == code }
    private fun hang(code: String) = com.cruxcoach.app.progress.HangAnswer.entries.firstOrNull { it.name == code }
    /** Android's fields: six characters, comma or dot decimals. */
    private fun kg(text: String): Double? =
        BenchmarksScreenModel.parseDecimal(text.take(6))?.let { Units.massFromDisplay(it, presenter.state.value.units) }
    private fun reps(text: String): Int? = text.filter(Char::isDigit).take(2).toIntOrNull()

    private fun map(s: com.cruxcoach.app.progress.QuickEstimateRunState) = QuickEstimateScreenState(
        loading = s.loading,
        unitsCode = BodyScreenModel.unitsCode(s.units),
        massUnit = Units.massUnit(s.units),
        needsWeight = s.bodyweight == null,
        pullChoices = com.cruxcoach.app.progress.PullAnswer.entries.map { a ->
            QuickEstimateChoiceUi(a.name, Tx.t(when (a) {
                com.cruxcoach.app.progress.PullAnswer.NONE -> "trc_estimate_pull_0"
                com.cruxcoach.app.progress.PullAnswer.FEW -> "trc_estimate_pull_1_3"
                com.cruxcoach.app.progress.PullAnswer.SOME -> "trc_estimate_pull_4_7"
                com.cruxcoach.app.progress.PullAnswer.MANY -> "trc_estimate_pull_8_12"
                com.cruxcoach.app.progress.PullAnswer.LOTS -> "trc_estimate_pull_13"
                com.cruxcoach.app.progress.PullAnswer.WEIGHTED -> "trc_estimate_pull_weighted"
            }))
        },
        hangChoices = com.cruxcoach.app.progress.HangAnswer.entries.map { a ->
            QuickEstimateChoiceUi(a.name, Tx.t(when (a) {
                com.cruxcoach.app.progress.HangAnswer.CANNOT -> "trc_estimate_hang_no"
                com.cruxcoach.app.progress.HangAnswer.BARELY -> "trc_estimate_hang_barely"
                com.cruxcoach.app.progress.HangAnswer.EASY -> "trc_estimate_hang_easy"
                com.cruxcoach.app.progress.HangAnswer.WEIGHTED -> "trc_estimate_hang_weighted"
            }))
        },
        hangAllowed = s.hangAllowed,
        showPickup = s.hasPickup && s.pickupAllowed,
        pickupSide = BenchmarksScreenModel.sideCode(s.pickupSide),
        saved = s.saved,
    )
}
