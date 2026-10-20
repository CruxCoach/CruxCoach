package com.cruxcoach.app.library

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.logbook.SerialDispatcher
import com.cruxcoach.app.ui.ExerciseDetailScreenModel
import com.cruxcoach.app.ui.ExerciseLibraryScreenModel
import com.cruxcoach.app.ui.RoutineEditorScreenModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.data.AthleteRepository
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.VolumeArea
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The training library's presenters against the ported service, a real
 * athlete database and the bundled catalogue. Ports the Android cases for
 * `WorkoutsViewModel`, `RoutineEditorViewModel`, `ExerciseCatalogViewModel`,
 * `ExerciseDetailViewModel`, `WeekPlanViewModel` and `CustomExerciseViewModel`
 * (StartLoadTest, OverridesTest, WeeklyHintTest, TrainingScreensSmokeTest,
 * TrainingUiReviewTest) with their assertions.
 */
class LibraryPresentersTest {

    private lateinit var kit: AthleteTestKit
    private lateinit var repo: AthleteRepository
    private lateinit var service: AthleteService

    /** One thread for the presenters' scope, their IO and the test's own database calls. */
    private val serial = SerialDispatcher().dispatcher
    private val closers = mutableListOf<() -> Unit>()

    @BeforeTest
    fun setUp() {
        kit = AthleteTestKit()
        repo = kit.repo
        service = kit.service
        db { service.ensureReady() }
    }

    @AfterTest
    fun tearDown() {
        closers.forEach { runCatching { it() } }
        db { }
        kit.close()
    }

    private fun <T> db(block: suspend () -> T): T = runBlocking { withContext(serial) { block() } }

