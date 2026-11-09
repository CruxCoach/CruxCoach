package com.cruxcoach.android.athlete.sim

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.Familiarity
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.BlockPhase
import com.cruxcoach.athlete.logic.LoadRules
import com.cruxcoach.athlete.logic.LoadStructure
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.SetType
import kotlin.math.abs
import kotlin.math.max

/** One finding of the automatic checks: which rule, when, what happened and why it is wrong. */
data class Flag(val check: Check, val day: Int, val what: String)

/**
 * The automatic checks of the training simulation (issue #59). Each one reads only what the athlete was
 * shown and did ([SimRun]); none calls the coach's rules, so a rule that misfires is caught instead of
 * confirmed. Thresholds and their sources: `docs/en/TRAINING_SIMULATION.md`.
 */
enum class Check(val group: String, val title: String) {
    MAX_FINGER_FORBIDDEN("Safety", "Max finger work while a return, comeback or injury rule forbids it"),
    FINGER_AFTER_HARD_CLIMB("Safety", "Hard finger session too soon after a hard climbing day"),
    CONTRAINDICATED("Safety", "Exercise that loads an open injury"),
    YOUTH("Safety", "Finger work a growing climber should not do"),
    MASTERS("Safety", "Max finger sessions too close together for a masters climber"),
    COMEBACK_LOAD("Safety", "Comeback load above its stage"),
    COMEBACK_EARLY("Safety", "Next comeback stage offered while it still hurt"),
    COMEBACK_UP_NO_MORNING("Safety", "Next comeback stage offered without a morning value in the week"),
    COMEBACK_SPACING("Safety", "Comeback sessions closer than their stage allows"),
    COMEBACK_CLIMBING("Safety", "Board session suggested while the comeback stage holds it back"),
    COMEBACK_NO_STEP_BACK("Safety", "Painful comeback sessions without a step back, a repeat or a question about it"),
    LOAD_JUMP("Load", "Planned load jumped more than one step or 10 % between sessions"),
    STRUCTURE_LOAD("Load", "Max finger work while the finger load is above 1.5 × its normal"),
    CONVERGENCE("Load", "The app's value is far from what the athlete can really do"),
    STALL("Progress", "An exercise stalled for weeks without a change"),
    DELOAD("Progress", "No lighter week in a block, or no taper before the date"),
    COMEBACK_STUCK("Progress", "Comeback plan stuck on a stage although it stayed calm"),
    COMEBACK_UP_UNTRAINED("Progress", "Next comeback stage offered after a week with few of its sessions done"),
    WEEKLY_VOLUME("Plan", "Weekly volume of an area far from its target"),
    SESSION_FIT("Plan", "Suggestion does not fit the session length"),
    TRAINING_DAYS("Plan", "A full training suggested after the week's training days are used up"),
    DAYS_LEFT("Plan", "Recovery suggested although training days are left"),
    VARIETY("Plan", "Too little variety"),
    STACKING("Stacking", "Reductions stacked into a pointless session"),
    SPECIAL_FOR_BEGINNER("Choice", "Special exercise for a beginner who does not know it (weeks 1–4)"),
    FEW_FAMILIAR("Choice", "Too few well-known exercises for a beginner (weeks 1–4) or with “Mostly classics”"),
    NEW_PER_SESSION("Choice", "More new exercises in a training than allowed"),
    UNUSUAL_FOR_ADVANCED("Choice", "Too few Common or Special exercises for an advanced athlete"),
    OPEN_WITHOUT_SPECIAL("Choice", "“Open to unusual exercises” brought too few Special exercises"),
    SPECIAL_WITH_CLASSICS("Choice", "Special exercise with “Mostly classics” that the athlete does not know"),
    DONT_KNOW_UNFAMILIAR("Choice", "“Don't know it” gave something unknown, or counted against the exercise"),
    DONT_KNOW_BACK("Choice", "An exercise marked “Don't know it” came back as a new pick within 14 days"),
    NEW_FORCED("Info", "More new exercises because nothing known fitted the slot (reported, not a fault)"),
}

object Checks {

