package com.cruxcoach.app.nutrition

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.platform.WallClock
import com.cruxcoach.app.settings.SyncInterval
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import kotlin.coroutines.resume

/**
 * When the product database looks for a newer extract on iOS: as on Android
 * only with the user's catalogue sync switched on and on an unmetered network
 * (not cellular, a hotspot or Low Data Mode), here at most once a day when
 * nutrition opens – iOS has no background board sync to ride along with.
 */
@OptIn(ExperimentalForeignApi::class)
internal object IosFoodProductsUpdate {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun refreshIfDue(sync: FoodProductsSync, interval: SyncInterval, keyValues: KeyValueStore, clock: WallClock) {
        if (interval == SyncInterval.MANUAL) return
        val now = clock.epochSeconds()
        val last = keyValues.getString(FoodProductsSync.KEY_LAST_CHECK)?.toLongOrNull()
        // A marker in the future means the clock moved back; check rather than wait for it.
        if (last != null && now - last in 0 until FoodProductsSync.CHECK_INTERVAL_SECONDS) return
        // Marked before the check: opening nutrition twice must not start two (the next chance is a day later).
        keyValues.putString(FoodProductsSync.KEY_LAST_CHECK, now.toString())
        scope.launch {
            if (!isUnmetered()) return@launch
            val result = sync.sync()
            println("IosFoodProductsUpdate: $result")
        }
    }

    /** One look at the current network path. */
    private suspend fun isUnmetered(): Boolean = suspendCancellableCoroutine { continuation ->
        val monitor = nw_path_monitor_create()
        if (monitor == null) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        var answered = false
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.cruxcoach.nutrition.network", null))
        nw_path_monitor_set_update_handler(monitor) { path ->
            // The handler runs on the serial queue above; only its first call answers.
            if (!answered) {
                answered = true
                val ok = path != null && nw_path_get_status(path) == nw_path_status_satisfied &&
                    !nw_path_is_expensive(path) && !nw_path_is_constrained(path)
                nw_path_monitor_cancel(monitor)
                if (continuation.isActive) continuation.resume(ok)
            }
        }
        nw_path_monitor_start(monitor)
    }

    /** Reads a text file line by line with a 64 KB buffer; never the whole file. */
    fun lines(path: String) = OffProductStore.LineReader { action ->
        val file = fopen(path, "r") ?: error("cannot open $path")
        try {
            memScoped {
                val size = 64 * 1024
                val buffer = allocArray<ByteVar>(size)
                val pending = StringBuilder()
                var going = true
                while (going) {
                    val read = fgets(buffer, size, file) ?: break
                    val part = read.toKString()
                    if (part.endsWith("\n")) {
                        pending.append(part, 0, part.length - 1)
                        going = action(pending.toString().removeSuffix("\r"))
                        pending.clear()
                    } else {
                        // A line longer than the buffer comes in pieces.
                        pending.append(part)
                    }
                }
                if (going && pending.isNotEmpty()) action(pending.toString().removeSuffix("\r"))
            }
        } finally {
            fclose(file)
        }
    }
}
