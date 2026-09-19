package com.cruxcoach.app.settings

import com.cruxcoach.app.platform.KeyValueStore

/**
 * Key names are identical to Android `PreferenceKeys`. DataStore is typed;
 * [KeyValueStore] holds strings, so ints are decimal text, booleans are
 * "true"/"false" and enums are their `name`, exactly the values Android stores.
 */
object SettingsKeys {
    const val GRADE_SCALE = "grade_scale"
    const val CLIMB_HISTORY_RETENTION_DAYS = "climb_history_retention_days"
}

enum class HistoryRetention(val days: Int) {
    OFF(0),
    DAYS_30(30),
    DAYS_90(90),
    DAYS_365(365);

    companion object {
        fun fromDays(days: Int?): HistoryRetention = entries.firstOrNull { it.days == days } ?: DAYS_30
    }
}

internal fun KeyValueStore.readGradeScale(): GradeScale =
    GradeScale.entries.firstOrNull { it.name == safeGet(SettingsKeys.GRADE_SCALE) } ?: GradeScale.FRENCH

internal fun KeyValueStore.readHistoryRetention(): HistoryRetention =
    HistoryRetention.fromDays(safeGet(SettingsKeys.CLIMB_HISTORY_RETENTION_DAYS)?.toIntOrNull())

/** A host store must not throw, but a presenter must survive one that does. */
internal fun KeyValueStore.safeGet(key: String): String? = try {
    getString(key)
} catch (_: Exception) {
    null
}

internal fun KeyValueStore.safePut(key: String, value: String?): Boolean = try {
    putString(key, value)
    true
} catch (_: Exception) {
    false
}
