package com.cruxcoach.app.nutrition

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.app.athlete.ClimbingDaysReader
import com.cruxcoach.app.athlete.ExerciseCatalogStore
import com.cruxcoach.app.athlete.AthleteTestKit
import com.cruxcoach.app.ui.NutritionScreenModel
import com.cruxcoach.athlete.logic.BuiltinRoutines
import com.cruxcoach.athlete.logic.DayLoad
import com.cruxcoach.athlete.logic.PlannedTraining
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import com.cruxcoach.athlete.model.ClimbIntensity
import com.cruxcoach.athlete.model.ClimbingDayEntry
import com.cruxcoach.athlete.model.ClimbingDayKind
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.PLAN_BOARD
import com.cruxcoach.athlete.model.UnitSystem
import com.cruxcoach.data.repository.BodyStatRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The day view against the ported presenter and facade, with Android's
 * assertions from NutritionAlwaysOnTest, FoodEditAndStorageTest,
 * PostTrainingMealTest, RecentFoodsTest, UsUnitsTest and PlannedDayTargetsTest.
 * Texts are rendered from the generated iOS strings the app ships.
 */
class FuelDayTest {
    private val kits = mutableListOf<NutritionTestKit>()
    private val screens = mutableListOf<NutritionScreenModel>()

    private fun kit(products: String = "", region: String? = null) = NutritionTestKit(products, region = region).also { kits += it }
    private fun NutritionTestKit.open() = screen().also { screens += it }

    @AfterTest
    fun tearDown() {
        screens.forEach { it.close() }
        kits.forEach { it.close() }
    }

    private val NutritionScreenModel.state get() = presenter.state.value

    // ── NutritionAlwaysOnTest ────────────────────────────────────────

    @Test
    fun `fresh profile gets the food log with a one-time intro card`() = runBlocking<Unit> {
        val k = kit()
        assertFalse(k.db { k.repo.profile().fuelEnabled })
        assertFalse(k.db { k.repo.profile().fuelIntroAccepted })
        val screen = k.open()
        screen.await { !it.isLoading && it.showIntro }
        screen.acceptIntro()
        k.awaitDb { k.repo.profile().fuelIntroAccepted }
        val after = screen.await { !it.showIntro }
        // Once the intro card is gone, its full explanation stays one tap away in the app bar.
        assertTrue(after.showGuideButton)
    }

