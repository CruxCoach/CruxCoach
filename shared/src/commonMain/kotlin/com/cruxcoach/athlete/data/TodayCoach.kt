package com.cruxcoach.athlete.data

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.logic.AreaProgress
import com.cruxcoach.athlete.logic.BlockState
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.ConsistencyStreak
import com.cruxcoach.athlete.logic.DayActivity
import com.cruxcoach.athlete.logic.DayPlans
import com.cruxcoach.athlete.logic.LoadStatus
import com.cruxcoach.athlete.logic.LoadStructure
import com.cruxcoach.athlete.logic.LoadTrend
import com.cruxcoach.athlete.logic.LogbookSummary
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.Readiness
import com.cruxcoach.athlete.logic.ReturnState
import com.cruxcoach.athlete.logic.ReturnToTraining
import com.cruxcoach.athlete.logic.Soreness
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionInput
import com.cruxcoach.athlete.logic.TrainingBlocks
import com.cruxcoach.athlete.logic.WeeklyVolume
import com.cruxcoach.athlete.model.AthleteProfile
import com.cruxcoach.athlete.model.Checkin
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.model.SuggestionFeedback
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * Everything today's suggestion is built from, gathered from the athlete's data: the training block,
 * the week's volume, the return ramp after a break, learned preferences, yesterday's and today's climbing.
 * Today's screen and the training simulation (issue #59) both use this, so what the simulation checks is
 * what the athlete gets. Reads only, except that the block starts on the first day the coach runs.
 */
