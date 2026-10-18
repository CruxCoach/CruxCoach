// Port of androidApp/.../foodvision/{FoodTableRepository,BlsRepository,UsdaRepository,MicronutrientWeek}.kt.
package com.cruxcoach.app.nutrition

import com.cruxcoach.app.athlete.AthleteService
import com.cruxcoach.athlete.logic.BlsFood
import com.cruxcoach.athlete.logic.BlsTable
import com.cruxcoach.athlete.logic.FoodMatcher
import com.cruxcoach.athlete.logic.MicroWatch
import com.cruxcoach.athlete.logic.UsdaNames
import com.cruxcoach.athlete.model.Sex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus

/**
 * A bundled food table (BLS or USDA), read into memory once on first use.
 * [read] returns the TSV text or throws; the app holds one instance per table,
 * so the 7,000-row parse happens once per process.
 */
class FoodTable(
    private val read: () -> String,
    private val io: CoroutineDispatcher,
    /** Adjusts rows before indexing, e.g. USDA core names. */
    private val prepare: (BlsFood) -> BlsFood = { it },
) {
    private class Loaded(val matcher: FoodMatcher, val byCode: Map<String, BlsFood>)

    private val mutex = Mutex()
    private var loaded: Loaded? = null

    private suspend fun load(): Loaded = mutex.withLock {
        loaded ?: withContext(io) {
            val foods = BlsTable.parse(read()).map(prepare)
            Loaded(FoodMatcher(foods), foods.associateBy { it.code })
        }.also { loaded = it }
    }

    suspend fun matcher(): FoodMatcher = load().matcher

    /** One food by its code, e.g. for the micronutrients of a logged entry. */
    suspend fun food(code: String): BlsFood? = load().byCode[code]

    companion object {
        /**
         * The bundled BLS 4.0 extract (Max Rubner-Institut, CC BY 4.0): 7,140
         * generic German foods with energy, macros, iron, calcium and vitamin D per
         * 100 g, offline.
         */
        const val BLS_ASSET = "fuel/bls_4_0_macros.tsv"
        /** food_item.source for BLS foods; their id is "bls:" + code. */
        const val BLS_SOURCE = "bls"
        const val BLS_ATTRIBUTION = "Max Rubner-Institut (2025): Bundeslebensmittelschlüssel (BLS), Version 4.0 — " +
            "Deutsche Nährstoffdatenbank. Karlsruhe. DOI: 10.25826/Data20251217-134202-0 (CC BY 4.0)"

        /**
         * The bundled USDA FoodData Central SR Legacy extract (public domain, CC0):
         * 7,793 generic US foods with English names, the same nutrients as BLS and
         * household measures (cup, tbsp, slice) with their weight, offline.
         */
        const val USDA_ASSET = "fuel/usda_sr_legacy.tsv"
        /** food_item.source for USDA foods; their id is "usda:" + FDC id. */
        const val USDA_SOURCE = "usda"
        const val USDA_ATTRIBUTION = "U.S. Department of Agriculture, Agricultural Research Service. FoodData Central: " +
            "SR Legacy (2018). https://fdc.nal.usda.gov/ (public domain, CC0 1.0)"

        /** Neither table is near this size; anything larger is not ours. */
        const val MAX_TABLE_BYTES = 8L * 1024 * 1024

        fun bls(read: () -> String, io: CoroutineDispatcher) = FoodTable(read, io)
        fun usda(read: () -> String, io: CoroutineDispatcher) = FoodTable(read, io, UsdaNames::indexed)
    }
}

/**
 * Iron, calcium and vitamin D over the 7 days up to a day, from logged BLS
 * and USDA foods (FEAT-069). One calculation for the nutrition screen's week
 * card and the weekly review.
 */
class MicronutrientWeek(
    private val service: AthleteService,
    private val bls: FoodTable,
    private val usda: FoodTable,
    private val io: CoroutineDispatcher,
) {
    /** Null when none of the week's foods carries micronutrients. */
    suspend fun upTo(day: LocalDate, sex: Sex?, birthYear: Int?): MicroWatch.Summary? = withContext(io) {
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
