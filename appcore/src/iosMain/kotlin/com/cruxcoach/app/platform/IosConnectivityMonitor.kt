package com.cruxcoach.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSLock
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * [ConnectivityMonitor] on the Network framework's path monitor, which pushes
 * every path change (Wi-Fi back, cellular lost, VPN up) to its update handler.
 *
 * The handler runs on a private serial dispatch queue; `MutableStateFlow` is
 * thread-safe, and observers that need the main thread get it through
 * `StateFlow.watch`.
 *
 * Starts optimistic (`true`): the first path update arrives right after
 * [start], and showing "offline" before the system has said so would be the
 * false banner this class exists to prevent.
 */
class IosConnectivityMonitor : ConnectivityMonitor {
    private val online = MutableStateFlow(true)
    override val isOnline: StateFlow<Boolean> = online.asStateFlow()

    private val lock = NSLock()
    private val queue = dispatch_queue_create("com.cruxcoach.connectivity", null)
    private var monitor: nw_path_monitor_t = null

    override fun start() {
        lock.lock()
        try {
            if (monitor != null) return
            // A cancelled nw_path_monitor cannot be restarted, so each start gets a new one.
            val created = nw_path_monitor_create() ?: return
            nw_path_monitor_set_queue(created, queue)
            nw_path_monitor_set_update_handler(created) { path ->
                // nw_path_status_satisfiable (e.g. VPN on demand) is deliberately not online yet.
                online.value = path != null && nw_path_get_status(path) == nw_path_status_satisfied
            }
            nw_path_monitor_start(created)
            monitor = created
        } finally {
            lock.unlock()
        }
    }

    override fun stop() {
        lock.lock()
        try {
            val current = monitor ?: return
            nw_path_monitor_cancel(current)
            monitor = null
        } finally {
            lock.unlock()
        }
    }
}
