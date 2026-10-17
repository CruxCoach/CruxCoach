package com.cruxcoach.app.training

import com.cruxcoach.app.ui.BasicsEquipment
import com.cruxcoach.app.ui.BasicsScreenModel
import com.cruxcoach.app.ui.WorkoutSummaryScreenModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.Sex
import com.cruxcoach.athlete.model.SuggestionEventKind
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The summary after a training and the quick start: Android's LearningOffersTest and BasicsTest at the view-model level. */
class SummaryAndBasicsTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service

    @BeforeTest
    fun setUp() { kit = TrainingKit() }

    @AfterTest
    fun tearDown() = kit.close()

    private val day get() = kit.db { service.today().toString() }
    private fun keptEvents() = repo.suggestionEventsForDay(service.today().toString()).filter { it.focus == PreferenceLearning.KEEP_CHOICE }

    @Test
    fun `a training with a swapped exercise asks once whether to keep it, with undo`() {
        kit.standardAthlete()
        val today = TodayPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !today.state.value.loading }
        today.saveQuickCheckin(null, emptySet())
        kit.until("suggestion") { today.state.value.suggestion != null && !today.state.value.asksBeforeSuggestion }
        val first = today.state.value.suggestion!!.routine.items.first { !it.warmup }.slug
        val to = today.alternatives(first).first().def.slug
        today.swapExercise(first, to)
        kit.until("swapped") { repo.suggestionEventsForDay(service.today().toString()).any { it.kind == SuggestionEventKind.SWAPPED } &&
            today.state.value.suggestion!!.routine.items.any { it.slug == to } }
        today.startSuggestion("Today")
        kit.until("started") { repo.openWorkout() != null && repo.suggestionEventsForDay(service.today().toString()).any { it.kind == SuggestionEventKind.STARTED } }
        val id = kit.db { repo.openWorkout()!!.id }
        kit.db { service.finishWorkout(id, null, null) }

        val summary = WorkoutSummaryPresenter(service, kit.scope(), kit.dispatcher)
        summary.load(id)
        kit.until("summary") { !summary.state.value.loading }
        assertEquals(listOf(PreferenceLearning.Change(first, to)), summary.state.value.keep)
        val model = WorkoutSummaryScreenModel(summary, "en")
        assertTrue(model.currentState.hasKeep)
        TrTextCheck.checkAll(model.currentState)
        summary.answerKeep(true)
        kit.until("kept") { keptEvents().size == 1 && summary.state.value.keep.isEmpty() }
        assertEquals(listOf(first, to), kit.db { keptEvents() }.single().slugs)
        kit.until("answer") { summary.state.value.keepAnswer != null }
        TrTextCheck.checkAll(model.currentState)
        // "Rückgängig": nothing kept, the question is back.
        model.undoKeep()
        kit.until("undone") { keptEvents().isEmpty() && summary.state.value.keep.isNotEmpty() }
        // "Nur heute": answered, nothing kept – and a later look does not ask again.
        summary.answerKeep(false)
        kit.until("today only") { repo.suggestionEventsForDay(service.today().toString()).any { it.focus == PreferenceLearning.KEEP_TODAY_ONLY } }
        assertTrue(kit.db { keptEvents() }.isEmpty())
        val again = WorkoutSummaryPresenter(service, kit.scope(), kit.dispatcher)
        again.load(id)
        kit.until("again") { !again.state.value.loading }
        assertEquals(emptyList(), again.state.value.keep)
    }

    @Test
    fun `the summary shows the sets, the reward tiles and can be deleted`() {
        kit.standardAthlete()
        val id = kit.db {
            val id = service.startWorkout(Routine("r", "Pull", items = listOf(
                RoutineItem("pull.weighted_pull_up", sets = 2, repsMin = 5, repsMax = 5, loadKg = 10.0),
                RoutineItem("finger.max_hang", sets = 1, durationS = 10, edgeMm = 20))), null)
            repo.setsFor(id).filter { it.setType == com.cruxcoach.athlete.model.SetType.WORK }.forEach { service.completeSet(it, startRest = false) }
            service.finishWorkout(id, 6, null)
            id
        }
        val summary = WorkoutSummaryPresenter(service, kit.scope(), kit.dispatcher)
        val model = WorkoutSummaryScreenModel(summary, "en")
        model.load(id)
        kit.until("loaded") { !summary.state.value.loading }
        val s = model.currentState
        assertTrue(s.found)
        assertEquals(3, s.setsValue)
        assertEquals(2, s.blocks.size)
        assertTrue(s.blocks.first().lines.size == 2)
        // Weighted reps: the volume tile.
        assertEquals("trp2_tile_volume", s.thirdLabel.key)
        // Finger work today: when the fingers take max work again.
        assertFalse(s.afterFingers.isEmpty)
        assertTrue(TrTextCheck.checkAll(s) > 10)
        model.saveRoutine("My pull")
        kit.until("saved") { summary.state.value.routineSaved && repo.routines().any { it.name == "My pull" } }
        model.delete()
        kit.until("deleted") { summary.state.value.deleted && repo.workout(id) == null }
    }

    @Test
    fun `the basics fill weight, height, age, sex, grade, experience and equipment`() {
        kit.db { service.ensureReady() }
        val presenter = BasicsPresenter(service, kit.scope(), kit.dispatcher)
        val model = BasicsScreenModel(presenter)
        model.load()
        kit.until("loaded") { !presenter.state.value.loading }
        val equipment = BasicsEquipment()
        val home = equipment.preset("home")
        assertEquals("home", equipment.presetOf(home))
        val grade = model.stepGrade("", 1)
        assertEquals("6a", grade)
        model.save("68,5", "176", "1990", "FEMALE", grade, "Y3_5", home)
        kit.until("saved") { presenter.state.value.finished && repo.profile().basicsAsked }
        val p = kit.db { repo.profile() }
        assertEquals(68.5, kit.db { repo.latest(BodyMetric.WEIGHT.key)!!.value }, 1e-9)
        assertEquals(176.0, kit.db { repo.latest(BodyMetric.HEIGHT.key)!!.value }, 1e-9)
        assertEquals(1990, p.birthYear)
        assertEquals(Sex.FEMALE, p.sex)
        assertEquals("6a", p.coach.currentGrade)
        assertEquals(ExperienceBand.Y3_5, p.coach.experience)
        // The coach's age guard rails follow the birth year.
        assertEquals(BasicsPresenter.ageBandFor(kit.db { service.today() }.year - 1990), p.coach.ageBand)
        assertTrue(p.equipmentConfigured && EquipmentV2.HANGBOARD in p.equipment)
        // Implausible values are refused, not saved.
        assertEquals(-1.0, model.weightKg("7"))
        assertEquals(-1, model.birthYear("1800"))
        // The second pick-up block comes with the first, dropping the first drops both.
        val pair = equipment.toggle(emptyList(), "PICKUP_BLOCK_PAIR")
        assertTrue("PICKUP_BLOCK" in pair && "PICKUP_BLOCK_PAIR" in pair)
        assertTrue(equipment.toggle(pair, "PICKUP_BLOCK").none { it.startsWith("PICKUP") })
        TrTextCheck.checkAll(equipment.codes.map { equipment.label(it) })
    }

    @Test
    fun `the basics open once, only while something is missing`() {
        kit.db { service.ensureReady() }
        val first = BasicsPresenter(service, kit.scope(), kit.dispatcher)
        first.checkGate()
        kit.until("gate open") { first.state.value.gateOpen }
        assertTrue(kit.db { repo.profile().basicsAsked })
        // Backing out does not bring them back on the next visit.
        val second = BasicsPresenter(service, kit.scope(), kit.dispatcher)
        second.checkGate()
        kit.db { }
        Thread.sleep(300)
        kit.db { }
        assertFalse(second.state.value.gateOpen)
    }

    @Test
    fun `a climbing day is logged, shows on today's tile and is edited and deleted`() {
        kit.standardAthlete()
        val today = TodayPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !today.state.value.loading }
        val presenter = ClimbingDayPresenter(service, kit.scope(), kit.dispatcher)
        val model = com.cruxcoach.app.ui.ClimbingDayScreenModel(presenter)
        model.load("")
        kit.until("form") { !presenter.state.value.loading }
        assertEquals("today", model.currentState.dayCode)
        model.setKind("OUTDOOR")
        model.setMinutes(120)
        model.setIntensity("HARD")
        model.setNote("Fontainebleau")
        model.save()
        kit.until("saved") { presenter.state.value.done }
        // Today's tile now opens that entry instead of logging the day twice (device test D30).
        kit.until("tile") { today.state.value.climbingEntriesToday.size == 1 && (today.state.value.activity?.climbingMinutes ?: 0) >= 120 }
        val id = today.state.value.climbingEntriesToday.single()
        val edit = ClimbingDayPresenter(service, kit.scope(), kit.dispatcher)
        val editModel = com.cruxcoach.app.ui.ClimbingDayScreenModel(edit)
        editModel.load(id)
        kit.until("edit") { !edit.state.value.loading }
        val s = editModel.currentState
        assertTrue(s.isEdit && s.kindCode == "OUTDOOR" && s.minutes == 120 && s.intensityCode == "HARD" && s.note == "Fontainebleau")
        TrTextCheck.checkAll(s)
        editModel.setDay("yesterday")
        editModel.save()
        kit.until("moved") { repo.climbingDay(id)?.day == service.today().minus(kotlinx.datetime.DatePeriod(days = 1)).toString() }
        editModel.loadList()
        kit.until("list") { editModel.currentState.rows.size == 1 }
        TrTextCheck.checkAll(editModel.currentState)
        editModel.delete()
        kit.until("deleted") { repo.climbingDay(id) == null }
    }
}
