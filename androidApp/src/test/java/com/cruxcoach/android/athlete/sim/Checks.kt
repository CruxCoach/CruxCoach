package com.cruxcoach.android.athlete.sim

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
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
    LOAD_JUMP("Load", "Planned load jumped more than one step or 10 % between sessions"),
    STRUCTURE_LOAD("Load", "Max finger work while the finger load is above 1.5 × its normal"),
    CONVERGENCE("Load", "The app's value is far from what the athlete can really do"),
    STALL("Progress", "An exercise stalled for weeks without a change"),
    DELOAD("Progress", "No lighter week in a block, or no taper before the date"),
    WEEKLY_VOLUME("Plan", "Weekly volume of an area far from its target"),
    SESSION_FIT("Plan", "Suggestion does not fit the session length"),
    TRAINING_DAYS("Plan", "A full training suggested after the week's training days are used up"),
    VARIETY("Plan", "Too little variety"),
    STACKING("Stacking", "Reductions stacked into a pointless session"),
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

    fun isMaxFinger(def: ExerciseDefinition): Boolean =
        (LoadDomain.FINGER in def.domains && (def.kind == ExerciseKind.HANG || def.kind == ExerciseKind.INTERVAL) &&
            (def.load == LoadMode.BODYWEIGHT_PLUS || def.load == LoadMode.EXTERNAL) && "strength" in def.tags) ||
            EquipmentV2.CAMPUS_BOARD in def.equipment

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
            val fingerInjury = d.activeInjuries.any { it.region == com.cruxcoach.athlete.model.InjuryRegion.FINGER }
            val forbidden = when {
                fingerInjury -> "an open finger injury"
                d.returnState?.noMaxFinger == true -> "the return ramp after a break (${d.returnState.reason})"
                youth -> "the youth guardrail"
                novice -> "the first-year guardrail"
                else -> null
            }
            if (forbidden != null && maxFinger.isNotEmpty()) flags += Flag(Check.MAX_FINGER_FORBIDDEN, d.day,
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
                val hit = defs.filter { def -> injury.region.bodyRegions.any { it in def.contraindications } ||
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
        val firstLoads = mutableMapOf<Pair<String, com.cruxcoach.athlete.model.Side?>, Pair<Int, Double>>()
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
                    firstLoads[key]?.let { (day, before) ->
                        val step = LoadRules.lifted(def, p.smallestIncrementKg) * if (EquipmentV2.BARBELL in def.equipment) 2 else 1
                        val allowed = max(step, before * JUMP_SHARE) + 1e-6
                        // A bigger step is fine after a session in which every work set had 3+ in reserve: the athlete said so.
                        val last = days.firstOrNull { it.day == day }?.sets.orEmpty().filter { it.slug == key.first && it.side == key.second && it.setType == SetType.WORK }
                        val easy = last.isNotEmpty() && last.all { (it.rir ?: 0) >= 3 }
                        if (total - before > allowed && !easy) flags += Flag(Check.LOAD_JUMP, d.day,
                            "${key.first}${key.second?.let { " (${it.name.lowercase()})" } ?: ""}: %.1f → %.1f kg total since day $day".format(before, total))
                    }
                    firstLoads[key] = d.day to total
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
        return flags
    }
}
