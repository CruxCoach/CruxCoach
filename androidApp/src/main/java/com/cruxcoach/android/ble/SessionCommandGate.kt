package com.cruxcoach.android.ble

/**
 * Keeps session command ordering explicit without adding an authentication
 * ceremony: a connected device must send JOIN before it can mutate the queue.
 * JOIN is intentionally open for a host-published session.
 */
internal class SessionCommandGate {
    private val joinedDevices = mutableSetOf<String>()
    private val contextCapableDevices = mutableSetOf<String>()

    @Synchronized
    fun join(deviceAddress: String): Boolean = joinedDevices.add(deviceAddress)

    @Synchronized
    fun hasJoined(deviceAddress: String): Boolean = deviceAddress in joinedDevices

    /** Sticky for exactly this connection/session lifetime. Once a peer has
     * proved it can bind mutations to semantic session context, it may not
     * selectively downgrade later writes to raw legacy commands. */
    @Synchronized
    fun markContextCapable(deviceAddress: String) {
        if (deviceAddress in joinedDevices) contextCapableDevices += deviceAddress
    }

    @Synchronized
    fun isContextCapable(deviceAddress: String): Boolean =
        deviceAddress in contextCapableDevices

    @Synchronized
    fun remove(deviceAddress: String) {
        joinedDevices.remove(deviceAddress)
        contextCapableDevices.remove(deviceAddress)
    }

    @Synchronized
    fun clear() {
        joinedDevices.clear()
        contextCapableDevices.clear()
    }
}
