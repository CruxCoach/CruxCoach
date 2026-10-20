package com.cruxcoach.athlete

import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.catalog.LoadMode
import com.cruxcoach.athlete.logic.ClimbingLevel
import com.cruxcoach.athlete.logic.Comeback
import com.cruxcoach.athlete.logic.ComebackContent
import com.cruxcoach.athlete.logic.ComebackTexts
import com.cruxcoach.athlete.logic.DayColor
import com.cruxcoach.athlete.logic.DueStatus
import com.cruxcoach.athlete.logic.InjuryAdvisor
import com.cruxcoach.athlete.logic.InjuryVerdict
import com.cruxcoach.athlete.logic.PromptKind
import com.cruxcoach.athlete.logic.Proposal
import com.cruxcoach.athlete.logic.Txt
import com.cruxcoach.athlete.model.ComebackEntry
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.ComebackSessionLog
import com.cruxcoach.athlete.model.ExerciseSet
import com.cruxcoach.athlete.model.Grip
import com.cruxcoach.athlete.model.Injury
import com.cruxcoach.athlete.model.InjuryCheck
import com.cruxcoach.athlete.model.InjuryFinding
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.InjurySide
import com.cruxcoach.athlete.model.PlanSource
import com.cruxcoach.athlete.model.Side
import com.cruxcoach.athlete.model.SideMode
import com.cruxcoach.athlete.model.StageChangeReason
import com.cruxcoach.athlete.model.Urgency
import com.cruxcoach.athlete.model.WarningSign
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The comeback plan (owner 2026-10-20): the athlete picks the stage agreed with a doctor or physio; the diary is
 * read against the stage's rule; nothing changes the stage without the athlete; warnings only point to a check.
 */
class ComebackTest {

    private val today = LocalDate(2026, 10, 20)
    private val now = 1_000_000_000L
    private fun day(offset: Int) = today.plus(DatePeriod(days = offset))

    private fun finger(finding: InjuryFinding? = InjuryFinding.PULLEY_STRAIN) = Injury(
        "i1", InjuryRegion.FINGER, InjurySide.LEFT, "Ring A2", severity = 3, startedOn = day(-20).toString(),
        check = InjuryCheck(finding = finding),
    )

    private fun started(stage: Int, since: LocalDate = day(-10), injury: Injury = finger()) =
        Comeback.start(injury, PlanSource.CRUXCOACH, stage, since, agreedWithProfessional = true, injuredDominant = false,
            referenceKg = mapOf(Grip.HALF_CRIMP.name to 30.0, Grip.OPEN_HAND.name to 32.0), now = 1)

    private fun entry(offset: Int, morning: Int? = null, pressure: Int? = null, session: Int? = null, kind: ComebackSessionKind = ComebackSessionKind.TENDON) =
        ComebackEntry("i1", day(offset).toString(), morningPain = morning, pressurePain = pressure,
            sessions = session?.let { listOf(ComebackSessionLog(kind, it, at = 1)) }.orEmpty())

    // ── Stages and plan changes ─────────────────────────────────────

    @Test
    fun startingAStageWithoutClimbingPausesClimbingAndMovingOnResumesIt() {
        val atOne = started(1)
        assertEquals(1, atOne.plan!!.stage)
        assertTrue(atOne.climbingPaused)
        assertEquals(ClimbingLevel.NONE, Comeback.climbingLevel(atOne))
        val atTwo = Comeback.moveTo(atOne, 2, StageChangeReason.MOVE_UP, today, now)
        assertEquals(2, atTwo.plan!!.stage)
        assertFalse(atTwo.climbingPaused)
        assertEquals(ClimbingLevel.EASY, Comeback.climbingLevel(atTwo))
        assertEquals(listOf(StageChangeReason.START, StageChangeReason.MOVE_UP), atTwo.plan!!.history.map { it.reason })
        assertEquals(today.toString(), atTwo.plan!!.stageSince)
    }

