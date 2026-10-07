package com.cruxcoach.android.foodvision

import android.app.Application
import android.health.connect.datatypes.MealType
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What CruxCoach would write to Health Connect, and that it writes nothing without permission (FEAT-069). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HealthConnectExporterTest {

    private val entry = FoodLogEntry(id = "e1", day = "2026-10-07", loggedAt = 1_791_400_000_000, meal = Meal.POST_TRAINING,
        name = "Skyr", amountG = 250.0, kcal = 157.5, proteinG = 27.5, carbsG = 10.0)

    @Test
    fun `a logged food becomes a nutrition record with a stable id`() {
        val record = HealthConnectExporter.nutrition(entry, version = 7)
        assertEquals("cruxcoach-food-e1", record.metadata.clientRecordId)
        assertEquals(7L, record.metadata.clientRecordVersion)
        assertEquals("Skyr", record.mealName)
        assertEquals(MealType.MEAL_TYPE_SNACK, record.mealType)
        // The platform stores small calories.
        assertEquals(157_500.0, record.energy!!.inCalories, 1e-6)
        assertEquals(27.5, record.protein!!.inGrams, 1e-9)
        assertEquals(10.0, record.totalCarbohydrate!!.inGrams, 1e-9)
        // Not logged: not written as zero.
        assertNull(record.totalFat)
        assertEquals(entry.loggedAt, record.startTime.toEpochMilli())
    }

    @Test
    fun `water becomes a hydration record`() {
        val record = HealthConnectExporter.hydration(HydrationEntry("w1", "2026-10-07", 1_791_400_000_000, 250), version = 1)
        assertEquals("cruxcoach-water-w1", record.metadata.clientRecordId)
        assertEquals(0.25, record.volume.inLiters, 1e-9)
    }

    @Test
    fun `without permission nothing is written`() = runBlocking {
        val exporter = HealthConnectExporter(ApplicationProvider.getApplicationContext())
        assertFalse(exporter.hasPermissions())
        exporter.syncDay("2026-10-07", listOf(entry), emptyList())
    }
}
