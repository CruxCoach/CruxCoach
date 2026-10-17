package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.PainAction
import com.cruxcoach.app.ui.WorkoutHangTimer
import com.cruxcoach.app.ui.WorkoutPlayerScreenModel
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.SuggestionEventKind
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The training player on iOS runs Android's WorkoutPlayerViewModel logic (ported presenter). The cases are
 * Android's ExercisePageTest / OverridesTest / CircuitServiceTest at the view-model level, on a real
 * database with the real catalogue.
 */
class WorkoutPlayerPresenterTest {
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

    private fun player(routine: Routine): Pair<String, WorkoutPlayerPresenter> {
        val id = kit.db { service.startWorkout(routine, null) }
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("set phase") { vm.state.value.phase == WorkoutPlayerPhase.SET }
        return id to vm
    }

    private fun work(id: String, slug: String? = null) = kit.db { repo.setsFor(id) }
        .filter { it.setType == SetType.WORK && (slug == null || it.exerciseSlug == slug) }.sortedBy { it.setIndex }

    private fun workNow(id: String) = repo.setsFor(id).filter { it.setType == SetType.WORK }.sortedBy { it.setIndex }

    private val pushUps = Routine("r", "Push", items = listOf(RoutineItem("push.push_up", sets = 4, repsMin = 10, repsMax = 10, restS = 60)))
    private val weighted = Routine("r", "Pull", items = listOf(
        RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5, loadKg = 20.0, restS = 120)))

    @Test
    fun `one tap logs the set as planned and the next row is due`() {
        val (id, vm) = player(pushUps)
        vm.completeCurrent()
        kit.until("first done") { workNow(id).first().isCompleted }
        assertEquals(10, work(id).first().reps)
        kit.until("next due") { vm.state.value.current?.set?.id == workNow(id)[1].id }
        kit.until("rest armed") { vm.state.value.phase == WorkoutPlayerPhase.REST && vm.state.value.restArmed }
        assertTrue(service.restTimer.value.isRunning)
        assertEquals(60, service.restTimer.value.totalSeconds)
    }

    @Test
    fun `a result chip logs fewer reps in one tap`() {
        val (id, vm) = player(pushUps)
        vm.completeAs(8.0)
        kit.until("8 reps") { workNow(id).first().let { it.isCompleted && it.reps == 8 } }
        kit.until("rest") { vm.state.value.phase == WorkoutPlayerPhase.REST }
    }

    @Test
    fun `missed reps with a load move the next sets and the rows show it`() {
        val (id, vm) = player(weighted)
        vm.completeAs(3.0)
        kit.until("logged 3") { workNow(id).first().let { it.isCompleted && it.reps == 3 } }
        kit.until("lighter") { workNow(id).drop(1).all { (it.loadKg ?: 20.0) < 20.0 } }
        kit.until("adjustment shown") { vm.state.value.adjustment != null && vm.state.value.adjustments.containsKey(workNow(id).first().id) }
    }

    @Test
    fun `a chip during the rest ends it and logs the next set`() {
        val (id, vm) = player(pushUps)
        vm.completeCurrent()
        kit.until("rest armed") { vm.state.value.phase == WorkoutPlayerPhase.REST && vm.state.value.restArmed }
        // The next set is the active row during the rest: one tap logs it.
        vm.completeAs(9.0)
        kit.until("second logged") { workNow(id)[1].let { it.isCompleted && it.reps == 9 } }
        kit.until("latest") { vm.state.value.lastDone?.set?.id == workNow(id)[1].id }
    }

    @Test
    fun `the reserve answer sits on the done row`() {
        val (id, vm) = player(weighted)
        vm.completeCurrent()
        kit.until("rest armed") { vm.state.value.phase == WorkoutPlayerPhase.REST && vm.state.value.restArmed }
        vm.setReserve(work(id).first().id, 2)
        kit.until("reserve") { workNow(id).first().rir == 2 }
    }

    @Test
    fun `ending the exercise after a set takes the rest of it, is noted as ended early and can be undone`() {
        val (id, vm) = player(Routine("r", "Two", items = listOf(
            RoutineItem("push.push_up", sets = 3, repsMin = 10, repsMax = 10, restS = 30),
            RoutineItem("core.hollow_hold", sets = 2, durationS = 20))))
        vm.completeCurrent()
        kit.until("rest") { vm.state.value.phase == WorkoutPlayerPhase.REST && vm.state.value.restArmed }
        vm.showPage(0)
        vm.endExercise()
        kit.until("open sets gone") { work(id, "push.push_up").size == 1 }
        kit.until("ended early noted") { repo.suggestionEventsForDay(service.today().toString())
            .any { it.focus == PreferenceLearning.ENDED_EARLY && it.slugs == listOf("push.push_up") } }
        assertTrue(vm.state.value.endedUndo != null)
        vm.undoEnd()
        kit.until("back") { work(id, "push.push_up").size == 3 }
    }

    @Test
    fun `a load changed in the player carries over to the next sets`() {
        val (id, vm) = player(Routine("r", "Pull", items = listOf(RoutineItem("pull.weighted_pull_up", sets = 4, repsMin = 5, repsMax = 5, loadKg = 20.0))))
        val model = WorkoutPlayerScreenModel(vm, "en")
        model.stepLoad(vm.state.value.current!!.set.id, 1)
        kit.until("all heavier") { workNow(id).all { (it.loadKg ?: 0.0) > 20.0 } }
        val after = work(id)
        assertEquals(1, after.map { it.loadKg }.distinct().size, "${after.map { it.loadKg }}")
        assertTrue(after.drop(1).all { it.targetLoadKg == it.loadKg })
        kit.until("carried line") { model.currentState.rows.any { !it.carriedText.isEmpty } }
    }

    @Test
    fun `the next load set by hand during the rest wins over the reserve answer`() {
        val (id, vm) = player(weighted)
        val model = WorkoutPlayerScreenModel(vm, "en")
        vm.completeCurrent()
        kit.until("rest armed") { vm.state.value.phase == WorkoutPlayerPhase.REST && vm.state.value.restArmed }
        model.stepLoad(vm.state.value.next!!.set.id, -1)
        kit.until("next lighter") { workNow(id).drop(1).all { (it.loadKg ?: 20.0) < 20.0 } }
        val manual = work(id)[1].loadKg
        vm.setReserve(work(id).first().id, 0)
        kit.until("reserve saved") { workNow(id).first().rir == 0 }
        // Everything queued before has run: the answer moved nothing.
        kit.db { }
        Thread.sleep(300)
        assertEquals(manual, work(id)[1].loadKg)
        assertEquals(manual, work(id)[2].loadKg)
    }

    @Test
    fun `a done set is corrected from its row and it can be opened again`() {
        val (id, vm) = player(Routine("r", "Push", items = listOf(RoutineItem("push.push_up", sets = 3, repsMin = 10, repsMax = 10, restS = 60))))
        val model = WorkoutPlayerScreenModel(vm, "en")
        vm.completeCurrent()
        kit.until("rest") { vm.state.value.phase == WorkoutPlayerPhase.REST && workNow(id).first().isCompleted }
        val first = work(id).first().id
        vm.edit(first)
        kit.until("editing") { vm.state.value.editingId == first }
        assertTrue(model.currentState.rows.single { it.id == first }.let { it.stateCode == "editing" && it.steppers.any { s -> s.code == "reps" } })
        model.stepMain(first, -1)
        kit.until("9 reps") { workNow(id).first().reps == 9 }
        vm.closeEdit()
        assertEquals(WorkoutPlayerPhase.REST, vm.state.value.phase)
        vm.reopen(first)
        kit.until("open again") { !workNow(id).first().isCompleted }
        kit.until("due") { vm.state.value.current?.set?.id == first }
        // Open again at the plan.
        assertEquals(10, work(id).first().reps)
    }

    @Test
    fun `the shown exercise is swapped or skipped in the player and the coach learns it`() {
        val (id, vm) = player(Routine("r", "Pull", items = listOf(
            RoutineItem("pull.pull_up", sets = 3, repsMin = 5, repsMax = 5), RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10))))
        kit.until("pull-ups first") { vm.state.value.current?.set?.exerciseSlug == "pull.pull_up" }
        assertTrue(vm.canSwap())
        val alternatives = kit.db { vm.alternatives() }
        assertTrue(alternatives.isNotEmpty() && alternatives.none { it.def.slug == "push.push_up" || it.def.slug == "pull.pull_up" }, "$alternatives")
        val to = alternatives.first().def.slug
        vm.swapExercise(to)
        kit.until("swapped") { repo.setsFor(id).none { it.exerciseSlug == "pull.pull_up" } && repo.setsFor(id).any { it.exerciseSlug == to } }
        // It keeps the replaced exercise's three sets.
        assertEquals(3, kit.db { repo.setsFor(id) }.filter { it.exerciseSlug == to && it.setType == SetType.WORK }.map { it.setIndex }.distinct().size)
        kit.until("ledger") { repo.suggestionEventsForDay(service.today().toString()).any {
            it.kind == SuggestionEventKind.SWAPPED && it.slugs == listOf("pull.pull_up", to) } }
        kit.until("new exercise due") { vm.state.value.current?.set?.exerciseSlug == to }
        vm.skipExercise()
        kit.until("skipped") { repo.setsFor(id).none { it.exerciseSlug == to } && vm.state.value.current?.set?.exerciseSlug == "push.push_up" }
    }

    @Test
    fun `a circuit is played round by round and plus round adds one set per member`() {
        val (id, vm) = player(Routine("r", "Circuit", items = listOf(
            RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10, circuit = 1, circuitRestS = 15, roundRestS = 60),
            RoutineItem("pull.inverted_row", sets = 2, repsMin = 8, repsMax = 8, circuit = 1, circuitRestS = 15, roundRestS = 60))))
        val page = vm.state.value.page!!
        assertTrue(page.circuit != null && page.blockIndexes.size == 2, "$page")
        val firstSlug = vm.state.value.current!!.set.exerciseSlug
        vm.completeCurrent()
        // One set of each in turn: the other member comes next, not the same exercise's second set.
        kit.until("other member") { vm.state.value.current?.set?.let { it.exerciseSlug != firstSlug } == true }
        val model = WorkoutPlayerScreenModel(vm, "en")
        assertTrue(model.currentState.rows.count { it.kind == "round" } == 2)
        vm.addSet()
        kit.until("third round") { repo.setsFor(id).filter { it.setType == SetType.WORK }.map { it.setIndex }.distinct().size == 3 }
    }

    @Test
    fun `it hurts swaps the exercise and remembered strong pain becomes an injury`() {
        val (_, vm) = player(Routine("r", "Pull", items = listOf(RoutineItem("pull.pull_up", sets = 3, repsMin = 5, repsMax = 8))))
        vm.reportPain(InjuryRegion.ELBOW, null, 6, PainAction.END_EXERCISE, remember = true)
        kit.until("result") { vm.state.value.painResult != null }
        val outcome = vm.state.value.painResult!!
        assertEquals(PainAction.END_EXERCISE, outcome.action)
        assertTrue(outcome.injuryId != null)
        assertTrue(kit.db { repo.activeInjuries() }.single().climbingPaused)
        val model = WorkoutPlayerScreenModel(vm, "en")
        assertFalse(model.currentState.painText.isEmpty)
    }

    @Test
    fun `finishing logs how much of the suggestion was done and reports the training`() {
        val id = kit.db { service.startWorkout(pushUps.copy(id = "suggestion:test"), "Today") }
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("set") { vm.state.value.phase == WorkoutPlayerPhase.SET }
        vm.completeCurrent()
        kit.until("first done") { workNow(id).first().isCompleted }
        val finished = runBlocking {
            val waiter = async(kit.dispatcher, CoroutineStart.UNDISPATCHED) { withTimeout(30_000) { vm.events.first() } }
            vm.finish(7, "good")
            waiter.await()
        }
        assertEquals(WorkoutPlayerEvent.Finished(id), finished)
        val w = kit.db { repo.workout(id)!! }
        assertTrue(w.endedAt != null && w.sessionRpe == 7)
        // Planned sets never done are dropped: the log shows what happened.
        assertEquals(1, kit.db { repo.setsFor(id) }.size)
        val completed = kit.db { repo.suggestionEventsForDay(service.today().toString()) }.single { it.kind == SuggestionEventKind.COMPLETED }
        assertEquals(0.25, completed.value!!, 1e-9)
        assertFalse(service.restTimer.value.isRunning)
    }

    @Test
    fun `every text of the player names an existing string in the right format`() {
        val (id, vm) = player(Routine("r", "Mixed", items = listOf(
            RoutineItem("finger.max_hang", sets = 2, durationS = 10, edgeMm = 20, restS = 120),
            RoutineItem("pull.weighted_pull_up", sets = 3, repsMin = 5, repsMax = 5, loadKg = 10.0, restS = 90),
            RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10, restS = 60))))
        val model = WorkoutPlayerScreenModel(vm, "de")
        var checked = TrTextCheck.checkAll(model.currentState)
        vm.state.value.pages.indices.forEach { i ->
            vm.showPage(i)
            vm.state.value.page!!.sets.firstOrNull { !it.isCompleted }?.let { vm.focus(it.id) }
            checked += TrTextCheck.checkAll(model.currentState)
            vm.completeAs(3.0)
            kit.until("logged on page $i") { vm.state.value.lastDone != null }
            kit.db { }
            checked += TrTextCheck.checkAll(model.currentState)
            vm.endExercise()
            checked += TrTextCheck.checkAll(model.currentState)
            vm.undoEnd()
        }
        kit.until("all written") { repo.setsFor(id).count { it.isCompleted } >= 3 }
        assertTrue(checked > 60, "only $checked texts")
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `a free training starts empty and takes exercises from the catalogue`() {
        val id = kit.db { service.startWorkout(null, null) }
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("empty") { vm.state.value.phase == WorkoutPlayerPhase.EMPTY }
        val model = WorkoutPlayerScreenModel(vm, "de")
        assertEquals("empty", model.currentState.phaseCode)
        val hits = kit.db { model.searchExercises("Klimmzug", 20) }
        assertTrue(hits.isNotEmpty())
        TrTextCheck.checkAll(hits)
        model.addExercise(hits.first().slug)
        kit.until("planned") { vm.state.value.phase == WorkoutPlayerPhase.SET && repo.setsFor(id).any { it.exerciseSlug == hits.first().slug } }
        // Typed lines (Android's WorkoutTextSheet): catalogue exercises with their numbers, unknown names as new ones.
        val typed = "Liegestütze 3x15\nZauberschaukel 2x10"
        val preview = kit.db { model.previewTyped(typed) }
        assertEquals(2, preview.lines.size)
        assertFalse(preview.lines[0].isNew)
        assertTrue(preview.lines[1].isNew)
        TrTextCheck.checkAll(preview)
        model.addTyped(typed)
        kit.until("typed added") { repo.setsFor(id).count { it.exerciseSlug == "push.push_up" && it.targetReps == 15 } == 3 &&
            repo.setsFor(id).any { it.exerciseSlug.startsWith("user.") } }
    }

    @Test
    fun `the hang timer runs prepare work and rest like Android's`() {
        // 7:3 repeaters, 6 reps: prepare 5 s, then work/rest alternating, no rest after the last rep.
        val t = WorkoutHangTimer(7, 3, 6, 1, 0)
        assertEquals((5 + 6 * 7 + 5 * 3) * 1000L, t.totalMs)
        assertEquals("prepare", t.at(0).phaseCode)
        assertEquals(5, t.at(0).remainingSeconds)
        assertEquals("work", t.at(5_000).phaseCode)
        assertEquals(7, t.at(5_000).remainingSeconds)
        assertEquals(1, t.at(11_500).remainingSeconds)
        assertEquals("rest", t.at(12_000).phaseCode)
        assertEquals(1, t.at(15_000).rep)
        assertTrue(t.at(t.totalMs).done)
        assertEquals(5_000L, t.nextStartMs(0))
        // A single hang: prepare and one work segment.
        val hang = WorkoutHangTimer(10, 0, 1, 1, 0)
        assertEquals(15_000L, hang.totalMs)
        assertEquals(t.totalMs, t.nextStartMs(t.at(t.totalMs - 1).segmentIndex))
    }
}
