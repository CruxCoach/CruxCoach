package com.cruxcoach.app.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * Live reachability of the host. [isOnline] must follow the system as it
 * changes (not only when a request fails), otherwise an offline banner stays
 * up after Wi-Fi returns.
 *
 * "Online" means the system has a usable network path; it is a hint for UI and
 * for retry scheduling, never proof that a particular relay is reachable.
 */
interface ConnectivityMonitor {
    val isOnline: StateFlow<Boolean>

    /** Idempotent. */
    fun start()

    /** Idempotent. [isOnline] keeps its last value; [start] may be called again. */
    fun stop()
}
