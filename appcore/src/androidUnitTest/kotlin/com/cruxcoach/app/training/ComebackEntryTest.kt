package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.ComebackService
import com.cruxcoach.app.progress.InjuriesPresenter
import com.cruxcoach.app.ui.InjuriesScreenModel
import com.cruxcoach.app.ui.TodayScreenModel
import com.cruxcoach.app.ui.WorkoutPlayerScreenModel
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ways into the comeback plan on iOS (Android c5a2665fb, owner 2026-10-20): the Injuries card and its backup
 * switch, the card on Today, and the pain question when a comeback training ends. Ported presenters on a real
 * database with the real catalogue; the cases follow Android's ComebackScreenTest and the Today/Player diffs.
 */
class ComebackEntryTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service
    private val day get() = kit.db { service.today().toString() }

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun injury(id: String = "i1", region: InjuryRegion = InjuryRegion.FINGER, startedDaysAgo: Int = 6) = kit.db {
        Injury(id, region, InjurySide.LEFT, "Ring A2", 3,
            startedOn = service.today().minus(DatePeriod(days = startedDaysAgo)).toString(), updatedAt = 1)
    }

    /** A CruxCoach plan on [stage], agreed with a doctor (Android's test set-up). */
    private fun running(stage: Int = 1, base: Injury = injury()): Injury = kit.db {
        Comeback.start(base, PlanSource.CRUXCOACH, stage, service.today(), true, false, emptyMap(), 1).also { repo.saveInjury(it) }
    }

    private fun today(): Pair<TodayPresenter, TodayScreenModel> {
        val vm = TodayPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !vm.state.value.loading }
        return vm to TodayScreenModel(vm, "de")
    }

    // ── Today ────────────────────────────────────────────────────────

    @Test
    fun `a running plan shows on today with its stage and the morning value saves`() {
        val plan = running(stage = 1)
        val (vm, model) = today()
        kit.until("card") { vm.comebacks.value.size == 1 }
        val card = model.currentState.comebacks.single()
        assertEquals("i1", card.injuryId)
        assertEquals("cb_today_title", card.title.key)
        assertEquals("cb_stage_header", card.stageLine.key)
        assertEquals(listOf(1.0, 5.0), card.stageLine.args.take(2).map { it.number })
        assertEquals(Comeback.stage(plan).title.de, card.stageLine.args[2].text)
        assertFalse(card.hasPrompt)
        assertEquals(-1, card.morningPain)
        // Stage 1: the tendon session and the strength session are both due on the first day.
        assertEquals(listOf(0, 1), card.sessions.map { it.index })
        assertEquals(listOf("tendon", "strength"), card.sessions.map { it.kindCode })
        assertEquals(listOf("cb_session_tendon", "cb_session_strength"), card.sessions.map { it.title.key })
        assertTrue(TrTextCheck.checkAll(model.currentState) > 20)

        model.setComebackMorning("i1", 3)
        kit.until("saved") { repo.comebackEntry("i1", service.today().toString())?.morningPain == 3 }
        kit.until("on the card") { model.currentState.comebacks.single().morningPain == 3 }
        // Tapping the chosen value again clears it.
        model.setComebackMorning("i1", -1)
        kit.until("cleared") { repo.comebackEntry("i1", service.today().toString())?.let { it.morningPain == null } == true }
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `a new warning sign in today's diary shows the note hint on the card`() {
        running(stage = 1)
        val (vm, model) = today()
        kit.until("card") { vm.comebacks.value.size == 1 }
        assertFalse(model.currentState.comebacks.single().hasPrompt)
        kit.db { repo.saveComebackEntry(ComebackEntry("i1", service.today().toString(), newSign = true, updatedAt = 1)) }
        kit.until("prompt") { vm.comebacks.value.single().prompts > 0 }
        assertTrue(model.currentState.comebacks.single().hasPrompt)
    }

    @Test
    fun `a due session starts a comeback training with the plan's routine id for the injured hand`() {
        running(stage = 1)
        val (vm, model) = today()
        kit.until("sessions") { vm.comebacks.value.singleOrNull()?.due?.isNotEmpty() == true }
        val tendon = model.currentState.comebacks.single().sessions.first()
        // A session that is not on the card starts nothing.
        model.startComebackSession("i1", 7, "Comeback · nothing")
        model.startComebackSession("other", tendon.index, "Comeback · nothing")
        model.startComebackSession("i1", tendon.index, "Comeback · Sehnen-Einheit")
        kit.until("started") { vm.state.value.startedWorkout && repo.openWorkout() != null }
        val open = kit.db { repo.openWorkout()!! }
        assertEquals(Comeback.routineId("i1", 1, tendon.index), open.routineId)
        assertEquals("Comeback · Sehnen-Einheit", open.title)
        assertTrue(vm.state.value.startedGuided)
        val holds = kit.db { repo.setsFor(open.id) }.filter { it.exerciseSlug == "finger.open_hand_block_lift" }
        // The injured hand on purpose, five holds at the careful start load, no warm-up ramp.
        assertEquals(5, holds.size)
        assertTrue(holds.all { it.side == Side.LEFT && it.setType == SetType.WORK })
        assertTrue(kit.db { repo.setsFor(open.id) }.none { it.setType == SetType.WARMUP && it.exerciseSlug.startsWith("finger.") })
        // Only one training: the two refused starts above made none.
        assertNull(vm.pendingStart.value)
    }

    @Test
    fun `starting a session while another training is open asks first like every start`() {
        running(stage = 1)
        val (vm, model) = today()
        kit.until("sessions") { vm.comebacks.value.singleOrNull()?.due?.isNotEmpty() == true }
        val other = kit.db { service.startWorkout(null, "Other") }
        kit.until("open") { vm.state.value.openWorkout != null }
        model.startComebackSession("i1", 0, "Comeback · Tendon session")
        kit.until("asks") { vm.pendingStart.value != null }
        assertEquals(other, vm.pendingStart.value!!.conflict.open.id)
        assertEquals("trw_open_replace", model.currentState.pendingReplace.key)
        model.resolveStart(1)
        kit.until("replaced") { repo.openWorkout()?.routineId == Comeback.routineId("i1", 1, 0) }
        // The empty one was dropped.
        assertNull(kit.db { repo.workout(other) })
    }

    @Test
    fun `a logged session leaves the card and an ended plan disappears from today`() {
        running(stage = 1)
        val (vm, model) = today()
        kit.until("two sessions") { vm.comebacks.value.singleOrNull()?.due?.size == 2 }
        val id = kit.db { ComebackService(service).startSession("i1", 0, "Comeback · Tendon session")!! }
        completeHolds(id)
        kit.db {
            service.finishWorkout(id, null, null)
            ComebackService(service).logSession(id, 2)
        }
        // The tendon session waits its six hours; the strength session is still due.
        kit.until("tendon done") { vm.comebacks.value.single().due.map { it.index } == listOf(1) }
        assertEquals(listOf("strength"), model.currentState.comebacks.single().sessions.map { it.kindCode })

        kit.db { ComebackService(service).end("i1", resolved = false) }
        kit.until("gone") { vm.comebacks.value.isEmpty() }
        assertTrue(model.currentState.comebacks.isEmpty())
    }

    @Test
    fun `the athlete's own plan shows as such without sessions of the stage ladder`() {
        val base = injury()
        kit.db {
            repo.saveInjury(Comeback.start(base, PlanSource.OWN, 0, service.today(), true, false, emptyMap(), 1, ownRoutineId = "r1"))
        }
        val (vm, model) = today()
        kit.until("card") { vm.comebacks.value.size == 1 }
        val card = model.currentState.comebacks.single()
        assertEquals("cb_card_own", card.stageLine.key)
        assertTrue(card.sessions.isEmpty())
    }

    // ── Player finish ────────────────────────────────────────────────

    private fun completeHolds(workoutId: String) = kit.db {
        repo.setsFor(workoutId).filter { it.exerciseSlug == "finger.open_hand_block_lift" }
            .forEach { repo.saveSet(it.copy(durationS = 30.0, loadKg = 6.0, completedAt = System.currentTimeMillis())) }
    }

    private fun finish(vm: WorkoutPlayerPresenter, model: WorkoutPlayerScreenModel, pain: Int): WorkoutPlayerEvent = runBlocking {
        val waiter = async(kit.dispatcher, CoroutineStart.UNDISPATCHED) { withTimeout(30_000) { vm.events.first() } }
        model.finish(0, "", pain)
        waiter.await()
    }

    @Test
    fun `finishing a comeback training puts its highest pain into the plan's diary`() {
        running(stage = 1)
        val id = kit.db { ComebackService(service).startSession("i1", 0, "Comeback · Tendon session")!! }
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { vm.state.value.workout?.id == id }
        val model = WorkoutPlayerScreenModel(vm, "de")
        assertTrue(model.currentState.comeback)
        completeHolds(id)
        assertEquals(WorkoutPlayerEvent.Finished(id), finish(vm, model, 1))
        kit.until("logged") { repo.comebackEntry("i1", service.today().toString())?.sessions?.isNotEmpty() == true }
        val log = kit.db { repo.comebackEntry("i1", service.today().toString())!! }.sessions.single()
        assertEquals(ComebackSessionKind.TENDON, log.kind)
        assertEquals(1, log.painMax)
        assertEquals(id, log.workoutId)
        // The next session starts from the load the athlete used.
        assertEquals(6.0, kit.db { ComebackService(service).lastLoad("i1", "finger.open_hand_block_lift", com.cruxcoach.athlete.model.Grip.OPEN_HAND, Side.LEFT) })
    }

    @Test
    fun `an unanswered pain question still logs the session of the own plan`() {
        val base = injury()
        kit.db {
            repo.saveRoutine(Routine("r1", "Physio", items = listOf(RoutineItem("push.push_up", sets = 1, repsMin = 5, repsMax = 5))))
            repo.saveInjury(Comeback.start(base, PlanSource.OWN, 0, service.today(), true, false, emptyMap(), 1, ownRoutineId = "r1"))
        }
        val id = kit.db { ComebackService(service).startOwn("i1", "r1", "Comeback · Meine Einheit")!! }
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("set") { vm.state.value.phase == WorkoutPlayerPhase.SET }
        val model = WorkoutPlayerScreenModel(vm, "en")
        assertTrue(model.currentState.comeback)
        vm.completeCurrent()
        kit.until("done") { repo.setsFor(id).any { it.isCompleted } }
        assertEquals(WorkoutPlayerEvent.Finished(id), finish(vm, model, -1))
        kit.until("logged") { repo.comebackEntry("i1", service.today().toString())?.sessions?.isNotEmpty() == true }
        val log = kit.db { repo.comebackEntry("i1", service.today().toString())!! }.sessions.single()
        assertEquals(ComebackSessionKind.OWN, log.kind)
        assertNull(log.painMax)
    }

    @Test
    fun `a normal training asks nothing and logs nothing into a running plan`() {
        running(stage = 1)
        val id = kit.db { service.startWorkout(Routine("r", "Push", items = listOf(RoutineItem("push.push_up", sets = 2, repsMin = 10, repsMax = 10))), null) }
        val vm = WorkoutPlayerPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("set") { vm.state.value.phase == WorkoutPlayerPhase.SET }
        val model = WorkoutPlayerScreenModel(vm, "en")
        assertFalse(model.currentState.comeback)
        vm.completeCurrent()
        kit.until("done") { repo.setsFor(id).any { it.isCompleted } }
        assertEquals(WorkoutPlayerEvent.Finished(id), finish(vm, model, 4))
        assertTrue(kit.db { repo.workout(id)!!.endedAt != null })
        assertTrue(kit.db { repo.allComebackEntries() }.isEmpty())
    }

    // ── Injuries ─────────────────────────────────────────────────────

    @Test
    fun `the injury card offers the plan with its stage line and the switch puts health data into the backup`() {
        val finger = running(stage = 2)
        kit.db {
            repo.saveInjury(injury("skin", InjuryRegion.SKIN).copy(updatedAt = 2))
            repo.saveInjury(injury("shoulder", InjuryRegion.SHOULDER).copy(updatedAt = 3))
        }
        val p = InjuriesPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("three") { !p.state.value.loading && p.state.value.active.size == 3 }
        val model = InjuriesScreenModel(p, "de")
        val s = model.currentState
        val card = s.active.single { it.id == "i1" }
        assertTrue(card.comebackAvailable && card.comebackRunning)
        assertEquals("cb_card_stage", card.comebackLine.key)
        assertEquals(listOf(2.0, 5.0), card.comebackLine.args.take(2).map { it.number })
        assertEquals(Comeback.stage(finger).title.de, card.comebackLine.args[2].text)
        // Skin has no plan; the shoulder offers one that has not started.
        val skin = s.active.single { it.id == "skin" }
        assertFalse(skin.comebackAvailable)
        assertTrue(skin.comebackLine.isEmpty)
        val shoulder = s.active.single { it.id == "shoulder" }
        assertTrue(shoulder.comebackAvailable && !shoulder.comebackRunning && shoulder.comebackLine.isEmpty)
        assertTrue(TrTextCheck.checkAll(s) > 0)

        // Off by default: health data stays on this phone.
        assertFalse(s.healthInBackup)
        assertFalse(kit.db { repo.profile().healthInCloudBackup })
        model.setHealthInBackup(true)
        kit.until("on") { repo.profile().healthInCloudBackup }
        kit.until("shown") { p.state.value.healthInBackup }
        assertTrue(model.currentState.healthInBackup)
        model.setHealthInBackup(false)
        kit.until("off") { !repo.profile().healthInCloudBackup }
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `an own plan says so and an ended plan or a healed injury no longer shows a running plan`() {
        val base = injury()
        kit.db {
            repo.saveInjury(Comeback.start(base, PlanSource.OWN, 0, service.today(), true, false, emptyMap(), 1, ownRoutineId = "r1"))
        }
        val p = InjuriesPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { p.state.value.active.singleOrNull()?.activePlan != null }
        val model = InjuriesScreenModel(p, "en")
        assertEquals("cb_card_own", model.currentState.active.single().comebackLine.key)

        kit.db { ComebackService(service).end("i1", resolved = false) }
        kit.until("ended") { p.state.value.active.singleOrNull()?.activePlan == null }
        val ended = model.currentState.active.single()
        assertTrue(ended.comebackAvailable && !ended.comebackRunning && ended.comebackLine.isEmpty)

        model.resolve("i1")
        kit.until("past") { p.state.value.past.size == 1 }
        val past = model.currentState.past.single()
        assertFalse(past.comebackAvailable)
        assertFalse(past.comebackRunning)
    }
}
