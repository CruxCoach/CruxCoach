package com.cruxcoach.app.training

import com.cruxcoach.app.ui.TodayScreenModel
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.logic.Circuits
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.SuggestionFocus
import com.cruxcoach.athlete.logic.SuggestionReason
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.SuggestionEvent
import com.cruxcoach.athlete.model.SuggestionEventKind
import com.cruxcoach.athlete.model.SuggestionFeedback
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Today on iOS runs Android's TodayViewModel logic (ported presenter): the cases are Android's
 * OverridesTest / LearningOffersTest at the view-model level, against a real database and the
 * real exercise catalogue.
 */
class TodayPresenterTest {
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

    private fun today() = TodayPresenter(service, kit.scope(), kit.dispatcher)

    /** Today after the two questions were skipped: the suggestion is on the card. */
    private fun answered(): TodayPresenter {
        val vm = today()
        kit.until("first state") { !vm.state.value.loading }
        vm.saveQuickCheckin(null, emptySet())
        kit.until("suggestion") { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        return vm
    }

    private fun TodayPresenter.items() = state.value.suggestion!!.routine.items
    private val day get() = kit.db { service.today().toString() }

    @Test
    fun `today asks how strong and what hurts before the suggestion`() {
        val vm = today()
        kit.until("loaded") { !vm.state.value.loading }
        assertTrue(vm.state.value.asksBeforeSuggestion)
        vm.saveQuickCheckin(4, setOf(InjuryRegion.SHOULDER))
        kit.until("check-in saved") { repo.checkin(service.today().toString())?.sore == setOf(InjuryRegion.SHOULDER) }
        assertEquals(4, kit.db { repo.checkin(service.today().toString())?.energy })
        // Spared today, not an injury.
        assertTrue(kit.db { repo.activeInjuries() }.isEmpty())
        kit.until("suggestion") { vm.state.value.suggestion != null && !vm.state.value.asksBeforeSuggestion }
        val slugs = vm.items().map { it.slug }
        assertTrue(slugs.none { service.catalog[it]?.domains?.contains(LoadDomain.SHOULDER) == true }, "$slugs")
    }

    @Test
    fun `another suggestion picks the kind of training and the place`() {
        val vm = answered()
        vm.chooseFocus(SuggestionFocus.LEGS_CORE)
        kit.until("legs and core") { vm.state.value.suggestion?.focus == SuggestionFocus.LEGS_CORE }
        assertEquals(SuggestionReason.CHOSEN_TODAY, vm.state.value.suggestion!!.reasons.first())
        // A finger day at a place without a hangboard turns into pull and push, with the reason.
        vm.chooseFocus(SuggestionFocus.FINGER_STRENGTH)
        kit.until("finger day") { vm.state.value.suggestion?.focus == SuggestionFocus.FINGER_STRENGTH }
        vm.pickPlace(TrainingPlaces.NO_GEAR_ID)
        kit.until("no equipment today") {
            val s = vm.state.value.suggestion
            s != null && s.focus == SuggestionFocus.PULL_PUSH && SuggestionReason.NO_FINGER_EQUIPMENT in s.reasons
        }
        assertNull(vm.items().firstOrNull { service.catalog[it.slug]?.equipment?.contains(EquipmentV2.HANGBOARD) == true })
    }

    @Test
    fun `the time on the card changes today's training, only for today`() {
        val vm = answered()
        val usual = vm.state.value.suggestion!!
        val preferred = kit.db { repo.profile().sessionMinutes }
        vm.chooseMinutes(90)
        kit.until("90 min") { vm.state.value.minutesToday == 90 && (vm.state.value.suggestion?.totalMinutes ?: 0) > usual.totalMinutes + 15 }
        assertTrue(SuggestionReason.MORE_TIME_TODAY in vm.state.value.suggestion!!.reasons, "${vm.state.value.suggestion!!.reasons}")
        vm.chooseMinutes(20)
        kit.until("20 min") { vm.state.value.minutesToday == 20 && vm.state.value.suggestion!!.totalMinutes <= 24 }
        vm.chooseMinutes(null)
        kit.until("as usual") { vm.state.value.minutesToday == null && vm.state.value.suggestion!!.totalMinutes == usual.totalMinutes }
        assertEquals(preferred, kit.db { repo.profile().sessionMinutes })
    }

    @Test
    fun `an exercise on today's card is swapped, left out or never suggested and the coach learns it`() {
        val vm = answered()
        val first = vm.items().first { !it.warmup }
        val alternatives = vm.alternatives(first.slug)
        assertTrue(alternatives.isNotEmpty())
        // Never more finger work through a swap than the rules allowed.
        if (LoadDomain.FINGER !in service.catalog.fallbackFor(first.slug).domains) {
            assertTrue(alternatives.none { LoadDomain.FINGER in it.def.domains })
        }
        val to = alternatives.first().def.slug
        val before = vm.items().map { it.slug }
        vm.swapExercise(first.slug, to)
        kit.until("swapped") { vm.items().any { it.slug == to } && vm.items().none { it.slug == first.slug } }
        assertEquals(first.sets, vm.items().first { it.slug == to }.sets)
        // Only the swapped exercise changes today, in its place.
        assertEquals(before.map { if (it == first.slug) to else it }, vm.items().map { it.slug })
        assertTrue(kit.db { repo.suggestionEventsForDay(day) }.any { it.kind == SuggestionEventKind.SWAPPED && it.slugs == listOf(first.slug, to) })
        // Swapped back to the original: the card shows it again.
        vm.swapExercise(to, first.slug)
        kit.until("swapped back") { vm.items().any { it.slug == first.slug } && vm.items().none { it.slug == to } }
        // Left out today: gone without a replacement, the rest stays as it was.
        val count = vm.items().size
        val others = vm.items().map { it.slug }.toSet() - first.slug
        vm.dropExercise(first.slug)
        kit.until("left out") { vm.items().none { it.slug == first.slug } && vm.items().size == count - 1 }
        assertEquals(others, vm.items().map { it.slug }.toSet())
        // Never suggested: in the profile, out of the card.
        val other = vm.items().first { !it.warmup }.slug
        vm.excludeExercise(other)
        kit.until("excluded") { other in repo.profile().excludedExercises && vm.items().none { it.slug == other } }
    }

    @Test
    fun `a swap or leaving out on the card can be undone and teaches nothing then`() {
        val vm = answered()
        val first = vm.items().first { !it.warmup }
        val to = vm.alternatives(first.slug).first().def.slug
        vm.swapExercise(first.slug, to)
        kit.until("swapped") { vm.items().any { it.slug == to } }
        vm.undoCardChange()
        kit.until("undone") { vm.items().any { it.slug == first.slug } && vm.items().none { it.slug == to } }
        kit.until("ledger clean") { repo.suggestionEventsForDay(service.today().toString()).none { it.kind == SuggestionEventKind.SWAPPED } }
        val count = vm.items().size
        vm.dropExercise(first.slug)
        kit.until("left out") { vm.items().size == count - 1 }
        vm.undoCardChange()
        kit.until("back") { vm.items().size == count && vm.items().any { it.slug == first.slug } }
        kit.until("no dislike") {
            repo.suggestionEventsForDay(service.today().toString()).none { it.feedback == SuggestionFeedback.DISLIKE_EXERCISE }
        }
    }

    @Test
    fun `turned down a third time, the card asks once whether to stop suggesting it`() {
        val vm = answered()
        val slug = vm.items().first { !it.warmup }.slug
        val dayMs = 24L * 3600 * 1000
        kit.db {
            listOf(20, 9).forEach { ago ->
                repo.logSuggestionEvent(SuggestionEvent("old$ago", service.today().minus(DatePeriod(days = ago)).toString(),
                    System.currentTimeMillis() - ago * dayMs, SuggestionEventKind.NEXT, slugs = listOf(slug),
                    feedback = SuggestionFeedback.DISLIKE_EXERCISE))
            }
        }
        vm.dropExercise(slug)
        kit.until("never offer") { vm.state.value.neverOffer == slug }
        vm.answerNeverOffer(true)
        kit.until("excluded") { slug in repo.profile().excludedExercises && vm.state.value.neverOffer == null }
        assertEquals("never", kit.db { repo.setting("never.offered.$slug") })
        // Asked once: another rejection does not ask again.
        vm.includeExercise(slug)
        kit.until("included") { slug !in repo.profile().excludedExercises }
        vm.dropExercise(slug)
        kit.until("dropped again") { repo.suggestionEventsForDay(service.today().toString()).count { it.slugs == listOf(slug) && it.kind == SuggestionEventKind.NEXT } >= 2 }
        kit.db { }
        assertNull(vm.state.value.neverOffer)
    }

    @Test
    fun `today's choices survive a restart of the app`() {
        val vm = answered()
        vm.chooseMinutes(90)
        vm.chooseCircuit(true)
        vm.setCircuit(4, 20, 120)
        kit.until("90 min") { vm.state.value.minutesToday == 90 }
        val left = vm.items().last { !it.warmup && it.circuit == null }.slug
        vm.dropExercise(left)
        kit.until("ledger") { repo.suggestionEventsForDay(service.today().toString()).let { ev ->
            ev.any { it.focus.orEmpty().startsWith(PreferenceLearning.CHOSEN_FORMAT) } &&
                ev.any { it.focus.orEmpty().startsWith(PreferenceLearning.CIRCUIT_SETUP) } &&
                ev.any { it.value == PreferenceLearning.LEFT_OUT } } }
        val again = today()
        kit.until("restarted") { again.state.value.suggestion != null }
        assertEquals(90, again.state.value.minutesToday)
        assertEquals(vm.state.value.circuitToday, again.state.value.circuitToday)
        assertTrue(again.items().none { it.slug == left }, left)
    }

    @Test
    fun `a circuit fills the chosen time with more rounds`() {
        val vm = answered()
        vm.chooseFocus(SuggestionFocus.PULL_PUSH)
        vm.chooseMinutes(60)
        kit.until("pull and push for 60") {
            vm.state.value.minutesToday == 60 && vm.state.value.suggestion?.focus == SuggestionFocus.PULL_PUSH && vm.state.value.circuitPossible
        }
        val straight = vm.state.value.suggestion!!.estimatedMinutes
        vm.chooseCircuit(true)
        kit.until("circuit") { vm.state.value.circuitToday }
        val s = vm.state.value.suggestion!!
        val key = s.routine.items.first { it.circuit != null }.circuit!!
        val rounds = Circuits.rounds(s.routine.items, key)
        if (straight >= 60 - TodayPresenter.CIRCUIT_TOLERANCE_MIN) {
            assertTrue(s.estimatedMinutes >= 60 - TodayPresenter.CIRCUIT_TOLERANCE_MIN || rounds == TodayPresenter.MAX_CIRCUIT_ROUNDS,
                "straight $straight min, circuit ${s.estimatedMinutes} min in $rounds rounds")
        }
    }

    @Test
    fun `starting today's suggestion opens the training and notes how much was planned`() {
        val vm = answered()
        val planned = vm.items()
        vm.startSuggestion("Pull & push")
        kit.until("started") { vm.state.value.startedWorkout && repo.openWorkout() != null }
        val open = kit.db { repo.openWorkout()!! }
        assertEquals("Pull & push", open.title)
        assertTrue(open.routineId!!.startsWith("suggestion:"))
        val slugs = kit.db { repo.setsFor(open.id) }.map { it.exerciseSlug }.toSet()
        assertTrue(planned.filter { !it.warmup }.all { it.slug in slugs }, "$planned vs $slugs")
        val started = kit.db { repo.suggestionEventsForDay(day) }.single { it.kind == SuggestionEventKind.STARTED }
        assertEquals(open.id, started.workoutId)
        assertTrue((started.value ?: 0.0) > 0)
        // With a training open, the card makes way for "continue".
        kit.until("open workout on today") { vm.state.value.openWorkout != null && vm.state.value.suggestion == null }
        vm.consumeStartedWorkout()
        assertTrue(!vm.state.value.startedWorkout)
    }

    @Test
    fun `starting while another training is open asks first and can end it`() {
        val vm = answered()
        val other = kit.db { service.startWorkout(null, "Other") }
        kit.until("open") { vm.state.value.openWorkout != null }
        // The card is gone with a training open; a built-in start still asks.
        vm.startRoutine(com.cruxcoach.athlete.logic.BuiltinRoutines.BASELINE_TESTS)
        kit.until("asks") { vm.pendingStart.value != null }
        assertEquals(other, vm.pendingStart.value!!.conflict.open.id)
        vm.resolveStart(true)
        kit.until("replaced") { repo.openWorkout()?.let { it.id != other } == true }
        // The empty one was dropped, not kept.
        assertNull(kit.db { repo.workout(other) })
    }

    @Test
    fun `every text of today names an existing string in the right format`() {
        val vm = answered()
        val model = TodayScreenModel(vm, "en")
        vm.chooseCircuit(true)
        kit.until("circuit") { vm.state.value.circuitToday || !vm.state.value.circuitPossible }
        val checked = TrTextCheck.checkAll(model.currentState)
        assertTrue(checked > 20, "only $checked texts")
        val first = vm.items().first { !it.warmup }.slug
        model.alternatives(first).forEach { TrTextCheck.checkAll(it) }
        val state = model.currentState
        assertTrue(state.hasSuggestion && state.items.isNotEmpty() && state.canStart)
        assertEquals(4, state.tiles.size)
        assertEquals(0, model.mapFailures)
    }
}
