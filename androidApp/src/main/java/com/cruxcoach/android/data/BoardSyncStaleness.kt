package com.cruxcoach.android.data

import kotlin.time.Clock

/** Pure, zone-independent staleness policy for automatic board sync. */
internal fun isBoardSyncStale(
    lastSyncEpochMillis: Long?,
    interval: SyncInterval,
    nowEpochMillis: Long = Clock.System.now().toEpochMilliseconds(),
): Boolean {
    if (interval == SyncInterval.MANUAL) return false
    if (lastSyncEpochMillis == null) return true

    val ageMillis = nowEpochMillis - lastSyncEpochMillis
    if (ageMillis < 0L) return true // corrupt/future value must not disable sync indefinitely

    val thresholdMillis = when (interval) {
        SyncInterval.DAILY -> 24L * 60 * 60 * 1_000
        SyncInterval.WEEKLY -> 7L * 24 * 60 * 60 * 1_000
        SyncInterval.MANUAL -> return false
    }
    return ageMillis >= thresholdMillis
}
