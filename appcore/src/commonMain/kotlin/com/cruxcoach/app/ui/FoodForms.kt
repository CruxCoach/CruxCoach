package com.cruxcoach.app.ui

import com.cruxcoach.app.nutrition.FuelPresenter
import com.cruxcoach.app.nutrition.FuelRules
import com.cruxcoach.app.nutrition.Nutrients
import com.cruxcoach.app.ui.NutritionTexts.amount
import com.cruxcoach.app.ui.NutritionTexts.literal
import com.cruxcoach.app.ui.NutritionTexts.macros
import com.cruxcoach.app.ui.NutritionTexts.t
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MacroTotals
import com.cruxcoach.athlete.logic.Recipe
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.UnitSystem
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/*
 * The nutrition dialogs as synchronous forms: Swift binds its fields to the
 * returned state and calls the setter on every change, so the validation and
 * the live preview are Android's, in Kotlin, and covered by JVM tests.
 * Nothing here suspends or throws.
 */

/** Android's `formatNumber` and `parseDecimal`, with the app's decimal separator. */
internal object NutritionFormat {
    fun number(value: Double, decimals: Int, comma: Boolean): String {
        val d = decimals.coerceAtLeast(0)
        val factor = 10.0.pow(d)
        val rounded = (value * factor).roundToLong() / factor
        if (abs(rounded - rounded.roundToLong()) < 1e-9) return rounded.roundToLong().toString()
        val scaled = (abs(rounded) * factor).roundToLong()
        val unit = factor.toLong()
        val frac = (scaled % unit).toString().padStart(d, '0').trimEnd('0')
        val text = (scaled / unit).toString() + (if (frac.isEmpty()) "" else (if (comma) "," else ".") + frac)
        return if (rounded < 0) "-$text" else text
    }

    fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    fun optionalAmount(text: String): Double? = parseDecimal(text)?.takeIf { it >= 0 }
    fun fieldValid(text: String) = text.isBlank() || optionalAmount(text) != null

    /** [base] grams or ml as the number to edit in [unit]: whole g/ml, tenths of an ounce, quarter cups. */
    fun inputText(base: Double, unit: FuelUnits.Amount, comma: Boolean): String {
        val value = FuelUnits.fromBase(base, unit)
        return when (unit) {
            FuelUnits.Amount.G, FuelUnits.Amount.ML -> number(value, 0, comma)
            FuelUnits.Amount.OZ, FuelUnits.Amount.FL_OZ -> number(value, 1, comma)
            FuelUnits.Amount.CUP -> number((value * 4).roundToInt() / 4.0, 2, comma)
        }
    }

    fun unitCode(unit: FuelUnits.Amount): String = when (unit) {
        FuelUnits.Amount.G -> "g"
        FuelUnits.Amount.ML -> "ml"
        FuelUnits.Amount.OZ -> "oz"
        FuelUnits.Amount.FL_OZ -> "floz"
        FuelUnits.Amount.CUP -> "cup"
    }

    fun unit(code: String): FuelUnits.Amount? = FuelUnits.Amount.entries.firstOrNull { unitCode(it) == code }

    fun mealCode(meal: Meal): String = when (meal) {
        Meal.BREAKFAST -> "breakfast"
        Meal.LUNCH -> "lunch"
        Meal.DINNER -> "dinner"
        Meal.SNACK -> "snack"
        Meal.PRE_TRAINING -> "preTraining"
        Meal.POST_TRAINING -> "postTraining"
    }

    fun meal(code: String): Meal? = Meal.entries.firstOrNull { mealCode(it) == code }
}

class AmountUnitUi(val code: String, val label: NutriText, val selected: Boolean)

