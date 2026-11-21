package com.cruxcoach.android.athlete.sim

import com.cruxcoach.android.athlete.sim.SimAthlete.Quality
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.BlockPhase
import com.cruxcoach.athlete.logic.LoadRules
import com.cruxcoach.athlete.logic.ProgressionAdvisor
import com.cruxcoach.athlete.logic.StrengthMath
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * A yardstick for the coach's progression (owner 2026-11-21: "our progression must not get weaker than the
 * reference"). A plain, well-known progression made of the usual building blocks trains the same hidden athlete on
 * the same days, with the same exercises and the same number of sets as the app did in [run]. Only the targets are
 * its own:
 *
 * - **Loaded repetitions:** the load for the top of the range from an estimated maximum and the planned reserve, in
 *   the athlete's weight steps, with the repetitions the estimate allows at that load (a coarse step costs
 *   repetitions, not the set). The estimate is the best set of the last training and falls by 5 % a training at most;
 *   the first training starts where the app started.
 * - **Body-weight repetitions and timed holds:** one repetition or a few seconds more after a training in which every
 *   set was reached with the planned reserve; the same again otherwise; back to what was managed after a training
 *   that was too hard. At the top of the range, the next exercise of the chain when the equipment is there.
 * - **Weeks:** the planned reserve is 2, 1 in the third load week of a block (closer to failure) and 3 in the intro
 *   and the lighter week.
 *
 * Finger work, warm-ups, climbing and stretches are replayed exactly as the app planned them: the app's finger rules
 * are safety rules, and the comparison is about strength work away from the fingers.
 *
 * The comparison ([ReferenceComparison]) asks one thing: on the same schedule, does the app's way of choosing
 * targets build as much as this one?
 */
class ReferenceCoach(private val run: SimRun) {

    private val persona = run.persona
    private val catalog = run.catalog
    private val athlete = SimAthlete(persona, Random(persona.seed * 7 + 1))

    /** Where the reference stands with one of the app's exercises: its own step of the chain and its own numbers. */
    private class State(var slug: String, var e1rm: Double? = null, var reps: Int? = null, var seconds: Int? = null, var hardInARow: Int = 0)

    private val states = mutableMapOf<Pair<String, Side?>, State>()
    /** Trainings in which a quality got its stimulus (two sets close to failure), for the report. */
    val effective = mutableMapOf<Quality, Int>()

    fun replay(): Map<Quality, Double> {
        run.days.forEach(::day)
        return athlete.value.toMap()
    }

    private fun day(d: DayTrace) {
        athlete.dayPenalty = 0.0
        if (d.away) { athlete.night(d.day); return }
        val ill = persona.events.filterIsInstance<Event.Illness>().any { d.day in it.day..it.until }
        val c = d.checkin
        if (c != null && ((c.sleep ?: 5) <= 2 || (c.energy ?: 5) <= 2)) athlete.dayPenalty = 0.05
        if (ill) athlete.dayPenalty = 0.15
        d.climbed?.let { athlete.climbed(d.day, share(it.intensity)) }
        if ("board as suggested" in d.events) athlete.climbed(d.day, share(ClimbIntensity.HARD))
        if (d.sets.isNotEmpty()) train(d)
        athlete.night(d.day)
    }

    private fun share(i: ClimbIntensity) = when (i) {
        ClimbIntensity.LIGHT -> 0.2
        ClimbIntensity.VOLUME -> 0.5
        ClimbIntensity.HARD -> 0.8
        ClimbIntensity.LIMIT -> 1.0
    }

    private fun reserveFor(d: DayTrace): Int {
        val block = d.block ?: return 2
        return when (block.phase) {
            BlockPhase.BUILD -> if (block.weekInBlock >= 3) 1 else 2
            else -> 3
        }
    }