    @Test
    fun theFingerStagesFollowTheResearchOrder() {
        val stages = ComebackContent.FINGER.stages
        assertEquals(6, stages.size)
        // Open hand before half crimp: no half-crimp item for the injured hand before stage 2.
        stages.take(2).flatMap { s -> s.sessions.flatMap { it.items } }
            .filter { it.side == com.cruxcoach.athlete.logic.SideTarget.INJURED }
            .forEach { assertTrue(it.grip != Grip.HALF_CRIMP && it.grip != Grip.FULL_CRIMP, "${it.slug} ${it.grip}") }
        // Climbing grows stage by stage.
        assertEquals(listOf(ClimbingLevel.NONE, ClimbingLevel.NONE, ClimbingLevel.EASY, ClimbingLevel.MODERATE, ClimbingLevel.HARD, ClimbingLevel.FULL),
            stages.map { it.climbing })
        // No full crimp item anywhere: it is optional and described, never planned.
        assertTrue(stages.flatMap { s -> s.sessions.flatMap { it.items } }.none { it.grip == Grip.FULL_CRIMP })
    }

    @Test
    fun findingsAdjustThePainRule() {
        assertEquals(2, Comeback.painRule(started(1)).greenMax)
        assertEquals(1, Comeback.painRule(started(1, injury = finger(InjuryFinding.PULLEY_RUPTURE))).greenMax)
        assertEquals(2, Comeback.painRule(started(3, injury = finger(InjuryFinding.PULLEY_RUPTURE))).greenMax)
        val sheath = Comeback.painRule(started(2, injury = finger(InjuryFinding.TENOSYNOVITIS)))
        assertTrue(sheath.overuse); assertEquals(3, sheath.greenMax); assertEquals(5, sheath.amberMax)
    }

    // ── Reading the diary ────────────────────────────────────────────

    @Test
    fun daysAreReadAgainstTheRuleAndTheMorningsBefore() {
        val injury = started(2)
        val entries = listOf(entry(-3, morning = 1), entry(-2, morning = 1, session = 2), entry(-1, morning = 2, session = 3), entry(0, morning = 3, session = 5))
        val r = Comeback.readings(injury, entries, day(-3), today)
        assertEquals(listOf(DayColor.GREEN, DayColor.GREEN, DayColor.AMBER, DayColor.RED), r.map { it.color })
        // Today's morning 3 against the median of 1, 1, 2.
        assertEquals(2, r.last().morningRise)
        assertNull(Comeback.readings(injury, emptyList(), today, today).single().color)
    }

    @Test
    fun aGreenWeekProposesTheNextStageButNeverMoves() {
        val injury = started(1, since = day(-8))
        val entries = (-6..0).map { entry(it, morning = 1, pressure = 2, session = 1) }
        val review = Comeback.review(injury, entries, today)!!
        assertEquals(Proposal.MOVE_UP, review.proposal)
        assertTrue(review.due)
        assertEquals(1, injury.plan!!.stage)
        val prompt = Comeback.prompts(injury, entries, today).single { it.kind == PromptKind.REVIEW }
        // Answered once per stage week.
        val answered = Comeback.markPrompt(injury, prompt.key, today, now)
        assertTrue(Comeback.prompts(answered, entries, today).none { it.kind == PromptKind.REVIEW })
    }

    @Test
    fun aWeekWithPainAboveTheRuleRepeatsOrStepsBack() {
        val injury = started(2, since = day(-8))
        val oneRed = (-6..0).map { entry(it, morning = 1, session = if (it == -3) 5 else 1) }
        val r1 = Comeback.review(injury, oneRed, today)!!
        assertEquals(Proposal.REPEAT, r1.proposal)
        assertEquals(day(-3) to 5, r1.worst)
        val twoRed = (-6..0).map { entry(it, morning = 1, session = if (it == -3 || it == -1) 5 else 1) }
        assertEquals(Proposal.STEP_BACK, Comeback.review(injury, twoRed, today)!!.proposal)
        // At the first stage there is nothing to step back to.
        assertEquals(Proposal.STAY, Comeback.review(started(0, since = day(-8)), twoRed, today)!!.proposal)
    }

