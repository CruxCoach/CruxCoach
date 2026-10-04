package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** What a performance value means for an exercise, in one comparable number. */
enum class CapacityKind {
    /** Estimated one-rep max of the total load (body weight ± added, or the lifted weight). */
    E1RM_TOTAL,
    /** Heaviest total load the athlete could hold for 10 s. */
    TEN_SECOND_MAX,
    MAX_REPS,
    MAX_SECONDS,
}

data class Capacity(
    val kind: CapacityKind,
    val value: Double,
    val bodyweightKg: Double?,
    /** Estimated from the other hand's value (one-sided work without an own value yet). */
    val fromOtherSide: Boolean = false,
)

/**
 * Hold time ↔ intensity for isometric finger and hang work, relative to the
 * 10-second maximum. Rule-of-thumb values in the range reported for climbers'
 * finger strength-endurance curves; they only need to be monotone and
 * plausible, because every prescription is corrected by the athlete's own
 * logged sets afterwards.
 */
object HoldCurve {
    private val points = listOf(
        3.0 to 1.12, 5.0 to 1.07, 7.0 to 1.03, 10.0 to 1.00, 12.0 to 0.97,
        15.0 to 0.94, 20.0 to 0.89, 30.0 to 0.81, 45.0 to 0.73, 60.0 to 0.67,
    )

    fun relative(durationS: Double): Double {
        val d = durationS.coerceIn(points.first().first, points.last().first)
        val upper = points.indexOfFirst { it.first >= d }
        if (upper <= 0) return points.first().second
        val (x0, y0) = points[upper - 1]
        val (x1, y1) = points[upper]
        return y0 + (y1 - y0) * (d - x0) / (x1 - x0)
    }
}

object BenchmarkMath {

    /** Reps in reserve a strength prescription aims for (MCI-style RIR 2). */
    const val TARGET_RIR = 2

    fun capacityKind(def: ExerciseDefinition): CapacityKind? = when (def.kind) {
        ExerciseKind.LOAD_REPS -> CapacityKind.E1RM_TOTAL
        ExerciseKind.HANG, ExerciseKind.INTERVAL ->
            if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) CapacityKind.TEN_SECOND_MAX
            else CapacityKind.MAX_SECONDS
        ExerciseKind.REPS -> CapacityKind.MAX_REPS
        ExerciseKind.TIME -> CapacityKind.MAX_SECONDS
        ExerciseKind.CLIMB -> null
    }

    /** Capacity a stored value stands for; [bodyweightKg] fills in a missing snapshot. */
    fun capacity(def: ExerciseDefinition, b: Benchmark, bodyweightKg: Double?): Capacity? {
        val bw = b.bodyweightKg ?: bodyweightKg
        return when (capacityKind(def)) {
            CapacityKind.E1RM_TOTAL -> {
                val reps = b.reps?.takeIf { it > 0 } ?: return null
                val total = StrengthMath.effectiveLoad(def.load, b.loadKg, bw) ?: return null
                Capacity(CapacityKind.E1RM_TOTAL, StrengthMath.epley(total, reps), bw)
            }
            CapacityKind.TEN_SECOND_MAX -> {
                val total = StrengthMath.effectiveLoad(def.load, b.loadKg, bw) ?: return null
                val duration = b.durationS?.takeIf { it > 0 } ?: 10.0
                Capacity(CapacityKind.TEN_SECOND_MAX, total / HoldCurve.relative(duration), bw)
            }
            CapacityKind.MAX_REPS -> b.reps?.takeIf { it > 0 }?.let { Capacity(CapacityKind.MAX_REPS, it.toDouble(), bw) }
            CapacityKind.MAX_SECONDS -> b.durationS?.takeIf { it > 0 }?.let { Capacity(CapacityKind.MAX_SECONDS, it, bw) }
            null -> null
        }
    }

    /**
     * What a completed set proves. Reps in reserve count as reps the athlete
     * could have done; holds shorter than 3 s say nothing about 10 s.
     */
    fun implied(def: ExerciseDefinition, set: ExerciseSet): Capacity? {
        if (!set.isCompleted || set.setType == SetType.WARMUP) return null
        val bw = set.bodyweightKg
        return when (capacityKind(def)) {
            CapacityKind.E1RM_TOTAL -> {
                val reps = (set.reps ?: return null) + (set.rir ?: 0)
                if (reps <= 0) return null
                val total = StrengthMath.effectiveLoad(def.load, set.loadKg, bw) ?: return null
                Capacity(CapacityKind.E1RM_TOTAL, StrengthMath.epley(total, reps), bw)
            }
            CapacityKind.TEN_SECOND_MAX -> {
                if (def.kind == ExerciseKind.INTERVAL) return null // repeaters are too fatigue-driven to estimate a maximum
                val duration = set.durationS?.takeIf { it >= 3.0 } ?: return null
                val total = StrengthMath.effectiveLoad(def.load, set.loadKg, bw) ?: return null
                Capacity(CapacityKind.TEN_SECOND_MAX, total / HoldCurve.relative(duration), bw)
            }
            CapacityKind.MAX_REPS -> ((set.reps ?: return null) + (set.rir ?: 0)).takeIf { it > 0 }
                ?.let { Capacity(CapacityKind.MAX_REPS, it.toDouble(), bw) }
            CapacityKind.MAX_SECONDS -> set.durationS?.takeIf { it > 0 }?.let { Capacity(CapacityKind.MAX_SECONDS, it, bw) }
            null -> null
        }
    }

    /** A stored value expressed the way the athlete enters it again (reps folded with RIR). */
    fun benchmarkFromSet(def: ExerciseDefinition, set: ExerciseSet, id: String, source: com.cruxcoach.athlete.model.BenchmarkSource, now: Long): Benchmark =
        Benchmark(
            id = id, exerciseSlug = def.slug, side = set.side, edgeMm = set.edgeMm, grip = set.grip,
            loadKg = set.loadKg, reps = set.reps?.let { it + (set.rir ?: 0) }, durationS = set.durationS,
            bodyweightKg = set.bodyweightKg, source = source, measuredAt = now,
        )

    /**
     * The value that applies to a set: same side first (one-sided work is
     * trained per hand), then the closest edge and the same grip; newest wins.
     */
    fun select(benchmarks: List<Benchmark>, side: Side?, edgeMm: Double?, grip: Grip?): Benchmark? {
        if (benchmarks.isEmpty()) return null
        fun score(b: Benchmark): Int {
            var s = 0
            if (side != null && b.side == side) s += 100 else if (side != null && b.side != null) s -= 100
            if (edgeMm != null && b.edgeMm != null) s -= (abs(b.edgeMm - edgeMm) * 4).roundToInt().coerceAtMost(60)
            if (grip != null && b.grip == grip) s += 10
            return s
        }
        return benchmarks.maxWith(compareBy<Benchmark>({ score(it) }, { it.measuredAt }))
    }
}