class FoodAmountUi(
    val title: String,
    /** add | edit | ingredient */
    val mode: String,
    /** The grams-or-portions choice is shown at all (per-100 g foods with a portion or a second unit). */
    val showChoice: Boolean,
    val canPortion: Boolean,
    val useGrams: Boolean,
    val units: List<AmountUnitUi>,
    /** Label of the amount field: "Grams", "fl oz", "cups"… */
    val amountLabel: NutriText,
    val amountText: String,
    val amountInvalid: Boolean,
    val portionsText: String,
    val portionsInvalid: Boolean,
    /** Portion presets as typed text ("0.5", "1", "2"). */
    val presets: List<String>,
    /** "1 portion = 1 cup · 2.9 oz"; empty without a portion size. */
    val serving: NutriText,
    val hasServing: Boolean,
    /** What the amount brings, live. */
    val preview: NutriText,
    val hasPreview: Boolean,
    val mealCode: String,
    val showMeal: Boolean,
    val valid: Boolean,
    /** trf_amount_add | tr_action_save | trf_recipe_add_confirm */
    val confirmKey: String,
)

/**
 * Amount for a saved food (Android `AmountDialog`): portions always; grams
 * (or oz, ml, fl oz, cups) when the food has per-100 g values.
 */
class FoodAmountForm internal constructor(
    private val item: FoodItem,
    initialMeal: Meal,
    private val units: UnitSystem,
    private val comma: Boolean,
    private val showCalories: Boolean,
    /** A logged entry to change: its amount and meal are the starting values. */
    private val editing: FoodLogEntry?,
    /** The last time this food was logged: a new entry starts with that amount. */
    last: FoodLogEntry?,
    private val forRecipe: Boolean,
    private val onConfirm: (meal: Meal, portions: Double?, grams: Double?) -> Unit,
) {
    private val perPortion = FuelRules.isPerPortion(item)
    private val gramsPossible = !perPortion
    // Drinks are measured by volume (ml, or fl oz and cups in US units); 1 ml counts as 1 g.
    private val drink = FuelUnits.isDrink(item.id, item.name, item.servingLabel)
    private val baseUnit = FuelUnits.unitFor(units, drink)
    private val amountUnits = if (units == UnitSystem.IMPERIAL && drink) listOf(baseUnit, FuelUnits.Amount.CUP) else listOf(baseUnit)
    private val canPortion = perPortion || item.servingG != null

    private var useGrams: Boolean
    private var unit = baseUnit
    private var portions: String
    private var grams: String
    private var meal = editing?.meal ?: initialMeal
    private var done = false

    init {
        // A change starts from the entry; a new entry from the amount eaten last time.
        val start = editing ?: last
        val editedByGrams = start != null && start.portions == null && start.amountG != null
        useGrams = gramsPossible && (item.servingG == null || editedByGrams)
        portions = start?.portions?.let { NutritionFormat.number(it, 2, comma) } ?: "1"
        grams = NutritionFormat.inputText(start?.amountG?.takeIf { editedByGrams } ?: item.servingG ?: 100.0, baseUnit, comma)
    }

    private val p: Double? get() = NutritionFormat.parseDecimal(portions)?.takeIf { it > 0 && it <= 50 }
    private val g: Double? get() = NutritionFormat.parseDecimal(grams)?.let { FuelUnits.toBase(it, unit) }?.takeIf { it > 0 && it <= 5000 }
    private val valid: Boolean get() = if (useGrams) g != null else (p != null && canPortion)

    val current: FoodAmountUi get() = ui()

    fun usePortions(): FoodAmountUi { if (canPortion) useGrams = false; return ui() }

    /** Keeps the amount, not the number: 240 ml becomes 8.1 fl oz or 1 cup. */
    fun switchUnit(code: String): FoodAmountUi {
        val next = NutritionFormat.unit(code)?.takeIf { it in amountUnits } ?: return ui()
        val base = NutritionFormat.parseDecimal(grams)?.let { FuelUnits.toBase(it, unit) }
        if (base != null) grams = NutritionFormat.inputText(base, next, comma)
        unit = next
        useGrams = gramsPossible
        return ui()
    }

    fun setAmount(text: String): FoodAmountUi { grams = text; return ui() }
    fun setPortions(text: String): FoodAmountUi { portions = text; return ui() }
    fun setMeal(code: String): FoodAmountUi { NutritionFormat.meal(code)?.let { meal = it }; return ui() }

    /** Logs (or saves, or adds to the recipe); false while the amount is not valid. Once only. */
    fun confirm(): Boolean {
        if (!valid || done) return false
        done = true
        if (useGrams) onConfirm(meal, null, g) else onConfirm(meal, p, null)
        return true
    }

    private fun ui(): FoodAmountUi {
        val n = FuelRules.scale(item, p.takeUnless { useGrams }, g.takeIf { useGrams })
        val preview = n?.let {
            val energy = it.kcal?.let { k -> MacroTotals.Energy(k, estimated = false) }
                ?: if (it.protein == null && it.carbs == null && it.fat == null) null
                else MacroTotals.Energy(4 * (it.protein ?: 0.0) + 4 * (it.carbs ?: 0.0) + 9 * (it.fat ?: 0.0), estimated = true)
            macros(it.protein, it.carbs, it.fat, energy, showCalories)
        }
        val serving = item.servingG?.let { sg ->
            // The pack's own words where known ("1 cup (30 g)"), with the amount in US units.
            val amountText = amount(sg, baseUnit)
            val text = when {
                item.servingLabel == null -> amountText
                units == UnitSystem.IMPERIAL -> NutritionTexts.join(listOf(literal(item.servingLabel!!), amountText))
                else -> literal(item.servingLabel!!)
            }
            t("trf_amount_serving", NutritionTexts.msg(text))
        }
        return FoodAmountUi(
            title = item.name,
            mode = when { forRecipe -> "ingredient"; editing != null -> "edit"; else -> "add" },
            showChoice = gramsPossible && (canPortion || amountUnits.size > 1),
            canPortion = canPortion,
            useGrams = useGrams,
            units = amountUnits.map { u ->
                AmountUnitUi(NutritionFormat.unitCode(u),
                    if (u == FuelUnits.Amount.G) t("trf_amount_grams") else NutritionTexts.unitLabel(u),
                    useGrams && unit == u)
            },
            amountLabel = if (unit == FuelUnits.Amount.G) t("trf_amount_grams") else NutritionTexts.unitLabel(unit),
            amountText = grams,
            amountInvalid = grams.isNotBlank() && (NutritionFormat.parseDecimal(grams)?.let { it < 0 } ?: true),
            portionsText = portions,
            portionsInvalid = portions.isNotBlank() && (NutritionFormat.parseDecimal(portions)?.let { it < 0 } ?: true),
            presets = listOf(0.5, 1.0, 2.0).map { NutritionFormat.number(it, 1, comma) },
            serving = serving ?: NutritionTexts.empty,
            hasServing = serving != null,
            preview = preview ?: NutritionTexts.empty,
            hasPreview = preview != null,
            mealCode = NutritionFormat.mealCode(meal),
            showMeal = !forRecipe,
            valid = valid,
            confirmKey = when {
                forRecipe -> "trf_recipe_add_confirm"
                editing != null -> "tr_action_save"
                else -> "trf_amount_add"
            },
        )
    }
}

