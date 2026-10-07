package com.cruxcoach.athlete

import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.MicroWatch.Nutrient
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal
import com.cruxcoach.athlete.model.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MicroWatchTest {

    private fun entry(day: String, id: String?, grams: Double?) =
        FoodLogEntry(id = "$day$id$grams", day = day, loggedAt = 0, meal = Meal.LUNCH, foodItemId = id, name = "x", amountG = grams)

    @Test
    fun efsaReferenceValues() {
        assertEquals(16.0, MicroWatch.reference(Nutrient.IRON, Sex.FEMALE, 30))
        assertEquals(11.0, MicroWatch.reference(Nutrient.IRON, Sex.FEMALE, 55))
        assertEquals(11.0, MicroWatch.reference(Nutrient.IRON, Sex.MALE, 30))
        assertEquals(16.0, MicroWatch.reference(Nutrient.IRON, Sex.FEMALE, null))
        assertEquals(1000.0, MicroWatch.reference(Nutrient.CALCIUM, null, 20))
        assertEquals(950.0, MicroWatch.reference(Nutrient.CALCIUM, null, 30))
        assertEquals(15.0, MicroWatch.reference(Nutrient.VITAMIN_D, Sex.MALE, 30))
    }

    @Test
    fun averagesPerLoggedDayWithCoverage() {
        val per100 = mapOf(
            "bls:oats" to MicroWatch.Per100(4.0, 50.0, 0.0),
            "usda:salmon" to MicroWatch.Per100(0.3, 9.0, 11.0),
        )
        val entries = listOf(
            entry("2026-10-01", "bls:oats", 100.0),
            entry("2026-10-01", "off:123", 100.0), // packaged product: no micronutrients
            entry("2026-10-02", "usda:salmon", 200.0),
            entry("2026-10-02", null, null), // quick entry without amount: ignored
        )
        val s = MicroWatch.summarize(entries, Sex.MALE, 30) { per100[it] }!!
        assertEquals(2, s.loggedDays)
        assertEquals(0.75, s.coverage, 1e-9)
        val iron = s.items.single { it.nutrient == Nutrient.IRON }
        assertEquals((4.0 + 0.6) / 2, iron.perDay, 1e-9)
        assertEquals(11.0, iron.reference)
        assertEquals(22.0 / 2, s.items.single { it.nutrient == Nutrient.VITAMIN_D }.perDay, 1e-9)
    }

    @Test
    fun nothingToEstimate() {
        assertNull(MicroWatch.summarize(emptyList(), null, null) { null })
        assertNull(MicroWatch.summarize(listOf(entry("2026-10-01", "off:1", 50.0)), null, null) { null })
    }
}