    /** Load tolerance between two sessions: one weight step or this share of the total, whichever is larger (ACSM 2009: 2–10 %). */
    const val JUMP_SHARE = 0.10
    /** Acute:chronic workload ratio above which injury risk climbs (Gabbett 2016). */
    const val ACWR_LIMIT = 1.5
    /** Hours after a hard or limit climbing day before max finger work; masters 72 (Hörst 2016; Fell & Williams 2008). */
    const val FINGER_RECOVERY_H = 48
    const val FINGER_RECOVERY_MASTERS_H = 72
    /** The app's value is close enough to the truth within this share after six weeks. */
    const val CONVERGENCE_SHARE = 0.20
    /** Weekly volume per area within this range of its goal counts as "near". */
    val VOLUME_RANGE = 0.5..1.6
    /** Comeback strength sessions start at least this far apart (the stage asks for 48 h of rest between them). */
    const val COMEBACK_STRENGTH_GAP_H = 46
    /** A stage that stayed calm this long should have offered the next one (a stage is a week or two). */
    const val COMEBACK_STUCK_DAYS = 21
    /** Sessions beyond the stage's amber limit within a week that ask the plan for a reaction within the next week. */
    const val COMEBACK_RED_SESSIONS = 2
    /** Days with a comeback session in the week before the next stage is offered: at least this many. */
    const val COMEBACK_UP_SESSION_DAYS = 3
    /** Issue #68: a beginner's first weeks, in which well-known exercises come first. */
    const val BEGINNER_WEEKS = 4
    /** Share of a beginner's main exercises in those weeks that are Basic or already known to them. */
    const val BEGINNER_FAMILIAR_SHARE = 0.7
    /** With "Mostly classics": the same share outside the finger work, for the whole run. */
    const val CLASSICS_FAMILIAR_SHARE = 0.8
    /** An advanced athlete still gets variety: this share of their main exercises is Common or Special. */
    const val ADVANCED_UNUSUAL_SHARE = 0.3
    /** "Open to unusual exercises": a Special exercise in at least this share of all two-week stretches. */
    const val OPEN_SPECIAL_STRETCHES = 0.5
    /** "Don't know it" gives something Basic or known in at least this share of the taps. */
    const val DONT_KNOW_FAMILIAR_SHARE = 0.95
    /** Logged in this many trainings, an exercise is known to the athlete. */
    const val KNOWN_AFTER_TRAININGS = 2

    /** The comeback stage from which training outside the plan may load the injured spot again (the app's own constants). */
    fun comebackOpenStage(region: com.cruxcoach.athlete.model.InjuryRegion): Int =
        if (region == com.cruxcoach.athlete.model.InjuryRegion.FINGER) com.cruxcoach.athlete.logic.InjuryAdvisor.FINGER_OPEN_STAGE
        else com.cruxcoach.athlete.logic.InjuryAdvisor.REGION_OPEN_STAGE

    fun isMaxFinger(def: ExerciseDefinition): Boolean =
        (LoadDomain.FINGER in def.domains && (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) &&
            (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) && "strength" in def.tags) ||
            EquipmentV2.CAMPUS_BOARD in def.equipment

    /**
     * One-sided work around an injury of one limb, done with the healthy side only: the exercise does not load the
     * injured region on the other side as well ([ExerciseDefinition.bothSides]), and no set of it was logged on the
     * injured side that day.
     */
    private fun healthySideOnly(d: DayTrace, def: ExerciseDefinition, injury: com.cruxcoach.athlete.model.Injury): Boolean {
        val injured = when (injury.side) {
            com.cruxcoach.athlete.model.InjurySide.LEFT -> com.cruxcoach.athlete.model.Side.LEFT
            com.cruxcoach.athlete.model.InjurySide.RIGHT -> com.cruxcoach.athlete.model.Side.RIGHT
            else -> return false
        }
        return def.unilateral && injury.region.limb && injury.region.bodyRegions.none { it in def.bothSides } &&
            d.sets.none { it.slug == def.slug && it.side == injured }
    }

    private fun DayTrace.suggestedDefs(catalog: ExerciseCatalog): List<ExerciseDefinition> =
        suggestion?.items.orEmpty().filter { !it.warmup }.mapNotNull { catalog[it.slug] }

