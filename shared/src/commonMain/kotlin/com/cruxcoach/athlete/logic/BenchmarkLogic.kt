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
    /** A careful start guess ([StartEstimate]), not a logged or entered value. */
    val estimated: Boolean = false,
)

/**
 * A careful first load for finger work with an external weight – block lifts
 * and pick-ups – when nothing is logged or entered yet, so the first set does
 * not read "0 kg" (owner 2026-10-09: "correcting is fine, but 0 should not be
 * the suggestion"). The two-hand 10-second total on 20 mm comes from a related
 * value when there is one (two-arm pick-up, a max hang at 70 %, a one-arm
 * pick-up at 45 % of two hands), else from body weight and the climbing grade:
 * the low end of [GradeStrengthNorms]' max-hang band (6a without a grade) at
 * 70 %. The result is taken at 80 %, and the first session feels out from
 * 85 % of that (owner 2026-10-10: "38 kg sounds like a lot for 6b+"): the
 * sets after it move by about 10 % on the reserve answer
 * ([SetAutoregulation] feelOut), so a guess that is too high or too low is
 * found within two sets.
 */
object StartEstimate {
    const val CAUTION = 0.8
    /** The first session starts this far under the careful guess and feels its way from there. */
    const val FEEL_OUT_START = 0.85
    /** A two-arm pick-up on an edge against a two-arm hang on the same edge. */
    const val PICKUP_OF_HANG = 0.7
    /** One hand's share of the two-hand total. */
    const val ONE_ARM_SHARE = 0.45
    /** 6a on the unified scale, for climbers without a grade. */
    const val DEFAULT_DIFFICULTY = 16.0

    fun applies(def: ExerciseDefinition): Boolean =
        def.load == LoadMode.EXTERNAL && BenchmarkMath.capacityKind(def) == CapacityKind.TEN_SECOND_MAX &&
            com.cruxcoach.athlete.catalog.LoadDomain.FINGER in def.domains

    /**
     * Working weight as a share of body weight for a first session of common dumbbell, kettlebell,
     * barbell and machine lifts without any value (per dumbbell for one-handed and paired work, else the
     * total). Device test 2026-10-17: wrist curls were planned at 0 kg. Deliberately on the light side –
     * the reserve answer after each set moves the next sets, and the first logged sets become the value.
     */
    private val LIFT_SHARE = mapOf(
        "antagonist.wrist_curl" to 0.06, "antagonist.reverse_wrist_curl" to 0.04,
        "antagonist.eccentric_wrist_extension" to 0.04, "antagonist.forearm_rotation" to 0.04,
        "antagonist.radial_ulnar_deviation" to 0.04, "antagonist.side_lying_external_rotation" to 0.025,
        "antagonist.cuban_press" to 0.03, "antagonist.prone_iyt_bench" to 0.02,
        "antagonist.bottoms_up_kettlebell_carry" to 0.1, "antagonist.turkish_get_up" to 0.12,
        "pull.dumbbell_row" to 0.22, "pull.lat_pulldown" to 0.55,
        "push.dumbbell_bench_press" to 0.18, "push.dumbbell_overhead_press" to 0.12,
        "core.dumbbell_side_bend" to 0.18,
        "legs.goblet_squat" to 0.22, "legs.hip_thrust" to 0.6, "legs.romanian_deadlift" to 0.55,
        "legs.back_squat" to 0.55, "legs.conventional_deadlift" to 0.7, "legs.single_leg_rdl" to 0.12,
        "legs.front_squat" to 0.45,
    )

    /**
     * The start guess for a loaded lift ([LIFT_SHARE]) as a one-rep max, so that the usual prescription
     * (top of the rep range, two in reserve) plans that working weight; null for anything else.
     */
    fun liftCapacity(def: ExerciseDefinition, bodyweightKg: Double?): Capacity? {
        val share = LIFT_SHARE[def.slug] ?: return null
        if (def.load != LoadMode.EXTERNAL || def.kind != com.cruxcoach.athlete.catalog.ExerciseKind.LOAD_REPS) return null
        val bw = bodyweightKg?.takeIf { it > 0 } ?: return null
        val reps = def.defaults.repsMax ?: def.defaults.repsMin ?: 10
        return Capacity(CapacityKind.E1RM_TOTAL, bw * share * (1.0 + (reps + BenchmarkMath.TARGET_RIR) / 30.0), bw, estimated = true)
    }

