package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.ComebackPlan
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.ProfessionalCheck
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.SideMode
import com.cruxcoach.athlete.model.StageChange
import com.cruxcoach.athlete.model.StageChangeReason
import com.cruxcoach.athlete.model.Urgency
import com.cruxcoach.athlete.model.WarningSign
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.roundToInt

/** How a diary day looks against the stage's pain rule. */
enum class DayColor { GREEN, AMBER, RED }

enum class PainTrend { FALLING, FLAT, RISING }

/** What the weekly look back suggests; the athlete decides. */
enum class Proposal { MOVE_UP, STAY, REPEAT, STEP_BACK }

/** Why a look back suggests staying. */
enum class StayReason { TOO_EARLY, FEW_ENTRIES, PRESSURE_RISING, LAST_STAGE }

data class DayReading(
    val day: LocalDate,
    val color: DayColor?,
    val sessionPain: Int?,
    val morningPain: Int?,
    /** Morning pain minus the median of the mornings before it. */
    val morningRise: Int?,
    val pressurePain: Int?,
)

data class Review(
    val proposal: Proposal,
    val daysOnStage: Int,
    val green: Int,
    val amber: Int,
    val red: Int,
    val loggedDays: Int,
    /** The day with the highest session pain of the week and that pain, for "on Tuesday you logged 4 of 10". */
    val worst: Pair<LocalDate, Int>?,
    val pressureTrend: PainTrend?,
    /** A week on the stage is complete: the look back is due. */
    val due: Boolean,
    /** Set when [proposal] is [Proposal.STAY]. */
    val stayReason: StayReason? = null,
)

enum class PromptKind {
    /** A week on the stage: "Ready for the next stage?" / "Stay at this stage for now". */
    REVIEW,
    /** Morning pain rose by two or more within three days. */
    WORSENING,
    /** Two weeks of diary without the pain going down. */
    NOT_BETTER_14,
    /** Three weeks of diary without the pain going down: firmer. */
    NOT_BETTER_21,
    /** A new warning sign was ticked in the diary. */
    NEW_SIGN,
}

data class Prompt(val kind: PromptKind, val key: String, val review: Review? = null, val from: Int? = null, val to: Int? = null)

enum class DueStatus { DUE, DONE, LATER, NOT_TODAY }

data class DueSession(
    val index: Int,
    val spec: SessionSpec,
    val doneToday: Int,
    val status: DueStatus,
    /** When the next one fits (epoch ms), for LATER and NOT_TODAY. */
    val nextAt: Long? = null,
)

/**
 * The comeback plan's rules (research/loading-principles.md "Rules an app can encode", adapted to the owner's
 * principle "nur in Absprache mit Arzt"): everything here reads the athlete's own entries against the stage the
 * athlete chose and suggests – a stage only changes when the athlete taps it ([moveTo]).
 */
object Comeback {

    /** Start load in kg for the injured hand when nothing is known yet ("2–5 kg", fingers.md §4.6). */
    const val START_KG = 5.0
    const val MIN_DAYS_ON_STAGE = 7
    /** Days a dismissed prompt stays quiet (research/safety-regulation.md §5 SHOULD). */
    const val PROMPT_QUIET_DAYS = 7
    const val ROUTINE_PREFIX = "comeback:"

    fun ladder(injury: Injury): Ladder = ComebackContent.ladderFor(injury.region, injury.check?.finding)

    fun stage(injury: Injury): StageSpec = injury.plan?.let { stage(injury, it) } ?: ladder(injury).stages.first()

    fun stage(injury: Injury, plan: ComebackPlan): StageSpec =
        ladder(injury).stages.let { it[plan.stage.coerceIn(0, it.lastIndex)] }

    /** The stage's rule, adjusted for what a professional told the athlete. */
    fun painRule(injury: Injury, stage: StageSpec = stage(injury)): PainRule = when (injury.check?.finding) {
        InjuryFinding.TENOSYNOVITIS -> PainRule(maxOf(stage.pain.greenMax, 3), overuse = true)
        InjuryFinding.PULLEY_RUPTURE, InjuryFinding.PULLEY_MULTIPLE -> if (stage.index <= 1) PainRule(1) else stage.pain
        else -> stage.pain
    }