class TodayCoach(
    private val repo: AthleteRepository,
    private val catalog: ExerciseCatalog,
    private val zone: TimeZone,
    /** Epoch milliseconds now; the same clock as [today]. */
    private val now: () -> Long,
) {

    /** What the athlete chose on today's card; resets with the day. */
    data class Choices(
        val variant: Int = 0,
        val budgetFactor: Double = 1.0,
        val levelShift: Int = 0,
        val wish: SuggestionFocus? = null,
        val minutesToday: Int? = null,
        val dislikedToday: Set<String> = emptySet(),
        /** "Another suggestion" or "the coach decides" on the usual workout: something else today. */
        val ignoreHabit: Boolean = false,
        /** "The coach decides" for today: the weekday's usual kind does not apply. */
        val ignoreUsualKind: Boolean = false,
    )

    /** The engine input plus what the card shows next to it. */
    data class Built(
        val input: SuggestionInput,
        /** The usual workout ([PreferenceLearning.habit]), also when ignored today. */
        val habit: PreferenceLearning.Habit?,
        val levelOffers: List<PreferenceLearning.LevelOffer>,
        val usual: PreferenceLearning.UsualChoice?,
    )

    private fun dayOf(ms: Long): LocalDate = kotlin.time.Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date

    private fun covers(p: PausePeriod, day: LocalDate, today: LocalDate): Boolean {
        val start = runCatching { LocalDate.parse(p.startDay) }.getOrNull() ?: return false
        val end = p.endDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        return day in start..end
    }

    /**
     * The training cycle: starts on the first day the coach runs (so every
     * athlete gets intro → build → deload), a trip or competition date adds the
     * taper; tired check-ins and a finger-load spike can bring the deload forward.
     */
    fun blockState(profile: AthleteProfile, loadStatus: LoadStatus?, today: LocalDate): BlockState? =
        runCatching {
            val coach = profile.coach
            val start = coach.blockStartDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: run {
                repo.updateProfile { p -> if (p.coach.blockStartDay != null) p else p.copy(coach = p.coach.copy(blockStartDay = today.toString())) }
                today
            }
            val target = coach.targetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val checkins = repo.checkinsBetween(today.minus(DatePeriod(days = 6)).toString(), today.toString())
            var fatigue = checkins.count { c -> (c.sleep ?: 5) <= 2 || (c.energy ?: 5) <= 2 || (c.fingers ?: 5) <= 2 }
            fatigue += when (loadStatus?.structures?.get(LoadStructure.FINGER)?.trend) {
                LoadTrend.SPIKE -> 2
                LoadTrend.RISING -> 1
                else -> 0
            }
            TrainingBlocks.state(start, target, today, fatigue)
        }.getOrNull()

    /**
     * The weekly volume plan: targets for this athlete and this block, and what
     * the week holds so far (logged sets of every training this week, board and
     * manual climbing days as credit).
     */
    fun weeklyProgress(
        profile: AthleteProfile, injuries: List<Injury>, activities: Map<String, DayActivity>, block: BlockState?,
        today: LocalDate, weekStart: LocalDate,
    ): List<AreaProgress> {
        val days = profile.coach.trainingDaysPerWeek ?: profile.weeklyGoal
        val targets = WeeklyVolume.targets(profile, block, injuries, days)
        val workoutDays = repo.workoutsBetween(weekStart.toString(), today.toString()).associate { it.id to LocalDate.parse(it.day) }
        val startMs = weekStart.atStartOfDayIn(zone).toEpochMilliseconds()
        val weekSets = repo.completedSetsSince(startMs).mapNotNull { set -> workoutDays[set.workoutId]?.let { it to set } }
        return WeeklyVolume.progress(weekSets, activities, catalog, weekStart, targets, today)
    }

    /** The return ramp after a break or a healed finger injury ([ReturnToTraining]); null when training normally. */
    fun returnState(activities: Map<String, DayActivity>, today: LocalDate): ReturnState? = runCatching {
        val since = today.minus(DatePeriod(days = 120))
        val days120 = repo.workoutsBetween(since.toString(), today.toString()).associate { it.id to LocalDate.parse(it.day) }
        val sets120 = repo.completedSetsSince(now() - 120L * 24 * 3600 * 1000)
            .mapNotNull { set -> days120[set.workoutId]?.let { it to set } }
        ReturnToTraining.state(activities, repo.pauses(), repo.allInjuries(), today,
            maxFingerBefore = ReturnToTraining.maxFingerBefore(sets120, catalog, today))
    }.getOrNull()

    /** Today's engine input from all the coach knows. */
    fun build(
        profile: AthleteProfile,
        checkin: Checkin?,
        injuries: List<Injury>,
        pauses: List<PausePeriod>,
        readiness: Readiness,
        activities: Map<String, DayActivity>,
        block: BlockState?,
        loadStatus: LoadStatus?,
        weekly: List<AreaProgress>,
        weekStart: LocalDate?,
        logbook: LogbookSummary?,
        today: LocalDate,
        choices: Choices = Choices(),
    ): Built {
        val nowMs = now()
        val workoutDays = repo.workoutsBetween(today.minus(DatePeriod(days = 8)).toString(), today.toString())
            .associate { it.id to LocalDate.parse(it.day) }
        val recentSets = repo.completedSetsSince(nowMs - 9L * 24 * 3600 * 1000)
            .mapNotNull { set -> workoutDays[set.workoutId]?.let { it to set } }
        val lastTrained = repo.trainedExercises().mapNotNull { t -> t.lastAt?.let { ms -> t.slug to dayOf(ms) } }.toMap()
        val routines = repo.routines()
        val sinceMs = nowMs - PreferenceLearning.WINDOW_DAYS * 24L * 3600 * 1000
        val resets = profile.learningResets
        // The workout the athlete keeps doing: today's suggestion on a free day, progression per exercise (owner 2026-10-19).
        val isRoutine: (String) -> Boolean = { id ->
            if (id.startsWith("builtin:")) BuiltinRoutines.byKey(id.removePrefix("builtin:")) != null else routines.any { it.id == id }
        }
        val habit = PreferenceLearning.habit(
            repo.workoutsBetween(today.minus(DatePeriod(days = PreferenceLearning.WINDOW_DAYS)).toString(), today.toString()),
            today, isRoutine, resets)
        val events = repo.suggestionEventsSince(sinceMs)
        // Today's swaps are learned for the days to come; today's card keeps the rest of its picks
        // (device test 2026-10-17: one swap moved the other exercises around).
        // "Leave it out today" likewise (device test 2026-10-17: the dropped press made the slot pick push-ups instead).
        val stable = events.filterNot { e ->
            e.day == today.toString() && (e.kind == SuggestionEventKind.SWAPPED ||
                (e.kind == SuggestionEventKind.NEXT && e.feedback == SuggestionFeedback.DISLIKE_EXERCISE && e.value == PreferenceLearning.LEFT_OUT))
        }
        val learned = PreferenceLearning.affinity(stable, repo.completedSetsSince(sinceMs), today, resets)
        val affinity = learned + choices.dislikedToday.associateWith { -10.0 }
        // Turned down on earlier days: rests a few days (today's own swaps keep the card stable instead).
        // Kept after a training ("künftig so"): the chosen exercise leads its slot, the replaced one rests.
        val kept = PreferenceLearning.keptEffect(PreferenceLearning.kept(events, today, resets))
        val resting = PreferenceLearning.resting(stable.filterNot { it.day == today.toString() }, today, resets).keys + kept.resting.keys
        // The kind and time the athlete chose on this weekday before: today's default until they choose otherwise.
        val levelOffers = PreferenceLearning.levelOffers(events, today, profile.keptLevels, resets)
        val usual = PreferenceLearning.usualChoices(events, today, resets)[today.dayOfWeek.isoDayNumber]
        val usualWish = usual?.focus?.takeIf { choices.wish == null && !choices.ignoreUsualKind }
        val usualMinutes = usual?.minutes?.takeIf { choices.minutesToday == null }
        val parsedActivities = activities.mapNotNull { (day, a) -> runCatching { LocalDate.parse(day) }.getOrNull()?.let { it to a } }.toMap()
        val todayActivity = parsedActivities[today]
        // Logged, or planned for the date (its own entry first, then the standard week and the usual climbing days).
        val climbingToday = (todayActivity != null && (todayActivity.climbingMinutes > 0 || todayActivity.climbingEfforts > 0)) ||
            DayPlans.climbingPlanned(profile, today)
        val yesterday = today.minus(DatePeriod(days = 1))
        val plannedYesterday = DayPlans.climbIntensity(profile, yesterday)
            ?.takeIf { DayPlans.climbingPlanned(profile, yesterday) && pauses.none { p -> covers(p, yesterday, today) } }
        val allBenchmarks = repo.allBenchmarks()
        val benchmarks = allBenchmarks.groupBy { it.exerciseSlug }.mapNotNull { (_, list) -> list.maxByOrNull { it.measuredAt } }
        val historyWeeks = parsedActivities.filterValues { it.trained }.keys.map { ConsistencyStreak.weekStart(it) }.distinct().size
        // Back after a break or a healed finger injury: a conservative ramp on top of every other rule.
        val returnState = returnState(activities, today)
        val input = SuggestionInput(
            catalog = catalog,
            profile = profile,
            today = today,
            readiness = readiness,
            injuries = injuries + Soreness.injuries(checkin),
            activities = parsedActivities,
            recentSets = recentSets,
            favorites = repo.favorites(),
            benchmarkSlugs = allBenchmarks.map { it.exerciseSlug }.toSet(),
            lastTrained = lastTrained,
            routines = routines,
            variant = choices.variant,
            coach = profile.coach,
            loadStatus = loadStatus,
            affinity = affinity,
            excluded = profile.excludedExercises,
            block = block,
            climbingToday = climbingToday,
            plannedYesterday = plannedYesterday,
            habit = habit.takeIf { !choices.ignoreHabit },
            benchmarks = benchmarks,
            levelShift = choices.levelShift,
            budgetFactor = choices.budgetFactor,
            logbookSends = logbook?.sampleSize ?: 0,
            logbookDifficulty = logbook?.takeIf { it.hasGrades }?.workingDifficulty,
            resting = resting,
            historyWeeks = historyWeeks,
            weekly = weekly,
            weekStart = weekStart,
            returnState = returnState,
            wish = choices.wish ?: usualWish,
            minutesToday = choices.minutesToday ?: usualMinutes,
            wishLearned = choices.wish == null && usualWish != null,
            minutesLearned = choices.minutesToday == null && usualMinutes != null,
            kept = kept.preferred,
        )
        return Built(input, habit, levelOffers, usual)
    }
}
