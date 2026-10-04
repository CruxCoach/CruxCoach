package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem

/**
 * Starter routines shipped with the app. Names and descriptions come from
 * string resources keyed by [Routine.builtinKey]; the athlete can start one
 * as-is or save an edited copy as their own routine.
 */
object BuiltinRoutines {

    const val WARMUP_BOARD = "warmup_board"
    const val FINGER_BASICS = "finger_basics"
    const val INJURY_ONE_ARM = "injury_one_arm"
    const val PULL_ANTAGONIST = "pull_antagonist"
    const val ANTAGONIST_15 = "antagonist_15"
    const val LEGS_BASICS = "legs_basics"
    const val CORE_CLIMBER = "core_climber"
    const val MOBILITY_10 = "mobility_10"
    const val BASELINE_TESTS = "baseline_tests"

    val all: List<Routine> = listOf(
        routine(WARMUP_BOARD,
            RoutineItem("warmup.easy_cardio", sets = 1, durationS = 120, restS = 15, warmup = true),
            RoutineItem("warmup.arm_circles", sets = 1, repsMin = 10, repsMax = 15, restS = 15, warmup = true),
            RoutineItem("warmup.band_pull_apart_warmup", sets = 1, repsMin = 12, repsMax = 15, restS = 15, warmup = true),
            RoutineItem("warmup.wrist_circles", sets = 1, repsMin = 10, repsMax = 15, restS = 15, warmup = true),
            RoutineItem("warmup.finger_tendon_glides", sets = 1, repsMin = 6, repsMax = 10, restS = 15, warmup = true),
            RoutineItem("warmup.active_hang_shrugs", sets = 2, repsMin = 6, repsMax = 8, restS = 30, warmup = true),
            RoutineItem("warmup.finger_ramp", sets = 5, durationS = 10, restS = 20, edgeMm = 30, warmup = true),
            RoutineItem("warmup.easy_climbing", sets = 4, restS = 60, warmup = true),
        ),
        routine(FINGER_BASICS,
            RoutineItem("warmup.finger_tendon_glides", sets = 1, repsMin = 6, repsMax = 10, restS = 15, warmup = true),
            RoutineItem("warmup.finger_ramp", sets = 4, durationS = 8, restS = 20, edgeMm = 30, warmup = true),
            RoutineItem("finger.max_hang", sets = 5, durationS = 10, restS = 180, edgeMm = 20),
            RoutineItem("pull.pull_up", sets = 3, repsMin = 4, repsMax = 8, restS = 150),
            RoutineItem("antagonist.reverse_wrist_curl", sets = 2, repsMin = 12, repsMax = 15, restS = 60),
        ),
        // No wall, no two-hand finger load: the healthy hand works on the
        // block, the logger restricts the side from the open injury.
        routine(INJURY_ONE_ARM,
            RoutineItem("warmup.arm_circles", sets = 1, repsMin = 10, repsMax = 15, restS = 15, warmup = true),
            RoutineItem("warmup.wrist_circles", sets = 1, repsMin = 10, repsMax = 15, restS = 15, warmup = true),
            RoutineItem("finger.one_arm_pickup", sets = 5, durationS = 10, restS = 120, edgeMm = 20),
            RoutineItem("pull.pull_up", sets = 4, repsMin = 4, repsMax = 8, restS = 150),
            RoutineItem("pull.dumbbell_row", sets = 3, repsMin = 8, repsMax = 12, restS = 90),
            RoutineItem("antagonist.band_external_rotation", sets = 2, repsMin = 12, repsMax = 20, restS = 45),
            RoutineItem("core.hollow_hold", sets = 3, durationS = 30, restS = 60),
        ),
        routine(PULL_ANTAGONIST,
            RoutineItem("warmup.band_pull_apart_warmup", sets = 1, repsMin = 12, repsMax = 15, restS = 15, warmup = true),
            RoutineItem("pull.pull_up", sets = 4, repsMin = 4, repsMax = 8, restS = 150),
            RoutineItem("pull.inverted_row", sets = 3, repsMin = 8, repsMax = 15, restS = 90),
            RoutineItem("push.push_up", sets = 3, repsMin = 8, repsMax = 15, restS = 90),
            RoutineItem("push.dumbbell_overhead_press", sets = 3, repsMin = 6, repsMax = 10, restS = 120),
            RoutineItem("antagonist.face_pull", sets = 3, repsMin = 12, repsMax = 15, restS = 60),
            RoutineItem("antagonist.band_external_rotation", sets = 2, repsMin = 12, repsMax = 20, restS = 45),
            RoutineItem("core.hollow_hold", sets = 3, durationS = 30, restS = 60),
        ),
        routine(ANTAGONIST_15,
            RoutineItem("antagonist.band_pull_apart", sets = 2, repsMin = 15, repsMax = 20, restS = 30),
            RoutineItem("push.push_up_plus", sets = 2, repsMin = 10, repsMax = 15, restS = 45),
            RoutineItem("antagonist.band_external_rotation", sets = 2, repsMin = 12, repsMax = 20, restS = 30),
            RoutineItem("antagonist.reverse_wrist_curl", sets = 2, repsMin = 12, repsMax = 15, restS = 45),
            RoutineItem("antagonist.finger_extensor_band", sets = 2, repsMin = 20, repsMax = 30, restS = 30),
            RoutineItem("push.push_up", sets = 2, repsMin = 8, repsMax = 15, restS = 60),
        ),
        routine(LEGS_BASICS,
            RoutineItem("warmup.leg_swings", sets = 1, repsMin = 10, repsMax = 15, restS = 15, warmup = true),
            RoutineItem("legs.goblet_squat", sets = 3, repsMin = 8, repsMax = 12, restS = 90),
            RoutineItem("legs.romanian_deadlift", sets = 3, repsMin = 8, repsMax = 12, restS = 90),
            RoutineItem("legs.bulgarian_split_squat", sets = 3, repsMin = 6, repsMax = 10, restS = 90),
            RoutineItem("legs.nordic_curl_assisted", sets = 3, repsMin = 3, repsMax = 6, restS = 120),
            RoutineItem("legs.copenhagen_plank_short", sets = 2, durationS = 20, restS = 60),
            RoutineItem("legs.calf_raise", sets = 2, repsMin = 12, repsMax = 20, restS = 45),
        ),
        routine(CORE_CLIMBER,
            RoutineItem("core.dead_bug", sets = 2, repsMin = 8, repsMax = 12, restS = 45),
            RoutineItem("core.hollow_hold", sets = 3, durationS = 30, restS = 60),
            RoutineItem("core.hanging_knee_raise", sets = 3, repsMin = 8, repsMax = 12, restS = 90),
            RoutineItem("core.side_plank", sets = 2, durationS = 30, restS = 45),
            RoutineItem("core.pallof_press", sets = 2, repsMin = 8, repsMax = 12, restS = 45),
            RoutineItem("core.tuck_l_sit", sets = 3, durationS = 10, restS = 60),
        ),
        routine(MOBILITY_10,
            RoutineItem("mobility.frog_stretch", sets = 1, durationS = 60, restS = 15),
            RoutineItem("mobility.hip_90_90_switch", sets = 1, repsMin = 6, repsMax = 10, restS = 15),
            RoutineItem("mobility.pigeon_stretch", sets = 1, durationS = 45, restS = 15),
            RoutineItem("mobility.open_book_rotation", sets = 1, repsMin = 8, repsMax = 10, restS = 15),
            RoutineItem("mobility.shoulder_dislocate", sets = 1, repsMin = 8, repsMax = 12, restS = 15),
            RoutineItem("mobility.wrist_flexor_stretch", sets = 1, durationS = 30, restS = 15),
        ),
        // Day-one baseline (MCI intro week, climber version): strength to
        // weight on the board, pulling strength, trunk tension.
        routine(BASELINE_TESTS,
            RoutineItem("warmup.finger_ramp", sets = 5, durationS = 8, restS = 20, edgeMm = 30, warmup = true),
            RoutineItem("finger.max_hang", sets = 3, durationS = 10, restS = 180, edgeMm = 20, test = true),
            RoutineItem("pull.pull_up", sets = 1, repsMin = 1, repsMax = 30, restS = 180, test = true),
            RoutineItem("core.hollow_hold", sets = 1, durationS = 60, restS = 60, test = true),
        ),
    )

    fun byKey(key: String): Routine? = all.firstOrNull { it.builtinKey == key }

    private fun routine(key: String, vararg items: RoutineItem) =
        Routine(id = "builtin:$key", name = key, items = items.toList(), builtinKey = key)
}