class QuickEntryUi(
    val isEditing: Boolean,
    val name: String,
    val mealCode: String,
    val protein: String,
    val carbs: String,
    val fat: String,
    val kcal: String,
    val proteinInvalid: Boolean,
    val carbsInvalid: Boolean,
    val fatInvalid: Boolean,
    val kcalInvalid: Boolean,
    val remember: Boolean,
    /** "Remember as my food" is offered for new entries only. */
    val showRemember: Boolean,
    val valid: Boolean,
)

/** Quick entry (Android `QuickAddSheet`): a name and any subset of nutrients. */
class QuickEntryForm internal constructor(
    private val editing: FoodLogEntry?,
    initialMeal: Meal,
    comma: Boolean,
    private val onSave: (name: String, meal: Meal, nutrients: Nutrients, remember: Boolean) -> Unit,
) {
    private fun text(v: Double?, comma: Boolean) = v?.let { NutritionFormat.number(it, 1, comma) }.orEmpty()
    private var name = editing?.name.orEmpty()
    private var meal = editing?.meal ?: initialMeal
    private var protein = text(editing?.proteinG, comma)
    private var carbs = text(editing?.carbsG, comma)
    private var fat = text(editing?.fatG, comma)
    private var kcal = text(editing?.kcal, comma)
    private var remember = false
    private var done = false

    private val valid: Boolean
        get() = listOf(protein, carbs, fat, kcal).all(NutritionFormat::fieldValid) && (!remember || name.isNotBlank())

    val current: QuickEntryUi get() = ui()

    fun setName(text: String): QuickEntryUi { name = text.take(120); return ui() }
    fun setMeal(code: String): QuickEntryUi { NutritionFormat.meal(code)?.let { meal = it }; return ui() }
    fun setProtein(text: String): QuickEntryUi { protein = text; return ui() }
    fun setCarbs(text: String): QuickEntryUi { carbs = text; return ui() }
    fun setFat(text: String): QuickEntryUi { fat = text; return ui() }
    fun setKcal(text: String): QuickEntryUi { kcal = text; return ui() }
    fun setRemember(value: Boolean): QuickEntryUi { remember = value; return ui() }

    /** [defaultName] is the localized "Quick entry" used when no name was typed. False while invalid. */
    fun save(defaultName: String): Boolean {
        if (!valid || done) return false
        done = true
        onSave(name.trim().ifBlank { defaultName }, meal,
            Nutrients(NutritionFormat.optionalAmount(kcal), NutritionFormat.optionalAmount(protein),
                NutritionFormat.optionalAmount(carbs), NutritionFormat.optionalAmount(fat)), remember)
        return true
    }

    private fun invalid(text: String) = !NutritionFormat.fieldValid(text)

    private fun ui() = QuickEntryUi(
        isEditing = editing != null, name = name, mealCode = NutritionFormat.mealCode(meal),
        protein = protein, carbs = carbs, fat = fat, kcal = kcal,
        proteinInvalid = invalid(protein), carbsInvalid = invalid(carbs), fatInvalid = invalid(fat), kcalInvalid = invalid(kcal),
        remember = remember, showRemember = editing == null, valid = valid,
    )
}

