package com.cruxcoach.app.training

import com.cruxcoach.app.athlete.ComebackService
import com.cruxcoach.app.ui.ComebackScreenModel
import com.cruxcoach.app.ui.ComebackScreenState
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackContent
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.ComebackSessionLog
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.ProfessionalCheck
import com.cruxcoach.athlete.model.Routine
import com.cruxcoach.athlete.model.RoutineItem
import com.cruxcoach.athlete.model.SetType
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.StageChangeReason
import com.cruxcoach.athlete.model.WarningSign
import com.cruxcoach.util.formatDecimal
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The comeback screen on iOS runs Android's ComebackViewModel (ported) and the choices Android keeps in its
 * composables, against a real database. Ports Android's `ComebackScreenTest` (owner 2026-10-20: "immer mit dem
 * Hinweis, dass dies kein Ersatz für Arztbesuch ist und nur in Absprache mit Arzt genutzt werden sollte").
 */
class ComebackPresenterTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service
    private val today get() = kit.db { service.today() }

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun injury(startedDaysAgo: Int = 6) = Injury("i1", InjuryRegion.FINGER, InjurySide.LEFT, "Ring A2", 3,
        startedOn = today.minus(DatePeriod(days = startedDaysAgo)).toString(), updatedAt = 1)

    private fun screen(): Pair<ComebackPresenter, ComebackScreenModel> {
        val p = ComebackPresenter(service, "i1", kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading && p.setup.value != null }
        return p to ComebackScreenModel(p, "en")
    }

    private fun texts(s: ComebackScreenState, model: ComebackScreenModel) {
        assertTrue(TrTextCheck.checkAll(s) > 0)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `questions warning signs and the start of a plan agreed with a doctor`() {
        kit.db { repo.saveInjury(injury()) }
        val (p, model) = screen()
        val first = model.currentState
        assertTrue(first.found)
        assertFalse(first.hasPlan)
        assertFalse(first.check.shown)
        assertEquals("cruxcoach", first.setup.sourceCode)
        assertEquals("cb_step_questions_open", first.setup.questionsStatus.key)

        model.openCheck()
        val sheet = model.currentState.check
        assertTrue(sheet.shown)
        assertTrue(sheet.showFingers)
        assertTrue(sheet.signs.any { it.code == "pop_with_swelling" })
        // Signs of other regions are not offered (a calf is no finger sign).
        assertTrue(sheet.signs.none { it.code == "calf_swollen_warm" })
        // Nothing ticked never means "all good": the sheet says it rules nothing out.
        assertTrue(sheet.nothingTicked.isNotEmpty())
        assertFalse(sheet.showFinding)
        model.toggleSign("calf_swollen_warm")
        model.toggleSign("pop_with_swelling")
        model.chooseChecked("imaging")
        assertTrue(model.currentState.check.showFinding)
        assertEquals("none", model.currentState.check.findings.first().code)
        model.chooseFinding("pulley_strain")
        val (y, m, d) = today.toString().split("-").map { it.toInt() }
        assertTrue(model.setCheckedOn(y, m, d))
        assertFalse(model.setCheckedOn(y + 1, 1, 1))
        assertFalse(model.setCheckedOn(y, 2, 30))
        assertEquals("", model.currentState.check.nothingTicked)
        // New signs: the alert is due.
        assertTrue(model.saveCheck())
        assertFalse(model.currentState.check.shown)
        kit.until("answers saved") { repo.injury("i1")?.check?.finding == InjuryFinding.PULLEY_STRAIN }
        val check = kit.db { repo.injury("i1")!!.check!! }
        assertEquals(setOf(WarningSign.POP_WITH_SWELLING), check.signs)
        assertEquals(ProfessionalCheck.IMAGING, check.checked)
        assertEquals(today.toString(), check.checkedOn)
        assertEquals(today.toString(), check.answeredOn)

        // A pop with swelling: best checked within days – the alert says so, with the 116117 hint.
        kit.until("alert") { p.state.value.injury?.check?.signs?.isNotEmpty() == true }
        val alert = model.currentState.alert
        assertEquals("days", alert.urgency)
        assertTrue(alert.outOfHours)
        assertTrue(alert.title.isNotEmpty() && alert.text.isNotEmpty())
        assertEquals("cb_banner_signs", model.currentState.signsBanner.key)
        model.acknowledgeSigns()
        kit.until("acknowledged") { repo.injury("i1")?.check?.signsAcknowledgedOn != null }
        kit.until("banner") { p.state.value.injury?.check?.signsAcknowledgedOn != null }
        assertEquals("cb_banner_signs_checked", model.currentState.signsBanner.key)
        // The same signs again: no new alert.
        model.openCheck()
        assertFalse(model.saveCheck())

        model.chooseStage(1)
        model.setReferenceHalf("30")
        model.setAgreed(true)
        val setup = model.currentState.setup
        assertEquals(1, setup.stage)
        assertTrue(setup.showStage && setup.showReference && setup.agreed)
        texts(model.currentState, model)
        // "Bevor du startest" confirmed with "in agreement with my doctor or physio".
        model.startPlan()
        kit.until("plan") { repo.injury("i1")?.activePlan != null }
        val plan = kit.db { repo.injury("i1")!!.activePlan!! }
        assertEquals(1, plan.stage)
        assertTrue(plan.agreedWithProfessional)
        assertEquals(30.0, plan.referenceKg[Grip.HALF_CRIMP.name])
        assertEquals(today.toString(), plan.acknowledgedOn)
        // Stage 1 has no climbing: climbing pauses on the injury, so the coach plans around it.
        assertTrue(kit.db { repo.injury("i1")!!.climbingPaused })
        kit.until("plan shown") { p.state.value.injury?.activePlan != null }
        val running = model.currentState
        assertTrue(running.hasPlan)
        assertEquals("cb_stage_header", running.plan.stageHeader.key)
        assertTrue(running.plan.stageHeader.args[2].text.contains("Gentle loading"))
        assertEquals("cb_pain_rule_acute", running.plan.painRule.key)
        assertFalse(running.plan.notAgreed)
        assertFalse(running.plan.notChecked)
        texts(running, model)
    }

    @Test
    fun `a session trains the injured hand with the plan's load and its pain goes into the diary`() {
        kit.db { repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 1, today, true, false, emptyMap(), 1)) }
        val (p, model) = screen()
        val session = model.currentState.plan.sessions.first()
        assertEquals(0, session.index)
        assertEquals("due", session.statusCode)
        assertEquals("cb_session_name", session.name.key)
        texts(model.currentState, model)
        // Only sessions the plan shows can start.
        model.startSession(9, "Comeback · nothing")
        model.startSession(0, "Comeback · Tendon session")
        kit.until("started") { repo.openWorkout() != null }
        kit.until("event") { p.started.value != null }
        assertTrue(model.currentState.startedWorkout)
        model.consumeStartedWorkout()
        assertFalse(model.currentState.startedWorkout)

        val workout = kit.db { repo.openWorkout()!! }
        assertEquals(Comeback.routineId("i1", 1, 0), workout.routineId)
        assertEquals("Comeback · Tendon session", workout.title)
        val sets = kit.db { repo.setsFor(workout.id) }
        val holds = sets.filter { it.exerciseSlug == "finger.open_hand_block_lift" }
        // The injured hand on purpose, five holds at the careful start load, no warm-up ramp in front.
        assertEquals(5, holds.size)
        assertTrue(holds.all { it.side == Side.LEFT && it.setType == SetType.WORK })
        assertTrue(holds.all { it.loadKg == Comeback.START_KG || it.targetLoadKg == Comeback.START_KG })
        assertTrue(sets.none { it.setType == SetType.WARMUP && it.exerciseSlug.startsWith("finger.") })

        // Finishing asks for the highest pain; it lands in today's diary (the player's finish calls logSession).
        kit.db {
            holds.forEach { repo.saveSet(it.copy(durationS = 30.0, loadKg = 6.0, completedAt = System.currentTimeMillis())) }
            service.finishWorkout(workout.id, null, null)
            ComebackService(service).logSession(workout.id, 1)
        }
        kit.until("diary") { repo.comebackEntry("i1", today.toString())?.sessions?.isNotEmpty() == true }
        val log = kit.db { repo.comebackEntry("i1", today.toString())!!.sessions.single() }
        assertEquals(ComebackSessionKind.TENDON, log.kind)
        assertEquals(1, log.painMax)
        kit.until("diary shown") { p.state.value.todayEntry?.sessions?.isNotEmpty() == true }
        val after = model.currentState
        assertEquals(1, after.plan.diarySessions.size)
        // "Tendon session: 1/10" — the parts of a joined text are nested.
        assertEquals("1/10", after.plan.diarySessions.single().args[1].nested!!.args.single().text)
        // Two tendon sessions a day, six hours apart: the next one later today; the start stays open.
        assertEquals("later", after.plan.sessions.first().statusCode)
        assertEquals("cb_session_later", after.plan.sessions.first().status.key)
        texts(after, model)
        // The next session starts from the load the athlete used.
        assertEquals(6.0, kit.db { ComebackService(service).lastLoad("i1", "finger.open_hand_block_lift", Grip.OPEN_HAND, Side.LEFT) })
    }

    @Test
    fun `a week above the rule proposes a step back and only the tap changes the stage`() {
        val start = today.minus(DatePeriod(days = 9))
        kit.db {
            repo.saveInjury(Comeback.start(injury(20), PlanSource.CRUXCOACH, 2, start, true, false, emptyMap(), 1))
            (0..6).forEach { d ->
                val day = today.minus(DatePeriod(days = d)).toString()
                repo.saveComebackEntry(ComebackEntry("i1", day, morningPain = 1,
                    sessions = listOf(ComebackSessionLog(ComebackSessionKind.TENDON, if (d == 1 || d == 3) 5 else 1, at = 1)), updatedAt = 1))
            }
        }
        val (p, model) = screen()
        val prompt = model.currentState.plan.prompts.single { it.kind == "review" }
        // "Your pain has gone up": step back first, keeping the stage is the athlete's other choice.
        assertEquals("cb_review_back_title", prompt.title.key)
        assertTrue(prompt.caution)
        assertEquals(listOf("primary" to "step_back", "secondary" to "dismiss"), prompt.actions.map { it.slot to it.code })
        texts(model.currentState, model)
        assertEquals(2, kit.db { repo.injury("i1")!!.plan!!.stage })
        // A button the card does not show is refused; the tap on "step back" changes the stage.
        model.promptAction(prompt.key, "move_up")
        model.promptAction(prompt.key, "step_back")
        kit.until("stepped back") { repo.injury("i1")?.plan?.stage == 1 }
        val plan = kit.db { repo.injury("i1")!!.plan!! }
        assertEquals(listOf(StageChangeReason.START, StageChangeReason.STEP_BACK), plan.history.map { it.reason })
        // The prompt was answered; climbing pauses again with the stage.
        assertTrue(plan.prompts.containsKey(prompt.key))
        assertTrue(kit.db { repo.injury("i1")!!.climbingPaused })
        kit.until("prompt gone") { p.state.value.prompts.none { it.key == prompt.key } }
    }

    @Test
    fun `the diary saves today's values and the summary for the appointment carries them`() {
        kit.db { repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 1, today, false, false, emptyMap(), 1)) }
        val (p, model) = screen()
        // Not agreed yet: the plan asks to talk the stage through.
        assertTrue(model.currentState.plan.notAgreed)
        assertTrue(model.currentState.plan.chartEmpty)
        model.setMorningPain(2)
        model.setPressurePain(3)
        model.setNote("  taped  ")
        kit.until("diary") { repo.comebackEntry("i1", today.toString())?.pressurePain == 3 }
        val entry = kit.db { repo.comebackEntry("i1", today.toString())!! }
        assertEquals(2, entry.morningPain)
        assertEquals("  taped  ", entry.note)
        kit.until("shown") { p.state.value.todayEntry?.pressurePain == 3 && p.state.value.todayEntry?.note != null }
        val shown = model.currentState.plan
        assertEquals(2, shown.morningPain)
        assertEquals(3, shown.pressurePain)
        assertFalse(shown.chartEmpty)
        assertEquals(14, shown.chart.size)
        assertEquals(2, shown.chart.last().morning)
        assertEquals(3, shown.chart.last().pressure)
        assertEquals(-1, shown.chart.last().session)

        model.share("Summary", "Injury", "Finger", "Left", "Since", "Told", "Signs", "Plan", "Stage", "Reference", "Day", "yes")
        kit.until("summary") { p.share.value != null }
        val text = model.currentState.shareText
        assertTrue(text.contains("Not a substitute for seeing a doctor"))
        assertTrue(text.lines().any { it.startsWith(today.toString()) && it.contains("2\t3") })
        model.consumeShare()
        assertEquals("", model.currentState.shareText)

        // Tapping the chosen value again clears it; a blank note is no note.
        model.setMorningPain(-1)
        model.setNote("   ")
        model.markAgreed()
        kit.until("cleared") { repo.comebackEntry("i1", today.toString())?.morningPain == null }
        kit.until("note cleared") { repo.comebackEntry("i1", today.toString())?.note == null }
        kit.until("agreed") { repo.injury("i1")?.plan?.agreedWithProfessional == true }
        kit.until("agreed shown") { p.state.value.injury?.plan?.agreedWithProfessional == true }
        assertFalse(model.currentState.plan.notAgreed)
        texts(model.currentState, model)
    }

    @Test
    fun `a finding for the professional's plan starts with the own plan and its routine`() {
        val routine = Routine("r1", "Physio band work", items = listOf(RoutineItem("finger.open_hand_block_lift", sets = 2)))
        kit.db {
            repo.saveRoutine(routine)
            repo.saveInjury(injury().copy(check = InjuryCheck(checked = ProfessionalCheck.SURGERY, finding = InjuryFinding.FLEXOR_TENDON_TEAR)))
        }
        val (p, model) = screen()
        // The professional's plan comes first for this finding; the CruxCoach stages stay selectable, with a warning.
        assertEquals("own", model.currentState.setup.sourceCode)
        assertEquals("", model.currentState.setup.ownPlanNote)
        assertTrue(model.currentState.setup.findingNotes.isNotEmpty())
        model.chooseSource("cruxcoach")
        assertTrue(model.currentState.setup.ownPlanNote.isNotEmpty())
        model.chooseSource("own")
        assertFalse(model.currentState.setup.showStage)
        model.chooseSetupRoutine("no-such-routine")
        assertTrue(model.currentState.setup.routines.none { it.selected })
        model.chooseSetupRoutine("r1")
        assertTrue(model.currentState.setup.routines.single().selected)
        model.startPlan()
        kit.until("own plan") { repo.injury("i1")?.activePlan != null }
        val plan = kit.db { repo.injury("i1")!!.activePlan!! }
        assertEquals(PlanSource.OWN, plan.source)
        assertEquals(0, plan.stage)
        assertEquals("r1", plan.ownRoutineId)
        kit.until("shown") { p.state.value.injury?.activePlan != null }
        val running = model.currentState.plan
        assertEquals("own", running.sourceCode)
        assertTrue(running.sessions.isEmpty())
        assertTrue(running.ownRoutineChosen)
        assertEquals("cb_session_name", running.ownSessionName.key)
        texts(model.currentState, model)
        model.startOwn("Comeback · Physio band work")
        kit.until("started") { repo.openWorkout() != null }
        assertEquals(Comeback.routineId("i1", ComebackService.OWN_STAGE, 0), kit.db { repo.openWorkout()!!.routineId })
        kit.until("event") { p.started.value != null }
    }

    @Test
    fun `an urgent sign asks for 112 and the plan can end and its data be deleted`() {
        kit.db { repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 0, today, true, false, emptyMap(), 1)) }
        val (p, model) = screen()
        model.openCheck()
        model.toggleSign("deformity")
        model.toggleSign("pop_with_swelling")
        assertTrue(model.saveCheck())
        kit.until("signs") { p.state.value.injury?.check?.signs?.size == 2 }
        // The most urgent sign decides: now – the alert offers 112 first and never locks the plan.
        val alert = model.currentState.alert
        assertEquals("emergency", alert.urgency)
        assertFalse(alert.outOfHours)
        assertTrue(model.currentState.hasPlan)

        kit.db { repo.saveComebackEntry(ComebackEntry("i1", today.toString(), morningPain = 1, updatedAt = 1)) }
        model.end(resolved = true)
        kit.until("ended") { repo.injury("i1")?.plan?.endedOn != null }
        assertEquals(today.toString(), kit.db { repo.injury("i1")!!.resolvedOn })
        kit.until("setup again") { p.state.value.injury?.plan?.endedOn != null }
        val ended = model.currentState
        assertFalse(ended.hasPlan)
        assertTrue(ended.setup.restart)
        assertEquals(today.toString(), ended.setup.endedOn)

        model.deleteAll()
        kit.until("deleted") { repo.injury("i1")?.plan == null && repo.injury("i1")?.check == null }
        assertTrue(kit.db { repo.comebackEntries("i1") }.isEmpty())
        // The injury entry itself stays.
        assertEquals("Ring A2", kit.db { repo.injury("i1")!!.detail })
        kit.until("no signs") { p.state.value.injury?.check == null }
        assertEquals("", model.currentState.alert.urgency)
        assertNull(p.check.value)
    }

    @Test
    fun `a reference value can be entered later and the strength targets follow it`() {
        // Android d4d050bee (device run 2026-10-20): a finger plan started without a reference value.
        kit.db { repo.saveInjury(Comeback.start(injury(), PlanSource.CRUXCOACH, 3, today, true, false, emptyMap(), 1)) }
        val (p, model) = screen()
        val before = model.currentState
        assertTrue(before.plan.showReference)
        assertEquals("cb_reference_none", before.plan.reference.key)
        assertEquals("", before.plan.refHalf)
        assertFalse(before.plan.dominant)
        // Without a reference the strength session shows percentages only.
        assertTrue(targets(before).isEmpty())
        assertTrue(before.plan.sessions.flatMap { it.items }.any { line -> line.args.any { it.nested?.key == "cb_item_percent" } })
        val changes = before.planChanges
        texts(before, model)

        // Saved from the stage card's dialog: an impossible open-hand value is no value.
        model.setReferences(half = "28", open = "abc", dominant = true)
        kit.until("reference") { repo.injury("i1")?.plan?.referenceKg?.get(Grip.HALF_CRIMP.name) == 28.0 }
        val plan = kit.db { repo.injury("i1")!!.activePlan!! }
        assertEquals(mapOf(Grip.HALF_CRIMP.name to 28.0), plan.referenceKg)
        assertEquals(true, plan.injuredDominant)
        // The stage stays: no stage change, so the screen does not jump.
        assertEquals(3, plan.stage)
        kit.until("shown") { p.state.value.injury?.activePlan?.referenceKg?.isNotEmpty() == true }
        val after = model.currentState
        assertEquals(changes, after.planChanges)
        assertEquals("cb_reference_values", after.plan.reference.key)
        assertEquals(listOf(formatDecimal(28.0, 1), "–"), after.plan.reference.args.map { it.text })
        assertEquals(formatDecimal(28.0, 1), after.plan.refHalf)
        assertEquals("", after.plan.refOpen)
        assertTrue(after.plan.dominant)
        // Stage 3 strength: half crimp at 40–60 % of 28 kg (Android's "11.0–17.0 kg").
        assertTrue(listOf(formatDecimal(11.0, 1), formatDecimal(17.0, 1)) in targets(after), "${targets(after)}")
        texts(after, model)
    }

    @Test
    fun `the plan opens at its top on start and on every stage change`() {
        kit.db { repo.saveInjury(injury()) }
        val (p, model) = screen()
        // No plan yet: nothing to scroll to.
        assertEquals(-1, model.currentState.planChanges)
        model.chooseStage(1)
        model.setAgreed(true)
        model.startPlan()
        kit.until("plan") { p.state.value.injury?.activePlan != null }
        val started = model.currentState.planChanges
        assertEquals(1, started)
        model.moveToStage(2)
        kit.until("stage 2") { p.state.value.injury?.activePlan?.stage == 2 }
        assertEquals(started + 1, model.currentState.planChanges)
        // Picking the current stage again is no change.
        model.moveToStage(2)
        model.markAgreed()
        kit.until("agreed") { p.state.value.injury?.activePlan?.agreedWithProfessional == true }
        assertEquals(started + 1, model.currentState.planChanges)
        // References only on a running finger plan: a closed plan takes none.
        model.end(resolved = false)
        kit.until("ended") { p.state.value.injury?.activePlan == null }
        assertEquals(-1, model.currentState.planChanges)
        model.setReferences(half = "30", open = "", dominant = false)
        kit.until("still none") { repo.injury("i1")?.plan?.endedOn != null }
        assertTrue(kit.db { repo.injury("i1")!!.plan!!.referenceKg }.isEmpty())
    }

    /** The kg targets (from, to) of all of today's session lines. */
    private fun targets(s: ComebackScreenState): List<List<String>> =
        s.plan.sessions.flatMap { it.items }.flatMap { line -> line.args.mapNotNull { it.nested } }
            .filter { it.key == "cb_item_target" }.map { t -> t.args.map { it.text } }

    @Test
    fun `every comeback surface on iOS carries the notice in the owner's words`() {
        // Swift is compiled on CI only: this pins the owner's hard rule to the sources and the strings here.
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "iosApp/CruxCoach").isDirectory }
        val de = File(root, "iosApp/CruxCoach/Resources/de.lproj/Localizable.strings").readText()
        assertTrue(de.contains("\"cb_not_a_substitute\" = \"Kein Ersatz für den Arztbesuch – nur in Absprache mit Ärztin, Arzt oder Physio nutzen.\";"))
        assertEquals(ComebackContent.NOT_A_SUBSTITUTE.de, "Kein Ersatz für den Arztbesuch – nur in Absprache mit Ärztin, Arzt oder Physio nutzen.")
        val dir = File(root, "iosApp/CruxCoach/UI/Training/Comeback")
        val screen = File(dir, "ComebackView.swift").readText()
        val sheet = File(dir, "InjuryCheckSheet.swift").readText()
        val alert = File(dir, "ComebackSignsAlert.swift").readText()
        // The screen's top in every state, the questions sheet, the alert's message and the "before you start" dialog.
        assertTrue(screen.contains("ComebackNotice(onSigns:"))
        assertTrue(sheet.contains("ComebackNotice()"))
        assertTrue(alert.contains("L(\"cb_not_a_substitute\")"))
        assertTrue(alert.contains("tel:112"))
        // "Call 112" comes first in the urgent alert.
        assertTrue(alert.indexOf("cb_call_112") in 0 until alert.indexOf("cb_continue_anyway"))
        assertTrue(screen.contains("L(\"cb_before_text\")"))
        // The sheets over the running plan: "Choose the stage" and "Enter reference value" (Android d4d050bee).
        val sections = File(dir, "ComebackPlanSections.swift").readText()
        listOf("ComebackReferenceSheet", "ComebackStagePickerSheet").forEach { name ->
            val body = sections.substringAfter("struct $name").substringBefore("\nprivate struct ").substringBefore("\nstruct ")
            assertTrue(body.contains("ComebackNotice()"), "$name has no notice")
        }
    }
}
