package com.cruxcoach.app.ui

import com.cruxcoach.app.progress.ExerciseStatsPresenter
import com.cruxcoach.app.progress.ExerciseStatsRunState
import com.cruxcoach.app.progress.ProgressRange
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.RecordKind
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.roundToInt
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus

/** One line of the capacity chart: both hands together, or one hand. */
class ExerciseStatsSeriesUi(
    /** left | right | "" */
    val sideCode: String,
    val label: TrText,
    val points: List<ProgressPointUi>,
)

class ExerciseStatsBestUi(val label: TrText, val value: TrText)

class ExerciseStatsSessionUi(
    val workoutId: String,
    val isoDate: String,
    val best: TrText,
    val setsText: TrText,
    /** One line per set; [bestIndex] is the session's best set, -1 without. */
    val sets: List<TrText>,
    val bestIndex: Int,
)

class ExerciseStatsScreenState(
    val loading: Boolean,
    val found: Boolean,
    val name: String,
    /** w4 | w12 | m6 | y1 */
    val rangeCode: String,
    val meaning: TrText,
    val hasSessions: Boolean,
    val best: TrText,
    val last: TrText,
    val change: TrText,
    val sessionCount: Int,
    val percentBodyweight: TrText,
    val capacityTitle: TrText,
    val series: List<ExerciseStatsSeriesUi>,
    val capacityDescription: TrText,
    /** The current performance value as a guide line (two-handed exercises); [hasLevel] false without. */
    val hasLevel: Boolean,
    val level: Double,
    /** "Current performance value: Left: … · Right: …"; empty without. */
    val currentValue: TrText,
    val volume: List<ProgressPointUi>,
    val volumeDescription: TrText,
    val volumeHint: TrText,
    val bests: List<ExerciseStatsBestUi>,
    /** Newest first, at most 30. */
    val sessions: List<ExerciseStatsSessionUi>,
)

/**
 * One exercise over time (Android `ExerciseStatsScreen`): best, latest, change in four weeks,
 * sessions and % body weight; the estimated maximum per session (one line per hand for one-sided
 * work) with the current performance value; volume per session; bests per edge, grip and side;
 * the sessions with their sets. Swift calls [load] with the slug.
 */
