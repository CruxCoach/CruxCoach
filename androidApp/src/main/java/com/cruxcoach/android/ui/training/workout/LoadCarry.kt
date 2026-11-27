package com.cruxcoach.android.ui.training.workout

import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet

/**
 * A load the athlete changes on an open set carries over to the later open sets of the same exercise
 * and side (owner 2026-10-15: "too heavy" for one set is too heavy for the next one too) – in the guided
 * player and in the list alike. Sets the athlete set by hand to something else keep their value; target
 * and value move together, so autoregulation keeps working from the athlete's load.
 */
internal fun carriedLoads(before: ExerciseSet?, after: ExerciseSet, sets: List<ExerciseSet>, def: ExerciseDefinition): List<ExerciseSet> {
    if (before == null || before.isCompleted || after.isCompleted) return emptyList()
    if (def.load != LoadMode.BODYWEIGHT_PLUS && def.load != LoadMode.EXTERNAL) return emptyList()
    // No load yet means body weight (or nothing on the block): the first "+" starts from zero.
    val old = before.loadKg ?: 0.0
    val new = after.loadKg ?: return emptyList()
    val delta = new - old
    if (kotlin.math.abs(delta) < 1e-9) return emptyList()
    val min = if (def.load == LoadMode.EXTERNAL) 0.0 else Double.NEGATIVE_INFINITY
    return sets.filter {
        val load = it.loadKg ?: 0.0
        it.id != after.id && !it.isCompleted && it.blockIndex == after.blockIndex && it.side == after.side &&
            it.setType == after.setType && it.setIndex > after.setIndex &&
            // Untouched by the athlete, or already following the old value.
            (load == (it.targetLoadKg ?: load) || load == old)
    }.map { s ->
        val moved = ((s.loadKg ?: 0.0) + delta).coerceAtLeast(min)
        s.copy(loadKg = moved, targetLoadKg = moved)
    }
}