    /** The stage's climbing, which pauses climbing on the injury while it is NONE. */
    fun climbingLevel(injury: Injury): ClimbingLevel? {
        val plan = injury.activePlan ?: return null
        if (plan.source != PlanSource.CRUXCOACH) return null
        return stage(injury, plan).climbing
    }

    // ── Plan changes (always the athlete's tap) ─────────────────────

    fun start(
        injury: Injury,
        source: PlanSource,
        stage: Int,
        today: LocalDate,
        agreedWithProfessional: Boolean,
        injuredDominant: Boolean?,
        referenceKg: Map<String, Double>,
        now: Long,
        ownRoutineId: String? = null,
    ): Injury {
        val day = today.toString()
        val index = stage.coerceIn(0, ladder(injury).stages.lastIndex)
        val plan = ComebackPlan(
            source = source, stage = index, history = listOf(StageChange(index, day, StageChangeReason.START)),
            acknowledgedOn = day, agreedWithProfessional = agreedWithProfessional, injuredDominant = injuredDominant,
            referenceKg = referenceKg, ownRoutineId = ownRoutineId, startedOn = day,
        )
        return withPausedClimbing(injury.copy(plan = plan, updatedAt = now))
    }

    fun moveTo(injury: Injury, stage: Int, reason: StageChangeReason, today: LocalDate, now: Long): Injury {
        val plan = injury.plan ?: return injury
        val index = stage.coerceIn(0, ladder(injury).stages.lastIndex)
        val day = today.toString()
        // "Repeat the week" keeps the stage and starts its week again.
        val next = plan.copy(stage = index, history = plan.history + StageChange(index, day, reason))
        return withPausedClimbing(injury.copy(plan = next, updatedAt = now))
    }

    fun end(injury: Injury, today: LocalDate, now: Long): Injury =
        injury.copy(plan = injury.plan?.copy(endedOn = today.toString()), updatedAt = now)

    /** Remembers that a prompt was shown, so it stays quiet for [PROMPT_QUIET_DAYS]. */
    fun markPrompt(injury: Injury, key: String, today: LocalDate, now: Long): Injury =
        injury.copy(plan = injury.plan?.let { it.copy(prompts = it.prompts + (key to today.toString())) }, updatedAt = now)

    /** A CruxCoach stage without climbing pauses climbing on the injury; others resume it. */
    private fun withPausedClimbing(injury: Injury): Injury {
        val level = climbingLevel(injury) ?: return injury
        return injury.copy(climbingPaused = level == ClimbingLevel.NONE)
    }

    // ── Reading the diary ────────────────────────────────────────────

    fun daysOnStage(plan: ComebackPlan, today: LocalDate): Int =
        parse(plan.stageSince)?.let { (today.toEpochDays() - it.toEpochDays()).toInt() } ?: 0

    fun readings(injury: Injury, entries: List<ComebackEntry>, from: LocalDate, to: LocalDate): List<DayReading> {
        val rule = painRule(injury)
        val byDay = entries.associateBy { it.day }
        val mornings = entries.mapNotNull { e -> e.morningPain?.let { (parse(e.day) ?: return@mapNotNull null) to it } }.sortedBy { it.first }
        val out = mutableListOf<DayReading>()
        var d = from
        while (d <= to) {
            val e = byDay[d.toString()]
            val baseline = mornings.filter { it.first < d && it.first >= d.minus(DatePeriod(days = 7)) }.takeLast(3).map { it.second }.median()
            val rise = e?.morningPain?.let { m -> baseline?.let { m - it } }
            out += DayReading(d, color(e, rule, rise), e?.sessionPainMax, e?.morningPain, rise, e?.pressurePain)
            d = d.plusDays(1)
        }
        return out
    }

    private fun color(e: ComebackEntry?, rule: PainRule, rise: Int?): DayColor? {
        if (e == null) return null
        val session = e.sessionPainMax?.let { p ->
            when {
                p <= rule.greenMax -> DayColor.GREEN
                p <= rule.amberMax -> DayColor.AMBER
                else -> DayColor.RED
            }
        }
        val morning = e.morningPain?.let { m ->
            when {
                m >= 5 || (rise != null && rise >= 2) -> DayColor.RED
                rise != null && rise == 1 -> DayColor.AMBER
                else -> DayColor.GREEN
            }
        }
        return listOfNotNull(session, morning).maxOrNull()
    }

