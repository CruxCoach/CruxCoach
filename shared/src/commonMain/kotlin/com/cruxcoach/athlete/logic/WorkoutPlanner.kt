package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.SideMode

/**
 * Turns a routine item (or a bare exercise) into planned set rows: the
 * prescription goes into the target columns, the athlete's last values into
 * the actual columns as ghost values, so ticking a set off is one tap.
 *
 * One-sided exercises get one row per side; an open one-sided limb injury
 * drops the injured side (and the item's own side restriction is honoured).
 */
object WorkoutPlanner {

    data class PlannedBlock(val definition: ExerciseDefinition, val sets: List<ExerciseSet>, val skippedForInjury: Boolean)

    fun itemFor(def: ExerciseDefinition): RoutineItem = RoutineItem(
        slug = def.slug,
        sets = def.defaults.sets ?: 3,
        repsMin = def.defaults.repsMin,
        repsMax = def.defaults.repsMax,
        durationS = def.defaults.durationS,
        restS = def.defaults.restS,
        edgeMm = def.defaults.edgeMm,
        workS = def.defaults.workS,
        restBetweenS = def.defaults.restBetweenS,
        repsPerSet = def.defaults.repsPerSet ?: def.defaults.rounds,
    )

    fun plan(
        workoutId: String,
        blockIndex: Int,
        item: RoutineItem,
        catalog: ExerciseCatalog,
        history: List<ExerciseSet>,
        injuries: List<Injury>,
        bodyweightKg: Double?,
        /** Performance value per side (null = two-handed / not one-sided); null when none is known. */
        capacityFor: (Side?) -> Capacity? = { null },
        incrementKg: Double = 1.0,
        newId: () -> String,
    ): PlannedBlock {
        val def = catalog.fallbackFor(item.slug)
        val advice = InjuryAdvisor.assess(def, injuries)
        val sides: List<Side?> = when {
            !def.unilateral -> listOf(null)
            advice.verdict == InjuryVerdict.ONE_SIDE_ONLY -> listOf(advice.allowedSide)
            item.sides == SideMode.LEFT_ONLY -> listOf(Side.LEFT)
            item.sides == SideMode.RIGHT_ONLY -> listOf(Side.RIGHT)
            else -> listOf(Side.LEFT, Side.RIGHT)
        }
        val ghosts = GhostValues.lastSession(history)
        val setType = when {
            item.test -> SetType.TEST
            item.warmup -> SetType.WARMUP
            else -> SetType.WORK
        }
        val itemReps = item.repsMax ?: item.repsMin
        // With a performance value the prescription wins over last time's
        // numbers: that is what makes loads progress instead of repeating.
        val targets = sides.associateWith { side -> LoadPrescriber.prescribe(def, item, capacityFor(side), bodyweightKg, incrementKg) }
        val rows = (0 until item.sets.coerceIn(1, 20)).flatMap { setIndex ->
            sides.map { side ->
                val ghost = GhostValues.forSet(ghosts, setIndex, side)
                val target = targets[side]
                val targetReps = target?.reps ?: itemReps
                val targetDuration = target?.durationS ?: item.durationS
                ExerciseSet(
                    id = newId(),
                    workoutId = workoutId,
                    exerciseSlug = def.slug,
                    blockIndex = blockIndex,
                    setIndex = setIndex,
                    setType = setType,
                    side = side,
                    targetReps = targetReps.takeIf { def.kind == ExerciseKind.REPS || def.kind == ExerciseKind.LOAD_REPS },
                    targetDurationS = targetDuration?.toDouble()?.takeIf { def.kind == ExerciseKind.TIME || def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.CLIMB },
                    targetLoadKg = target?.loadKg ?: item.loadKg,
                    reps = when (def.kind) {
                        ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> target?.reps ?: ghost?.reps ?: targetReps
                        ExerciseKind.CLIMB -> ghost?.reps ?: item.repsPerSet
                        else -> null
                    },
                    durationS = when (def.kind) {
                        ExerciseKind.TIME, ExerciseKind.HANG -> target?.durationS?.toDouble() ?: ghost?.durationS ?: item.durationS?.toDouble()
                        else -> null
                    },
                    loadKg = if (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL)
                        target?.loadKg ?: ghost?.loadKg ?: item.loadKg else null,
                    edgeMm = (ghost?.edgeMm ?: item.edgeMm?.toDouble())
                        .takeIf { def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL },
                    grip = ghost?.grip ?: item.grip,
                    workS = item.workS?.toDouble().takeIf { def.kind == ExerciseKind.INTERVAL },
                    restBetweenS = item.restBetweenS?.toDouble().takeIf { def.kind == ExerciseKind.INTERVAL },
                    repsPerSet = item.repsPerSet.takeIf { def.kind == ExerciseKind.INTERVAL },
                    restS = item.restS,
                    bodyweightKg = bodyweightKg,
                )
            }
        }
        return PlannedBlock(def, if (advice.verdict == InjuryVerdict.AVOID) emptyList() else rows,
            skippedForInjury = advice.verdict == InjuryVerdict.AVOID)
    }

