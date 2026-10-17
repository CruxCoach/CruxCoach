package com.cruxcoach.android.ui.training

import android.app.Application
import com.cruxcoach.android.athlete.PainAction
import com.cruxcoach.athlete.logic.PlayerQueue
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEventKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Circuits and the training view's actions against the real service and
 * database (no UI): planning, the player's order, swaps, "it hurts", ending
 * exercises with undo, "+ Runde", "+ Satz" and saving as a routine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CircuitServiceTest : AthleteScreenTest() {

    /** Pull-ups 3 ×, push-ups 3 ×, dead bugs 2 × as one circuit with the default rests. */
    private val circuit = listOf(
        RoutineItem("pull.pull_up", sets = 3, repsMin = 5, repsMax = 8, restS = 150, circuit = 1),
        RoutineItem("push.push_up", sets = 3, repsMin = 10, repsMax = 10, circuit = 1),
        RoutineItem("core.dead_bug", sets = 2, repsMin = 10, repsMax = 10, circuit = 1),
    )

    @Before
    fun bodyWeight() {
        repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
    }

    private fun start(items: List<RoutineItem> = circuit) = service.startWorkout(Routine("r", "Circuit", items = items), null)

    /** The player's order as "exercise + set index" (both hands of a one-sided set read once). */
    private fun order(id: String) = PlayerQueue.ordered(repo.setsFor(id)).map { it.exerciseSlug.substringAfter('.') + it.setIndex }.distinct()

    private fun block(id: String, slug: String) = repo.setsFor(id).first { it.exerciseSlug == slug }.blockIndex

    private fun completeNext(id: String, count: Int) = repeat(count) {
        val next = PlayerQueue.current(repo.setsFor(id)) ?: error("nothing open in $id")
        service.completeSetDetailed(next, startRest = false)
    }

    @Test
    fun `a routine with a circuit is planned and played round by round`() {
        val id = start()
        val sets = repo.setsFor(id)
        assertEquals(setOf(1), sets.map { it.circuit }.toSet())
        // 15 s between the exercises, after a round the longest own rest (pull-ups' 150 s).
        assertEquals(setOf(15), sets.map { it.restS }.toSet())
        assertEquals(setOf(150), sets.map { it.roundRestS }.toSet())
        assertEquals(listOf("pull_up0", "push_up0", "dead_bug0", "pull_up1", "push_up1", "dead_bug1", "pull_up2", "push_up2"), order(id))
        val ordered = PlayerQueue.ordered(sets)
        val def = service.catalog.fallbackFor("pull.pull_up")
        assertEquals(15, PlayerQueue.restAfter(ordered[0], ordered[1], def))
        assertEquals(150, PlayerQueue.restAfter(ordered[2], ordered[3], def))
        assertEquals(listOf(PlayerQueue.Page(listOf(0, 1, 2), 1)), PlayerQueue.pages(sets))
        val position = PlayerQueue.position(sets, ordered[4])
        assertEquals(2, position.round); assertEquals(3, position.rounds); assertEquals(2, position.workNumber)
    }

    @Test
    fun `warm-ups of circuit members come before the first round`() {
        val id = start(listOf(RoutineItem("warmup.arm_circles", sets = 1, repsMin = 10, repsMax = 10, restS = 15, warmup = true)) + circuit)
        val sets = repo.setsFor(id)
        val ramps = sets.filter { it.setType == SetType.WARMUP && it.exerciseSlug != "warmup.arm_circles" }
        assertTrue("$sets", ramps.any { it.exerciseSlug == "pull.pull_up" })
        assertTrue("$ramps", ramps.all { it.circuit == 1 && it.setIndex < 0 })
        val ordered = PlayerQueue.ordered(sets)
        val firstWork = ordered.indexOfFirst { it.setType == SetType.WORK }
        assertEquals("warmup.arm_circles", ordered.first().exerciseSlug)
        assertTrue(ordered.take(firstWork).containsAll(ramps))
        assertEquals(listOf("pull_up0", "push_up0", "dead_bug0"), ordered.drop(firstWork).take(3).map { it.exerciseSlug.substringAfter('.') + it.setIndex })
        assertEquals(2, PlayerQueue.pages(sets).size)
    }

    @Test
    fun `a swapped member stays in its circuit`() {
        val id = start()
        service.swapBlock(id, block(id, "push.push_up"), "push.incline_push_up")
        val swapped = repo.setsFor(id).filter { it.exerciseSlug == "push.incline_push_up" }
        assertEquals(listOf(0, 1, 2), swapped.map { it.setIndex }.sorted())
        assertTrue("$swapped", swapped.all { it.circuit == 1 && it.restS == 15 && it.roundRestS == 150 })
        assertEquals(listOf("pull_up0", "incline_push_up0", "dead_bug0", "pull_up1", "incline_push_up1", "dead_bug1", "pull_up2", "incline_push_up2"),
            order(id))
    }

    @Test
    fun `a pain swap in a circuit takes over the remaining rounds`() {
        val id = start()
        completeNext(id, 3)
        val pull = block(id, "pull.pull_up")
        val outcome = service.painStop(id, pull, InjuryRegion.FINGER, null, 3, PainAction.SWAP)
        assertEquals(PainAction.SWAP, outcome.action)
        val alt = outcome.newSlug!!
        val altSets = repo.setsFor(id).filter { it.exerciseSlug == alt }
        assertEquals(listOf(1, 2), altSets.map { it.setIndex }.distinct().sorted())
        assertTrue("$altSets", altSets.all { it.circuit == 1 && it.restS == 15 && it.roundRestS == 150 && it.blockIndex == pull + 1 })
        val a = alt.substringAfter('.')
        assertEquals(listOf("pull_up0", "push_up0", "dead_bug0", "${a}1", "push_up1", "dead_bug1", "${a}2", "push_up2"), order(id))
        assertEquals(alt, PlayerQueue.current(repo.setsFor(id))?.exerciseSlug)
        assertEquals(listOf(PlayerQueue.Page(listOf(0, 1, 2, 3), 1)), PlayerQueue.pages(repo.setsFor(id)))
    }

    @Test
    fun `ending exercises removes their open sets, logs them as ended early and can be undone`() {
        val id = start(circuit + RoutineItem("legs.air_squat", sets = 2))
        completeNext(id, 4) // round 1 and the second set of pull-ups
        val before = repo.setsFor(id)
        val blocks = listOf("pull.pull_up", "push.push_up", "core.dead_bug", "legs.air_squat").map { block(id, it) }
        val removed = runBlocking { service.endBlocks(id, blocks) }
        assertEquals(before.filter { !it.isCompleted }.map { it.id }.toSet(), removed.map { it.id }.toSet())
        assertEquals(before.filter { it.isCompleted }.toSet(), repo.setsFor(id).toSet())
        val events = repo.suggestionEventsForDay(service.today().toString())
        val ended = events.filter { it.kind == SuggestionEventKind.FEEDBACK && it.focus == PreferenceLearning.ENDED_EARLY }
            .associate { it.slugs.single() to it.value }
        // Squats had no done set: ended, but not "ended early".
        assertEquals(mapOf("pull.pull_up" to 2.0 / 3, "push.push_up" to 1.0 / 3, "core.dead_bug" to 0.5), ended)
        assertTrue(events.none { it.kind == SuggestionEventKind.SKIPPED_SET })
        runBlocking { service.restoreSets(removed) }
        assertEquals(before.toSet(), repo.setsFor(id).toSet())
        assertEquals("push_up1", PlayerQueue.current(repo.setsFor(id))?.let { it.exerciseSlug.substringAfter('.') + it.setIndex })
    }

    @Test
    fun `one more round adds a set for every member and one more set is logged`() {
        val id = start()
        completeNext(id, 2)
        val added = runBlocking { service.addRound(id, 1) }
        assertEquals(listOf("pull.pull_up", "push.push_up", "core.dead_bug"), added.map { it.exerciseSlug })
        assertTrue("$added", added.all { it.setIndex == 3 && it.circuit == 1 && it.restS == 15 && it.roundRestS == 150 && !it.isCompleted })
        assertEquals(added.map { it.id }.toSet(), repo.setsFor(id).filter { it.setIndex == 3 }.map { it.id }.toSet())
        assertEquals(listOf("pull_up3", "push_up3", "dead_bug3"), order(id).takeLast(3))
        assertEquals(4, PlayerQueue.position(repo.setsFor(id), added.first()).rounds)
        assertTrue(runBlocking { service.addRound(id, 9) }.isEmpty())
        // "+ Satz" on push-ups: one more set with its target, and the ledger notes it.
        service.addSet(id, block(id, "push.push_up"))
        val extra = repo.setsFor(id).filter { it.exerciseSlug == "push.push_up" }.maxBy { it.setIndex }
        assertEquals(4, extra.setIndex); assertEquals(extra.targetReps, extra.reps); assertEquals(1, extra.circuit)
        val logged = repo.suggestionEventsForDay(service.today().toString()).single { it.focus == PreferenceLearning.EXTRA_SET }
        assertEquals(listOf("push.push_up"), logged.slugs); assertEquals(5.0, logged.value)
    }

    @Test
    fun `a circuit training saved as a routine starts as the same circuit`() {
        val items = listOf(RoutineItem("core.hollow_hold", sets = 2, durationS = 20, restS = 45)) +
            circuit.mapIndexed { i, item -> if (i == 0) item.copy(circuitRestS = 0, roundRestS = 100) else item }
        val id = start(items)
        val saved = service.saveRoutineFromWorkout(id, "Mine")
        assertEquals(listOf(null, 1, 1, 1), saved.items.map { it.circuit })
        assertEquals(listOf(null, 0, 0, 0), saved.items.map { it.circuitRestS })
        assertEquals(listOf(null, 100, 100, 100), saved.items.map { it.roundRestS })
        assertEquals(listOf(45, null, null, null), saved.items.map { it.restS })
        assertEquals(listOf(2, 3, 3, 2), saved.items.map { it.sets })
        assertEquals(saved.items, repo.routines().single { it.id == saved.id }.items)

        service.discardWorkout(id)
        val again = service.startWorkout(saved, null)
        val sets = repo.setsFor(again)
        assertNull(sets.first { it.exerciseSlug == "core.hollow_hold" }.circuit)
        assertTrue(sets.filter { it.exerciseSlug != "core.hollow_hold" }.all { it.circuit == 1 && it.restS == 0 && it.roundRestS == 100 })
        assertEquals(listOf("hollow_hold0", "hollow_hold1", "pull_up0", "push_up0", "dead_bug0", "pull_up1", "push_up1", "dead_bug1",
            "pull_up2", "push_up2"), order(again))
    }
}
