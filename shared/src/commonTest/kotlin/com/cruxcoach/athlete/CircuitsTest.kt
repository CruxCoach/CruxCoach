package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.*
import com.cruxcoach.athlete.logic.*
import com.cruxcoach.athlete.model.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Circuit training (owner 2026-10-17): routine items, the player's order and rests, and the time estimate. */
class CircuitsTest {

    private fun ex(
        slug: String, category: ExerciseCategoryV2, kind: ExerciseKind = ExerciseKind.REPS, load: LoadMode = LoadMode.BODYWEIGHT,
        unilateral: Boolean = false, restS: Int? = 90,
    ) = ExerciseDefinition(slug, category, kind, load, unilateral,
        defaults = Prescription(sets = 3, repsMin = 6, repsMax = 10, durationS = 20, restS = restS), i18n = mapOf("en" to ExerciseText(slug)))

    private val catalog = ExerciseCatalog(1, listOf(
        ex("warmup.arm_circles", ExerciseCategoryV2.WARMUP),
        ex("finger.max_hang", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS, restS = 180),
        ex("pull.pull_up", ExerciseCategoryV2.PULL, restS = 120),
        ex("pull.dumbbell_row", ExerciseCategoryV2.PULL, ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, unilateral = true),
        ex("push.push_up", ExerciseCategoryV2.PUSH, restS = 90),
        ex("core.sit_up", ExerciseCategoryV2.CORE, restS = 60),
        ex("legs.air_squat", ExerciseCategoryV2.LEGS, restS = 60),
        ex("power.explosive_pull_up", ExerciseCategoryV2.POWER, restS = 150),
        ex("power.limit_bouldering", ExerciseCategoryV2.POWER, ExerciseKind.CLIMB, LoadMode.NONE),
        ex("mobility.frog", ExerciseCategoryV2.MOBILITY, ExerciseKind.TIME, LoadMode.NONE),
    ))

    private fun set(
        id: String, block: Int, index: Int, circuit: Int? = null, type: SetType = SetType.WORK, side: Side? = null,
        restS: Int? = 15, roundRestS: Int? = if (circuit != null) 120 else null, slug: String = "pull.pull_up",
    ) = ExerciseSet(id, "w", slug, block, index, type, side, restS = restS, circuit = circuit, roundRestS = roundRestS)

    private fun ids(sets: List<ExerciseSet>) = PlayerQueue.ordered(sets).map { it.id }

    private val pull = catalog.fallbackFor("pull.pull_up")

    // ── Player order ─────────────────────────────────────────────────

    @Test
    fun aCircuitIsPlayedRoundByRound() {
        val sets = (0..2).flatMap { block -> (0..2).map { i -> set("${"ABC"[block]}$i", block, i, circuit = 1) } }
        assertEquals(listOf("A0", "B0", "C0", "A1", "B1", "C1", "A2", "B2", "C2"), ids(sets.shuffled()))
        assertEquals("A0", PlayerQueue.current(sets)?.id)
        val done = sets.map { if (it.id == "A0" || it.id == "B0") it.copy(completedAt = 1) else it }
        assertEquals("C0", PlayerQueue.current(done)?.id)
        assertEquals("A1", PlayerQueue.nextAfter(done, done.first { it.id == "C0" })?.id)
    }

    @Test
    fun withoutCircuitsTheOrderIsExactlyTheOldOne() {
        val old = compareBy<ExerciseSet>({ it.blockIndex }, { it.setIndex }, { it.side?.ordinal ?: -1 })
        val sets = buildList {
            for (block in 0..4) for (i in -2..3) {
                if ((block + i) % 3 == 0) continue
                val type = if (i < 0) SetType.WARMUP else if (block == 4) SetType.TEST else SetType.WORK
                if (block == 1) Side.entries.forEach { add(set("b${block}s$i$it", block, i, type = type, side = it)) }
                else add(set("b${block}s$i", block, i, type = type))
            }
        }
        repeat(5) {
            val shuffled = sets.shuffled()
            assertEquals(shuffled.sortedWith(old).map { it.id }, ids(shuffled))
        }
    }

