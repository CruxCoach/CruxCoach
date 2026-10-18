package com.cruxcoach.app.nutrition

import com.cruxcoach.app.ui.CreateFoodForm
import com.cruxcoach.app.ui.FoodAmountForm
import com.cruxcoach.app.ui.NutritionFormat
import com.cruxcoach.app.ui.QuickEntryForm
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dialogs' rules (Android `AmountDialog`, `QuickAddSheet`,
 * `CreateFoodDialog`, `formatNumber`, `inputText`) in the synchronous forms
 * Swift binds to.
 */
class FoodFormsTest {

    @Test
    fun `numbers are formatted as Android's formatNumber in either decimal separator`() {
        assertEquals("12.5", NutritionFormat.number(12.5, 1, comma = false))
        assertEquals("12,5", NutritionFormat.number(12.5, 1, comma = true))
        assertEquals("7", NutritionFormat.number(7.04, 1, comma = false))
        assertEquals("-10", NutritionFormat.number(-10.0, 1, comma = false))
        assertEquals("-0.5", NutritionFormat.number(-0.5, 1, comma = false))
        assertEquals("0.05", NutritionFormat.number(0.05, 2, comma = false))
        assertEquals(1.5, NutritionFormat.parseDecimal("1,5"))
        assertNull(NutritionFormat.parseDecimal("abc"))
        // Whole g and ml, tenths of an ounce, quarter cups.
        assertEquals("355", NutritionFormat.inputText(354.9, FuelUnits.Amount.ML, false))
        assertEquals("12", NutritionFormat.inputText(354.9, FuelUnits.Amount.FL_OZ, false))
        assertEquals("1.5", NutritionFormat.inputText(354.9, FuelUnits.Amount.CUP, false))
    }

    private val juice = FoodItem(id = "juice", name = "Orange juice", kcalPer100 = 45.0, proteinPer100 = 0.7, carbsPer100 = 10.0, fatPer100 = 0.2)

    @Test
    fun `switching units keeps the amount, not the number`() {
        var logged: Triple<Meal, Double?, Double?>? = null
        val form = FoodAmountForm(juice, Meal.SNACK, UnitSystem.IMPERIAL, false, true, null, null, false) { m, p, g -> logged = Triple(m, p, g) }
        assertTrue(form.current.useGrams)
        form.setAmount("8.1")
        // 8.1 fl oz are about 240 ml: one cup.
        assertEquals("1", form.switchUnit("cup").amountText)
        // … and back: a cup is 8 fl oz.
        assertEquals("8", form.switchUnit("floz").amountText)
        // An amount over 5 kg or not a number cannot be logged.
        assertFalse(form.setAmount("abc").valid)
        assertTrue(form.setAmount("-1").amountInvalid)
        assertFalse(form.confirm())
        form.setAmount("8.1")
        form.setMeal("breakfast")
        assertTrue(form.confirm())
        // Once only: a second tap does not log twice.
        assertFalse(form.confirm())
        assertEquals(Meal.BREAKFAST, logged!!.first)
        assertNull(logged!!.second)
        assertEquals(8.1 * FuelUnits.ML_PER_FL_OZ, logged!!.third!!, 1e-9)
    }

    @Test
    fun `a per-portion food is logged by portions only, a change starts from the entry`() {
        val bar = FoodItem(id = "bar", name = "Riegel", proteinPer100 = 10.0, source = FuelRules.SOURCE_PORTION)
        var logged: Pair<Double?, Double?>? = null
        val entry = FoodLogEntry("e", "2026-10-17", 1, Meal.LUNCH, foodItemId = "bar", name = "Riegel", portions = 2.0, proteinG = 20.0)
        val form = FoodAmountForm(bar, Meal.SNACK, UnitSystem.METRIC, false, false, entry, null, false) { _, p, g -> logged = p to g }
        val ui = form.current
        assertFalse(ui.useGrams)
        assertFalse(ui.showChoice)
        assertEquals("2", ui.portionsText)
        assertEquals("lunch", ui.mealCode)
        assertEquals("tr_action_save", ui.confirmKey)
        assertEquals(listOf("0.5", "1", "2"), ui.presets)
        // Portions are kept between 0 and 50.
        assertFalse(form.setPortions("60").valid)
        assertTrue(form.setPortions("1,5").valid)
        assertTrue(form.confirm())
        assertEquals(1.5 to null, logged)
    }

    @Test
    fun `a quick entry needs a name only when it is remembered`() {
        var saved: String? = null
        val form = QuickEntryForm(null, Meal.DINNER, false) { name, _, n, remember -> saved = "$name ${n.protein} $remember" }
        assertTrue(form.current.valid)
        assertFalse(form.setRemember(true).valid)
        assertTrue(form.setName("Shake").valid)
        assertTrue(form.setProtein("x").proteinInvalid)
        assertFalse(form.current.valid)
        form.setProtein("25")
        assertTrue(form.save("Quick entry"))
        assertEquals("Shake 25.0 true", saved)
    }

    @Test
    fun `an own food from a US label is stored per 100 g`() {
        var per100Protein: Double? = null
        var serving: Double? = null
        val form = CreateFoodForm("012345678905", UnitSystem.IMPERIAL) { _, _, n, s, _ -> per100Protein = n.protein; serving = s }
        // US labels state nutrition per serving.
        assertTrue(form.current.perServing)
        form.setName("Granola")
        form.setProtein("10")
        // Values per serving need the serving's weight.
        assertFalse(form.current.valid)
        form.setServingGrams("30")
        assertTrue(form.current.valid)
        assertEquals("012345678905", form.current.barcode)
        assertTrue(form.save())
        assertEquals(10 * 100.0 / 30, per100Protein!!, 1e-9)
        assertEquals(30.0, serving)
    }
}
