package com.cruxcoach.app.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSLock
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * [NetworkCost] on the Network framework's path monitor, the same reading as the meal-photo
 * model download's `IosVisionNetwork`: "expensive" (cellular, a phone's hotspot) and
 * "constrained" (Low Data Mode) are metered, so a catalogue of ~130 MB never goes over them
 * without the user's yes. Pushes every change, so a queued download starts when Wi-Fi is back.
 *
 * [NetworkCost.UNKNOWN] until the first path update, which the system delivers right after
 * [start]; the gate treats that as "ask", never as Wi-Fi.
 */
class IosNetworkCost : NetworkCost {
    private val current = MutableStateFlow(NetworkCost.UNKNOWN)
    override val kind: StateFlow<String> = current.asStateFlow()

    private val lock = NSLock()
    private val queue = dispatch_queue_create("com.cruxcoach.catalogue.network", null)
    private var monitor: nw_path_monitor_t = null

    fun start() {
        lock.lock()
        try {
            if (monitor != null) return
            val created = nw_path_monitor_create() ?: return
            nw_path_monitor_set_queue(created, queue)
            nw_path_monitor_set_update_handler(created) { path ->
                current.value = when {
                    path == null || nw_path_get_status(path) != nw_path_status_satisfied -> NetworkCost.NONE
                    nw_path_is_expensive(path) || nw_path_is_constrained(path) -> NetworkCost.METERED
                    else -> NetworkCost.UNMETERED
                }
            }
            nw_path_monitor_start(created)
            monitor = created
        } finally {
            lock.unlock()
        }
    }
}