    @Test
    fun aMemberWithFewerSetsDropsOutOfTheLateRounds() {
        val sets = (0..2).map { set("A$it", 0, it, circuit = 1) } + (0..1).map { set("B$it", 1, it, circuit = 1) }
        assertEquals(listOf("A0", "B0", "A1", "B1", "A2"), ids(sets.reversed()))
        // B ends round 2: the round rest follows; A's last set leaves the circuit.
        assertEquals(120, PlayerQueue.restAfter(sets.first { it.id == "B1" }, sets.first { it.id == "A2" }, pull))
    }

    @Test
    fun warmUpsComeFirstBlockByBlockAndStraightBlocksStayInPlace() {
        val sets = listOf(
            set("F0", 0, 0, restS = 180, slug = "finger.max_hang"), set("F1", 0, 1, restS = 180, slug = "finger.max_hang"),
            set("Aw2", 1, -2, circuit = 1, type = SetType.WARMUP, restS = 60), set("Aw1", 1, -1, circuit = 1, type = SetType.WARMUP, restS = 60),
            set("A0", 1, 0, circuit = 1), set("A1", 1, 1, circuit = 1),
            // A warm-up row without the key (from before circuits) still opens the circuit.
            set("Bw1", 2, -1, type = SetType.WARMUP, restS = 60), set("B0", 2, 0, circuit = 1), set("B1", 2, 1, circuit = 1),
            set("M0", 3, 0, restS = 30, slug = "mobility.frog"),
        )
        assertEquals(listOf("F0", "F1", "Aw2", "Aw1", "Bw1", "A0", "B0", "A1", "B1", "M0"), ids(sets.shuffled()))
        // A warm-up keeps its own rest, also before the first round.
        assertEquals(60, PlayerQueue.restAfter(sets.first { it.id == "Bw1" }, sets.first { it.id == "A0" }, pull))
    }

    @Test
    fun aOneSidedMemberKeepsBothHandsTogetherWithAShortSwitch() {
        val row = catalog.fallbackFor("pull.dumbbell_row")
        val sets = (0..1).flatMap { i ->
            listOf(set("A${i}L", 0, i, circuit = 1, side = Side.LEFT, slug = row.slug), set("A${i}R", 0, i, circuit = 1, side = Side.RIGHT, slug = row.slug),
                set("B$i", 1, i, circuit = 1))
        }
        assertEquals(listOf("A0L", "A0R", "B0", "A1L", "A1R", "B1"), ids(sets.shuffled()))
        val switch = PlayerQueue.restAfter(sets.first { it.id == "A0L" }, sets.first { it.id == "A0R" }, row)
        assertTrue(switch <= 15, "$switch")
        assertEquals(15, PlayerQueue.restAfter(sets.first { it.id == "A0R" }, sets.first { it.id == "B0" }, row))
        // Straight on between the exercises: no rest for the side switch either.
        val straightOn = sets.map { it.copy(restS = 0) }
        assertEquals(0, PlayerQueue.restAfter(straightOn.first { it.id == "A0L" }, straightOn.first { it.id == "A0R" }, row))
    }

    @Test
    fun restsInACircuit() {
        val a0 = set("A0", 0, 0, circuit = 1, restS = 0)
        val b0 = set("B0", 1, 0, circuit = 1, restS = 0)
        val a1 = set("A1", 0, 1, circuit = 1, restS = 0)
        val after = set("X0", 2, 0, restS = 90)
        // Same round: the short rest, 0 = straight on.
        assertEquals(0, PlayerQueue.restAfter(a0, b0, pull))
        assertEquals(20, PlayerQueue.restAfter(a0.copy(restS = 20), b0, pull))
        // End of the round, and leaving the circuit: the round rest.
        assertEquals(120, PlayerQueue.restAfter(b0, a1, pull))
        assertEquals(120, PlayerQueue.restAfter(a1, after, pull))
        // No round rest stored: the set's rest.
        assertEquals(0, PlayerQueue.restAfter(b0.copy(roundRestS = null), a1, pull))
        // The last set of all: no rest.
        assertEquals(0, PlayerQueue.restAfter(a1, null, pull))
        // Outside a circuit nothing changes.
        assertEquals(90, PlayerQueue.restAfter(after, set("X1", 2, 1, restS = 90), pull))
    }

