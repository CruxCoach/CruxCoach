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
import kotlin.math.max
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

    // ── Forgetting (owner 2026-10-16: what is learned stays visible and can be reset) ──

    /** Key of [com.cruxcoach.athlete.model.AthleteProfile.learningResets] that forgets everything learned before its time. */
    const val RESET_ALL = "all"
    /** Forgets what was learned about one exercise. */
    fun exerciseKey(slug: String) = "ex:$slug"
    /** Forgets the usual kind and duration of one ISO weekday. */
    fun weekdayKey(isoDay: Int) = "day:$isoDay"

    /** Signals at or before this time no longer count for [key]. */
    fun since(resets: Map<String, Long>, key: String): Long = max(resets[RESET_ALL] ?: 0L, resets[key] ?: 0L)

    // ── The athlete's choices for a day ("another suggestion", the time on the card) ──

    /** Ledger [SuggestionEventKind.FEEDBACK] focus for a kind chosen today: `chosen_kind:<SuggestionFocus>`, empty = the coach's choice. */
    const val CHOSEN_KIND = "chosen_kind:"
    /** Ledger [SuggestionEventKind.FEEDBACK] focus for a duration chosen today; value = minutes, null = back to the usual. */
    const val CHOSEN_MINUTES = "chosen_minutes"

    /** What the athlete chose again and again on one weekday: a kind of training and/or a duration. */
    data class UsualChoice(val isoDay: Int, val focus: SuggestionFocus?, val minutes: Int?)

    /**
     * The kind and duration the athlete usually chooses per ISO weekday (owner 2026-10-16: "learn
     * the preferences when the athlete deviates"): a choice counts on days the athlete then trained
     * (a started training that day), the last choice of a day wins, and a weekday's choice is usual
     * once it was made on two such days within [WINDOW_DAYS] and is also the most recent one there.
     * Today's own choices do not count for today. Signals at or before a reset are ignored.
     */
    fun usualChoices(events: List<SuggestionEvent>, today: LocalDate, resets: Map<String, Long> = emptyMap()): Map<Int, UsualChoice> {
        val from = today.minus(DatePeriod(days = WINDOW_DAYS))
        val byDay = events.groupBy { it.day }.mapNotNull { (day, list) ->
            val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return@mapNotNull null
            if (date >= today || date < from) return@mapNotNull null
            val iso = date.dayOfWeek.isoDayNumber
            val cutoff = since(resets, weekdayKey(iso))
            val valid = list.filter { it.createdAt > cutoff }
            if (valid.none { it.kind == SuggestionEventKind.STARTED }) return@mapNotNull null
            val choices = valid.filter { it.kind == SuggestionEventKind.FEEDBACK }.sortedBy { it.createdAt }
            val kind = choices.lastOrNull { it.focus?.startsWith(CHOSEN_KIND) == true }?.let { e ->
                val name = e.focus!!.removePrefix(CHOSEN_KIND)
                Choice(SuggestionFocus.entries.firstOrNull { it.name == name })
            }
            val minutes = choices.lastOrNull { it.focus == CHOSEN_MINUTES }?.let { Choice(it.value?.roundToInt()) }
            DayChoice(date, iso, kind, minutes)
        }
        return byDay.groupBy { it.iso }.mapNotNull { (iso, days) ->
            val recent = days.sortedByDescending { it.date }
            fun <T : Any> usual(pick: (DayChoice) -> Choice<T>?): T? {
                val made = recent.mapNotNull(pick)
                val last = made.firstOrNull()?.value ?: return null
                return last.takeIf { made.count { it.value == last } >= 2 }
            }
            val focus = usual { it.kind }
            val minutes = usual { it.minutes }
            if (focus == null && minutes == null) null else iso to UsualChoice(iso, focus, minutes)
        }.toMap()
    }

    private data class Choice<T>(val value: T?)
    private data class DayChoice(val date: LocalDate, val iso: Int, val kind: Choice<SuggestionFocus>?, val minutes: Choice<Int>?)

    /**
     * Slug → affinity in [-MAX_AFFINITY, MAX_AFFINITY].
     *
     * - "another suggestion" because of an exercise (DISLIKE) −2, skipped set −0.5, excluded −3
     * - swapped away −1, swapped to +1 (SWAPPED slugs = [from, to])
     * - started suggestion +0.3, completed suggestion +0.5 × share done
     * - every training the exercise was logged in +0.3 (repeated → liked)
     */
    fun affinity(
        events: List<SuggestionEvent>, sets: List<ExerciseSet>, today: LocalDate,
        /** Forgotten exercises and "forget everything" ([since]). */
        resets: Map<String, Long> = emptyMap(),
    ): Map<String, Double> {
        val todayMs = today.toEpochDays().toLong() * DAY_MS + DAY_MS
        val scores = mutableMapOf<String, Double>()
        fun add(slug: String, value: Double, atMs: Long) {
            if (atMs <= since(resets, exerciseKey(slug))) return
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
