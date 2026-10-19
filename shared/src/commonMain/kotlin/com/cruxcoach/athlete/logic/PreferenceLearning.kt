package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEvent
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.model.SuggestionFeedback
import com.cruxcoach.athlete.model.Workout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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
    /** An exercise logged in the athlete's own training (not a suggestion they followed). */
    const val OWN_LOGGED = 0.3
    const val FOLLOWED_START = 0.05
    const val FOLLOWED_DONE = 0.05
    const val FOLLOWED_LOGGED = 0.05
    /** Days a rejected exercise rests: once, twice, three times or more within [WINDOW_DAYS]. */
    val REST_DAYS = listOf(3, 10, 21)

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
    /**
     * Ledger [SuggestionEventKind.FEEDBACK] focus for a training format chosen today:
     * `chosen_format:<format>` (`chosen_format:circuit` for the circuit chip). Recorded only; it is
     * neither a kind ([CHOSEN_KIND]) nor a duration, and no exercise preference.
     */
    const val CHOSEN_FORMAT = "chosen_format:"
    /** Today's circuit rounds and rests ("rounds,circuitRest,roundRest", empty = coach default), for a restart. */
    const val CIRCUIT_SETUP = "circuit_setup:"
    /** A DISLIKE_EXERCISE with this value is "leave it out today" (no replacement), not "not today, something else". */
    const val LEFT_OUT = 1.0
    /**
     * Ledger [SuggestionEventKind.FEEDBACK] focus when the athlete ended an exercise after at least one
     * set ("Übung beenden"); slugs = the exercise, value = share of its work sets done. Unlike a skipped
     * set it costs the exercise nothing: stopping after two good sets is no dislike.
     */
    const val ENDED_EARLY = "ended_early"
    /** Ledger [SuggestionEventKind.FEEDBACK] focus for "+ Satz"; slugs = the exercise, value = its work sets now. */
    const val EXTRA_SET = "extra_set"

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
     * - every own training the exercise was logged in +0.3 (repeated → liked)
     * - following the coach counts little (owner 2026-10-17: learn, but do not overfit): started suggestion
     *   +[FOLLOWED_START], completed +[FOLLOWED_DONE] × share done, logged in a suggested training
     *   +[FOLLOWED_LOGGED]. Doing what was suggested is not a preference; otherwise the coach kept
     *   suggesting its own picks (+0.8 per session, the cap after four).
     */
    fun affinity(
        events: List<SuggestionEvent>, sets: List<ExerciseSet>, today: LocalDate,
        /** Forgotten exercises and "forget everything" ([since]). */
        resets: Map<String, Long> = emptyMap(),
        /** The athlete's time zone: signals age by their local days. */
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Map<String, Double> {
        val scores = mutableMapOf<String, Double>()
        // Trainings started from a suggestion: what was done there followed the coach.
        val followed = events.filter { it.kind == SuggestionEventKind.STARTED || it.kind == SuggestionEventKind.COMPLETED }
            .mapNotNull { it.workoutId }.toSet()
        // Age in the athlete's days, not in UTC days: just after local midnight, a swap made a minute
        // ago counted as a day old (0.98) and missed "done gladly" (≥ 1) – device test 2026-10-17.
        fun add(slug: String, value: Double, atMs: Long, day: LocalDate) {
            if (atMs <= since(resets, exerciseKey(slug))) return
            val ageDays = (today.toEpochDays() - day.toEpochDays()).toDouble().coerceAtLeast(0.0)
            val weight = (1.0 - ageDays / WINDOW_DAYS).coerceAtLeast(0.0)
            if (weight > 0) scores[slug] = (scores[slug] ?: 0.0) + value * weight
        }
        for (e in events) {
            val day = runCatching { LocalDate.parse(e.day) }.getOrNull() ?: ProgressStats.dayOf(e.createdAt, zone)
            fun add(slug: String, value: Double) = add(slug, value, e.createdAt, day)
            when (e.kind) {
                SuggestionEventKind.NEXT, SuggestionEventKind.FEEDBACK ->
                    if (e.feedback == SuggestionFeedback.DISLIKE_EXERCISE) e.slugs.forEach { add(it, -2.0) }
                SuggestionEventKind.SKIPPED_SET -> e.slugs.forEach { add(it, -0.5) }
                SuggestionEventKind.EXCLUDED -> e.slugs.forEach { add(it, -3.0) }
                SuggestionEventKind.SWAPPED -> {
                    e.slugs.getOrNull(0)?.let { add(it, -1.0) }
                    e.slugs.getOrNull(1)?.let { add(it, 1.0) }
                }
                SuggestionEventKind.STARTED -> e.slugs.forEach { add(it, FOLLOWED_START) }
                SuggestionEventKind.COMPLETED -> e.slugs.forEach { add(it, FOLLOWED_DONE * (e.value ?: 1.0).coerceIn(0.0, 1.0)) }
                SuggestionEventKind.SHOWN, SuggestionEventKind.EDITED, SuggestionEventKind.SAVED -> Unit
            }
        }
        sets.filter { it.isCompleted && it.setType != SetType.WARMUP }
            .groupBy { it.workoutId to it.exerciseSlug }
            .forEach { (key, rows) ->
                val at = rows.maxOf { it.completedAt ?: 0L }
                add(key.second, if (key.first in followed) FOLLOWED_LOGGED else OWN_LOGGED, at, ProgressStats.dayOf(at, zone))
            }
        return scores.mapValues { (_, v) -> v.coerceIn(-MAX_AFFINITY, MAX_AFFINITY) }.filterValues { abs(it) >= 0.05 }
    }

    /**
     * Exercises that rest after the athlete turned them down, with the day they may come back
     * (owner 2026-10-17: the coach must not keep suggesting what the athlete rejects – and stays a coach).
     * Swapped away or left out today counts 1, a skipped exercise ½; once rests [REST_DAYS][0] days,
     * twice [REST_DAYS][1], three times or more [REST_DAYS][2], from the last rejection. Afterwards the
     * exercise may come back; only "never suggest" is for good. Forgotten exercises start fresh.
     */
    fun resting(
        events: List<SuggestionEvent>,
        today: LocalDate,
        resets: Map<String, Long> = emptyMap(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Map<String, LocalDate> = rejectionDays(events, today, resets, zone).mapNotNull { (slug, list) ->
        val count = list.sumOf { it.second }
        // One skipped exercise alone says too little (time, mood): no rest yet.
        if (count < 1.0) return@mapNotNull null
        val days = when {
            count >= 3.0 -> REST_DAYS[2]
            count >= 2.0 -> REST_DAYS[1]
            else -> REST_DAYS[0]
        }
        val until = list.maxOf { it.first }.plus(DatePeriod(days = days))
        if (until > today) slug to until else null
    }.toMap()

    /** How often each exercise was turned down in [WINDOW_DAYS], counted like [resting]. */
    fun rejections(
        events: List<SuggestionEvent>,
        today: LocalDate,
        resets: Map<String, Long> = emptyMap(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Map<String, Double> = rejectionDays(events, today, resets, zone).mapValues { (_, list) -> list.sumOf { it.second } }

    /** After this many rejections the card asks once whether to stop suggesting the exercise. */
    const val OFFER_NEVER_AFTER = 3.0

    private fun rejectionDays(
        events: List<SuggestionEvent>,
        today: LocalDate,
        resets: Map<String, Long>,
        zone: TimeZone,
    ): Map<String, List<Pair<LocalDate, Double>>> {
        val from = today.minus(DatePeriod(days = WINDOW_DAYS))
        val rejections = mutableMapOf<String, MutableList<Pair<LocalDate, Double>>>()
        for (e in events) {
            val day = runCatching { LocalDate.parse(e.day) }.getOrNull() ?: ProgressStats.dayOf(e.createdAt, zone)
            if (day < from || day > today) continue
            fun reject(slug: String, weight: Double) {
                if (e.createdAt <= since(resets, exerciseKey(slug))) return
                rejections.getOrPut(slug) { mutableListOf() } += day to weight
            }
            when (e.kind) {
                SuggestionEventKind.NEXT, SuggestionEventKind.FEEDBACK ->
                    if (e.feedback == SuggestionFeedback.DISLIKE_EXERCISE) e.slugs.forEach { reject(it, 1.0) }
                SuggestionEventKind.SWAPPED -> e.slugs.getOrNull(0)?.let { reject(it, 1.0) }
                SuggestionEventKind.SKIPPED_SET -> e.slugs.forEach { reject(it, 0.5) }
                else -> Unit
            }
        }
        // At most one rejection per exercise and day (the strongest): an older player logged a skip for
        // every set it moved past, which rested an exercise for three weeks after one session.
        return rejections.mapValues { (_, all) -> all.groupBy { it.first }.map { (day, rows) -> day to rows.maxOf { it.second } } }
    }

    // ── "Easier"/"harder" for good (comparison review #10) ──

    /**
     * Ledger [SuggestionEventKind.FEEDBACK] focus `chosen_level:<SuggestionFocus>`, value 0: back to the coach's level
     * for today. "Easier" and "harder" themselves are NEXT events with TOO_HARD / TOO_EASY and the kind as focus.
     */
    const val CHOSEN_LEVEL = "chosen_level:"
    /** Ledger FEEDBACK focus `level_answer:<SuggestionFocus>`: value = the step taken over for good (−1/+1), 0 = "no". */
    const val LEVEL_ANSWER = "level_answer:"
    /** Two same-direction choices within this many days make the offer; a "no" keeps it away as long. */
    const val LEVEL_WINDOW_DAYS = 21
    /** Forgets the level kept for one kind (and the choices behind it). */
    fun levelKey(focus: String) = "level:$focus"

    /** "From now on one step easier ([direction] −1) / more demanding (+1) for [focus]?" */
    data class LevelOffer(val focus: SuggestionFocus, val direction: Int)

    /**
     * The level step to offer for good: the athlete chose the same direction for the same kind of training on two
     * days within [LEVEL_WINDOW_DAYS] (a day counts with its last choice; "back to normal" cancels it). Choices before an
     * answer for that kind, and the [LEVEL_WINDOW_DAYS] after a "no", ask nothing; a kept step is never offered twice.
     */
    fun levelOffers(
        events: List<SuggestionEvent>,
        today: LocalDate,
        kept: Map<String, Int>,
        resets: Map<String, Long> = emptyMap(),
    ): List<LevelOffer> {
        val from = today.minus(DatePeriod(days = LEVEL_WINDOW_DAYS - 1))
        val answers = events.filter { it.kind == SuggestionEventKind.FEEDBACK && it.focus?.startsWith(LEVEL_ANSWER) == true }
            .groupBy { it.focus!!.removePrefix(LEVEL_ANSWER) }.mapValues { (_, list) -> list.maxBy { it.createdAt } }
        // Kind → day → the day's last level choice.
        val days = mutableMapOf<String, MutableMap<LocalDate, Int>>()
        for (e in events.sortedBy { it.createdAt }) {
            val (kind, shift) = when {
                e.kind == SuggestionEventKind.NEXT && e.feedback == SuggestionFeedback.TOO_EASY -> (e.focus ?: continue) to 1
                e.kind == SuggestionEventKind.NEXT && e.feedback == SuggestionFeedback.TOO_HARD -> (e.focus ?: continue) to -1
                e.kind == SuggestionEventKind.FEEDBACK && e.focus?.startsWith(CHOSEN_LEVEL) == true ->
                    e.focus!!.removePrefix(CHOSEN_LEVEL) to (e.value?.roundToInt() ?: 0)
                else -> continue
            }
            val day = runCatching { LocalDate.parse(e.day) }.getOrNull() ?: continue
            if (day < from || day > today) continue
            if (e.createdAt <= max(since(resets, levelKey(kind)), answers[kind]?.createdAt ?: 0L)) continue
            days.getOrPut(kind) { mutableMapOf() }[day] = shift.coerceIn(-1, 1)
        }
        return days.mapNotNull { (kind, byDay) ->
            val focus = SuggestionFocus.entries.firstOrNull { it.name == kind }?.takeIf { it != SuggestionFocus.REST } ?: return@mapNotNull null
            val no = answers[kind]?.takeIf { (it.value ?: 0.0) == 0.0 }
            if (no != null && runCatching { LocalDate.parse(no.day) }.getOrNull()?.let { it >= from } != false) return@mapNotNull null
            val direction = listOf(-1, 1).firstOrNull { d -> byDay.values.count { it == d } >= 2 } ?: return@mapNotNull null
            val now = kept[kind] ?: 0
            if ((now + direction) !in -1..1) return@mapNotNull null
            LevelOffer(focus, direction)
        }
    }

    // ── "From now on like this?" after a training (comparison review #3) ──

    /**
     * Ledger [SuggestionEventKind.FEEDBACK] focus for "künftig so" after a training: slugs = [replaced, chosen], or
     * [left out] alone; value = the epoch day the choice holds until ([keepUntil]). The chosen exercise is then the
     * preferred pick of its slot, the replaced one rests – both until that day.
     */
    const val KEEP_CHOICE = "keep_choice"
    /** "Nur heute": the question of this training is answered; its swaps teach the coach as lightly as always. */
    const val KEEP_TODAY_ONLY = "keep_today_only"
    /** Ledger FEEDBACK focus for an exercise removed from a running training (slugs = it); teaches nothing by itself. */
    const val REMOVED = "removed"
    /** A kept choice holds at least this long (and to the end of the training block when that is later). */
    const val KEEP_MIN_DAYS = 21

    /** One exercise of a suggestion the athlete changed: swapped for [chosen], or left out ([chosen] null). */
    data class Change(val replaced: String, val chosen: String?)

    /**
     * What the athlete changed in the suggestion training [workoutId] was started from, from that day's ledger:
     * swaps and "leave out today" on the card before the start, swaps, skips and removals in the training. A swap back
     * cancels out, a swap of a swap counts once from the suggested exercise. Card changes an earlier training of the
     * day already asked about do not count again; only the suggestion's own exercises count.
     */
    fun changes(dayEvents: List<SuggestionEvent>, workoutId: String): List<Change> {
        val sorted = dayEvents.sortedBy { it.createdAt }
        val started = sorted.firstOrNull { it.kind == SuggestionEventKind.STARTED && it.workoutId == workoutId } ?: return emptyList()
        val answeredAt = sorted.lastOrNull {
            it.kind == SuggestionEventKind.FEEDBACK && (it.focus == KEEP_CHOICE || it.focus == KEEP_TODAY_ONLY) && it.createdAt < started.createdAt
        }?.createdAt ?: Long.MIN_VALUE
        val now = LinkedHashMap<String, String>()   // suggested slug → what took its place ("" = left out)
        val changedAt = mutableMapOf<String, Long>()
        val cardOriginals = mutableSetOf<String>()
        fun original(shown: String) = now.entries.firstOrNull { it.value == shown }?.key ?: shown
        for (e in sorted) {
            // The card's own changes come before the start and belong to no training; the summary's swap in the
            // workouts has no suggestion focus.
            val card = e.workoutId == null && e.createdAt < started.createdAt && e.focus != null
            val training = e.workoutId == workoutId && e.createdAt >= started.createdAt
            if (!card && !training) continue
            when {
                e.kind == SuggestionEventKind.SWAPPED -> {
                    val from = e.slugs.getOrNull(0) ?: continue
                    val to = e.slugs.getOrNull(1) ?: continue
                    val key = original(from)
                    if (key == to) now.remove(key) else now[key] = to
                    changedAt[key] = e.createdAt
                    if (card) cardOriginals += key
                }
                card && e.kind == SuggestionEventKind.NEXT && e.feedback == SuggestionFeedback.DISLIKE_EXERCISE && e.value == LEFT_OUT ->
                    e.slugs.forEach { val key = original(it); now[key] = ""; changedAt[key] = e.createdAt; cardOriginals += key }
                training && (e.kind == SuggestionEventKind.SKIPPED_SET || (e.kind == SuggestionEventKind.FEEDBACK && e.focus == REMOVED)) ->
                    e.slugs.forEach { val key = original(it); now[key] = ""; changedAt[key] = e.createdAt }
            }
        }
        val suggested = started.slugs.toSet() + cardOriginals
        return now.entries.filter { (key, _) -> key in suggested && (changedAt[key] ?: Long.MIN_VALUE) > answeredAt }
            .map { (key, to) -> Change(key, to.ifEmpty { null }) }
    }

    /** A choice the athlete kept after a training, until [until] (exclusive: from that day the coach chooses again). */
    data class Kept(val replaced: String, val chosen: String?, val until: LocalDate, val eventId: String, val at: Long)

    /** Forgets one kept choice ("Vergessen" on the learned page). */
    fun keptKey(eventId: String) = "keep:$eventId"

    /** The choices kept and still holding today, newest first; forgetting either exercise, the choice or everything ends them. */
    fun kept(events: List<SuggestionEvent>, today: LocalDate, resets: Map<String, Long> = emptyMap()): List<Kept> =
        events.filter { it.kind == SuggestionEventKind.FEEDBACK && it.focus == KEEP_CHOICE }.mapNotNull { e ->
            val replaced = e.slugs.getOrNull(0) ?: return@mapNotNull null
            val chosen = e.slugs.getOrNull(1)
            val until = e.value?.let { LocalDate.fromEpochDays(it.roundToLong()) } ?: return@mapNotNull null
            if (until <= today || e.createdAt <= since(resets, exerciseKey(replaced)) || e.createdAt <= since(resets, keptKey(e.id)) ||
                (chosen != null && e.createdAt <= since(resets, exerciseKey(chosen)))) return@mapNotNull null
            Kept(replaced, chosen, until, e.id, e.createdAt)
        }.sortedByDescending { it.at }

    /** What the kept choices mean now: preferred picks (newest first) and exercises resting until a day. A newer choice about an exercise wins. */
    data class KeptEffect(val preferred: List<String>, val resting: Map<String, LocalDate>)

    fun keptEffect(kept: List<Kept>): KeptEffect {
        val decided = mutableSetOf<String>()
        val preferred = mutableListOf<String>()
        val resting = mutableMapOf<String, LocalDate>()
        for (k in kept) {   // newest first
            if (k.replaced !in decided) resting[k.replaced] = k.until
            if (k.chosen != null && k.chosen !in decided) preferred += k.chosen
            decided += k.replaced
            k.chosen?.let { decided += it }
        }
        return KeptEffect(preferred, resting)
    }

    /** Until when a choice kept today holds: the end of the training block, at least [KEEP_MIN_DAYS] days. */
    fun keepUntil(today: LocalDate, blockEnd: LocalDate?): LocalDate {
        val least = today.plus(DatePeriod(days = KEEP_MIN_DAYS))
        return if (blockEnd != null && blockEnd > least) blockEnd else least
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

    // ── The usual workout (owner 2026-10-19: "always the same training, only the progression") ──

    /** On which grounds a workout is the usual one: it is done on this weekday, or in most trainings lately. */
    enum class HabitKind { WEEKDAY, USUAL }

    /** The athlete's own workout (a routine id, or "builtin:<key>") done again and again. */
    data class Habit(val entry: String, val kind: HabitKind, val isoDay: Int?, val times: Int)

    /** Forgets that [entry] is the usual workout (the learned page's "Vergessen"). */
    fun habitKey(entry: String) = "habit:$entry"

    /** Times on one weekday that make a workout that weekday's usual one. */
    const val HABIT_WEEKDAY_TIMES = 2
    /** Of the last [HABIT_RECENT] trainings, this many with the same workout make it the usual one on any day. */
    const val HABIT_USUAL_TIMES = 3
    const val HABIT_RECENT = 4

    /** Separates a suggestion training's id from the workout it was made of: "suggestion:<day>@<entry>". */
    const val SUGGESTION_OF = '@'

    /** The workout a training was: its routine, or the routine a suggestion was made of; null for anything else. */
    fun routineOf(w: Workout, isRoutine: (String) -> Boolean): String? {
        val id = w.routineId ?: return null
        val entry = if (id.startsWith("suggestion")) id.substringAfter(SUGGESTION_OF, "").ifEmpty { return null } else id
        return entry.takeIf(isRoutine)
    }

    /**
     * The usual workouts as of [today]: per ISO weekday the one done on [HABIT_WEEKDAY_TIMES] days of that weekday within
     * [WINDOW_DAYS], the last training on that weekday included; and the one done in [HABIT_USUAL_TIMES] of the last
     * [HABIT_RECENT] trainings. Today's trainings and trainings at or before a reset ([habitKey], the weekday, "all")
     * do not count. [isRoutine] says whether an id is one of the athlete's routines or a starter routine (deleted
     * ones are no habit).
     */
    fun habits(workouts: List<Workout>, today: LocalDate, isRoutine: (String) -> Boolean, resets: Map<String, Long> = emptyMap()): List<Habit> {
        val from = today.minus(DatePeriod(days = WINDOW_DAYS)).toString()
        val done = workouts.filter { !it.isOpen && it.day < today.toString() && it.day >= from }.sortedByDescending { it.startedAt }
        fun counts(w: Workout, entry: String, iso: Int?) =
            w.startedAt > since(resets, habitKey(entry)) && (iso == null || w.startedAt > since(resets, weekdayKey(iso)))
        val out = mutableListOf<Habit>()
        val byWeekday = done.groupBy { runCatching { LocalDate.parse(it.day).dayOfWeek.isoDayNumber }.getOrNull() }
        for ((iso, list) in byWeekday) {
            if (iso == null) continue
            val last = list.first()
            val entry = routineOf(last, isRoutine) ?: continue
            if (!counts(last, entry, iso)) continue
            val days = list.filter { routineOf(it, isRoutine) == entry && counts(it, entry, iso) }.map { it.day }.distinct().size
            if (days >= HABIT_WEEKDAY_TIMES) out += Habit(entry, HabitKind.WEEKDAY, iso, days)
        }
        val recent = done.take(HABIT_RECENT)
        recent.mapNotNull { w -> routineOf(w, isRoutine)?.takeIf { counts(w, it, null) } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }
            ?.takeIf { it.value >= HABIT_USUAL_TIMES }
            ?.let { (entry, times) -> out += Habit(entry, HabitKind.USUAL, null, times) }
        return out.sortedWith(compareBy({ it.kind }, { it.isoDay ?: 0 }))
    }

    /** Today's usual workout: this weekday's, else the one done in most trainings lately. */
    fun habit(workouts: List<Workout>, today: LocalDate, isRoutine: (String) -> Boolean, resets: Map<String, Long> = emptyMap()): Habit? {
        val all = habits(workouts, today, isRoutine, resets)
        return all.firstOrNull { it.kind == HabitKind.WEEKDAY && it.isoDay == today.dayOfWeek.isoDayNumber }
            ?: all.firstOrNull { it.kind == HabitKind.USUAL }
    }

    /** Setting that remembers "Nein danke" to taking [entry] into the standard week on [isoDay]. */
    fun habitOfferKey(isoDay: Int, entry: String) = "habit_offer_no:$isoDay:$entry"
}