    fun review(injury: Injury, entries: List<ComebackEntry>, today: LocalDate): Review? {
        val plan = injury.activePlan ?: return null
        val days = daysOnStage(plan, today)
        val since = parse(plan.stageSince) ?: today
        val from = maxOf(today.minus(DatePeriod(days = 6)), since)
        val week = readings(injury, entries, from, today)
        val green = week.count { it.color == DayColor.GREEN }
        val amber = week.count { it.color == DayColor.AMBER }
        val red = week.count { it.color == DayColor.RED }
        val logged = week.count { it.color != null }
        val worst = week.filter { it.sessionPain != null }.maxByOrNull { it.sessionPain!! }?.let { it.day to it.sessionPain!! }
        val trend = pressureTrend(entries, today)
        val lastStage = ladder(injury).stages.lastIndex
        val recentRedMorning = week.takeLast(2).any { (it.morningRise ?: 0) >= 2 || (it.morningPain ?: 0) >= 5 }
        val proposal = when {
            red >= 2 || recentRedMorning -> if (plan.stage > 0) Proposal.STEP_BACK else Proposal.STAY
            amber >= 2 || red == 1 -> Proposal.REPEAT
            days >= MIN_DAYS_ON_STAGE && logged >= 4 && trend != PainTrend.RISING && plan.stage < lastStage -> Proposal.MOVE_UP
            else -> Proposal.STAY
        }
        val stay = if (proposal != Proposal.STAY) null else when {
            days < MIN_DAYS_ON_STAGE -> StayReason.TOO_EARLY
            plan.stage >= lastStage -> StayReason.LAST_STAGE
            trend == PainTrend.RISING -> StayReason.PRESSURE_RISING
            else -> StayReason.FEW_ENTRIES
        }
        return Review(proposal, days, green, amber, red, logged, worst, trend, due = days >= MIN_DAYS_ON_STAGE, stayReason = stay)
    }

    /** Pressure pain of the last 7 days against the 7 before (two values each at least). */
    fun pressureTrend(entries: List<ComebackEntry>, today: LocalDate): PainTrend? {
        fun mean(from: LocalDate, to: LocalDate) = entries.filter { e -> parse(e.day)?.let { it in from..to } == true }
            .mapNotNull { it.pressurePain }.takeIf { it.size >= 2 }?.average()
        val now = mean(today.minus(DatePeriod(days = 6)), today) ?: return null
        val before = mean(today.minus(DatePeriod(days = 13)), today.minus(DatePeriod(days = 7))) ?: return null
        return when {
            now - before >= 1.0 -> PainTrend.RISING
            now - before <= -1.0 -> PainTrend.FALLING
            else -> PainTrend.FLAT
        }
    }

    /**
     * Prompts due today, most important first, without the ones shown within [PROMPT_QUIET_DAYS] (a review once per
     * stage week). Escalation only points to a professional; it never says all is well.
     */
    fun prompts(injury: Injury, entries: List<ComebackEntry>, today: LocalDate): List<Prompt> {
        val plan = injury.activePlan ?: return emptyList()
        val out = mutableListOf<Prompt>()
        fun quiet(key: String) = plan.prompts[key]?.let(::parse)?.let { (today.toEpochDays() - it.toEpochDays()) < PROMPT_QUIET_DAYS } == true
        if (entries.firstOrNull { it.day == today.toString() }?.newSign == true && !quiet("sign:$today")) {
            out += Prompt(PromptKind.NEW_SIGN, "sign:$today")
        }
        worsening(entries, today)?.let { (a, b) -> if (!quiet("worse")) out += Prompt(PromptKind.WORSENING, "worse", from = a, to = b) }
        notBetter(entries, today)?.let { days ->
            val (kind, key) = if (days >= 21) PromptKind.NOT_BETTER_21 to "nb21" else PromptKind.NOT_BETTER_14 to "nb14"
            if (!quiet(key) && !(kind == PromptKind.NOT_BETTER_14 && plan.prompts.containsKey("nb14"))) out += Prompt(kind, key)
        }
        if (plan.source == PlanSource.CRUXCOACH) review(injury, entries, today)?.takeIf { it.due }?.let { r ->
            val key = "review:${plan.stage}:${plan.history.size}:${r.daysOnStage / MIN_DAYS_ON_STAGE}"
            if (!plan.prompts.containsKey(key)) out += Prompt(PromptKind.REVIEW, key, review = r)
        }
        return out
    }