    /** Stepped by the reference; everything else is replayed as the app planned it. */
    private fun stepped(def: ExerciseDefinition, set: DoneSet): Boolean =
        set.setType == SetType.WORK && athlete.qualityOf(def) in COMPARED &&
            def.category !in setOf(ExerciseCategoryV2.MOBILITY, ExerciseCategoryV2.WARMUP, ExerciseCategoryV2.TECHNIQUE, ExerciseCategoryV2.ENDURANCE) &&
            when (def.kind) {
                ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> true
                ExerciseKind.TIME -> def.load != LoadMode.EXTERNAL && def.load != LoadMode.BODYWEIGHT_PLUS
                else -> false
            }

    private class Target(val def: ExerciseDefinition, val reps: Int?, val seconds: Int?, val loadKg: Double?)
    private class Done(val reps: Int?, val seconds: Double?, val rir: Int?, val reached: Boolean, val short: Boolean)

    private fun train(d: DayTrace) {
        val need = reserveFor(d)
        val hard = mutableMapOf<Quality, Int>()
        val targets = mutableMapOf<Pair<String, Side?>, Target>()
        val results = mutableMapOf<Pair<String, Side?>, MutableList<Done>>()
        d.sets.forEachIndexed { index, s ->
            val appDef = catalog.fallbackFor(s.slug)
            val key = s.slug to s.side
            val target = if (stepped(appDef, s)) targets.getOrPut(key) { plan(appDef, key, s, need) } else null
            val def = target?.def ?: appDef
            val planned = ExerciseSet(
                id = "ref-${d.day}-$index", workoutId = "ref-${d.day}", exerciseSlug = def.slug, blockIndex = 0, setIndex = s.setIndex,
                setType = s.setType, side = s.side,
                targetReps = if (target != null) target.reps else s.targetReps, reps = if (target != null) target.reps else s.reps,
                targetDurationS = if (target != null) target.seconds?.toDouble() else s.targetDurationS,
                durationS = if (target != null) target.seconds?.toDouble() else s.durationS,
                targetLoadKg = if (target != null) target.loadKg else s.targetLoadKg, loadKg = if (target != null) target.loadKg else s.loadKg,
                edgeMm = s.edgeMm,
            )
            val r = athlete.perform(def, planned, persona.alwaysEasy, persona.rirNoise)
            if (s.setType != SetType.WARMUP && def.kind != ExerciseKind.CLIMB) {
                athlete.tire(def, r.trueReserve)
                if (r.trueReserve <= 4.0) hard.merge(athlete.qualityOf(def), 1, Int::plus)
            }
            if (target != null) {
                val wanted = (target.reps ?: target.seconds ?: 0).toDouble()
                val got = r.reps?.toDouble() ?: r.durationS ?: 0.0
                results.getOrPut(key) { mutableListOf() }.add(Done(r.reps, r.durationS, r.reportedRir, got >= wanted - 1e-9, got < wanted * 0.8 - 1e-9))
            }
        }
        targets.forEach { (key, target) -> learn(key, target, results[key].orEmpty(), need) }
        hard.filterValues { it >= 2 }.keys.forEach { effective.merge(it, 1, Int::plus) }
        athlete.adapt(d.day, hard)
    }

    private fun owns(def: ExerciseDefinition) = def.equipment.all { EquipmentV2.satisfied(it, persona.equipment) }

    private fun loaded(def: ExerciseDefinition) = def.kind == ExerciseKind.LOAD_REPS && (def.load == LoadMode.EXTERNAL || def.load == LoadMode.BODYWEIGHT_PLUS)

    private fun total(def: ExerciseDefinition, loadKg: Double): Double =
        if (def.load == LoadMode.BODYWEIGHT_PLUS) athlete.bodyweightKg + loadKg else LoadRules.lifted(def, loadKg)

    private fun logged(def: ExerciseDefinition, totalKg: Double): Double =
        if (def.load == LoadMode.BODYWEIGHT_PLUS) totalKg - athlete.bodyweightKg else LoadRules.logged(def, max(totalKg, 0.0))

