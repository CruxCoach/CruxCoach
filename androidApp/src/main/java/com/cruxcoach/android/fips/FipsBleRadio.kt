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

/** Android API 29+ L2CAP CoC radio owned by Kotlin and driven by FIPS over JNI. */
@SuppressLint("MissingPermission")
internal class FipsBleRadio(context: Context, private val realm: FipsRealmContext) {
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
    private val observedNonceTags = ConcurrentHashMap<String, Long>()
    private val observedAddresses = ConcurrentHashMap<String, Long>()

    fun bindBridge(handle: Long) { bridge = handle }

    @RequiresApi(29)
    fun listen(): Int = try {
        if (stopped) return 0
        val socket = adapter?.listenUsingInsecureL2capChannel() ?: return 0
        server = socket
        io.execute { acceptLoop(socket) }
        socket.psm
    } catch (e: Exception) { Log.e(TAG, "L2CAP listen failed", e); 0 }

    @RequiresApi(29)
    fun connect(connectId: Long, address: String, psm: Int) {
        if (stopped) return
        io.execute {
            val mac = address.substringAfter('/', address)
            try {
                val socket = adapter?.getRemoteDevice(mac)?.createInsecureL2capChannel(psm)
                    ?: throw IOException("BLE unavailable")
                socket.connect()
                val id = NativeFips.bleDeliverConnectResult(bridge, connectId, true, address,
                    sendMtu(socket), receiveMtu(socket))
                if (id > 0) startChannel(id, socket) else socket.close()
            } catch (e: Exception) {
                Log.w(TAG, "L2CAP connect failed: ${e.message}")
                NativeFips.bleDeliverConnectResult(bridge, connectId, false, address, 0, 0)
            }
        }
    }

