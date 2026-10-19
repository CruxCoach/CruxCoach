// The food sources and the conversions of androidApp/.../fuel/FoodPhotoViewModel.kt's
// companion (0e1bad0c6, products as review choices 89c7b902f), without the photo model.
package com.cruxcoach.app.nutrition

import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.FoodMatcher
import com.cruxcoach.athlete.logic.FoodVisionParser
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MealTextParser
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.OffProduct
import com.cruxcoach.athlete.logic.OffTable
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.Sex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

/**
 * Every offline food source the nutrition screens search: the BLS and USDA
 * tables and the packaged-product database. One per app, like Android's
 * singletons, so the tables are parsed once.
 */
class FoodCatalog(
    val bls: FoodTable,
    val usda: FoodTable,
    val products: OffProductStore,
    private val micros: MicronutrientWeek,
    /** The app language is German: German names first, German products first. */
    val german: () -> Boolean,
    private val compute: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + compute)

    /**
     * Unpacks the product database and loads the BLS and USDA tables in the
     * background, so the first search does not wait for them.
     */
    fun prepare() {
        products.prepareInBackground()
        scope.launch {
            runCatching { bls.matcher() }
            runCatching { usda.matcher() }
        }
    }

    suspend fun search(query: String): List<BlsFood> {
        val matcher = bls.matcher()
        return withContext(compute) { matcher.search(query, limit = 25).map { it.food } }
    }

    /**
     * The review's choice: the food table first, then packaged products (protein powders, bars, branded foods the
     * table has no counterpart for – owner 2026-10-19). Products come as choices with a "off:" code.
     */
    suspend fun searchWithProducts(query: String): List<BlsFood> {
        val foods = search(query)
        val packaged: List<OffProduct> = try {
            products.search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val found = packaged.take(15).map(FoodItems::productChoice)
        // "Whey" finds sweet whey in the table; what is meant is a product (device test 2026-10-19).
        return if (FoodMatcher.lacksCounterpart(query)) found + foods.take(15) else foods.take(15) + found
    }

    /** USDA generic foods (English names, household measures). */
    suspend fun searchUsda(query: String): List<BlsFood> {
        val matcher = usda.matcher()
        return withContext(compute) { matcher.search(query, limit = 15).map { it.food } }
    }

    /** Packaged products (Open Food Facts) by name or brand, on the device. */
    suspend fun searchProducts(query: String): List<OffProduct> = products.search(query)

    /** A scanned barcode in the on-device product database. */
    suspend fun productByBarcode(code: String): OffProduct? = products.byBarcode(code)

    /** Iron, calcium and vitamin D over the 7 days up to [day]; null when none of the foods carries them. */
    suspend fun microWeek(day: LocalDate, sex: Sex?, birthYear: Int?): MicroWatch.Summary? = micros.upTo(day, sex, birthYear)
}

/** Food items from the bundled sources, as FoodPhotoViewModel's companion builds them. */
object FoodItems {
    private val WATER = setOf("wasser", "water", "mineralwasser", "leitungswasser", "sprudelwasser", "mineral water",
        "sparkling water", "tap water", "trinkwasser", "drinking water")

    fun isWater(food: DetectedFood) =
        food.nameDe.trim().lowercase() in WATER || food.nameEn.trim().lowercase() in WATER

    fun blsName(food: BlsFood, german: Boolean): String = if (german) food.nameDe else food.nameEn

    /** A packaged product as an (unsaved) per-100 g food item; saved on first use. */
    fun offFoodItem(p: OffProduct, german: Boolean) = FoodItem(
        id = "off:${p.code}", name = p.displayName(german), brand = p.brand.ifEmpty { null }, barcode = p.code,
        kcalPer100 = p.kcal, proteinPer100 = p.protein, carbsPer100 = p.carbs, fatPer100 = p.fat,
        servingG = p.servingG, servingLabel = p.servingLabel?.takeIf(OffTable::readableServing), source = OffProductStore.SOURCE,
    )