    @Test
    fun `logged day shows meal subtotals and the day total against targets`() = runBlocking<Unit> {
        val k = kit()
        val today = k.service.today().toString()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true) }
            k.repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 70.0, "kg", 1))
            k.repo.saveFoodLog(FoodLogEntry("a", today, 1, Meal.LUNCH, name = "Pasta", amountG = 250.0, proteinG = 12.0, carbsG = 70.0, fatG = 2.0))
            k.repo.saveFoodLog(FoodLogEntry("b", today, 2, Meal.LUNCH, name = "Tomato sauce", amountG = 150.0, proteinG = 3.0, carbsG = 10.0, fatG = 8.0))
        }
        val screen = k.open()
        val ui = screen.await { !it.isLoading && it.meals.any { m -> m.entries.size == 2 } && !it.needsWeight }
        val rings = ui.rings.associateBy { it.code }
        // 70 kg × 1.6 g/kg protein = 112 g; 15 g logged.
        // Four rings on a narrow screen: the unit sits in the line under the ring.
        assertEquals("of 112", rings.getValue("protein").compactTargetText.text)
        assertEquals("97 g to go", rings.getValue("protein").belowText.text)
        // Fat has its own ring: without a height, 1 g per kg = 70 g; 10 g logged.
        assertEquals("of 70", rings.getValue("fat").compactTargetText.text)
        assertEquals("60 g to go", rings.getValue("fat").belowText.text)
        // Order: energy, carbohydrates, protein, fat (owner 2026-10-10).
        assertEquals(listOf("energy", "carbs", "protein", "fat"), ui.rings.map { it.code })
        // A ring explains its own target when tapped; the fat share is in the fat explanation.
        assertTrue(rings.getValue("fat").explanation.any { "of your energy comes from fat" in it.text })
        // No kcal logged: calories are on by default and estimated from the macros (4/4/9).
        assertEquals("≈470", rings.getValue("energy").value.text)
        assertEquals("P 15 · C 80 · F 10 g · ≈ 470 kcal", ui.meals.first { it.code == "lunch" }.totals.text)

        // The day before is "Yesterday", not a bare date (device test 2026-10-09).
        screen.previousDay()
        val yesterday = screen.await { it.isYesterday }
        assertEquals(k.service.today().minus(DatePeriod(days = 1)).toString(), yesterday.dayIso)
        assertFalse(yesterday.isToday)
    }

    // ── FoodEditAndStorageTest ───────────────────────────────────────

    @Test
    fun `a logged food gets a new amount and meal, a quick entry new values`() = runBlocking<Unit> {
        val k = kit()
        val day = k.service.today().toString()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true) }
            k.repo.saveFoodItem(FoodItem(id = "oats", name = "Haferflocken", kcalPer100 = 370.0, proteinPer100 = 13.0,
                carbsPer100 = 59.0, fatPer100 = 7.0, updatedAt = 1))
            k.repo.saveFoodLog(FoodLogEntry(id = "e1", day = day, loggedAt = 1, meal = Meal.BREAKFAST, foodItemId = "oats",
                name = "Haferflocken", amountG = 100.0, kcal = 370.0, proteinG = 13.0, carbsG = 59.0, fatG = 7.0))
            k.repo.saveFoodLog(FoodLogEntry(id = "e2", day = day, loggedAt = 2, meal = Meal.SNACK, name = "Riegel", portions = 1.0,
                proteinG = 10.0))
        }
        val screen = k.open()
        val ui = screen.await { s -> s.meals.sumOf { it.entries.size } == 2 && s.meals.flatMap { it.entries }.any { it.editsByAmount } }

        assertTrue(ui.meals.flatMap { it.entries }.first { it.id == "e1" }.editsByAmount)
        val amount = assertNotNull(screen.editForm("e1"))
        assertEquals("100", amount.current.amountText)
        assertTrue(amount.setAmount("150").valid)
        assertTrue(amount.confirm())
        k.awaitDb { k.repo.foodLog(day).first { it.id == "e1" }.amountG == 150.0 }
        val oats = k.db { k.repo.foodLog(day).first { it.id == "e1" } }
        assertEquals(19.5, oats.proteinG!!, 0.001)
        assertEquals(Meal.BREAKFAST, oats.meal)

        assertNull(screen.editForm("e2"))
        val quick = assertNotNull(screen.quickEditForm("e2"))
        assertEquals("10", quick.current.protein)
        quick.setProtein("12")
        assertTrue(quick.save("Quick entry"))
        k.awaitDb { k.repo.foodLog(day).first { it.id == "e2" }.proteinG == 12.0 }
        assertEquals("Riegel", k.db { k.repo.foodLog(day).first { it.id == "e2" }.name })
        assertEquals(2, k.db { k.repo.foodLog(day).size })
    }

    @Test
    fun `the product database is prepared ahead and can be removed`() = runBlocking<Unit> {
        val k = kit(products = "4025500000001\tSkyr Natur\t\tMilbona\t63\t11\t4\t0.2\t150\t1\t1 Becher (150 g)\n")
        val screen = k.open()
        // Opening nutrition prepares the database in the background.
        kotlinx.coroutines.withTimeout(com.cruxcoach.app.logbook.CI_WAIT_MS) { k.store.state.first { it is OffProductStore.State.Ready } }
        assertEquals("1 Becher (150 g)", k.store.byBarcode("4025500000001")?.servingLabel)
        screen.refreshProductBytes()
        assertTrue(screen.await { it.settings.productBytes > 0 }.settings.productBytes > 0)
        screen.removeProducts()
        screen.await { it.settings.productBytes == 0L }
        assertEquals(OffProductStore.State.Missing, k.store.state.value)
        assertTrue(!java.io.File(k.store.databasePath).exists())
    }

    // ── PostTrainingMealTest ─────────────────────────────────────────

    @Test
    fun `the meal follows the time of day except within two hours after a training`() {
        val now = 1_800_000_000_000L
        val hour = 60 * 60 * 1000L
        assertEquals(Meal.POST_TRAINING, FuelRules.mealFor(now, 19, now - hour / 2))
        assertEquals(Meal.POST_TRAINING, FuelRules.mealFor(now, 19, now - 2 * hour))
        assertEquals(Meal.DINNER, FuelRules.mealFor(now, 19, now - 2 * hour - 1))
        assertEquals(Meal.BREAKFAST, FuelRules.mealFor(now, 7, null))
        // A clock that jumped back is no "after training".
        assertEquals(Meal.LUNCH, FuelRules.mealFor(now, 12, now + hour))
    }

    @Test
    fun `the add button starts in after training right after a finished training`() = runBlocking<Unit> {
        val k = kit()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true) }
            val id = k.service.startWorkout(BuiltinRoutines.byKey(BuiltinRoutines.CORE_CLIMBER), null)
            k.repo.setsFor(id).take(1).forEach { k.service.completeSet(it, startRest = false) }
            k.service.finishWorkout(id, 6, null)
        }
        val screen = k.open()
        screen.await { !it.isLoading }
        assertNotNull(screen.state.lastTrainingEndedAt)
        assertEquals("postTraining", screen.mealNowCode())
    }

    // ── RecentFoodsTest ──────────────────────────────────────────────

    @Test
    fun `a breakfast of the last days is logged again with one tap, a food starts at its last amount`() = runBlocking<Unit> {
        val k = kit()
        val today = k.service.today()
        val yesterday = today.minus(DatePeriod(days = 1)).toString()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true) }
            k.repo.saveFoodItem(FoodItem(id = "oats", name = "Haferflocken", kcalPer100 = 370.0, proteinPer100 = 13.0,
                carbsPer100 = 59.0, fatPer100 = 7.0, updatedAt = 1))
            k.repo.saveFoodLog(FoodLogEntry(id = "y1", day = yesterday, loggedAt = 1, meal = Meal.BREAKFAST, foodItemId = "oats",
                name = "Haferflocken", amountG = 80.0, kcal = 296.0, proteinG = 10.4, carbsG = 47.2, fatG = 5.6))
            // A quick entry never became one of "my foods" – it comes back all the same.
            k.repo.saveFoodLog(FoodLogEntry(id = "y2", day = yesterday, loggedAt = 2, meal = Meal.BREAKFAST, name = "Kaffee mit Milch",
                portions = 1.0, kcal = 40.0, proteinG = 2.0))
        }
        val screen = k.open()
        screen.beginSearch("log", "breakfast", null)
        screen.await { it.search.recent.size == 2 }
        screen.relog("y2")
        val day = today.toString()
        k.awaitDb { k.repo.foodLog(day).any { it.name == "Kaffee mit Milch" } }
        val coffee = k.db { k.repo.foodLog(day).single() }
        assertEquals(Meal.BREAKFAST, coffee.meal)
        assertEquals(40.0, coffee.kcal!!, 0.001)
        // Logged today: it leaves the list for breakfast; the oats stay, and their dialog starts at 80 g.
        screen.await { it.search.recent.map { r -> r.title } == listOf("Haferflocken") }
        assertEquals(80.0, screen.state.lastByFood["oats"]?.amountG!!, 0.001)
        // Logging again can be taken back.
        assertEquals("Logged: Kaffee mit Milch", screen.currentState.undo.message.text)
        val amount = assertNotNull(screen.amountForm("oats"))
        assertEquals("80", amount.current.amountText)
        assertTrue(amount.confirm())
        k.awaitDb { k.repo.foodLog(day).any { it.foodItemId == "oats" } }
        assertEquals(80.0, k.db { k.repo.foodLog(day).first { it.foodItemId == "oats" }.amountG!! }, 0.001)
    }

    @Test
    fun `logging again can be taken back and the screen publishes its state`() = runBlocking<Unit> {
        val k = kit()
        val today = k.service.today()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true) }
            k.repo.saveFoodLog(FoodLogEntry(id = "y", day = today.minus(DatePeriod(days = 1)).toString(), loggedAt = 1,
                meal = Meal.DINNER, name = "Reis", amountG = 200.0, carbsG = 56.0))
        }
        val screen = k.open()
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        val subscription = screen.watch { seen += it.search.mealCode }
        screen.beginSearch("log", "dinner", null)
        screen.await { it.search.recent.any { r -> r.ref == "y" } }
        screen.relog("y")
        k.awaitDb { k.repo.foodLog(today.toString()).size == 1 }
        assertEquals(Meal.DINNER, k.db { k.repo.foodLog(today.toString()).single().meal })
        screen.undo()
        k.awaitDb { k.repo.foodLog(today.toString()).isEmpty() }
        screen.setSearchMeal("snack")
        screen.await { it.search.mealCode == "snack" }
        kotlinx.coroutines.withTimeout(com.cruxcoach.app.logbook.CI_WAIT_MS) { while ("snack" !in seen) kotlinx.coroutines.delay(10) }
        subscription.cancel()
        // An unknown meal code changes nothing.
        screen.setSearchMeal("brunch")
        assertEquals("snack", screen.currentState.search.mealCode)
    }

    @Test
    fun `deleting an entry and removing a glass can be undone`() = runBlocking<Unit> {
        val k = kit()
        val day = k.service.today().toString()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true) }
            k.repo.saveFoodLog(FoodLogEntry(id = "e1", day = day, loggedAt = 1, meal = Meal.LUNCH, name = "Reis", carbsG = 50.0))
        }
        val screen = k.open()
        screen.await { s -> s.meals.any { it.entries.isNotEmpty() } }
        screen.deleteEntry("e1")
        k.awaitDb { k.repo.foodLog(day).isEmpty() }
        assertEquals("Entry deleted", screen.currentState.undo.message.text)
        screen.undo()
        k.awaitDb { k.repo.foodLog(day).singleOrNull()?.id == "e1" }
        assertFalse(screen.currentState.undo.visible)

        screen.addGlass()
        k.awaitDb { k.repo.hydration(day).sumOf { it.ml } == 250 }
        screen.await { it.water.full == 1 }
        screen.removeLastGlass()
        k.awaitDb { k.repo.hydration(day).isEmpty() }
        screen.undo()
        k.awaitDb { k.repo.hydration(day).sumOf { it.ml } == 250 }
    }

    @Test
    fun `same as yesterday copies the previous day after asking`() = runBlocking<Unit> {
        val k = kit()
        val today = k.service.today()
        k.db {
            k.repo.updateProfile { it.copy(fuelIntroAccepted = true) }
            listOf("a", "b").forEachIndexed { i, id ->
                k.repo.saveFoodLog(FoodLogEntry(id, today.minus(DatePeriod(days = 1)).toString(), i.toLong(), Meal.DINNER, name = "Food $id", kcal = 100.0))
            }
        }
        val screen = k.open()
        val ui = screen.await { it.previousDayCount == 2 }
        assertTrue(ui.showRepeatCard)
        assertEquals("Take over yesterday's 2 entries with one tap.", ui.repeatCardText.text)
        assertEquals("2 entries will be copied.", ui.repeatConfirmText.text)
        screen.repeatPreviousDay()
        k.awaitDb { k.repo.foodLog(today.toString()).size == 2 }
        val after = screen.await { it.showRepeatLink }
        assertFalse(after.showRepeatCard)
    }

    // ── UsUnitsTest ──────────────────────────────────────────────────

    @Test
    fun `a first start in the US picks US units, an existing profile keeps its own`() = runBlocking<Unit> {
        val k = kit(region = "US")
        assertEquals(UnitSystem.IMPERIAL, k.db { k.repo.profile().units })
        assertEquals(2.5, k.db { k.repo.profile().smallestIncrementKg } * Units.LB_PER_KG, 1e-9)

        k.db { k.repo.updateProfile { Units.withUnits(it, UnitSystem.METRIC) } }
        val again = AthleteService(
            repoLazy = { k.repo },
            catalogStore = ExerciseCatalogStore({ AthleteTestKit.catalogJson }, { k.repo }, Dispatchers.Unconfined),
            climbingDays = ClimbingDaysReader(k.athlete.secureDb),
            bodyStatRepository = BodyStatRepositoryImpl(k.athlete.secureDb),
            sessionManager = k.athlete.restTimer,
            io = Dispatchers.Unconfined,
        ).apply { systemRegion = { "US" } }
        k.db { again.ensureReady() }
        assertEquals(UnitSystem.METRIC, k.db { k.repo.profile().units })
    }

    @Test
    fun `the units setting switches the whole profile`() = runBlocking<Unit> {
        val k = kit()
        k.db { k.repo.updateProfile { Units.withUnits(it, UnitSystem.METRIC) } }
        val screen = k.open()
        screen.await { !it.isLoading && it.settings.unitsCode == "metric" }
        screen.setUnits("us")
        k.awaitDb { k.repo.profile().units == UnitSystem.IMPERIAL }
        assertEquals(2.5, k.db { k.repo.profile().smallestIncrementKg } * Units.LB_PER_KG, 1e-9)
        screen.await { it.unitsCode == "us" && it.massUnit == "lb" }
    }

    @Test
    fun `US units show water in fl oz and log a drink by cups`() = runBlocking<Unit> {
        val k = kit()
        k.db {
            k.repo.updateProfile { Units.withUnits(it.copy(fuelIntroAccepted = true), UnitSystem.IMPERIAL) }
            k.repo.saveFoodItem(FoodItem(id = "juice", name = "Orange juice", kcalPer100 = 45.0, proteinPer100 = 0.7,
                carbsPer100 = 10.0, fatPer100 = 0.2, updatedAt = 1))
        }
        val screen = k.open()
        // A US cup of water: 8 fl oz, stored as 237 ml.
        val ui = screen.await { !it.isLoading && it.unitsCode == "us" }
        assertEquals(237, ui.water.glassMl)
        assertEquals("One glass = 8 fl oz", ui.water.hint.text)
        screen.addGlass()
        val day = k.service.today().toString()
        k.awaitDb { k.repo.hydration(day).isNotEmpty() }
        assertEquals(237, k.db { k.repo.hydration(day).single().ml })

        screen.beginSearch("log", "snack", null)
        screen.await { it.search.own.any { r -> r.ref == "juice" } }
        val amount = assertNotNull(screen.amountForm("juice"))
        // A drink offers fl oz and cups; one and a half cups are 355 ml.
        assertEquals(listOf("floz", "cup"), amount.current.units.map { it.code })
        amount.switchUnit("cup")
        val form = amount.setAmount("1.5")
        // The dialog shows what that amount brings before it is logged.
        assertTrue("160 kcal" in form.preview.text, form.preview.text)
        assertTrue(amount.confirm())

        k.awaitDb { k.repo.foodLog(day).isNotEmpty() }
        val entry = k.db { k.repo.foodLog(day).single() }
        assertEquals(354.9, entry.amountG!!, 0.1)
        assertEquals(45.0 * 3.549, entry.kcal!!, 0.1)
        val shown = screen.await { s -> s.meals.flatMap { it.entries }.isNotEmpty() }
        assertTrue("12 fl oz" in shown.meals.flatMap { it.entries }.single().subtitle.text)
    }

    // ── PlannedDayTargetsTest ────────────────────────────────────────

    private suspend fun NutritionTestKit.body() = db {
        val today = service.today().toString()
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.WEIGHT.key, 70.0, "kg", 1))
        repo.saveMeasurement(BodyMeasurement(today, BodyMetric.HEIGHT.key, 175.0, "cm", 1))
    }

    @Test
    fun `a planned board day sets today's targets before anything is logged and says so`() = runBlocking<Unit> {
        val k = kit()
        val today = k.service.today()
        k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true, weekPlan = mapOf(today.dayOfWeek.isoDayNumber to PLAN_BOARD)) } }
        k.body()
        val screen = k.open()
        screen.await { screen.state.planned != null && screen.state.targets != null }
        val s = screen.state
        // Before Today has computed its board plan: the week plan's board day, 60 min hard.
        assertEquals(PlannedTraining.Kind.BOARD, s.planned!!.kind)
        assertEquals(60, s.planned!!.minutes)
        assertEquals(DayLoad.HARD, s.dayLoad)
        assertEquals(420, s.targets!!.carbsG)
        // The goal sheet plans from the same today as the rings.
        assertEquals(s.need, s.weekNeeds.last())
        // The energy card stays on what was logged: today is no training day there.
        assertNull(k.db { k.service.activities(days = 1)[today.toString()] })
        val ui = screen.currentState
        assertEquals("Hard training day (planned: board 60 min)", ui.dayLoad.text)
        assertTrue(ui.rings.first { it.code == "carbs" }.explanation.any { it.text.startsWith("Planned today: board 60 min.") })
        // The energy sheet's training line says it is the planned training, not one already done.
        assertTrue(ui.energySheet.hasPlannedTraining)
        assertTrue(ui.energySheet.plannedTraining.text.startsWith("Planned, not done yet: board 60 min."))

        // Logging the climbing ends the plan: the real day counts.
        k.db { k.service.saveClimbingDay(ClimbingDayEntry("cd1", today.toString(), ClimbingDayKind.GYM_BOULDER, 30, ClimbIntensity.LIGHT)) }
        screen.await { screen.state.planned == null && screen.state.dayLoad == DayLoad.LIGHT }
        assertEquals(280, screen.state.targets!!.carbsG)
    }

    @Test
    fun `the day before a planned board day the carbohydrate info says so, the target stays`() = runBlocking<Unit> {
        val k = kit()
        val today = k.service.today()
        val tomorrow = (today.dayOfWeek.isoDayNumber % 7) + 1
        k.db { k.repo.updateProfile { it.copy(fuelIntroAccepted = true, weekPlan = mapOf(tomorrow to PLAN_BOARD)) } }
        k.body()
        val screen = k.open()
        screen.await { screen.state.tomorrowHard && screen.state.targets != null }
        // Today is a rest day and keeps its target.
        assertEquals(DayLoad.REST, screen.state.dayLoad)
        assertEquals(210, screen.state.targets!!.carbsG)
        assertTrue(screen.currentState.rings.first { it.code == "carbs" }.explanation
            .any { it.text == "Hard day tomorrow: a carb-rich dinner tonight helps." })
        // Only today looks at tomorrow.
        screen.previousDay()
        screen.await { screen.state.day == today.minus(DatePeriod(days = 1)) }
        assertEquals(false, screen.state.tomorrowHard)
    }
}
