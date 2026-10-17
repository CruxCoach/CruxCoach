package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.domain.playlist.GeneratorType
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Today's planned training for the nutrition targets (holistic audit W4, P1-4). Carbohydrate and energy
 * follow the day's load, but only once a training was logged: the morning before a board session showed a
 * rest day. Today's planned training now stands in until it is done:
 *
 * - a board day: the board plan's minutes, as hard as its generator type (volume → volume, else hard);
 * - off the wall: the estimated minutes at session RPE [OFF_WALL_RPE].
 *
 * Planned means the week plan, a usual climbing day or what the athlete chose for today – not the coach's
 * offer on a free day. It counts until it is done (climbing logged for a board day, a finished training
 * otherwise) and only while it weighs more than what is done. Earlier days and the energy check keep the
 * real data.
 */
object PlannedTraining {

    /** Setting with today's plan, written where today's suggestion is computed (Today). */
    const val SETTING_KEY = "plan.today"
    const val OFF_WALL_RPE = 6
    /** A planned board day before Today has computed its board plan: the build-week session. */
    const val BOARD_DEFAULT_MINUTES = 60

    enum class Kind { BOARD, OFF_WALL }

    /** Today's plan as stored; [kind] null = nothing planned (a rest day, or only a free day's offer). */
    @Serializable
    data class Summary(val day: String, val kind: Kind? = null, val minutes: Int = 0, val boardType: String? = null)

    /** The planned training as the targets see it. */
    data class Planned(val kind: Kind, val minutes: Int, val activity: DayActivity)

    /** Reasons that make a suggestion a plan rather than an offer. */
    private val PLAN_REASONS = setOf(
        SuggestionReason.WEEK_PLAN, SuggestionReason.WEEK_PLAN_BOARD, SuggestionReason.CLIMBING_DAY,
        SuggestionReason.CHOSEN_TODAY, SuggestionReason.USUAL_KIND, SuggestionReason.MORE_TIME_TODAY,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** What of today's suggestion [s] is planned. */
    fun summaryOf(s: SessionSuggestion?, day: LocalDate): Summary {
        val none = Summary(day.toString())
        if (s == null || s.focus == SuggestionFocus.REST) return none
        val planned = (s.plannedEntry != null && s.plannedEntry != PLAN_REST) || s.reasons.any { it in PLAN_REASONS }
        if (!planned) return none
        val board = s.boardPlan
        return when {
            board != null -> Summary(day.toString(), Kind.BOARD, board.minutes, board.type.name)
            s.estimatedMinutes > 0 -> Summary(day.toString(), Kind.OFF_WALL, s.estimatedMinutes)
            else -> none
        }
    }

    /** Today's plan from the week plan and the usual climbing days alone, before Today computed its suggestion. */
    fun fromWeekPlan(profile: AthleteProfile, day: LocalDate, routines: List<Routine>, catalog: ExerciseCatalog): Summary {
        val weekday = day.dayOfWeek.isoDayNumber
        val entry = profile.weekPlan[weekday]
        val none = Summary(day.toString())
        return when {
            entry == PLAN_REST -> none
            entry == PLAN_BOARD || (entry == null && weekday in profile.coach.climbingDays) ->
                Summary(day.toString(), Kind.BOARD, BOARD_DEFAULT_MINUTES)
            entry != null -> {
                val routine = if (entry.startsWith("builtin:")) BuiltinRoutines.byKey(entry.removePrefix("builtin:"))
                    else routines.firstOrNull { it.id == entry }
                val minutes = routine?.let { SessionSuggester.estimateMinutes(it.items, catalog) } ?: 0
                if (minutes > 0) Summary(day.toString(), Kind.OFF_WALL, minutes) else none
            }
            else -> none
        }
    }

    /** The day [summary] plans, as a [DayActivity]; null when nothing is planned. */
    fun planned(summary: Summary?): Planned? {
        val kind = summary?.kind ?: return null
        val minutes = summary.minutes.takeIf { it > 0 } ?: return null
        val activity = when (kind) {
            Kind.BOARD -> DayActivity(summary.day, climbingMinutes = minutes, climbIntensity = boardIntensity(summary.boardType))
            Kind.OFF_WALL -> DayActivity(summary.day, workoutMinutes = minutes, workoutLoad = minutes * OFF_WALL_RPE)
        }
        return Planned(kind, minutes, activity)
    }

    /** Limit, pyramid, projects and power endurance are hard board sessions; volume is volume. */
    fun boardIntensity(type: String?): ClimbIntensity =
        if (type == GeneratorType.VOLUME.name) ClimbIntensity.VOLUME else ClimbIntensity.HARD

    /** Done: climbing logged on a board day, a finished training otherwise. */
    fun done(kind: Kind, real: DayActivity?): Boolean = real != null && when (kind) {
        Kind.BOARD -> real.climbingMinutes > 0 || real.climbingEfforts > 0
        Kind.OFF_WALL -> real.workoutMinutes > 0
    }

    /** [planned] when today's targets follow it: not done yet and weighing more than what is done ([real]). */
    fun forTargets(real: DayActivity?, planned: Planned?): Planned? {
        val p = planned ?: return null
        if (done(p.kind, real)) return null
        return p.takeIf { load(it.activity) > load(real) }
    }

    /** Session load of a day as [FuelTargets.dayLoad] weighs it (minutes × RPE). */
    fun load(a: DayActivity?): Int {
        if (a == null || !a.trained) return 0
        return FuelTargets.climbingMinutes(a) * FuelTargets.climbRpe(a.climbIntensity) + a.workoutLoad
    }

    fun encode(s: Summary): String = json.encodeToString(Summary.serializer(), s)

    fun decode(value: String?): Summary? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString(Summary.serializer(), it) }.getOrNull() }
}