    fun run(r: SimRun): List<Flag> {
        val flags = mutableListOf<Flag>()
        val catalog = r.catalog
        val p = r.persona
        val coach = p.coach
        val youth = coach.ageBand == AgeBand.UNDER_16 || coach.ageBand == AgeBand.Y16_17
        val masters = coach.ageBand == AgeBand.Y55_PLUS || coach.ageBand == AgeBand.Y40_54
        val novice = coach.experience == ExperienceBand.UNDER_1
        val days = r.days.filter { !it.away }

        // ── Safety ──
        days.forEach { d ->
            val defs = d.suggestedDefs(catalog)
            val maxFinger = defs.filter(::isMaxFinger)
            val fingerInjuries = d.activeInjuries.filter { it.region == com.cruxcoach.athlete.model.InjuryRegion.FINGER }
            // One-handed work for the healthy hand stays allowed around an injury of one hand (the comeback plan trains it too).
            val injuredHand = if (fingerInjuries.isEmpty()) emptyList()
                else maxFinger.filterNot { def -> fingerInjuries.all { healthySideOnly(d, def, it) } }
            val forbidden = when {
                d.returnState?.noMaxFinger == true -> "the return ramp after a break (${d.returnState.reason})"
                youth -> "the youth guardrail"
                novice -> "the first-year guardrail"
                else -> null
            }
            if (injuredHand.isNotEmpty()) flags += Flag(Check.MAX_FINGER_FORBIDDEN, d.day,
                "${injuredHand.joinToString { it.slug }} suggested during an open finger injury")
            else if (forbidden != null && maxFinger.isNotEmpty()) flags += Flag(Check.MAX_FINGER_FORBIDDEN, d.day,
                "${maxFinger.joinToString { it.slug }} suggested during $forbidden")

            if (maxFinger.isNotEmpty()) {
                val window = (if (masters && coach.ageBand == AgeBand.Y55_PLUS) FINGER_RECOVERY_MASTERS_H else FINGER_RECOVERY_H) / 24
                // Strictly less than the window: a Thursday-evening limit session allows max hangs on Saturday evening.
                val hardBefore = days.filter { it.day in (d.day - window + 1) until d.day }
                    .firstOrNull { it.climbed?.intensity == ClimbIntensity.HARD || it.climbed?.intensity == ClimbIntensity.LIMIT }
                if (hardBefore != null) flags += Flag(Check.FINGER_AFTER_HARD_CLIMB, d.day,
                    "${maxFinger.first().slug} ${d.day - hardBefore.day} day(s) after ${hardBefore.climbed!!.intensity.name.lowercase()} climbing")
            }

            d.activeInjuries.filter { it.severity >= 4 }.forEach { injury ->
                // From its open stage on, a running CruxCoach comeback plan lets training outside the plan load the injured
                // spot again, marked (owner 2026-10-20): only the contraindication is allowed then, climbing while it is paused never.
                val plan = injury.activePlan?.takeIf { it.source == com.cruxcoach.athlete.model.PlanSource.CRUXCOACH }
                val open = plan != null && plan.stage >= comebackOpenStage(injury.region)
                val hit = defs.filter { def -> (!open && injury.region.bodyRegions.any { it in def.contraindications } &&
                    !healthySideOnly(d, def, injury)) ||
                    (injury.region.domains.any { it in def.domains } && injury.climbingPaused && def.kind == ExerciseKind.CLIMB) }
                if (hit.isNotEmpty()) flags += Flag(Check.CONTRAINDICATED, d.day,
                    "${hit.joinToString { it.slug }} loads the open ${injury.region.name.lowercase()} injury")
            }

            if (youth) {
                val bad = defs.filter { def ->
                    EquipmentV2.CAMPUS_BOARD in def.equipment ||
                        (LoadDomain.FINGER in def.domains && def.category != ExerciseCategoryV2.WARMUP && (def.unilateral || isMaxFinger(def)))
                }
                if (bad.isNotEmpty()) flags += Flag(Check.YOUTH, d.day, "${bad.joinToString { it.slug }} suggested to a climber under 18")
            }
        }
        if (coach.ageBand == AgeBand.Y55_PLUS) {
            val maxDays = days.filter { d -> d.trained && d.sets.any { s -> catalog[s.slug]?.let(::isMaxFinger) == true && s.setType == SetType.WORK } }
            maxDays.zipWithNext().forEach { (a, b) ->
                if (b.day - a.day < FINGER_RECOVERY_MASTERS_H / 24) flags += Flag(Check.MASTERS, b.day,
                    "max finger work ${b.day - a.day} day(s) after the last")
            }
        }

        // ── Load ──
        val sessions = days.filter { it.trained }
        val firstLoads = mutableMapOf<Pair<String, com.cruxcoach.athlete.model.Side?>, Triple<Int, Double, Double>>()
        sessions.forEach { d ->
            d.sets.filter { it.setType == SetType.WORK && it.targetLoadKg != null }
                .groupBy { it.slug to it.side }
                .forEach { (key, rows) ->
                    val def = catalog[key.first] ?: return@forEach
                    val bw = p.bodyweightKg
                    val first = rows.minBy { it.setIndex }
                    val total = when (def.load) {
                        LoadMode.BODYWEIGHT_PLUS -> bw + (first.targetLoadKg ?: 0.0)
                        else -> LoadRules.lifted(def, first.targetLoadKg ?: 0.0)
                    }
                    firstLoads[key]?.let { (day, plannedBefore, plannedLoggedBefore) ->
                        val step = LoadRules.lifted(def, p.smallestIncrementKg) * if (EquipmentV2.BARBELL in def.equipment) 2 else 1
                        val last = days.firstOrNull { it.day == day }?.sets.orEmpty().filter { it.slug == key.first && it.side == key.second && it.setType == SetType.WORK }
                        // The reference is the higher of what was planned and what was done last time: a load raised
                        // within the training (or by the athlete) is where the next one starts.
                        val doneLogged = last.mapNotNull { it.loadKg }.maxOrNull() ?: plannedLoggedBefore
                        val doneTotal = if (def.load == LoadMode.BODYWEIGHT_PLUS) bw + doneLogged else LoadRules.lifted(def, doneLogged)
                        val before = max(plannedBefore, doneTotal)
                        val loggedBefore = max(plannedLoggedBefore, doneLogged)
                        val allowed = max(step, before * JUMP_SHARE) + 1e-6
                        // A bigger step is fine after a session in which every work set had 3+ in reserve: the athlete said so.
                        val easy = last.isNotEmpty() && last.all { (it.rir ?: 0) >= 3 }
                        // Below 10 kg one step per training, whatever the reserve said (owner 2026-11-06).
                        val small = def.load == LoadMode.EXTERNAL && loggedBefore < 10.0 &&
                            (first.targetLoadKg ?: 0.0) > loggedBefore + p.smallestIncrementKg + 1e-6
                        if ((total - before > allowed && !easy) || small) flags += Flag(Check.LOAD_JUMP, d.day,
                            "${key.first}${key.second?.let { " (${it.name.lowercase()})" } ?: ""}: %.1f → %.1f kg total since day $day (done then: %s, reserve %s)".format(
                                before, total, last.joinToString("/") { "%.1f".format(it.loadKg ?: 0.0) }, last.joinToString("/") { it.rir?.toString() ?: "–" }))
                    }
                    firstLoads[key] = Triple(d.day, total, first.targetLoadKg ?: 0.0)
                }
        }
        // The athlete's climbing makes the load; the coach must not add max finger work on top of a spike.
        // Acute against chronic needs four weeks of history before it says anything (the chronic window is 28 days).
        days.filter { it.day >= 28 }.forEach { d ->
            val before = r.days.getOrNull(d.day - 1)?.load?.get(LoadStructure.FINGER) ?: return@forEach
            val maxFinger = d.suggestedDefs(catalog).filter(::isMaxFinger)
            if (before.chronic > 0.5 && before.acute / before.chronic > ACWR_LIMIT && maxFinger.isNotEmpty()) flags += Flag(Check.STRUCTURE_LOAD, d.day,
                "${maxFinger.first().slug} at a finger load of %.1f against a normal of %.1f (%.2f×)".format(before.acute, before.chronic, before.acute / before.chronic))
        }
        r.convergence.filter { it.week >= 6 && it.appKg != null && it.week == r.convergence.maxOf { c -> c.week } }.forEach { c ->
            val sessionsOfIt = sessions.count { d -> d.sets.any { it.slug == c.slug && it.side == c.side && it.setType == SetType.WORK } }
            if (sessionsOfIt >= 4 && abs(c.appKg!! - c.trueKg) / c.trueKg > CONVERGENCE_SHARE) flags += Flag(Check.CONVERGENCE, (c.week * 7) - 1,
                "${c.slug}${c.side?.let { " (${it.name.lowercase()})" } ?: ""}: app %.1f kg, true %.1f kg after %d sessions".format(c.appKg, c.trueKg, sessionsOfIt))
        }

        // ── Progress ──
        sessions.flatMap { d -> d.sets.filter { it.setType == SetType.WORK }.map { it.slug to d } }.groupBy({ it.first }, { it.second })
            .forEach { (slug, ds) ->
                val distinct = ds.distinctBy { it.day }
                val def = catalog[slug]
                // Band, mobility and warm-up work does not progress by plan; only loaded or body-weight strength work should.
                if (distinct.size < 5 || def == null || def.load == LoadMode.NONE ||
                    def.category == ExerciseCategoryV2.MOBILITY || def.category == ExerciseCategoryV2.WARMUP) return@forEach
                val span = distinct.last().day - distinct.first().day
                fun signature(d: DayTrace) = d.sets.filter { it.slug == slug && it.setType == SetType.WORK }
                    .joinToString { "${it.targetLoadKg}/${it.targetReps}/${it.targetDurationS}" }
                val last5 = distinct.takeLast(5)
                if (span >= 28 && last5.map(::signature).distinct().size == 1) flags += Flag(Check.STALL, last5.last().day,
                    "$slug planned the same way in 5 sessions over ${last5.last().day - last5.first().day} days")
            }
        val build = days.filter { it.block?.phase == BlockPhase.BUILD }
        if (r.days.size >= 42 && build.isNotEmpty() && days.none { it.block?.phase == BlockPhase.DELOAD }) {
            flags += Flag(Check.DELOAD, r.days.last().day, "no deload phase in ${r.days.size / 7} weeks")
        }
        p.targetInDays?.let { target ->
            val taper = days.filter { it.day in (target - 10) until target }
            if (taper.isNotEmpty() && taper.none { it.block?.phase == BlockPhase.TAPER }) flags += Flag(Check.DELOAD, target - 1,
                "no taper in the ten days before the date")
        }
        // Deload weeks must be lighter than build weeks: fewer work sets in the trainings the athlete did.
        fun setsPerTraining(ds: List<DayTrace>): Double {
            val trained = ds.filter { it.trained && it.suggestion?.addOn != true }
            return if (trained.isEmpty()) 0.0 else trained.sumOf { d -> d.sets.count { it.setType == SetType.WORK } }.toDouble() / trained.size
        }
        val deload = days.filter { it.block?.phase == BlockPhase.DELOAD }
        val deloadTrainings = deload.count { it.trained && it.suggestion?.addOn != true }
        if (deloadTrainings >= 2 && build.count { it.trained } >= 4 && setsPerTraining(deload) > setsPerTraining(build) * 0.85) {
            flags += Flag(Check.DELOAD, deload.last().day, "deload trainings had %.1f work sets on average against %.1f in build weeks"
                .format(setsPerTraining(deload), setsPerTraining(build)))
        }

        // ── Plan ──
        days.forEach { d ->
            val s = d.suggestion ?: return@forEach
            // The event itself and the day before are short on purpose.
            if (s.focus == SuggestionFocus.REST || s.addOn || s.board || d.block?.phase == BlockPhase.EVENT) return@forEach
            if (s.minutes > p.sessionMinutes * 1.3 + 5) flags += Flag(Check.SESSION_FIT, d.day,
                "${s.focus.name.lowercase()} planned for ${s.minutes} min, chosen length ${p.sessionMinutes} min")
            // The athlete's training days (climbing included) are already used up this week.
            val days = coach.trainingDaysPerWeek
            if (days != null) {
                val weekStart = d.day - d.date.dayOfWeek.ordinal
                val used = r.days.filter { it.day in weekStart until d.day }.count { it.trained || it.climbed != null }
                if (used >= days && d.climbed == null && s.focus != SuggestionFocus.MOBILITY_RECOVERY) flags += Flag(Check.TRAINING_DAYS, d.day,
                    "${s.focus.name.lowercase()} for ${s.minutes} min after $used of $days training days this week")
                // The other way round: trainings and climbing days are what uses a training day up, nothing else.
                if (used < days && com.cruxcoach.athlete.logic.SuggestionReason.WEEK_DAYS_DONE in s.reasons) flags += Flag(Check.DAYS_LEFT, d.day,
                    "\"training days done\" after $used of $days training days this week")
            }
            val work = s.items.filter { !it.warmup }
            if (work.isNotEmpty() && (work.sumOf { it.sets } < 3 || s.minutes < 12)) flags += Flag(Check.STACKING, d.day,
                "${s.focus.name.lowercase()} with ${work.sumOf { it.sets }} work set(s) in ${s.minutes} min" +
                    listOfNotNull(d.block?.phase?.takeIf { it != BlockPhase.BUILD }?.name?.lowercase(), d.returnState?.let { "return ramp" },
                        d.readiness.name.lowercase().takeIf { it != "go" }).joinToString(", ", " (", ")"))
        }
        // Weekly volume, judged only for weeks the athlete trained as planned and was not held back by an injury.
        days.filter { it.weekEnd != null }.forEach { d ->
            val week = days.filter { it.day in (d.day - 6)..d.day }
            val planned = week.count { it.date.dayOfWeek.ordinal + 1 in p.trainingDays }
            val trainedAll = planned > 0 && week.count { it.trained } >= planned && week.none { it.skipped }
            if (!trainedAll || week.any { it.activeInjuries.isNotEmpty() || it.returnState != null } || week.size < 7) return@forEach
            // A block phase that changed within the week (a deload brought forward, the taper) planned its first days
            // against other targets than Sunday's; such a week has no single target to judge it by.
            if (week.map { it.block?.phase }.distinct().size > 1) return@forEach
            // Mobility is no strain: its target is a reminder, not a limit.
            d.weekEnd!!.filter { !it.target.isOff && it.goal > 0 && it.area != com.cruxcoach.athlete.logic.VolumeArea.MOBILITY }.forEach { a ->
                val ratio = a.effective / a.goal
                val high = a.area != com.cruxcoach.athlete.logic.VolumeArea.FINGER && a.target.maxSets > 0 && a.effective > a.target.maxSets * 1.25
                if (ratio < VOLUME_RANGE.start || high) flags += Flag(Check.WEEKLY_VOLUME, d.day,
                    "${a.area.name.lowercase()}: %.1f against a goal of %d (max %d)".format(a.effective, a.goal, a.target.maxSets))
            }
        }
        // The short block after climbing is meant to repeat; variety counts full trainings.
        val full = sessions.filter { it.suggestion?.addOn != true }
        if (full.size >= 8) {
            full.chunked(8).filter { it.size == 8 }.forEach { chunk ->
                val distinct = chunk.flatMap { d -> d.sets.filter { it.setType == SetType.WORK }.map { it.slug } }.toSet()
                if (distinct.size < 6) flags += Flag(Check.VARIETY, chunk.last().day, "${distinct.size} different exercises in ${chunk.size} trainings")
            }
        }
        comeback(r, flags)
        familiarity(r, flags)
        return flags
    }