class ExerciseStatsScreenModel internal constructor(
    private val presenter: ExerciseStatsPresenter,
    private val language: String,
) {
    val currentState: ExerciseStatsScreenState get() = map(presenter.state.value)

    fun watch(onState: (ExerciseStatsScreenState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    fun load(slug: String) = presenter.load(slug)

    fun setRange(code: String) {
        ProgressScreenModel.RANGES.entries.firstOrNull { it.value == code }?.let { presenter.setRange(it.key) }
    }

    fun rangeLabel(code: String): TrText =
        Tx.t(ProgressScreenModel.rangeKey(ProgressScreenModel.RANGES.entries.firstOrNull { it.value == code }?.key ?: ProgressRange.SIX_MONTHS))

    val rangeCodes: List<String> get() = ProgressRange.entries.map { ProgressScreenModel.RANGES.getValue(it) }

    fun close() = presenter.close()

    private var lastGood: ExerciseStatsScreenState? = null

    /** Mapping failures so far; the JVM tests require none. */
    internal var mapFailures = 0
        private set

    /** Never throws into Swift: a state that cannot be mapped keeps the last good one on screen. */
    private fun map(s: ExerciseStatsRunState): ExerciseStatsScreenState = try {
        mapUnsafe(s).also { lastGood = it }
    } catch (e: Exception) {
        mapFailures++
        println("${this::class.simpleName}: state not mapped: ${e.message}")
        lastGood ?: mapUnsafe(ExerciseStatsRunState())
    }

    private fun mapUnsafe(s: ExerciseStatsRunState): ExerciseStatsScreenState {
        val def = s.def
        val progress = s.progress
        val units = s.profile.units
        val rangeCode = ProgressScreenModel.RANGES.getValue(s.range)
        if (def == null || progress == null) {
            return ExerciseStatsScreenState(s.loading, false, "", rangeCode, Tx.EMPTY, false, Tx.EMPTY, Tx.EMPTY, Tx.EMPTY, 0,
                Tx.EMPTY, Tx.EMPTY, emptyList(), Tx.EMPTY, false, 0.0, Tx.EMPTY, emptyList(), Tx.EMPTY, Tx.EMPTY, emptyList(), emptyList())
        }
        val kind = progress.kind
        val label = Tx.t(ProgressScreenModel.capacityLabelKey(kind))
        val from = s.today?.minus(DatePeriod(days = s.range.days - 1))
        val sessions = progress.sessions.filter { from == null || it.day >= from }
        val sides: List<Side?> = if (def.unilateral) listOf(Side.LEFT, Side.RIGHT) else listOf(null)
        val series = sides.mapNotNull { side ->
            val points = sessions.mapNotNull { p ->
                (if (side == null) p.bestDisplay else p.display[side])?.let { ProgressPointUi(p.day.toString(), p.day.toEpochDays().toLong(), it) }
            }
            if (points.isEmpty()) null else ExerciseStatsSeriesUi(BenchmarksScreenModel.sideCode(side), side?.let { Tx.t(Tx.sideKey(it)) } ?: label, points)
        }
        val level = if (!def.unilateral) s.levels[null] else null
        val levelParts = s.levels.entries.sortedBy { it.key?.ordinal ?: -1 }.map { (side, v) ->
            val value = ProgressScreenModel.capacityText(def, kind, v, units)
            side?.let { TrText("", listOf(Tx.k(Tx.sideKey(it)), Tx.s(": "), Tx.x(value)), -1, "") } ?: value
        }
        val volume = sessions.filter { it.volume > 0 }.map { ProgressPointUi(it.day.toString(), it.day.toEpochDays().toLong(), it.volume) }
        val cap = { v: Double -> ProgressScreenModel.capacityText(def, kind, v, units) }
        return ExerciseStatsScreenState(
            loading = s.loading,
            found = true,
            name = def.name(language),
            rangeCode = rangeCode,
            meaning = Tx.t("trs_capacity_meaning", Tx.x(label)),
            hasSessions = progress.sessions.isNotEmpty(),
            best = progress.best?.let(cap) ?: Tx.plain("–"),
            last = progress.last?.let(cap) ?: Tx.plain("–"),
            change = progress.change28?.let { ProgressScreenModel.changeText(def, kind, it, units) } ?: Tx.plain("–"),
            sessionCount = progress.sessionCount,
            percentBodyweight = progress.percentBodyweight?.let { Tx.plain("${it.roundToInt()} %") } ?: Tx.plain("–"),
            capacityTitle = Tx.t("trs_capacity_chart", Tx.x(label)),
            series = series,
            capacityDescription = Tx.t("trs_capacity_cd", Tx.s(def.name(language)), Tx.i(sessions.size)),
            hasLevel = level != null,
            level = level ?: 0.0,
            currentValue = if (levelParts.isEmpty()) Tx.EMPTY else Tx.t("trs_current_value", Tx.x(Tx.join(levelParts))),
            volume = volume,
            volumeDescription = Tx.t("trs_volume_cd", Tx.i(volume.size)),
            volumeHint = Tx.t(volumeHintKey(def)),
            bests = s.bests.map { (key, best) ->
                val parts = listOfNotNull(
                    key.side?.let { Tx.t(Tx.sideKey(it)) },
                    key.edgeMm?.let { Tx.t("tr_format_mm", Tx.s(it.toString())) },
                    key.grip?.let { Tx.t(Tx.gripKey(it)) },
                )
                ExerciseStatsBestUi(if (parts.isEmpty()) label else Tx.join(parts), recordValue(best.first, best.second, units))
            },
            sessions = sessions.reversed().take(30).map { p ->
                val sets = s.setsByWorkout[p.workoutId].orEmpty().sortedWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
                ExerciseStatsSessionUi(
                    workoutId = p.workoutId,
                    isoDate = p.day.toString(),
                    best = p.bestDisplay?.let(cap) ?: Tx.EMPTY,
                    setsText = Tx.p("trs_sets", p.sets, Tx.i(p.sets)),
                    sets = sets.map { setSummary(def, it, units) },
                    bestIndex = sets.indexOfFirst { it.id == p.bestSet?.id },
                )
            },
        )
    }

    internal companion object {
        fun volumeHintKey(def: ExerciseDefinition) = when (def.kind) {
            ExerciseKind.LOAD_REPS -> "trs_volume_hint_load"
            ExerciseKind.REPS, ExerciseKind.CLIMB -> "trs_volume_hint_reps"
            ExerciseKind.TIME -> "trs_volume_hint_time"
            ExerciseKind.HANG, ExerciseKind.INTERVAL -> "trs_volume_hint_hang"
        }

        /** Android `recordValue` (ExerciseStatsScreen.kt). */
        fun recordValue(kind: RecordKind, value: Double, units: UnitSystem): TrText = when (kind) {
            RecordKind.LOAD -> Tx.t("trs_best_load", Tx.x(Tx.mass(value, units)))
            RecordKind.ESTIMATED_MAX -> Tx.t("trs_best_e1rm", Tx.x(Tx.mass(value, units)))
            RecordKind.REPS -> value.roundToInt().let { Tx.p("trs_reps", it, Tx.i(it)) }
            RecordKind.DURATION -> Tx.t("tr_format_seconds", Tx.i(value.roundToInt()))
        }

        /** Android `setSummary` (ExerciseDetailScreen.kt). */
        fun setSummary(def: ExerciseDefinition, set: ExerciseSet, units: UnitSystem): TrText {
            val parts = mutableListOf<TrText>()
            if (set.setType != SetType.WORK) parts += Tx.t(Tx.setTypeKey(set.setType))
            set.side?.let { parts += Tx.t(Tx.sideKey(it)) }
            set.reps?.let { parts += Tx.t("trx_reps_short", Tx.i(it)) }
            (set.durationS ?: set.workS)?.let { parts += Tx.t("tr_format_seconds", Tx.i(it.roundToInt())) }
            set.repsPerSet?.let { parts += Tx.t("trx_reps_short", Tx.i(it)) }
            when (def.load) {
                LoadMode.BODYWEIGHT_PLUS -> parts += set.loadKg?.takeIf { it != 0.0 }?.let { Tx.mass(it, units, signed = true) }
                    ?: Tx.t("tr_bodyweight")
                LoadMode.EXTERNAL -> set.loadKg?.let { parts += Tx.mass(it, units) }
                else -> Unit
            }
            set.edgeMm?.let { parts += Tx.mm(it) }
            set.grip?.let { parts += Tx.t(Tx.gripKey(it)) }
            set.rir?.let { parts += Tx.plain("RIR $it") }
            return Tx.join(parts)
        }
    }
}
