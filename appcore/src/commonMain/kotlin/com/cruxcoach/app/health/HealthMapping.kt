package com.cruxcoach.app.health

import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayKind
import kotlin.math.roundToInt

/**
 * Which workouts are climbing, and what kind — Android's `HealthConnectMapping`, unchanged. Climbing
 * workouts always count; other types only when the title or notes say climbing. The title decides between
 * gym bouldering, rope, another board and outdoor; without a hint the session stays "other climbing".
 * HealthKit workouts rarely carry a title, so most of them land in "other climbing".
 */
internal object HealthMapping {

    /** Shorter sessions are warm-ups or mis-taps, not climbing days. */
    const val MIN_MINUTES = 10
    const val MAX_MINUTES = 8 * 60

    private val CLIMB_WORDS = listOf("klettern", "kletter", "climb", "boulder", "bouldern", "escalad", "arrampic", "bloc")
    private val BOARD_WORDS = listOf("kilter", "moonboard", "moon board", "tension", "board")
    private val ROPE_WORDS = listOf("seil", "rope", "lead", "vorstieg", "toprope", "top rope", "top-rope", "sport climb")
    private val OUTDOOR_WORDS = listOf("fels", "outdoor", "crag", "draußen", "draussen", "rock ", "gebiet", "font", "trad")
    private val GYM_WORDS = listOf("halle", "gym", "indoor", "boulder")

    /** [climbingType]: the workout's activity type is climbing (Android: EXERCISE_TYPE_ROCK_CLIMBING). */
    fun kindFor(climbingType: Boolean, title: String?, notes: String?): ClimbingDayKind? {
        val text = listOfNotNull(title, notes).joinToString(" ").lowercase()
        if (!climbingType && CLIMB_WORDS.none { it in text }) return null
        return when {
            BOARD_WORDS.any { it in text } -> ClimbingDayKind.OTHER_BOARD
            ROPE_WORDS.any { it in text } && OUTDOOR_WORDS.none { it in text } -> ClimbingDayKind.GYM_ROPE
            OUTDOOR_WORDS.any { it in "$text " } -> ClimbingDayKind.OUTDOOR
            GYM_WORDS.any { it in text } -> ClimbingDayKind.GYM_BOULDER
            else -> ClimbingDayKind.OTHER
        }
    }

    /** Volume unless the athlete wrote otherwise in the title or notes. */
    fun intensityFor(title: String?, notes: String?): ClimbIntensity {
        val text = listOfNotNull(title, notes).joinToString(" ").lowercase()
        return when {
            listOf("limit", "projekt", "project", "max").any { it in text } -> ClimbIntensity.LIMIT
            listOf("hart", "hard", "intensiv", "intense").any { it in text } -> ClimbIntensity.HARD
            listOf("locker", "easy", "leicht", "technik", "technique", "recovery", "erholung").any { it in text } -> ClimbIntensity.LIGHT
            else -> ClimbIntensity.VOLUME
        }
    }
}

/** Merging of overlapping sleep; Android's `SleepMath` on epoch milliseconds. */
internal object SleepMath {

    class Range(val startMs: Long, val endMs: Long)

    class Session(val startMs: Long, val endMs: Long, val awake: List<Range> = emptyList())

    /** Total sleep in hours (one decimal), overlaps counted once; null without sessions. */
    fun hours(sessions: List<Session>): Double? {
        if (sessions.isEmpty()) return null
        return hours(sessions.map { Range(it.startMs, it.endMs) }, sessions.flatMap { it.awake })
    }

    /** The same on loose samples (HealthKit has no sessions): asleep or in-bed spans minus awake spans. */
    fun hours(asleep: List<Range>, awake: List<Range>): Double? {
        if (asleep.isEmpty()) return null
        val sleepRanges = merge(asleep)
        val awakeRanges = merge(awake)
        val asleepMs = sleepRanges.sumOf { it.endMs - it.startMs }
        val awakeMs = awakeRanges.sumOf { w ->
            sleepRanges.sumOf { s ->
                val from = maxOf(w.startMs, s.startMs)
                val to = minOf(w.endMs, s.endMs)
                if (to > from) to - from else 0L
            }
        }
        val hours = (asleepMs - awakeMs).coerceAtLeast(0L) / 3_600_000.0
        return if (hours <= 0.0) null else (hours * 10).roundToInt() / 10.0
    }

    private fun merge(ranges: List<Range>): List<Range> {
        val sorted = ranges.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        val out = mutableListOf<Range>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.startMs <= last.endMs) out[out.size - 1] = Range(last.startMs, maxOf(last.endMs, r.endMs))
            else out += r
        }
        return out
    }
}

/** Last night's sleep as a suggestion for the check-in (Android `SleepHint`). */
internal object SleepHint {

    /**
     * Check-in sleep score (1 = bad … 5 = great) for a night's hours. Capped
     * at 4: hours alone do not make a night "great" — that is the athlete's call.
     */
    fun scoreFor(hours: Double): Int = when {
        hours >= 7.5 -> 4
        hours >= 6.0 -> 3
        hours >= 5.0 -> 2
        else -> 1
    }
}
