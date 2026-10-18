package com.cruxcoach.app.foodvision

import kotlin.concurrent.Volatile
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
 * [VisionNetwork] on the Network framework's path monitor. "Expensive" is
 * Apple's word for what Android calls metered (mobile data, a phone's
 * hotspot); Low Data Mode ("constrained") counts as metered too, so a model
 * of gigabytes never goes over it without the user's yes.
 *
 * Starts as [VisionNetwork.NONE] until the first path update, which the
 * system delivers right after start: a transfer then waits a moment instead
 * of guessing.
 */
class IosVisionNetwork : VisionNetwork {
    @Volatile private var kind: String = VisionNetwork.NONE
    private val lock = NSLock()
    private val queue = dispatch_queue_create("com.cruxcoach.foodvision.network", null)
    private var monitor: nw_path_monitor_t = null

    fun start() {
        lock.lock()
        try {
            if (monitor != null) return
            val created = nw_path_monitor_create() ?: return
            nw_path_monitor_set_queue(created, queue)
            nw_path_monitor_set_update_handler(created) { path ->
                kind = when {
                    path == null || nw_path_get_status(path) != nw_path_status_satisfied -> VisionNetwork.NONE
                    nw_path_is_expensive(path) || nw_path_is_constrained(path) -> VisionNetwork.METERED
                    else -> VisionNetwork.UNMETERED
                }
            }
            nw_path_monitor_start(created)
            monitor = created
        } finally {
            lock.unlock()
        }
    }

    override fun current(): String = kind
}
