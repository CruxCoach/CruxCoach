package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** One training of one exercise, condensed to what the charts need. */
data class SessionPoint(
    val day: LocalDate,
    val workoutId: String,
    /** Best implied capacity per side (null key = two-handed exercise). */
    val capacity: Map<Side?, Double>,
    /**
     * The same capacity the way the athlete reads it: added (or assisted)
     * kilos for bodyweight-plus work, otherwise the capacity itself.
     */
    val display: Map<Side?, Double>,
    val volume: Double,
    val sets: Int,
    val bodyweightKg: Double?,
    /** Set with the highest implied capacity of this session. */
    val bestSet: ExerciseSet?,
) {
    val bestCapacity: Double? get() = capacity.values.maxOrNull()
    val bestDisplay: Double? get() = display.values.maxOrNull()
}

data class ExerciseProgress(
    val kind: CapacityKind?,
    /** Chronological. */
    val sessions: List<SessionPoint>,
    /** Best display value ever. */
    val best: Double?,
    /** Display value of the latest session. */
    val last: Double?,
    /** Latest display value minus the latest one at least 28 days older; null without such a session. */
    val change28: Double?,
    /** Latest capacity in % body weight (loaded holds and reps only). */
    val percentBodyweight: Double?,
) {
    val sessionCount: Int get() = sessions.size
}

/** Days of one Monday-based week. */
data class WeekAggregate(
    val weekStart: LocalDate,
    val trainingDays: Int,
    val climbingDays: Int,
    val offBoardDays: Int,
    val climbingMinutes: Int,
    val workoutMinutes: Int,
    val sets: Int,
)

/** Loading days per structure in one week. */
data class DomainWeek(val weekStart: LocalDate, val days: Map<LoadDomain, Int>)

/**
 * Pure statistics behind the stats tab and the per-exercise charts. Every
 * value is derived from logged sets and the board logbook; nothing here is
 * stored, so the charts always match the log.
 */
object ProgressStats {

    private const val MINUTES_PER_EFFORT = 4

    fun dayOf(epochMillis: Long, zone: TimeZone): LocalDate =
        kotlin.time.Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date

    /**
     * Value shown for a capacity: bodyweight-plus work in added kilos
     * (negative = assisted) when the body weight is known, else the capacity.
     */
    fun displayValue(def: ExerciseDefinition, kind: CapacityKind?, capacity: Double, bodyweightKg: Double?): Double =
        if (def.load == LoadMode.BODYWEIGHT_PLUS && bodyweightKg != null &&
            (kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.TEN_SECOND_MAX)) capacity - bodyweightKg
        else capacity

    /** Work done in one set, in the unit that makes sense for the exercise kind. */
    fun volume(def: ExerciseDefinition, set: ExerciseSet, bodyweightKg: Double?): Double {
        if (!set.isCompleted || set.setType == SetType.WARMUP) return 0.0
        val bw = set.bodyweightKg ?: bodyweightKg
        fun load(): Double? = StrengthMath.effectiveLoad(def.load, set.loadKg, bw)
        return when (def.kind) {
            ExerciseKind.LOAD_REPS -> (set.reps ?: 0) * (load() ?: set.loadKg ?: 0.0)
            ExerciseKind.REPS, ExerciseKind.CLIMB -> (set.reps ?: 0).toDouble()
            ExerciseKind.TIME -> set.durationS ?: 0.0
            ExerciseKind.HANG -> (set.durationS ?: 0.0) * (load() ?: 1.0)
            ExerciseKind.INTERVAL -> {
                val seconds = (set.workS ?: 0.0) * (set.repsPerSet ?: 1)
                seconds * (load() ?: 1.0)
            }
        }
    }