    @Test
    fun tooFewDaysOrRisingPressurePainStayAtTheStage() {
        assertEquals(Proposal.STAY, Comeback.review(started(1, since = day(-3)), (-3..0).map { entry(it, morning = 1, session = 1) }, today)!!.proposal)
        val rising = (-13..0).map { entry(it, morning = 1, session = 1, pressure = if (it < -6) 1 else 3) }
        val review = Comeback.review(started(1, since = day(-13)), rising, today)!!
        assertEquals(com.cruxcoach.athlete.logic.PainTrend.RISING, review.pressureTrend)
        assertEquals(Proposal.STAY, review.proposal)
        assertEquals(com.cruxcoach.athlete.logic.StayReason.PRESSURE_RISING, review.stayReason)
        val last = Comeback.review(started(5, since = day(-13)), (-6..0).map { entry(it, morning = 1, session = 1) }, today)!!
        assertEquals(com.cruxcoach.athlete.logic.StayReason.LAST_STAGE, last.stayReason)
    }

    @Test
    fun worseningAndNoImprovementPointToACheckOnly() {
        val injury = started(2, since = day(-25))
        val worse = listOf(entry(-2, morning = 2), entry(-1, morning = 3), entry(0, morning = 4))
        val p = Comeback.prompts(injury, worse, today).first { it.kind == PromptKind.WORSENING }
        assertEquals(2, p.from); assertEquals(4, p.to)
        // Quiet for a week once shown.
        assertTrue(Comeback.prompts(Comeback.markPrompt(injury, p.key, today, now), worse, today).none { it.kind == PromptKind.WORSENING })

        val flat = (-21..0 step 3).map { entry(it, morning = 3, pressure = 4) }
        assertEquals(PromptKind.NOT_BETTER_21, Comeback.prompts(injury, flat, today).first { it.kind.name.startsWith("NOT_BETTER") }.kind)
        val fortnight = (-15..0 step 3).map { entry(it, morning = 3, pressure = 4) }
        assertEquals(PromptKind.NOT_BETTER_14, Comeback.prompts(injury, fortnight, today).first { it.kind.name.startsWith("NOT_BETTER") }.kind)
        val better = (-21..0 step 3).mapIndexed { i, o -> entry(o, morning = (5 - i).coerceAtLeast(0), pressure = (6 - i).coerceAtLeast(0)) }
        assertNull(Comeback.notBetter(better, today))
    }

    // ── Today's sessions ─────────────────────────────────────────────

    @Test
    fun tendonSessionsKeepSixHoursAndStrengthTwoDaysApart() {
        val injury = started(1)
        val tendon = 0; val strength = 1
        var due = Comeback.due(injury, emptyList(), today, now)
        assertEquals(DueStatus.DUE, due[tendon].status); assertEquals(DueStatus.DUE, due[strength].status)

        val morning = ComebackEntry("i1", today.toString(), sessions = listOf(ComebackSessionLog(ComebackSessionKind.TENDON, 1, at = now - 2 * 3_600_000L)))
        due = Comeback.due(injury, listOf(morning), today, now)
        assertEquals(DueStatus.LATER, due[tendon].status)
        assertEquals(now + 4 * 3_600_000L, due[tendon].nextAt)
        assertEquals(DueStatus.DUE, Comeback.due(injury, listOf(morning), today, now + 5 * 3_600_000L)[tendon].status)

        val yesterdayStrength = ComebackEntry("i1", day(-1).toString(), sessions = listOf(ComebackSessionLog(ComebackSessionKind.STRENGTH, 1, at = now - 20 * 3_600_000L)))
        assertEquals(DueStatus.NOT_TODAY, Comeback.due(injury, listOf(yesterdayStrength), today, now)[strength].status)
    }

    @Test
    fun sessionsBecomeTrainingsForTheRightHandWithCarefulLoads() {
        val injury = started(3)
        val routine = Comeback.routine(injury, 1, "Kraft", lastLoad = { _, _, _ -> null })!!
        assertEquals(Comeback.routineId("i1", 3, 1), routine.id)
        assertEquals("i1" to 1, Comeback.parseRoutineId(routine.id))
        val halfInjured = routine.items.first { it.grip == Grip.HALF_CRIMP && it.sides == SideMode.LEFT_ONLY }
        // 40 % of the 30 kg reference.
        assertEquals(12.0, halfInjured.loadKg)
        assertTrue(routine.items.any { it.sides == SideMode.RIGHT_ONLY }, "the healthy hand keeps training")
        // The last comeback load wins over the percentage.
        val again = Comeback.routine(injury, 1, "Kraft", lastLoad = { _, grip, side -> if (grip == Grip.HALF_CRIMP && side == Side.LEFT) 14.5 else null })!!
        assertEquals(14.5, again.items.first { it.grip == Grip.HALF_CRIMP && it.sides == SideMode.LEFT_ONLY }.loadKg)
        // Nothing known: the start load for the injured hand.
        val fresh = Comeback.start(finger(), PlanSource.CRUXCOACH, 1, today, true, null, emptyMap(), 1)
        val gentle = Comeback.routine(fresh, 0, "Sehnen", lastLoad = { _, _, _ -> null })!!
        assertEquals(Comeback.START_KG, gentle.items.first { it.slug == "finger.open_hand_block_lift" }.loadKg)
        // An injury on both hands has no healthy hand to train.
        val both = started(3, injury = finger().copy(side = InjurySide.BOTH))
        assertTrue(Comeback.routine(both, 1, "Kraft", lastLoad = { _, _, _ -> null })!!.items.none { it.sides != SideMode.BOTH })
    }

