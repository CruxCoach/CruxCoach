package com.cruxcoach.app.sync

/**
 * Android's `setRequiresBatteryNotLow(true)` on the periodic `BoardSyncWorker`: automatic
 * catalogue downloads wait while the battery is low. WorkManager's `BatteryNotLowTracker`
 * calls a battery low at or below 15 % that is not charging; an unknown reading (a device
 * without a battery, the simulator) is not low.
 */
interface BatteryStatus {
    fun isLow(): Boolean

    companion object {
        /** WorkManager's `BATTERY_LOW_THRESHOLD`. */
        const val LOW_FRACTION = 0.15f

        /** [level] in 0..1, negative when unknown. */
        fun isLow(level: Float, charging: Boolean): Boolean =
            !charging && level >= 0f && level <= LOW_FRACTION

        val NEVER_LOW: BatteryStatus = object : BatteryStatus {
            override fun isLow(): Boolean = false
        }
    }
}
