package com.cruxcoach.app.sync

import kotlin.concurrent.Volatile
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryLevelDidChangeNotification
import platform.UIKit.UIDeviceBatteryState
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification

/**
 * [BatteryStatus] from UIKit. iOS reports the battery only while monitoring is switched
 * on, so [start] does that (on the main thread) and follows the level and charging
 * notifications. [onNotLow] runs when a low battery recovers (charger plugged in, or
 * above the threshold again): WorkManager starts a waiting worker then, so the automatic
 * refresh looks whether it is due.
 */
class IosBatteryStatus(private val onNotLow: () -> Unit = {}) : BatteryStatus {
    @Volatile
    private var low = false

    private var started = false

    /** Main thread only. */
    fun start() {
        if (started) return
        started = true
        UIDevice.currentDevice.batteryMonitoringEnabled = true
        read()
        val center = NSNotificationCenter.defaultCenter
        for (name in listOf(UIDeviceBatteryLevelDidChangeNotification, UIDeviceBatteryStateDidChangeNotification)) {
            center.addObserverForName(name, null, NSOperationQueue.mainQueue) { _ ->
                val wasLow = low
                read()
                if (wasLow && !low) onNotLow()
            }
        }
    }

    override fun isLow(): Boolean = low

    private fun read() {
        val device = UIDevice.currentDevice
        val state = device.batteryState
        val charging = state == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
            state == UIDeviceBatteryState.UIDeviceBatteryStateFull
        low = BatteryStatus.isLow(device.batteryLevel, charging)
    }
}