    /**
     * Well-known exercises first (issue #68), from the catalogue's levels and what the athlete logged: an exercise is
     * known after [KNOWN_AFTER_TRAININGS] trainings with it, Basic ones are known to everyone. The coach's own picks are
     * judged, before the athlete's "Don't know it" swaps.
     */
    private fun familiarity(r: SimRun, flags: MutableList<Flag>) {
        val catalog = r.catalog
        val p = r.persona
        val choice = p.coach.exerciseChoice
        val days = r.days.filter { !it.away }
        // Trainings per exercise before each day.
        val trainings = mutableMapOf<String, Int>()
        val knownOn = mutableMapOf<Int, Set<String>>()
        r.days.forEach { d ->
            knownOn[d.day] = trainings.filterValues { it >= KNOWN_AFTER_TRAININGS }.keys.toSet()
            if (d.trained) d.sets.map { it.slug }.distinct().forEach { trainings.merge(it, 1, Int::plus) }
        }
        fun known(day: Int, def: ExerciseDefinition) = def.familiarity == Familiarity.BASIC || def.slug in knownOn[day].orEmpty() || def.custom
        fun coachItems(d: DayTrace) = d.suggestion?.items.orEmpty().map { item -> d.dontKnow.firstOrNull { it.to == item.slug }?.let { item.copy(slug = it.from) } ?: item }
        fun main(d: DayTrace) = coachItems(d).filter { !it.warmup }.mapNotNull { catalog[it.slug] }
            .filter { it.category != ExerciseCategoryV2.MOBILITY && it.category != ExerciseCategoryV2.WARMUP }
        fun counted(d: DayTrace) = coachItems(d).mapNotNull { item -> catalog[item.slug]?.let { item to it } }.filter { (item, def) ->
            !known(d.day, def) && (def.familiarity == Familiarity.SPECIAL ||
                (!item.warmup && def.category != ExerciseCategoryV2.MOBILITY && def.category != ExerciseCategoryV2.WARMUP))
        }.map { it.second }
        val beginner = p.level == Level.BEGINNER
        val early = days.filter { it.day < BEGINNER_WEEKS * 7 && it.suggestion != null && it.suggestion.focus != SuggestionFocus.REST }

        if (beginner || choice == com.cruxcoach.athlete.model.ExerciseChoice.CLASSICS) {
            val judged = if (choice == com.cruxcoach.athlete.model.ExerciseChoice.CLASSICS) days.filter { it.suggestion != null } else early
            judged.forEach { d ->
                val special = main(d).filter { it.familiarity == Familiarity.SPECIAL && !known(d.day, it) }
                if (special.isNotEmpty()) flags += Flag(if (beginner) Check.SPECIAL_FOR_BEGINNER else Check.SPECIAL_WITH_CLASSICS, d.day,
                    "${special.joinToString { it.slug }} (${d.suggestion!!.focus.name.lowercase()})")
            }
            val share = if (beginner) BEGINNER_FAMILIAR_SHARE else CLASSICS_FAMILIAR_SHARE
            val pairs = judged.flatMap { d -> main(d).filter { beginner || it.category != ExerciseCategoryV2.FINGER }.map { d to it } }
            if (pairs.isNotEmpty()) {
                val familiar = pairs.count { (d, def) -> known(d.day, def) }.toDouble() / pairs.size
                if (familiar < share) flags += Flag(Check.FEW_FAMILIAR, judged.last().day,
                    "%.0f %% of %d main exercises Basic or known (at least %.0f %%); not: %s".format(familiar * 100, pairs.size, share * 100,
                        pairs.filter { (d, def) -> !known(d.day, def) }.map { it.second.slug }.groupingBy { it }.eachCount()
                            .entries.sortedByDescending { it.value }.take(4).joinToString { "${it.key} ×${it.value}" }))
            }
        }

        // At most one new exercise per training (two with "Open"); more only where nothing known fitted the slot.
        val limit = if (choice == com.cruxcoach.athlete.model.ExerciseChoice.OPEN) 2 else 1
        days.filter { it.trained && it.suggestion != null && !it.suggestion.board }.forEach { d ->
            val new = counted(d)
            if (new.size <= limit) return@forEach
            val owned = d.equipmentToday ?: p.equipment
            val inSession = coachItems(d).map { it.slug }.toSet()
            // The catalogue's progression chains: one chain per training.
            val chains = inSession.flatMap { s -> catalog.chainOf(s).map { it.slug } }.toSet()
            // Forced: nothing Basic or known of the same kind fits the place, outside the training's chains, and at
            // most one difficulty step easier than the new pick (which stands for the athlete's level).
            val forced = new.drop(limit).all { def ->
                catalog.all.none { o -> o.category == def.category && o.slug !in inSession && o.slug !in chains && known(d.day, o) &&
                    o.difficulty >= def.difficulty - 1 && !o.needsClimbingWall && ExerciseDefinition.TAG_COMEBACK !in o.tags &&
                    o.equipment.all { EquipmentV2.satisfied(it, owned) } && (LoadDomain.FINGER in def.domains || LoadDomain.FINGER !in o.domains) }
            }
            flags += Flag(if (forced) Check.NEW_FORCED else Check.NEW_PER_SESSION, d.day,
                "${new.size} new (${new.joinToString { "${it.slug} ${it.familiarity.name.lowercase()}" }}), allowed $limit")
        }

        if (p.level == Level.ADVANCED && choice != com.cruxcoach.athlete.model.ExerciseChoice.CLASSICS) {
            val all = days.flatMap { main(it) }
            if (all.isNotEmpty()) {
                val unusual = all.count { it.familiarity != Familiarity.BASIC }.toDouble() / all.size
                if (unusual < ADVANCED_UNUSUAL_SHARE) flags += Flag(Check.UNUSUAL_FOR_ADVANCED, days.last().day,
                    "%.0f %% of %d main exercises Common or Special (at least %.0f %%)".format(unusual * 100, all.size, ADVANCED_UNUSUAL_SHARE * 100))
            }
        }

        if (choice == com.cruxcoach.athlete.model.ExerciseChoice.OPEN) {
            val stretches = days.groupBy { it.day / 14 }.values.filter { it.size == 14 }
            val withSpecial = stretches.count { st -> st.any { d -> main(d).any { it.familiarity == Familiarity.SPECIAL } } }
            if (stretches.isNotEmpty() && withSpecial < stretches.size * OPEN_SPECIAL_STRETCHES) flags += Flag(Check.OPEN_WITHOUT_SPECIAL,
                days.last().day, "a Special exercise in $withSpecial of ${stretches.size} two-week stretches")
        }

        // "Don't know it": something known instead, nothing learned against the exercise, and it stays away for 14 days.
        val taps = days.flatMap { d -> d.dontKnow.map { d to it } }
        if (taps.isNotEmpty()) {
            val familiar = taps.count { (d, t) -> catalog[t.to]?.let { known(d.day, it) } == true }
            if (familiar < taps.size * DONT_KNOW_FAMILIAR_SHARE) flags += Flag(Check.DONT_KNOW_UNFAMILIAR, taps.last().first.day,
                "$familiar of ${taps.size} replacements Basic or known: " +
                    taps.filter { (d, t) -> catalog[t.to]?.let { known(d.day, it) } != true }.joinToString { "${it.second.from} → ${it.second.to}" })
            taps.filter { (_, t) -> kotlin.math.abs(t.affinityChange) > 1e-9 || t.resting }.forEach { (d, t) ->
                flags += Flag(Check.DONT_KNOW_UNFAMILIAR, d.day, "${t.from}: preference changed by %.2f%s".format(t.affinityChange, if (t.resting) ", rests" else ""))
            }
            taps.forEach { (d, t) ->
                val back = days.firstOrNull { o -> o.day in (d.day + 1) until (d.day + 14) &&
                    coachItems(o).any { it.slug == t.from } && catalog[t.from]?.let { known(o.day, it) } != true }
                if (back != null) flags += Flag(Check.DONT_KNOW_BACK, back.day, "${t.from} again ${back.day - d.day} day(s) after “Don't know it”")
            }
        }
    }

