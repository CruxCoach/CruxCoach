package com.cruxcoach.app.ble

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import platform.posix.memcpy
import kotlin.concurrent.Volatile

/**
 * [BleCentral] on CoreBluetooth. COMPILE-CHECKED ONLY: this file has never run,
 * neither in a simulator (which has no Bluetooth) nor against a board.
 *
 * All CoreBluetooth objects are touched on one private serial queue. Requests
 * hop onto it; delegate callbacks already run there and are forwarded to the
 * listener from there. The host app needs NSBluetoothAlwaysUsageDescription.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class CoreBluetoothCentral : BleCentral {
    private val queue = dispatch_queue_create("com.cruxcoach.ble.central", null)

    // CoreBluetooth holds its delegates weakly: these fields are what keeps them alive.
    private val centralDelegate = CentralDelegate()
    private val peripheralDelegate = PeripheralDelegate()
    private var manager: CBCentralManager? = null

    /** Strong references: CoreBluetooth drops a peripheral nobody retains, cancelling its connection. */
    private val peripherals = mutableMapOf<String, CBPeripheral>()
    private val pendingCharacteristicDiscoveries = mutableMapOf<String, Int>()
    private val failedDiscoveries = mutableSetOf<String>()
    private val withoutResponse = mutableMapOf<String, PendingWrite>()

    private class PendingWrite(val characteristic: CBCharacteristic, val data: NSData, var written: Boolean)

    @Volatile
    private var listener: BleCentralListener? = null

    @Volatile
    private var adapter = BleAdapterState.UNKNOWN

    override fun setListener(listener: BleCentralListener?) {
        this.listener = listener
    }

    override fun adapterState(): BleAdapterState = adapter

    /** Creating the manager is what makes iOS show the Bluetooth permission prompt. */
    override fun activate() = onQueue { ensureManager() }

    private fun ensureManager(): CBCentralManager =
        manager ?: CBCentralManager(delegate = centralDelegate, queue = queue).also { manager = it }

    override fun startScan() = onQueue {
        val central = ensureManager()
        if (central.state != CBManagerStatePoweredOn) return@onQueue
        // No service filter: boards are identified by name. Duplicates keep RSSI and names fresh.
        central.scanForPeripheralsWithServices(
            serviceUUIDs = null,
            options = mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to true),
        )
    }

    override fun stopScan() = onQueue {
        val central = manager ?: return@onQueue
        if (central.state == CBManagerStatePoweredOn) central.stopScan()
    }

    override fun connect(identifier: String) = onQueue {
        val central = ensureManager()
        val peripheral = peripherals[identifier] ?: retrieve(central, identifier)
        if (peripheral == null || central.state != CBManagerStatePoweredOn) {
            listener?.onDisconnected(identifier)
            return@onQueue
        }
        peripherals[identifier] = peripheral
        peripheral.delegate = peripheralDelegate
        // connectPeripheral never times out by itself; the controller owns the 10 s budget.
        central.connectPeripheral(peripheral, options = null)
    }

    /** A remembered board: CoreBluetooth can hand back a peripheral it has seen before without a scan. */
    private fun retrieve(central: CBCentralManager, identifier: String): CBPeripheral? {
        if (!isUuidString(identifier)) return null
        val found = central.retrievePeripheralsWithIdentifiers(listOf(NSUUID(uUIDString = identifier)))
        return found.firstOrNull() as? CBPeripheral
    }

    override fun cancelConnection(identifier: String) = onQueue {
        clearOperationState(identifier)
        val peripheral = peripherals[identifier]
        val central = manager
        if (peripheral == null || central == null || central.state != CBManagerStatePoweredOn) {
            listener?.onDisconnected(identifier)
            return@onQueue
        }
        central.cancelPeripheralConnection(peripheral)
    }

    override fun discoverServices(identifier: String) = onQueue {
        val peripheral = peripherals[identifier]
        if (peripheral == null) {
            listener?.onServicesDiscovered(identifier, null)
            return@onQueue
        }
        failedDiscoveries.remove(identifier)
        peripheral.discoverServices(null)
    }

    override fun maximumWriteLength(identifier: String, withResponse: Boolean): Int {
        var result = 0
        dispatch_sync(queue) {
            result = peripherals[identifier]?.maximumWriteValueLengthForType(
                if (withResponse) CBCharacteristicWriteWithResponse else CBCharacteristicWriteWithoutResponse,
            )?.toInt() ?: 0
        }
        return result
    }

    override fun write(
        identifier: String,
        serviceUuid: String,
        characteristicUuid: String,
        value: ByteArray,
        withResponse: Boolean,
    ) = onQueue {
        val peripheral = peripherals[identifier]
        val characteristic = peripheral?.let { find(it, serviceUuid, characteristicUuid) }
        if (peripheral == null || characteristic == null) {
            listener?.onWriteCompleted(identifier, characteristicUuid, false)
            return@onQueue
        }
        val data = value.toNSData()
        if (withResponse) {
            peripheral.writeValue(data, characteristic, CBCharacteristicWriteWithResponse)
            return@onQueue
        }
        // Without response there is no acknowledgement; CoreBluetooth silently
        // drops packets written while its buffer is full, so gate on readiness.
        val pending = PendingWrite(characteristic, data, written = false)
        withoutResponse[identifier] = pending
        pumpWithoutResponse(peripheral, pending)
    }

    private fun pumpWithoutResponse(peripheral: CBPeripheral, pending: PendingWrite) {
        val identifier = peripheral.identifier.UUIDString
        if (withoutResponse[identifier] !== pending || !peripheral.canSendWriteWithoutResponse) return
        if (!pending.written) {
            peripheral.writeValue(pending.data, pending.characteristic, CBCharacteristicWriteWithoutResponse)
            pending.written = true
            // Report only once the stack can take the next packet.
            if (!peripheral.canSendWriteWithoutResponse) return
        }
        withoutResponse.remove(identifier)
        listener?.onWriteCompleted(identifier, pending.characteristic.UUID.UUIDString, true)
    }

    override fun read(identifier: String, serviceUuid: String, characteristicUuid: String) = onQueue {
        val peripheral = peripherals[identifier]
        val characteristic = peripheral?.let { find(it, serviceUuid, characteristicUuid) }
        if (peripheral == null || characteristic == null) {
            listener?.onCharacteristicRead(identifier, characteristicUuid, null)
            return@onQueue
        }
        peripheral.readValueForCharacteristic(characteristic)
    }

    override fun setNotify(identifier: String, serviceUuid: String, characteristicUuid: String, enabled: Boolean) =
        onQueue {
            val peripheral = peripherals[identifier]
            val characteristic = peripheral?.let { find(it, serviceUuid, characteristicUuid) }
            if (peripheral == null || characteristic == null) {
                listener?.onNotifyStateChanged(identifier, characteristicUuid, enabled, false)
                return@onQueue
            }
            // CoreBluetooth writes the CCCD itself; didUpdateNotificationState is its completion.
            peripheral.setNotifyValue(enabled, characteristic)
        }

    private fun find(peripheral: CBPeripheral, serviceUuid: String, characteristicUuid: String): CBCharacteristic? {
        val service = peripheral.services.orEmpty().filterIsInstance<CBService>()
            .firstOrNull { BoardBleUuids.sameUuid(it.UUID.UUIDString, serviceUuid) } ?: return null
        return service.characteristics.orEmpty().filterIsInstance<CBCharacteristic>()
            .firstOrNull { BoardBleUuids.sameUuid(it.UUID.UUIDString, characteristicUuid) }
    }

    private fun clearOperationState(identifier: String) {
        pendingCharacteristicDiscoveries.remove(identifier)
        failedDiscoveries.remove(identifier)
        withoutResponse.remove(identifier)
    }

    private fun onQueue(block: () -> Unit) = dispatch_async(queue) { block() }

    private fun describe(peripheral: CBPeripheral): List<BleServiceInfo> =
        peripheral.services.orEmpty().filterIsInstance<CBService>().map { service ->
            BleServiceInfo(
                service.UUID.UUIDString,
                service.characteristics.orEmpty().filterIsInstance<CBCharacteristic>().map { c ->
                    val p = c.properties
                    BleCharacteristicInfo(
                        uuid = c.UUID.UUIDString,
                        canRead = p and CBCharacteristicPropertyRead != 0uL,
                        canWrite = p and CBCharacteristicPropertyWrite != 0uL,
                        canWriteWithoutResponse = p and CBCharacteristicPropertyWriteWithoutResponse != 0uL,
                        canNotify = p and (CBCharacteristicPropertyNotify or CBCharacteristicPropertyIndicate) != 0uL,
                    )
                },
            )
        }

    private inner class CentralDelegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            val state = when (central.state) {
                CBManagerStatePoweredOn -> BleAdapterState.POWERED_ON
                CBManagerStatePoweredOff -> BleAdapterState.POWERED_OFF
                CBManagerStateUnauthorized -> BleAdapterState.UNAUTHORIZED
                CBManagerStateUnsupported -> BleAdapterState.UNSUPPORTED
                CBManagerStateResetting -> BleAdapterState.RESETTING
                else -> BleAdapterState.UNKNOWN
            }
            adapter = state
            if (state != BleAdapterState.POWERED_ON) {
                // Every CBPeripheral is invalid after the adapter left poweredOn.
                peripherals.clear()
                pendingCharacteristicDiscoveries.clear()
                failedDiscoveries.clear()
                withoutResponse.clear()
            }
            listener?.onAdapterState(state)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val identifier = didDiscoverPeripheral.identifier.UUIDString
            val advertisedName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
            val cachedName = didDiscoverPeripheral.name
            // Retain only what could be a board; a busy gym advertises hundreds of devices.
            val name = advertisedName ?: cachedName ?: return
            if (BoardBleNames.classify(name, identifier, 0) == null) return
            peripherals[identifier] = didDiscoverPeripheral
            listener?.onAdvertisement(identifier, advertisedName, cachedName, RSSI.intValue)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.delegate = peripheralDelegate
            listener?.onConnected(didConnectPeripheral.identifier.UUIDString)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            val identifier = didFailToConnectPeripheral.identifier.UUIDString
            clearOperationState(identifier)
            listener?.onDisconnected(identifier)
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            val identifier = didDisconnectPeripheral.identifier.UUIDString
            clearOperationState(identifier)
            listener?.onDisconnected(identifier)
        }
    }

    private inner class PeripheralDelegate : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val identifier = peripheral.identifier.UUIDString
            val services = peripheral.services.orEmpty().filterIsInstance<CBService>()
            if (didDiscoverServices != null) {
                listener?.onServicesDiscovered(identifier, null)
                return
            }
            if (services.isEmpty()) {
                listener?.onServicesDiscovered(identifier, emptyList())
                return
            }
            pendingCharacteristicDiscoveries[identifier] = services.size
            services.forEach { peripheral.discoverCharacteristics(null, it) }
        }

        override fun peripheral(
            peripheral: CBPeripheral,
            didDiscoverCharacteristicsForService: CBService,
            error: NSError?,
        ) {
            val identifier = peripheral.identifier.UUIDString
            val remaining = (pendingCharacteristicDiscoveries[identifier] ?: return) - 1
            if (error != null) failedDiscoveries.add(identifier)
            if (remaining > 0) {
                pendingCharacteristicDiscoveries[identifier] = remaining
                return
            }
            pendingCharacteristicDiscoveries.remove(identifier)
            // Never report a partial table: a missing characteristic must mean "absent".
            val failed = failedDiscoveries.remove(identifier)
            listener?.onServicesDiscovered(identifier, if (failed) null else describe(peripheral))
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            listener?.onWriteCompleted(
                peripheral.identifier.UUIDString,
                didWriteValueForCharacteristic.UUID.UUIDString,
                error == null,
            )
        }

        /** Both read responses and notifications arrive here; CoreBluetooth does not tell them apart. */
        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val identifier = peripheral.identifier.UUIDString
            val uuid = didUpdateValueForCharacteristic.UUID.UUIDString
            val value = if (error == null) didUpdateValueForCharacteristic.value?.toByteArray() else null
            if (didUpdateValueForCharacteristic.isNotifying) {
                if (value != null) listener?.onNotification(identifier, uuid, value)
            } else {
                listener?.onCharacteristicRead(identifier, uuid, value)
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(
            peripheral: CBPeripheral,
            didUpdateNotificationStateForCharacteristic: CBCharacteristic,
            error: NSError?,
        ) {
            val enabled = didUpdateNotificationStateForCharacteristic.isNotifying
            listener?.onNotifyStateChanged(
                peripheral.identifier.UUIDString,
                didUpdateNotificationStateForCharacteristic.UUID.UUIDString,
                enabled,
                error == null,
            )
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            val pending = withoutResponse[peripheral.identifier.UUIDString] ?: return
            pumpWithoutResponse(peripheral, pending)
        }
    }
}

private fun isUuidString(value: String): Boolean =
    value.length == 36 && value.withIndex().all { (i, c) ->
        if (i == 8 || i == 13 || i == 18 || i == 23) c == '-' else c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    }

/** NSData.create with a null/zero-length buffer is not defined: an empty array is an empty NSData. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/** addressOf(0) on an empty array throws, and NSData.bytes may be null when empty. */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val count = length.toInt()
    if (count == 0) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    return ByteArray(count).also { target -> target.usePinned { memcpy(it.addressOf(0), source, length) } }
}
