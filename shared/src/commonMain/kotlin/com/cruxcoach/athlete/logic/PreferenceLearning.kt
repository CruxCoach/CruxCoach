package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEvent
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.model.SuggestionFeedback
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the athlete's behaviour says about their preferences, from the
 * recommendation ledger and the logged sets. Every signal fades out over
 * [WINDOW_DAYS], so a dislike from two months ago no longer counts, and the
 * result is clamped so learning can nudge the picks but never dominate the
 * safety rules or the athlete's explicit favourites and exclusions.
 */
object PreferenceLearning {

    const val WINDOW_DAYS = 42
    const val MAX_AFFINITY = 3.0
    private const val DAY_MS = 86_400_000L

    /**
     * Slug → affinity in [-MAX_AFFINITY, MAX_AFFINITY].
     *
     * - "another suggestion" because of an exercise (DISLIKE) −2, skipped set −0.5, excluded −3
     * - swapped away −1, swapped to +1 (SWAPPED slugs = [from, to])
     * - started suggestion +0.3, completed suggestion +0.5 × share done
     * - every training the exercise was logged in +0.3 (repeated → liked)
     */
    fun affinity(events: List<SuggestionEvent>, sets: List<ExerciseSet>, today: LocalDate): Map<String, Double> {
        val todayMs = today.toEpochDays().toLong() * DAY_MS + DAY_MS
        val scores = mutableMapOf<String, Double>()
        fun add(slug: String, value: Double, atMs: Long) {
            val ageDays = ((todayMs - atMs) / DAY_MS).toDouble().coerceAtLeast(0.0)
            val weight = (1.0 - ageDays / WINDOW_DAYS).coerceAtLeast(0.0)
            if (weight > 0) scores[slug] = (scores[slug] ?: 0.0) + value * weight
        }
        for (e in events) when (e.kind) {
            SuggestionEventKind.NEXT, SuggestionEventKind.FEEDBACK ->
                if (e.feedback == SuggestionFeedback.DISLIKE_EXERCISE) e.slugs.forEach { add(it, -2.0, e.createdAt) }
            SuggestionEventKind.SKIPPED_SET -> e.slugs.forEach { add(it, -0.5, e.createdAt) }
            SuggestionEventKind.EXCLUDED -> e.slugs.forEach { add(it, -3.0, e.createdAt) }
            SuggestionEventKind.SWAPPED -> {
                e.slugs.getOrNull(0)?.let { add(it, -1.0, e.createdAt) }
                e.slugs.getOrNull(1)?.let { add(it, 1.0, e.createdAt) }
            }
            SuggestionEventKind.STARTED -> e.slugs.forEach { add(it, 0.3, e.createdAt) }
            SuggestionEventKind.COMPLETED -> e.slugs.forEach { add(it, 0.5 * (e.value ?: 1.0).coerceIn(0.0, 1.0), e.createdAt) }
            SuggestionEventKind.SHOWN, SuggestionEventKind.EDITED, SuggestionEventKind.SAVED -> Unit
        }
        sets.filter { it.isCompleted && it.setType != SetType.WARMUP }
            .groupBy { it.workoutId to it.exerciseSlug }
            .forEach { (key, rows) -> add(key.second, 0.3, rows.maxOf { it.completedAt ?: 0L }) }
        return scores.mapValues { (_, v) -> v.coerceIn(-MAX_AFFINITY, MAX_AFFINITY) }.filterValues { abs(it) >= 0.05 }
    }

    /**
     * The duration the athlete actually trains, when it differs clearly from
     * the preferred one: median of the last finished trainings (at least four
     * in [WINDOW_DAYS]), rounded to 5 minutes; null when the setting fits.
     */
    fun suggestedSessionMinutes(workouts: List<Workout>, current: Int, today: LocalDate? = null): Int? {
        val from = today?.minus(DatePeriod(days = WINDOW_DAYS))?.toString()
        val minutes = workouts.filter { !it.isOpen && (from == null || it.day >= from) }
            .mapNotNull { it.durationMinutes }.filter { it in 5..240 }.sorted()
        if (minutes.size < 4) return null
        val median = minutes[minutes.size / 2]
        val rounded = ((median / 5.0).roundToInt() * 5).coerceIn(15, 120)
        return rounded.takeIf { abs(it - current) >= 10 }
    }

    /** Weekdays (ISO) the athlete trained on in at least three of the last eight weeks. */
    fun actualTrainingWeekdays(activities: Map<String, DayActivity>, today: LocalDate): Set<Int> {
        val from = today.minus(DatePeriod(days = 56))
        return activities.values.filter { it.trained }
            .mapNotNull { runCatching { LocalDate.parse(it.day) }.getOrNull() }
            .filter { it > from && it <= today }
            .groupingBy { it.dayOfWeek.isoDayNumber }.eachCount()
            .filterValues { it >= 3 }.keys
    }
}