class CreateFoodUi(
    val name: String,
    val brand: String,
    /** Values per serving (US labels) instead of per 100 g. */
    val perServing: Boolean,
    val protein: String,
    val carbs: String,
    val fat: String,
    val kcal: String,
    val servingG: String,
    val servingLabel: String,
    val barcode: String,
    val invalidFields: List<String>,
    val valid: Boolean,
)

/** A food with per-100 g values and an optional portion size (Android `CreateFoodDialog`). */
class CreateFoodForm internal constructor(
    private val barcode: String?,
    units: UnitSystem,
    private val onSave: (name: String, brand: String?, per100: Nutrients, servingG: Double?, servingLabel: String?) -> Unit,
) {
    // US labels state nutrition per serving, European ones per 100 g.
    private var perServing = units == UnitSystem.IMPERIAL
    private var name = ""
    private var brand = ""
    private var protein = ""
    private var carbs = ""
    private var fat = ""
    private var kcal = ""
    private var servingG = ""
    private var servingLabel = ""
    private var done = false

    private val serving: Double? get() = NutritionFormat.optionalAmount(servingG)?.takeIf { it > 0 }
    private val valid: Boolean
        get() {
            val anyNutrient = listOf(protein, carbs, fat, kcal).any { it.isNotBlank() }
            return name.isNotBlank() && listOf(protein, carbs, fat, kcal, servingG).all(NutritionFormat::fieldValid) &&
                (!perServing || !anyNutrient || serving != null)
        }

    /** Per 100 g, whichever way the label gave them. */
    private fun per100(text: String): Double? =
        NutritionFormat.optionalAmount(text)?.let { v -> if (perServing) serving?.let { v * 100.0 / it } else v }

    val current: CreateFoodUi get() = ui()

    fun setName(text: String): CreateFoodUi { name = text.take(120); return ui() }
    fun setBrand(text: String): CreateFoodUi { brand = text.take(80); return ui() }
    fun setPerServing(value: Boolean): CreateFoodUi { perServing = value; return ui() }
    fun setProtein(text: String): CreateFoodUi { protein = text; return ui() }
    fun setCarbs(text: String): CreateFoodUi { carbs = text; return ui() }
    fun setFat(text: String): CreateFoodUi { fat = text; return ui() }
    fun setKcal(text: String): CreateFoodUi { kcal = text; return ui() }
    fun setServingGrams(text: String): CreateFoodUi { servingG = text; return ui() }
    fun setServingLabel(text: String): CreateFoodUi { servingLabel = text.take(40); return ui() }

    fun save(): Boolean {
        if (!valid || done) return false
        done = true
        onSave(name.trim(), brand.trim().ifBlank { null },
            Nutrients(per100(kcal), per100(protein), per100(carbs), per100(fat)),
            serving, servingLabel.trim().ifBlank { null })
        return true
    }

    private fun ui() = CreateFoodUi(
        name = name, brand = brand, perServing = perServing, protein = protein, carbs = carbs, fat = fat, kcal = kcal,
        servingG = servingG, servingLabel = servingLabel, barcode = barcode.orEmpty(),
        invalidFields = listOf("protein" to protein, "carbs" to carbs, "fat" to fat, "kcal" to kcal, "serving" to servingG)
            .filter { !NutritionFormat.fieldValid(it.second) }.map { it.first },
        valid = valid,
    )
}

