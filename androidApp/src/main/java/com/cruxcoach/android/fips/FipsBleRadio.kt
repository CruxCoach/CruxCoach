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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** FIPS deliberately cross-probes BLE peers and deterministically keeps one
 * direction after exchanging node keys. Suppressing scans for an established
 * member breaks that contract: a joining node whose outbound loses the
 * tie-breaker would wait forever for the member's suppressed outbound. */
internal fun shouldDeliverFipsScan(matchesActiveRealm: Boolean): Boolean = matchesActiveRealm

internal data class FipsRadioChannelClose(
    val channel: Long,
    val address: String,
    val direction: String,
    val reason: String,
    val remainingForAddress: Int,
)

/** Android API 29+ L2CAP CoC radio owned by Kotlin and driven by FIPS over JNI. */
@SuppressLint("MissingPermission")
internal class FipsBleRadio(
    context: Context,
    private val realm: FipsRealmContext,
    private val onNearbyMesh: (FipsNearbyMesh) -> Unit = {},
    private val onConnectionStage: (FipsConnectionStage) -> Unit = {},
    private val onChannelClosed: (FipsRadioChannelClose) -> Unit = {},
) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val gattPresence = FipsGattPresence(context)
    private val io = Executors.newCachedThreadPool()
    private val retry = Executors.newSingleThreadScheduledExecutor()
    private val channels = ConcurrentHashMap<Long, BluetoothSocket>()
    private val channelTraces = ConcurrentHashMap<Long, ChannelTrace>()
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
    private val lastDiscoveryLog = ConcurrentHashMap<String, Long>()
    private val dialScheduler = FipsDialScheduler()
    private val scanCoalescer = FipsScanCoalescer()
    @Volatile private var outboundSocket: BluetoothSocket? = null

    fun bindBridge(handle: Long) {
        bridge = handle
        FipsDebugLog.event("radio", "bridge_bound", "handle" to handle,
            "realm" to FipsDebugLog.id(realm.realmId), "cell" to FipsDebugLog.id(realm.boardCellId))
    }

    @RequiresApi(29)
    fun listen(): Int = try {
        if (stopped) return 0
        gattPresence.start()
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

    /**
     * Begin one outbound dial.
     *
     * The only synthesized failure here is a stopped radio, which is a real
     * one. Every other local decision either waits for the radio or abandons
     * the request silently: the FIPS revision this app runs turns a reported
     * connect failure into per-address exponential backoff, so calling a
     * scheduling decision a radio failure would silence a reachable member for
     * up to sixteen minutes. See [FipsDialScheduler].
     */
    @RequiresApi(29)
    fun connect(connectId: Long, address: String, psm: Int) {
        if (stopped) {
            NativeFips.bleDeliverConnectResult(bridge, connectId, false, address, 0, 0)
            return
        }
        io.execute {
            val mac = address.substringAfter('/', address)
            if (!awaitDialSlot(connectId, mac)) return@execute
            FipsDebugLog.event("radio", "outbound_connect_begin", "connectId" to connectId,
                "address" to mac, "psm" to psm)
            var timeout: ScheduledFuture<*>? = null
            try {
                val socket = adapter?.getRemoteDevice(mac)?.createInsecureL2capChannel(psm)
                    ?: throw IOException("BLE unavailable")
                outboundSocket = socket
                timeout = retry.schedule({
                    FipsDebugLog.warning("radio", "outbound_connect_platform_timeout",
                        "connectId" to connectId, "address" to mac)
                    runCatching { socket.close() }
                }, PLATFORM_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                socket.connect()
                val id = NativeFips.bleDeliverConnectResult(bridge, connectId, true, address,
                    sendMtu(socket), receiveMtu(socket))
                FipsDebugLog.event("radio", "outbound_connect_result", "connectId" to connectId,
                    "channel" to id, "address" to mac, "sendMtu" to sendMtu(socket),
                    "receiveMtu" to receiveMtu(socket))
                if (id > 0) startChannel(id, socket, "outbound") else {
                    FipsDebugLog.warning("radio", "channel_transport_rejected",
                        "connectId" to connectId, "address" to mac, "direction" to "outbound")
                    socket.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "L2CAP connect failed: ${e.message}")
                FipsDebugLog.warning("radio", "outbound_connect_failed", "connectId" to connectId,
                    "address" to mac, "psm" to psm, "error" to (e.message ?: e.javaClass.simpleName))
                // A real radio outcome, so it is reported as one — and the
                // coalescer stops preferring this address, or an unreachable
                // candidate would keep masking the same member's others.
                scanCoalescer.forget(mac)
                NativeFips.bleDeliverConnectResult(bridge, connectId, false, address, 0, 0)
            } finally {
                timeout?.cancel(false)
                outboundSocket = null
                dialScheduler.release(connectId)
            }
        }
    }

    /**
     * Hold the caller until a dial slot frees, or give up without answering.
     *
     * Returns whether the dial may proceed. A `false` deliberately leaves FIPS
     * unanswered: its own `connect_timeout_ms` concludes the attempt, which is
     * honest about what happened, where reporting a failure would claim the
     * radio tried and could not reach the peer.
     */
    private fun awaitDialSlot(connectId: Long, mac: String): Boolean {
        while (!stopped) {
            when (dialScheduler.admit(connectId, System.currentTimeMillis())) {
                FipsDialScheduler.Admission.DIAL -> return true
                FipsDialScheduler.Admission.ABANDON -> {
                    FipsDebugLog.warning("radio", "outbound_connect_abandoned",
                        "connectId" to connectId, "address" to mac,
                        "activeDials" to dialScheduler.activeDials(),
                        "reason" to "no dial slot within the deferral budget",
                        "reported" to "nothing; a scheduling decision is not a radio failure")
                    return false
                }
                FipsDialScheduler.Admission.DEFER -> {
                    try {
                        Thread.sleep(FipsDialScheduler.DEFER_POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        dialScheduler.release(connectId)
                        return false
                    }
                }
            }
        }
        dialScheduler.release(connectId)
        return false
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
                scheduleNonceRotation(generation, NONCE_ROTATE_MS)
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

    /**
     * Some Android 17 Bluetooth stacks tear down active L2CAP CoC sockets when
     * a connectable legacy advertiser is stopped and restarted. A fresh join
     * proof remains timestamp-bound, so it is safe to defer the advertisement
     * nonce change while a channel is carrying authenticated traffic. Rotate
     * at the first channel-free poll instead of risking the mesh itself.
     */
    private fun scheduleNonceRotation(generation: Int, delayMs: Long) {
        nonceRotation?.cancel(false)
        nonceRotation = retry.schedule({
            if (stopped || generation != advertiseGeneration.get()) return@schedule
            if (channels.isNotEmpty()) {
                FipsDebugLog.event(
                    "radio", "advertising_nonce_rotation_deferred",
                    "channels" to channels.size,
                    "nonceAgeMs" to (System.currentTimeMillis() - nonceRotatedAtMs),
                )
                scheduleNonceRotation(generation, NONCE_DEFER_POLL_MS)
                return@schedule
            }
            localNonce = newNonce()
            nonceRotatedAtMs = System.currentTimeMillis()
            FipsDebugLog.event("radio", "advertising_nonce_rotation_due", "channels" to 0)
            startAdvertising(advertisedPsm)
        }, delayMs, TimeUnit.MILLISECONDS)
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
        if (!shouldDeliverFipsScan(matchesActiveRealm)) return
        onConnectionStage(FipsConnectionStage.ADVERTISEMENT_SEEN)
        val nonceTag = advertisement.nonceTag.toHex()
        observedNonceTags[nonceTag] = System.currentTimeMillis()
        observedNonceTags.entries.removeAll { System.currentTimeMillis() - it.value > DirectJoinProof.MAX_AGE_MS }
        val psm = advertisement.psm
        if (psm > 0) {
            val now = System.currentTimeMillis()
            val logKey = "${advertisement.realmTag.toHex()}:${advertisement.cellTag.toHex()}"
            val previousLog = lastDiscoveryLog[logKey] ?: 0L
            if (now - previousLog >= DISCOVERY_LOG_INTERVAL_MS) {
                lastDiscoveryLog[logKey] = now
                FipsDebugLog.event("radio", "matching_peer_discovered", "address" to result.device.address,
                    "psm" to psm, "rssi" to result.rssi, "nonceTag" to nonceTag)
            }
            // Collapse one member's rotating addresses here rather than
            // declining the resulting dial later: a declined dial is
            // indistinguishable, to FIPS, from a peer that would not answer.
            when (
                val decision = scanCoalescer.offer(
                    realmTag = advertisedRealmTag.toHex(),
                    cellTag = advertisedCellTag.toHex(),
                    nonceTag = nonceTag,
                    address = result.device.address,
                    rssi = result.rssi,
                    nowMs = now,
                )
            ) {
                is FipsScanCoalescer.Decision.Deliver ->
                    NativeFips.bleDeliverScan(
                        bridge, "$ADAPTER/${result.device.address}", psm, result.rssi,
                    )
                is FipsScanCoalescer.Decision.Suppress ->
                    FipsDebugLog.event(
                        "radio", "scan_candidate_coalesced",
                        "address" to result.device.address,
                        "retained" to decision.retained,
                        "reason" to decision.reason,
                    )
            }
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

    /** Called by the native transport when it deliberately drops a stream. */
    fun closeChannel(id: Long) = closeChannel(id, "native_close_requested")

    fun shutdown() {
        FipsDebugLog.event("radio", "shutdown", "channels" to channels.size,
            "observedNonces" to observedNonceTags.size)
        stopped = true
        scanCoalescer.clear()
        runCatching { outboundSocket?.close() }; outboundSocket = null
        stopScanning(); stopAdvertising()
        runCatching { server?.close() }; server = null
        channels.keys.toList().forEach { closeChannel(it, "runtime_shutdown") }
        gattPresence.shutdown()
        io.shutdownNow(); retry.shutdownNow()
        runCatching { io.awaitTermination(EXECUTOR_STOP_SECONDS, TimeUnit.SECONDS) }
        runCatching { retry.awaitTermination(EXECUTOR_STOP_SECONDS, TimeUnit.SECONDS) }
    }

    private fun acceptLoop(listener: BluetoothServerSocket) {
        while (!stopped) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            // The initiator already discovered this exact realm advertisement.
            // Requiring the listener to have scanned the initiator as well
            // made direct joins impossible on asymmetric Android scanners.
            // FIPS authenticates the channel; CCJ1 then verifies full realm and
            // cell scope before BoardCell admission.
            val id = NativeFips.bleDeliverInbound(bridge,
                "$ADAPTER/${socket.remoteDevice.address}", sendMtu(socket), receiveMtu(socket))
            FipsDebugLog.event("radio", "inbound_accepted", "address" to socket.remoteDevice.address,
                "channel" to id, "sendMtu" to sendMtu(socket), "receiveMtu" to receiveMtu(socket))
            if (id > 0) startChannel(id, socket, "inbound") else {
                FipsDebugLog.warning("radio", "channel_transport_rejected",
                    "address" to socket.remoteDevice.address, "direction" to "inbound")
                runCatching { socket.close() }
            }
        }
    }

    private fun startChannel(id: Long, socket: BluetoothSocket, direction: String) {
        channels[id] = socket
        channelTraces[id] = ChannelTrace(direction, socket.remoteDevice.address)
        gattPresence.channelOpened(socket.remoteDevice.address)
        onConnectionStage(FipsConnectionStage.CHANNEL_OPEN)
        FipsDebugLog.event("radio", "channel_open", "channel" to id,
            "address" to socket.remoteDevice.address, "direction" to direction,
            "channels" to channels.size)
        io.execute { reader(id, socket) }
        io.execute { writer(id, socket) }
    }

    private fun reader(id: Long, socket: BluetoothSocket) {
        val buffer = ByteArray(MAX_PACKET)
        var closeReason = "reader_stopped"
        try {
            while (!stopped) {
                val count = socket.inputStream.read(buffer)
                if (count < 0) {
                    closeReason = "remote_eof"
                    break
                }
                channelTraces[id]?.recordReceived(count)
                if (!NativeFips.bleChannelDeliverRecv(bridge, id, buffer, count)) {
                    closeReason = "native_receive_rejected"
                    break
                }
            }
        } catch (failure: IOException) {
            closeReason = "reader_io:${failure.message ?: failure.javaClass.simpleName}"
        } finally { channelGone(id, closeReason) }
    }

    private fun writer(id: Long, socket: BluetoothSocket) {
        val buffer = ByteArray(MAX_PACKET)
        var closeReason = "writer_stopped"
        try {
            // Channel close disconnects the native sender and wakes this wait;
            // a long fallback timeout avoids one idle wakeup per channel/sec.
            while (!stopped) when (val count = NativeFips.bleChannelNextSend(
                bridge, id, buffer, OUTBOUND_WAIT_MS,
            )) {
                -1 -> {
                    closeReason = "native_sender_closed"
                    break
                }
                0 -> Unit
                else -> {
                    socket.outputStream.write(buffer, 0, count); socket.outputStream.flush()
                    channelTraces[id]?.recordSent(count)
                }
            }
        } catch (failure: IOException) {
            closeReason = "writer_io:${failure.message ?: failure.javaClass.simpleName}"
        } finally { channelGone(id, closeReason) }
    }

    private fun closeChannel(id: Long, reason: String) {
        channels.remove(id)?.let { runCatching { it.close() } }
        logChannelClosed(id, reason)
    }

    private fun channelGone(id: Long, reason: String) {
        closeChannel(id, reason)
        runCatching { NativeFips.bleChannelClosed(bridge, id) }
    }

    private fun logChannelClosed(id: Long, reason: String) {
        val trace = channelTraces[id] ?: return
        if (!trace.closed.compareAndSet(false, true)) return
        channelTraces.remove(id, trace)
        gattPresence.channelClosed(trace.address)
        val remainingForAddress = channelTraces.values.count {
            !it.closed.get() && it.address.equals(trace.address, ignoreCase = true)
        }
        FipsDebugLog.event(
            "radio", "channel_closed",
            "channel" to id,
            "address" to trace.address,
            "direction" to trace.direction,
            "reason" to reason,
            "lifetimeMs" to (System.currentTimeMillis() - trace.openedAtMs),
            "rxPackets" to trace.rxPackets.get(),
            "rxBytes" to trace.rxBytes.get(),
            "txPackets" to trace.txPackets.get(),
            "txBytes" to trace.txBytes.get(),
            "remaining" to channels.size,
            "remainingForAddress" to remainingForAddress,
        )
        onChannelClosed(FipsRadioChannelClose(
            id, trace.address, trace.direction, reason, remainingForAddress,
        ))
    }

    private class ChannelTrace(val direction: String, val address: String) {
        val openedAtMs = System.currentTimeMillis()
        val rxPackets = AtomicLong()
        val rxBytes = AtomicLong()
        val txPackets = AtomicLong()
        val txBytes = AtomicLong()
        val closed = AtomicBoolean(false)
        fun recordReceived(bytes: Int) { rxPackets.incrementAndGet(); rxBytes.addAndGet(bytes.toLong()) }
        fun recordSent(bytes: Int) { txPackets.incrementAndGet(); txBytes.addAndGet(bytes.toLong()) }
    }

    private fun sendMtu(socket: BluetoothSocket) = socket.maxTransmitPacketSize.coerceIn(20, 1_500)
    private fun receiveMtu(socket: BluetoothSocket) = socket.maxReceivePacketSize.coerceIn(20, 1_500)

    companion object {
        private const val TAG = "CruxFipsRadio"
        private const val ADAPTER = "ble0"
        private const val MAX_PACKET = 8_192
        private const val NONCE_ROTATE_MS = 30_000L
        private const val NONCE_DEFER_POLL_MS = 5_000L
        private const val DISCOVERY_LOG_INTERVAL_MS = 10_000L
        private const val EXECUTOR_STOP_SECONDS = 2L
        private const val OUTBOUND_WAIT_MS = 60_000
        private const val PLATFORM_CONNECT_TIMEOUT_MS = 10_000L
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
