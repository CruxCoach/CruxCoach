package com.cruxcoach.android.athlete.sim

import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.sim.SimAthlete.Quality
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.data.TodayCoach
import com.cruxcoach.athlete.logic.BlockState
import com.cruxcoach.athlete.logic.ConsistencyStreak
import com.cruxcoach.athlete.logic.LoadStructure
import com.cruxcoach.athlete.logic.LoadTrend
import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.logic.ReadinessEvaluator
import com.cruxcoach.athlete.logic.ReadinessLevel
import com.cruxcoach.athlete.logic.ReturnState
import com.cruxcoach.athlete.logic.SessionSuggester
import com.cruxcoach.athlete.logic.SessionSuggestion
import com.cruxcoach.athlete.logic.Soreness
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.Checkin
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.PauseReason
import com.cruxcoach.athlete.model.PausePeriod
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.model.SuggestionFeedback
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlin.random.Random

/** A suggestion as the athlete saw it. */
data class SuggestedItem(
    val slug: String, val sets: Int, val reps: Int?, val durationS: Int?, val loadKg: Double?, val warmup: Boolean,
    val edgeMm: Int?,
)

data class SuggestionTrace(
    val focus: SuggestionFocus,
    val reasons: List<SuggestionReason>,
    val items: List<SuggestedItem>,
    val minutes: Int,
    val addOn: Boolean,
    val board: Boolean,
    val shortenedForFatigue: Boolean,
    /** Shown after "too easy" on the card. */
    val levelShift: Int = 0,
)

/** A set as it was planned and done. */
data class DoneSet(
    val slug: String, val setType: SetType, val side: Side?, val setIndex: Int,
    val targetLoadKg: Double?, val loadKg: Double?, val targetReps: Int?, val reps: Int?,
    val targetDurationS: Double?, val durationS: Double?, val edgeMm: Double?, val rir: Int?, val trueReserve: Double,
)

data class LoadSnapshot(val acute: Double, val chronic: Double, val trend: LoadTrend)

/**
 * "Don't know it" on the card (issue #68): [from] swapped for [to]; [affinityChange] is what the tap changed in the
 * learned preference for [from], [resting] whether it rests afterwards (neither should happen).
 */
data class DontKnowTrace(val from: String, val to: String, val affinityChange: Double, val resting: Boolean)

data class DayTrace(
    val day: Int,
    val date: LocalDate,
    val events: List<String>,
    val checkin: Checkin?,
    val readiness: ReadinessLevel,
    val block: BlockState?,
    val returnState: ReturnState?,
    val activeInjuries: List<Injury>,
    val climbed: Climb?,
    val suggestion: SuggestionTrace?,
    val trained: Boolean,
    val skipped: Boolean,
    val sets: List<DoneSet>,
    val load: Map<LoadStructure, LoadSnapshot>,
    /** The athlete's true qualities at the end of the day, % body weight. */
    val truth: Map<Quality, Double>,
    /** Away (holiday or ill): nothing is opened or logged. */
    val away: Boolean,
    /** On Sundays: the week's volume per area after the day's training. */
    val weekEnd: List<com.cruxcoach.athlete.logic.AreaProgress>? = null,
    /** While the athlete follows a comeback plan: its day. */
    val comeback: ComebackTrace? = null,
    /** "Don't know it" taps of the day (issue #68). */
    val dontKnow: List<DontKnowTrace> = emptyList(),
    /** Where the athlete trained today had only this equipment (a trip); null = the persona's own. */
    val equipmentToday: Set<com.cruxcoach.athlete.catalog.EquipmentV2>? = null,
)

/** A whole run of one persona. */
data class SimRun(val persona: Persona, val days: List<DayTrace>, val catalog: com.cruxcoach.athlete.catalog.ExerciseCatalog,
    /** True 1RM or 10-s maximum against the app's value, per exercise, at the end of each week. */
    val convergence: List<Convergence>)

data class Convergence(val week: Int, val slug: String, val side: Side?, val trueKg: Double, val appKg: Double?)

/**
 * Runs one persona day by day through the real training service (issue #59): the same suggestion builder as
 * Today ([TodayCoach]), [SessionSuggester], [AthleteService.startSuggestion], every set through
 * [AthleteService.completeSetDetailed] and [AthleteService.adjustUpcomingSets] with the reserve answer as the
 * player sends it, and [AthleteService.finishWorkout]. Nothing of the coach's rules is copied here.
 */