    /** A further set copying the last one of the block (the "+ Satz" button). */
    fun extraSet(block: List<ExerciseSet>, newId: () -> String): List<ExerciseSet> {
        val last = block.maxByOrNull { it.setIndex } ?: return emptyList()
        val sides = block.filter { it.setIndex == last.setIndex }.map { it.side }
        return sides.map { side ->
            val template = block.last { it.setIndex == last.setIndex && it.side == side }
            template.copy(id = newId(), setIndex = last.setIndex + 1, completedAt = null, rir = null, note = null)
        }
    }
}

/** End-of-training summary (MCI's reward screen, without confetti). */
data class WorkoutSummary(
    val completedSets: Int,
    val exercises: Int,
    val durationMinutes: Int?,
    val records: List<Pair<String, PersonalRecord>>,
    val suggestions: List<Pair<String, ProgressionSuggestion>>,
    /** Hold time per side for one-sided finger work, to spot imbalances. */
    val sideLoad: Map<Side, Double>,
)

object WorkoutSummarizer {

    fun summarize(
        sets: List<ExerciseSet>,
        durationMinutes: Int?,
        catalog: ExerciseCatalog,
        historyBefore: (String) -> List<ExerciseSet>,
        smallestIncrementKg: Double,
    ): WorkoutSummary {
        val done = sets.filter { it.isCompleted }
        val bySlug = done.groupBy { it.exerciseSlug }
        val records = mutableListOf<Pair<String, PersonalRecord>>()
        val suggestions = mutableListOf<Pair<String, ProgressionSuggestion>>()
        bySlug.forEach { (slug, slugSets) ->
            val def = catalog.fallbackFor(slug)
            val earlier = historyBefore(slug).filter { h -> slugSets.none { it.id == h.id } }
            slugSets.mapNotNull { PersonalRecords.detect(def, it, earlier) }
                .maxByOrNull { it.value - it.previous }
                ?.let { records += slug to it }
            ProgressionAdvisor.evaluate(def, slugSets, smallestIncrementKg)
                ?.takeIf { it.verdict != ProgressionVerdict.ON_TRACK }
                ?.let { suggestions += slug to it }
        }
        // Like with like: per exercise the same number of sets on each side (work sets when both sides have
        // some), so a training ended before the last right-hand set does not read "right 30 % weaker".
        fun work(set: ExerciseSet): Double {
            val def = catalog.fallbackFor(set.exerciseSlug)
            // Total load (body weight ± added, or the lifted block), so +0 vs +2 kg is not "half".
            val load = StrengthMath.effectiveLoad(def.load, set.loadKg, set.bodyweightKg) ?: set.loadKg ?: 1.0
            return (set.durationS ?: 0.0) * load.coerceAtLeast(1.0)
        }
        val sideLoad = mutableMapOf<Side, Double>()
        done.filter { it.side != null && catalog.fallbackFor(it.exerciseSlug).kind == ExerciseKind.HANG }
            .groupBy { it.exerciseSlug }
            .forEach { (_, slugSets) ->
                val bothWork = Side.entries.all { side -> slugSets.any { it.side == side && it.setType == SetType.WORK } }
                val pool = if (bothWork) slugSets.filter { it.setType == SetType.WORK } else slugSets
                val left = pool.filter { it.side == Side.LEFT }.sortedBy { it.setIndex }
                val right = pool.filter { it.side == Side.RIGHT }.sortedBy { it.setIndex }
                val n = minOf(left.size, right.size)
                if (n == 0) return@forEach
                sideLoad[Side.LEFT] = (sideLoad[Side.LEFT] ?: 0.0) + left.take(n).sumOf(::work)
                sideLoad[Side.RIGHT] = (sideLoad[Side.RIGHT] ?: 0.0) + right.take(n).sumOf(::work)
            }
        return WorkoutSummary(done.size, bySlug.size, durationMinutes, records, suggestions, sideLoad)
    }
}
