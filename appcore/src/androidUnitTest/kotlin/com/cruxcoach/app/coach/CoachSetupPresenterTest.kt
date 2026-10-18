package com.cruxcoach.app.coach

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.CoachSetupScreenModel
import com.cruxcoach.app.ui.CoachSetupScreenState
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.CoachGoal
import com.cruxcoach.athlete.model.CoachProfile
import com.cruxcoach.athlete.model.ExperienceBand
import com.cruxcoach.athlete.model.FocusArea
import com.cruxcoach.athlete.model.PLAN_REST
import com.cruxcoach.athlete.model.SetupState
import com.cruxcoach.data.repository.UserRepositoryImpl
import com.cruxcoach.domain.model.UserProfile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The coach setup on iOS runs Android's CoachSetupViewModel (ported) against a real database. */
class CoachSetupPresenterTest {
    private lateinit var kit: TrainingKit
    private val repo get() = kit.repo
    private val service get() = kit.service
    private lateinit var users: UserRepositoryImpl

    @BeforeTest
    fun setUp() {
        kit = TrainingKit()
        kit.standardAthlete()
        users = UserRepositoryImpl(kit.athlete.secureDb)
    }

    @AfterTest
    fun tearDown() = kit.close()

    private fun screen(): Pair<CoachSetupPresenter, CoachSetupScreenModel> {
        val p = CoachSetupPresenter(service, users, null, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        return p to CoachSetupScreenModel(p, "en")
    }

    private fun checkTexts(s: CoachSetupScreenState) {
        assertTrue(TrTextCheck.checkAll(s) > 0)
    }

    private fun oldProfile(age: Int = 30, years: Double = 4.0, sessions: Int = 4, grade: String = "6c") = kit.db {
        users.insertProfile(UserProfile(name = "A", age = age, weightKg = 70.0, heightCm = 175.0, maxBoulderGrade = grade,
            climbingYears = years, sessionsPerWeek = sessions))
    }

    // ── Android companion rules ─────────────────────────────────────

    @Test
    fun `experience and age bands follow the Android boundaries`() {
        assertEquals(ExperienceBand.UNDER_1, CoachSetupPresenter.experienceFor(0.9))
        assertEquals(ExperienceBand.Y1_2, CoachSetupPresenter.experienceFor(1.0))
        assertEquals(ExperienceBand.Y1_2, CoachSetupPresenter.experienceFor(2.99))
        assertEquals(ExperienceBand.Y3_5, CoachSetupPresenter.experienceFor(5.0))
        assertEquals(ExperienceBand.OVER_5, CoachSetupPresenter.experienceFor(5.1))
        assertEquals(AgeBand.UNDER_16, CoachSetupPresenter.ageBandFor(15))
        assertEquals(AgeBand.Y16_17, CoachSetupPresenter.ageBandFor(17))
        assertEquals(AgeBand.Y18_39, CoachSetupPresenter.ageBandFor(39))
        assertEquals(AgeBand.Y40_54, CoachSetupPresenter.ageBandFor(40))
        assertEquals(AgeBand.Y55_PLUS, CoachSetupPresenter.ageBandFor(55))
    }

    @Test
    fun `a flash is capped at the working grade and grades step along the Font scale`() {
        assertEquals("6c", CoachSetupPresenter.capFlash("7a", "6c"))
        assertEquals("6b", CoachSetupPresenter.capFlash("6b", "6c"))
        assertNull(CoachSetupPresenter.capFlash(null, "6c"))
        assertEquals("7a", CoachSetupPresenter.capFlash("7a", null))
        // From no value the first tap lands on 6a, whichever button.
        assertEquals("6a", CoachSetupPresenter.stepGrade(null, null, 1))
        assertEquals("6a", CoachSetupPresenter.stepGrade(null, null, -1))
        val scale = com.cruxcoach.athlete.logic.LogbookSummaries.fontScale
        val i = scale.indexOf("6a")
        assertEquals(scale[i + 1], CoachSetupPresenter.stepGrade("6a", null, 1))
        assertEquals(scale[i - 1], CoachSetupPresenter.stepGrade("6A", null, -1))
        assertEquals(scale.last(), CoachSetupPresenter.stepGrade(scale.last(), null, 1))
        assertEquals(scale.first(), CoachSetupPresenter.stepGrade(scale.first(), null, -1))
    }

    @Test
    fun `merging a card takes only that card's fields`() {
        val stored = CoachProfile(goal = CoachGoal.COMEBACK, trainingDaysPerWeek = 2, experience = ExperienceBand.Y1_2,
            focus = setOf(FocusArea.CORE))
        val draft = CoachProfile(goal = CoachGoal.EVENT, targetDate = "2030-01-01", trainingDaysPerWeek = 5,
            experience = ExperienceBand.OVER_5, focus = setOf(FocusArea.POWER), variety = 10)
        val week = CoachSetupPresenter.mergeCard(1, stored, draft)
        assertEquals(5, week.trainingDaysPerWeek)
        assertEquals(CoachGoal.COMEBACK, week.goal)
        assertEquals(ExperienceBand.Y1_2, week.experience)
        val reverted = CoachSetupPresenter.revertCard(3, draft, stored)
        assertEquals(setOf(FocusArea.CORE), reverted.focus)
        assertNull(reverted.variety)
        assertEquals(CoachGoal.EVENT, reverted.goal)
        assertEquals(stored, CoachSetupPresenter.mergeCard(4, stored, draft))
    }

    // ── The cards ───────────────────────────────────────────────────

    @Test
    fun `the old settings profile prefills days experience age and grade`() {
        oldProfile(age = 45, years = 4.0, sessions = 4, grade = "6C")
        val (p, model) = screen()
        val d = p.state.value.draft
        assertEquals(4, d.trainingDaysPerWeek)
        assertEquals(ExperienceBand.Y3_5, d.experience)
        assertEquals(AgeBand.Y40_54, d.ageBand)
        assertEquals("6c", d.currentGrade)
        assertEquals(PrefillSource.PROFILE, p.state.value.prefill[PrefillField.TRAINING_DAYS])
        val s = model.currentState
        assertEquals("trc_prefill_profile", s.trainingDaysPrefill.key)
        assertEquals("trc_prefill_profile", s.gradePrefill.key)
        assertEquals("", s.flashPrefill.key)
        assertTrue(s.ages.single { it.code == "Y40_54" }.selected)
        // 40+ needs longer recovery; the preview says so.
        assertTrue(s.guardrailPreview.args.any { it.nested?.key == "trc_preview_recovery" })
        checkTexts(s)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `next stores each card and skip drops the changes of the card`() {
        val (p, model) = screen()
        assertEquals(0, model.currentState.step)
        model.toggleGoal("BUILD_STRENGTH")
        model.stepGrade("target", 1)
        model.next()
        kit.until("goal stored") { repo.profile().coach.goal == CoachGoal.BUILD_STRENGTH }
        assertEquals(AthleteGoal.BUILD_STRENGTH, kit.db { repo.profile().goal })
        assertEquals("6a", kit.db { repo.profile().coach.targetGrade })
        assertEquals(SetupState.IN_PROGRESS, kit.db { repo.profile().coach.setupState })
        assertEquals(1, model.currentState.step)

        // Week card: nothing chosen, "Next" takes the weekly goal it shows; minutes and climbing days are kept.
        val goal = kit.db { repo.profile().weeklyGoal }
        assertEquals(goal, model.currentState.trainingDays)
        model.toggleClimbingDay(2)
        model.toggleClimbingDay(9)
        model.setMinutes(60)
        model.setMinutes(61)
        model.toggleAddOn("yes")
        assertEquals("yes", model.currentState.addOnCode)
        model.next()
        kit.until("week stored") { repo.profile().coach.climbingDays == setOf(2) && repo.profile().sessionMinutes == 60 }
        assertEquals(goal.coerceIn(1, 7), kit.db { repo.profile().coach.trainingDaysPerWeek })
        assertEquals(true, kit.db { repo.profile().coach.addOnAfterClimbing })

        // Experience card skipped: its changes go, the stored answers stay.
        model.toggleExperience("OVER_5")
        model.setRopeGrade("  ")
        model.setRopeGrade("7a+ and more")
        assertEquals("7a+ and ", p.state.value.draft.ropeGrade)
        model.skip()
        assertEquals(3, model.currentState.step)
        assertNull(p.state.value.draft.experience)
        assertNull(p.state.value.draft.ropeGrade)

        // Preferences: at most three focus areas; "don't know" clears them.
        listOf("POWER", "CORE", "MOBILITY", "PREVENTION").forEach { model.toggleFocus(it) }
        assertEquals(3, p.state.value.draft.focus.size)
        assertFalse(model.currentState.focus.single { it.code == "PREVENTION" }.enabled)
        model.toggleFocus("")
        assertTrue(model.currentState.focusUnknown)
        model.toggleFocus("CORE")
        model.setVariety(20)
        model.setIntensity(-1)
        assertEquals("trc_favorites_routine", model.currentState.favoritesHint.key)
        assertEquals(-1, model.currentState.intensity)
        checkTexts(model.currentState)
        model.next()
        kit.until("prefs stored") { repo.profile().coach.focus == setOf(FocusArea.CORE) && repo.profile().coach.variety == 20 }
        assertTrue(model.currentState.isLastStep)
        model.back()
        assertEquals(3, model.currentState.step)
        model.next()

        model.finish()
        kit.until("done") { repo.profile().coach.setupState == SetupState.DONE }
        // A goal starts the first training block.
        assertEquals(service.today().toString(), kit.db { repo.profile().coach.blockStartDay })
        assertTrue(model.currentState.finished)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `the week and grade answers are written back to the old settings profile`() {
        oldProfile(sessions = 2, grade = "6a")
        val (_, model) = screen()
        model.next()
        model.changeTrainingDays(1)
        model.changeTrainingDays(9)
        assertEquals(7, model.currentState.trainingDays)
        model.next()
        kit.until("sessions") { users.getActiveProfile()!!.sessionsPerWeek == 7 }
        kit.until("weekly goal") { repo.profile().weeklyGoal == 7 }
        model.setGrade("current", "7A")
        model.setGrade("flash", "7b")
        // The flash is capped at the working grade.
        assertEquals("7a", model.currentState.flashGrade)
        model.next()
        kit.until("grade") { users.getActiveProfile()!!.maxBoulderGrade.lowercase() == "7a" }
        assertEquals("7a", kit.db { repo.profile().coach.currentGrade })
    }

    @Test
    fun `a lower grade never lowers the old profile's max grade`() {
        oldProfile(grade = "7a")
        val (_, model) = screen()
        model.next(); model.next()
        model.setGrade("current", "6b")
        model.setGrade("current", "no grade")
        assertEquals("6b", model.currentState.currentGrade)
        model.next()
        kit.until("grade stored") { repo.profile().coach.currentGrade == "6b" }
        assertEquals("7a", kit.db { users.getActiveProfile()!!.maxBoulderGrade.lowercase() })
    }

    @Test
    fun `a target date must not lie in the past and makes the goal an event`() {
        val (p, model) = screen()
        assertFalse(model.setTargetDate("2000-01-01"))
        assertFalse(model.setTargetDate("not a date"))
        assertTrue(model.setTargetDate(service.today().toString()))
        assertEquals(CoachGoal.EVENT, p.state.value.draft.goal)
        assertEquals("trc_preview_goal_event", model.currentState.goalPreview.args.first().nested?.key)
        model.clearTargetDate()
        assertEquals("", model.currentState.targetDate)
        checkTexts(model.currentState)
    }

    @Test
    fun `favourites are searched added and removed`() {
        val (p, model) = screen()
        val hit = model.searchExercises("Dead Bug").first { it.slug == "core.dead_bug" }
        assertFalse(hit.isFavorite)
        model.toggleFavorite("core.dead_bug")
        model.toggleFavorite("no.such_exercise")
        kit.until("favourite") { "core.dead_bug" in repo.favorites() }
        kit.until("shown") { p.state.value.favorites.contains("core.dead_bug") }
        assertEquals(listOf("core.dead_bug"), model.currentState.favorites.map { it.slug })
        assertTrue(model.searchExercises("Dead Bug").first { it.slug == "core.dead_bug" }.isFavorite)
        model.removeFavorite("core.dead_bug")
        kit.until("removed") { "core.dead_bug" !in repo.favorites() }
    }

    @Test
    fun `the proposed week is applied and undone`() {
        kit.db { repo.updateProfile { it.copy(weekPlan = mapOf(1 to PLAN_REST)) } }
        val (p, model) = screen()
        val proposal = p.state.value.proposal
        assertEquals(7, model.currentState.plan.size)
        model.applyPlan()
        assertTrue(model.currentState.planApplied)
        kit.until("applied") { repo.profile().weekPlan == proposal }
        model.undoPlan()
        assertFalse(model.currentState.planApplied)
        kit.until("undone") { repo.profile().weekPlan == mapOf(1 to PLAN_REST) }
        checkTexts(model.currentState)
    }

    @Test
    fun `the test session finishes the setup and starts the baseline tests`() {
        val (p, model) = screen()
        val title = "Baseline"
        model.startTestSession(title)
        kit.until("started") { p.state.value.testStarted }
        val open = kit.db { repo.openWorkout() }
        assertNotNull(open)
        assertEquals(BuiltinRoutines.byKey(BuiltinRoutines.BASELINE_TESTS)?.id, open.routineId)
        assertEquals(SetupState.DONE, kit.db { repo.profile().coach.setupState })
        model.consumeTestStarted()
        assertFalse(model.currentState.testStarted)
    }

    @Test
    fun `an open training asks before the test session replaces it`() {
        kit.db { service.startWorkout(null, "Mine") }
        val (p, model) = screen()
        model.startTestSession("Baseline")
        kit.until("asked") { p.pendingStart.value != null }
        val s = model.currentState
        assertTrue(s.hasPendingStart)
        assertEquals("trw_open_title", s.pendingTitle.key)
        checkTexts(s)
        assertFalse(p.state.value.testStarted)
        model.resolveStart(1)
        kit.until("replaced") { p.state.value.testStarted }
        assertEquals(BuiltinRoutines.byKey(BuiltinRoutines.BASELINE_TESTS)?.id, kit.db { repo.openWorkout()?.routineId })
        assertFalse(model.currentState.hasPendingStart)
    }

    @Test
    fun `do not ask again keeps the answers and hides the setup`() {
        val (_, model) = screen()
        model.toggleGoal("STAY_HEALTHY")
        model.next()
        kit.until("stored") { repo.profile().coach.goal == CoachGoal.STAY_HEALTHY }
        model.dismiss()
        kit.until("dismissed") { repo.profile().coach.setupState == SetupState.DISMISSED }
        assertEquals(CoachGoal.STAY_HEALTHY, kit.db { repo.profile().coach.goal })
    }

    @Test
    fun `a weight is logged in the profile units and implausible ones are refused`() {
        val (p, model) = screen()
        assertFalse(model.logWeight(5.0))
        assertTrue(model.logWeight(72.5))
        kit.until("weight") { (p.state.value.bodyweightKg ?: 0.0) > 70.0 }
        assertTrue(model.currentState.hasWeight)
        checkTexts(model.currentState)
    }

    @Test
    fun `open projects come from the board logbook without sent climbs or few tries`() {
        val board = com.cruxcoach.data.repository.PersonalBoardRepositoryImpl(kit.athlete.secureDb)
        val day = service.today().toString()
        kit.db {
            fun bid(uuid: String, climb: String, tries: Long, time: String) = board.insertBid(uuid, climb, 40, false, tries, null,
                "$day $time", false, climbName = "Climb $climb", difficultyAverage = 22.0)
            bid("b1", "A", 2, "10:00:00")
            bid("b2", "A", 1, "11:00:00")
            bid("b3", "B", 5, "10:00:00")
            board.insertAscent("s1", "B", 40, false, 1, 1, 3, 22, false, null, "$day 12:00:00", false,
                climbName = "Climb B", difficultyAverage = 22.0, climbFrames = "", framesCount = 0)
            bid("b4", "C", 2, "10:00:00")
        }
        val p = CoachSetupPresenter(service, users, board, kit.scope(), kit.dispatcher)
        kit.until("loaded") { !p.state.value.loading }
        val projects = p.state.value.projects
        assertEquals(listOf("A"), projects.map { it.climbUuid })
        assertEquals(3, projects.single().tries)
        assertEquals("7a", projects.single().grade)
        val model = CoachSetupScreenModel(p, "en")
        model.toggleProject("A")
        assertEquals(CoachGoal.PROJECT, p.state.value.draft.goal)
        assertEquals("Climb A", p.state.value.draft.targetClimbName)
        assertTrue(model.currentState.projects.single().selected)
        checkTexts(model.currentState)
        model.toggleProject("A")
        assertNull(p.state.value.draft.targetClimbUuid)
    }
}
