package com.cruxcoach.app.progress

import com.cruxcoach.app.training.TrTextCheck
import com.cruxcoach.app.training.TrainingKit
import com.cruxcoach.app.ui.AthleteSettingsScreenModel
import com.cruxcoach.app.ui.AthleteSettingsScreenState
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.logic.PreferenceLearning
import com.cruxcoach.athlete.logic.TrainingPlaces
import com.cruxcoach.athlete.model.PlaceKind
import com.cruxcoach.athlete.model.Sex
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Training settings on iOS run Android's AthleteSettingsViewModel (ported) against a real database. */
class AthleteSettingsPresenterTest {
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

    private fun screen(): Pair<AthleteSettingsPresenter, AthleteSettingsScreenModel> {
        val p = AthleteSettingsPresenter(service, kit.scope(), kit.dispatcher)
        kit.until("loaded") { p.state.value.loaded }
        return p to AthleteSettingsScreenModel(p, "en")
    }

    private fun checkTexts(s: AthleteSettingsScreenState) {
        s.rows.forEach { TrTextCheck.check(it.title, "row ${it.code}"); TrTextCheck.check(it.summary, "summary ${it.code}") }
        listOf(s.coachGoal, s.weeklyGoalText, s.weightText, s.learnedMinutesText).forEach { TrTextCheck.check(it, "page") }
        (s.sexes + s.placeKinds + s.increments).forEach { TrTextCheck.check(it.label, "choice ${it.code}") }
        s.places.forEach { TrTextCheck.check(it.label, "place") }
        s.learned.forEach { TrTextCheck.check(it.title, "learned ${it.kind}"); TrTextCheck.check(it.detail, "learned detail ${it.kind}") }
        assertTrue(TrTextCheck.checkAll(s) > 0)
    }

    @Test
    fun `the overview lists every page with its value and says which are not on iPhone`() {
        val (_, model) = screen()
        val s = model.currentState
        assertEquals(listOf("profile", "about", "equipment", "nutrition", "reminders", "timer", "display", "connections",
            "climbingDays", "excluded"), s.rows.map { it.code })
        assertEquals(setOf("nutrition", "reminders"), s.rows.filter { !it.available }.map { it.code }.toSet())
        assertEquals("tru_set_all_off", s.rows.single { it.code == "reminders" }.summary.key)
        assertEquals("trlr_summary_nothing", s.rows.single { it.code == "excluded" }.summary.args.single().nested!!.key)
        assertTrue(s.learnedEmpty)
        checkTexts(s)
        assertEquals(0, model.mapFailures)
    }

    @Test
    fun `the weekly goal is one number for streak and coach`() {
        val (p, model) = screen()
        model.setWeeklyGoal(9)
        kit.until("seven") { p.state.value.profile.weeklyGoal == 7 }
        assertEquals(7, kit.db { repo.profile().coach.trainingDaysPerWeek })
        model.setWeeklyGoal(0)
        kit.until("one") { p.state.value.profile.weeklyGoal == 1 }
        assertEquals("tra_weekly_goal", model.currentState.weeklyGoalText.key)
    }

    @Test
    fun `about you checks the year of birth and the height`() {
        val (p, model) = screen()
        assertTrue(model.setBirthYear("1990", 2026))
        kit.until("year") { p.state.value.profile.birthYear == 1990 }
        assertFalse(model.setBirthYear("2019", 2026))
        assertFalse(model.setBirthYear("1925", 2026))
        assertFalse(model.setBirthYear("19x0", 2026))
        assertTrue(model.setBirthYear("  ", 2026))
        kit.until("cleared") { p.state.value.profile.birthYear == null }
        assertFalse(model.setHeight(50.0))
        assertTrue(model.setHeight(181.0))
        kit.until("height") { p.state.value.heightCm == 181.0 }
        assertTrue(model.currentState.hasHeight)
        model.chooseSex("FEMALE")
        kit.until("sex") { p.state.value.profile.sex == Sex.FEMALE }
        model.chooseSex("FEMALE")
        kit.until("cleared sex") { p.state.value.profile.sex == null }
        assertFalse(model.logWeight(500.0))
        checkTexts(model.currentState)
    }