    fun startAdvertising(psm: Int) {
        if (stopped) return
        advertisedPsm = psm
        stopAdvertising()
        if (System.currentTimeMillis() - nonceRotatedAtMs >= NONCE_ROTATE_MS) {
            localNonce = newNonce()
            nonceRotatedAtMs = System.currentTimeMillis()
        }
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val psmBytes = byteArrayOf(
            ADVERTISEMENT_VERSION, psm.toByte(), (psm shr 8).toByte(),
            *realm.realmTag, *realm.cellTag, *DirectJoinProof.nonceTag(localNonce),
        )
        val data = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceUuid(CRUXCOACH_FIPS_UUID).addServiceData(CRUXCOACH_FIPS_UUID, psmBytes).build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true).setTimeout(0).build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(value: AdvertiseSettings?) {
                advertiseRetries = 0
                nonceRotation = retry.schedule({
                    if (!stopped) {
                        localNonce = newNonce()
                        nonceRotatedAtMs = System.currentTimeMillis()
                        startAdvertising(advertisedPsm)
                    }
                }, NONCE_ROTATE_MS, TimeUnit.MILLISECONDS)
            }
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "advertise failed $errorCode")
                scheduleAdvertiseRetry()
            }
        }
        advertiserCallback = callback
        runCatching { advertiser.startAdvertising(settings, data, callback) }
            .onFailure { scheduleAdvertiseRetry() }
    }

    private fun scheduleAdvertiseRetry() {
        if (stopped) return
        val seconds = minOf(60L, 5L shl minOf(advertiseRetries++, 3))
        retry.schedule({ if (!stopped) startAdvertising(advertisedPsm) }, seconds, TimeUnit.SECONDS)
    }

    fun stopAdvertising() {
        nonceRotation?.cancel(false); nonceRotation = null
        advertiserCallback?.let { callback ->
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertiserCallback = null
    }

    fun startScanning() {
        if (stopped) return
        stopScanning()
        val scanner = adapter?.bluetoothLeScanner ?: return
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) = handleResult(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleResult)
            override fun onScanFailed(errorCode: Int) {
                val floor = if (errorCode == SCAN_FAILED_SCANNING_TOO_FREQUENTLY) 30L else 0L
                val seconds = maxOf(floor, minOf(60L, 5L shl minOf(scanRetries++, 3)))
                retry.schedule({ if (!stopped) startScanning() }, seconds, TimeUnit.SECONDS)
            }
        }
        scannerCallback = callback
        val filter = ScanFilter.Builder().setServiceUuid(CRUXCOACH_FIPS_UUID).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
    }

    private fun handleResult(result: ScanResult) {
        scanRetries = 0
        val bytes = result.scanRecord?.getServiceData(CRUXCOACH_FIPS_UUID) ?: return
        if (bytes.size != ADVERTISEMENT_BYTES || bytes[0] != ADVERTISEMENT_VERSION) return
        if (!bytes.copyOfRange(3, 7).contentEquals(realm.realmTag) ||
            !bytes.copyOfRange(7, 11).contentEquals(realm.cellTag)) return
        val nonceTag = bytes.copyOfRange(11, 15).toHex()
        observedNonceTags[nonceTag] = System.currentTimeMillis()
        observedNonceTags.entries.removeAll { System.currentTimeMillis() - it.value > DirectJoinProof.MAX_AGE_MS }
        observedAddresses[result.device.address.uppercase()] = System.currentTimeMillis()
        observedAddresses.entries.removeAll { System.currentTimeMillis() - it.value > DirectJoinProof.MAX_AGE_MS }
        val psm = (bytes[1].toInt() and 255) or ((bytes[2].toInt() and 255) shl 8)
        if (psm > 0) NativeFips.bleDeliverScan(bridge,
            "$ADAPTER/${result.device.address}", psm, result.rssi)
    }

    fun stopScanning() {
        scannerCallback?.let { runCatching { adapter?.bluetoothLeScanner?.stopScan(it) } }
        scannerCallback = null
    }

    fun localNonceHex(): String = localNonce.toHex()

    fun validateDirectJoin(hello: DirectJoinHello): Boolean {
        return DirectJoinProof.validate(realm, hello, observedNonceTags, directBleEdge = true,
            nowMs = System.currentTimeMillis())
    }

    fun closeChannel(id: Long) { channels.remove(id)?.let { runCatching { it.close() } } }

    fun shutdown() {
        stopped = true
        stopScanning(); stopAdvertising()
        runCatching { server?.close() }; server = null
        channels.keys.toList().forEach(::closeChannel)
        io.shutdownNow(); retry.shutdownNow()
    }

    private fun acceptLoop(listener: BluetoothServerSocket) {
        while (!stopped) {
            val socket = try { listener.accept() } catch (_: IOException) { break }
            val seenAt = observedAddresses[socket.remoteDevice.address.uppercase()]
            if (seenAt == null || System.currentTimeMillis() - seenAt > DirectJoinProof.MAX_AGE_MS) {
                // Inbound L2CAP is not discovery: require a matching, recent local scan first.
                runCatching { socket.close() }
                continue
            }
            val id = NativeFips.bleDeliverInbound(bridge,
                "$ADAPTER/${socket.remoteDevice.address}", sendMtu(socket), receiveMtu(socket))
            if (id > 0) startChannel(id, socket) else runCatching { socket.close() }
        }
    }

    private fun startChannel(id: Long, socket: BluetoothSocket) {
        channels[id] = socket
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
            while (!stopped) when (val count = NativeFips.bleChannelNextSend(bridge, id, buffer, 1_000)) {
                -1 -> break
                0 -> Unit
                else -> { socket.outputStream.write(buffer, 0, count); socket.outputStream.flush() }
            }
        } catch (_: IOException) { } finally { channelGone(id) }
    }

    private fun channelGone(id: Long) {
        closeChannel(id)
        runCatching { NativeFips.bleChannelClosed(bridge, id) }
    }

    private fun sendMtu(socket: BluetoothSocket) = socket.maxTransmitPacketSize.coerceIn(20, 1_500)
    private fun receiveMtu(socket: BluetoothSocket) = socket.maxReceivePacketSize.coerceIn(20, 1_500)

    companion object {
        private const val TAG = "CruxFipsRadio"
        private const val ADAPTER = "ble0"
        private const val MAX_PACKET = 8_192
        private const val ADVERTISEMENT_VERSION: Byte = 1
        private const val ADVERTISEMENT_BYTES = 15
        private const val NONCE_ROTATE_MS = 30_000L
        // A compact CruxCoach-specific 16-bit-shaped UUID keeps the legacy 31-byte
        // advertising budget; full ids and the full nonce are authenticated in CCJ1.
        private val CRUXCOACH_FIPS_UUID = ParcelUuid(UUID.fromString("0000ccf1-0000-1000-8000-00805f9b34fb"))
    }


    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun newNonce() = ByteArray(16).also(SecureRandom()::nextBytes)
}
