package com.cruxcoach.app.nutrition

import com.cruxcoach.app.logbook.CI_WAIT_MS
import com.cruxcoach.app.ui.MealTextScreenModel
import com.cruxcoach.app.ui.MealTextUi
import com.cruxcoach.app.ui.NutritionScreenModel
import com.cruxcoach.app.ui.RecipeForm
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.logic.Units
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Logging without a photo (FEAT-069): the BLS, USDA and product search of the
 * add sheet, a typed meal through the rule parser, and recipes – ports of
 * Android's FoodSearchAndTextTest and RecipeFlowTest, assertions unchanged.
 */
class FoodSearchAndTextTest {
    private val kits = mutableListOf<NutritionTestKit>()
    private val closers = mutableListOf<() -> Unit>()

    private fun kit(products: String = "") = NutritionTestKit(products).also { kits += it }
    private fun NutritionTestKit.open(): NutritionScreenModel {
        val screen = screen()
        closers += { screen.close() }
        runBlocking { db { repo.updateProfile { it.copy(fuelIntroAccepted = true) } } }
        return screen
    }

    @AfterTest
    fun tearDown() {
        closers.forEach { it() }
        kits.forEach { it.close() }
    }

    @Test
    fun `search finds a BLS food and logs it by grams`() = runBlocking<Unit> {
        val k = kit()
        val screen = k.open()
        screen.beginSearch("log", "snack", null)
        screen.setQuery("haferflocken")
        screen.await { s -> s.search.bls.any { it.ref == "bls:C133000" } }
        val amount = assertNotNull(screen.amountForm("bls:C133000"))
        amount.setAmount("80")
        assertTrue(amount.confirm())

        val day = k.service.today().toString()
        k.awaitDb { k.repo.foodLog(day).isNotEmpty() }
        val entry = k.db { k.repo.foodLog(day).single() }
        assertEquals("bls:C133000", entry.foodItemId)
        assertEquals(80.0, entry.amountG!!, 0.0)
        // BLS 4.0 oat flakes: 13.22 g protein per 100 g.
        assertEquals(13.22 * 0.8, entry.proteinG!!, 0.01)
        // The picked food is now one of "my foods".
        assertEquals("bls", k.db { k.repo.foodItem("bls:C133000")!!.source })
    }

    @Test
    fun `product search finds a packaged product and logs one serving`() = runBlocking<Unit> {
        val k = kit(products = "# version: t\n4025500000001\tSkyr Natur\t\tMilbona\t63\t11\t4\t0.2\t150\t1\n")
        val screen = k.open()
        screen.beginSearch("log", "snack", null)
        screen.setQuery("skyr")
        screen.await { s -> s.search.products.any { it.ref == "off:4025500000001" } }
        val amount = assertNotNull(screen.amountForm("off:4025500000001"))
        // A product with a serving size starts on "1 portion".
        assertFalse(amount.current.useGrams)
        assertTrue(amount.confirm())

        val day = k.service.today().toString()
        k.awaitDb { k.repo.foodLog(day).isNotEmpty() }
        val entry = k.db { k.repo.foodLog(day).single() }
        assertEquals("off:4025500000001", entry.foodItemId)
        assertEquals(150.0, entry.amountG!!, 0.0)
        assertEquals(16.5, entry.proteinG!!, 0.01)
        assertEquals("4025500000001", k.db { k.repo.foodItem("off:4025500000001")!!.barcode })
    }

    @Test
    fun `a scanned barcode finds own foods first, then products, else offers to create one`() = runBlocking<Unit> {
        val k = kit(products = "# version: t\n4025500000001\tSkyr Natur\t\tMilbona\t63\t11\t4\t0.2\t150\t1\n")
        k.db { k.repo.saveFoodItem(FoodItem(id = "mine", name = "Mein Riegel", barcode = "0012345678905", proteinPer100 = 20.0, updatedAt = 1)) }
        val screen = k.open()
        screen.await { s -> !s.isLoading }
        // UPC-A as scanned, stored as EAN-13 with the leading zero.
        screen.lookupBarcode("012345678905")
        assertEquals("mine", screen.await { it.scan.phase == "found" }.scan.ref)
        screen.consumeScan()
        screen.lookupBarcode("4025500000001")
        assertEquals("off:4025500000001", screen.await { it.scan.phase == "found" }.scan.ref)
        assertEquals("Skyr Natur", assertNotNull(screen.amountForm("off:4025500000001")).current.title)
        screen.consumeScan()
        screen.lookupBarcode("4000000000000")
        assertEquals("4000000000000", screen.await { it.scan.phase == "missing" }.scan.code)
        // The miss creates an own food that carries the code.
        val create = screen.createFoodForm("4000000000000")
        create.setName("Hafermilch")
        create.setPerServing(false)
        create.setProtein("1")
        assertTrue(create.save())
        k.awaitDb { k.repo.foodItems().any { it.barcode == "4000000000000" && it.name == "Hafermilch" } }
    }