class RecipeIngredientUi(val index: Int, val name: String, val amount: NutriText)

class RecipeUi(
    val name: String,
    val portions: String,
    val cooked: String,
    /** "Cooked weight (g)" with the athlete's weight unit. */
    val cookedLabel: NutriText,
    val ingredients: List<RecipeIngredientUi>,
    /** "Per portion (200 g): …"; empty while the recipe is incomplete. */
    val summary: NutriText,
    val hasSummary: Boolean,
    val valid: Boolean,
    val isNew: Boolean,
)

/** A recipe being written or changed (Android `RecipeDraft` + `RecipeSheet`). */
class RecipeForm internal constructor(
    private val id: String?,
    recipe: Recipe?,
    private val units: UnitSystem,
    private val comma: Boolean,
    private val showCalories: Boolean,
    private val onSave: (id: String?, recipe: Recipe) -> Unit,
) {
    private val weightUnit = FuelUnits.unitFor(units, drink = false)
    private var name = recipe?.name.orEmpty()
    private var portions = recipe?.portions?.toString() ?: "2"
    private var cooked = recipe?.cookedWeightG?.let { NutritionFormat.inputText(it, weightUnit, comma) }.orEmpty()
    private var ingredients: List<Recipe.Ingredient> = recipe?.ingredients.orEmpty()
    private var done = false

    /** The food id this recipe is saved under (empty until it was saved once): not offered as its own ingredient. */
    val foodId: String get() = id.orEmpty()

    private fun recipe(): Recipe? {
        val count = portions.trim().toIntOrNull()?.takeIf { it in 1..50 } ?: return null
        if (name.isBlank() || ingredients.isEmpty()) return null
        val cookedG = NutritionFormat.parseDecimal(cooked)?.takeIf { it > 0 }?.let { FuelUnits.toBase(it, weightUnit) }
        return Recipe(name.trim(), count, ingredients, cookedG)
    }

    val current: RecipeUi get() = ui()

    fun setName(text: String): RecipeUi { name = text.take(120); return ui() }
    fun setPortions(text: String): RecipeUi { portions = text.take(2); return ui() }
    fun setCooked(text: String): RecipeUi { cooked = text.take(6); return ui() }
    fun removeIngredient(index: Int): RecipeUi { ingredients = ingredients.filterIndexed { j, _ -> j != index }; return ui() }

    internal fun addIngredient(item: FoodItem, grams: Double) {
        ingredients = ingredients + Recipe.ingredient(item, grams)
    }

    fun save(): Boolean {
        val r = recipe() ?: return false
        if (done) return false
        done = true
        onSave(id, r)
        return true
    }

    private fun ui(): RecipeUi {
        val r = recipe()
        val summary = r?.let {
            val item = it.toFoodItem("draft", 0)
            val serving = item.servingG ?: return@let null
            val f = serving / 100.0
            fun g(v: Double?) = v?.let { x -> NutritionTexts.num(x * f, 0) } ?: NutritionTexts.str("–")
            val kcal = item.kcalPer100?.let { k -> (k * f).roundToInt() }
            val perPortion = t("trf_recipe_per_portion", NutritionTexts.msg(amount(serving, weightUnit)),
                g(item.proteinPer100), g(item.carbsPer100), g(item.fatPer100))
            if (showCalories && kcal != null) NutritionTexts.join(listOf(perPortion, t("trf_kcal_value", NutritionTexts.int(kcal))))
            else perPortion
        }
        return RecipeUi(
            name = name, portions = portions, cooked = cooked,
            cookedLabel = t("trf_recipe_cooked", NutritionTexts.msg(NutritionTexts.unitLabel(weightUnit))),
            ingredients = ingredients.mapIndexed { i, ing ->
                // Drinks in ml / fl oz, like everywhere else.
                RecipeIngredientUi(i, ing.name, amount(ing.grams, FuelUnits.unitFor(units, FuelUnits.isDrink(ing.foodItemId, ing.name))))
            },
            summary = summary ?: NutritionTexts.empty,
            hasSummary = summary != null,
            valid = r != null,
            isNew = id == null,
        )
    }
}

