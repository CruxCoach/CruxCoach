package com.cruxcoach.athlete.model

import com.cruxcoach.athlete.catalog.BodyRegion
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.LoadDomain
import kotlinx.serialization.Serializable

// ── Training ─────────────────────────────────────────────────────────

@Serializable
enum class SetType { WARMUP, WORK, TEST }

@Serializable
enum class Side { LEFT, RIGHT;
    val opposite: Side get() = if (this == LEFT) RIGHT else LEFT
}

@Serializable
enum class Grip { HALF_CRIMP, OPEN_HAND, FULL_CRIMP, THREE_FINGER_DRAG, FRONT_TWO, MIDDLE_TWO, PINCH, SLOPER, JUG }

@Serializable
data class Workout(
    val id: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val day: String,
    val title: String? = null,
    val routineId: String? = null,
    val sessionRpe: Int? = null,
    val notes: String? = null,
    val updatedAt: Long,
) {
    val isOpen: Boolean get() = endedAt == null
    val durationMinutes: Int? get() = endedAt?.let { ((it - startedAt) / 60_000L).toInt().coerceAtLeast(0) }
}

@Serializable
data class ExerciseSet(
    val id: String,
    val workoutId: String,
    val exerciseSlug: String,
    val blockIndex: Int,
    val setIndex: Int,
    val setType: SetType = SetType.WORK,
    val side: Side? = null,
    val targetReps: Int? = null,
    val targetDurationS: Double? = null,
    val targetLoadKg: Double? = null,
    val reps: Int? = null,
    val durationS: Double? = null,
    val loadKg: Double? = null,
    val edgeMm: Double? = null,
    val grip: Grip? = null,
    val rir: Int? = null,
    val workS: Double? = null,
    val restBetweenS: Double? = null,
    val repsPerSet: Int? = null,
    val restS: Int? = null,
    /** Body weight at the time of the set, so relative load stays right as weight changes. */
    val bodyweightKg: Double? = null,
    val completedAt: Long? = null,
    val note: String? = null,
) {
    val isCompleted: Boolean get() = completedAt != null
}

@Serializable
enum class SideMode { BOTH, LEFT_ONLY, RIGHT_ONLY }

/** One exercise in a routine template. */
@Serializable
data class RoutineItem(
    val slug: String,
    val sets: Int = 3,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val durationS: Int? = null,
    val loadKg: Double? = null,
    val restS: Int? = null,
    val edgeMm: Int? = null,
    val grip: Grip? = null,
    val workS: Int? = null,
    val restBetweenS: Int? = null,
    val repsPerSet: Int? = null,
    val sides: SideMode = SideMode.BOTH,
    val warmup: Boolean = false,
    /** Benchmark item: its sets are logged as [SetType.TEST]. */
    val test: Boolean = false,
)

@Serializable
data class Routine(
    val id: String,
    val name: String,
    val notes: String? = null,
    val items: List<RoutineItem>,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Non-null for packaged starter routines; their texts come from resources. */
    val builtinKey: String? = null,
)

@Serializable
/** ESTIMATE: a quick self-estimate from the coach setup; the first logged set replaces it either way. */
enum class BenchmarkSource { MANUAL, TEST, AUTO, ESTIMATE }

/**
 * What the athlete can do in one exercise: "+20 kg × 5", "32 kg for 10 s on
 * 20 mm, right hand", "12 reps", "45 s". Prescriptions are derived from the
 * newest value per side, edge and grip.
 */
@Serializable
data class Benchmark(
    val id: String,
    val exerciseSlug: String,
    val side: Side? = null,
    val edgeMm: Double? = null,
    val grip: Grip? = null,
    val loadKg: Double? = null,
    val reps: Int? = null,
    val durationS: Double? = null,
    val bodyweightKg: Double? = null,
    val source: BenchmarkSource = BenchmarkSource.MANUAL,
    val measuredAt: Long,
    val note: String? = null,
)

// ── Body ─────────────────────────────────────────────────────────────

@Serializable
data class BodyMeasurement(
    val day: String,
    val metric: String,
    val value: Double,
    val unit: String,
    val measuredAt: Long,
    val source: String = SOURCE_MANUAL,
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_LEGACY = "legacy_body_stats"
        const val SOURCE_IMPORT = "import"
    }
}

