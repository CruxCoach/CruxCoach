package com.cruxcoach.android.ui.training.player

import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType

/**
 * The warm-up on the wall is done and the climbing itself comes now: in a training started from a climbing or board
 * day's card ([PlayerState.climbingDay]), after a warm-up exercise on the wall, once no warm-up and nothing on the wall
 * is left in it – what follows is the block for after the climbing. A workout of the athlete's own that warms up on
 * the wall and goes on at the hangboard is no such day: there the rest bar says what comes next, as after every
 * exercise.
 */
internal fun climbingComesNow(state: PlayerState, finishedExercise: Boolean): Boolean {
    val done = state.lastDone ?: return false
    val next = state.next ?: state.current ?: return false
    fun onWall(set: ExerciseSet) = state.defs[set.exerciseSlug]?.needsClimbingWall == true
    return finishedExercise && state.climbingDay && done.def.needsClimbingWall && !next.def.needsClimbingWall &&
        state.sets.none { it.blockIndex == done.set.blockIndex && it.setType == SetType.WORK } &&
        state.sets.none { it.setType == SetType.WARMUP && !it.isCompleted } &&
        state.sets.none { !it.isCompleted && onWall(it) }
}
