package com.cruxcoach.android.ble

/**
 * Keeps session command ordering explicit without adding an authentication
 * ceremony: a connected device must send JOIN before it can mutate the queue.
 * JOIN is intentionally open for a host-published session.
 */
internal class SessionCommandGate {
    private val joinedDevices = mutableSetOf<String>()

    @Synchronized
    fun join(deviceAddress: String): Boolean = joinedDevices.add(deviceAddress)

    @Synchronized
    fun hasJoined(deviceAddress: String): Boolean = deviceAddress in joinedDevices

    @Synchronized
    fun remove(deviceAddress: String) {
        joinedDevices.remove(deviceAddress)
    }

    @Synchronized
    fun clear() {
        joinedDevices.clear()
    }
}