/** Metrics with first-class UI. Unknown keys (legacy imports) are kept as-is. */
enum class BodyMetric(val key: String, val unit: String, val group: MetricGroup) {
    WEIGHT("weight", "kg", MetricGroup.WEIGHT),
    BODY_FAT("body_fat", "%", MetricGroup.COMPOSITION),
    HEIGHT("height", "cm", MetricGroup.ANTHROPOMETRY),
    ARM_SPAN("arm_span", "cm", MetricGroup.ANTHROPOMETRY),
    FOREARM("forearm", "cm", MetricGroup.CIRCUMFERENCE),
    UPPER_ARM("upper_arm", "cm", MetricGroup.CIRCUMFERENCE),
    CHEST("chest", "cm", MetricGroup.CIRCUMFERENCE),
    WAIST("waist", "cm", MetricGroup.CIRCUMFERENCE),
    HIPS("hips", "cm", MetricGroup.CIRCUMFERENCE),
    THIGH("thigh", "cm", MetricGroup.CIRCUMFERENCE),
    CALF("calf", "cm", MetricGroup.CIRCUMFERENCE),
    NECK("neck", "cm", MetricGroup.CIRCUMFERENCE);

    companion object {
        fun fromKey(key: String): BodyMetric? = entries.firstOrNull { it.key == key }

        /** Map a 0.1–0.2.3 `body_stats.stat_name` onto the new metric keys. */
        fun legacyKey(statName: String): String = when (statName) {
            "body fat" -> BODY_FAT.key
            "forearm_circumference" -> FOREARM.key
            else -> statName
        }
    }
}

enum class MetricGroup { WEIGHT, COMPOSITION, ANTHROPOMETRY, CIRCUMFERENCE }

// ── Fueling ──────────────────────────────────────────────────────────

@Serializable
enum class Meal { BREAKFAST, LUNCH, DINNER, SNACK, PRE_TRAINING, POST_TRAINING }

