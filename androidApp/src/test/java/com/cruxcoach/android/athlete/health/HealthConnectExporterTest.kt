package com.cruxcoach.android.athlete.health

import android.app.Application
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.metadata.Metadata
import androidx.test.core.app.ApplicationProvider
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What CruxCoach would write to Health Connect, and that it writes nothing without permission (FEAT-069). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HealthConnectExporterTest {

    private val loggedAt = 1_791_400_000_000
    /** The day of [loggedAt] wherever the test runs, so the entry counts as logged on its own day. */
    private val loggedDay = java.time.Instant.ofEpochMilli(loggedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
    private val entry = FoodLogEntry(id = "e1", day = loggedDay, loggedAt = loggedAt, meal = Meal.POST_TRAINING,
        name = "Skyr", amountG = 250.0, kcal = 157.5, proteinG = 27.5, carbsG = 10.0)

    @Test
    fun `a logged food becomes a nutrition record with a stable id`() {
        val record = HealthConnectExporter.nutrition(entry, version = 7)
        assertEquals("cruxcoach-food-e1", record.metadata.clientRecordId)
        assertEquals(7L, record.metadata.clientRecordVersion)
        assertEquals(Metadata.RECORDING_METHOD_MANUAL_ENTRY, record.metadata.recordingMethod)
        assertEquals("Skyr", record.name)
        assertEquals(MealType.MEAL_TYPE_SNACK, record.mealType)
        assertEquals(157.5, record.energy!!.inKilocalories, 1e-6)
        assertEquals(27.5, record.protein!!.inGrams, 1e-9)
        assertEquals(10.0, record.totalCarbohydrate!!.inGrams, 1e-9)
        // Not logged: not written as zero.
        assertNull(record.totalFat)
        assertEquals(entry.loggedAt, record.startTime.toEpochMilli())
    }

    @Test
    fun `an entry logged for an earlier day lands on that day`() {
        val zone = java.time.ZoneId.systemDefault()
        // Tuesday 08:15, logging Monday's dinner.
        val tuesdayMorning = java.time.LocalDate.of(2026, 10, 6).atTime(8, 15).atZone(zone).toInstant().toEpochMilli()
        val record = HealthConnectExporter.nutrition(entry.copy(day = "2026-10-05", loggedAt = tuesdayMorning, meal = Meal.DINNER), 1)
        val at = record.startTime.atZone(zone)
        assertEquals(java.time.LocalDate.of(2026, 10, 5), at.toLocalDate())
        assertEquals(java.time.LocalTime.of(8, 15), at.toLocalTime())
        // Logged on its own day: the logging time as it is.
        val sameDay = HealthConnectExporter.timeOf("2026-10-06", tuesdayMorning)
        assertEquals(tuesdayMorning, sameDay.toEpochMilli())
        val water = HealthConnectExporter.hydration(HydrationEntry("w2", "2026-10-05", tuesdayMorning, 500), version = 1)
        assertEquals(java.time.LocalDate.of(2026, 10, 5), water.startTime.atZone(zone).toLocalDate())
    }

    @Test
    fun `water becomes a hydration record`() {
        val record = HealthConnectExporter.hydration(HydrationEntry("w1", "2026-10-07", 1_791_400_000_000, 250), version = 1)
        assertEquals("cruxcoach-water-w1", record.metadata.clientRecordId)
        assertEquals(0.25, record.volume.inLiters, 1e-9)
    }

    @Test
    fun `the write permissions are the platform names the manifest declares`() {
        assertEquals(
            setOf("android.permission.health.WRITE_NUTRITION", "android.permission.health.WRITE_HYDRATION"),
            HealthConnectExporter.PERMISSIONS,
        )
        assertEquals(
            setOf("android.permission.health.READ_SLEEP", "android.permission.health.READ_EXERCISE"),
            HealthConnectSource.PERMISSIONS,
        )
    }

    @Test
    fun `without Health Connect or permission nothing is written and nothing throws`() = runBlocking {
        HealthConnectExporter(ApplicationProvider.getApplicationContext()).syncDay("2026-10-07", listOf(entry), emptyList())
    }
}
