package com.cruxcoach.android.ui.training.player

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.ExerciseText
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Warmed up – time to climb" (device walk 2026-11-22) belongs to a climbing day's training: after the warm-up on the
 * wall, once nothing on the wall is left. A workout of one's own that warms up on the wall and goes on at the
 * hangboard said it too.
 */
class ClimbNowTest {

    private fun def(slug: String, kind: ExerciseKind, vararg equipment: EquipmentV2) =
        ExerciseDefinition(slug, ExerciseCategoryV2.WARMUP, kind, LoadMode.NONE, equipment = equipment.toList(),
            i18n = mapOf("en" to ExerciseText(slug)))

    private val circles = def("warmup.arm_circles", ExerciseKind.REPS)
    private val easyClimbing = def("warmup.easy_climbing", ExerciseKind.CLIMB, EquipmentV2.WALL)
    private val pushUp = def("push.push_up", ExerciseKind.REPS)
    private val limit = def("power.limit_bouldering", ExerciseKind.CLIMB, EquipmentV2.WALL)
    private val defs = listOf(circles, easyClimbing, pushUp, limit).associateBy { it.slug }

    private fun set(id: String, def: ExerciseDefinition, block: Int, index: Int, type: SetType, done: Boolean) =
        ExerciseSet(id, "w", def.slug, block, index, setType = type, completedAt = if (done) 1L else null)

    /** A climbing day's training: two warm-ups (the second on the wall), all done, then the block for afterwards. */
    private val sets = listOf(
        set("a", circles, 0, 0, SetType.WARMUP, true),
        set("b1", easyClimbing, 1, 0, SetType.WARMUP, true),
        set("b2", easyClimbing, 1, 1, SetType.WARMUP, true),
        set("c1", pushUp, 2, 0, SetType.WORK, false),
        set("c2", pushUp, 2, 1, SetType.WORK, false),
    )

    private fun state(sets: List<ExerciseSet>, done: String, next: String, climbingDay: Boolean = true): PlayerState {
        fun item(id: String) = sets.first { it.id == id }.let { PlayerItem(it, defs.getValue(it.exerciseSlug), PlayerQueue.position(sets, it)) }
        return PlayerState(phase = PlayerPhase.REST, sets = sets, lastDone = item(done), next = item(next), defs = defs, climbingDay = climbingDay)
    }

    @Test
    fun `after the warm-up on the wall of a climbing day's training the climbing comes`() {
        assertTrue(climbingComesNow(state(sets, "b2", "c1"), finishedExercise = true))
        // Not in the middle of the exercise.
        assertFalse(climbingComesNow(state(sets, "b2", "c1"), finishedExercise = false))
    }

    @Test
    fun `a workout of one's own that warms up on the wall is no climbing day`() {
        assertFalse(climbingComesNow(state(sets, "b2", "c1", climbingDay = false), finishedExercise = true))
    }

    @Test
    fun `not while something on the wall or a warm-up is still to come`() {
        // The session on the wall is part of the training itself: it comes later, the climbing is not "now" and what
        // follows is not "after climbing".
        val wallLater = sets + set("d1", limit, 3, 0, SetType.WORK, false)
        assertFalse(climbingComesNow(state(wallLater, "b2", "c1"), finishedExercise = true))
        // Done, it is no longer in the way.
        val wallDone = sets + set("d1", limit, 3, 0, SetType.WORK, true)
        assertTrue(climbingComesNow(state(wallDone, "b2", "c1"), finishedExercise = true))
        // A warm-up skipped earlier still waits.
        val warmupOpen = sets.map { if (it.id == "a") it.copy(completedAt = null) else it }
        assertFalse(climbingComesNow(state(warmupOpen, "b2", "c1"), finishedExercise = true))
        // The next exercise is on the wall itself.
        val wallNext = sets.take(3) + set("d1", limit, 2, 0, SetType.WORK, false)
        assertFalse(climbingComesNow(state(wallNext, "b2", "d1"), finishedExercise = true))
    }

    @Test
    fun `only after a warm-up exercise, and only one on the wall`() {
        // The exercise on the wall had work sets: it was climbing, not the warm-up for it.
        val worked = sets.map { if (it.id == "b2") it.copy(setType = SetType.WORK) else it }
        assertFalse(climbingComesNow(state(worked, "b2", "c1"), finishedExercise = true))
        // The warm-up done last was off the wall.
        val offWall = listOf(sets[1].copy(blockIndex = 0), sets[2].copy(blockIndex = 0), sets[0].copy(blockIndex = 1), sets[3], sets[4])
        assertFalse(climbingComesNow(state(offWall, "a", "c1"), finishedExercise = true))
        // Nothing done yet, or nothing to come.
        assertFalse(climbingComesNow(PlayerState(sets = sets, defs = defs, climbingDay = true), finishedExercise = true))
    }
}