    /** The comeback plan's sessions, prompts and climbing, against the truth of the injured tissue ([SimInjury]). */
    private fun comeback(r: SimRun, flags: MutableList<Flag>) {
        val injury = r.persona.events.filterIsInstance<Event.Injury>().firstOrNull { it.comeback } ?: return
        val ladder = SimComeback.ladderFor(injury)
        val days = r.days.filter { it.comeback != null }
        // The stage's pain rule as the plan applies it: some findings have their own (a rupture, a tenosynovitis).
        fun rule(d: DayTrace, stage: Int): com.cruxcoach.athlete.logic.PainRule {
            val spec = ladder.stages[stage.coerceIn(0, ladder.stages.lastIndex)]
            val model = d.activeInjuries.firstOrNull { it.id == "injury-${injury.day}" } ?: return spec.pain
            return com.cruxcoach.athlete.logic.Comeback.painRule(model, spec)
        }
        val step = r.persona.smallestIncrementKg
        days.forEach { d ->
            val cb = d.comeback!!
            // The plan's load for the injured hand stays within the stage's range of the athlete's reference (one step of rounding).
            cb.sessions.flatMap { s -> s.sets.map { s to it } }
                .filter { (_, set) -> set.injured && set.rangeKg != null && set.plannedKg != null && set.plannedKg > set.rangeKg.endInclusive + step + 1e-6 }
                .distinctBy { (s, set) -> s.kind to set.slug }
                .forEach { (s, set) -> flags += Flag(Check.COMEBACK_LOAD, d.day, "stage ${s.stage}, ${s.kind.name.lowercase()}: ${set.slug} planned %.1f kg, the stage's range is %.1f–%.1f kg"
                    .format(set.plannedKg, set.rangeKg!!.start, set.rangeKg.endInclusive)) }
            // Where the stage asks for an effort instead, the planned load must not feel far harder than that.
            cb.sessions.flatMap { s -> s.sets.map { s to it } }
                .filter { (_, set) -> set.injured && set.rangeKg == null && set.effort != null && set.plannedEffort != null && set.plannedEffort > set.effort.last * 1.5 }
                .distinctBy { (s, set) -> s.kind to set.slug }
                .forEach { (s, set) -> flags += Flag(Check.COMEBACK_LOAD, d.day, "stage ${s.stage}, ${s.kind.name.lowercase()}: ${set.slug} planned %.1f kg, about %.0f/10 where the stage asks for %d–%d"
                    .format(set.plannedKg, set.plannedEffort, set.effort!!.first, set.effort.last)) }
            // The day's trace has the stage after the athlete's answer; the week behind it was at the stage before.
            val before = r.days.lastOrNull { it.day < d.day && it.comeback != null }?.comeback?.stage ?: cb.stage
            // The next stage is offered only after a week that stayed within the stage's pain rule (the truth, not the diary).
            if (com.cruxcoach.athlete.logic.Proposal.MOVE_UP in cb.reviews) {
                val rule = rule(d, before)
                val week = r.days.filter { it.day in (d.day - 7) until d.day }.mapNotNull { it.comeback }
                val worst = week.flatMap { it.sessions }.maxOfOrNull { it.painMax } ?: 0
                val mornings = week.mapNotNull { it.morningPain }
                if (worst > rule.amberMax || mornings.any { it >= 5 }) flags += Flag(Check.COMEBACK_EARLY, d.day,
                    "stage ${before + 1} offered after a week with session pain up to $worst (amber up to ${rule.amberMax}) and mornings ${mornings.joinToString("/")}")
                // Steady morning pain above the rule, rising or not (owner 2026-11-09): the week up to the review morning.
                val steady = r.days.filter { it.day in (d.day - 6)..d.day }.mapNotNull { it.comeback?.morningPain }.sorted()
                val middle = steady.getOrNull(steady.size / 2)
                if (middle != null && middle > rule.greenMax) flags += Flag(Check.COMEBACK_EARLY, d.day,
                    "stage ${before + 1} offered with mornings ${steady.joinToString("/")}, the middle one above the rule of ${rule.greenMax}")
                // No step up without at least one morning value (owner 2026-11-09).
                if (steady.isEmpty()) flags += Flag(Check.COMEBACK_UP_NO_MORNING, d.day, "stage ${before + 1} offered without a morning value since day ${d.day - 6}")
            }
            // The next stage asks for the week's loading: offered after a week with only a few session days, it comes untrained.
            if (com.cruxcoach.athlete.logic.Proposal.MOVE_UP in cb.reviews) {
                val planSessions = ladder.stages[before.coerceIn(0, ladder.stages.lastIndex)].sessions.filter { !it.optional && it.items.isNotEmpty() }
                // Only the stage's planned kinds count: an optional tendon warm-up is not the loading the next stage builds on.
                val planKinds = planSessions.map { it.kind }.toSet()
                val sessionDays = r.days.filter { it.day in (d.day - 7) until d.day }
                    .count { day -> day.comeback?.sessions?.any { it.kind in planKinds } == true }
                // A stage that plans fewer session days (two strength sessions a week) asks for those.
                val planned = planSessions.maxOfOrNull { minOf(7, (it.perWeek + it.perDay - 1) / it.perDay) } ?: 0
                val needed = minOf(COMEBACK_UP_SESSION_DAYS, planned)
                if (sessionDays < needed) flags += Flag(Check.COMEBACK_UP_UNTRAINED, d.day,
                    "stage ${before + 1} offered after a week with its planned sessions on $sessionDays day(s), the stage plans $planned")
            }
            // No more sessions of a kind in a day than the stage plans.
            cb.sessions.groupBy { it.kind to it.stage }.forEach { (key, list) ->
                val spec = ladder.stages[key.second].sessions.firstOrNull { it.kind == key.first } ?: return@forEach
                if (list.size > spec.perDay) flags += Flag(Check.COMEBACK_SPACING, d.day, "${list.size} ${key.first.name.lowercase()} sessions, the stage plans ${spec.perDay} a day")
            }
            // Board days wait until the stage's climbing is hard.
            val level = cb.climbing
            if (level != null && level < com.cruxcoach.athlete.logic.ClimbingLevel.HARD && d.suggestion?.board == true) flags += Flag(Check.COMEBACK_CLIMBING, d.day,
                "a board session while the stage allows ${level.name.lowercase()} climbing")
        }
        // Sessions that truly hurt beyond the stage's amber limit: the plan steps back, repeats the stage or asks within a week.
        // The athlete reports the true pain, so the plan saw it too.
        var lastFlag = -99
        days.forEach { d ->
            val rule = rule(d, d.comeback!!.stage)
            val redDays = r.days.filter { it.day in (d.day - 6)..d.day }
                .filter { w -> w.comeback?.sessions.orEmpty().any { it.painMax > rule.amberMax } }
            val red = redDays.sumOf { w -> w.comeback!!.sessions.count { it.painMax > rule.amberMax } }
            if (red < COMEBACK_RED_SESSIONS || d.day - lastFlag < 7) return@forEach
            // Only what came after the first painful session counts as the plan's answer to it (a move up just before
            // the pain is no step back).
            val first = redDays.first()
            val window = r.days.filter { it.day in first.day..(d.day + 7) }
            val reacted = window.any { w ->
                val c = w.comeback ?: return@any false
                c.reviews.any { it == com.cruxcoach.athlete.logic.Proposal.STEP_BACK || it == com.cruxcoach.athlete.logic.Proposal.REPEAT } ||
                    c.prompts.any { it.startsWith("worse") } || c.stage < first.comeback!!.stage
            }
            // The plan ended or the run ended before a week passed: nothing to judge.
            val judged = r.days.lastOrNull()?.let { it.day >= d.day + 7 } == true && window.count { it.comeback != null } >= 8
            if (!reacted && judged) { lastFlag = d.day; flags += Flag(Check.COMEBACK_NO_STEP_BACK, d.day,
                "$red session(s) above ${rule.amberMax}/10 at stage ${d.comeback.stage} within a week, no step back, repeat or question") }
        }
        // Strength sessions with a rest in between.
        days.flatMap { d -> d.comeback!!.sessions.filter { it.kind == com.cruxcoach.athlete.model.ComebackSessionKind.STRENGTH }.map { d to it } }
            .zipWithNext().forEach { (a, b) ->
                val hours = (b.second.atMs - a.second.atMs) / 3_600_000.0
                if (hours < COMEBACK_STRENGTH_GAP_H) flags += Flag(Check.COMEBACK_SPACING, b.first.day, "strength sessions %.0f h apart".format(hours))
            }
        // A stage that stayed calm for three weeks should have moved on.
        var since = days.firstOrNull() ?: return
        days.forEach { d ->
            if (d.comeback!!.stage != since.comeback!!.stage) since = d
            val held = d.day - since.day
            if (held == COMEBACK_STUCK_DAYS) {
                val rule = rule(d, d.comeback.stage)
                val last = r.days.filter { it.day in (d.day - 14)..d.day }.mapNotNull { it.comeback }
                val calm = last.flatMap { it.sessions }.all { it.painMax <= rule.greenMax } && last.mapNotNull { it.morningPain }.all { it <= 2 }
                if (calm && d.comeback.stage < ladder.stages.lastIndex) flags += Flag(Check.COMEBACK_STUCK, d.day,
                    "stage ${d.comeback.stage} for $held days, the last two weeks within its pain rule")
            }
        }
    }
}