/** A prescription derived from a performance value. */
data class LoadTarget(val loadKg: Double?, val reps: Int?, val durationS: Int?, val basis: Capacity)

/**
 * Turns a capacity into today's targets, the MCI way but for climbers:
 * loaded reps at RIR 2 from the e1RM, max hangs at 90 % of what the athlete
 * can hold for the prescribed time, long density hangs at 85 %, repeaters at
 * 65 % of the 10-s maximum, bodyweight reps and holds at 70 % of the maximum.
 * Loads snap to the athlete's smallest weight step. Tests get no target.
 */
object LoadPrescriber {

    fun prescribe(
        def: ExerciseDefinition,
        item: RoutineItem,
        capacity: Capacity?,
        bodyweightKg: Double?,
        incrementKg: Double,
    ): LoadTarget? {
        if (capacity == null || item.test || item.warmup) return null
        val bw = bodyweightKg ?: capacity.bodyweightKg
        fun toLoad(total: Double): Double? = when (def.load) {
            LoadMode.BODYWEIGHT_PLUS -> bw?.let { StrengthMath.roundTo(total - it, incrementKg) }
            LoadMode.EXTERNAL -> StrengthMath.roundTo(max(total, 0.0), incrementKg)
            else -> null
        }
        return when (capacity.kind) {
            CapacityKind.E1RM_TOTAL -> {
                val reps = item.repsMax ?: item.repsMin ?: def.defaults.repsMax ?: 5
                val total = capacity.value / (1.0 + (reps + BenchmarkMath.TARGET_RIR) / 30.0)
                toLoad(total)?.let { LoadTarget(it, reps, null, capacity) }
            }
            CapacityKind.TEN_SECOND_MAX -> {
                if (def.kind == ExerciseKind.INTERVAL) {
                    val work = item.workS ?: def.defaults.workS ?: 7
                    toLoad(capacity.value * 0.65)?.let { LoadTarget(it, null, work, capacity) }
                } else {
                    val d = item.durationS ?: def.defaults.durationS ?: 10
                    val share = if (d >= 20) 0.85 else 0.90
                    toLoad(capacity.value * HoldCurve.relative(d.toDouble()) * share)?.let { LoadTarget(it, null, d, capacity) }
                }
            }
            CapacityKind.MAX_REPS -> LoadTarget(null, max(1, (capacity.value * 0.7).roundToInt()), null, capacity)
            CapacityKind.MAX_SECONDS -> LoadTarget(null, null, max(3, (capacity.value * 0.7).roundToInt()), capacity)
        }
    }
}

/** Order and rests of the guided training mode. */
object PlayerQueue {

    const val SIDE_SWITCH_MAX_S = 30
    const val DEFAULT_REST_S = 90

    fun ordered(sets: List<ExerciseSet>): List<ExerciseSet> =
        sets.sortedWith(compareBy({ it.blockIndex }, { it.setIndex }, { it.side?.ordinal ?: -1 }))

    fun current(sets: List<ExerciseSet>): ExerciseSet? = ordered(sets).firstOrNull { !it.isCompleted }

    fun nextAfter(sets: List<ExerciseSet>, done: ExerciseSet): ExerciseSet? {
        val list = ordered(sets)
        val index = list.indexOfFirst { it.id == done.id }
        return list.drop(index + 1).firstOrNull { !it.isCompleted } ?: list.firstOrNull { !it.isCompleted && it.id != done.id }
    }

    /** Rest after [done]: a short side switch between the hands of one set, otherwise the set's rest. */
    fun restAfter(done: ExerciseSet, next: ExerciseSet?, def: ExerciseDefinition): Int {
        if (next == null) return 0
        val rest = done.restS ?: def.defaults.restS ?: DEFAULT_REST_S
        val sideSwitch = next.blockIndex == done.blockIndex && next.setIndex == done.setIndex &&
            next.side != null && next.side != done.side
        return if (sideSwitch) minOf(SIDE_SWITCH_MAX_S, rest) else rest
    }

    data class Position(val step: Int, val total: Int, val setNumber: Int, val setsInBlock: Int)

    fun position(sets: List<ExerciseSet>, set: ExerciseSet): Position {
        val list = ordered(sets)
        val block = list.filter { it.blockIndex == set.blockIndex }
        val setIndices = block.map { it.setIndex }.distinct()
        return Position(list.indexOfFirst { it.id == set.id } + 1, list.size, setIndices.indexOf(set.setIndex) + 1, setIndices.size)
    }
}
