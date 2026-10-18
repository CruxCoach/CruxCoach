package com.cruxcoach.app.health

/**
 * The phone's health store (HealthKit on iOS), implemented by the host and
 * handed to the core with `AppCore.attachHealthStore`. The iOS counterpart of
 * Android's Jetpack Health Connect client: the core decides what to read and
 * write ([HealthSync]); the host only talks to the store.
 *
 * Type codes (see [HealthTypes]): reading "sleepAnalysis" and "workouts";
 * writing "dietaryEnergyConsumed", "dietaryProtein", "dietaryCarbohydrates",
 * "dietaryFatTotal" and "dietaryWater".
 *
 * Every callback may arrive on any thread and must be called exactly once.
 * Nothing here may throw: a failed call reports "failed" or `ok = false`.
 */
interface HealthStore {
    /** False on a device without the health store (HealthKit missing). */
    fun isAvailable(): Boolean

    /**
     * Shows the system access sheet for the codes. [onDone] gets "done" when the request went through
     * — which says nothing about what was allowed: HealthKit never tells an app whether reading was
     * denied — or "failed" when the store refused the request (missing entitlement, restricted device).
     */
    fun requestAuthorization(read: List<String>, write: List<String>, onDone: (String) -> Unit)

    /**
     * Whether the athlete was already asked for all of [read] and [write]: "asked", "notAsked", or
     * "failed" when the store cannot tell (missing entitlement and the like).
     */
    fun requestStatus(read: List<String>, write: List<String>, onStatus: (String) -> Unit)

    /** Sharing status of all [write] codes: "authorized" when all are, "denied" when any is, else "notDetermined". */
    fun writeStatus(write: List<String>): String

    /** Sleep analysis samples that overlap [fromMs, toMs). */
    fun sleepSamples(fromMs: Long, toMs: Long, onResult: (HealthSleepResult) -> Unit)

    /** Climbing workouts that start in [fromMs, toMs). */
    fun climbingWorkouts(fromMs: Long, toMs: Long, onResult: (HealthWorkoutResult) -> Unit)

    /**
     * Saves [samples] (a sample with a known sync id and a higher sync version replaces the older one) and
     * then deletes the samples this app wrote with one of [deleteSyncIds]. "done" or "failed".
     */
    fun writeNutrition(samples: List<HealthNutritionSample>, deleteSyncIds: List<String>, onDone: (String) -> Unit)
}

/** One sleep analysis sample; [stage] is "inBed", "asleep" (any asleep stage) or "awake". */
class HealthSleepSample(val startMs: Long, val endMs: Long, val stage: String)

class HealthSleepResult(val ok: Boolean, val samples: List<HealthSleepSample>)

/** One climbing workout; [id] is the store's stable id (HKObject uuid), [title] "" when it has none. */
class HealthWorkoutSample(val id: String, val startMs: Long, val endMs: Long, val title: String)

class HealthWorkoutResult(val ok: Boolean, val workouts: List<HealthWorkoutSample>)

/**
 * One quantity to write. [typeCode] is a write code; [value] is in kcal (energy), grams (protein,
 * carbohydrates, fat) or millilitres (water). [syncId] and [version] go into the sync metadata so a
 * rewrite replaces the sample instead of adding a second one. [foodName] is "" for water.
 */
class HealthNutritionSample(
    val syncId: String,
    val version: Long,
    val typeCode: String,
    val value: Double,
    val startMs: Long,
    val endMs: Long,
    val foodName: String,
)

/** The type codes [HealthStore] understands. */
internal object HealthTypes {
    const val SLEEP = "sleepAnalysis"
    const val WORKOUTS = "workouts"
    const val ENERGY = "dietaryEnergyConsumed"
    const val PROTEIN = "dietaryProtein"
    const val CARBS = "dietaryCarbohydrates"
    const val FAT = "dietaryFatTotal"
    const val WATER = "dietaryWater"

    /** Read access for the coach: sleep and workouts (Android: READ_SLEEP, READ_EXERCISE). */
    val READ: List<String> = listOf(SLEEP, WORKOUTS)

    /** Write access for nutrition: meals and water (Android: WRITE_NUTRITION, WRITE_HYDRATION). */
    val WRITE: List<String> = listOf(ENERGY, PROTEIN, CARBS, FAT, WATER)

    const val STAGE_IN_BED = "inBed"
    const val STAGE_ASLEEP = "asleep"
    const val STAGE_AWAKE = "awake"
}
