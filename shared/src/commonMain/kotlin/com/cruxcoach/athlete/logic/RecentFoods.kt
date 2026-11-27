package com.cruxcoach.athlete.logic

import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.Meal

/**
 * What the athlete eats again and again, learned from the log (UX review 2026-10-16: the same
 * breakfast was searched anew every day; quick entries, described and photographed meals never
 * became "my foods", and amounts always started at 100 g or one portion).
 */
object RecentFoods {

    /** Days of the log that count. */
    const val WINDOW_DAYS = 28
    const val LIMIT = 5

    /** One food or quick entry, whatever way it was logged: the saved food, else its name. */
    fun key(entry: FoodLogEntry): String = entry.foodItemId ?: ("name:" + entry.name.trim().lowercase())

    /**
     * Entries to log again with one tap at [meal]: the foods eaten at that meal, most often first,
     * then the most recent; each as its newest entry (so with its usual amount). Foods already in
     * [shown] at the same meal stay out.
     */
    fun forMeal(log: List<FoodLogEntry>, meal: Meal, shown: List<FoodLogEntry> = emptyList(), limit: Int = LIMIT): List<FoodLogEntry> {
        val already = shown.filter { it.meal == meal }.map(::key).toSet()
        return log.filter { it.meal == meal && key(it) !in already }
            .groupBy(::key)
            .map { (_, list) -> list.size to list.maxBy { it.loggedAt } }
            .sortedWith(compareByDescending<Pair<Int, FoodLogEntry>> { it.first }.thenByDescending { it.second.loggedAt })
            .take(limit)
            .map { it.second }
    }

    /** The newest entry of each saved food: its amount is where the amount dialog starts next time. */
    fun lastByFood(log: List<FoodLogEntry>): Map<String, FoodLogEntry> =
        log.filter { it.foodItemId != null && (it.amountG != null || it.portions != null) }
            .groupBy { it.foodItemId!! }
            .mapValues { (_, list) -> list.maxBy { it.loggedAt } }
}
