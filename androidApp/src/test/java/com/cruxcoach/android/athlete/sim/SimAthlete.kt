package com.cruxcoach.android.athlete.sim

import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.LoadRules
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * The simulated athlete's body (issue #59): what they can really do, how a set goes, and how they adapt.
 * Deliberately not the app's own model, so the app has to find the truth from what the athlete logs:
 *
 * - **Qualities**, not exercises: one true value per quality (fingers, pulling, pushing, legs, core,
 *   wrists and shoulders, power, mobility) in % body weight (1RM, or the 10-s maximum on a 20 mm edge for
 *   fingers); holds and body-weight reps are derived from it. Every exercise reads
 *   its own true capacity from its quality with a fixed, seeded factor, so a swapped exercise keeps what was
 *   trained.
 * - **Reps to failure** follow Brzycki (reps = 37 − 36 · load / 1RM), not the app's Epley.
 * - **Holds**: the time held at a load relative to the 10-second maximum follows t = 10 s · rel^(−1/0.12).
 * - **Fatigue**: each hard set of a quality costs it 3 % for the rest of the session; a bad night costs 5 %.
 * - **Adaptation**: a session with at least two near-failure sets (true reserve ≤ 4) raises the quality by
 *   its rate, less as it nears its ceiling (logistic); fingers adapt slower than muscles.
 * - **Detraining**: after 14 days without stimulus a quality loses 0.4 % a day, down to 85 % of its peak.
 * - **Reserve answers**: the true reserve plus N(0, noise), rounded and capped at "3+", as the app asks.
 */
class SimAthlete(private val persona: Persona, private val rng: Random) {

    enum class Quality { FINGER, PULL, PUSH, LEGS, CORE, PREHAB, POWER, MOBILITY }

    /** True value per quality: % body weight (1RM or 10-s max) for strength, reps or seconds otherwise. */
    val value = Quality.entries.associateWith { persona.start(it) }.toMutableMap()
    private val peak = value.toMutableMap()
    private val lastStimulus = mutableMapOf<Quality, Int>()
    private val exerciseFactor = mutableMapOf<String, Double>()
    private val fatigue = mutableMapOf<Quality, Double>()
    /** Extra loss today (bad sleep, illness), as a share. */
    var dayPenalty = 0.0

    var bodyweightKg: Double = persona.bodyweightKg

    fun qualityOf(def: ExerciseDefinition): Quality = when {
        LoadDomain.FINGER in def.domains && def.kind != ExerciseKind.CLIMB && def.category == ExerciseCategoryV2.FINGER -> Quality.FINGER
        def.category == ExerciseCategoryV2.PULL -> Quality.PULL
        def.category == ExerciseCategoryV2.PUSH -> Quality.PUSH
        def.category == ExerciseCategoryV2.LEGS -> Quality.LEGS
        def.category == ExerciseCategoryV2.CORE -> Quality.CORE
        def.category == ExerciseCategoryV2.ANTAGONIST -> Quality.PREHAB
        def.category == ExerciseCategoryV2.POWER -> Quality.POWER
        else -> Quality.MOBILITY
    }

    private fun factor(def: ExerciseDefinition): Double = exerciseFactor.getOrPut(def.slug) {
        // Seeded per exercise: the same exercise always has the same relation to its quality.
        val r = Random(def.slug.hashCode() * 31 + persona.seed)
        0.9 + r.nextDouble() * 0.2
    }

    private fun effective(q: Quality): Double = (1.0 - (fatigue[q] ?: 0.0)) * (1.0 - dayPenalty)

    /** Strength exercises: 1RM or 10-s maximum of the total load, in kg. */
    fun trueMaxKg(def: ExerciseDefinition, edgeMm: Double? = null): Double {
        val q = qualityOf(def)
        val pct = value.getValue(q) / 100.0 * factor(def)
        var total = bodyweightKg * pct
        if (q == Quality.FINGER) {
            // Smaller edges hold less: about the square root of the edge depth relative to 20 mm.
            val edge = (edgeMm ?: 20.0).coerceIn(6.0, 45.0)
            total *= (edge / 20.0).pow(0.5)
            if (def.unilateral) total *= 0.47
            if (def.load == LoadMode.EXTERNAL && !def.unilateral) total *= 0.72
        } else if (def.load == LoadMode.EXTERNAL) {
            // Free weights and machines move a share of what the quality is in body-weight terms.
            total *= externalShare(def)
            // One dumbbell for one side; wrist and shoulder work is measured per hand already.
            if (def.unilateral && q != Quality.PREHAB) total *= 0.45
        } else if (def.unilateral) {
            // One leg still carries the whole body: single-leg work keeps most of the relative strength; one arm about half.
            total *= if (q == Quality.LEGS) 0.85 else 0.5
        }
        return total
    }

    private fun externalShare(def: ExerciseDefinition): Double = when (qualityOf(def)) {
        Quality.PULL -> 0.55
        Quality.PUSH -> 0.65
        Quality.LEGS -> 0.8
        Quality.CORE -> 0.35
        Quality.PREHAB -> 0.12
        else -> 0.5
    }

    /** Load the athlete moves or holds in a set, in kg (both dumbbells, body weight included). */
    fun totalKg(def: ExerciseDefinition, loadKg: Double?): Double = when (def.load) {
        LoadMode.BODYWEIGHT_PLUS -> bodyweightKg + (loadKg ?: 0.0)
        LoadMode.BODYWEIGHT -> bodyweightKg
        LoadMode.EXTERNAL -> LoadRules.lifted(def, loadKg ?: 0.0)
        LoadMode.NONE -> 0.0
    }