class Simulator(
    private val service: AthleteService,
    private val clock: SimClock,
    private val persona: Persona,
    private val start: LocalDate = LocalDate.parse("2026-01-05"),
) {
    private val repo get() = service.repo
    private val rng = Random(persona.seed)
    private val athlete = SimAthlete(persona, Random(persona.seed * 7 + 1))
    private var comeback: SimComeback? = null

    private fun dateOf(day: Int) = start.plus(DatePeriod(days = day))

    private fun at(day: Int, hour: Int, minute: Int = 0) {
        val midnight = dateOf(day).atStartOfDayIn(service.zone).toEpochMilliseconds()
        clock.nowMs = midnight + (hour * 60L + minute) * 60_000L
    }

    fun run(): SimRun {
        setUp()
        val traces = mutableListOf<DayTrace>()
        val convergence = mutableListOf<Convergence>()
        for (day in 0 until persona.weeks * 7) {
            traces += day(day)
            if (day % 7 == 6) convergence += converge(day / 7 + 1, traces)
        }
        return SimRun(persona, traces, service.catalog, convergence)
    }

    private fun setUp() {
        at(0, 7)
        val target = persona.targetInDays?.let { dateOf(it).toString() }
        repo.updateProfile { p ->
            p.copy(
                units = persona.units, equipment = persona.equipment, equipmentConfigured = true,
                smallestIncrementKg = persona.smallestIncrementKg, weeklyGoal = persona.trainingDays.size.coerceAtLeast(1),
                sessionMinutes = persona.sessionMinutes, basicsAsked = true,
                coach = persona.coach.copy(targetDate = target, blockStartDay = null),
            )
        }
    }

    private fun weighIn(day: Int) {
        repo.saveMeasurement(BodyMeasurement(dateOf(day).toString(), "weight", athlete.bodyweightKg, "kg", clock.nowMs))
    }

    private fun day(day: Int): DayTrace {
        val date = dateOf(day)
        val events = mutableListOf<String>()
        athlete.dayPenalty = 0.0
        at(day, 7)
        if (day % 7 == 0) weighIn(day)

        val ill = persona.events.filterIsInstance<Event.Illness>().firstOrNull { day in it.day..it.until }
        val holiday = persona.events.filterIsInstance<Event.Holiday>().firstOrNull { day in it.day..it.until }
        val trip = persona.events.filterIsInstance<Event.Trip>().firstOrNull { day in it.day..it.until }
        persona.events.filterIsInstance<Event.Illness>().firstOrNull { it.day == day }?.let { e ->
            repo.savePause(PausePeriod("ill-$day", PauseReason.ILLNESS, date.toString(), dateOf(e.until).toString())); events += "ill"
        }
        persona.events.filterIsInstance<Event.Holiday>().firstOrNull { it.day == day }?.let { e ->
            repo.savePause(PausePeriod("away-$day", PauseReason.HOLIDAY, date.toString(), dateOf(e.until).toString())); events += "holiday"
        }
        persona.events.filterIsInstance<Event.Injury>().firstOrNull { it.day == day }?.let { e ->
            repo.saveInjury(Injury("injury-$day", e.region, side = e.side, severity = e.severity, climbingPaused = true,
                startedOn = date.toString(), updatedAt = clock.nowMs))
            if (e.comeback) {
                // Made before the setback, so the plan's truth knows the hand's maximum before the injury.
                comeback = SimComeback(service, clock, persona, athlete, "injury-$day", SimComeback.sideOf(e.side), e.severity,
                    Random(persona.seed * 13 + 5), e.habits).also { if (e.habits.startDelayDays == 0) it.start(date, e.finding) }
                if (e.habits.startDelayDays == 0) events += "comeback plan"
            }
            athlete.setback(Quality.FINGER, e.loss)
            events += "injury ${e.region.name.lowercase()}"
        }
        // A plan started later than the injury ([ComebackHabits.startDelayDays]), at its own stage.
        persona.events.filterIsInstance<Event.Injury>().firstOrNull { it.comeback && it.habits.startDelayDays > 0 && it.day + it.habits.startDelayDays == day }
            ?.let { e -> comeback?.start(date, e.finding); events += "comeback plan from stage ${e.habits.startStage}" }
        persona.events.filterIsInstance<Event.Injury>().firstOrNull { it.resolvedOn == day }?.let { e ->
            repo.injury("injury-${e.day}")?.let { repo.saveInjury(it.copy(resolvedOn = date.toString(), updatedAt = clock.nowMs)) }
            events += "injury over"
        }
        if (trip != null) {
            if (trip.day == day) events += "trip"
            service.pickPlace(TrainingPlaces.NO_GEAR_ID)
        }

        if (holiday != null) {
            athlete.night(day)
            comeback?.night()
            return DayTrace(day, date, events, null, ReadinessLevel.GO, null, null, repo.activeInjuries(), null, null,
                trained = false, skipped = false, sets = emptyList(), load = emptyMap(), truth = athlete.value.toMap(), away = true)
        }

        val checkin = if (persona.checkins) checkIn(day, ill != null) else null
        checkin?.let { repo.saveCheckin(it) }
        if (checkin != null && ((checkin.sleep ?: 5) <= 2 || (checkin.energy ?: 5) <= 2)) athlete.dayPenalty = 0.05
        if (ill != null) athlete.dayPenalty = 0.15

        // The comeback plan: the diary and the plan's prompts in the morning, its sessions when Today lists them.
        val cb = comeback?.takeIf { !it.ended }
        val (cbMorning, cbPrompts, cbReviews) = cb?.morning(date) ?: Triple(null to null, emptyList(), emptyList())
        val cbEnded = cb != null && cb.ended
        if (cbEnded) events += "comeback plan ended"
        val cbSessions = mutableListOf<ComebackSessionTrace>()
        fun comebackAt(hour: Int, strength: Boolean) {
            if (cb == null || cb.ended || ill != null) return
            at(day, hour)
            cbSessions += cb.sessions(day, strength)
        }
        comebackAt(9, strength = false)
        comebackAt(13, strength = false)
        comebackAt(16, strength = true)

        // Climbing first, in the afternoon (it is logged after the session); during a comeback plan as its stage allows.
        val climb = persona.climbing[date.dayOfWeek.isoDayNumber]?.takeIf { ill == null && repo.activeInjuries().none { it.climbingPaused } }
            ?.let { c -> if (cb != null && !cb.ended) cb.climb(c) else c }

        // Today in the evening: what the coach suggests (also on days the athlete does not train).
        at(day, 17)
        if (climb != null) { logClimb(day, climb); cb?.climbed(climb) }
        val today = service.today()
        service.usePlaceOfToday()
        val profile = repo.profile()
        val injuries = repo.activeInjuries()
        val readiness = ReadinessEvaluator.evaluate(checkin, injuries + Soreness.injuries(checkin))
        val activities = service.activities(days = 7 * 26)
        val loadStatus = runCatching { service.loadStatus() }.getOrNull()
        val coach = service.todayCoach()
        val block = coach.blockState(profile, loadStatus, today)
        val weekStart = ConsistencyStreak.weekStart(today)
        val weekly = runCatching { coach.weeklyProgress(profile, injuries, activities, block, today, weekStart) }.getOrDefault(emptyList())
        fun suggestion(choices: TodayCoach.Choices): Pair<SessionSuggestion, TodayCoach.Built> {
            val built = coach.build(profile, checkin, injuries, repo.pauses(), readiness, activities, block, loadStatus, weekly,
                weekStart, runCatching { service.logbookSummary() }.getOrNull(), today, choices)
            return SessionSuggester.suggest(built.input) to built
        }
        var choices = TodayCoach.Choices()
        var (shown, built) = suggestion(choices)
        service.logSuggestion(SuggestionEventKind.SHOWN, shown.focus.name, shown.routine.items.map { it.slug })
        val wantsToTrain = date.dayOfWeek.isoDayNumber in persona.trainingDays && ill == null
        val skipped = wantsToTrain && rng.nextDouble() < persona.skipShare
        if (wantsToTrain && !skipped && persona.alwaysEasy && rng.nextDouble() < 0.3 && shown.focus != SuggestionFocus.REST) {
            // "Too easy" on the card, the way the sheet sends it.
            service.logSuggestion(SuggestionEventKind.NEXT, shown.focus.name, shown.routine.items.map { it.slug }, SuggestionFeedback.TOO_EASY)
            choices = choices.copy(variant = 1, levelShift = 1)
            val again = suggestion(choices)
            shown = again.first; built = again.second
            events += "too easy"
        }
        // The first two weeks: "Don't know it" on every Special exercise new to the athlete (issue #68), the way the card sends it.
        val dontKnow = mutableListOf<DontKnowTrace>()
        if (wantsToTrain && !skipped && day < DONT_KNOW_DAYS) {
            for (item in shown.routine.items.toList()) {
                val def = service.catalog[item.slug] ?: continue
                if (def.familiarity != com.cruxcoach.athlete.catalog.Familiarity.SPECIAL || !SessionSuggester.isNewTo(built.input, item.slug)) continue
                val to = SessionSuggester.familiarSwap(built.input, shown.routine.items, shown.focus, item.slug) ?: continue
                val sinceMs = clock.nowMs - com.cruxcoach.athlete.logic.PreferenceLearning.WINDOW_DAYS * 86_400_000L
                fun learned(): Pair<Double, Boolean> {
                    val events = repo.suggestionEventsSince(sinceMs)
                    val affinity = com.cruxcoach.athlete.logic.PreferenceLearning.affinity(events, repo.completedSetsSince(sinceMs), today)
                    val resting = com.cruxcoach.athlete.logic.PreferenceLearning.resting(events, today.plus(DatePeriod(days = 1)))
                    return (affinity[item.slug] ?: 0.0) to (item.slug in resting)
                }
                val before = learned()
                service.logSuggestion(SuggestionEventKind.FEEDBACK, com.cruxcoach.athlete.logic.PreferenceLearning.DONT_KNOW, listOf(item.slug, to.slug))
                val after = learned()
                shown = SessionSuggester.withSwaps(shown, mapOf(item.slug to to.slug), service.catalog)
                dontKnow += DontKnowTrace(item.slug, to.slug, after.first - before.first, after.second && !before.second)
            }
            if (dontKnow.isNotEmpty()) events += "don't know: " + dontKnow.joinToString { "${it.from} → ${it.to}" }
        }
        // The card's other buttons (persona `picky`): "Another suggestion" first, then a swap from the exercise's alternatives.
        if (wantsToTrain && !skipped && persona.anotherShare > 0 && rng.nextDouble() < persona.anotherShare && shown.focus != SuggestionFocus.REST) {
            service.logSuggestion(SuggestionEventKind.NEXT, shown.focus.name, shown.routine.items.map { it.slug })
            choices = choices.copy(variant = choices.variant + 1)
            val again = suggestion(choices)
            shown = again.first; built = again.second
            events += "another suggestion"
        }
        if (wantsToTrain && !skipped && persona.swapShare > 0 && rng.nextDouble() < persona.swapShare) {
            val main = shown.routine.items.filter { !it.warmup }
            val from = main.getOrNull(rng.nextInt(main.size.coerceAtLeast(1)))?.slug
            val options = from?.let { SessionSuggester.alternatives(built.input, shown, it) }.orEmpty()
            if (from != null && options.isNotEmpty()) {
                val to = options[rng.nextInt(options.size)].def.slug
                service.logSuggestion(SuggestionEventKind.SWAPPED, shown.focus.name, listOf(from, to))
                shown = SessionSuggester.withSwaps(shown, mapOf(from to to), service.catalog)
                events += "swap: $from → $to"
            }
        }
        val returnState = built.input.returnState
        val trace = SuggestionTrace(shown.focus, shown.reasons, shown.routine.items.map {
            SuggestedItem(it.slug, it.sets, it.repsMax ?: it.repsMin, it.durationS, it.loadKg, it.warmup, it.edgeMm)
        }, shown.totalMinutes, shown.addOn, shown.boardPlan != null, shown.shortenedForFatigue, choices.levelShift)

        var trained = false
        var done = emptyList<DoneSet>()
        val takes = (wantsToTrain && !skipped) || (climb != null && shown.addOn && rng.nextDouble() < 0.5)
        if (takes && shown.focus != SuggestionFocus.REST && shown.routine.items.isNotEmpty() && shown.boardPlan == null) {
            at(day, 18)
            done = train(day, shown)
            trained = done.any { it.setType != SetType.WARMUP }
        } else if (takes && shown.boardPlan != null && climb == null && ill == null) {
            // A board day suggested on a training day: the athlete climbs it.
            val plan = shown.boardPlan!!
            val c = Climb(ClimbingDayKind.OTHER_BOARD, plan.minutes, ClimbIntensity.HARD)
            logClimb(day, c)
            cb?.climbed(c)
            events += "board as suggested"
        }
        comebackAt(21, strength = true)
        athlete.night(day)
        comeback?.night()
        val weekEnd = if (date.dayOfWeek == kotlinx.datetime.DayOfWeek.SUNDAY) runCatching {
            coach.weeklyProgress(repo.profile(), repo.activeInjuries(), service.activities(days = 8), block, today, weekStart)
        }.getOrNull() else null
        val load = runCatching { service.loadStatus() }.getOrNull()?.structures?.mapValues { (_, s) ->
            LoadSnapshot(s.acute7, s.chronic28, s.trend)
        }.orEmpty()
        return DayTrace(day, date, events, checkin, readiness.level, block, returnState, injuries, climb, trace, trained, skipped,
            done, load, athlete.value.toMap(), away = false, weekEnd = weekEnd,
            comeback = cb?.trace(cbPrompts, cbReviews, cbMorning, cbSessions, cbEnded),
            dontKnow = dontKnow, equipmentToday = trip?.equipment)
    }

    private fun checkIn(day: Int, ill: Boolean): Checkin {
        val bad = rng.nextDouble() < 0.08
        return Checkin(
            day = dateOf(day).toString(), createdAt = clock.nowMs,
            sleep = if (bad) 2 else 3 + rng.nextInt(3), energy = if (ill) 1 else if (bad) 2 else 3 + rng.nextInt(3),
            skin = 4, fingers = if (rng.nextDouble() < 0.05) 2 else 4, motivation = 4, sick = ill,
        )
    }

    private fun logClimb(day: Int, climb: Climb) {
        service.saveClimbingDay(ClimbingDayEntry("climb-$day", dateOf(day).toString(), climb.kind, climb.minutes, climb.intensity,
            updatedAt = clock.nowMs))
        athlete.climbed(day, when (climb.intensity) {
            ClimbIntensity.LIGHT -> 0.2
            ClimbIntensity.VOLUME -> 0.5
            ClimbIntensity.HARD -> 0.8
            ClimbIntensity.LIMIT -> 1.0
        })
    }

    /** One training of the suggestion, set by set in the player's order. */
    private fun train(day: Int, suggestion: SessionSuggestion): List<DoneSet> {
        val id = service.startSuggestion(suggestion)
        val log = mutableListOf<DoneSet>()
        val hard = mutableMapOf<Quality, Int>()
        var guard = 0
        while (guard++ < 400) {
            val sets = repo.setsFor(id)
            val next = PlayerQueue.current(sets) ?: break
            val def = service.catalog.fallbackFor(next.exerciseSlug)
            // Far too heavy (not even half the plan): the athlete lowers the load by hand, the player carries it on.
            val planned = lowerIfFarTooHeavy(id, def, next, sets)
            val result = athlete.perform(def, planned, persona.alwaysEasy, persona.rirNoise)
            val performed = planned.copy(reps = result.reps ?: planned.reps, durationS = result.durationS ?: planned.durationS)
            service.completeSetDetailed(performed, startRest = false)
            val saved = repo.setsFor(id).firstOrNull { it.id == next.id } ?: break
            val first = runCatching { service.adjustUpcomingSets(id, saved, null) }.getOrNull()
            var final = saved
            if (result.reportedRir != null && saved.setType != SetType.WARMUP) {
                final = saved.copy(rir = result.reportedRir)
                service.updateSet(final)
                runCatching { service.adjustUpcomingSets(id, final, first) }
            }
            if (saved.setType != SetType.WARMUP && def.kind != ExerciseKind.CLIMB) {
                athlete.tire(def, result.trueReserve)
                if (result.trueReserve <= 4.0) hard.merge(athlete.qualityOf(def), 1, Int::plus)
            }
            log += DoneSet(final.exerciseSlug, final.setType, final.side, final.setIndex, final.targetLoadKg, final.loadKg,
                final.targetReps, final.reps, final.targetDurationS, final.durationS, final.edgeMm, final.rir, result.trueReserve)
            clock.nowMs += ((saved.restS ?: 90) + 40) * 1000L
        }
        service.finishWorkout(id, sessionRpe = 7, notes = null)
        athlete.adapt(day, hard)
        return log
    }

    /**
     * The athlete's own correction when a planned load is far beyond them: the load they believe gives the
     * planned reps or seconds with two in reserve (their estimate is off by up to 10 %), rounded to their
     * weight step. Through [AthleteService.updateSet] and the player's carry-over to the sets after it.
     */
    private fun lowerIfFarTooHeavy(workoutId: String, def: ExerciseDefinition, set: ExerciseSet, sets: List<ExerciseSet>): ExerciseSet {
        val planned = set.loadKg
        if (set.setType != SetType.WORK || planned == null) return set
        if (def.load != com.cruxcoach.athlete.catalog.LoadMode.BODYWEIGHT_PLUS && def.load != com.cruxcoach.athlete.catalog.LoadMode.EXTERNAL) return set
        if (def.kind != ExerciseKind.LOAD_REPS && def.kind != ExerciseKind.HANG) return set
        val probe = athlete.perform(def, set, alwaysEasy = false, noise = 0.0)
        val target = if (def.kind == ExerciseKind.LOAD_REPS) (set.targetReps ?: 8).toDouble() else set.targetDurationS ?: 10.0
        val achieved = if (def.kind == ExerciseKind.LOAD_REPS) (probe.reps ?: 0).toDouble() else probe.durationS ?: 0.0
        if (achieved >= target / 2) return set
        val guess = athlete.trueMaxKg(def, set.edgeMm) * (0.9 + rng.nextDouble() * 0.2)
        val total = if (def.kind == ExerciseKind.LOAD_REPS) guess * (37.0 - target - 2) / 36.0
            else guess * ((target + 2) / 10.0).let { Math.pow(it, -0.12) }
        val logged = when (def.load) {
            com.cruxcoach.athlete.catalog.LoadMode.BODYWEIGHT_PLUS -> total - athlete.bodyweightKg
            else -> com.cruxcoach.athlete.logic.LoadRules.logged(def, total.coerceAtLeast(0.0))
        }
        val step = persona.smallestIncrementKg
        val load = (Math.floor(logged / step) * step).let { if (def.load == com.cruxcoach.athlete.catalog.LoadMode.EXTERNAL) it.coerceAtLeast(0.0) else it }
        if (load >= planned) return set
        val after = set.copy(loadKg = load)
        service.updateSet(after)
        com.cruxcoach.android.ui.training.workout.carriedLoads(set, after, sets, def).forEach { service.updateSet(it) }
        lowered++
        return after
    }

    /** How often the athlete lowered a planned load by hand. */
    var lowered = 0
        private set

    /** End of a week: the true maximum against the app's value for every loaded exercise trained so far. */
    private fun converge(week: Int, traces: List<DayTrace>): List<Convergence> {
        val trained = traces.flatMap { it.sets }.filter { it.setType == SetType.WORK }.map { it.slug to it.side }.distinct()
        return trained.mapNotNull { (slug, side) ->
            val def = service.catalog[slug] ?: return@mapNotNull null
            if (def.kind != ExerciseKind.LOAD_REPS && def.kind != ExerciseKind.HANG) return@mapNotNull null
            if (def.load != com.cruxcoach.athlete.catalog.LoadMode.BODYWEIGHT_PLUS && def.load != com.cruxcoach.athlete.catalog.LoadMode.EXTERNAL) return@mapNotNull null
            val edge = traces.flatMap { it.sets }.lastOrNull { it.slug == slug && it.side == side }?.edgeMm
            val app = runCatching { service.capacityFor(def, side, edge, null, athlete.bodyweightKg) }.getOrNull()
            Convergence(week, slug, side, athlete.trueMaxKg(def, edge), app?.value)
        }
    }
}

/** Days at the start in which the athlete taps "Don't know it" on every Special exercise new to them (issue #68). */
const val DONT_KNOW_DAYS = 14

/** The simulation's clock; the repository and the service read it. */
class SimClock(var nowMs: Long = 0L)
