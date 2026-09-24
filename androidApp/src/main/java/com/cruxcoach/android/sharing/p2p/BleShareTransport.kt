package com.cruxcoach.android.sharing.p2p

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * BLE GATT path of the direct channel, for small frames only (deltas, acks,
 * control): at most [MAX_CONTAINER] bytes per exchange step. Initial full
 * states wait for LAN.
 *
 * * Advertisement: manufacturer data with rotating beacons (no stable id).
 * * GATT service: HELLO (read), REQUEST (fragmented writes with response),
 *   RESPONSE (fragmented notifications).
 * * The initiator connects only when the native host identified a friend or
 *   has an invitation to offer; one exchange step per connection.
 *
 * Spike M0: compiled, not run on a device (no ADB in this spike). Callers own
 * the runtime permissions (BLUETOOTH_SCAN/CONNECT/ADVERTISE) like the session
 * queue does.
 */
@SuppressLint("MissingPermission")
class BleShareTransport(private val context: Context) : ShareTransport {
    override val name = "ble"
    override val maxContainer = MAX_CONTAINER
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var gattServer: BluetoothGattServer? = null
    private var local: ShareExchangeServer? = null
    private val seen = ConcurrentHashMap<String, Pair<ShareHello, Long>>()
    private val inbound = ConcurrentHashMap<String, ShareBleFraming.Reassembler>()
    private val mtus = ConcurrentHashMap<String, Int>()
    private val notified = ConcurrentHashMap<String, LinkedBlockingQueue<Boolean>>()
    @Volatile private var advertisedAt = 0L

    override val running: Boolean get() = gattServer != null

