package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.BlsTable
import com.cruxcoach.athlete.logic.FoodMatcher
import com.cruxcoach.athlete.logic.UsdaNames
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The bundled USDA SR Legacy extract and the micronutrient columns of both tables (FEAT-069). */
class UsdaAndMicroAssetTest {

    private val usda = BlsTable.parse(File("../androidApp/src/main/assets/fuel/usda_sr_legacy.tsv").readText())
    private val bls = BlsTable.parse(File("../androidApp/src/main/assets/fuel/bls_4_0_macros.tsv").readText())

    @Test
    fun usdaHasEveryFoodWithCupsWhereMeasured() {
        assertEquals(7793, usda.size)
        val oats = usda.single { it.code == "173904" }
        assertEquals("Cereals, oats, regular and quick, not fortified, dry", oats.nameEn)
        assertEquals("", oats.nameDe)
        // Available carbohydrate: 67.7 g by difference minus 10.1 g fibre.
        assertEquals(57.6, oats.carbs, 0.01)
        assertEquals(81.0, oats.portions.single { it.label == "cup" }.grams, 0.01)
        assertEquals(4.25, oats.ironMg!!, 0.001)
    }

    @Test
    fun englishSearchFindsUsdaFoods() {
        val matcher = FoodMatcher(usda.map(UsdaNames::indexed))
        // USDA's generic "Oats" first, rolled oats when asked for them.
        val top = matcher.search("oats", limit = 10).map { it.food.code }
        assertTrue("169705" in top.take(3), top.toString())
        val rolled = matcher.search("oats regular quick", limit = 5).map { it.food.code }
        assertTrue("173904" in rolled, rolled.toString())
        val milk = matcher.search("whole milk", limit = 5).map { it.food.nameEn }
        assertTrue(milk.first().startsWith("Milk, whole"), milk.toString())
        assertEquals("Milk, whole", UsdaNames.core("Milk, whole, 3.25% milkfat, with added vitamin D"))
        assertEquals("Oats", UsdaNames.core("Oats (Includes foods for USDA's Food Distribution Program)"))
    }

    @Test
    fun blsCarriesIronCalciumAndVitaminD() {
        val herring = bls.single { it.code == "T102100" }
        assertEquals(1.04, herring.ironMg!!, 0.001)
        assertEquals(52.0, herring.calciumMg!!, 0.001)
        assertEquals(16.0, herring.vitaminDUg!!, 0.001)
        assertNotNull(bls.single { it.code == "C133000" }.ironMg)
        assertTrue(bls.count { it.ironMg != null } > 7000)
    }
}
