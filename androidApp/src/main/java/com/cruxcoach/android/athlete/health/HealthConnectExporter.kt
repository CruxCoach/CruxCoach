package com.cruxcoach.android.athlete.health

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional export of logged meals and water to Health Connect (FEAT-069),
 * through the same Jetpack layer as the coach's reading
 * ([HealthConnectSource]), so it works wherever Health Connect does
 * (Android 9–13 with the Health Connect app, built into 14+). Each entry
 * becomes one record with a stable client record id, so a changed entry
 * replaces its record and a deleted one is removed. Off unless the athlete
 * switches it on and grants the two write permissions.
 */
@Singleton
class HealthConnectExporter @Inject constructor(@ApplicationContext private val context: Context) {

    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /**
     * Writes one day's entries: new and changed ones are inserted (a higher
     * client record version replaces the old record), entries that are gone
     * are deleted. Unchanged ones are skipped. Without Health Connect or
     * without both write permissions nothing happens.
     */
    suspend fun syncDay(day: String, entries: List<FoodLogEntry>, water: List<HydrationEntry>) = mutex.withLock {
        HealthConnectSource.guarded(context, Unit) { client ->
            if (!client.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)) return@guarded
            val key = "day:$day"
            val before = prefs.getStringSet(key, emptySet()).orEmpty()
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            val current = entries.associate { foodId(it.id) to it.hashCode().toString() } +
                water.associate { waterId(it.id) to it.hashCode().toString() }
            val now = System.currentTimeMillis()
            val records = buildList<Record> {
                entries.filter { before[foodId(it.id)] != current[foodId(it.id)] }.forEach { add(nutrition(it, now)) }
                water.filter { before[waterId(it.id)] != current[waterId(it.id)] }.forEach { add(hydration(it, now)) }
            }
            val gone = before.keys - current.keys
            if (records.isNotEmpty()) client.insertRecords(records)
            gone.filter { it.startsWith(FOOD) }.takeIf { it.isNotEmpty() }
                ?.let { client.deleteRecords(NutritionRecord::class, emptyList(), it) }
            gone.filter { it.startsWith(WATER) }.takeIf { it.isNotEmpty() }
                ?.let { client.deleteRecords(HydrationRecord::class, emptyList(), it) }
            prefs.edit { putStringSet(key, current.map { (id, hash) -> "$id=$hash" }.toSet()) }
            Log.d(TAG, "Health Connect: $day ${records.size} written, ${gone.size} removed")
        }
    }

    companion object {
        private const val TAG = "HealthConnectExporter"
        private const val PREFS = "health_connect_export"
        private const val FOOD = "cruxcoach-food-"
        private const val WATER = "cruxcoach-water-"

        /** Write access for nutrition: meals and water. */
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(NutritionRecord::class),
            HealthPermission.getWritePermission(HydrationRecord::class),
        )

        fun foodId(id: String) = FOOD + id
        fun waterId(id: String) = WATER + id

        private fun offset(at: Instant) = ZoneId.systemDefault().rules.getOffset(at)

        /** A logged food as a nutrition record over one minute at the time it was logged. */
        fun nutrition(e: FoodLogEntry, version: Long): NutritionRecord {
            val start = Instant.ofEpochMilli(e.loggedAt)
            val end = start.plus(Duration.ofMinutes(1))
            return NutritionRecord(
                startTime = start, startZoneOffset = offset(start),
                endTime = end, endZoneOffset = offset(end),
                metadata = Metadata.manualEntry(clientRecordId = foodId(e.id), clientRecordVersion = version),
                name = e.name.take(100).takeIf { it.isNotBlank() },
                mealType = mealType(e.meal),
                energy = e.kcal?.let { Energy.kilocalories(it) },
                protein = e.proteinG?.let { Mass.grams(it) },
                totalCarbohydrate = e.carbsG?.let { Mass.grams(it) },
                totalFat = e.fatG?.let { Mass.grams(it) },
            )
        }

        fun hydration(w: HydrationEntry, version: Long): HydrationRecord {
            val start = Instant.ofEpochMilli(w.loggedAt)
            val end = start.plus(Duration.ofMinutes(1))
            return HydrationRecord(
                startTime = start, startZoneOffset = offset(start),
                endTime = end, endZoneOffset = offset(end),
                volume = Volume.milliliters(w.ml.toDouble()),
                metadata = Metadata.manualEntry(clientRecordId = waterId(w.id), clientRecordVersion = version),
            )
        }

        fun mealType(meal: Meal): Int = when (meal) {
            Meal.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
            Meal.LUNCH -> MealType.MEAL_TYPE_LUNCH
            Meal.DINNER -> MealType.MEAL_TYPE_DINNER
            Meal.SNACK, Meal.PRE_TRAINING, Meal.POST_TRAINING -> MealType.MEAL_TYPE_SNACK
        }
    }
}