    private val advertiseCallback = object : AdvertiseCallback() {}
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val hello = ShareBleFraming.parseAdvertisement(result.scanRecord?.getManufacturerSpecificData(ShareBleFraming.COMPANY_ID))
                ?: return
            if (seen.size < MAX_SEEN || seen.containsKey(result.device.address)) {
                seen[result.device.address] = hello to System.currentTimeMillis()
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                inbound.remove(device.address)
                mtus.remove(device.address)
                notified.remove(device.address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val hello = if (characteristic.uuid == HELLO) local?.hello()?.let(ShareBleFraming::advertisement) else null
            val server = gattServer ?: return
            if (hello == null || offset > hello.size) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, hello.copyOfRange(offset, hello.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val server = gattServer ?: return
            val accepted = characteristic.uuid == REQUEST && value != null && runCatching {
                val assembler = inbound.getOrPut(device.address) { ShareBleFraming.Reassembler(MAX_CONTAINER + 4096) }
                assembler.add(value)?.let { request ->
                    inbound.remove(device.address)
                    thread(isDaemon = true, name = "share-ble-serve") { respond(device, request) }
                }
                true
            }.getOrElse { inbound.remove(device.address); false }
            if (responseNeeded) {
                server.sendResponse(device, requestId, if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notified[device.address]?.offer(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /** Answer one reassembled request with notified response fragments. */
    private fun respond(device: BluetoothDevice, request: ByteArray) {
        val server = gattServer ?: return
        val response = local?.exchange(request, MAX_CONTAINER) ?: return
        val characteristic = server.getService(SERVICE)?.getCharacteristic(RESPONSE) ?: return
        val queue = LinkedBlockingQueue<Boolean>().also { notified[device.address] = it }
        for (fragment in ShareBleFraming.fragments(response, mtus[device.address] ?: DEFAULT_MTU)) {
            val sent = if (Build.VERSION.SDK_INT >= 33) {
                server.notifyCharacteristicChanged(device, characteristic, false, fragment) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = fragment
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
            if (!sent || queue.poll(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS) != true) return
        }
    }

    @Synchronized override fun start(server: ShareExchangeServer) {
        if (gattServer != null) return
        val adapter = adapter?.takeIf { it.isEnabled } ?: return
        local = server
        val gatt = manager?.openGattServer(context, serverCallback) ?: return
        val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(BluetoothGattCharacteristic(HELLO, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            addCharacteristic(BluetoothGattCharacteristic(REQUEST, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            addCharacteristic(
                BluetoothGattCharacteristic(RESPONSE, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ).apply {
                    addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                },
            )
        }
        gatt.addService(service)
        gattServer = gatt
        advertise()
        adapter.bluetoothLeScanner?.startScan(
            listOf(ScanFilter.Builder().setManufacturerData(ShareBleFraming.COMPANY_ID, ShareBleFraming.MAGIC, ShareBleFraming.MAGIC.map { 0xFF.toByte() }.toByteArray()).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
            scanCallback,
        )
    }

    /** (Re)advertise the current beacons; they rotate every ten minutes. */
    private fun advertise() {
        val hello = local?.hello() ?: return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        advertiser.startAdvertising(
            AdvertiseSettings.Builder().setConnectable(true).setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).build(),
            AdvertiseData.Builder().setIncludeDeviceName(false).addManufacturerData(ShareBleFraming.COMPANY_ID, ShareBleFraming.advertisement(hello)).build(),
            advertiseCallback,
        )
        advertisedAt = System.currentTimeMillis()
    }

    @Synchronized override fun stop() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { gattServer?.close() }
        gattServer = null
        local = null
        seen.clear()
        inbound.clear()
    }

    override fun endpoints(): List<ShareEndpoint> {
        val now = System.currentTimeMillis()
        if (now - advertisedAt > REFRESH_MS) advertise()
        seen.entries.removeIf { now - it.value.second > SEEN_MS }
        return seen.map { (address, value) -> ShareEndpoint(name, address, value.first) }
    }

    /** One step: connect, negotiate MTU, subscribe, write the request, collect the response. */
    override fun exchange(endpoint: ShareEndpoint, request: ByteArray): ByteArray? {
        if (request.size > MAX_CONTAINER + 4096) return null
        val device = runCatching { adapter?.getRemoteDevice(endpoint.address) }.getOrNull() ?: return null
        val done = CountDownLatch(1)
        val writes = LinkedBlockingQueue<Boolean>()
        var result: ByteArray? = null
        val assembler = ShareBleFraming.Reassembler(MAX_CONTAINER + 4096)
        var mtu = DEFAULT_MTU
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> if (!gatt.requestMtu(REQUESTED_MTU)) gatt.discoverServices()
                    BluetoothProfile.STATE_DISCONNECTED -> done.countDown()
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, value: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) mtu = value
                gatt.discoverServices()
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val response = gatt.getService(SERVICE)?.getCharacteristic(RESPONSE) ?: return done.countDown()
                gatt.setCharacteristicNotification(response, true)
                val cccd = response.getDescriptor(CCCD) ?: return done.countDown()
                if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            }
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                val target = gatt.getService(SERVICE)?.getCharacteristic(REQUEST) ?: return done.countDown()
                thread(isDaemon = true, name = "share-ble-write") {
                    for (fragment in ShareBleFraming.fragments(request, mtu)) {
                        val queued = if (Build.VERSION.SDK_INT >= 33) {
                            gatt.writeCharacteristic(target, fragment, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            target.value = fragment
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(target)
                        }
                        if (!queued || writes.poll(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS) != true) {
                            done.countDown()
                            return@thread
                        }
                    }
                }
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                writes.offer(status == BluetoothGatt.GATT_SUCCESS)
            }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                if (characteristic.uuid != RESPONSE) return
                runCatching { assembler.add(value) }.onSuccess { complete ->
                    if (complete != null) {
                        result = complete
                        done.countDown()
                    }
                }.onFailure { done.countDown() }
            }
            @Deprecated("Android < 13")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                onCharacteristicChanged(gatt, characteristic, characteristic.value ?: return)
            }
        }
        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return null
        try {
            done.await(EXCHANGE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        return result
    }

    companion object {
        val SERVICE: UUID = UUID.fromString("C1140C00-CC01-4000-8000-DEADC0AC0001")
        val HELLO: UUID = UUID.fromString("C1140C01-CC01-4000-8000-DEADC0AC0001")
        val REQUEST: UUID = UUID.fromString("C1140C02-CC01-4000-8000-DEADC0AC0001")
        val RESPONSE: UUID = UUID.fromString("C1140C03-CC01-4000-8000-DEADC0AC0001")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        /** Frame budget per step: deltas and acks, not initial pages. */
        const val MAX_CONTAINER = 16_384
        const val DEFAULT_MTU = 23
        const val REQUESTED_MTU = 512
        const val STEP_TIMEOUT_MS = 3_000L
        const val EXCHANGE_TIMEOUT_MS = 20_000L
        const val REFRESH_MS = 5 * 60_000L
        const val SEEN_MS = 60_000L
        const val MAX_SEEN = 32
    }
}