    @Test
    fun positionCountsRoundsAndWorkSetsWithoutWarmUps() {
        val sets = listOf(
            set("Aw", 0, -1, circuit = 1, type = SetType.WARMUP), set("A0", 0, 0, circuit = 1), set("A1", 0, 1, circuit = 1),
            set("A2", 0, 2, circuit = 1), set("B0", 1, 0, circuit = 1), set("B1", 1, 1, circuit = 1),
            set("Xw", 2, -1, type = SetType.WARMUP), set("X0", 2, 0), set("X1", 2, 1),
        )
        fun pos(id: String) = PlayerQueue.position(sets, sets.first { it.id == id })
        assertEquals(PlayerQueue.Position(5, 9, 2, 2, round = 2, rounds = 3, workNumber = 2, workTotal = 2), pos("B1"))
        assertEquals(3, pos("A2").round); assertEquals(3, pos("A2").workNumber); assertEquals(3, pos("A2").workTotal)
        assertNull(pos("Aw").round); assertEquals(3, pos("Aw").rounds); assertNull(pos("Aw").workNumber)
        // Outside a circuit: no rounds, and "set 1 of 2" no longer counts the warm-up.
        val x0 = pos("X0")
        assertNull(x0.round); assertNull(x0.rounds)
        assertEquals(2, x0.setNumber); assertEquals(1, x0.workNumber); assertEquals(2, x0.workTotal)
    }

    @Test
    fun blocksMovingDownNeverTearACircuitApart() {
        // An exercise inserted before the circuit: the members move down one place.
        val shifted = listOf(set("S0", 0, 0), set("N0", 1, 0)) +
            (0..1).flatMap { i -> listOf(set("A$i", 2, i, circuit = 1), set("B$i", 3, i, circuit = 1)) } + set("T0", 4, 0)
        assertEquals(listOf("S0", "N0", "A0", "B0", "A1", "B1", "T0"), ids(shifted.shuffled()))
        // "It hurts" swaps A after round 1: the alternative (block 3) takes over its remaining round, B moves to block 4.
        val swapped = listOf(set("A0", 2, 0, circuit = 1).copy(completedAt = 1), set("B0", 4, 0, circuit = 1).copy(completedAt = 2),
            set("Alt1", 3, 1, circuit = 1), set("B1", 4, 1, circuit = 1), set("S0", 0, 0), set("N0", 1, 0), set("T0", 5, 0))
        assertEquals(listOf("S0", "N0", "A0", "B0", "Alt1", "B1", "T0"), ids(swapped.shuffled()))
        // Keys do not decide where a circuit stands: circuit 2 before circuit 1 stays before it.
        val keys = listOf(set("P0", 0, 0, circuit = 2), set("Q0", 1, 0, circuit = 2), set("R0", 2, 0, circuit = 1), set("U0", 3, 0, circuit = 1))
        assertEquals(listOf("P0", "Q0", "R0", "U0"), ids(keys.reversed()))
    }

    // ── Routine items ────────────────────────────────────────────────

    private fun item(slug: String, circuit: Int? = null, sets: Int = 3, between: Int? = null, round: Int? = null, warmup: Boolean = false,
                     test: Boolean = false, restS: Int? = null) =
        RoutineItem(slug, sets = sets, restS = restS, warmup = warmup, test = test, circuit = circuit, circuitRestS = between, roundRestS = round)

    @Test
    fun eligibility() {
        fun ok(slug: String, warmup: Boolean = false, test: Boolean = false) = Circuits.eligible(catalog[slug], item(slug, warmup = warmup, test = test))
        assertTrue(ok("pull.pull_up")); assertTrue(ok("finger.max_hang")); assertTrue(ok("mobility.frog"))
        assertFalse(ok("pull.pull_up", warmup = true)); assertFalse(ok("pull.pull_up", test = true))
        assertFalse(ok("power.limit_bouldering")); assertFalse(ok("warmup.arm_circles"))
        assertTrue(Circuits.eligible(null, item("user.unknown")))
        fun today(slug: String) = Circuits.eligibleToday(catalog[slug], item(slug))
        assertTrue(today("pull.pull_up")); assertTrue(today("push.push_up")); assertTrue(today("core.sit_up")); assertTrue(today("legs.air_squat"))
        assertFalse(today("finger.max_hang")); assertFalse(today("power.explosive_pull_up")); assertFalse(today("mobility.frog"))
        assertFalse(Circuits.eligibleToday(null, item("user.unknown")))
    }