    /**
     * A USDA food as an (unsaved) per-100 g food item; saved on first use. Its
     * portion is a cup where USDA weighed one, so cups work for solid foods too.
     */
    fun usdaFoodItem(food: BlsFood): FoodItem {
        val portion = food.portions.firstOrNull { it.label.startsWith("cup") } ?: food.portions.firstOrNull()
        return FoodItem(
            id = "usda:${food.code}", name = food.nameEn, source = FoodTable.USDA_SOURCE,
            kcalPer100 = food.kcal, proteinPer100 = food.protein, carbsPer100 = food.carbs, fatPer100 = food.fat,
            servingG = portion?.grams, servingLabel = portion?.let { "1 ${it.label}" },
        )
    }

    /**
     * A BLS food as an (unsaved) per-100 g food item; saved on first use. Foods people count (a banana, an egg)
     * get one piece as their portion, so the amount dialog offers pieces next to grams (device test D43).
     */
    fun blsFoodItem(food: BlsFood, german: Boolean): FoodItem {
        val piece = MealTextParser.pieceGramsOf(food.nameDe)
        return FoodItem(
            id = "bls:${food.code}", name = blsName(food, german), source = FoodTable.BLS_SOURCE,
            kcalPer100 = food.kcal, proteinPer100 = food.protein, carbsPer100 = food.carbs, fatPer100 = food.fat,
            servingG = piece, servingLabel = piece?.let { pieceLabel(it, german) },
        )
    }

    // Stored with the food like Android's label, so it reads the same wherever the saved food shows up.
    private fun pieceLabel(grams: Double, german: Boolean) =
        if (german) "1 Stück (${grams.toInt()} g)" else "1 piece (${grams.toInt()} g)"

    /**
     * A BLS food saved before it knew its piece weight ("my foods"), with the piece added for the amount dialog;
     * logging it saves the piece (device test D43: the saved "Banane roh" still offered only grams).
     */
    fun withPiece(item: FoodItem, german: Boolean): FoodItem {
        if (item.source != FoodTable.BLS_SOURCE || item.servingG != null) return item
        val piece = MealTextParser.pieceGramsOf(item.name) ?: return item
        return item.copy(servingG = piece, servingLabel = pieceLabel(piece, german))
    }

    fun displayName(food: DetectedFood, german: Boolean): String = if (german) food.nameDe else food.nameEn

    /** Code prefix of packaged products offered as a review choice. */
    const val PRODUCT_PREFIX = "off:"

    fun isProduct(food: BlsFood): Boolean = food.code.startsWith(PRODUCT_PREFIX)

    /** A packaged product as a review choice (per 100 g); the brand is part of the name. */
    fun productChoice(p: OffProduct): BlsFood {
        fun named(german: Boolean) =
            p.displayName(german).let { n -> if (p.brand.isBlank() || n.contains(p.brand, ignoreCase = true)) n else "$n (${p.brand})" }
        return BlsFood(code = PRODUCT_PREFIX + p.code, nameDe = named(true), nameEn = named(false),
            kcal = p.kcal, protein = p.protein, fat = p.fat, carbs = p.carbs)
    }

    /** A product choice as its (unsaved) per-100 g "off:" food item, the same id a scanned barcode gets. */
    fun productFoodItem(food: BlsFood, german: Boolean) = FoodItem(
        id = food.code, name = blsName(food, german), barcode = food.code.removePrefix(PRODUCT_PREFIX), source = OffProductStore.SOURCE,
        kcalPer100 = food.kcal, proteinPer100 = food.protein, carbsPer100 = food.carbs, fatPer100 = food.fat,
    )

    /** Whether a chosen food is a drink: products by their names, table foods by their group too. */
    fun isDrinkChoice(food: BlsFood): Boolean =
        if (isProduct(food)) FuelUnits.isDrink(null, food.nameDe) || FuelUnits.isDrink(null, food.nameEn)
        else FuelUnits.isDrink("bls:${food.code}", food.nameEn)

    /** Grams (or ml for water) of a typed amount in [unit]; null outside 0 < x ≤ 2000. */
    fun amountOf(text: String, unit: FuelUnits.Amount): Double? =
        text.replace(',', '.').toDoubleOrNull()?.let { FuelUnits.toBase(it, unit) }
            ?.takeIf { it > 0 && it <= FoodVisionParser.MAX_GRAMS }
}
