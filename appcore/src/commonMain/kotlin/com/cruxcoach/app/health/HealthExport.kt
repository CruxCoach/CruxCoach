package com.cruxcoach.app.health

import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * What the nutrition export writes (Android `HealthConnectExporter`'s companion), pure for tests. Each
 * logged food and glass of water keeps Android's stable client record id ("cruxcoach-food-<id>",
 * "cruxcoach-water-<id>"). HealthKit stores one sample per quantity, so each quantity gets its own sync id
 * "<record id>.<type code>"; a changed entry is rewritten with a higher sync version (HealthKit replaces the
 * old sample), a quantity that is no longer logged and an entry that is gone are deleted.
 */
internal object HealthExport {
    const val FOOD = "cruxcoach-food-"
    const val WATER = "cruxcoach-water-"

    /** Key prefix of the per-day stamps (Android: SharedPreferences "health_connect_export", key "day:<day>"). */
    const val STAMP_PREFIX = "health_connect_export.day:"

    private val FOOD_TYPES = listOf(HealthTypes.ENERGY, HealthTypes.PROTEIN, HealthTypes.CARBS, HealthTypes.FAT)

    fun foodId(id: String) = FOOD + id
    fun waterId(id: String) = WATER + id

    fun syncId(recordId: String, typeCode: String) = "$recordId.$typeCode"

    /** Every sync id a record may have written. */
    fun syncIds(recordId: String): List<String> =
        if (recordId.startsWith(WATER)) listOf(syncId(recordId, HealthTypes.WATER)) else FOOD_TYPES.map { syncId(recordId, it) }

    /**
     * Content stamp of an entry for the "unchanged" check. From toString(), not
     * hashCode(): an enum's hash code (the meal) differs from process to
     * process, which rewrote every record of the day after each app start.
     */
    fun stamp(entry: Any): String = entry.toString().hashCode().toString()

    /**
     * When an entry happened: the time it was logged, moved onto its own day
     * when it was logged for another day (yesterday's dinner, typed in this
     * morning, belongs to yesterday). Never in the future.
     */
    fun timeOf(day: String, loggedAt: Long, zone: TimeZone, nowMs: Long): Long {
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return loggedAt
        val local = Instant.fromEpochMilliseconds(loggedAt).toLocalDateTime(zone)
        if (local.date == date) return loggedAt
        return minOf(LocalDateTime(date, local.time).toInstant(zone).toEpochMilliseconds(), nowMs)
    }

    /** A logged food as one minute at the time it was eaten (see [timeOf]); a value not logged stays null, never zero. */
    class FoodRecord(
        val clientRecordId: String,
        val version: Long,
        val startMs: Long,
        val endMs: Long,
        val name: String?,
        /** breakfast | lunch | dinner | snack (Health Connect's meal types). */
        val mealCode: String,
        val kcal: Double?,
        val proteinG: Double?,
        val carbsG: Double?,
        val fatG: Double?,
    ) {
        /** Entered by hand in the app, as Android's `Metadata.manualEntry`. */
        val manualEntry: Boolean get() = true

        fun samples(): List<HealthNutritionSample> = listOfNotNull(
            kcal?.let { HealthTypes.ENERGY to it },
            proteinG?.let { HealthTypes.PROTEIN to it },
            carbsG?.let { HealthTypes.CARBS to it },
            fatG?.let { HealthTypes.FAT to it },
        ).filter { it.second.isFinite() && it.second >= 0.0 }.map { (code, value) ->
            HealthNutritionSample(syncId(clientRecordId, code), version, code, value, startMs, endMs, name.orEmpty())
        }
    }

    class WaterRecord(val clientRecordId: String, val version: Long, val startMs: Long, val endMs: Long, val ml: Double) {
        fun samples(): List<HealthNutritionSample> =
            if (ml > 0.0) listOf(HealthNutritionSample(syncId(clientRecordId, HealthTypes.WATER), version, HealthTypes.WATER, ml, startMs, endMs, ""))
            else emptyList()
    }

    fun nutrition(e: FoodLogEntry, version: Long, zone: TimeZone, nowMs: Long): FoodRecord {
        val start = timeOf(e.day, e.loggedAt, zone, nowMs)
        return FoodRecord(
            clientRecordId = foodId(e.id),
            version = version,
            startMs = start,
            endMs = start + 60_000L,
            name = e.name.take(100).takeIf { it.isNotBlank() },
            mealCode = mealCode(e.meal),
            kcal = e.kcal,
            proteinG = e.proteinG,
            carbsG = e.carbsG,
            fatG = e.fatG,
        )
    }

    fun hydration(w: HydrationEntry, version: Long, zone: TimeZone, nowMs: Long): WaterRecord {
        val start = timeOf(w.day, w.loggedAt, zone, nowMs)
        return WaterRecord(waterId(w.id), version, start, start + 60_000L, w.ml.toDouble())
    }

    fun mealCode(meal: Meal): String = when (meal) {
        Meal.BREAKFAST -> "breakfast"
        Meal.LUNCH -> "lunch"
        Meal.DINNER -> "dinner"
        Meal.SNACK, Meal.PRE_TRAINING, Meal.POST_TRAINING -> "snack"
    }

    /** What one day's sync sends: new and changed entries, the sync ids to delete, and the stamps to keep afterwards. */
    class Plan(val samples: List<HealthNutritionSample>, val deleteSyncIds: List<String>, val stamps: Map<String, String>, val written: Int, val removed: Int)

    fun plan(
        entries: List<FoodLogEntry>,
        water: List<HydrationEntry>,
        before: Map<String, String>,
        version: Long,
        zone: TimeZone,
        nowMs: Long,
    ): Plan {
        val current = entries.associate { foodId(it.id) to stamp(it) } + water.associate { waterId(it.id) to stamp(it) }
        val changedFood = entries.filter { before[foodId(it.id)] != current[foodId(it.id)] }.map { nutrition(it, version, zone, nowMs) }
        val changedWater = water.filter { before[waterId(it.id)] != current[waterId(it.id)] }.map { hydration(it, version, zone, nowMs) }
        val samples = changedFood.flatMap { it.samples() } + changedWater.flatMap { it.samples() }
        val written = samples.map { it.syncId }.toSet()
        val gone = before.keys - current.keys
        // A changed entry whose fat (say) was removed would keep its old fat sample otherwise.
        val dropped = (changedFood.map { it.clientRecordId } + changedWater.map { it.clientRecordId })
            .filter { it in before }
            .flatMap { syncIds(it) }
            .filter { it !in written }
        val deletes = gone.flatMap { syncIds(it) } + dropped
        return Plan(samples, deletes.distinct(), current, changedFood.size + changedWater.size, gone.size)
    }

    fun encodeStamps(stamps: Map<String, String>): String =
        stamps.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

    fun decodeStamps(raw: String?): Map<String, String> =
        raw.orEmpty().split('\n').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
}
