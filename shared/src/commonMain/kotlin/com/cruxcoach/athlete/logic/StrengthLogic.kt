package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

object StrengthMath {

    /** Epley estimate; a single rep is the load itself. */
    fun epley(loadKg: Double, reps: Int): Double = if (reps <= 1) loadKg else loadKg * (1.0 + reps / 30.0)

    /**
     * What the athlete actually held or moved: bodyweight ± added kg for
     * hangs and pull-ups, the lifted load for pick-ups and free weights.
     */
    fun effectiveLoad(mode: LoadMode, loadKg: Double?, bodyweightKg: Double?): Double? = when (mode) {
        LoadMode.BODYWEIGHT_PLUS -> bodyweightKg?.let { it + (loadKg ?: 0.0) }
        LoadMode.EXTERNAL -> loadKg
        LoadMode.BODYWEIGHT -> bodyweightKg
        LoadMode.NONE -> null
    }

    /** Effective load as % of body weight — the strength-to-weight number climbers care about. */
    fun percentBodyweight(mode: LoadMode, loadKg: Double?, bodyweightKg: Double?): Double? {
        val bw = bodyweightKg?.takeIf { it > 0 } ?: return null
        return effectiveLoad(mode, loadKg, bw)?.let { it / bw * 100.0 }
    }

    /** Round to the smallest increment the athlete can actually load (plates, pulley steps). */
    fun roundTo(valueKg: Double, incrementKg: Double): Double {
        val step = incrementKg.takeIf { it > 0 } ?: return valueKg
        return (valueKg / step).roundToInt() * step
    }
}

/** Which result a set improved, compared with everything logged before it. */
enum class RecordKind { LOAD, ESTIMATED_MAX, REPS, DURATION }

data class PersonalRecord(val kind: RecordKind, val value: Double, val previous: Double)

object PersonalRecords {

    private const val MIN_HANG_SECONDS = 5.0

    /** Records are compared like for like: same edge, grip and side. */
    data class Key(val edgeMm: Int?, val grip: Grip?, val side: Side?)

    fun keyOf(set: ExerciseSet) = Key(set.edgeMm?.roundToInt(), set.grip, set.side)

    /** Comparable score of one completed work/test set, or null if it doesn't produce one. */
    fun score(def: ExerciseDefinition, set: ExerciseSet): Pair<RecordKind, Double>? {
        if (!set.isCompleted || set.setType == SetType.WARMUP) return null
        return when (def.kind) {
            ExerciseKind.LOAD_REPS -> {
                val reps = set.reps ?: return null
                if (reps <= 0) return null
                val load = StrengthMath.effectiveLoad(def.load, set.loadKg, set.bodyweightKg)
                    ?: set.loadKg ?: return null
                RecordKind.ESTIMATED_MAX to StrengthMath.epley(load, reps)
            }
            ExerciseKind.REPS -> set.reps?.takeIf { it > 0 }?.let { RecordKind.REPS to it.toDouble() }
            ExerciseKind.TIME -> set.durationS?.takeIf { it > 0 }?.let { RecordKind.DURATION to it }
            ExerciseKind.HANG, ExerciseKind.INTERVAL -> {
                val duration = set.durationS ?: set.workS
                if (duration != null && duration < MIN_HANG_SECONDS) return null
                if (def.load == LoadMode.NONE || def.load == LoadMode.BODYWEIGHT) {
                    return duration?.let { RecordKind.DURATION to it }
                }
                val load = StrengthMath.effectiveLoad(def.load, set.loadKg, set.bodyweightKg)
                    ?: set.loadKg ?: return null
                RecordKind.LOAD to load
            }
            ExerciseKind.CLIMB -> null
        }
    }

    /**
     * A record needs history to beat: the very first log of an exercise is a
     * baseline, not a PR — otherwise every new exercise would flash "record".
     */
    fun detect(def: ExerciseDefinition, set: ExerciseSet, history: List<ExerciseSet>): PersonalRecord? {
        val (kind, value) = score(def, set) ?: return null
        val key = keyOf(set)
        val previousBest = history.asSequence()
            .filter { it.id != set.id && keyOf(it) == key }
            .mapNotNull { score(def, it) }
            .filter { it.first == kind }
            .maxOfOrNull { it.second } ?: return null
        return if (value > previousBest + 1e-9) PersonalRecord(kind, value, previousBest) else null
    }

    /** Best score per key, e.g. for the exercise detail page. */
    fun bests(def: ExerciseDefinition, history: List<ExerciseSet>): Map<Key, Pair<RecordKind, Double>> =
        history.mapNotNull { set -> score(def, set)?.let { keyOf(set) to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, scores) -> scores.maxBy { it.second } }
}

/** Values to prefill from the athlete's last session of an exercise (ghost values). */
object GhostValues {

    data class Ghost(
        val setIndex: Int,
        val side: Side?,
        val reps: Int?,
        val durationS: Double?,
        val loadKg: Double?,
        val edgeMm: Double?,
        val grip: Grip?,
        val rir: Int?,
    )

    /** [history] newest first, completed sets only. Returns the last workout's sets of that exercise. */
    fun lastSession(history: List<ExerciseSet>): List<Ghost> {
        val lastWorkout = history.firstOrNull { it.isCompleted }?.workoutId ?: return emptyList()
        return history.filter { it.workoutId == lastWorkout && it.isCompleted && it.setType != SetType.WARMUP }
            .sortedWith(compareBy({ it.setIndex }, { it.side?.ordinal ?: -1 }))
            .map { Ghost(it.setIndex, it.side, it.reps, it.durationS, it.loadKg, it.edgeMm, it.grip, it.rir) }
    }