/** Wires the forms to the day's presenter. */
internal class FoodFormFactory(
    private val presenter: FuelPresenter,
    private val comma: Boolean,
) {
    fun amount(item: FoodItem, meal: Meal, units: UnitSystem, showCalories: Boolean, last: FoodLogEntry?) =
        FoodAmountForm(item, meal, units, comma, showCalories, editing = null, last = last, forRecipe = false) { m, p, g ->
            presenter.addFood(item, m, p, g)
        }

    fun edit(entry: FoodLogEntry, item: FoodItem, units: UnitSystem, showCalories: Boolean) =
        FoodAmountForm(item, entry.meal, units, comma, showCalories, editing = entry, last = null, forRecipe = false) { m, p, g ->
            presenter.updateEntry(entry, item, m, p, g)
        }

    fun ingredient(item: FoodItem, recipe: RecipeForm, units: UnitSystem, showCalories: Boolean) =
        FoodAmountForm(item, Meal.SNACK, units, comma, showCalories, editing = null, last = null, forRecipe = true) { _, p, g ->
            val grams = g ?: p?.let { portions -> item.servingG?.let { it * portions } }
            if (grams != null) recipe.addIngredient(item, grams)
        }

    fun quick(meal: Meal) = QuickEntryForm(null, meal, comma) { name, m, n, remember -> presenter.quickAdd(name, m, n, remember) }

    fun quickEdit(entry: FoodLogEntry) = QuickEntryForm(entry, entry.meal, comma) { name, m, n, _ ->
        presenter.updateQuickEntry(entry, name, m, n)
    }

    fun create(barcode: String?, units: UnitSystem) = CreateFoodForm(barcode, units) { name, brand, per100, servingG, label ->
        presenter.createFood(name, brand, per100, servingG, label, barcode)
    }

    fun recipe(id: String?, recipe: Recipe?, units: UnitSystem, showCalories: Boolean) =
        RecipeForm(id, recipe, units, comma, showCalories) { rid, r -> presenter.saveRecipe(rid, r) }
}