    /** The targets of today's sets of one exercise; the first training starts where the app started. */
    private fun plan(appDef: ExerciseDefinition, key: Pair<String, Side?>, first: DoneSet, need: Int): Target {
        val state = states.getOrPut(key) { State(appDef.slug) }
        val def = catalog.fallbackFor(state.slug)
        val top = def.defaults.repsMax ?: def.defaults.repsMin ?: 8
        val bottom = (def.defaults.repsMin ?: top).coerceAtMost(top)
        if (loaded(def)) {
            val e = state.e1rm ?: return Target(def, first.targetReps ?: first.reps ?: top, null, first.targetLoadKg ?: first.loadKg ?: 0.0)
            val step = ProgressionAdvisor.increment(def, persona.smallestIncrementKg)
            fun repsAt(load: Double): Int = (30.0 * (e / total(def, load).coerceAtLeast(1.0) - 1.0) - need).roundToInt()
            var load = StrengthMath.roundTo(logged(def, e / (1.0 + (top + need) / 30.0)), persona.smallestIncrementKg)
            if (def.load == LoadMode.EXTERNAL) load = load.coerceAtLeast(0.0)
            var reps = repsAt(load)
            if (reps < bottom && (def.load == LoadMode.BODYWEIGHT_PLUS || load - step > 0)) { load -= step; reps = repsAt(load) }
            return Target(def, reps.coerceIn(1, top), null, load)
        }
        if (def.kind == ExerciseKind.TIME) {
            return Target(def, null, state.seconds ?: (first.targetDurationS ?: first.durationS ?: 30.0).roundToInt(), null)
        }
        return Target(def, state.reps ?: first.targetReps ?: first.reps ?: top, null, if (appDef.slug == def.slug) first.loadKg else null)
    }

    /** What the training taught: the next targets of this exercise. */
    private fun learn(key: Pair<String, Side?>, target: Target, done: List<Done>, need: Int) {
        if (done.isEmpty()) return
        val state = states.getValue(key)
        val def = target.def
        val reachedWithReserve = done.all { it.reached && (it.rir ?: 0) >= need }
        val tooHard = done.count { it.short } * 2 >= done.size && done.any { it.short }
        state.hardInARow = if (tooHard) state.hardInARow + 1 else 0
        if (loaded(def)) {
            val best = done.mapNotNull { r -> r.reps?.takeIf { it > 0 }?.let { StrengthMath.epley(total(def, target.loadKg ?: 0.0), it + (r.rir ?: 0)) } }.maxOrNull() ?: return
            state.e1rm = max(best, (state.e1rm ?: best) * 0.95)
            return
        }
        val top: Int
        val unit: Int
        val now: Int
        if (def.kind == ExerciseKind.TIME) {
            now = target.seconds ?: return
            unit = if (now < 20) 2 else 5
            top = ((def.defaults.durationS ?: 30) * 2).coerceIn(30, 120)
        } else {
            now = target.reps ?: return
            unit = 1
            top = def.defaults.repsMax ?: 15
        }
        fun managed(): Int = done.map { (it.reps ?: floor(it.seconds ?: 0.0).toInt()) }.sorted()[(done.size - 1) / 2]
        val next = when {
            tooHard -> min(now - unit, managed()).coerceAtLeast(1)
            reachedWithReserve -> now + unit
            else -> now
        }
        // The top of the range with reserve: the next exercise of the chain, started at the bottom of its range.
        val harder = def.harder?.let { catalog[it] }?.takeIf { owns(it) && athlete.qualityOf(it) == athlete.qualityOf(def) }
        val easier = def.easier?.let { catalog[it] }?.takeIf { owns(it) && athlete.qualityOf(it) == athlete.qualityOf(def) }
        when {
            reachedWithReserve && now >= top && harder != null -> moveTo(state, harder)
            state.hardInARow >= 2 && easier != null -> moveTo(state, easier)
            def.kind == ExerciseKind.TIME -> state.seconds = next
            else -> state.reps = next
        }
    }