    /**
     * Sessions of one exercise from its completed sets (any order). Sets of
     * the same workout form one session; sets without a workout id fall back
     * to their calendar day.
     */
    fun exerciseProgress(
        def: ExerciseDefinition,
        sets: List<ExerciseSet>,
        zone: TimeZone,
        fallbackBodyweight: Double?,
    ): ExerciseProgress {
        val kind = BenchmarkMath.capacityKind(def)
        val done = sets.filter { it.isCompleted && it.setType != SetType.WARMUP && it.exerciseSlug == def.slug }
        val sessions = done.groupBy { it.workoutId }.mapNotNull { (workoutId, list) ->
            val first = list.minByOrNull { it.completedAt ?: 0L } ?: return@mapNotNull null
            val day = dayOf(first.completedAt ?: return@mapNotNull null, zone)
            val bw = list.firstNotNullOfOrNull { it.bodyweightKg } ?: fallbackBodyweight
            val implied = list.mapNotNull { set ->
                val withBw = if (set.bodyweightKg == null) set.copy(bodyweightKg = bw) else set
                BenchmarkMath.implied(def, withBw)?.let { set to it.value }
            }
            val capacity = implied.groupBy({ it.first.side }, { it.second }).mapValues { (_, v) -> v.max() }
            SessionPoint(
                day = day,
                workoutId = workoutId,
                capacity = capacity,
                display = capacity.mapValues { (_, v) -> displayValue(def, kind, v, bw) },
                volume = list.sumOf { volume(def, it, bw) },
                sets = list.size,
                bodyweightKg = bw,
                bestSet = implied.maxByOrNull { it.second }?.first,
            )
        }.sortedWith(compareBy({ it.day }, { it.bestSet?.completedAt ?: 0L }))

        val withValue = sessions.filter { it.bestDisplay != null }
        val latest = withValue.lastOrNull()
        val reference = latest?.let { l -> withValue.lastOrNull { it.day <= l.day.minus(DatePeriod(days = 28)) } }
        val percent = latest?.let { l ->
            val cap = l.bestCapacity ?: return@let null
            val bw = l.bodyweightKg?.takeIf { it > 0 } ?: return@let null
            if (kind == CapacityKind.E1RM_TOTAL || kind == CapacityKind.TEN_SECOND_MAX) cap / bw * 100.0 else null
        }
        return ExerciseProgress(
            kind = kind,
            sessions = sessions,
            best = withValue.mapNotNull { it.bestDisplay }.maxOrNull(),
            last = latest?.bestDisplay,
            change28 = if (latest != null && reference != null) latest.bestDisplay!! - reference.bestDisplay!! else null,
            percentBodyweight = percent,
        )
    }

    private fun climbed(a: DayActivity) = a.climbingMinutes > 0 || a.climbingEfforts > 0

    private fun climbingMinutes(a: DayActivity) =
        if (a.climbingMinutes > 0) a.climbingMinutes else a.climbingEfforts * MINUTES_PER_EFFORT

    private fun weekStarts(today: LocalDate, weeks: Int): List<LocalDate> {
        val current = ConsistencyStreak.weekStart(today)
        return (weeks - 1 downTo 0).map { current.minus(DatePeriod(days = it * 7)) }
    }

    /** One aggregate per week for the [weeks] weeks ending with the week of [today]. */
    fun weekly(
        activities: Map<String, DayActivity>,
        setDays: List<LocalDate>,
        today: LocalDate,
        weeks: Int,
    ): List<WeekAggregate> = weekStarts(today, weeks).map { start ->
        val days = (0 until 7).map { start.plus(DatePeriod(days = it)) }
        val acts = days.mapNotNull { activities[it.toString()] }
        WeekAggregate(
            weekStart = start,
            trainingDays = acts.count { it.trained },
            climbingDays = acts.count { climbed(it) },
            offBoardDays = acts.count { it.workoutMinutes > 0 },
            climbingMinutes = acts.sumOf { climbingMinutes(it) },
            workoutMinutes = acts.sumOf { it.workoutMinutes },
            sets = setDays.count { it in days },
        )
    }

    /** 0 = nothing, 1 = light, 2 = training, 3 = hard day — for the calendar heatmap. */
    fun calendar(activities: Map<String, DayActivity>, today: LocalDate, days: Int): Map<LocalDate, Int> =
        (0 until days).associate { back ->
            val day = today.minus(DatePeriod(days = back))
            val activity = activities[day.toString()]
            day to when (FuelTargets.dayLoad(activity)) {
                DayLoad.REST -> 0
                DayLoad.LIGHT -> 1
                DayLoad.MODERATE -> 2
                DayLoad.HARD, DayLoad.VERY_HARD -> 3
            }
        }

    /** Loading days per structure and week: board days load fingers, skin, shoulders and elbows. */
    fun domainWeeks(
        activities: Map<String, DayActivity>,
        sets: List<Pair<LocalDate, ExerciseSet>>,
        catalog: ExerciseCatalog,
        today: LocalDate,
        weeks: Int,
    ): List<DomainWeek> {
        val perDomain = mutableMapOf<LoadDomain, MutableSet<LocalDate>>()
        activities.values.filter { climbed(it) }.forEach { a ->
            val day = runCatching { LocalDate.parse(a.day) }.getOrNull() ?: return@forEach
            LoadBalance.CLIMBING_DOMAINS.forEach { perDomain.getOrPut(it) { mutableSetOf() } += day }
        }
        sets.forEach { (day, set) ->
            if (!set.isCompleted || set.setType == SetType.WARMUP) return@forEach
            catalog.fallbackFor(set.exerciseSlug).domains.forEach { perDomain.getOrPut(it) { mutableSetOf() } += day }
        }
        return weekStarts(today, weeks).map { start ->
            val end = start.plus(DatePeriod(days = 6))
            DomainWeek(start, LoadDomain.entries.associateWith { d -> perDomain[d].orEmpty().count { it >= start && it <= end } })
        }
    }
}