    /** Two-hand 10-second pick-up total from body weight and grade alone. */
    fun twoHandFromGrade(bodyweightKg: Double, difficulty: Double?): Double {
        val d = (difficulty ?: DEFAULT_DIFFICULTY).coerceIn(13.0, 32.0)
        val band = requireNotNull(GradeStrengthNorms.band(GradeStrengthNorms.Metric.FINGER_MAX_HANG_20MM, d))
        return band.low / 100.0 * bodyweightKg * PICKUP_OF_HANG
    }

    /**
     * The start guess for [def], or null when it does not apply. [twoHandTotalKg]
     * is a two-hand pick-up total derived from a related value, if any.
     */
    fun capacity(def: ExerciseDefinition, bodyweightKg: Double?, twoHandTotalKg: Double?, difficulty: Double?): Capacity? {
        if (!applies(def)) return null
        val bw = bodyweightKg?.takeIf { it > 0 } ?: return null
        val twoHand = twoHandTotalKg?.takeIf { it > 0 } ?: twoHandFromGrade(bw, difficulty)
        val own = if (def.unilateral) twoHand * ONE_ARM_SHARE else twoHand
        return Capacity(CapacityKind.TEN_SECOND_MAX, own * CAUTION * FEEL_OUT_START, bw, estimated = true)
    }
}

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

    /** Reps in reserve a strength prescription aims for (RIR 2). */
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

    /**
     * Capacity a stored value stands for; [bodyweightKg] fills in a missing snapshot.
     * A value entered as several sets ([Benchmark.sets]) is read back the
     * way [LoadPrescriber] plans: "5 × 5 at +20 kg" becomes the capacity for
     * which today's plan would be 5 reps at +20 kg (RIR 2), a hang the
     * capacity it would be planned at, reps and holds the maximum they are 70 % of.
     */
    fun capacity(def: ExerciseDefinition, b: Benchmark, bodyweightKg: Double?): Capacity? {
        val bw = b.bodyweightKg ?: bodyweightKg
        if (b.sets != null) return fromWorkingSets(def, b, bw)
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

    private fun fromWorkingSets(def: ExerciseDefinition, b: Benchmark, bw: Double?): Capacity? = when (capacityKind(def)) {
        CapacityKind.E1RM_TOTAL -> {
            val reps = b.reps?.takeIf { it > 0 }
            val total = StrengthMath.effectiveLoad(def.load, b.loadKg, bw)
            if (reps == null || total == null) null
            else Capacity(CapacityKind.E1RM_TOTAL, total * (1.0 + (reps + TARGET_RIR) / 30.0), bw)
        }
        CapacityKind.TEN_SECOND_MAX -> StrengthMath.effectiveLoad(def.load, b.loadKg, bw)?.let { total ->
            val value = if (def.kind == ExerciseKind.INTERVAL) total / LoadPrescriber.REPEATER_SHARE else {
                val d = b.durationS?.takeIf { it > 0 } ?: 10.0
                total / HoldCurve.relative(d) / LoadPrescriber.holdShare(d.roundToInt())
            }
            Capacity(CapacityKind.TEN_SECOND_MAX, value, bw)
        }
        CapacityKind.MAX_REPS -> b.reps?.takeIf { it > 0 }?.let { Capacity(CapacityKind.MAX_REPS, it / LoadPrescriber.MAX_SHARE, bw) }
        CapacityKind.MAX_SECONDS -> b.durationS?.takeIf { it > 0 }?.let { Capacity(CapacityKind.MAX_SECONDS, it / LoadPrescriber.MAX_SHARE, bw) }
        null -> null
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
 * Turns a capacity into today's targets, for climbers:
 * loaded reps at RIR 2 from the e1RM, max hangs at 90 % of what the athlete
 * can hold for the prescribed time, long density hangs at 85 %, repeaters at
 * 65 % of the 10-s maximum, bodyweight reps and holds at 70 % of the maximum.
 * Loads snap to the athlete's smallest weight step. Tests get no target.
 */
object LoadPrescriber {

    /** Body-weight reps and holds: share of the maximum a set is planned at. */
    const val MAX_SHARE = 0.7
    /** Repeaters: share of the 10-s maximum. */
    const val REPEATER_SHARE = 0.65

    /** Weighted holds: share of what the athlete can hold for [durationS]; long density hangs go easier. */
    fun holdShare(durationS: Int): Double = if (durationS >= 20) 0.85 else 0.90

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
                    toLoad(capacity.value * REPEATER_SHARE)?.let { LoadTarget(it, null, work, capacity) }
                } else {
                    val d = item.durationS ?: def.defaults.durationS ?: 10
                    toLoad(capacity.value * HoldCurve.relative(d.toDouble()) * holdShare(d))?.let { LoadTarget(it, null, d, capacity) }
                }
            }
            CapacityKind.MAX_REPS -> LoadTarget(null, max(1, (capacity.value * MAX_SHARE).roundToInt()), null, capacity)
            CapacityKind.MAX_SECONDS -> LoadTarget(null, null, max(3, (capacity.value * MAX_SHARE).roundToInt()), capacity)
        }
    }
}

