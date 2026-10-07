package com.cruxcoach.android.foodvision

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.InsertRecordsResponse
import android.health.connect.RecordIdFilter
import android.health.connect.datatypes.HydrationRecord
import android.health.connect.datatypes.MealType
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.NutritionRecord
import android.health.connect.datatypes.Record
import android.health.connect.datatypes.units.Energy
import android.health.connect.datatypes.units.Mass
import android.health.connect.datatypes.units.Volume
import android.os.Build
import android.os.OutcomeReceiver
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.cruxcoach.athlete.model.FoodLogEntry
import com.cruxcoach.athlete.model.HydrationEntry
import com.cruxcoach.athlete.model.Meal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional export of logged meals and water to Health Connect (FEAT-069),
 * write-only: CruxCoach never reads health data. It uses the Health Connect
 * built into Android 14+ – no extra library in the app. Each entry becomes
 * one record with a stable client record id, so a changed entry replaces its
 * record and a deleted one is removed. Off unless the athlete switches it on
 * and grants the two write permissions.
 */
@Singleton
class HealthConnectExporter @Inject constructor(@ApplicationContext private val context: Context) {

    private val mutex = Mutex()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Health Connect is part of the system from Android 14. */
    fun available(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && manager() != null

    fun hasPermissions(): Boolean = available() &&
        PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun manager(): HealthConnectManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { context.getSystemService(HealthConnectManager::class.java) }.getOrNull()
        } else null

    /**
     * Writes one day's entries: new and changed ones are inserted (a higher
     * client record version replaces the old record), entries that are gone
     * are deleted. Unchanged ones are skipped.
     */
    suspend fun syncDay(day: String, entries: List<FoodLogEntry>, water: List<HydrationEntry>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || !hasPermissions()) return@withContext
            val hc = manager() ?: return@withContext
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
            val gone = (before.keys - current.keys).map { id ->
                RecordIdFilter.fromClientRecordId(if (id.startsWith(FOOD)) NutritionRecord::class.java else HydrationRecord::class.java, id)
            }
            runCatching {
                if (records.isNotEmpty()) hc.await<InsertRecordsResponse> { r -> insertRecords(records, DIRECT, r) }
                if (gone.isNotEmpty()) hc.await<Void> { r -> deleteRecords(gone, DIRECT, r) }
                prefs.edit { putStringSet(key, current.map { (id, hash) -> "$id=$hash" }.toSet()) }
            }.onFailure { Log.w(TAG, "Health Connect export of $day failed", it) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun <T> HealthConnectManager.await(call: HealthConnectManager.(OutcomeReceiver<T, HealthConnectException>) -> Unit) {
        suspendCancellableCoroutine { cont ->
            call(object : OutcomeReceiver<T, HealthConnectException> {
                override fun onResult(result: T) = cont.resume(Unit)
                override fun onError(error: HealthConnectException) = cont.resumeWithException(error)
            })
        }
    }

    companion object {
        private const val TAG = "HealthConnectExporter"
        private const val PREFS = "health_connect_export"
        private const val FOOD = "cruxcoach-food-"
        private const val WATER = "cruxcoach-water-"
        private val DIRECT = Executor { it.run() }

        val PERMISSIONS = arrayOf("android.permission.health.WRITE_NUTRITION", "android.permission.health.WRITE_HYDRATION")

        fun foodId(id: String) = FOOD + id
        fun waterId(id: String) = WATER + id

        private fun metadata(clientId: String, version: Long) =
            Metadata.Builder().setClientRecordId(clientId).setClientRecordVersion(version).build()

        /** A logged food as a nutrition record over one minute at the time it was logged. */
        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun nutrition(e: FoodLogEntry, version: Long): NutritionRecord {
            val start = Instant.ofEpochMilli(e.loggedAt)
            return NutritionRecord.Builder(metadata(foodId(e.id), version), start, start.plus(Duration.ofMinutes(1))).apply {
                setMealType(mealType(e.meal))
                e.name.take(100).takeIf { it.isNotBlank() }?.let { setMealName(it) }
                // The platform counts energy in small calories.
                e.kcal?.let { setEnergy(Energy.fromCalories(it * 1000.0)) }
                e.proteinG?.let { setProtein(Mass.fromGrams(it)) }
                e.carbsG?.let { setTotalCarbohydrate(Mass.fromGrams(it)) }
                e.fatG?.let { setTotalFat(Mass.fromGrams(it)) }
            }.build()
        }

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        fun hydration(w: HydrationEntry, version: Long): HydrationRecord {
            val start = Instant.ofEpochMilli(w.loggedAt)
            return HydrationRecord.Builder(metadata(waterId(w.id), version), start, start.plus(Duration.ofMinutes(1)),
                Volume.fromLiters(w.ml / 1000.0)).build()
        }

        fun mealType(meal: Meal): Int = when (meal) {
            Meal.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
            Meal.LUNCH -> MealType.MEAL_TYPE_LUNCH
            Meal.DINNER -> MealType.MEAL_TYPE_DINNER
            Meal.SNACK, Meal.PRE_TRAINING, Meal.POST_TRAINING -> MealType.MEAL_TYPE_SNACK
        }
    }
}