    @Test
    fun `a USDA food is logged by the cup and feeds the weekly micronutrients`() = runBlocking<Unit> {
        val k = kit()
        val screen = k.open()
        k.db { k.repo.updateProfile { Units.withUnits(it, UnitSystem.IMPERIAL) } }
        screen.await { it.unitsCode == "us" }
        screen.beginSearch("log", "snack", null)
        screen.setQuery("oats regular quick")
        screen.await { s -> s.search.usda.any { it.ref == "usda:173904" } }
        val amount = assertNotNull(screen.amountForm("usda:173904"))
        // USDA weighed a cup of oats: one portion is that cup, shown with its weight in oz.
        assertTrue("1 cup · 2.9 oz" in amount.current.serving.text, amount.current.serving.text)
        assertTrue(amount.confirm())

        val day = k.service.today().toString()
        k.awaitDb { k.repo.foodLog(day).isNotEmpty() }
        val entry = k.db { k.repo.foodLog(day).single() }
        assertEquals("usda:173904", entry.foodItemId)
        assertEquals(81.0, entry.amountG!!, 0.001)
        assertEquals("1 cup", k.db { k.repo.foodItem("usda:173904")!!.servingLabel })
        // 81 g oats carry 3.4 mg iron (4.25 mg per 100 g).
        val micros = screen.await { it.micros.visible }.micros
        assertTrue(micros.items.first { it.code == "iron" }.value.text.startsWith("3.4 / 11 mg"))
    }

    private suspend fun MealTextScreenModel.await(predicate: (MealTextUi) -> Boolean): MealTextUi = withTimeout(CI_WAIT_MS) {
        var s = currentState
        while (!predicate(s)) { delay(10); s = currentState }
        s
    }

    @Test
    fun `typed meal becomes a review list and is logged to the named meal`() = runBlocking<Unit> {
        val k = kit()
        val day = k.service.today().toString()
        val text = k.mealText(day, Meal.SNACK)
        closers += { text.close() }
        text.setText("Zum Frühstück 80 g Haferflocken und eine Banane")
        text.read()
        val review = text.await { it.phase == "review" }
        assertEquals("breakfast", review.mealCode)
        assertEquals(2, review.saveCount)
        assertEquals("Log 2 entries", review.saveLabel.text)
        text.save()
        text.await { it.phase == "saved" }

        k.awaitDb { k.repo.foodLog(day).size == 2 }
        val log = k.db { k.repo.foodLog(day) }
        assertTrue(log.all { it.meal == Meal.BREAKFAST })
        assertEquals(listOf(80.0, 120.0), log.map { it.amountG })
        assertTrue(log.all { it.foodItemId?.startsWith("bls:") == true && it.kcal != null })
    }

    @Test
    fun `a review line can take another food, none, or a food the parser missed`() = runBlocking<Unit> {
        val k = kit()
        val day = k.service.today().toString()
        val text = k.mealText(day, Meal.LUNCH)
        closers += { text.close() }
        text.setText("200 g Reis und ein Glas Wasser")
        text.read()
        val review = text.await { it.phase == "review" }
        assertEquals("lunch", review.mealCode)
        val water = review.rows.single { it.isWater }
        assertEquals("ml", water.unitLabel.text)
        val rice = review.rows.single { !it.isWater }
        text.beginChoice(rice.key)
        assertTrue(text.currentState.choices.isNotEmpty())
        text.pick("")
        assertFalse(text.await { s -> s.rows.any { !it.isWater && !it.hasChoice } }.rows.single { !it.isWater }.hasChoice)
        text.beginChoice(-2)
        text.setChoiceQuery("banane")
        val choices = text.await { it.choices.isNotEmpty() }.choices
        text.pick(choices.first().code)
        text.await { it.rows.size == 3 }
        text.setMeal("dinner")
        text.save()
        text.await { it.phase == "saved" }
        k.awaitDb { k.repo.foodLog(day).size == 2 && k.repo.hydration(day).isNotEmpty() }
        assertTrue(k.db { k.repo.foodLog(day) }.all { it.meal == Meal.DINNER })
        assertEquals(1, k.db { k.repo.foodLog(day) }.count { it.foodItemId == null && it.kcal == null })
    }