    /** The morning value rose by two or more within the last three days: (lowest, today's). */
    fun worsening(entries: List<ComebackEntry>, today: LocalDate): Pair<Int, Int>? {
        val now = entries.firstOrNull { it.day == today.toString() }?.morningPain ?: return null
        val before = entries.filter { e -> parse(e.day)?.let { it < today && it >= today.minus(DatePeriod(days = 3)) } == true }
            .mapNotNull { it.morningPain }.minOrNull() ?: return null
        return if (now - before >= 2) before to now else null
    }

    /**
     * Days of diary when the pain has not gone down: the last three entries are not lower than the first three
     * (morning or pressure pain, whichever was logged). Null before 14 days or while it improves.
     */
    fun notBetter(entries: List<ComebackEntry>, today: LocalDate): Int? {
        val logged = entries.filter { it.morningPain != null || it.pressurePain != null }.sortedBy { it.day }
        val first = logged.firstOrNull()?.day?.let(::parse) ?: return null
        val days = (today.toEpochDays() - first.toEpochDays()).toInt()
        if (days < 14 || logged.size < 6) return null
        fun value(e: ComebackEntry) = maxOf(e.morningPain ?: 0, e.pressurePain ?: 0)
        val start = logged.take(3).map(::value).average()
        val end = logged.takeLast(3).map(::value).average()
        return if (end >= start) days else null
    }

    // ── Today's sessions ─────────────────────────────────────────────

    fun due(injury: Injury, entries: List<ComebackEntry>, today: LocalDate, now: Long): List<DueSession> {
        val plan = injury.activePlan ?: return emptyList()
        if (plan.source != PlanSource.CRUXCOACH) return emptyList()
        val logs = entries.flatMap { e -> e.sessions.map { (parse(e.day) ?: today) to it } }
        return stage(injury, plan).sessions.mapIndexed { index, spec ->
            val mine = logs.filter { it.second.kind == spec.kind }
            val todays = mine.filter { it.first == today }
            val last = mine.maxOfOrNull { it.second.at }?.takeIf { it > 0 }
            val gapMs = spec.minHoursBetween * 3_600_000L
            val status: DueStatus
            var next: Long? = null
            if (spec.perDay > 1 || spec.minHoursBetween < 24) {
                when {
                    todays.size >= spec.perDay -> status = DueStatus.DONE
                    last != null && now - last < gapMs -> { status = DueStatus.LATER; next = last + gapMs }
                    else -> status = DueStatus.DUE
                }
            } else {
                val weekStart = today.minus(DatePeriod(days = today.dayOfWeek.isoDayNumber - 1))
                val thisWeek = mine.count { it.first >= weekStart }
                when {
                    todays.isNotEmpty() -> status = DueStatus.DONE
                    last != null && now - last < gapMs -> { status = DueStatus.NOT_TODAY; next = last + gapMs }
                    thisWeek >= spec.perWeek -> status = DueStatus.NOT_TODAY
                    else -> status = DueStatus.DUE
                }
            }
            DueSession(index, spec, todays.size, status, next)
        }
    }

    // ── Sessions as trainings ────────────────────────────────────────

    fun routineId(injuryId: String, stage: Int, session: Int) = "$ROUTINE_PREFIX$injuryId:$stage:$session"

    /** (injury id, session index) of a comeback training, or null. */
    fun parseRoutineId(id: String?): Pair<String, Int>? {
        if (id == null || !id.startsWith(ROUTINE_PREFIX)) return null
        val parts = id.removePrefix(ROUTINE_PREFIX).split(':')
        if (parts.size != 3) return null
        return parts[0] to (parts[2].toIntOrNull() ?: return null)
    }