    @Test
    fun normalizeKeepsCircuitsCanonical() {
        val plain = listOf(item("pull.pull_up"), item("push.push_up"))
        assertSame(plain, Circuits.normalize(plain, catalog))
        // A lone member is no circuit.
        assertEquals(listOf(item("pull.pull_up"), item("push.push_up")),
            Circuits.normalize(listOf(item("pull.pull_up", 5, between = 10, round = 90), item("push.push_up")), catalog))
        // The same key in two separate runs: two circuits; keys renumbered in order; rests from the first member.
        val split = Circuits.normalize(listOf(
            item("pull.pull_up", 7, between = 10, round = 100), item("push.push_up", 7, between = 30),
            item("finger.max_hang"),
            item("core.sit_up", 7), item("legs.air_squat", 7, round = 150),
        ), catalog)
        assertEquals(listOf(1, 1, null, 2, 2), split.map { it.circuit })
        assertEquals(listOf(10, 10, null, null, null), split.map { it.circuitRestS })
        assertEquals(listOf(100, 100, null, 150, 150), split.map { it.roundRestS })
        assertEquals(listOf(IntRange(0, 1), IntRange(3, 4)), Circuits.groups(split))
        // Warm-ups, tests and climbing never join; they also break a circuit apart.
        val broken = Circuits.normalize(listOf(
            item("warmup.arm_circles", 1, warmup = true), item("pull.pull_up", 1), item("power.limit_bouldering", 1),
            item("push.push_up", 1), item("core.sit_up", 1, test = true),
        ), catalog)
        assertTrue(broken.all { it.circuit == null && it.circuitRestS == null && it.roundRestS == null }, "$broken")
        // Normalizing twice changes nothing.
        assertEquals(split, Circuits.normalize(split, catalog))
    }

    @Test
    fun linkUnlinkDissolveRoundsAndRests() {
        val items = listOf(item("finger.max_hang"), item("pull.pull_up", sets = 3), item("push.push_up", sets = 2),
            item("core.sit_up", sets = 4), item("power.limit_bouldering"))
        val two = Circuits.link(items, 1, catalog)
        assertEquals(listOf(null, 1, 1, null, null), two.map { it.circuit })
        assertEquals(3, Circuits.rounds(two, 1))
        val withRests = Circuits.setRests(two, 1, 0, 150)
        // The next item joins and takes the circuit's rests.
        val three = Circuits.link(withRests, 2, catalog)
        assertEquals(listOf(null, 1, 1, 1, null), three.map { it.circuit })
        assertEquals(listOf(null, 0, 0, 0, null), three.map { it.circuitRestS })
        assertEquals(listOf(null, 150, 150, 150, null), three.map { it.roundRestS })
        assertEquals(4, Circuits.rounds(three, 1))
        // An item before the circuit joins it too; climbing cannot.
        assertEquals(listOf(1, 1, 1, 1, null), Circuits.link(three, 0, catalog).map { it.circuit })
        assertEquals(three, Circuits.link(three, 3, catalog))
        // Two circuits linked become one, with the first one's rests.
        val pairs = Circuits.normalize(listOf(item("pull.pull_up", 1, between = 5), item("push.push_up", 1, between = 5),
            item("core.sit_up", 2, between = 40), item("legs.air_squat", 2, between = 40)), catalog)
        val merged = Circuits.link(pairs, 1, catalog)
        assertEquals(listOf(1, 1, 1, 1), merged.map { it.circuit }); assertEquals(listOf(5, 5, 5, 5), merged.map { it.circuitRestS })
        // Unlinking in the middle splits; at the end the last member leaves; a pair falls apart.
        assertEquals(listOf(1, 1, 2, 2), Circuits.unlink(merged, 1, catalog).map { it.circuit })
        assertEquals(listOf(1, 1, 1, null), Circuits.unlink(merged, 3, catalog).map { it.circuit })
        assertEquals(listOf(null, null, null, null, null), Circuits.unlink(two, 1, catalog).map { it.circuit })
        assertEquals(listOf(null, 1, 1, 1), Circuits.unlink(merged, 0, catalog).map { it.circuit })
        // Rounds set every member's sets; dissolving leaves plain items with their own rests.
        assertEquals(listOf(3, 5, 5, 5, 3), Circuits.setRounds(three, 1, 5).map { it.sets })
        val own = Circuits.dissolve(listOf(item("pull.pull_up", 1, between = 10, round = 90, restS = 120), item("push.push_up", 1, between = 10, round = 90)), 1, catalog)
        assertEquals(listOf(item("pull.pull_up", restS = 120), item("push.push_up")), own)
        assertEquals(listOf(null, 3600, 3600), Circuits.setRests(listOf(item("x"), item("pull.pull_up", 1), item("push.push_up", 1)), 1, 99_999, null)
            .map { it.circuitRestS })
    }

