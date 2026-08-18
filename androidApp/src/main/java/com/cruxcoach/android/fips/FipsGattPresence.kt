package com.cruxcoach.android.fips

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

/**
 * Keeps an otherwise data-less GATT edge beside an active FIPS L2CAP edge.
 *
 * GrapheneOS' Bluetooth auto-off checks classic profile state plus
 * [BluetoothProfile.GATT], but Android does not expose an active L2CAP CoC
 * through either check. Mesh frames and identity remain exclusively on the
 * Noise-authenticated L2CAP channel; this service has no characteristics and
 * transports no app data.
 */
@SuppressLint("MissingPermission")
internal class FipsGattPresence(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val leases = FipsGattPresenceLeaseBook()
    private val clients = mutableMapOf<String, BluetoothGatt>()
    private var server: BluetoothGattServer? = null
    @Volatile private var stopped = false

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && !leases.isActive(address)) {
                    FipsDebugLog.warning(
                        "gatt_presence",
                        "unexpected_peer_rejected",
                        "address" to address,
                    )
                    runCatching { server?.cancelConnection(device) }
                    return@post
                }
                FipsDebugLog.event(
                    "gatt_presence",
                    "server_state_changed",
                    "address" to address,
                    "status" to status,
                    "state" to newState,
                )
            }
        }
    }

    fun start() {
        main.post { ensureServer() }
    }

    fun channelOpened(address: String) {
        val change = leases.acquire(address)
        if (change.connect) main.post {
            ensureServer()
            ensureClient(address)
        }
    }

    fun channelClosed(address: String) {
        val change = leases.release(address)
        if (change.disconnect) main.post { closeClient(address, "last_l2cap_channel_closed") }
    }

    fun shutdown() {
        stopped = true
        leases.clear()
        main.post {
            clients.keys.toList().forEach { closeClient(it, "runtime_shutdown") }
            runCatching { server?.close() }
            server = null
            FipsDebugLog.event("gatt_presence", "stopped")
        }
    }

    private fun ensureServer() {
        if (stopped || server != null || manager == null) return
        runCatching {
            manager.openGattServer(appContext, serverCallback)?.also { opened ->
                server = opened
                val service = BluetoothGattService(
                    SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY,
                )
                val accepted = opened.addService(service)
                FipsDebugLog.event(
                    "gatt_presence",
                    "server_started",
                    "serviceAccepted" to accepted,
                )
            } ?: FipsDebugLog.warning("gatt_presence", "server_start_failed", "reason" to "null")
        }.onFailure {
            FipsDebugLog.warning(
                "gatt_presence",
                "server_start_failed",
                "reason" to (it.message ?: it.javaClass.simpleName),
            )
        }
    }

    private fun ensureClient(address: String) {
        if (stopped || !leases.isActive(address) || clients.containsKey(address)) return
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                main.post {
                    if (clients[address] !== gatt) {
                        runCatching { gatt.close() }
                        return@post
                    }
                    if (newState == BluetoothProfile.STATE_CONNECTED && leases.isActive(address)) {
                        FipsDebugLog.event(
                            "gatt_presence",
                            "client_connected",
                            "address" to address,
                            "status" to status,
                        )
                        return@post
                    }
                    clients.remove(address, gatt)
                    runCatching { gatt.close() }
                    FipsDebugLog.event(
                        "gatt_presence",
                        "client_disconnected",
                        "address" to address,
                        "status" to status,
                        "state" to newState,
                    )
                    if (!stopped && leases.isActive(address)) {
                        main.postDelayed({ ensureClient(address) }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }
        runCatching {
            device.connectGatt(
                appContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
            )
        }.onSuccess { gatt ->
            if (gatt == null) {
                scheduleReconnect(address, "connect_gatt_returned_null")
            } else {
                clients[address] = gatt
                FipsDebugLog.event("gatt_presence", "client_connecting", "address" to address)
            }
        }.onFailure {
            scheduleReconnect(address, it.message ?: it.javaClass.simpleName)
        }
    }

    private fun scheduleReconnect(address: String, reason: String) {
        FipsDebugLog.warning(
            "gatt_presence",
            "client_connect_failed",
            "address" to address,
            "reason" to reason,
        )
        if (!stopped && leases.isActive(address)) {
            main.postDelayed({ ensureClient(address) }, RECONNECT_DELAY_MS)
        }
    }

    private fun closeClient(address: String, reason: String) {
        val gatt = clients.remove(address) ?: return
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        FipsDebugLog.event(
            "gatt_presence",
            "client_closed",
            "address" to address,
            "reason" to reason,
        )
    }

    companion object {
        private const val RECONNECT_DELAY_MS = 2_000L
        private val SERVICE_UUID = UUID.fromString("0000ccf3-0000-1000-8000-00805f9b34fb")
    }
}

internal data class FipsGattPresenceLeaseChange(
    val connect: Boolean = false,
    val disconnect: Boolean = false,
)

/** Reference-counts duplicate/cross-probed L2CAP channels by rotating BLE address. */
internal class FipsGattPresenceLeaseBook {
    private val counts = mutableMapOf<String, Int>()

    @Synchronized
    fun acquire(address: String): FipsGattPresenceLeaseChange {
        val previous = counts[address] ?: 0
        counts[address] = previous + 1
        return FipsGattPresenceLeaseChange(connect = previous == 0)
    }

    @Synchronized
    fun release(address: String): FipsGattPresenceLeaseChange {
        val previous = counts[address] ?: return FipsGattPresenceLeaseChange()
        if (previous > 1) {
            counts[address] = previous - 1
            return FipsGattPresenceLeaseChange()
        }
        counts.remove(address)
        return FipsGattPresenceLeaseChange(disconnect = true)
    }

    @Synchronized fun isActive(address: String): Boolean = counts.containsKey(address)

    @Synchronized fun clear() = counts.clear()
}