    /**
     * One session of the stage as a training. Loads: the athlete's last load in a comeback training of the same
     * exercise, grip and side ([lastLoad]); else the stage's lower percentage of the reference; else the careful
     * start load for the injured hand – the athlete adjusts it in the training.
     */
    fun routine(
        injury: Injury,
        sessionIndex: Int,
        name: String,
        lastLoad: (slug: String, grip: Grip?, side: Side?) -> Double?,
        incrementKg: Double = 0.5,
    ): Routine? {
        val plan = injury.activePlan ?: return null
        val spec = stage(injury, plan).sessions.getOrNull(sessionIndex) ?: return null
        val injured = when (injury.side) { InjurySide.LEFT -> Side.LEFT; InjurySide.RIGHT -> Side.RIGHT; else -> null }
        val items = spec.items.mapNotNull { item ->
            val side = when (item.side) {
                SideTarget.BOTH -> null
                SideTarget.INJURED -> injured
                SideTarget.HEALTHY -> injured?.opposite ?: return@mapNotNull null
            }
            val sides = when (side) { Side.LEFT -> SideMode.LEFT_ONLY; Side.RIGHT -> SideMode.RIGHT_ONLY; null -> SideMode.BOTH }
            RoutineItem(
                slug = item.slug, sets = item.sets, repsMin = item.repsMin, repsMax = item.repsMax, durationS = item.durationS,
                loadKg = load(plan, item, side, injured, lastLoad, incrementKg),
                restS = item.restS, edgeMm = item.edgeMm, grip = item.grip, sides = sides,
            )
        }
        return Routine(routineId(injury.id, plan.stage, sessionIndex), name, items = items)
    }

    private fun load(
        plan: ComebackPlan, item: SessionItemSpec, side: Side?, injured: Side?,
        lastLoad: (String, Grip?, Side?) -> Double?, incrementKg: Double,
    ): Double? {
        if (!item.slug.startsWith("finger.")) return lastLoad(item.slug, item.grip, side)
        lastLoad(item.slug, item.grip, side)?.let { return it }
        val reference = item.grip?.let { plan.referenceKg[it.name] }
        val percent = item.loadPercent
        if (reference != null && percent != null) return roundTo(reference * percent.first / 100.0, incrementKg)
        return if (side == injured || injured == null) START_KG else null
    }

    /** The target range in kg of an item, when its stage gives a percentage and a reference is known. */
    fun targetKg(plan: ComebackPlan, item: SessionItemSpec, incrementKg: Double = 0.5): ClosedFloatingPointRange<Double>? {
        val reference = item.grip?.let { plan.referenceKg[it.name] } ?: return null
        val percent = item.loadPercent ?: return null
        return roundTo(reference * percent.first / 100.0, incrementKg)..roundTo(reference * percent.last / 100.0, incrementKg)
    }

    /**
     * A reference value from logged sets: the best completed one-arm hold of [slug] in [grip] (7 s or more) on
     * [side] before [before] – the injured hand before the injury, or the other hand.
     */
    fun referenceFrom(history: List<ExerciseSet>, slug: String, grip: Grip?, side: Side?, before: Long = Long.MAX_VALUE): Double? =
        history.filter {
            it.exerciseSlug == slug && it.isCompleted && it.setType != SetType.WARMUP && it.side == side &&
                (grip == null || it.grip == null || it.grip == grip) && (it.durationS ?: 0.0) >= 7.0 && (it.completedAt ?: 0L) < before
        }.mapNotNull { it.loadKg }.maxOrNull()

    // ── Warning signs ────────────────────────────────────────────────

    fun signsFor(region: InjuryRegion): List<WarningSign> = WarningSign.entries.filter { it.appliesTo(region) }

    /** The most urgent ticked sign's urgency, or null. */
    fun urgency(signs: Set<WarningSign>): Urgency? = signs.map { it.urgency }.minOrNull()

    /** Under 18 with finger pain: always recommend a doctor first (growth plate, fingers.md §1.7). */
    fun under18(injury: Injury, birthYear: Int?, today: LocalDate): Boolean =
        injury.region == InjuryRegion.FINGER && birthYear != null && today.year - birthYear < 18

    /** Not checked by anyone yet: the plan recommends a check. */
    fun notChecked(injury: Injury): Boolean = (injury.check?.checked ?: ProfessionalCheck.NOT_YET) == ProfessionalCheck.NOT_YET

    // ── Helpers ──────────────────────────────────────────────────────

    private fun roundTo(kg: Double, step: Double): Double = if (step <= 0) kg else ((kg / step).roundToInt() * step).coerceAtLeast(step)
    private fun parse(day: String): LocalDate? = runCatching { LocalDate.parse(day.take(10)) }.getOrNull()
    private fun LocalDate.plusDays(n: Int) = plus(DatePeriod(days = n))
    private fun List<Int>.median(): Int? = if (isEmpty()) null else sorted()[size / 2]
}