    // ── RecipeFlowTest ───────────────────────────────────────────────

    private suspend fun addIngredient(screen: NutritionScreenModel, recipe: RecipeForm, search: String?, ref: String, grams: String) {
        screen.beginSearch("ingredient", "", recipe)
        search?.let { screen.setQuery(it) }
        screen.await { s -> (s.search.bls + s.search.own).any { it.ref == ref } }
        val amount = assertNotNull(screen.ingredientForm(ref, recipe))
        assertEquals("ingredient", amount.current.mode)
        amount.setAmount(grams)
        assertTrue(amount.confirm())
    }

    @Test
    fun `a recipe from a BLS food and an own food is saved and can be changed`() = runBlocking<Unit> {
        val k = kit()
        k.db {
            k.repo.saveFoodItem(FoodItem(id = "milk", name = "Milch", kcalPer100 = 64.0, proteinPer100 = 3.3,
                carbsPer100 = 4.8, fatPer100 = 3.5, updatedAt = 1))
        }
        val screen = k.open()
        screen.await { s -> !s.isLoading }
        val recipe = screen.makeRecipeForm()
        recipe.setName("Porridge")
        addIngredient(screen, recipe, "haferflocken", "bls:C133000", "100")
        addIngredient(screen, recipe, null, "milk", "300")
        // Milk is a drink: ml like everywhere else (device test 2026-10-09 showed "250 g").
        assertEquals("300 ml", recipe.current.ingredients[1].amount.text)
        recipe.setPortions("2")
        assertTrue(recipe.current.hasSummary)
        assertTrue(recipe.save())

        k.awaitDb { k.repo.foodItems().any { it.source == Recipe.SOURCE } }
        val item = k.db { k.repo.foodItems().single { it.source == Recipe.SOURCE } }
        assertEquals("Porridge", item.name)
        assertEquals(200.0, item.servingG!!, 1e-9)
        // 13.22 g (BLS oat flakes) + 9.9 g (milk) protein in 400 g.
        assertEquals((13.22 + 9.9) / 4, item.proteinPer100!!, 1e-6)
        assertEquals(listOf("bls:C133000", "milk"), k.db { k.repo.recipe(item.id)!!.ingredients.map { it.foodItemId } })

        // Changing it keeps the id: one ingredient less, one portion.
        screen.await { screen.presenter.state.value.foods.any { it.id == item.id } }
        val loaded = CompletableDeferred<RecipeForm?>()
        screen.editRecipe(item.id) { loaded.complete(it) }
        val edit = assertNotNull(withTimeout(CI_WAIT_MS) { loaded.await() })
        assertEquals(2, edit.current.ingredients.size)
        edit.removeIngredient(1)
        edit.setPortions("1")
        assertTrue(edit.save())
        k.awaitDb { k.repo.recipe(item.id)?.ingredients?.size == 1 }
        assertEquals(100.0, k.db { k.repo.foodItem(item.id)!!.servingG!! }, 1e-9)
        assertEquals(1, k.db { k.repo.foodItems().count { it.source == Recipe.SOURCE } })
    }

    @Test
    fun `an own food can be deleted, and the ingredient picker cannot delete`() = runBlocking<Unit> {
        val k = kit()
        k.db {
            k.repo.saveFoodItem(FoodItem(id = "milk", name = "Milch", kcalPer100 = 64.0, proteinPer100 = 3.3,
                carbsPer100 = 4.8, fatPer100 = 3.5, updatedAt = 1))
            k.repo.saveFoodItem(FoodItem(id = "cheese", name = "Bergkäse", kcalPer100 = 400.0, proteinPer100 = 28.0,
                carbsPer100 = 0.0, fatPer100 = 32.0, updatedAt = 1))
        }
        val screen = k.open()
        screen.beginSearch("log", "snack", null)
        val ui = screen.await { s -> s.search.own.any { it.ref == "cheese" } }
        assertTrue(ui.search.own.first { it.ref == "cheese" }.canDelete)
        screen.deleteFood("cheese")
        k.awaitDb { k.repo.foodItems().none { it.id == "cheese" } }

        screen.beginSearch("ingredient", "", screen.makeRecipeForm())
        val picker = screen.await { s -> s.search.purpose == "ingredient" && s.search.own.any { it.ref == "milk" } }
        assertTrue(picker.search.own.none { it.canDelete })
        assertFalse(picker.search.showMeal)
    }
}