    fun forSet(ghosts: List<Ghost>, setIndex: Int, side: Side?): Ghost? =
        ghosts.firstOrNull { it.setIndex == setIndex && it.side == side }
            ?: ghosts.lastOrNull { it.side == side }
            ?: ghosts.lastOrNull()
}

enum class ProgressionVerdict { TOO_EASY, ON_TRACK, TOO_HARD }

data class ProgressionSuggestion(
    val verdict: ProgressionVerdict,
    /** Suggested load for next time (may be negative = more assistance). */
    val nextLoadKg: Double? = null,
    val harderSlug: String? = null,
    val easierSlug: String? = null,
)

/**
 * Double progression, one lever at a time: when every work set hit the top of
 * the range with reps in reserve, suggest a small load step — or the next
 * variant in the chain when the exercise has no load. When most sets missed
 * the bottom of the range, step back. The athlete decides; nothing changes
 * silently.
 */
object ProgressionAdvisor {

    fun evaluate(def: ExerciseDefinition, lastSession: List<ExerciseSet>, smallestIncrementKg: Double): ProgressionSuggestion? {
        val work = lastSession.filter { it.isCompleted && it.setType == SetType.WORK }
        if (work.isEmpty() || def.kind == ExerciseKind.CLIMB) return null

        val easyEnough = work.all { (it.rir ?: 2) >= 2 }
        val (hitTop, missedBottom) = when (def.kind) {
            ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> {
                val top = def.defaults.repsMax ?: work.firstNotNullOfOrNull { it.targetReps } ?: return null
                val bottom = def.defaults.repsMin ?: top
                val hit = work.all { (it.reps ?: 0) >= (it.targetReps?.coerceAtLeast(top) ?: top) }
                val missed = work.count { (it.reps ?: 0) < bottom }
                hit to missed
            }
            else -> {
                val target = work.firstNotNullOfOrNull { it.targetDurationS }
                    ?: def.defaults.durationS?.toDouble() ?: def.defaults.workS?.toDouble() ?: return null
                val hit = work.all { (it.durationS ?: it.workS ?: 0.0) >= target }
                val missed = work.count { (it.durationS ?: it.workS ?: 0.0) < target * 0.8 }
                hit to missed
            }
        }
        val tooHard = missedBottom >= ceil(work.size / 2.0).toInt() ||
            (work.all { it.rir == 0 } && missedBottom > 0)
        val lastLoad = work.lastOrNull { it.loadKg != null }?.loadKg
        val step = increment(def, smallestIncrementKg)
        val supportsLoad = def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL

        return when {
            tooHard -> when {
                supportsLoad && (def.load == LoadMode.BODYWEIGHT_PLUS || (lastLoad ?: 0.0) > step) ->
                    ProgressionSuggestion(ProgressionVerdict.TOO_HARD, nextLoadKg = (lastLoad ?: 0.0) - step,
                        easierSlug = def.easier)
                else -> ProgressionSuggestion(ProgressionVerdict.TOO_HARD, easierSlug = def.easier)
            }
            hitTop && easyEnough -> when {
                supportsLoad -> ProgressionSuggestion(ProgressionVerdict.TOO_EASY,
                    nextLoadKg = (lastLoad ?: 0.0) + step, harderSlug = def.harder)
                else -> ProgressionSuggestion(ProgressionVerdict.TOO_EASY, harderSlug = def.harder)
            }
            else -> ProgressionSuggestion(ProgressionVerdict.ON_TRACK)
        }
    }

    /** Barbells load both sides; everything else steps by the smallest increment (min 0.5 kg). */
    fun increment(def: ExerciseDefinition, smallestIncrementKg: Double): Double {
        val base = smallestIncrementKg.coerceAtLeast(0.5)
        return if (EquipmentV2.BARBELL in def.equipment) base * 2 else base
    }
}

/**
 * Warm-up ramp before heavy loaded sets — the finger-board version of
 * "empty bar, 50 %, 70 %, 85 %". Steps are % of the effective load, so for a
 * hang at +10 kg and 70 kg body weight the first step can be an assisted hang.
 */
object WarmupRamp {

    data class Step(val percent: Int, val loadKg: Double, val reps: Int?, val durationS: Int?)

    private val PERCENTS = listOf(50, 70, 85)

    fun build(def: ExerciseDefinition, workingLoadKg: Double?, bodyweightKg: Double?, incrementKg: Double): List<Step> {
        if (def.kind !in setOf(ExerciseKind.HANG, ExerciseKind.LOAD_REPS, ExerciseKind.INTERVAL)) return emptyList()
        val working = workingLoadKg ?: return emptyList()
        return when (def.load) {
            LoadMode.EXTERNAL -> {
                if (working <= 0) return emptyList()
                PERCENTS.mapIndexed { i, p ->
                    Step(p, StrengthMath.roundTo(working * p / 100.0, incrementKg), repsFor(def, i), durationFor(def))
                }.filter { it.loadKg > 0 }
            }
            LoadMode.BODYWEIGHT_PLUS -> {
                val bw = bodyweightKg?.takeIf { it > 0 } ?: return emptyList()
                val total = bw + working
                PERCENTS.mapIndexed { i, p ->
                    Step(p, StrengthMath.roundTo(total * p / 100.0 - bw, incrementKg), repsFor(def, i), durationFor(def))
                }.filter { it.loadKg < working }
            }
            else -> emptyList()
        }
    }

    private fun repsFor(def: ExerciseDefinition, index: Int): Int? =
        if (def.kind == ExerciseKind.LOAD_REPS) listOf(8, 5, 2)[index] else null

    private fun durationFor(def: ExerciseDefinition): Int? =
        if (def.kind == ExerciseKind.LOAD_REPS) null else floor((def.defaults.durationS ?: def.defaults.workS ?: 10) * 0.6).toInt().coerceIn(3, 10)
}