/**
 * Order and rests of the guided training mode.
 *
 * Without circuits a training is played block by block, set by set, the two
 * hands of one set next to each other. A circuit ([ExerciseSet.circuit]) is
 * played where its first member block stands: the members' warm-ups first,
 * block by block, then round by round ([ExerciseSet.setIndex]), within a
 * round in block order. Its position follows the lowest member block, not the
 * key, so blocks moving down (an exercise inserted before or inside it) never
 * tear it apart.
 */
object PlayerQueue {

    const val SIDE_SWITCH_MAX_S = 30
    const val DEFAULT_REST_S = 90

    /**
     * The circuit each block is played in. A block counts as a member when
     * any of its sets carries a key, so a warm-up row without one still opens
     * the circuit instead of trailing it.
     */
    private fun blockCircuits(sets: List<ExerciseSet>): Map<Int, Int> =
        sets.filter { it.circuit != null }.groupBy { it.blockIndex }.mapValues { (_, rows) ->
            rows.minWith(compareBy({ if (it.setType == SetType.WARMUP) 1 else 0 }, { it.setIndex })).circuit!!
        }

    private val straight: Comparator<ExerciseSet> = compareBy({ it.blockIndex }, { it.setIndex }, { it.side?.ordinal ?: -1 })

    fun ordered(sets: List<ExerciseSet>): List<ExerciseSet> {
        if (sets.none { it.circuit != null }) return sets.sortedWith(straight)
        val byBlock = blockCircuits(sets)
        fun circuitOf(s: ExerciseSet): Int? = s.circuit ?: byBlock[s.blockIndex]
        val start = sets.mapNotNull { s -> circuitOf(s)?.let { it to s.blockIndex } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, blocks) -> blocks.min() }
        // Before the rounds: sets outside circuits (sorting exactly as without circuits) and the members' warm-ups.
        fun pre(s: ExerciseSet) = circuitOf(s) == null || s.setType == SetType.WARMUP
        return sets.sortedWith(compareBy(
            { s -> circuitOf(s)?.let { start.getValue(it) } ?: s.blockIndex },
            { if (pre(it)) 0 else 1 },
            { if (pre(it)) 0 else it.setIndex },
            { it.blockIndex },
            { it.setIndex },
            { it.side?.ordinal ?: -1 },
        ))
    }

    fun current(sets: List<ExerciseSet>): ExerciseSet? = ordered(sets).firstOrNull { !it.isCompleted }

    fun nextAfter(sets: List<ExerciseSet>, done: ExerciseSet): ExerciseSet? {
        val list = ordered(sets)
        val index = list.indexOfFirst { it.id == done.id }
        return list.drop(index + 1).firstOrNull { !it.isCompleted } ?: list.firstOrNull { !it.isCompleted && it.id != done.id }
    }

    /**
     * Rest after [done]: a short side switch between the hands of one set,
     * otherwise the set's rest. In a circuit the set's rest is the short one
     * to the next exercise of the same round (0 = straight on); at the end of
     * a round, or when the training leaves the circuit, the round rest follows.
     */
    fun restAfter(done: ExerciseSet, next: ExerciseSet?, def: ExerciseDefinition): Int {
        if (next == null) return 0
        val rest = done.restS ?: def.defaults.restS ?: DEFAULT_REST_S
        val sideSwitch = next.blockIndex == done.blockIndex && next.setIndex == done.setIndex &&
            next.side != null && next.side != done.side
        if (sideSwitch) return minOf(SIDE_SWITCH_MAX_S, rest)
        if (done.circuit != null && done.setType != SetType.WARMUP) {
            val sameRound = next.circuit == done.circuit && next.setType != SetType.WARMUP && next.setIndex == done.setIndex
            return if (sameRound) rest else done.roundRestS ?: rest
        }
        return rest
    }

    /**
     * Where a set stands. [setNumber] of [setsInBlock] counts warm-ups;
     * [workNumber] of [workTotal] counts only the block's work (and test) sets
     * and is null on a warm-up. [round] of [rounds] is set for circuit sets
     * ([round] null on a circuit warm-up).
     */
    data class Position(
        val step: Int,
        val total: Int,
        val setNumber: Int,
        val setsInBlock: Int,
        val round: Int? = null,
        val rounds: Int? = null,
        val workNumber: Int? = null,
        val workTotal: Int? = null,
    )

    fun position(sets: List<ExerciseSet>, set: ExerciseSet): Position {
        val list = ordered(sets)
        val block = list.filter { it.blockIndex == set.blockIndex }
        val setIndices = block.map { it.setIndex }.distinct()
        val work = block.filter { it.setType != SetType.WARMUP }.map { it.setIndex }.distinct().sorted()
        val warmup = set.setType == SetType.WARMUP
        val circuit = set.circuit ?: blockCircuits(sets)[set.blockIndex]
        val roundIndices = if (circuit == null) emptyList() else {
            val byBlock = blockCircuits(sets)
            list.filter { (it.circuit ?: byBlock[it.blockIndex]) == circuit && it.setType != SetType.WARMUP }
                .map { it.setIndex }.distinct().sorted()
        }
        return Position(
            step = list.indexOfFirst { it.id == set.id } + 1,
            total = list.size,
            setNumber = setIndices.indexOf(set.setIndex) + 1,
            setsInBlock = setIndices.size,
            round = if (circuit == null || warmup) null else (roundIndices.indexOf(set.setIndex) + 1).takeIf { it > 0 },
            rounds = if (circuit == null) null else roundIndices.size.takeIf { it > 0 },
            workNumber = if (warmup) null else (work.indexOf(set.setIndex) + 1).takeIf { it > 0 },
            workTotal = work.size.takeIf { it > 0 },
        )
    }

    /**
     * One page of the training view: one exercise ([blockIndexes] of one
     * block), or a whole circuit with all its member blocks and their warm-ups.
     */
    data class Page(val blockIndexes: List<Int>, val circuit: Int?)

    /** The pages of a training in the order the player reaches them ([ordered]). */
    fun pages(sets: List<ExerciseSet>): List<Page> {
        val byBlock = blockCircuits(sets)
        val pages = mutableListOf<Page>()
        val seenCircuits = mutableSetOf<Int>()
        val seenBlocks = mutableSetOf<Int>()
        for (s in ordered(sets)) {
            val circuit = byBlock[s.blockIndex]
            if (circuit != null) {
                if (seenCircuits.add(circuit)) {
                    pages += Page(byBlock.filterValues { it == circuit }.keys.sorted(), circuit)
                }
            } else if (seenBlocks.add(s.blockIndex)) {
                pages += Page(listOf(s.blockIndex), null)
            }
        }
        return pages
    }

    /** Index of the page [set] is on in [pages]; -1 when its block is not in [sets]. */
    fun pageOf(sets: List<ExerciseSet>, set: ExerciseSet): Int {
        val circuit = blockCircuits(sets)[set.blockIndex]
        return pages(sets).indexOfFirst { page ->
            if (circuit != null) page.circuit == circuit else page.circuit == null && set.blockIndex in page.blockIndexes
        }
    }
}
