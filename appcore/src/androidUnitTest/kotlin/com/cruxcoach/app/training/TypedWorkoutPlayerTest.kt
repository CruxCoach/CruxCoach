package com.cruxcoach.app.training

import com.cruxcoach.app.ui.WorkoutPlayerScreenModel
import com.cruxcoach.athlete.logic.WorkoutTextParser
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Typed exercises in the open training (Android `AthleteService.addTyped` + `WorkoutTextSheet`, commit 8d98b494f):
 * a typed circuit gets a key the training does not use yet, so it stays a circuit of its own; the sheet's preview
 * groups a circuit under one line, and "Add" takes what is in the field. Real database and catalogue.
 */
class TypedWorkoutPlayerTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    /** An open training that already has circuit 1: pull-ups and push-ups in turn, three rounds. */
    private val withCircuit = Routine("r", "Zirkel", items = listOf(
        RoutineItem("pull.pull_up", sets = 3, repsMin = 8, repsMax = 8, circuit = 1),
        RoutineItem("push.push_up", sets = 3, repsMin = 15, repsMax = 15, circuit = 1),
    ))

    private fun workBlocks(sets: List<ExerciseSet>): List<Pair<String, Int?>> =
        sets.filter { it.setType == SetType.WORK }.groupBy { it.blockIndex }.entries.sortedBy { it.key }
            .map { (_, block) -> block.first().exerciseSlug to block.first().circuit }

    @Test
    fun `a typed circuit added to an open training with circuit 1 becomes circuit 2`() {
        val id = kit.db { service.startWorkout(withCircuit, null) }
        val before = kit.db { repo.setsFor(id) }
        assertEquals(listOf("pull.pull_up" to 1, "push.push_up" to 1), workBlocks(before))
        val lines = WorkoutTextParser.parse("Zirkel 3 Runden: Dips 10, Sit-ups 20", service.catalog).lines
        assertEquals(listOf(1, 1), lines.map { it.circuit })

        val skipped = kit.db { service.addTyped(id, lines) }
        assertTrue(skipped.isEmpty())
        val after = kit.db { repo.setsFor(id) }
        // Without the offset the typed circuit's key 1 would join the training's circuit.
        assertEquals(listOf("pull.pull_up" to 1, "push.push_up" to 1, "push.dip" to 2, "core.sit_up" to 2), workBlocks(after))
        assertEquals(before.map { it.id to it.circuit }.toSet(), after.filter { s -> before.any { it.id == s.id } }.map { it.id to it.circuit }.toSet())
        assertEquals(3, after.count { it.exerciseSlug == "push.dip" && it.setType == SetType.WORK }, "three rounds")

        // The player plays them as two circuits, one page each.
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("two circuit pages") { vm.state.value.pages.mapNotNull { it.circuit } == listOf(1, 2) }

        // A third typed circuit follows as circuit 3; a typed exercise outside a circuit stays outside.
        val more = WorkoutTextParser.parse("Plank 3x60s\nSupersatz 4 Sätze: Klimmzüge 6 und Dips 10", service.catalog).lines
        kit.db { service.addTyped(id, more) }
        val last = workBlocks(kit.db { repo.setsFor(id) }).drop(4)
        assertEquals(listOf("core.front_plank" to null, "pull.pull_up" to 3, "push.dip" to 3), last)
    }

    @Test
    fun `the typed sheet groups a circuit under one line and adds what is in the field`() {
        val id = kit.db { service.startWorkout(withCircuit, null) }
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("set phase") { vm.state.value.phase == WorkoutPlayerPhase.SET }
        val model = WorkoutPlayerScreenModel(vm, "de")
        val sheet = model.typedReaderOn(kit.scope(), kit.dispatcher)
        assertTrue(sheet.currentState.lines.isEmpty())

        val text = "Klimmzüge 5x5, dann 5 Runden im Zirkel: Dips 10, Kniebeugen 15"
        sheet.setText(text)
        kit.until("read") { sheet.currentState.lines.size == 3 }
        val preview = sheet.currentState
        assertEquals(listOf(false, true, true), preview.lines.map { it.inCircuit })
        assertEquals(listOf(false, true, false), preview.lines.map { it.startsCircuit })
        val header = preview.lines[1].circuitHeader
        assertEquals("trcirc_group", header.key)
        assertEquals("trcirc_rounds", header.args[0].nested!!.key)
        assertEquals(5, header.args[0].nested!!.pluralCount)
        assertEquals("0:15", header.args[1].text)
        assertTrue(preview.lines[0].circuitHeader.isEmpty && preview.lines[2].circuitHeader.isEmpty)
        TrTextCheck.checkAll(preview)

        // "Add" right after a keystroke: the field's text, read now if the sheet has not read it yet.
        sheet.setText("$text\nPlank 3x60s")
        sheet.add("$text\nPlank 3x60s")
        kit.until("typed added") { repo.setsFor(id).any { it.exerciseSlug == "core.front_plank" } }
        val blocks = workBlocks(kit.db { repo.setsFor(id) })
        assertEquals(listOf("pull.pull_up" to 1, "push.push_up" to 1, "pull.pull_up" to null), blocks.take(3))
        assertEquals(listOf(2, 2), blocks.drop(3).take(2).map { it.second })
        assertEquals("push.dip", blocks[3].first)
        assertEquals("core.front_plank" to null, blocks.last())
        assertEquals(6, blocks.size)
    }
}