@Serializable
data class FoodItem(
    val id: String,
    val name: String,
    val brand: String? = null,
    val barcode: String? = null,
    val kcalPer100: Double? = null,
    val proteinPer100: Double? = null,
    val carbsPer100: Double? = null,
    val fatPer100: Double? = null,
    val servingG: Double? = null,
    val servingLabel: String? = null,
    val favorite: Boolean = false,
    val source: String = "user",
    val useCount: Int = 0,
    val lastUsedAt: Long? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class FoodLogEntry(
    val id: String,
    val day: String,
    val loggedAt: Long,
    val meal: Meal,
    val foodItemId: String? = null,
    val name: String,
    val amountG: Double? = null,
    val portions: Double? = null,
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
)

@Serializable
data class HydrationEntry(val id: String, val day: String, val loggedAt: Long, val ml: Int)

// ── Wellbeing ────────────────────────────────────────────────────────

/** 1 (bad) … 5 (great); null = not answered. Fingers: 5 = fresh, 1 = wrecked. */
@Serializable
data class Checkin(
    val day: String,
    val createdAt: Long,
    val sleep: Int? = null,
    val energy: Int? = null,
    val skin: Int? = null,
    val fingers: Int? = null,
    val motivation: Int? = null,
    val sick: Boolean = false,
    val note: String? = null,
)

@Serializable
enum class InjuryRegion(val bodyRegions: Set<BodyRegion>, val domains: Set<LoadDomain>, val limb: Boolean) {
    FINGER(setOf(BodyRegion.FINGER, BodyRegion.PULLEY), setOf(LoadDomain.FINGER), true),
    WRIST(setOf(BodyRegion.WRIST), setOf(LoadDomain.WRIST), true),
    ELBOW(setOf(BodyRegion.ELBOW), setOf(LoadDomain.ELBOW), true),
    SHOULDER(setOf(BodyRegion.SHOULDER), setOf(LoadDomain.SHOULDER), true),
    BACK(setOf(BodyRegion.BACK), setOf(LoadDomain.BACK), false),
    HIP(setOf(BodyRegion.HIP), setOf(LoadDomain.LOWER_BODY), true),
    KNEE(setOf(BodyRegion.KNEE), setOf(LoadDomain.LOWER_BODY), true),
    ANKLE(setOf(BodyRegion.ANKLE), setOf(LoadDomain.LOWER_BODY), true),
    SKIN(emptySet(), setOf(LoadDomain.SKIN), true),
    OTHER(emptySet(), emptySet(), false),
}

@Serializable
enum class InjurySide { LEFT, RIGHT, BOTH }

@Serializable
data class Injury(
    val id: String,
    val region: InjuryRegion,
    val side: InjurySide? = null,
    val detail: String? = null,
    /** Pain 0–9 as reported by the athlete. */
    val severity: Int,
    val climbingPaused: Boolean = true,
    val startedOn: String,
    val resolvedOn: String? = null,
    val note: String? = null,
    val updatedAt: Long = 0,
) {
    val isActive: Boolean get() = resolvedOn == null
}

@Serializable
enum class PauseReason { ILLNESS, INJURY, HOLIDAY }

@Serializable
data class PausePeriod(
    val id: String,
    val reason: PauseReason,
    val startDay: String,
    val endDay: String? = null,
    val note: String? = null,
)

// ── Profile / settings ───────────────────────────────────────────────

const val PLAN_BOARD = "board"
const val PLAN_REST = "rest"

@Serializable
enum class UnitSystem { METRIC, IMPERIAL }

@Serializable
enum class Sex { FEMALE, MALE, OTHER }

@Serializable
enum class AthleteGoal { PERFORM, MAINTAIN, BUILD_STRENGTH, LOSE_WEIGHT }

/**
 * Everything the athlete can configure. Stored as one JSON value so new
 * fields only need a default — no schema change, and backups stay readable.
 */
@Serializable
data class AthleteProfile(
    val units: UnitSystem = UnitSystem.METRIC,
    val sex: Sex? = null,
    val birthYear: Int? = null,
    val equipment: Set<EquipmentV2> = setOf(EquipmentV2.NONE, EquipmentV2.MAT),
    val equipmentConfigured: Boolean = false,
    val smallestIncrementKg: Double = 1.0,
    val weeklyGoal: Int = 3,
    val goal: AthleteGoal = AthleteGoal.PERFORM,
    val bodyEnabled: Boolean = true,
    val fuelEnabled: Boolean = false,
    val fuelIntroAccepted: Boolean = false,
    val hideBodyNumbers: Boolean = false,
    val showCalories: Boolean = false,
    val proteinPerKg: Double = 1.6,
    val timerSound: Boolean = true,
    val timerVibration: Boolean = true,
    val timerVoice: Boolean = false,
    val autoRestTimer: Boolean = true,
    val checkinEnabled: Boolean = true,
    val legacyBodyStatsImported: Boolean = false,
    /**
     * Standard week: ISO weekday (1 = Monday … 7 = Sunday) → what is planned:
     * a routine id ("builtin:<key>" for starter routines), [PLAN_BOARD] for a
     * board session or [PLAN_REST]. Days without an entry are free.
     */
    val weekPlan: Map<Int, String> = emptyMap(),
    /** Preferred length of a suggested training in minutes. */
    val sessionMinutes: Int = 45,
    /** Optional weigh-in reminder: ISO weekdays (1 = Monday … 7 = Sunday) at a time of day. */
    val weighReminderEnabled: Boolean = false,
    val weighReminderDays: Set<Int> = setOf(1),
    val weighReminderMinutes: Int = 7 * 60 + 30,
    /** Optional monthly measuring round (circumferences) on a day of the month, same time of day. */
    val measureReminderEnabled: Boolean = false,
    val measureReminderDayOfMonth: Int = 1,
    /** Answers of the optional coach setup (FEAT-071); every field may stay unanswered. */
    val coach: CoachProfile = CoachProfile(),
    /** Exercises the athlete never wants suggested ("nie vorschlagen"); the counterpart of favourites. */
    val excludedExercises: Set<String> = emptySet(),
)

// ── Coach profile (FEAT-071) ─────────────────────────────────────────

@Serializable
enum class CoachGoal { CLIMB_HARDER, PROJECT, BUILD_STRENGTH, STAY_HEALTHY, COMEBACK, EVENT }

@Serializable
enum class FocusArea { FINGER_STRENGTH, PULL_STRENGTH, POWER, POWER_ENDURANCE, CORE, MOBILITY, PREVENTION }

@Serializable
enum class FingerPreference { HANGBOARD, PICKUP, ONE_ARM, NONE }

@Serializable
enum class ClimbingContext { HOME_BOARD, GYM_BOARD, GYM_BOULDER, ROPE, OUTDOOR }

@Serializable
enum class ExperienceBand { UNDER_1, Y1_2, Y3_5, OVER_5 }

@Serializable
enum class AgeBand { UNDER_16, Y16_17, Y18_39, Y40_54, Y55_PLUS }

@Serializable
enum class SetupState { NOT_STARTED, IN_PROGRESS, DONE, DISMISSED }

/**
 * What the athlete told the coach. Missing answers stay null/empty — the
 * engine then falls back to conservative defaults and says so; nothing is
 * silently filled with an average. Stored locally inside the profile JSON.
 */
@Serializable
data class CoachProfile(
    val goal: CoachGoal? = null,
    /** Font boulder grade the athlete aims for, e.g. "7A". */
    val targetGrade: String? = null,
    /** A project from the logbook. */
    val targetClimbUuid: String? = null,
    val targetClimbName: String? = null,
    val targetClimbAngle: Int? = null,
    /** ISO date of a trip or competition (drives taper). */
    val targetDate: String? = null,
    /** ISO weekdays the athlete usually climbs on. */
    val climbingDays: Set<Int> = emptySet(),
    /** All training days per week, climbing included. */
    val trainingDaysPerWeek: Int? = null,
    /** 10–15 min antagonists/core after climbing. */
    val addOnAfterClimbing: Boolean? = null,
    val contexts: Set<ClimbingContext> = emptySet(),
    val experience: ExperienceBand? = null,
    val ageBand: AgeBand? = null,
    /** Confirmed boulder grades (Font); prefilled from the logbook. */
    val currentGrade: String? = null,
    val currentFlashGrade: String? = null,
    /** Rope grade, free text in French notation. */
    val ropeGrade: String? = null,
    val focus: Set<FocusArea> = emptySet(),
    val fingerPreference: FingerPreference? = null,
    /** 0 = short and intense … 100 = longer and calmer; null = no preference. */
    val intensityStyle: Int? = null,
    /** 0 = fixed routine … 100 = lots of variety; null = no preference. */
    val variety: Int? = null,
    val setupState: SetupState = SetupState.NOT_STARTED,
    val setupUpdatedAt: Long = 0,
    /** First day of the current training block (periodisation). */
    val blockStartDay: String? = null,
    val healthConnectEnabled: Boolean = false,
    val forceGaugeEnabled: Boolean = false,
)

/** How hard a climbing day was, relative to the athlete's own level. */
@Serializable
enum class ClimbIntensity { LIGHT, VOLUME, HARD, LIMIT }

@Serializable
enum class ClimbingDayKind { GYM_BOULDER, GYM_ROPE, OUTDOOR, OTHER_BOARD, OTHER }

@Serializable
enum class ClimbingDaySource { MANUAL, HEALTH_CONNECT }

/** Climbing outside the board app (gym, rock, another board), logged by hand or from Health Connect. */
@Serializable
data class ClimbingDayEntry(
    val id: String,
    val day: String,
    val kind: ClimbingDayKind,
    val minutes: Int,
    val intensity: ClimbIntensity,
    val source: ClimbingDaySource = ClimbingDaySource.MANUAL,
    /** Health Connect record id, so a re-sync never duplicates. */
    val externalId: String? = null,
    val note: String? = null,
    val updatedAt: Long = 0,
)

/** What happened to a recommendation — the ledger the coach learns from. */
@Serializable
enum class SuggestionEventKind { SHOWN, STARTED, EDITED, SAVED, NEXT, COMPLETED, EXCLUDED, SWAPPED, SKIPPED_SET, FEEDBACK }

/** Why the athlete asked for another suggestion (optional chip). */
@Serializable
enum class SuggestionFeedback { NO_TIME, DISLIKE_EXERCISE, MISSING_EQUIPMENT, HURTS, TOO_EASY, TOO_HARD, OTHER }

@Serializable
data class SuggestionEvent(
    val id: String,
    val day: String,
    val createdAt: Long,
    val kind: SuggestionEventKind,
    val focus: String? = null,
    /** Exercise slugs the event is about. */
    val slugs: List<String> = emptyList(),
    val feedback: SuggestionFeedback? = null,
    val workoutId: String? = null,
    /** Kind-specific number: completion share 0–1, minutes, … */
    val value: Double? = null,
)
