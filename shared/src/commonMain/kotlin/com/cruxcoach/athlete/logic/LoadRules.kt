package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadMode

/** What the load number of an exercise refers to; decides its label and how it is shown. */
enum class LoadBasis {
    /** No weight field. */
    NONE,
    /** Body-weight work: everything carried on top of body weight, together; negative = assistance. */
    ADDED,
    /** Two dumbbells at once: the weight of one ("2 × 16 kg"). */
    PER_DUMBBELL,
    /** Two kettlebells at once: the weight of one. */
    PER_KETTLEBELL,
    /** One dumbbell: one-arm work, goblet squat. */
    DUMBBELL,
    /** One kettlebell. */
    KETTLEBELL,
    /** Barbell: the total including the bar. */
    BARBELL,
    /** One-hand pick-up block: everything lifted, block, pin or carabiner and plates. */
    PICKUP,
    /** Two-arm pick-up: both hands together, on one block or two, block and pin included. */
    PICKUP_BOTH_HANDS,
    /** Cable or machine: the weight on the stack in kg or lb, not the plate number. */
    STACK,
    /** The reading of a force gauge ([ExerciseDefinition.TAG_FORCE_GAUGE]). */
    FORCE,
    /** Any other external weight. */
    WEIGHT,
}

/**
 * One rule per case for the weight field of an exercise (issue #58). Stored loads
 * ([com.cruxcoach.athlete.model.ExerciseSet.loadKg], targets, routine items, performance values) are always
 * the number as the athlete enters it:
 *
 * - **Dumbbells and kettlebells** are logged per implement, the number printed on it: two 16 kg dumbbells
 *   are 16, shown as "2 × 16 kg". [ExerciseDefinition.implements] says how many are used at once; totals
 *   (volume, % body weight, the one-rep max estimate, finger and shoulder load) multiply by it.
 * - **Barbells** are the total including the bar.
 * - **Pick-up blocks** are everything lifted: block, pin or carabiner and plates. A two-arm pick-up is both
 *   hands together, whether on one block or two.
 * - **Cable and machine** work is the weight on the stack in kg or lb, not the plate number.
 * - **Body-weight work** logs only what is added, everything together (belt, vest, two dumbbells on a
 *   lunge); 0 is body weight alone, negative is assistance in kg (pulley counterweight, band, feet).
 * - **One-sided exercises** log each side as its own set; reps, time and weight are that side's.
 *
 * Units: stored in kg; lb are only for display and typing.
 */
object LoadRules {

    fun basis(def: ExerciseDefinition): LoadBasis = when (def.load) {
        LoadMode.NONE, LoadMode.BODYWEIGHT -> LoadBasis.NONE
        LoadMode.BODYWEIGHT_PLUS -> LoadBasis.ADDED
        LoadMode.EXTERNAL -> {
            val gear = def.equipment
            val paired = implements(def) > 1
            when {
                def.hasTag(ExerciseDefinition.TAG_FORCE_GAUGE) -> LoadBasis.FORCE
                EquipmentV2.PICKUP_BLOCK_PAIR in gear && !def.unilateral -> LoadBasis.PICKUP_BOTH_HANDS
                EquipmentV2.PICKUP_BLOCK in gear || EquipmentV2.PICKUP_BLOCK_PAIR in gear -> LoadBasis.PICKUP
                EquipmentV2.BARBELL in gear -> LoadBasis.BARBELL
                EquipmentV2.CABLE in gear -> LoadBasis.STACK
                EquipmentV2.DUMBBELL in gear -> if (paired) LoadBasis.PER_DUMBBELL else LoadBasis.DUMBBELL
                EquipmentV2.KETTLEBELL in gear -> if (paired) LoadBasis.PER_KETTLEBELL else LoadBasis.KETTLEBELL
                else -> LoadBasis.WEIGHT
            }
        }
    }

    /** Implements whose weight the logged number is: 2 for paired dumbbells or kettlebells, else 1. */
    fun implements(def: ExerciseDefinition): Int =
        if (pairable(def)) def.implements.coerceIn(1, MAX_IMPLEMENTS) else 1

    /**
     * Free-weight work with dumbbells or kettlebells, which can use one or two of them. Not pick-ups (a
     * kettlebell on a block is the block's load), barbell, cable or force-gauge work.
     */
    fun pairable(def: ExerciseDefinition): Boolean {
        if (def.load != LoadMode.EXTERNAL || def.hasTag(ExerciseDefinition.TAG_FORCE_GAUGE)) return false
        val gear = def.equipment
        if (listOf(EquipmentV2.PICKUP_BLOCK, EquipmentV2.PICKUP_BLOCK_PAIR, EquipmentV2.BARBELL, EquipmentV2.CABLE).any { it in gear }) return false
        return EquipmentV2.DUMBBELL in gear || EquipmentV2.KETTLEBELL in gear
    }

    /** The weight actually lifted for a logged [loadKg] of an external load: both dumbbells together. */
    fun lifted(def: ExerciseDefinition, loadKg: Double): Double = loadKg * implements(def)

    /** A total lifted weight back in the number the athlete logs (the weight of one dumbbell). */
    fun logged(def: ExerciseDefinition, totalKg: Double): Double = totalKg / implements(def)

    const val MAX_IMPLEMENTS = 2
}
