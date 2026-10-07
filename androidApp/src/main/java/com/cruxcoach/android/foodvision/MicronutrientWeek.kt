package com.cruxcoach.android.foodvision

import com.cruxcoach.android.athlete.AthleteService
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.model.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Iron, calcium and vitamin D over the 7 days up to a day, from logged BLS
 * and USDA foods (FEAT-069). One calculation for the nutrition screen's week
 * card and the weekly review.
 */
@Singleton
class MicronutrientWeek @Inject constructor(
    private val service: AthleteService,
    private val bls: BlsRepository,
    private val usda: UsdaRepository,
) {
    /** Null when none of the week's foods carries micronutrients. */
    suspend fun upTo(day: LocalDate, sex: Sex?, birthYear: Int?): MicroWatch.Summary? = withContext(Dispatchers.IO) {
        service.ensureReady()
        val entries = service.repo.foodLogBetween(day.minus(DatePeriod(days = 6)).toString(), day.toString())
        val foods = entries.mapNotNull { it.foodItemId }.toSet().associateWith { id ->
            val food = when {
                id.startsWith("bls:") -> bls.food(id.removePrefix("bls:"))
                id.startsWith("usda:") -> usda.food(id.removePrefix("usda:"))
                else -> null
            }
            food?.let { MicroWatch.Per100(it.ironMg, it.calciumMg, it.vitaminDUg) }
        }
        MicroWatch.summarize(entries, sex, birthYear?.let { day.year - it }) { foods[it] }
    }
}