    /**
     * Reps to failure without a free choice of load: body-weight work by Brzycki against the quality's
     * 1RM (harder variants move a larger share of body weight); unloaded mobility and band work is never
     * the limit (40 reps).
     */
    private fun maxRepsUnloaded(def: ExerciseDefinition): Double {
        if (def.load != LoadMode.BODYWEIGHT) return 40.0 * factor(def)
        val q = qualityOf(def)
        val relative = when (q) {
            Quality.PULL, Quality.PUSH -> 0.55 + 0.15 * def.difficulty
            Quality.LEGS -> 0.3 + 0.12 * def.difficulty
            else -> 0.25 + 0.12 * def.difficulty
        }
        val pct = relative / (value.getValue(q) / 100.0 * factor(def))
        return (37.0 - 36.0 * pct).coerceAtLeast(0.0)
    }

    /** Holds without a load to choose: seconds from the quality, shorter for harder variants. */
    private fun maxSecondsUnloaded(def: ExerciseDefinition, q: Quality): Double =
        0.6 * value.getValue(q) * factor(def) / def.difficulty.coerceAtLeast(1).toDouble().pow(0.7)

    data class Result(val reps: Int?, val durationS: Double?, val trueReserve: Double, val reportedRir: Int?)

    /**
     * How the set goes. Warm-ups are done as planned. Climbing and repeaters are done as planned, with no
     * reserve answer (the app does not ask for one either).
     */
    fun perform(def: ExerciseDefinition, set: ExerciseSet, alwaysEasy: Boolean, noise: Double): Result {
        if (set.setType == SetType.WARMUP || def.kind == ExerciseKind.CLIMB || def.kind == ExerciseKind.INTERVAL) {
            return Result(set.targetReps ?: set.reps, set.targetDurationS ?: set.durationS, 3.0, null)
        }
        val q = qualityOf(def)
        val eff = effective(q)
        return when (def.kind) {
            ExerciseKind.LOAD_REPS, ExerciseKind.REPS -> {
                val target = (set.targetReps ?: set.reps ?: 8).coerceAtLeast(1)
                val maxReps = if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) {
                    val oneRm = trueMaxKg(def) * eff
                    val pct = totalKg(def, set.loadKg) / oneRm
                    (37.0 - 36.0 * pct).coerceAtLeast(0.0)
                } else maxRepsUnloaded(def) * eff
                val done = min(target, floor(maxReps).toInt())
                val reserve = maxReps - done
                Result(done, null, reserve, answer(reserve, alwaysEasy, noise))
            }
            ExerciseKind.TIME, ExerciseKind.HANG -> {
                val target = set.targetDurationS ?: set.durationS ?: def.defaults.durationS?.toDouble() ?: 10.0
                val maxSeconds = if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL || q == Quality.FINGER) {
                    val max10 = trueMaxKg(def, set.edgeMm) * eff
                    val rel = (totalKg(def, set.loadKg) / max10).coerceAtLeast(0.05)
                    10.0 * rel.pow(-1.0 / 0.12)
                } else maxSecondsUnloaded(def, q) * eff
                val held = min(target, floor(maxSeconds).coerceAtLeast(0.0))
                val reserve = maxSeconds - held
                Result(null, held, reserve, answer(reserve, alwaysEasy, noise))
            }
            else -> Result(set.targetReps, set.targetDurationS, 3.0, null)
        }
    }

    private fun answer(reserve: Double, alwaysEasy: Boolean, noise: Double): Int {
        if (alwaysEasy) return 3
        val noisy = reserve + rng.nextGaussian() * noise
        return noisy.toInt().coerceIn(0, 3)
    }

    /** After a hard work set of [def]. */
    fun tire(def: ExerciseDefinition, trueReserve: Double) {
        val q = qualityOf(def)
        if (trueReserve <= 4.0) fatigue[q] = (fatigue[q] ?: 0.0) + 0.03
    }

    /** End of a training: adapt the qualities that got enough stimulus, then recover. */
    fun adapt(day: Int, hardSets: Map<Quality, Int>) {
        hardSets.forEach { (q, n) ->
            if (n < 2) return@forEach
            val ceiling = persona.ceiling(q)
            val v = value.getValue(q)
            val gain = persona.rate(q) * (1.0 - v / ceiling).coerceAtLeast(0.0)
            value[q] = v * (1.0 + gain)
            peak[q] = max(peak.getValue(q), value.getValue(q))
            lastStimulus[q] = day
        }
        fatigue.clear()
    }

    /** Climbing trains the fingers and pulling a little, and counts as stimulus against detraining. */
    fun climbed(day: Int, intensityShare: Double) {
        listOf(Quality.FINGER, Quality.PULL, Quality.POWER).forEach { q ->
            val ceiling = persona.ceiling(q)
            val v = value.getValue(q)
            value[q] = v * (1.0 + persona.rate(q) * 0.3 * intensityShare * (1.0 - v / ceiling).coerceAtLeast(0.0))
            peak[q] = max(peak.getValue(q), value.getValue(q))
            lastStimulus[q] = day
        }
    }

    /** One night: qualities without stimulus for two weeks lose a little, never below 85 % of their peak. */
    fun night(day: Int) {
        Quality.entries.forEach { q ->
            val since = day - (lastStimulus[q] ?: 0)
            if (since > 14) value[q] = max(value.getValue(q) * 0.996, peak.getValue(q) * 0.85)
        }
    }

    /** An injury or illness takes [share] off a quality at once (a pulley injury: fingers). */
    fun setback(q: Quality, share: Double) {
        value[q] = value.getValue(q) * (1.0 - share)
    }

    fun approx(a: Double, b: Double, tolerance: Double): Boolean = abs(a - b) <= tolerance * max(abs(a), abs(b))
}

private fun Random.nextGaussian(): Double {
    // Box–Muller with the seeded generator, so a run is reproducible.
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}