    private fun <S> StateFlow<S>.await(predicate: (S) -> Boolean): S = runBlocking {
        try {
            withTimeout(CI_WAIT_MS) { first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("state never matched; last: $value", e)
        }
    }

    private fun scope() = CoroutineScope(serial)

    private fun workouts() = WorkoutsPresenter(service, serial, scope()).also { closers += it::close }
    private fun editor() = RoutineEditorPresenter(service, serial, scope()).also { closers += it::close }
    private fun library() = ExerciseLibraryPresenter(service, serial, scope()).also { closers += it::close }
    private fun detail() = ExerciseDetailPresenter(service, serial, scope()).also { closers += it::close }

    /** The injury scenario of Android's smoke test: left finger injured, climbing paused. */
    private fun injuredClimber() = db {
        val today = service.today().toString()
        repo.updateProfile { it.copy(equipmentConfigured = true,
            equipment = setOf(EquipmentV2.PICKUP_BLOCK, EquipmentV2.PLATES, EquipmentV2.PULL_UP_BAR, EquipmentV2.DUMBBELL)) }
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 68.0, "kg", 1))
        repo.saveInjury(Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, "ring finger A2", 5, true, today))
    }

    // ── Workouts (StartLoadTest) ─────────────────────────────────────

    @Test
    fun `starting another training asks first then continues or replaces the open one`() {
        val finger = db { service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.FINGER_BASICS), "Fingerkraft") }
        val mobility = BuiltinRoutines.byKey(BuiltinRoutines.MOBILITY_10)!!
        val p = workouts()
        p.state.await { !it.loading }
        p.start(mobility, "Mobility")
        val pending = p.state.await { it.pendingStart != null }.pendingStart!!
        assertEquals(finger, pending.conflict.open.id)
        assertEquals(0, pending.conflict.doneSets)
        // Continue: the open finger training stays, and it is the one the screen opens.
        p.resolveStart(false)
        val continued = p.state.await { it.pendingStart == null && it.startedWorkoutId != null }
        assertEquals(finger, continued.startedWorkoutId)
        assertEquals(finger, db { repo.openWorkout()!!.id })
        p.consumeStarted()
        // Replace: nothing was done, so the finger training is dropped and mobility starts.
        p.start(mobility, "Mobility")
        p.state.await { it.pendingStart != null }
        p.resolveStart(true)
        val started = p.state.await { it.startedWorkoutId != null }
        val open = db { repo.openWorkout()!! }
        assertEquals(open.id, started.startedWorkoutId)
        assertEquals(mobility.id, open.routineId)
        assertEquals(null, db { repo.workout(finger) })
        // Starting the open training's own routine again just continues it.
        assertEquals(null, db { service.openConflict(mobility.id) })
    }

    @Test
    fun `a free training starts without a routine and dismissing the question starts nothing`() {
        val p = workouts()
        p.state.await { !it.loading }
        p.startFree("Free")
        val started = p.state.await { it.startedWorkoutId != null }
        assertTrue(started.startedFree)
        val free = db { repo.openWorkout()!! }
        assertEquals(free.id, started.startedWorkoutId)
        assertNull(free.routineId)
        p.consumeStarted()
        p.start(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER)!!, "Core")
        p.state.await { it.pendingStart != null }
        p.resolveStart(null)
        p.state.await { it.pendingStart == null }
        assertEquals(free.id, db { repo.openWorkout()!!.id })
        assertNull(p.state.value.startedWorkoutId)
    }

    @Test
    fun `an own workout is copied with the suffix and deleted`() {
        db { repo.saveRoutine(Routine("own", "Pull", items = listOf(RoutineItem("pull.pull_up", sets = 4)), createdAt = 1, updatedAt = 1)) }
        val p = workouts()
        val loaded = p.state.await { it.routines.size == 1 }
        p.duplicate(loaded.routines.single(), " (copy)")
        val two = p.state.await { it.routines.size == 2 }
        val copy = two.routines.single { it.id != "own" }
        assertEquals("Pull (copy)", copy.name)
        assertEquals(listOf("pull.pull_up"), copy.items.map { it.slug })
        assertNull(copy.builtinKey)
        p.delete(two.routines.single { it.id == "own" })
        p.state.await { s -> s.routines.map { it.id } == listOf(copy.id) }
    }

    @Test
    fun `the week runs Monday to Sunday with done planned missed and rest days`() {
        val monday = kotlinx.datetime.LocalDate.parse("2026-10-12")
        val wednesday = kotlinx.datetime.LocalDate.parse("2026-10-14")
        val plan = mapOf(1 to "builtin:finger_basics", 2 to com.cruxcoach.athlete.model.PLAN_REST, 3 to com.cruxcoach.athlete.model.PLAN_BOARD,
            5 to "builtin:core_climber")
        val done = com.cruxcoach.athlete.model.Workout("w", 1, 2, "2026-10-12", null, null, updatedAt = 2)
        val week = LibraryWeek.build(wednesday, plan, emptyMap(), listOf(done), emptyList())
        assertEquals(7, week.size)
        assertEquals(monday, week.first().date)
        assertEquals(listOf(LibraryDayStatus.DONE, LibraryDayStatus.REST, LibraryDayStatus.PLANNED, LibraryDayStatus.FREE,
            LibraryDayStatus.PLANNED, LibraryDayStatus.FREE, LibraryDayStatus.FREE), week.map { it.status })
        assertTrue(week[2].isToday)
        // A planned day before today without training is missed.
        val friday = LibraryWeek.build(kotlinx.datetime.LocalDate.parse("2026-10-16"), plan, emptyMap(), emptyList(), emptyList())
        assertEquals(LibraryDayStatus.MISSED, friday[0].status)
        assertEquals(LibraryDayStatus.MISSED, friday[2].status)
        assertEquals("builtin:core_climber", LibraryWeek.plannedRoutine(friday[4].planned, emptyList())?.id)
        assertNull(LibraryWeek.plannedRoutine(friday[2].planned, emptyList()))
    }

    // ── Editor (OverridesTest, WeeklyHintTest, TrainingUiReviewTest) ─

    @Test
    fun `a typed workout adds catalogue exercises new ones and its name`() {
        val p = editor()
        p.load(null, null, null)
        p.state.await { !it.loading }
        val text = "Oberkörper\nKlimmzüge 5x5 +20 kg\nPlank 3x60s\nRussian Twists 3x20 8kg"
        p.setTypedText(text)
        // Read off the main thread (Android's WorkoutTextSheet): the preview follows once the field is read.
        val read = p.state.await { it.typedRead == text }
        assertEquals("Oberkörper", read.typed.title)
        assertEquals(3, read.typed.lines.size)
        p.applyTyped("de", " & ")
        val s = p.state.await { it.items.size == 3 }
        val items = s.items.map { it.item }
        assertEquals("pull.weighted_pull_up", items[0].slug)
        assertEquals(20.0, items[0].loadKg)
        assertEquals("core.front_plank", items[1].slug)
        assertEquals(60, items[1].durationS)
        assertTrue(items[2].slug.startsWith("user."), items[2].slug)
        assertEquals("Oberkörper", s.name)
        assertTrue(db { repo.customExercises() }.any { it.slug == items[2].slug })
        // The editor shows the workout's own load, and "auto" for the others.
        val ui = RoutineEditorScreenModel(p, "de").currentState
        assertTrue(ui.items[0].showLoad)
        assertFalse(ui.items[0].loadIsAuto)
        assertEquals("", p.state.value.typedText)
    }

    @Test
    fun `the editor says where the week stands and what the workout adds`() {
        db {
            repo.saveMeasurement(BodyMeasurement(service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
            repo.updateProfile { it.copy(equipmentConfigured = true, equipment = setOf(EquipmentV2.NONE, EquipmentV2.MAT,
                EquipmentV2.HANGBOARD, EquipmentV2.PULL_UP_BAR, EquipmentV2.DUMBBELL, EquipmentV2.BANDS)) }
        }
        val pull = db { service.weeklyFor("pull.pull_up")!! }
        assertEquals(VolumeArea.PULL, pull.area)
        assertTrue(pull.goal > 0)
        db { repo.saveRoutine(Routine("own", "Pull", items = listOf(RoutineItem("pull.pull_up", sets = 4), RoutineItem("pull.dumbbell_row", sets = 3)),
            createdAt = 1, updatedAt = 1)) }
        val p = editor()
        p.load("own", null, null)
        val s = p.state.await { !it.loading }
        // 4 pull-ups + 3 one-arm rows on each side = 10 sets of pulling.
        val line = RoutineEditorPresenter.weeklyAdds(s).single { it.first.area == VolumeArea.PULL }
        assertEquals(10, line.second)
        val text = RoutineEditorScreenModel(p, "en").currentState.weeklyLines.single()
        assertEquals("trv_hint_plus", text.key)
        assertEquals("trv_hint_sets", text.args[0].nested!!.key)
        assertEquals(10L, text.args[1].intValue)
    }

    @Test
    fun `two links make three exercises one circuit and dissolving undoes it`() {
        val p = editor()
        p.load(null, null, null)
        p.state.await { !it.loading && it.catalog.all.isNotEmpty() }
        p.addSlugs(listOf("pull.pull_up", "push.push_up", "core.dead_bug"))
        p.link(p.state.value.items[0].key)
        p.link(p.state.value.items[1].key)
        assertTrue(p.state.value.items.all { it.item.circuit != null })
        val ui = RoutineEditorScreenModel(p, "en").currentState
        assertTrue(ui.items[0].startsCircuit)
        assertFalse(ui.items[1].startsCircuit)
        assertTrue(ui.items[0].linkedToNext && ui.items[1].linkedToNext && !ui.items[2].linkedToNext)
        // Sets and rest come from the circuit card: the rows have no own sets or rest steppers.
        assertTrue(ui.items.all { row -> (row.steppers + row.steppersAfterLoad).none { it.field == "sets" || it.field == "rest" } })
        p.dissolve(ui.items[0].circuit)
        assertTrue(p.state.value.items.all { it.item.circuit == null })
    }

    @Test
    fun `the editor saves a workout copied from a starter routine`() {
        val p = editor()
        p.load(null, "builtin:" + BuiltinRoutines.CORE_CLIMBER, "Core for climbers")
        val s = p.state.await { !it.loading }
        assertEquals("Core for climbers", s.name)
        assertEquals(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER)!!.items, s.items.map { it.item })
        val model = RoutineEditorScreenModel(p, "en")
        val first = model.currentState.items.first()
        model.stepField(first.key, "sets", up = true)
        assertEquals(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER)!!.items.first().sets + 1, p.state.value.items.first().item.sets)
        // A typed value outside the stepper's range is ignored.
        model.setField(first.key, "sets", 99)
        assertEquals(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER)!!.items.first().sets + 1, p.state.value.items.first().item.sets)
        p.save()
        p.state.await { it.saved }
        val saved = db { repo.routines() }.single()
        assertEquals("Core for climbers", saved.name)
        assertNull(saved.builtinKey)
        assertEquals(s.items.size, saved.items.size)
    }

    @Test
    fun `the picker lists favourites first and adds the ticked exercises`() {
        db { repo.setFavorite("core.dead_bug", true) }
        val p = editor()
        p.load(null, null, null)
        p.state.await { !it.loading && "core.dead_bug" in it.favorites }
        val (favs, results) = RoutineEditorPresenter.pickerRows(p.state.value, "en")
        assertEquals(listOf("core.dead_bug"), favs.map { it.slug })
        assertEquals(p.state.value.catalog.all.size, results.size)
        // Searching hides the favourites block; the category chip filters both.
        p.setPickerQuery("plank")
        assertTrue(RoutineEditorPresenter.pickerRows(p.state.value, "en").first.isEmpty())
        p.setPickerQuery("")
        p.setPickerCategory(ExerciseCategoryV2.PULL)
        assertTrue(RoutineEditorPresenter.pickerRows(p.state.value, "en").let { (f, r) -> f.isEmpty() && r.all { it.category == ExerciseCategoryV2.PULL } })
        p.togglePicked("pull.pull_up")
        p.togglePicked("core.dead_bug")
        p.confirmPicker()
        assertEquals(listOf("pull.pull_up", "core.dead_bug"), p.state.value.items.map { it.item.slug })
        assertTrue(p.state.value.pickerSelected.isEmpty())
    }

    // ── Library (TrainingScreensSmokeTest) ───────────────────────────

    @Test
    fun `one-hand finger injury keeps one-arm pick-up visible and favourites on top`() {
        injuredClimber()
        db { repo.setFavorite("finger.one_arm_pickup", true) }
        val p = library()
        val s = p.state.await { !it.loading && "finger.one_arm_pickup" in it.favorites }
        // Left hand hurt: climbing is paused, but finger work stays listed for the healthy hand.
        assertTrue(s.withoutClimbing)
        assertFalse(s.fingerFree)
        assertTrue(s.injuryDefaultsApplied)
        val ui = ExerciseLibraryScreenModel(p, "en").currentState
        assertTrue(ui.showSections)
        val row = ui.favoriteRows.single { it.slug == "finger.one_arm_pickup" }
        assertTrue(row.favorite)
        assertEquals("one_side_only", row.verdictCode)
        assertEquals("trx_badge_only_right", row.badgeKey)
        assertTrue(ui.rows.none { it.slug == "finger.one_arm_pickup" })
        assertTrue(ui.rows.isNotEmpty())
        // A tap on a safety filter is the athlete's: the injury note goes.
        p.toggleWithoutClimbing()
        assertFalse(p.state.value.withoutClimbing)
        assertFalse(p.state.value.injuryDefaultsApplied)
    }

    @Test
    fun `my equipment starts on once set up and filters what cannot be done`() {
        injuredClimber()
        val p = library()
        val s = p.state.await { !it.loading && it.profile.equipmentConfigured }
        assertTrue(s.mineOnly)
        val owned = s.profile.equipment + EquipmentV2.NONE
        val results = s.catalog.search("", "en", s.filter)
        assertTrue(results.all { def -> def.equipment.all { EquipmentV2.satisfied(it, owned) } })
        assertTrue(results.size < s.catalog.all.size)
        p.toggleMine()
        assertFalse(p.state.value.mineOnly)
    }

    @Test
    fun `the equipment sheet saves what was picked and turns the filter on`() {
        val p = library()
        p.state.await { !it.loading }
        assertFalse(p.state.value.profile.equipmentConfigured)
        p.openEquipmentSheet()
        // The second pick-up block comes with the first.
        p.toggleEquipmentDraft(EquipmentV2.PICKUP_BLOCK_PAIR)
        assertEquals(setOf(EquipmentV2.PICKUP_BLOCK_PAIR, EquipmentV2.PICKUP_BLOCK, EquipmentV2.NONE, EquipmentV2.MAT), p.state.value.equipmentDraft)
        p.closeEquipmentSheet()
        val s = p.state.await { it.profile.equipmentConfigured && it.mineOnly }
        assertTrue(EquipmentV2.PICKUP_BLOCK in s.profile.equipment)
        assertNull(s.equipmentDraft)
    }

    @Test
    fun `picking adds the exercise to the open training and an injury rules one out`() {
        injuredClimber()
        val id = db { service.startWorkout(null, null) }
        val p = library()
        p.state.await { !it.loading }
        p.pick(id, "pull.pull_up")
        p.state.await { it.picked }
        assertTrue(db { repo.setsFor(id) }.any { it.exerciseSlug == "pull.pull_up" })
        p.consumeEvents()
        // Both hands: finger work is out.
        db { repo.saveInjury(Injury("i2", InjuryRegion.FINGER, InjurySide.BOTH, "pulley", 7, true, service.today().toString())) }
        p.pick(id, "finger.max_hang")
        p.state.await { it.pickRejected }
        assertTrue(db { repo.setsFor(id) }.none { it.exerciseSlug == "finger.max_hang" })
    }

    // ── Detail (TrainingScreensSmokeTest, OverridesTest) ─────────────

    @Test
    fun `exercise detail of the one-arm pick-up renders`() {
        injuredClimber()
        val p = detail()
        p.load("finger.one_arm_pickup")
        p.state.await { !it.loading && it.definition != null }
        val ui = ExerciseDetailScreenModel(p, "en").currentState
        assertEquals("One-Arm Edge Pick-Up", ui.name)
        assertTrue(ui.found)
        assertTrue(ui.why.isNotBlank())
        assertTrue(ui.steps.isNotEmpty())
        assertEquals("one_side_only", ui.verdictCode)
        assertEquals("trx_injury_one_side", ui.injuryMessage.key)
        assertTrue(ui.canAdd)
    }

    @Test
    fun `an own exercise can be deleted and leaves workouts and history readable`() {
        val slug = db { service.createCustomExercise("Russian Twists", ExerciseCategoryV2.CORE,
            ExerciseKind.LOAD_REPS, LoadMode.EXTERNAL, false, emptyList(), emptyList()) }
        db {
            repo.saveRoutine(Routine("r", "Mixed", items = listOf(RoutineItem(slug), RoutineItem("push.push_up"))))
            repo.saveRoutine(Routine("o", "Only", items = listOf(RoutineItem(slug))))
        }
        val p = detail()
        p.load(slug)
        val s = p.state.await { !it.loading && it.definition != null }
        assertTrue(s.definition!!.custom)
        p.deleteCustom()
        p.state.await { it.deleted }
        assertNull(service.catalog[slug])
        assertEquals(listOf("push.push_up"), db { repo.routines() }.single { it.id == "r" }.items.map { it.slug })
        assertTrue(db { repo.routines() }.none { it.id == "o" })
        assertEquals("Russian twists", service.catalog.fallbackFor(slug).name("de"))
    }

    @Test
    fun `start with an exercise opens a training with it and favourites toggle`() {
        val p = detail()
        p.load("pull.pull_up")
        p.state.await { !it.loading && it.definition != null }
        p.addToTraining("Pull-up")
        val s = p.state.await { it.navigateToWorkout != null }
        val open = db { repo.openWorkout()!! }
        assertEquals(open.id, s.navigateToWorkout)
        assertEquals("Pull-up", open.title)
        assertTrue(db { repo.setsFor(open.id) }.any { it.exerciseSlug == "pull.pull_up" })
        p.consumeEvents()
        p.toggleFavorite()
        p.state.await { it.favorite }
        assertTrue("pull.pull_up" in db { repo.favorites() })
    }

    @Test
    fun `a rejected exercise does not leave an empty training behind`() {
        db { repo.saveInjury(Injury("i2", InjuryRegion.FINGER, InjurySide.BOTH, "pulley", 7, true, service.today().toString())) }
        val p = AddToWorkoutPresenter(service, serial, scope()).also { closers += it::close }
        p.load("finger.max_hang")
        p.state.await { it.def != null }
        p.startNew("Max hangs")
        p.state.await { it.rejected }
        assertNull(db { repo.openWorkout() })
        // Into an own workout instead: allowed, the plan is the athlete's.
        db { repo.saveRoutine(Routine("own", "Fingers", items = emptyList())) }
        p.state.await { it.routines.size == 1 }
        p.addToRoutine("own")
        p.state.await { it.addedToRoutine == "Fingers" }
        assertEquals(listOf("finger.max_hang"), db { repo.routines() }.single().items.map { it.slug })
    }

    // ── Week plan (TrainingScreensSmokeTest) ─────────────────────────

    @Test
    fun `the week plan proposal can be applied and taken back`() {
        val p = WeekPlanPresenter(service, serial, scope()).also { closers += it::close }
        val s = p.state.await { !it.loading }
        val before = db { repo.profile().weekPlan }
        assertTrue(s.proposal.isNotEmpty())
        assertTrue(s.proposal != before)
        p.applyProposal()
        p.state.await { it.profile.weekPlan == s.proposal }
        assertEquals(s.proposal, db { repo.profile().weekPlan })
        p.undoProposal()
        p.state.await { it.profile.weekPlan == before }
        assertEquals(before, db { repo.profile().weekPlan })
    }

    @Test
    fun `a day of the standard week is set and cleared and the session length kept`() {
        val p = WeekPlanPresenter(service, serial, scope()).also { closers += it::close }
        p.state.await { !it.loading }
        p.set(3, com.cruxcoach.athlete.model.PLAN_BOARD)
        p.state.await { it.profile.weekPlan[3] == com.cruxcoach.athlete.model.PLAN_BOARD }
        p.set(3, null)
        p.state.await { 3 !in it.profile.weekPlan }
        p.setMinutes(75)
        p.state.await { it.profile.sessionMinutes == 75 }
    }

    // ── Own exercise (TrainingUiReviewTest) ──────────────────────────

    @Test
    fun `the name of an own exercise fills in the rest and saving creates it`() {
        val p = CustomExercisePresenter(service, serial, scope()).also { closers += it::close }
        p.save()
        assertTrue(p.state.value.showNameError)
        p.setName("Ringdips mit Zusatzgewicht")
        assertTrue(p.state.value.guessed)
        // A field set by hand stays when the name changes.
        p.setCategory(ExerciseCategoryV2.ANTAGONIST)
        p.setName("Ringdips mit Zusatzgewicht schwer")
        assertEquals(ExerciseCategoryV2.ANTAGONIST, p.state.value.category)
        p.save()
        val slug = p.state.await { it.savedSlug != null }.savedSlug!!
        assertTrue(slug.startsWith("user."), slug)
        assertEquals(ExerciseCategoryV2.ANTAGONIST, service.catalog[slug]!!.category)
    }

    // ── Pictograms ───────────────────────────────────────────────────

    @Test
    fun `every catalogue exercise has a pictogram with strokes`() {
        val catalog = service.catalog
        assertTrue(catalog.all.size >= 200, "${catalog.all.size}")
        catalog.all.forEach { def ->
            val shapes = LibraryPictograms.shapes(LibraryPictograms.of(def))
            assertTrue(shapes.isNotEmpty(), def.slug)
            assertTrue(shapes.all { s -> s.points.all { it in -1.0..25.0 } }, def.slug)
        }
        assertEquals(LibraryPictogram.PICKUP, LibraryPictograms.of(catalog["finger.one_arm_pickup"]!!))
        assertEquals(LibraryPictogram.PULL_UP, LibraryPictograms.of(catalog["pull.pull_up"]!!))
        // A figure: two far limbs, the trunk, two near limbs and the head.
        val pullUp = LibraryPictograms.shapes(LibraryPictogram.PULL_UP)
        assertEquals(1, pullUp.count { it.kind == "dot" && it.role == "figure" && it.filled })
        assertNotNull(pullUp.firstOrNull { it.role == "gear" })
    }
}
