package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.model.RoutineItem

/**
 * Circuit training (owner 2026-10-17): instead of all pull-ups, then all
 * push-ups, then all sit-ups, one set of each in turn, repeated for N rounds –
 * a short rest from one exercise to the next, a longer one after each round.
 *
 * A circuit is a run of consecutive routine items with the same
 * [RoutineItem.circuit] key; its rounds are the members' largest
 * [RoutineItem.sets] (a member with fewer sets drops out of the late rounds).
 * Every edit goes through [normalize], so a stored routine never holds a lone
 * member, a split run under one key, or members with differing rests.
 * Planned sets carry the key and the rests ([com.cruxcoach.athlete.model.ExerciseSet.circuit]),
 * and [PlayerQueue] plays them round by round.
 */
object Circuits {

    /** Seconds from one exercise to the next within a round when the athlete set nothing. */
    const val DEFAULT_CIRCUIT_REST_S = 15
    /** Bounds of the default rest after a round ([defaultRoundRest]). */
    const val MIN_ROUND_REST_S = 60
    const val MAX_ROUND_REST_S = 180
    /** Upper bound for any circuit rest (the backup validator's range). */
    const val MAX_REST_S = 3600
    private const val MAX_ROUNDS = 20

    /** Areas the one-tap circuit of today's suggestion takes in; finger, power and mobility stay out. */
    private val TODAY_CATEGORIES = setOf(
        ExerciseCategoryV2.PULL, ExerciseCategoryV2.PUSH, ExerciseCategoryV2.ANTAGONIST,
        ExerciseCategoryV2.CORE, ExerciseCategoryV2.LEGS,
    )

    /** Can [item] be a circuit member at all: not a warm-up, not a test, not climbing. */
    fun eligible(def: ExerciseDefinition?, item: RoutineItem): Boolean =
        !item.warmup && !item.test && def?.kind != ExerciseKind.CLIMB && def?.category != ExerciseCategoryV2.WARMUP

    /** [eligible], and an area the one-tap circuit of today's suggestion takes in ([asCircuitToday]). */
    fun eligibleToday(def: ExerciseDefinition?, item: RoutineItem): Boolean =
        eligible(def, item) && def != null && def.category in TODAY_CATEGORIES

    private fun RoutineItem.unlinked(): RoutineItem =
        if (circuit == null && circuitRestS == null && roundRestS == null) this
        else copy(circuit = null, circuitRestS = null, roundRestS = null)

    /** Runs of consecutive items with the same non-null key, lone ones included. */
    private fun runs(items: List<RoutineItem>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var i = 0
        while (i < items.size) {
            val key = items[i].circuit
            if (key == null) { i++; continue }
            var end = i
            while (end + 1 < items.size && items[end + 1].circuit == key) end++
            out += i..end
            i = end + 1
        }
        return out
    }

    /**
     * The canonical form: ineligible items and lone members lose the key, a
     * key that appears in two separate runs becomes two circuits, every member
     * carries the circuit's rests (the first member's; a member that has one
     * fills in where the first has none), and keys are renumbered 1..n in
     * order. Items without circuits come back unchanged.
     */
    fun normalize(items: List<RoutineItem>, catalog: ExerciseCatalog): List<RoutineItem> {
        if (items.none { it.circuit != null || it.circuitRestS != null || it.roundRestS != null }) return items
        val cleaned = items.map { item ->
            if (item.circuit == null || !eligible(catalog[item.slug], item)) item.unlinked() else item
        }
        val out = cleaned.toMutableList()
        var next = 1
        for (range in runs(cleaned)) {
            if (range.first == range.last) { out[range.first] = cleaned[range.first].unlinked(); continue }
            val members = range.map { cleaned[it] }
            val circuitRest = members.firstNotNullOfOrNull { it.circuitRestS }
            val roundRest = members.firstNotNullOfOrNull { it.roundRestS }
            val key = next++
            for (i in range) out[i] = cleaned[i].copy(circuit = key, circuitRestS = circuitRest, roundRestS = roundRest)
        }
        return out
    }

    /** Index ranges of the circuits in [items] (runs of at least two members), in order. */
    fun groups(items: List<RoutineItem>): List<IntRange> = runs(items).filter { it.last > it.first }

    /** Rounds of circuit [key]: its members' largest number of sets; 0 when there is no such circuit. */
    fun rounds(items: List<RoutineItem>, key: Int): Int = items.filter { it.circuit == key }.maxOfOrNull { it.sets } ?: 0

    /**
     * Links item [index] with the next one ("Mit nächster als Zirkel"): a new
     * circuit of the two, the next item joining [index]'s circuit, [index]
     * joining the next one's, or both circuits merged. Rests of an existing
     * circuit are kept (the earlier circuit's when two merge).
     */
    fun link(items: List<RoutineItem>, index: Int, catalog: ExerciseCatalog): List<RoutineItem> {
        val norm = normalize(items, catalog)
        if (index !in norm.indices || index + 1 !in norm.indices) return norm
        val a = norm[index]
        val b = norm[index + 1]
        if (!eligible(catalog[a.slug], a) || !eligible(catalog[b.slug], b)) return norm
        if (a.circuit != null && a.circuit == b.circuit) return norm
        val runs = groups(norm)
        val from = runs.firstOrNull { index in it }?.first ?: index
        val to = runs.firstOrNull { index + 1 in it }?.last ?: (index + 1)
        val members = (from..to).map { norm[it] }
        val key = a.circuit ?: b.circuit ?: ((norm.mapNotNull { it.circuit }.maxOrNull() ?: 0) + 1)
        val circuitRest = members.firstNotNullOfOrNull { it.circuitRestS }
        val roundRest = members.firstNotNullOfOrNull { it.roundRestS }
        val out = norm.toMutableList()
        for (i in from..to) out[i] = norm[i].copy(circuit = key, circuitRestS = circuitRest, roundRestS = roundRest)
        return normalize(out, catalog)
    }

