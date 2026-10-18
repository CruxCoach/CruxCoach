package com.cruxcoach.app.nutrition

import com.cruxcoach.app.ui.NutritionScreenModel
import com.cruxcoach.athlete.model.BodyMeasurement
import com.cruxcoach.athlete.model.BodyMetric
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Device test D42: a 2600 ml target showed ten glasses and one lonely glass in a second row. The row holds as many
 * glasses as fit, and a bigger target splits them evenly (Android `WaterCard`).
 */
class FuelWaterRowsTest {
    private val kits = mutableListOf<NutritionTestKit>()
    private val screens = mutableListOf<NutritionScreenModel>()

    private fun open(ownWaterMl: Int?): NutritionScreenModel {
        val k = NutritionTestKit().also { kits += it }
        runBlocking {
            k.db {
                k.repo.updateProfile { it.copy(fuelIntroAccepted = true, ownWaterMl = ownWaterMl) }
                if (ownWaterMl != null) {
                    k.repo.saveMeasurement(BodyMeasurement(k.service.today().toString(), BodyMetric.WEIGHT.key, 70.0, "kg", 1))
                }
            }
        }
        return k.screen().also { screens += it }
    }

    @AfterTest
    fun tearDown() {
        screens.forEach { it.close() }
        kits.forEach { it.close() }
    }

    @Test
    fun `more than ten glasses wrap into even rows`() = runBlocking<Unit> {
        val screen = open(ownWaterMl = 2600)
        val water = screen.await { !it.isLoading && it.water.glasses == 11 }.water
        // Ten fit one row: 6 + 5, not 10 + 1.
        assertEquals(6, water.glassesPerRow(10))
        // Room for all of them: one row.
        assertEquals(11, water.glassesPerRow(11))
        assertEquals(11, water.glassesPerRow(14))
        // A narrow card: as many as fit, the rows even (4 + 4 + 3).
        assertEquals(4, water.glassesPerRow(5))
    }

    @Test
    fun `the eight glasses without a target stay in one row`() = runBlocking<Unit> {
        val screen = open(ownWaterMl = null)
        val water = screen.await { !it.isLoading && it.needsWeight }.water
        assertEquals(8, water.glasses)
        assertEquals(8, water.glassesPerRow(10))
        // Never less than one per row, even when nothing seems to fit.
        assertEquals(1, water.glassesPerRow(0))
    }
}
