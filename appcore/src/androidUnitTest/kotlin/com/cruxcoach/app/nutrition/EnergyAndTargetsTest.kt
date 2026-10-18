package com.cruxcoach.app.nutrition

import com.cruxcoach.app.ui.NutritionScreenModel
import com.cruxcoach.athlete.model.AgeBand
import com.cruxcoach.athlete.model.AthleteGoal
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.EverydayActivity
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Sex
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.minus
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Energy in the open and own targets: ports of Android's
 * EnergyTransparencyTest and OwnTargetsTest against the facade, assertions
 * unchanged where they are about behaviour or wording.
 */
class EnergyAndTargetsTest {
    private val kits = mutableListOf<NutritionTestKit>()
    private val screens = mutableListOf<NutritionScreenModel>()

    @AfterTest
    fun tearDown() {
        screens.forEach { it.close() }
        kits.forEach { it.close() }
    }

    private fun kit() = NutritionTestKit().also { kits += it }
    private fun NutritionTestKit.open() = screen().also { screens += it }

    private suspend fun NutritionTestKit.body(weightKg: Double, heightCm: Double? = 175.0) = db {
        val today = service.today().toString()
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, weightKg, "kg", 1))
        heightCm?.let { repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, it, "cm", 1)) }
    }

    // ── EnergyTransparencyTest ───────────────────────────────────────

    @Test
    fun `energy ring counts against the calorie target and opens the breakdown`() = runBlocking<Unit> {
        val k = kit()
        val year = k.service.today().year
        k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.MALE, birthYear = year - 30, goal = AthleteGoal.LOSE_WEIGHT, weeklyLossKg = 0.5) } }
        k.body(70.0)
        val screen = k.open()
        // Need 1649 + 659/660 everyday (rest day) = 2308/2309, minus 550 for 0.5 kg a week.
        val ui = screen.await { s -> s.rings.firstOrNull()?.hasTarget == true && s.energySheet.hasPlan }
        val energy = ui.rings.first { it.code == "energy" }
        assertTrue(energy.compactTargetText.text in setOf("of 1758", "of 1759"), energy.compactTargetText.text)
        val sheet = ui.energySheet
        assertTrue(sheet.hasNeed)
        assertEquals("−550 kcal", sheet.deficit.value.text)
        assertTrue(sheet.target.value.text in setOf("1758 kcal", "1759 kcal"))
        // Everyday life is chosen right here and changes the need.
        screen.setEverydayActivity("physical")
        k.awaitDb { k.repo.profile().everydayActivity == EverydayActivity.PHYSICAL }
    }

    @Test
    fun `a hard day while losing weight gets a smaller deficit so the rings fit, said with the numbers`() = runBlocking<Unit> {
        // Holistic audit 2026-10-17: 60 kg, 165 cm, 25 years, 0.5 kg a week, 90 minutes of hard climbing today.
        val k = kit()
        val year = k.service.today().year
        k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.FEMALE, birthYear = year - 25, goal = AthleteGoal.LOSE_WEIGHT, weeklyLossKg = 0.5) } }
        k.body(60.0, 165.0)
        k.db { k.service.saveClimbingDay(ClimbingDayEntry("cd1", k.service.today().toString(), ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.HARD)) }
        // Need 2333 kcal; the full 550 kcal deficit would leave 1783 for 96 g protein + 360 g carbohydrate + 20 % fat (2283).
        k.db {
            val activity = k.service.activities(days = 1)[k.service.today().toString()]
            val plan = k.service.weightPlan(k.repo.profile(), activity)!!
            assertEquals(2283, plan.targetKcal)
            assertEquals(50, plan.dailyDeficitKcal)
            val targets = k.service.fuelTargets(activity, k.repo.profile())!!
            assertEquals(360, targets.carbsG)
            assertTrue(4 * targets.proteinG + 4 * targets.carbsG + 9 * targets.fatG <= plan.targetKcal!!)
        }
        val screen = k.open()
        val ui = screen.await { s -> s.rings.firstOrNull()?.compactTargetText?.text == "of 2283" }
        // The carbohydrate ring says why the deficit is smaller today …
        assertTrue(ui.rings.first { it.code == "carbs" }.explanation.any { it.text.startsWith("Hard training day: deficit 50 instead of 550 kcal") })
        // … and the energy sheet too, with the pace the last seven days really add up to (6 × 500 + 50 kcal).
        assertEquals("−50 kcal", ui.energySheet.deficit.value.text)
        assertTrue(ui.energySheet.planNotes.any { it.text.startsWith("Hard training day: deficit 50 instead of 550 kcal") })
        assertTrue(ui.energySheet.planNotes.any { "about 0.4 kg instead of 0.5 kg per week" in it.text })
    }

    @Test
    fun `under 18 the energy card and the goal sheet say adult thresholds do not fit, nothing is locked`() = runBlocking<Unit> {
        val k = kit()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.FEMALE, goal = AthleteGoal.LOSE_WEIGHT, weeklyLossKg = 0.3,
                coach = it.coach.copy(ageBand = AgeBand.UNDER_16)) }
        }
        k.body(50.0, 160.0)
        val screen = k.open()
        // A gentle plan with no warning of its own: the card speaks because of the age, first.
        val card = screen.await { it.energyCard.visible }.energyCard
        assertEquals("youth", card.warnings.first().code)
        assertTrue(card.warnings.first().text.text.startsWith("Under 18 the body is still growing"))
        // The running goal can be looked at again from the card – and kept or stopped, as the athlete decides.
        assertTrue(card.canAdjustGoal)
        val goal = screen.weightGoalForm().current
        assertTrue(goal.youthWarning)
        assertTrue(goal.losing)
    }

    @Test
    fun `low carbohydrate on earlier training days is named with its numbers`() = runBlocking<Unit> {
        val k = kit()
        k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.MALE) } }
        k.body(70.0)
        val today = k.service.today()
        k.db {
            // Three earlier hard days, each with breakfast and dinner logged: 140, 150 and 151 g carbohydrate.
            listOf(140.0, 150.0, 151.0).forEachIndexed { i, carbs ->
                val day = today.minus(DatePeriod(days = i + 1)).toString()
                k.service.saveClimbingDay(ClimbingDayEntry("cd$i", day, ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.HARD))
                k.repo.saveFoodLog(FoodLogEntry("b$i", day, 1, Meal.BREAKFAST, name = "Oats", carbsG = carbs / 2))
                k.repo.saveFoodLog(FoodLogEntry("d$i", day, 2, Meal.DINNER, name = "Rice", carbsG = carbs / 2))
            }
            // Today's breakfast on a training day does not count: the day is still running.
            k.service.saveClimbingDay(ClimbingDayEntry("cd-today", today.toString(), ClimbingDayKind.GYM_BOULDER, 90, ClimbIntensity.HARD))
            k.repo.saveFoodLog(FoodLogEntry("t", today.toString(), 1, Meal.BREAKFAST, name = "Toast", carbsG = 20.0))
        }
        val screen = k.open()
        val card = screen.await { s -> s.energyCard.warnings.any { it.code == "lowCarbsOnTrainingDays" } }.energyCard
        // 441 g over 3 days at 70 kg: 2.1 g/kg against 6 g/kg on a hard day.
        assertTrue(card.warnings.first { it.code == "lowCarbsOnTrainingDays" }.text.text
            .startsWith("On 3 training days you logged 2.1 g/kg of carbohydrate on average instead of 6 g/kg"))
    }

    @Test
    fun `missing height is entered in the energy sheet`() = runBlocking<Unit> {
        val k = kit()
        k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true) } }
        k.body(70.0, heightCm = null)
        val screen = k.open()
        assertTrue(screen.await { !it.isLoading && !it.needsWeight }.energySheet.needsHeight)
        // Implausible input is not kept.
        assertFalse(screen.isHeightValid("17"))
        assertTrue(screen.isHeightValid("178"))
        assertFalse(screen.enterHeight("17"))
        assertTrue(screen.enterHeight("178"))
        k.awaitDb { k.repo.latest(BodyMetric.HEIGHT.key)?.value == 178.0 }
        // With the height the need appears, with its assumptions named and answerable in place.
        val sheet = screen.await { it.energySheet.hasNeed }.energySheet
        assertTrue(sheet.ageAssumed)
        assertTrue(sheet.sexAssumed)
        assertTrue(screen.enterBirthYear((k.service.today().year - 30).toString()))
        screen.setSex("female")
        k.awaitDb { k.repo.profile().sex == Sex.FEMALE && k.repo.profile().birthYear != null }
    }

    @Test
    fun `a low BMI does not lock losing weight, it warns with the numbers`() = runBlocking<Unit> {
        val k = kit()
        k.db { k.repo.updateProfile { it.copy(sex = Sex.MALE) } }
        k.body(55.0) // BMI 18.0 at 175 cm
        val screen = k.open()
        screen.await { !it.isLoading && !it.needsWeight }
        val form = screen.weightGoalForm()
        form.setTarget("50")
        repeat(5) { form.increasePace() } // 0.5 → 1.0 kg a week
        val ui = form.current
        assertEquals("1 kg per week", ui.pace.text)
        // 1 kg is 1.8 % of 55 kg; 50 kg at 175 cm is BMI 16.3 – both named, neither refused.
        assertEquals(setOf("fastPace", "lowTargetBmi"), ui.warnings.map { it.code }.filter { it in setOf("fastPace", "lowTargetBmi") }.toSet())
        // The pace line and the warning both say it.
        assertEquals(2, (listOf(ui.paceShare) + ui.warnings.map { it.text }).count { "1.8 % of your weight" in it.text })
        assertTrue(ui.canStart)
        assertTrue(form.start())
        k.awaitDb { k.repo.profile().goal == AthleteGoal.LOSE_WEIGHT }
        assertEquals(1.0, k.db { k.repo.profile().weeklyLossKg }, 1e-9)
        assertEquals(50.0, k.db { k.repo.profile().targetWeightKg!! }, 1e-9)
        // Switching off keeps target and pace for next time.
        screen.await { it.energySheet.goalButtonKey == "trn_edit_goal" }
        screen.weightGoalForm().stop()
        k.awaitDb { k.repo.profile().goal != AthleteGoal.LOSE_WEIGHT }
        assertEquals(50.0, k.db { k.repo.profile().targetWeightKg!! }, 1e-9)
    }

    @Test
    fun `nutrition names a fast plan on the energy card and opens the goal from it`() = runBlocking<Unit> {
        val k = kit()
        k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.FEMALE, goal = AthleteGoal.LOSE_WEIGHT, weeklyLossKg = 1.2) } }
        k.body(60.0, 165.0)
        val screen = k.open()
        val card = screen.await { it.energyCard.visible }.energyCard
        assertTrue(card.warnings.any { it.code == "fastPace" })
        assertTrue(card.warnings.any { it.code == "largeDeficit" })
        assertTrue(card.canAdjustGoal)
        val form = screen.weightGoalForm()
        // While the goal runs, a change is kept at once; "Done" only closes the sheet.
        repeat(2) { form.decreasePace() }
        k.awaitDb { abs(k.repo.profile().weeklyLossKg - 1.0) < 1e-9 }
        assertEquals(AthleteGoal.LOSE_WEIGHT, k.db { k.repo.profile().goal })
    }

    // ── OwnTargetsTest ───────────────────────────────────────────────

    private suspend fun NutritionTestKit.ownBody() {
        body(70.0, 176.0)
        db { repo.updateProfile { it.copy(fuelIntroAccepted = true, sex = Sex.MALE, birthYear = service.today().year - 30) } }
    }

    @Test
    fun `the settings set an own fat target and take it back`() = runBlocking<Unit> {
        val k = kit()
        k.ownBody()
        val screen = k.open()
        screen.await { s -> s.settings.targets.any { it.kindCode == "fat" } && !s.needsWeight }
        // "Own" starts from the recommendation, then takes a typed value.
        screen.chooseOwnTarget("fat")
        k.awaitDb { k.repo.profile().ownFatG != null }
        assertTrue(screen.setOwnTarget("fat", "60"))
        k.awaitDb { k.repo.profile().ownFatG == 60 }
        assertEquals(60, k.db { k.service.fuelTargets(null, k.repo.profile())!!.fatG })
        screen.followRecommendation("fat")
        k.awaitDb { k.repo.profile().ownFatG == null }
    }

    @Test
    fun `an own calorie target outside the plausible range is not kept and the field says why`() = runBlocking<Unit> {
        val k = kit()
        k.ownBody()
        k.db { k.repo.updateProfile { it.copy(ownKcal = 2000) } }
        val screen = k.open()
        val energy = screen.await { s -> s.settings.targets.any { it.kindCode == "energy" && it.isOwn } }
            .settings.targets.first { it.kindCode == "energy" }
        // 700 kcal is most likely a typo: not kept, and the range is named at the field – nothing else is locked.
        assertFalse(screen.isOwnTargetValid("energy", "700"))
        assertTrue(screen.isOwnTargetValid("energy", "1200"))
        assertFalse(screen.setOwnTarget("energy", "700"))
        assertTrue(energy.invalidHint.text.startsWith("Kept between 800 and 6000 kcal"))
        assertEquals(2000, k.db { k.repo.profile().ownKcal })
        // A low but plausible target is kept.
        assertTrue(screen.setOwnTarget("energy", "1200"))
        k.awaitDb { k.repo.profile().ownKcal == 1200 }
    }

    @Test
    fun `a ring offers its own target and a low calorie target is named`() = runBlocking<Unit> {
        val k = kit()
        k.ownBody()
        k.db { k.repo.updateProfile { it.copy(ownKcal = 1400) } }
        val screen = k.open()
        // The energy ring counts against the own target …
        val ui = screen.await { s -> s.rings.firstOrNull()?.compactTargetText?.text == "of 1400" }
        // … and the energy card names how far it lies under the need, without the weight-goal action.
        assertTrue(ui.energyCard.warnings.any { it.code == "largeDeficit" })
        assertFalse(ui.energyCard.canAdjustGoal)
        // The carbohydrate ring explains itself and offers an own target.
        assertEquals("carbs", ui.rings.first { it.code == "carbs" }.ownTarget.kindCode)
        screen.chooseOwnTarget("carbs")
        k.awaitDb { k.repo.profile().ownCarbsG != null }
        assertNull(k.db { k.repo.profile().ownProteinG })
    }

    @Test
    fun `an own carbohydrate target says it does not follow the training day and names today's recommendation`() = runBlocking<Unit> {
        val k = kit()
        k.ownBody()
        k.db { k.repo.updateProfile { it.copy(ownCarbsG = 300) } }
        val screen = k.open()
        // A rest day recommends 3 g per kg: 70 kg → 210 g, while the own 300 g stay on the ring.
        val ui = screen.await { s -> s.ownNotes.isNotEmpty() }
        assertEquals(1, ui.ownNotes.size)
        assertTrue("does not follow the training day (recommended today 210 g)" in ui.ownNotes.single().text)
        assertEquals("of 300", ui.rings.first { it.code == "carbs" }.compactTargetText.text)
        // The ring's own field says the same in a word.
        assertTrue(ui.rings.first { it.code == "carbs" }.ownTarget.hasOwnNote)
    }

    @Test
    fun `an own calorie target can be taken back while calories are hidden`() = runBlocking<Unit> {
        val k = kit()
        k.ownBody()
        k.db { k.repo.updateProfile { it.copy(ownKcal = 1800, showCalories = false) } }
        val screen = k.open()
        // No calorie field while they are hidden, but the target is named as still active and can be reset.
        val settings = screen.await { it.settings.ownKcalHidden }.settings
        assertTrue(settings.targets.none { it.kindCode == "energy" })
        assertTrue(screen.currentState.rings.none { it.code == "energy" })
        screen.followRecommendation("energy")
        k.awaitDb { k.repo.profile().ownKcal == null }
        screen.await { !it.settings.ownKcalHidden }
    }

    @Test
    fun `while losing weight the protein choice names the higher recommendation and goes up to 2_4 g per kg`() = runBlocking<Unit> {
        val k = kit()
        k.ownBody()
        val screen = k.open()
        val settings = screen.await { !it.isLoading }.settings
        assertEquals(1.4, settings.proteinMin, 1e-9)
        assertEquals(2.4, settings.proteinMax, 1e-9)
        // Without a weight-loss goal no deficit recommendation.
        assertFalse(settings.hasProteinLosingHint)
        k.db { k.repo.updateProfile { it.copy(goal = AthleteGoal.LOSE_WEIGHT, targetWeightKg = 66.0, weeklyLossKg = 0.3) } }
        val losing = screen.await { it.settings.hasProteinLosingHint }.settings
        assertTrue(losing.proteinLosingHint.text.startsWith("While losing weight 1.8–2.2 g per kg is recommended"))
        // The athlete's choice above the usual range is kept and counts.
        screen.setProteinPerKg(2.3)
        k.awaitDb { k.repo.profile().proteinPerKg == 2.3 }
        assertEquals(161, k.db { k.service.fuelTargets(null, k.repo.profile())!!.proteinG })
    }
}
