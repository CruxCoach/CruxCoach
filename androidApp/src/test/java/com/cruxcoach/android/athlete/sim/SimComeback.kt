package com.cruxcoach.android.athlete.sim

import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.android.athlete.ComebackService
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.logic.ClimbingLevel
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackContent
import com.cruxcoach.athlete.logic.DueStatus
import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.logic.PromptKind
import com.cruxcoach.athlete.logic.Proposal
import com.cruxcoach.athlete.logic.SideTarget
import com.cruxcoach.athlete.logic.StayReason
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.ProfessionalCheck
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.StageChangeReason
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The injured tissue (issue #59), apart from the athlete's strength: how much load the injured fingers take
 * without pain, how irritated they are, and what the athlete feels. Deliberately not the comeback plan's rules.
 *
 * - **Tolerance**, as a share of the hand's maximum before the injury: 15 % on the day of the injury, rising
 *   evenly to 100 % over seven weeks of healing (pulley strain: easy climbing after about four weeks, full after
 *   six to eight; Schöffl et al. 2003); other findings and regions over their own [healDays]
 *   ([SimComeback.healDays]). A session that loads the hand within tolerance speeds healing a little; a session
 *   that hurts (6 or more) and climbing above tolerance set it back.
 * - **Pain** in a set: none up to 70 % of the tolerance, then one point per 10 % more, plus 0.4 × the irritation.
 * - **Irritation** starts at the injury's pain and fades by 7 % a day; painful sessions and climbing above
 *   tolerance add to it. Morning pain reads the irritation; pressure pain adds what has not healed yet.
 */
class SimInjury(severity: Int, private val rng: Random, private val healDays: Double = HEAL_DAYS) {
    private var healed = 0.0
    var irritation = severity.toDouble()
        private set

    fun tolerance(): Double = 0.15 + 0.85 * (healed / healDays).coerceIn(0.0, 1.0)

    /** Pain 0–10 of a set at [rel] of the hand's maximum before the injury (for the set's length). */
    fun pain(rel: Double): Int =
        (max(0.0, (rel / tolerance() - 0.7) * 10) + irritation * 0.4 + rng.gaussian() * 0.5).roundToInt().coerceIn(0, 10)

    /** After a session that hurt [pain] at most; [loadedHand] when it put real load on the injured hand. */
    fun loaded(pain: Int, loadedHand: Boolean) {
        if (loadedHand && pain <= 3) healed += 0.3
        if (pain >= 6) { healed = max(0.0, healed - 2.0); irritation += (pain - 5) * 0.5 }
    }

    fun climbed(rel: Double) {
        val over = rel - tolerance()
        if (over > 0) { irritation += over * 5; healed = max(0.0, healed - 1.5) }
    }

    fun night() { healed += 1.0; irritation *= 0.93 }

    fun morningPain(): Int = (irritation + rng.gaussian() * 0.5).roundToInt().coerceIn(0, 10)
    fun pressurePain(): Int = (irritation * 0.8 + (1 - tolerance()) * 3 + rng.gaussian() * 0.5).roundToInt().coerceIn(0, 10)

    companion object {
        const val HEAL_DAYS = 49.0

        /** Share of the injured hand's maximum a climbing day asks for. */
        fun climbingShare(i: ClimbIntensity): Double = when (i) {
            ClimbIntensity.LIGHT -> 0.3
            ClimbIntensity.VOLUME -> 0.45
            ClimbIntensity.HARD -> 0.7
            ClimbIntensity.LIMIT -> 0.9
        }
    }
}

/** A comeback set as the plan planned it and the athlete did it. */
data class ComebackSetTrace(
    val slug: String, val side: Side?, val injured: Boolean, val plannedKg: Double?, val doneKg: Double?,
    val targetDurationS: Double?, val durationS: Double?,
    /** The stage's range for the item from the athlete's reference, in kg (null: by effort). */
    val rangeKg: ClosedFloatingPointRange<Double>?,
    /** How hard the item should feel, out of 10, when the stage says so. */
    val effort: IntRange?,
    /** How hard the planned load would have felt (truth: its share of what the hand holds today for the set's length). */
    val plannedEffort: Double?,
    /** Share of the hand's maximum before the injury (injured hand only). */
    val rel: Double?, val pain: Int,
)

data class ComebackSessionTrace(val kind: ComebackSessionKind, val stage: Int, val atMs: Long, val sets: List<ComebackSetTrace>, val painMax: Int)

/** The comeback plan on one day: what the athlete logged, was shown and did, and the truth behind it. */
data class ComebackTrace(
    val stage: Int,
    val climbing: ClimbingLevel?,
    val tolerance: Double,
    val irritation: Double,
    val morningPain: Int?,
    val pressurePain: Int?,
    /** Prompts shown today and the athlete's answer, e.g. "review move_up: moved up". */
    val prompts: List<String>,
    /** Proposals of the week's look back shown today. */
    val reviews: List<Proposal>,
    val sessions: List<ComebackSessionTrace>,
    /** The athlete ended the plan today (injury over). */
    val ended: Boolean,
)

/**
 * The athlete who follows the app's comeback plan (issue #59), through the same service calls as the comeback
 * screen and Today's card: [ComebackService.start], the diary, the plan's prompts (answered with the button the
 * screen offers), [ComebackService.startSession] for every session Today lists as due, each set through
 * [AthleteService.completeSetDetailed], [AthleteService.finishWorkout] and [ComebackService.logSession].
 *
 * The athlete is careful and honest: they log the morning pain on most days and pressure pain twice a week,
 * report the true highest pain of each session, take the loads the plan shows (the stage's range when it has
 * one, otherwise the planned load and their sense of effort), go one step lighter after a session above the
 * green zone and one step heavier at most once a week when it stayed green, and accept every proposal.
 */
class SimComeback(
    private val service: AthleteService,
    private val clock: SimClock,
    private val persona: Persona,
    private val athlete: SimAthlete,
    val injuryId: String,
    private val side: Side,
    severity: Int,
    private val rng: Random,
    /** How closely the athlete follows the plan; the defaults are the careful athlete described above. */
    private val habits: ComebackHabits = ComebackHabits(),
    /** A finger plan (loads from the athlete's references); else a region plan, done by effort. */
    private val fingers: Boolean = true,
    healDays: Double = SimInjury.HEAL_DAYS,
    /** Share of a climbing day's load that reaches the injured spot (less for the legs than for the hands). */
    private val climbingReach: Double = 1.0,
) {
    private val comebacks = ComebackService(service)
    private val repo get() = service.repo
    val truth = SimInjury(severity, rng, healDays)
    /** The injured hand's 10-second maximum before the injury, per exercise (total kg on a 20 mm edge). */
    private val before = FINGER_SLUGS.associateWith { athlete.trueMaxKg(service.catalog.fallbackFor(it), 20.0) }
    private val lastPain = mutableMapOf<ComebackSessionKind, Int>()
    private val lastRaise = mutableMapOf<Pair<String, Side?>, Int>()
    var ended = false
        private set
    /** Due sessions the athlete leaves out today ([ComebackHabits.skipShare]), by their index in the stage. */
    private val skipped = mutableMapOf<Int, MutableSet<Int>>()

    /**
     * The doctor's finding, then the plan with the athlete's own references: on the injury's day from the first stage,
     * or later from [ComebackHabits.startStage].
     */
    fun start(date: LocalDate, finding: com.cruxcoach.athlete.model.InjuryFinding?) {
        comebacks.saveCheck(injuryId, InjuryCheck(checked = ProfessionalCheck.EXAMINED, finding = finding, checkedOn = date.toString()))
        val injury = repo.injury(injuryId) ?: return
        // The app offers the athlete's logged bests; otherwise they enter what they know (within 10 %).
        val offered = comebacks.suggestedReferences(injury).mapValues { it.value.first }
        val own = mapOf(Grip.HALF_CRIMP.name to before.getValue(HALF), Grip.OPEN_HAND.name to before.getValue(OPEN))
            .mapValues { (_, kg) -> floor(kg * (0.9 + rng.nextDouble() * 0.2)) }
        comebacks.start(injuryId, PlanSource.CRUXCOACH, stage = habits.startStage, agreed = true, injuredDominant = true,
            referenceKg = if (fingers) own + offered else offered)
    }

    private fun injury() = repo.injury(injuryId)
    private fun plan() = injury()?.activePlan

    /** The stage's climbing while the plan runs (null when it has ended). */
    fun climbingLevel(): ClimbingLevel? = injury()?.let { Comeback.climbingLevel(it) }

    /**
     * What the athlete climbs on a planned climbing day: no more than the stage allows, or one level more on some
     * days ([ComebackHabits.climbsAhead]).
     */
    fun climb(planned: Climb): Climb? {
        val level = climbingLevel() ?: return planned
        val ahead = habits.climbsAhead > 0 && rng.nextDouble() < habits.climbsAhead
        val climbed = if (ahead) ClimbingLevel.entries.getOrElse(level.ordinal + 1) { level } else level
        return when (climbed) {
            ClimbingLevel.FULL -> planned
            ClimbingLevel.NONE -> null
            ClimbingLevel.EASY -> planned.copy(intensity = ClimbIntensity.LIGHT, minutes = minOf(planned.minutes, 60))
            ClimbingLevel.MODERATE -> planned.copy(intensity = minOf(planned.intensity, ClimbIntensity.VOLUME))
            ClimbingLevel.HARD -> planned.copy(intensity = minOf(planned.intensity, ClimbIntensity.HARD))
        }
    }

    fun climbed(c: Climb) = truth.climbed(SimInjury.climbingShare(c.intensity) * climbingReach)

    /** Morning: the diary, then the plan's prompts. Returns (morning pain, pressure pain, prompts, reviews). */
    fun morning(date: LocalDate): Triple<Pair<Int?, Int?>, List<String>, List<Proposal>> {
        val plan = plan() ?: return Triple(null to null, emptyList(), emptyList())
        val morning = truth.morningPain().takeIf { rng.nextDouble() < 0.9 }
        val pressure = truth.pressurePain().takeIf { date.dayOfWeek.isoDayNumber == 1 || date.dayOfWeek.isoDayNumber == 4 }
        if (morning != null || pressure != null) {
            comebacks.saveEntry(comebacks.entry(injuryId).let { e -> e.copy(morningPain = morning ?: e.morningPain, pressurePain = pressure ?: e.pressurePain) })
        }
        val shown = mutableListOf<String>()
        val reviews = mutableListOf<Proposal>()
        val injury = injury() ?: return Triple(morning to pressure, shown, reviews)
        val prompts = Comeback.prompts(injury, repo.comebackEntries(injuryId), service.today())
        // One answer a day, the most important prompt first; the others wait for tomorrow, as on the screen.
        prompts.firstOrNull()?.let { p ->
            val stage = plan.stage
            val answer = when (p.kind) {
                PromptKind.REVIEW -> {
                    val r = p.review!!
                    reviews += r.proposal
                    when (r.proposal) {
                        Proposal.MOVE_UP -> { comebacks.markPrompt(injuryId, p.key); comebacks.moveTo(injuryId, stage + 1, StageChangeReason.MOVE_UP); "moved up to ${stage + 1}" }
                        Proposal.REPEAT -> { comebacks.markPrompt(injuryId, p.key); comebacks.moveTo(injuryId, stage, StageChangeReason.REPEAT); "repeats the week" }
                        Proposal.STEP_BACK -> if (habits.ignoresStepBack) {
                            comebacks.markPrompt(injuryId, p.key); "declines, keeps the stage"
                        } else if (stage > 0) {
                            comebacks.markPrompt(injuryId, p.key); comebacks.moveTo(injuryId, stage - 1, StageChangeReason.STEP_BACK); "stepped back to ${stage - 1}"
                        } else { comebacks.markPrompt(injuryId, p.key); "keeps the stage" }
                        Proposal.STAY -> {
                            comebacks.markPrompt(injuryId, p.key)
                            if (r.stayReason == StayReason.LAST_STAGE) {
                                // The last stage's week went well: the athlete ends the plan, the injury is over.
                                comebacks.end(injuryId, resolved = true); ended = true; "ends the plan"
                            } else "ok"
                        }
                    }.let { "review ${r.proposal.name.lowercase()}: $it" }
                }
                PromptKind.WORSENING -> if (habits.ignoresStepBack) {
                    comebacks.markPrompt(injuryId, p.key); "worse: declines, keeps the stage"
                } else if (stage > 0) {
                    comebacks.markPrompt(injuryId, p.key); comebacks.moveTo(injuryId, stage - 1, StageChangeReason.STEP_BACK); "worse: stepped back to ${stage - 1}"
                } else { comebacks.markPrompt(injuryId, p.key); "worse: keeps the stage" }
                else -> { comebacks.markPrompt(injuryId, p.key); "${p.kind.name.lowercase()}: ok" }
            }
            shown += answer
        }
        return Triple(morning to pressure, shown, reviews)
    }

    /**
     * The sessions Today lists as due at this hour (the strength session only when [strength]), each done
     * as a training. Not on sick days.
     */
    fun sessions(day: Int, strength: Boolean): List<ComebackSessionTrace> {
        val out = mutableListOf<ComebackSessionTrace>()
        repeat(3) {
            val injury = injury() ?: return out
            if (injury.activePlan == null) return out
            val leftOut = skipped.getOrPut(day) { mutableSetOf() }
            val due = Comeback.due(injury, repo.comebackEntries(injuryId), service.today(), service.now())
                .filter { it.status == DueStatus.DUE && it.spec.items.isNotEmpty() && !it.spec.optional && it.index !in leftOut }
                .firstOrNull { strength || it.spec.kind != ComebackSessionKind.STRENGTH } ?: return out
            if (habits.skipShare > 0 && rng.nextDouble() < habits.skipShare) { leftOut += due.index; return@repeat }
            out += session(day, due.index, due.spec.kind)
        }
        return out
    }

    private fun session(day: Int, index: Int, kind: ComebackSessionKind): ComebackSessionTrace {
        val injury = injury()!!
        val plan = injury.activePlan!!
        val stage = Comeback.stage(injury, plan)
        val spec = stage.sessions[index]
        val rule = Comeback.painRule(injury, stage)
        val started = clock.nowMs
        val id = comebacks.startSession(injuryId, index, "Comeback")!!
        val traces = mutableListOf<ComebackSetTrace>()
        val chosen = mutableSetOf<Pair<String, Side?>>()
        var guard = 0
        while (guard++ < 200) {
            val sets = repo.setsFor(id)
            val next = PlayerQueue.current(sets) ?: break
            val def = service.catalog.fallbackFor(next.exerciseSlug)
            // An injury without a side (the back) gives its items no side.
            val item = spec.items.firstOrNull { it.slug == next.exerciseSlug && sideOf(it.side) == next.side }
                ?: spec.items.firstOrNull { it.slug == next.exerciseSlug && next.side == null }
            // A region plan's items load the injured spot unless they are for the healthy side only.
            val injured = if (fingers) next.side == side && next.exerciseSlug in FINGER_SLUGS
                else item != null && item.side != SideTarget.HEALTHY
            val range = item?.let { range(plan.referenceKg, it) }
            var set = next
            val key = next.exerciseSlug to next.side
            if (next.setType == SetType.WORK && next.exerciseSlug in FINGER_SLUGS && chosen.add(key)) {
                val load = chooseLoad(day, key, next.loadKg, range, item?.effort, injured, def, next.edgeMm, next.targetDurationS, kind, rule.greenMax)
                if (load != null && load != next.loadKg) {
                    set = next.copy(loadKg = load)
                    service.updateSet(set)
                    com.cruxcoach.android.ui.training.workout.carriedLoads(next, set, sets, def).forEach { service.updateSet(it) }
                }
            }
            val result = athlete.perform(def, set, alwaysEasy = false, noise = persona.rirNoise)
            val performed = set.copy(reps = result.reps ?: set.reps, durationS = result.durationS ?: set.durationS)
            service.completeSetDetailed(performed, startRest = false)
            val saved = repo.setsFor(id).firstOrNull { it.id == next.id } ?: break
            runCatching { service.adjustUpcomingSets(id, saved, null) }
            if (result.reportedRir != null && saved.setType != SetType.WARMUP) service.updateSet(saved.copy(rir = result.reportedRir))
            val rel = if (!injured) null else if (fingers) relative(def, set.loadKg, set.edgeMm, set.targetDurationS) else regionShare(item!!, kind)
            val pain = if (rel != null) truth.pain(rel) else truth.pain(0.0)
            val planned = next.targetLoadKg ?: next.loadKg
            traces += ComebackSetTrace(next.exerciseSlug, next.side, injured, planned, set.loadKg,
                set.targetDurationS, performed.durationS, range, item?.effort,
                if (fingers && injured && planned != null) felt(def, planned, next.edgeMm, next.targetDurationS) else null, rel, pain)
            clock.nowMs += ((saved.restS ?: 60) + (performed.durationS ?: 20.0).toLong()) * 1000L
        }
        service.finishWorkout(id, sessionRpe = 3, notes = null)
        val painMax = traces.maxOfOrNull { it.pain } ?: 0
        comebacks.logSession(id, painMax)
        truth.loaded(painMax, loadedHand = traces.any { it.injured && (it.rel ?: 0.0) >= 0.15 })
        lastPain[kind] = painMax
        return ComebackSessionTrace(kind, plan.stage, started, traces, painMax)
    }

    /**
     * The load the athlete takes for an item's first work set; the player carries it to the sets after it. The
     * stage's range when it has one; else the planned load, raised toward the effort the stage asks for.
     */
    private fun chooseLoad(
        day: Int, key: Pair<String, Side?>, planned: Double?, range: ClosedFloatingPointRange<Double>?, effort: IntRange?,
        injured: Boolean, def: com.cruxcoach.athlete.catalog.ExerciseDefinition, edgeMm: Double?, seconds: Double?,
        kind: ComebackSessionKind, greenMax: Int,
    ): Double? {
        val step = persona.smallestIncrementKg
        var load = planned ?: range?.start ?: Comeback.START_KG
        if (!injured) return range?.let { load.coerceIn(it) } ?: load
        val hurt = (lastPain[kind] ?: 0) > greenMax
        val mayRaise = !hurt && day - (lastRaise[key] ?: -99) >= 6
        when {
            hurt -> load = max(0.0, load - step)
            // Below the stage's range: toward it, a fifth more per session at most.
            range != null && load < range.start -> { load = minOf(range.start, maxOf(load + step, floor(load * 1.2 / step) * step)); lastRaise[key] = day }
            range != null -> if (mayRaise && load + step <= range.endInclusive + 1e-9) { load += step; lastRaise[key] = day }
            effort != null -> {
                val felt = felt(def, load, edgeMm, seconds)
                if (felt > effort.last + 1) load = max(0.0, floor(effort.last / felt * load / step) * step)
                else if (felt < effort.first && mayRaise) { load += step; lastRaise[key] = day }
            }
        }
        return load
    }

    /** Felt effort out of 10: the share of what the hand can hold today for the set's length. */
    private fun felt(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, loadKg: Double, edgeMm: Double?, seconds: Double?): Double {
        val today = athlete.trueMaxKg(def, edgeMm) * ((seconds ?: 10.0) / 10.0).pow(-0.12)
        return 10 * athlete.totalKg(def, loadKg) / today
    }

    /** The share of the injured hand's maximum before the injury that a set asks for (longer holds ask more). */
    private fun relative(def: com.cruxcoach.athlete.catalog.ExerciseDefinition, loadKg: Double?, edgeMm: Double?, seconds: Double?): Double? {
        if (def.kind != ExerciseKind.HANG) return null
        val max10 = before[def.slug] ?: return null
        val edge = ((edgeMm ?: 20.0).coerceIn(6.0, 45.0) / 20.0).pow(0.5)
        return athlete.totalKg(def, loadKg) / (max10 * edge) * ((seconds ?: 10.0) / 10.0).pow(0.12)
    }

    /**
     * A region plan's set (no references, done by effort): the share of the region's maximum before the injury it asks
     * for. The athlete works at the effort the item asks for (holds about 4–5 of 10, 15 repetitions about 5, fewer
     * about 7, mobility 2) of what the region can do today, which is a little more than it takes without pain.
     */
    private fun regionShare(item: com.cruxcoach.athlete.logic.SessionItemSpec, kind: ComebackSessionKind): Double {
        val effort = if (kind == ComebackSessionKind.MOBILITY) 0.2
            else item.effort?.let { (it.first + it.last) / 20.0 } ?: if ((item.repsMax ?: item.repsMin ?: 0) >= 12) 0.5 else 0.7
        return effort * minOf(1.0, truth.tolerance() + 0.25)
    }

    private fun sideOf(t: SideTarget): Side? = when (t) {
        SideTarget.BOTH -> null
        SideTarget.INJURED -> side
        SideTarget.HEALTHY -> if (side == Side.LEFT) Side.RIGHT else Side.LEFT
    }

    fun night() = truth.night()

    fun trace(prompts: List<String>, reviews: List<Proposal>, morning: Pair<Int?, Int?>, sessions: List<ComebackSessionTrace>, endedToday: Boolean): ComebackTrace? {
        val injury = injury() ?: return null
        val plan = injury.plan ?: return null
        if (!plan.isActive && !endedToday) return null
        return ComebackTrace(plan.stage, Comeback.climbingLevel(injury), truth.tolerance(), truth.irritation, morning.first, morning.second,
            prompts, reviews, sessions, endedToday)
    }

    companion object {
        const val HALF = "finger.one_arm_pickup"
        const val OPEN = "finger.open_hand_block_lift"
        val FINGER_SLUGS = setOf(HALF, OPEN)

        /** The stage's range of an item from the references the athlete gave (the stage's own percentages). */
        fun range(references: Map<String, Double>, item: com.cruxcoach.athlete.logic.SessionItemSpec): ClosedFloatingPointRange<Double>? {
            val reference = item.grip?.let { references[it.name] } ?: return null
            val percent = item.loadPercent ?: return null
            return reference * percent.first / 100.0..reference * percent.last / 100.0
        }

        fun sideOf(s: InjurySide?): Side = if (s == InjurySide.LEFT) Side.LEFT else Side.RIGHT

        /** The ladder a persona's injury follows. */
        fun ladderFor(e: Event.Injury) = ComebackContent.ladderFor(e.region, e.finding)

        /**
         * Days until the injured tissue takes its full load again (the simulation's truth, not the plan's rule): rough
         * mid values of the usual healing times, longer for a rupture or several pulleys and for tendon problems.
         */
        fun healDays(e: Event.Injury): Double = when (e.finding) {
            com.cruxcoach.athlete.model.InjuryFinding.PULLEY_RUPTURE -> 70.0
            com.cruxcoach.athlete.model.InjuryFinding.PULLEY_MULTIPLE -> 84.0
            com.cruxcoach.athlete.model.InjuryFinding.TENOSYNOVITIS -> 42.0
            com.cruxcoach.athlete.model.InjuryFinding.MEDIAL_ELBOW, com.cruxcoach.athlete.model.InjuryFinding.LATERAL_ELBOW -> 84.0
            else -> when (e.region) {
                com.cruxcoach.athlete.model.InjuryRegion.FINGER -> SimInjury.HEAL_DAYS
                com.cruxcoach.athlete.model.InjuryRegion.ANKLE, com.cruxcoach.athlete.model.InjuryRegion.HIP,
                com.cruxcoach.athlete.model.InjuryRegion.BACK -> 42.0
                else -> 56.0
            }
        }
    }
}

private fun Random.gaussian(): Double {
    val u1 = nextDouble().coerceAtLeast(1e-12)
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
}