    @Test
    fun referenceComesFromHoldsBeforeTheInjury() {
        fun set(kg: Double, side: Side, at: Long, s: Double = 10.0) = ExerciseSet("s$kg$at", "w", "finger.one_arm_pickup", 0, 0,
            side = side, loadKg = kg, durationS = s, grip = Grip.HALF_CRIMP, completedAt = at)
        val history = listOf(set(31.0, Side.LEFT, 10), set(33.0, Side.LEFT, 100), set(40.0, Side.LEFT, 10, s = 3.0), set(29.0, Side.RIGHT, 10))
        assertEquals(31.0, Comeback.referenceFrom(history, "finger.one_arm_pickup", Grip.HALF_CRIMP, Side.LEFT, before = 50))
        assertEquals(29.0, Comeback.referenceFrom(history, "finger.one_arm_pickup", Grip.HALF_CRIMP, Side.RIGHT))
    }

    // ── The coach's injury filter follows the stage ─────────────────

    private val hangboard = ExerciseDefinition("finger.max_hang", ExerciseCategoryV2.FINGER, ExerciseKind.HANG, LoadMode.BODYWEIGHT_PLUS,
        domains = listOf(LoadDomain.FINGER))
    private val oneArm = hangboard.copy(slug = "finger.one_arm_pickup", unilateral = true, load = LoadMode.EXTERNAL)
    private val boulder = ExerciseDefinition("climb.boulder", ExerciseCategoryV2.TECHNIQUE, ExerciseKind.CLIMB, LoadMode.NONE,
        domains = listOf(LoadDomain.FINGER), equipment = listOf(com.cruxcoach.athlete.catalog.EquipmentV2.WALL))

    @Test
    fun fingerWorkOutsideThePlanWaitsForTheLastStage() {
        val early = started(2)
        assertEquals(InjuryVerdict.AVOID, InjuryAdvisor.assess(hangboard, listOf(early)).verdict)
        assertEquals(InjuryVerdict.ONE_SIDE_ONLY, InjuryAdvisor.assess(oneArm, listOf(early)).verdict)
        assertEquals(Side.RIGHT, InjuryAdvisor.assess(oneArm, listOf(early)).allowedSide)
        assertEquals(InjuryVerdict.CAUTION, InjuryAdvisor.assess(boulder, listOf(early)).verdict, "easy climbing is part of stage 2")
        assertEquals(InjuryVerdict.AVOID, InjuryAdvisor.assess(boulder, listOf(started(1))).verdict)
        assertEquals(InjuryVerdict.CAUTION, InjuryAdvisor.assess(hangboard, listOf(started(5))).verdict)
        // An ended plan hands back to the usual injury rules.
        val ended = Comeback.end(early, today, now)
        assertNull(Comeback.climbingLevel(ended))
    }

    // ── Warning signs and wording ───────────────────────────────────