    private fun moveTo(state: State, to: ExerciseDefinition) {
        state.slug = to.slug
        state.hardInARow = 0
        state.e1rm = null
        state.reps = null
        state.seconds = null
        when {
            loaded(to) -> state.e1rm = athlete.bodyweightKg.takeIf { to.load == LoadMode.BODYWEIGHT_PLUS }
                ?.let { it * (1.0 + ((to.defaults.repsMax ?: 5) + 2) / 30.0) }
            to.kind == ExerciseKind.TIME -> state.seconds = to.defaults.durationS ?: 20
            else -> state.reps = to.defaults.repsMin ?: to.defaults.repsMax ?: 5
        }
    }

    companion object {
        /** Strength away from the fingers: what both coaches step in their own way. */
        val COMPARED = listOf(Quality.PULL, Quality.PUSH, Quality.LEGS, Quality.CORE, Quality.PREHAB)
    }
}

/** The app's run against the reference's replay of it, per quality. */
object ReferenceComparison {

    data class Row(val persona: String, val quality: Quality, val start: Double, val app: Double, val reference: Double,
                   val appTrainings: Int, val referenceTrainings: Int) {
        val appGain get() = (app / start - 1.0) * 100.0
        val referenceGain get() = (reference / start - 1.0) * 100.0
    }

    /** Personas with an injury follow the comeback rules, which the reference does not know: not compared. */
    fun comparable(persona: Persona): Boolean = persona.events.none { it is Event.Injury }

    fun compare(run: SimRun): List<Row> {
        if (!comparable(run.persona) || run.days.isEmpty()) return emptyList()
        val coach = ReferenceCoach(run)
        val reference = coach.replay()
        val app = run.days.last().truth
        // The app's trainings with a stimulus, read from its sets the way the athlete counts them.
        val appTrainings = mutableMapOf<Quality, Int>()
        val probe = SimAthlete(run.persona, Random(1))
        run.days.forEach { d ->
            d.sets.filter { it.setType != SetType.WARMUP && it.trueReserve <= 4.0 }
                .groupingBy { probe.qualityOf(run.catalog.fallbackFor(it.slug)) }.eachCount()
                .filterValues { it >= 2 }.keys.forEach { appTrainings.merge(it, 1, Int::plus) }
        }
        return ReferenceCoach.COMPARED.mapNotNull { q ->
            val trained = run.days.any { d -> d.sets.any { it.setType == SetType.WORK && probe.qualityOf(run.catalog.fallbackFor(it.slug)) == q } }
            if (!trained) null
            else Row(run.persona.id, q, run.persona.start(q), app.getValue(q), reference.getValue(q), appTrainings[q] ?: 0, coach.effective[q] ?: 0)
        }
    }

    /** Behind the reference by more than this many points of gain counts as weaker (the spread between seeds is below it). */
    const val TOLERANCE_POINTS = 1.0

    fun weaker(rows: List<Row>): List<Row> = rows.filter { it.appGain < it.referenceGain - TOLERANCE_POINTS }

    fun report(rows: List<Row>): String = buildString {
        appendLine("# The coach against the reference progression")
        appendLine()
        appendLine("Same hidden athlete, same days, same exercises and sets; only the targets differ. Gain in true capacity in %,")
        appendLine("and the trainings in which the quality got its stimulus (two sets close to failure).")
        appendLine()
        appendLine("| Persona | Quality | App | Reference | App trainings | Reference trainings | |")
        appendLine("|---|---|---:|---:|---:|---:|---|")
        rows.forEach { r ->
            val mark = if (r.appGain < r.referenceGain - TOLERANCE_POINTS) "**weaker**" else ""
            appendLine("| ${r.persona} | ${r.quality.name.lowercase()} | ${"%.1f".format(r.appGain)} | ${"%.1f".format(r.referenceGain)} | ${r.appTrainings} | ${r.referenceTrainings} | $mark |")
        }
        appendLine()
        val weaker = weaker(rows)
        appendLine(if (weaker.isEmpty()) "No quality of any persona is weaker than the reference."
            else "Weaker than the reference: " + weaker.joinToString { "${it.persona}/${it.quality.name.lowercase()}" })
    }
}
