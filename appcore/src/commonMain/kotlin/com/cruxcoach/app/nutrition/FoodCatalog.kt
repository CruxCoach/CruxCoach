// The food sources and the conversions of androidApp/.../fuel/FoodPhotoViewModel.kt's
// companion (0e1bad0c6), without the photo model.
package com.cruxcoach.app.nutrition

import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.DetectedFood
import com.cruxcoach.athlete.logic.FoodVisionParser
import com.cruxcoach.athlete.logic.FuelUnits
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.OffProduct
import com.cruxcoach.athlete.logic.OffTable
import com.cruxcoach.athlete.model.FoodItem
import com.cruxcoach.athlete.model.Sex
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

    /** A BLS food as an (unsaved) per-100 g food item; saved on first use. */
    fun blsFoodItem(food: BlsFood, german: Boolean) = FoodItem(
        id = "bls:${food.code}", name = blsName(food, german), source = FoodTable.BLS_SOURCE,
        kcalPer100 = food.kcal, proteinPer100 = food.protein, carbsPer100 = food.carbs, fatPer100 = food.fat,
    )

    fun displayName(food: DetectedFood, german: Boolean): String = if (german) food.nameDe else food.nameEn

    /** Grams (or ml for water) of a typed amount in [unit]; null outside 0 < x ≤ 2000. */
    fun amountOf(text: String, unit: FuelUnits.Amount): Double? =
        text.replace(',', '.').toDoubleOrNull()?.let { FuelUnits.toBase(it, unit) }
            ?.takeIf { it > 0 && it <= FoodVisionParser.MAX_GRAMS }
}
