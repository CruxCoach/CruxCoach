package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.Benchmark
import com.cruxcoach.athlete.model.BenchmarkSource
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side

/**
 * The athlete's word on loads (owner 2026-10-15: "own loads win over the suggestions"). A load the
 * athlete lowers during a training used to be forgotten: performance values only rise by themselves,
 * so the next training planned the old, heavier load again and it had to be corrected every time
 * (UX review 2026-10-16). Now a training in which the athlete worked lighter than planned leaves a
 * performance value "from your training" in working-set form – "4 × 5 at +10 kg" – which plans exactly
 * that load next time ([BenchmarkMath.capacity] reads working sets back the way [LoadPrescriber] plans).
 * It is an ordinary value: shown under performance values, deletable (planning then falls back to the
 * one before), raised again by sets that prove more, and removed with the training it came from.
 */
object OwnLoads {

    /** The athlete worked at the lowered load for at least this many work sets (one lighter last set is fatigue, not a choice). */
    const val MIN_SETS = 2

    private const val EPS = 1e-6

    /**
     * Working-set values for the sides of [def] the athlete trained lighter than planned in one
     * training ([sets]: that training's sets of [def]). A set counts as lowered by the athlete when its
     * load is below its plan: autoregulation moves plan and load together, only a hand-set load leaves
     * the plan behind (the sets after it follow the athlete's load and take it as their plan).
     */
    fun lowered(def: ExerciseDefinition, sets: List<ExerciseSet>, newId: () -> String, now: Long): List<Benchmark> {
        if (def.load != LoadMode.BODYWEIGHT_PLUS && def.load != LoadMode.EXTERNAL) return emptyList()
        if (def.kind == ExerciseKind.CLIMB || def.kind == ExerciseKind.REPS || def.kind == ExerciseKind.TIME) return emptyList()
        return sets.filter { it.exerciseSlug == def.slug && it.setType == SetType.WORK && it.isCompleted }
            .groupBy { it.side }
            .mapNotNull { (side, rows) -> forSide(def, side, rows.sortedWith(compareBy({ it.blockIndex }, { it.setIndex }))) }
            .map { it.copy(id = newId(), measuredAt = now) }
    }

    private fun forSide(def: ExerciseDefinition, side: Side?, work: List<ExerciseSet>): Benchmark? {
        val first = work.indexOfFirst { s ->
            val load = s.loadKg ?: return@indexOfFirst false
            val plan = s.targetLoadKg ?: return@indexOfFirst false
            load < plan - EPS
        }
        if (first < 0) return null
        val chosen = work[first].loadKg ?: return null
        // The sets the athlete did at (or below) the load they chose, from that set on.
        val atChosen = work.drop(first).filter { (it.loadKg ?: Double.MAX_VALUE) <= chosen + EPS }
        if (atChosen.size < MIN_SETS) return null
        val reps = atChosen.mapNotNull { it.reps }.minOrNull()
        val seconds = atChosen.mapNotNull { it.durationS }.minOrNull()
        return when (def.kind) {
            ExerciseKind.LOAD_REPS -> reps?.takeIf { it > 0 }?.let { value(def, side, work[first], chosen, atChosen.size, reps = it) }
            ExerciseKind.HANG -> seconds?.takeIf { it >= 3.0 }?.let { value(def, side, work[first], chosen, atChosen.size, seconds = it) }
            ExerciseKind.INTERVAL -> value(def, side, work[first], chosen, atChosen.size)
            else -> null
        }
    }

    private fun value(
        def: ExerciseDefinition, side: Side?, at: ExerciseSet, loadKg: Double, sets: Int,
        reps: Int? = null, seconds: Double? = null,
    ) = Benchmark(
        id = "", exerciseSlug = def.slug, side = side, edgeMm = at.edgeMm, grip = at.grip, loadKg = loadKg,
        reps = reps, durationS = seconds ?: at.workS, bodyweightKg = at.bodyweightKg, source = BenchmarkSource.AUTO,
        measuredAt = 0, sets = sets,
    )
}