    @Test
    fun warningSignsFitTheRegionAndTheMostUrgentWins() {
        val finger = Comeback.signsFor(InjuryRegion.FINGER)
        assertTrue(WarningSign.BOWSTRINGING in finger && WarningSign.FINGERTIP_CANNOT_BEND in finger && WarningSign.DEFORMITY in finger)
        assertFalse(WarningSign.KNEE_LOCKS_OR_SWELLED in finger)
        assertEquals(Urgency.EMERGENCY, Comeback.urgency(setOf(WarningSign.HAD_IT_BEFORE, WarningSign.NUMB_OR_COLD)))
        assertEquals(Urgency.DAYS, Comeback.urgency(setOf(WarningSign.HAD_IT_BEFORE, WarningSign.POP_WITH_SWELLING)))
        assertNull(Comeback.urgency(emptySet()))
        assertTrue(Comeback.under18(finger(), 2010, today))
        assertFalse(Comeback.under18(finger(), 2000, today))
        assertFalse(Comeback.under18(finger(), null, today))
        WarningSign.entries.forEach { s -> assertTrue(ComebackTexts.sign(s).de.isNotBlank() && ComebackTexts.sign(s).en.isNotBlank()) }
    }

    @Test
    fun everyRegionHasStagesAndEveryFindingHasWords() {
        InjuryRegion.entries.filter { ComebackContent.hasPlan(it) }.forEach { r ->
            val ladder = ComebackContent.ladderFor(r)
            assertEquals(6, ladder.stages.size, r.name)
            assertTrue(ladder.sources.isNotEmpty(), r.name)
        }
        InjuryFinding.entries.forEach { f ->
            assertTrue(ComebackTexts.finding(f).de.isNotBlank())
            assertTrue(f.regions.isNotEmpty())
        }
        assertEquals(ComebackContent.ELBOW_MEDIAL, ComebackContent.ladderFor(InjuryRegion.ELBOW, InjuryFinding.MEDIAL_ELBOW))
        assertEquals(ComebackContent.HAMSTRING, ComebackContent.ladderFor(InjuryRegion.KNEE, InjuryFinding.HAMSTRING))
    }

    /** research/safety-regulation.md §4.7: no treatment or healing words, no promises – the app is a training aid. */
    @Test
    fun noTextPromisesHealingOrTreatment() {
        val forbiddenDe = listOf("Reha", "Therapie", "Behandlungsplan", " heilt", "geheilt", "Diagnose", "garantiert", "freigegeben", "erlaubt", "sicher ist")
        val forbiddenEn = listOf("rehab", "therapy", "cures", "diagnosis", "guaranteed", "cleared", "allowed", "is safe")
        val texts = mutableListOf<Txt>()
        ComebackContent.all.forEach { l ->
            texts += l.evidence
            l.stages.forEach { s -> texts += listOf(s.title, s.goal, s.climbingText) + s.doThis + s.nextWhen + s.avoid }
        }
        InjuryFinding.entries.forEach { f -> texts += ComebackTexts.findingNotes(f); ComebackTexts.typicalCourse(f)?.let { texts += it } }
        WarningSign.entries.forEach { texts += ComebackTexts.sign(it); ComebackTexts.signWhy(it)?.let { w -> texts += w } }
        texts += listOf(ComebackContent.NOT_A_SUBSTITUTE, ComebackTexts.GELATIN, ComebackTexts.UNDER_18, ComebackTexts.NOTHING_TICKED)
        texts.forEach { t ->
            forbiddenDe.forEach { w -> assertFalse(t.de.contains(w), "\"$w\" in: ${t.de}") }
            forbiddenEn.forEach { w -> assertFalse(t.en.lowercase().contains(w), "\"$w\" in: ${t.en}") }
            // "Healed" only to say that criteria do not show it.
            if (t.de.contains("verheilt")) assertTrue(t.de.contains("nicht"), t.de)
            if (t.en.contains("healed")) assertTrue(t.en.contains("not"), t.en)
        }
        // The one place that may say "healed" says that criteria do not show it.
        assertTrue(ComebackContent.FINGER.stages.last().nextWhen.any { it.de.contains("nicht, dass die Verletzung verheilt ist") })
    }

    @Test
    fun aStageSinceDayCountsFromTheLastChange() {
        val injury = started(1, since = day(-9))
        assertEquals(9, Comeback.daysOnStage(injury.plan!!, today))
        val repeated = Comeback.moveTo(injury, 1, StageChangeReason.REPEAT, today.minus(DatePeriod(days = 2)), now)
        assertEquals(2, Comeback.daysOnStage(repeated.plan!!, today))
        assertNotNull(Comeback.review(repeated, emptyList(), today))
        assertFalse(Comeback.review(repeated, emptyList(), today)!!.due)
    }
}