    /**
     * Undoes the link after item [index]: the circuit ends with [index] and
     * the members after it go on as a circuit of their own; for the last
     * member, [index] leaves the circuit. A part left with one member is no
     * circuit any more.
     */
    fun unlink(items: List<RoutineItem>, index: Int, catalog: ExerciseCatalog): List<RoutineItem> {
        val norm = normalize(items, catalog)
        if (norm.getOrNull(index)?.circuit == null) return norm
        val run = groups(norm).first { index in it }
        val out = norm.toMutableList()
        if (index < run.last) {
            val key = (norm.mapNotNull { it.circuit }.maxOrNull() ?: 0) + 1
            for (i in index + 1..run.last) out[i] = norm[i].copy(circuit = key)
        } else {
            out[index] = norm[index].unlinked()
        }
        return normalize(out, catalog)
    }

    /** Dissolves circuit [key]: its members are plain items again, with their own sets and rests. */
    fun dissolve(items: List<RoutineItem>, key: Int, catalog: ExerciseCatalog): List<RoutineItem> =
        normalize(items.map { if (it.circuit == key) it.unlinked() else it }, catalog)

    /** Sets every member of circuit [key] to [rounds] sets (1–20). */
    fun setRounds(items: List<RoutineItem>, key: Int, rounds: Int): List<RoutineItem> {
        val r = rounds.coerceIn(1, MAX_ROUNDS)
        return items.map { if (it.circuit == key) it.copy(sets = r) else it }
    }

    /** Rests of circuit [key] on every member; null = the default ([DEFAULT_CIRCUIT_REST_S], [defaultRoundRest]). */
    fun setRests(items: List<RoutineItem>, key: Int, circuitRestS: Int?, roundRestS: Int?): List<RoutineItem> {
        val between = circuitRestS?.coerceIn(0, MAX_REST_S)
        val round = roundRestS?.coerceIn(0, MAX_REST_S)
        return items.map { if (it.circuit == key) it.copy(circuitRestS = between, roundRestS = round) else it }
    }

    /**
     * Rest after a round when the athlete set none: the longest rest a member
     * would have on its own (the item's, else the catalogue's), kept between
     * one and three minutes.
     */
    fun defaultRoundRest(members: List<RoutineItem>, catalog: ExerciseCatalog): Int {
        val longest = members.maxOfOrNull { it.restS ?: catalog[it.slug]?.defaults?.restS ?: PlayerQueue.DEFAULT_REST_S }
            ?: PlayerQueue.DEFAULT_REST_S
        return longest.coerceIn(MIN_ROUND_REST_S, MAX_ROUND_REST_S)
    }

    /**
     * [items] with the default rests written into every circuit member where
     * none is set, so all members of a circuit plan the same rests (the
     * round rest needs all members to be computed).
     */
    fun resolveRests(items: List<RoutineItem>, catalog: ExerciseCatalog): List<RoutineItem> {
        val groups = groups(items)
        if (groups.isEmpty()) return items
        val out = items.toMutableList()
        for (range in groups) {
            val members = range.map { items[it] }
            val between = members.firstNotNullOfOrNull { it.circuitRestS } ?: DEFAULT_CIRCUIT_REST_S
            val round = members.firstNotNullOfOrNull { it.roundRestS } ?: defaultRoundRest(members, catalog)
            for (i in range) out[i] = items[i].copy(circuitRestS = between, roundRestS = round)
        }
        return out
    }

    /**
     * Today's suggestion as one circuit ("Zirkel" chip): the pull, push,
     * antagonist, core and leg items become one circuit block in their order;
     * finger, power and other main items that stay out move before it,
     * mobility after it, warm-ups stay first. 15 s from one exercise to the
     * next, after a round the longest member rest kept within 1–3 minutes.
     * With fewer than two such items nothing changes.
     */
    fun asCircuitToday(items: List<RoutineItem>, catalog: ExerciseCatalog): List<RoutineItem> {
        val memberIdx = items.indices.filter { !items[it].warmup && eligibleToday(catalog[items[it].slug], items[it]) }
        if (memberIdx.size < 2) return items
        val members = memberIdx.map { items[it].unlinked() }
        val warmups = items.indices.filter { items[it].warmup }
        val rest = items.indices.filter { it !in memberIdx && it !in warmups }
        val mobility = rest.filter { catalog[items[it].slug]?.category == ExerciseCategoryV2.MOBILITY }
        val before = rest.filter { it !in mobility }
        val roundRest = defaultRoundRest(members, catalog)
        val circuit = members.map { it.copy(circuit = 1, circuitRestS = DEFAULT_CIRCUIT_REST_S, roundRestS = roundRest) }
        val ordered = warmups.map { items[it].unlinked() } + before.map { items[it].unlinked() } + circuit +
            mobility.map { items[it].unlinked() }
        return normalize(ordered, catalog)
    }
}
