package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Sex

/**
 * Weekly watch items for iron, calcium and vitamin D – the micronutrients
 * that matter most for low energy availability in climbers. Estimated from
 * logged foods that come from BLS or USDA (packaged products and quick
 * entries carry no micronutrients), compared with EFSA reference values.
 * Information, never a diagnosis; no red states.
 */
object MicroWatch {

    enum class Nutrient { IRON, CALCIUM, VITAMIN_D }

    data class Item(val nutrient: Nutrient, val perDay: Double, val reference: Double)

    data class Summary(
        val items: List<Item>,
        /** Days of the window with any logged food. */
        val loggedDays: Int,
        /** Share of logged grams whose food has micronutrient data. */
        val coverage: Double,
    )

    /** Micronutrients per 100 g of a food; null fields are unknown. */
    data class Per100(val ironMg: Double?, val calciumMg: Double?, val vitaminDUg: Double?)

    /**
     * EFSA DRVs for adults: iron PRI 11 mg (men, women from 50) or 16 mg
     * (women before menopause, taken as under 50); calcium PRI 1000 mg at
     * 18–24 years, 950 mg from 25; vitamin D AI 15 µg.
     */
    fun reference(nutrient: Nutrient, sex: Sex?, age: Int?): Double = when (nutrient) {
        Nutrient.IRON -> if (sex == Sex.FEMALE && (age == null || age < 50)) 16.0 else 11.0
        Nutrient.CALCIUM -> if (age != null && age < 25) 1000.0 else 950.0
        Nutrient.VITAMIN_D -> 15.0
    }

    /**
     * Averages over the days with logged food. [lookup] gives the micronutrients
     * of an entry's food id ("bls:…", "usda:…"), or null when there are none.
     * Null when nothing in the window could be estimated.
     */
    fun summarize(entries: List<FoodLogEntry>, sex: Sex?, age: Int?, lookup: (String) -> Per100?): Summary? {
        val days = entries.map { it.day }.toSet().size
        if (days == 0) return null
        var known = 0.0
        var total = 0.0
        val sums = DoubleArray(Nutrient.entries.size)
        for (e in entries) {
            val grams = e.amountG ?: continue
            total += grams
            val micro = e.foodItemId?.let(lookup) ?: continue
            known += grams
            val f = grams / 100.0
            micro.ironMg?.let { sums[Nutrient.IRON.ordinal] += it * f }
            micro.calciumMg?.let { sums[Nutrient.CALCIUM.ordinal] += it * f }
            micro.vitaminDUg?.let { sums[Nutrient.VITAMIN_D.ordinal] += it * f }
        }
        if (known == 0.0) return null
        return Summary(
            items = Nutrient.entries.map { Item(it, sums[it.ordinal] / days, reference(it, sex, age)) },
            loggedDays = days,
            coverage = known / total,
        )
    }
}