    @Test
    fun `switches and units change the profile`() {
        val (p, model) = screen()
        model.setSwitch("voice", true)
        model.setSwitch("autoRest", false)
        model.setSwitch("hideNumbers", true)
        model.setSwitch("checkin", false)
        kit.until("switched") { p.state.value.profile.let { it.timerVoice && !it.autoRestTimer && it.hideBodyNumbers && !it.checkinEnabled } }
        assertEquals("trt_numbers_hidden_short", model.currentState.weightText.key)
        model.setUnits("imperial")
        kit.until("imperial") { p.state.value.profile.units == UnitSystem.IMPERIAL }
        val s = model.currentState
        assertEquals("imperial", s.unitsCode)
        assertEquals(3, s.increments.size)
        model.setIncrement(s.increments.last().code)
        kit.until("5 lb") { kotlin.math.abs(p.state.value.profile.smallestIncrementKg - 5.0 / 2.2046226218) < 1e-6 }
        assertTrue(model.currentState.increments.last().selected)
        model.setUnits("nonsense")
        checkTexts(model.currentState)
    }

    @Test
    fun `learned level steps and exclusions can be forgotten`() {
        kit.db {
            repo.updateProfile { it.copy(keptLevels = mapOf("FINGER_STRENGTH" to -1), excludedExercises = setOf("push.push_up")) }
        }
        val (p, model) = screen()
        kit.until("learned") { p.state.value.learned.levels.isNotEmpty() }
        val s = model.currentState
        assertFalse(s.learnedEmpty)
        val level = s.learned.single { it.kind == "level" }
        assertEquals("FINGER_STRENGTH", level.key)
        assertEquals("trlr_level_easier", level.detail.key)
        assertEquals(listOf("push.push_up"), s.excluded.map { it.slug })
        checkTexts(s)
        model.forget("level", "FINGER_STRENGTH")
        kit.until("forgotten") { repo.profile().keptLevels.isEmpty() }
        assertTrue(kit.db { repo.profile().learningResets.containsKey(PreferenceLearning.levelKey("FINGER_STRENGTH")) })
        model.allow("push.push_up")
        kit.until("allowed") { repo.profile().excludedExercises.isEmpty() }
        model.forgetAll()
        kit.until("all") { repo.profile().learningResets.containsKey(PreferenceLearning.RESET_ALL) }
        kit.until("nothing") { p.state.value.learned.isEmpty }
    }

    @Test
    fun `places are added from a template, made default, equipped and removed`() {
        val (p, model) = screen()
        model.addPlace("GYM")
        kit.until("gym") { TrainingPlaces.of(p.state.value.profile).any { it.kind == PlaceKind.GYM } }
        val gym = model.currentState.places.single { it.kindCode == "GYM" }
        assertTrue("CAMPUS_BOARD" in gym.equipmentCodes)
        assertFalse("MAT" in gym.equipmentCodes, "the always-there items are not offered")
        model.makeDefaultPlace(gym.id)
        kit.until("default") { TrainingPlaces.default(p.state.value.profile)?.id == gym.id }
        model.savePlaceEquipment(gym.id, listOf("HANGBOARD"))
        kit.until("equipped") { TrainingPlaces.of(p.state.value.profile).single { it.id == gym.id }.equipment ==
            setOf(EquipmentV2.HANGBOARD, EquipmentV2.NONE, EquipmentV2.MAT) }
        model.renamePlace(gym.id, "  Boulder hall  ")
        kit.until("renamed") { TrainingPlaces.of(p.state.value.profile).single { it.id == gym.id }.name == "Boulder hall" }
        model.removePlace(gym.id)
        kit.until("removed") { TrainingPlaces.of(p.state.value.profile).none { it.id == gym.id } }
        model.addPlace("NOWHERE")
        assertNull(model.currentState.places.firstOrNull { it.kindCode == "NOWHERE" })
        checkTexts(model.currentState)
    }
}
