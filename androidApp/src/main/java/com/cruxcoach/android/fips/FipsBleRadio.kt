package com.cruxcoach.android.fips

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicInteger

/** Android API 29+ L2CAP CoC radio owned by Kotlin and driven by FIPS over JNI. */
@SuppressLint("MissingPermission")
internal class FipsBleRadio(
    context: Context,
    private val realm: FipsRealmContext,
    private val onNearbyMesh: (FipsNearbyMesh) -> Unit = {},
) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val io = Executors.newCachedThreadPool()
    private val retry = Executors.newSingleThreadScheduledExecutor()
    private val channels = ConcurrentHashMap<Long, BluetoothSocket>()
    @Volatile private var bridge = 0L
    @Volatile private var stopped = false
    private var server: BluetoothServerSocket? = null
    private var advertiserCallback: AdvertiseCallback? = null
    private var scannerCallback: ScanCallback? = null
    private var advertisedPsm = 0
    private var advertiseRetries = 0
    private var scanRetries = 0
    @Volatile private var localNonce = newNonce()
    private var nonceRotatedAtMs = System.currentTimeMillis()
    private var nonceRotation: ScheduledFuture<*>? = null
    private var advertiseRetry: ScheduledFuture<*>? = null
    private var scanRetry: ScheduledFuture<*>? = null
    private val advertiseGeneration = AtomicInteger(0)
    private val scanGeneration = AtomicInteger(0)
    private val observedNonceTags = ConcurrentHashMap<String, Long>()
    private val observedAddresses = ConcurrentHashMap<String, Long>()
    private val lastDiscoveryLog = ConcurrentHashMap<String, Long>()

    fun bindBridge(handle: Long) {
        bridge = handle
        FipsDebugLog.event("radio", "bridge_bound", "handle" to handle,
            "realm" to FipsDebugLog.id(realm.realmId), "cell" to FipsDebugLog.id(realm.boardCellId))
    }

    @RequiresApi(29)
    fun listen(): Int = try {
        if (stopped) return 0
        val socket = adapter?.listenUsingInsecureL2capChannel() ?: return 0
        server = socket
        io.execute { acceptLoop(socket) }
        FipsDebugLog.event("radio", "l2cap_listening", "psm" to socket.psm)
        socket.psm
    } catch (e: Exception) {
        Log.e(TAG, "L2CAP listen failed", e)
        FipsDebugLog.warning("radio", "l2cap_listen_failed", "error" to (e.message ?: e.javaClass.simpleName))
        0
    }

    @RequiresApi(29)
    fun connect(connectId: Long, address: String, psm: Int) {
        if (stopped) return
        FipsDebugLog.event("radio", "outbound_connect_begin", "connectId" to connectId,
            "address" to address.substringAfter('/'), "psm" to psm)
        io.execute {
            val mac = address.substringAfter('/', address)
            try {
                val socket = adapter?.getRemoteDevice(mac)?.createInsecureL2capChannel(psm)
                    ?: throw IOException("BLE unavailable")
                socket.connect()
                val id = NativeFips.bleDeliverConnectResult(bridge, connectId, true, address,
                    sendMtu(socket), receiveMtu(socket))
                FipsDebugLog.event("radio", "outbound_connect_result", "connectId" to connectId,
                    "channel" to id, "address" to mac, "sendMtu" to sendMtu(socket),
                    "receiveMtu" to receiveMtu(socket))
                if (id > 0) startChannel(id, socket) else socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "L2CAP connect failed: ${e.message}")
                FipsDebugLog.warning("radio", "outbound_connect_failed", "connectId" to connectId,
                    "address" to mac, "psm" to psm, "error" to (e.message ?: e.javaClass.simpleName))
                NativeFips.bleDeliverConnectResult(bridge, connectId, false, address, 0, 0)
            }
        }
    }

    fun startAdvertising(psm: Int) {
        if (stopped) return
        advertisedPsm = psm
        stopAdvertising()
        val generation = advertiseGeneration.incrementAndGet()
        if (System.currentTimeMillis() - nonceRotatedAtMs >= NONCE_ROTATE_MS) {
            localNonce = newNonce()
            nonceRotatedAtMs = System.currentTimeMillis()
        }
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val psmBytes = FipsAdvertisementCodec.encode(realm, psm, DirectJoinProof.nonceTag(localNonce))
        // V2 uses the complete 128-bit BoardCell id. Advertising it only as
        // service data (without a duplicate service-UUID list entry) keeps the
        // connectable legacy advertisement within Android's 31-byte budget.
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceData(CRUXCOACH_FIPS_UUID, psmBytes).build()
        val scanResponse = realm.meshName?.trim()?.takeIf(String::isNotEmpty)?.let { name ->
            AdvertiseData.Builder().addServiceData(
                CRUXCOACH_FIPS_NAME_UUID, meshNameBytes(name),
            ).build()
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTimeout(0).build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(value: AdvertiseSettings?) {
                if (stopped || generation != advertiseGeneration.get()) return
                advertiseRetries = 0
                FipsDebugLog.event("radio", "advertising_started", "psm" to psm,
                    "realmTag" to FipsDebugLog.tag(realm.realmTag), "cellTag" to FipsDebugLog.tag(realm.cellTag),
                    "nonceTag" to FipsDebugLog.tag(DirectJoinProof.nonceTag(localNonce)))
                nonceRotation = retry.schedule({
                    if (!stopped && generation == advertiseGeneration.get()) {
                        localNonce = newNonce()
                        nonceRotatedAtMs = System.currentTimeMillis()
                        startAdvertising(advertisedPsm)
                    }
                }, NONCE_ROTATE_MS, TimeUnit.MILLISECONDS)
            }
            override fun onStartFailure(errorCode: Int) {
                if (stopped || generation != advertiseGeneration.get()) return
                Log.w(TAG, "advertise failed $errorCode")
                FipsDebugLog.warning("radio", "advertising_failed", "code" to errorCode, "psm" to psm)
                scheduleAdvertiseRetry(generation)
            }
        }
        advertiserCallback = callback
        FipsDebugLog.event("radio", "advertising_start_requested", "psm" to psm,
            "payloadBytes" to psmBytes.size)
        runCatching {
            if (scanResponse != null) advertiser.startAdvertising(settings, data, scanResponse, callback)
            else advertiser.startAdvertising(settings, data, callback)
        }
            .onFailure { scheduleAdvertiseRetry(generation) }
    }

    private fun scheduleAdvertiseRetry(generation: Int) {
        if (stopped || generation != advertiseGeneration.get()) return
        val seconds = minOf(60L, 5L shl minOf(advertiseRetries++, 3))
        FipsDebugLog.event("radio", "advertising_retry_scheduled", "delaySeconds" to seconds,
            "attempt" to advertiseRetries)
        advertiseRetry?.cancel(false)
        advertiseRetry = retry.schedule({
            if (!stopped && generation == advertiseGeneration.get()) startAdvertising(advertisedPsm)
        }, seconds, TimeUnit.SECONDS)
    }

    fun stopAdvertising() {
        advertiseGeneration.incrementAndGet()
        nonceRotation?.cancel(false); nonceRotation = null
        advertiseRetry?.cancel(false); advertiseRetry = null
        advertiserCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertiserCallback = null
        FipsDebugLog.event("radio", "advertising_stopped")
    }

    fun startScanning() {
        if (stopped) return
        stopScanning()
        val generation = scanGeneration.incrementAndGet()
        val scanner = adapter?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) = handleResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleResult)
            override fun onScanFailed(errorCode: Int) {
                if (stopped || generation != scanGeneration.get()) return
                val floor = if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) 30L else 0L
                val seconds = maxOf(floor, minOf(60L, 5L shl minOf(scanRetries++, 3)))
                FipsDebugLog.warning("radio", "scan_failed", "code" to errorCode,
                    "retrySeconds" to seconds, "attempt" to scanRetries)
                scheduleScanRetry(generation, seconds)
            }
        }
        scannerCallback = callback
        val filter = ScanFilter.Builder().setServiceData(CRUXCOACH_FIPS_UUID, byteArrayOf()).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        FipsDebugLog.event("radio", "scan_started", "mode" to "low_power",
            "realmTag" to FipsDebugLog.tag(realm.realmTag), "cellTag" to FipsDebugLog.tag(realm.cellTag))
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure { FipsDebugLog.warning("radio", "scan_start_failed",
                "error" to (it.message ?: it.javaClass.simpleName)).also {
                    scheduleScanRetry(generation, 5L)
                } }
    }

    private fun scheduleScanRetry(generation: Int, seconds: Long) {
        if (stopped || generation != scanGeneration.get()) return
        scanRetry?.cancel(false)
        scanRetry = retry.schedule({
            if (!stopped && generation == scanGeneration.get()) startScanning()
        }, seconds, TimeUnit.SECONDS)
    }

    private fun handleResult(result: ScanResult) {
        scanRetries = 0
        val bytes = result.scanRecord?.getServiceData(CRUXCOACH_FIPS_UUID) ?: return
        val advertisement = FipsAdvertisementCodec.decode(bytes) ?: return
        val boardName = result.scanRecord?.getServiceData(CRUXCOACH_FIPS_NAME_UUID)
            ?.decodeToString()?.trim()?.takeIf(String::isNotEmpty)
        val advertisedRealmTag = advertisement.realmTag
        val advertisedCellTag = advertisement.cellTag
        val matchesActiveRealm = advertisedRealmTag.contentEquals(realm.realmTag) &&
            advertisedCellTag.contentEquals(realm.cellTag)
        onNearbyMesh(
            FipsNearbyMesh(
                address = result.device.address,
                realmTag = advertisedRealmTag.toHex(),
                cellTag = advertisedCellTag.toHex(),
                rssi = result.rssi,
                lastSeenMs = System.currentTimeMillis(),
                matchesActiveRealm = matchesActiveRealm,
                joinableBoardCellId = advertisement.joinableBoardCellId,
                boardName = boardName,
            )
        )
        // Discovery may describe foreign CruxCoach meshes in the UI, but only
        // the exact active realm/cell is handed to FIPS for connection.
        if (!matchesActiveRealm) return
        val nonceTag = advertisement.nonceTag.toHex()
        observedNonceTags[nonceTag] = System.currentTimeMillis()
        observedNonceTags.entries.removeAll { System.currentTimeMillis() - it.value > DirectJoinProof.MAX_AGE_MS }
        observedAddresses[result.device.address.uppercase()] = System.currentTimeMillis()
        observedAddresses.entries.removeAll { System.currentTimeMillis() - it.value > DirectJoinProof.MAX_AGE_MS }
        val psm = advertisement.psm
        if (psm > 0) {
            val now = System.currentTimeMillis()
            val previousLog = lastDiscoveryLog.put(result.device.address, now) ?: 0L
            if (now - previousLog >= DISCOVERY_LOG_INTERVAL_MS) {
                FipsDebugLog.event("radio", "matching_peer_discovered", "address" to result.device.address,
                    "psm" to psm, "rssi" to result.rssi, "nonceTag" to nonceTag)
            }
            NativeFips.bleDeliverScan(bridge, "$ADAPTER/${result.device.address}", psm, result.rssi)
        }
    }

    fun stopScanning() {
        scanGeneration.incrementAndGet()
        scanRetry?.cancel(false); scanRetry = null
        scannerCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scannerCallback = null
        FipsDebugLog.event("radio", "scan_stopped")
    }

    fun localNonceHex(): String = localNonce.toHex()

    fun validateDirectJoin(hello: DirectJoinHello): Boolean {
        return DirectJoinProof.validate(realm, hello, observedNonceTags, directBleEdge = true,
            nowMs = System.currentTimeMillis())
    }

    fun closeChannel(id: Long) { channels.remove(id)?.let { runCatching { it.close() } } }

    fun shutdown() {
        FipsDebugLog.event("radio", "shutdown", "channels" to channels.size,
            "observedPeers" to observedAddresses.size)
        stopped = true
        stopScanning(); stopAdvertising()
        runCatching { server?.close() }; server = null
        channels.keys.toList().forEach(::closeChannel)
        io.shutdownNow(); retry.shutdownNow()
        runCatching { io.awaitTermination(EXECUTOR_STOP_SECONDS, TimeUnit.SECONDS) }
        runCatching { retry.awaitTermination(EXECUTOR_STOP_SECONDS, TimeUnit.SECONDS) }
    }

    private fun acceptLoop(listener: BluetoothServerSocket) {
        while (!stopped) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            val seenAt = observedAddresses[socket.remoteDevice.address.uppercase()]
            if (seenAt == null || System.currentTimeMillis() - seenAt > DirectJoinProof.MAX_AGE_MS) {
                // Inbound L2CAP is not discovery: require a matching, recent local scan first.
                runCatching { socket.close() }
                FipsDebugLog.warning("radio", "inbound_rejected", "address" to socket.remoteDevice.address,
                    "reason" to "no recent matching local scan")
                continue
            }
            val id = NativeFips.bleDeliverInbound(bridge,
                "$ADAPTER/${socket.remoteDevice.address}", sendMtu(socket), receiveMtu(socket))
            FipsDebugLog.event("radio", "inbound_accepted", "address" to socket.remoteDevice.address,
                "channel" to id, "sendMtu" to sendMtu(socket), "receiveMtu" to receiveMtu(socket))
            if (id > 0) startChannel(id, socket) else runCatching { socket.close() }
        }
    }

    private fun startChannel(id: Long, socket: BluetoothSocket) {
        channels[id] = socket
        FipsDebugLog.event("radio", "channel_open", "channel" to id,
            "address" to socket.remoteDevice.address, "channels" to channels.size)
        io.execute { reader(id, socket) }
        io.execute { writer(id, socket) }
    }

    private fun reader(id: Long, socket: BluetoothSocket) {
        val buffer = ByteArray(MAX_PACKET)
        try {
            while (!stopped) {
                val count = socket.inputStream.read(buffer)
                if (count < 0 || !NativeFips.bleChannelDeliverRecv(bridge, id, buffer, count)) break
            }
        } catch (_: IOException) { } finally { channelGone(id) }
    }

    private fun writer(id: Long, socket: BluetoothSocket) {
        val buffer = ByteArray(MAX_PACKET)
        try {
            // Channel close disconnects the native sender and wakes this wait;
            // a long fallback timeout avoids one idle wakeup per channel/sec.
            while (!stopped) when (val count = NativeFips.bleChannelNextSend(
                bridge, id, buffer, OUTBOUND_WAIT_MS,
            )) {
                -1 -> break
                0 -> Unit
                else -> { socket.outputStream.write(buffer, 0, count); socket.outputStream.flush() }
            }
        } catch (_: IOException) { } finally { channelGone(id) }
    }

    private fun channelGone(id: Long) {
        closeChannel(id)
        FipsDebugLog.event("radio", "channel_closed", "channel" to id, "remaining" to channels.size)
        runCatching { NativeFips.bleChannelClosed(bridge, id) }
    }

    private fun sendMtu(socket: BluetoothSocket) = socket.maxTransmitPacketSize.coerceIn(20, 1_500)
    private fun receiveMtu(socket: BluetoothSocket) = socket.maxReceivePacketSize.coerceIn(20, 1_500)

    companion object {
        private const val TAG = "CruxFipsRadio"
        private const val ADAPTER = "ble0"
        private const val MAX_PACKET = 8_192
        private const val NONCE_ROTATE_MS = 30_000L
        private const val DISCOVERY_LOG_INTERVAL_MS = 10_000L
        private const val EXECUTOR_STOP_SECONDS = 2L
        private const val OUTBOUND_WAIT_MS = 60_000
        // A compact CruxCoach-specific 16-bit-shaped UUID keeps the legacy 31-byte
        // advertising budget; full ids and the full nonce are authenticated in CCJ1.
        private val CRUXCOACH_FIPS_UUID = ParcelUuid(UUID.fromString("0000ccf1-0000-1000-8000-00805f9b34fb"))
        private val CRUXCOACH_FIPS_NAME_UUID = ParcelUuid(UUID.fromString("0000ccf2-0000-1000-8000-00805f9b34fb"))
    }


    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun newNonce() = ByteArray(16).also(SecureRandom()::nextBytes)
    private fun meshNameBytes(value: String): ByteArray {
        var result = ByteArray(0)
        value.codePoints().forEach { codePoint ->
            val candidate = result + String(Character.toChars(codePoint)).encodeToByteArray()
            if (candidate.size <= 24) result = candidate
        }
        return result
    }
}