    @Test
    fun todaysSuggestionAsOneCircuit() {
        val suggestion = listOf(
            item("warmup.arm_circles", sets = 1, warmup = true), item("pull.pull_up", restS = 150), item("finger.max_hang"),
            item("push.push_up"), item("power.explosive_pull_up"), item("core.sit_up"), item("mobility.frog", sets = 1),
        )
        val circuit = Circuits.asCircuitToday(suggestion, catalog)
        assertEquals(listOf("warmup.arm_circles", "finger.max_hang", "power.explosive_pull_up", "pull.pull_up", "push.push_up", "core.sit_up", "mobility.frog"),
            circuit.map { it.slug })
        assertEquals(listOf(null, null, null, 1, 1, 1, null), circuit.map { it.circuit })
        assertEquals(listOf(null, null, null, 15, 15, 15, null), circuit.map { it.circuitRestS })
        // The longest member rest (pull-ups' own 150 s) after each round.
        assertEquals(150, circuit[3].roundRestS)
        assertEquals(listOf(IntRange(3, 5)), Circuits.groups(circuit))
        // Applying it again changes nothing.
        assertEquals(circuit, Circuits.asCircuitToday(circuit, catalog))
        // Round rest stays within one to three minutes.
        assertEquals(60, Circuits.defaultRoundRest(listOf(item("core.sit_up", restS = 20), item("legs.air_squat", restS = 30)), catalog))
        assertEquals(180, Circuits.defaultRoundRest(listOf(item("pull.pull_up", restS = 300)), catalog))
        assertEquals(120, Circuits.defaultRoundRest(listOf(item("pull.pull_up"), item("core.sit_up")), catalog))
        // Fewer than two items for a circuit: nothing changes.
        val fingerDay = listOf(item("warmup.arm_circles", warmup = true), item("finger.max_hang"), item("pull.pull_up"), item("mobility.frog"))
        assertSame(fingerDay, Circuits.asCircuitToday(fingerDay, catalog))
    }

    // ── Time ─────────────────────────────────────────────────────────

    @Test
    fun aCircuitIsShorterThanTheSameSetsStraight() {
        // 3 × 8 pull-ups, 3 × 12 push-ups, 3 × 15 sit-ups.
        val straight = listOf(
            RoutineItem("pull.pull_up", sets = 3, repsMin = 8, repsMax = 8, restS = 120),
            RoutineItem("push.push_up", sets = 3, repsMin = 12, repsMax = 12, restS = 90),
            RoutineItem("core.sit_up", sets = 3, repsMin = 15, repsMax = 15, restS = 60),
        )
        // Work 3·24 + 3·36 + 3·45 = 315 s, rests 3·(120 + 90 + 60) = 810 s, setup 3·60 s → 1305 s ≈ 22 min.
        assertEquals(22, SessionSuggester.estimateMinutes(straight, catalog))
        // Circuit 15 s / 120 s: 315 s work, 15 s · 3 rounds · 2, 3 · 120 s, setup 180 s → 945 s ≈ 16 min.
        val circuit = straight.map { it.copy(circuit = 1, circuitRestS = 15, roundRestS = 120) }
        assertEquals(16, SessionSuggester.estimateMinutes(circuit, catalog))
        // Without rests set: 15 s and the longest own rest (120 s) – the same.
        assertEquals(16, SessionSuggester.estimateMinutes(straight.map { it.copy(circuit = 1) }, catalog))
        // Uneven sets: rounds = 3, short rests 2 + 2 + 1, round rest 3 × 120 s → 315 − 45 + 75 + 360 + 180 = 885 s ≈ 15 min.
        assertEquals(15, SessionSuggester.estimateMinutes(circuit.mapIndexed { i, it -> if (i == 2) it.copy(sets = 2) else it }, catalog))
    }

