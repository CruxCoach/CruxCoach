package com.cruxcoach.android.ble

/**
 * Keeps session command ordering explicit without adding an authentication
 * ceremony: a connected device must send JOIN before it can mutate the queue.
 * JOIN is intentionally open for a host-published session.
 */
internal class SessionCommandGate(private val nanoTime: () -> Long = System::nanoTime) {
    private data class Budget(var tokens: Double = 120.0, var at: Long)
    private val budgets = mutableMapOf<String, Budget>()

    /** Allows bursts/legacy queue transfers, bounds sustained command flooding. */
    @Synchronized
    fun allowMutation(deviceAddress: String): Boolean {
        if (deviceAddress !in joinedDevices) return false
        val now = nanoTime()
        val budget = budgets.getOrPut(deviceAddress) { Budget(at = now) }
        budget.tokens = minOf(120.0, budget.tokens + (now - budget.at).coerceAtLeast(0) / 1e9 * 30)
        budget.at = now
        if (budget.tokens < 1) return false
        budget.tokens -= 1
        return true
    }

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
        budgets.remove(deviceAddress)
        joinedDevices.remove(deviceAddress)
        contextCapableDevices.remove(deviceAddress)
    }

    @Synchronized
    fun clear() {
        budgets.clear()
        joinedDevices.clear()
        contextCapableDevices.clear()
    }
}
