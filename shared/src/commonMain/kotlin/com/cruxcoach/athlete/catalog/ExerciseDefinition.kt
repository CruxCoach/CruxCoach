package com.cruxcoach.athlete.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One exercise of the packaged catalogue (FEAT-066) or a user-created one.
 *
 * The slug is the identity everything else refers to — logged sets, routines,
 * favourites — so a catalogue update can rename, reword or retire an exercise
 * without orphaning history. Retired slugs stay resolvable through
 * [ExerciseCatalog.fallbackFor].
 */
@Serializable
data class ExerciseDefinition(
    val slug: String,
    val category: ExerciseCategoryV2,
    val kind: ExerciseKind,
    val load: LoadMode,
    val unilateral: Boolean = false,
    val equipment: List<EquipmentV2> = emptyList(),
    val domains: List<LoadDomain> = emptyList(),
    val muscles: Muscles = Muscles(),
    val difficulty: Int = 1,
    val easier: String? = null,
    val harder: String? = null,
    val defaults: Prescription = Prescription(),
    val contraindications: List<BodyRegion> = emptyList(),
    val tags: List<String> = emptyList(),
    val i18n: Map<String, ExerciseText> = emptyMap(),
    /** True for exercises the user created; never part of the packaged catalogue. */
    val custom: Boolean = false,
) {
    fun text(language: String): ExerciseText =
        i18n[language] ?: i18n["en"] ?: i18n.values.firstOrNull() ?: ExerciseText(name = slug)

    fun name(language: String): String = text(language).name

    val needsClimbingWall: Boolean
        get() = kind == ExerciseKind.CLIMB ||
            equipment.any { it == EquipmentV2.WALL || it == EquipmentV2.BOARD || it == EquipmentV2.CAMPUS_BOARD }

    val fingerFree: Boolean get() = TAG_FINGER_FREE in tags || LoadDomain.FINGER !in domains

    fun hasTag(tag: String): Boolean = tag in tags

    companion object {
        const val TAG_FINGER_FREE = "finger_free"
        const val TAG_NO_CLIMBING = "no_climbing"
        const val TAG_WARMUP = "warmup"
        const val TAG_ANTAGONIST = "antagonist"
        const val TAG_PREHAB = "prehab"
        const val TAG_HOME = "home"
    }
}

@Serializable
data class Muscles(
    val primary: List<String> = emptyList(),
    val secondary: List<String> = emptyList(),
)

@Serializable
data class ExerciseText(
    val name: String,
    val aliases: List<String> = emptyList(),
    val why: String = "",
    val steps: List<String> = emptyList(),
    val cues: List<String> = emptyList(),
    val mistakes: List<String> = emptyList(),
)

/** Starting prescription; each field only applies to some [ExerciseKind]s. */
@Serializable
data class Prescription(
    val sets: Int? = null,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val durationS: Int? = null,
    val restS: Int? = null,
    val workS: Int? = null,
    val restBetweenS: Int? = null,
    val repsPerSet: Int? = null,
    val edgeMm: Int? = null,
    val rounds: Int? = null,
)

@Serializable
enum class ExerciseCategoryV2 {
    FINGER, PULL, PUSH, ANTAGONIST, CORE, LEGS, MOBILITY, WARMUP, POWER, ENDURANCE, TECHNIQUE
}

/** Decides which inputs the logger shows and which timer drives a set. */
@Serializable
enum class ExerciseKind {
    /** Bodyweight repetitions. */
    REPS,
    /** Repetitions with a load (added/assisted or external). */
    LOAD_REPS,
    /** A timed hold or stretch. */
    TIME,
    /** Isometric finger/hang or pick-up effort: duration, load, edge, grip. */
    HANG,
    /** Work/rest repeats inside a set, e.g. 7 s on / 3 s off × 6. */
    INTERVAL,
    /** Drills on a wall or board, logged as rounds/duration. */
    CLIMB,
}

/** How the load field is read. */
@Serializable
enum class LoadMode {
    NONE,
    BODYWEIGHT,
    /** Bodyweight plus added kg; negative values mean assistance (pulley, band, feet). */
    BODYWEIGHT_PLUS,
    /** Absolute lifted kg (pick-up blocks, dumbbells, barbells). */
    EXTERNAL,
}

@Serializable
enum class EquipmentV2 {
    NONE, MAT, HANGBOARD, PICKUP_BLOCK, PULL_UP_BAR, RINGS, DUMBBELL, KETTLEBELL, BARBELL,
    PLATES, BANDS, BENCH, BOX, CABLE, WALL, BOARD, CAMPUS_BOARD, FOAM_ROLLER, PULLEY, DIP_BARS;

    companion object {
        /**
         * Gear that can stand in for a required piece: a kettlebell loads a
         * pick-up block as well as plates do, bands replace a cable, rings
         * make dip bars. Keeps the "what do you own" filter from hiding
         * exercises over a technicality.
         */
        val SUBSTITUTES: Map<EquipmentV2, Set<EquipmentV2>> = mapOf(
            PLATES to setOf(KETTLEBELL, DUMBBELL, BARBELL),
            DUMBBELL to setOf(KETTLEBELL),
            KETTLEBELL to setOf(DUMBBELL),
            CABLE to setOf(BANDS),
            DIP_BARS to setOf(RINGS),
            PULL_UP_BAR to setOf(HANGBOARD, RINGS),
            BOARD to setOf(WALL),
        )

        fun satisfied(required: EquipmentV2, owned: Set<EquipmentV2>): Boolean =
            required == NONE || required == MAT || required in owned ||
                SUBSTITUTES[required].orEmpty().any { it in owned }
    }
}

/** Structures an exercise puts load on — the language of injuries and weekly load. */
@Serializable
enum class LoadDomain { FINGER, WRIST, ELBOW, SHOULDER, SKIN, LOWER_BODY, BACK, SYSTEMIC }

/** Body regions for contraindications; lower-case on the wire like the catalogue file. */
@Serializable
enum class BodyRegion {
    @SerialName("finger") FINGER,
    @SerialName("pulley") PULLEY,
    @SerialName("wrist") WRIST,
    @SerialName("elbow") ELBOW,
    @SerialName("shoulder") SHOULDER,
    @SerialName("back") BACK,
    @SerialName("hip") HIP,
    @SerialName("knee") KNEE,
    @SerialName("ankle") ANKLE;

    companion object {
        fun fromKey(key: String): BodyRegion? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}