    /** The estimate before circuits, kept verbatim, as the reference for routines without circuits. */
    private fun oldEstimate(items: List<RoutineItem>, catalog: ExerciseCatalog): Int =
        (items.sumOf { item ->
            val def = catalog.fallbackFor(item.slug)
            val work = when (def.kind) {
                ExerciseKind.REPS, ExerciseKind.LOAD_REPS -> (item.repsMax ?: item.repsMin ?: 8) * 3
                ExerciseKind.TIME, ExerciseKind.HANG -> item.durationS ?: 20
                ExerciseKind.INTERVAL -> (item.repsPerSet ?: 6) * ((item.workS ?: 7) + (item.restBetweenS ?: 3))
                ExerciseKind.CLIMB -> 60
            }
            val sides = if (def.unilateral) 2 else 1
            item.sets * (work * sides + (item.restS ?: def.defaults.restS ?: 60)) + 60
        } / 60.0).roundToInt().coerceAtLeast(1)

    @Test
    fun routinesWithoutCircuitsKeepTheirEstimate() {
        for (routine in BuiltinRoutines.all) {
            assertEquals(oldEstimate(routine.items, catalog), SessionSuggester.estimateMinutes(routine.items, catalog), routine.builtinKey)
            assertEquals(oldEstimate(routine.items, ExerciseCatalog.EMPTY), SessionSuggester.estimateMinutes(routine.items, ExerciseCatalog.EMPTY))
        }
    }

    @Test
    fun aSwapInACircuitKeepsTheCircuit() {
        val routine = Routine("s", "S", items = listOf(item("pull.pull_up", 1, between = 15, round = 120),
            item("push.push_up", 1, between = 15, round = 120), item("core.sit_up", 1, between = 15, round = 120)))
        val suggestion = SessionSuggestion(SuggestionFocus.PULL_PUSH, routine, reasons = emptyList(), estimatedMinutes = 0)
        val swapped = SessionSuggester.withSwaps(suggestion, mapOf("push.push_up" to "legs.air_squat"), catalog).routine.items
        assertEquals(listOf(1, 1, 1), swapped.map { it.circuit })
        assertEquals(listOf(15, 15, 15), swapped.map { it.circuitRestS })
        // Left out today: the other two stay a circuit; one alone is none.
        val dropped = SessionSuggester.withSwaps(suggestion, mapOf("push.push_up" to ""), catalog).routine.items
        assertEquals(listOf(1, 1), dropped.map { it.circuit })
        val single = SessionSuggester.withSwaps(suggestion.copy(routine = routine.copy(items = routine.items.take(2))), mapOf("push.push_up" to ""), catalog)
        assertNull(single.routine.items.single().circuit)
    }

    // ── Storage ──────────────────────────────────────────────────────

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun routineItemsWithoutCircuitFieldsStayAsTheyWere() {
        val serializer = ListSerializer(RoutineItem.serializer())
        // Items stored before circuits read back without one.
        val old = json.decodeFromString(serializer, """[{"slug":"pull.pull_up","sets":4,"restS":150}]""").single()
        assertNull(old.circuit); assertNull(old.circuitRestS); assertNull(old.roundRestS)
        // Unset fields are not written, so older builds see the same JSON as before.
        assertEquals("""[{"slug":"pull.pull_up","sets":4,"restS":150}]""", json.encodeToString(serializer, listOf(old)))
        val linked = old.copy(circuit = 1, circuitRestS = 0, roundRestS = 120)
        assertEquals(listOf(linked), json.decodeFromString(serializer, json.encodeToString(serializer, listOf(linked))))
        // An older build ignores the keys it does not know.
        val seenByOld = json.decodeFromString(ListSerializer(OldItem.serializer()), json.encodeToString(serializer, listOf(linked))).single()
        assertEquals(OldItem("pull.pull_up", 4, 150), seenByOld)
    }

    @Test
    fun setsWithoutCircuitFieldsReadBackWithoutOne() {
        val old = json.decodeFromString(ExerciseSet.serializer(),
            """{"id":"s","workoutId":"w","exerciseSlug":"pull.pull_up","blockIndex":0,"setIndex":1,"restS":90}""")
        assertNull(old.circuit); assertNull(old.roundRestS)
        assertFalse("circuit" in json.encodeToString(ExerciseSet.serializer(), old))
        val member = old.copy(circuit = 2, restS = 15, roundRestS = 120)
        assertEquals(member, json.decodeFromString(ExerciseSet.serializer(), json.encodeToString(ExerciseSet.serializer(), member)))
    }
}

/** What a build from before circuits knows of a routine item. */
@kotlinx.serialization.Serializable
private data class OldItem(val slug: String, val sets: Int = 3, val restS: Int? = null)
