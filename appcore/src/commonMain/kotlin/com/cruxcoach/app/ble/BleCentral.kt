package com.cruxcoach.app.ble

/** CBManagerState, one to one. Only [POWERED_ON] permits scanning or connecting. */
enum class BleAdapterState { UNKNOWN, RESETTING, UNSUPPORTED, UNAUTHORIZED, POWERED_OFF, POWERED_ON }

class BleCharacteristicInfo(
    val uuid: String,
    val canRead: Boolean,
    val canWrite: Boolean,
    val canWriteWithoutResponse: Boolean,
    val canNotify: Boolean,
)

class BleServiceInfo(val uuid: String, val characteristics: List<BleCharacteristicInfo>)

/**
 * Events from the platform central. They may arrive on any thread (CoreBluetooth
 * uses its own serial queue); the consumer hops to its own dispatcher. Events
 * for one peripheral arrive in the order the platform produced them.
 */
interface BleCentralListener {
    fun onAdapterState(state: BleAdapterState)

    /** [advertisedName] is the advertisement's local name, [peripheralName] the platform's cached one. */
    fun onAdvertisement(identifier: String, advertisedName: String?, peripheralName: String?, rssi: Int)
    fun onConnected(identifier: String)

    /** The connection attempt failed or the link ended, requested or not. */
    fun onDisconnected(identifier: String)

    /** Null [services] means discovery failed. Characteristics are already discovered. */
    fun onServicesDiscovered(identifier: String, services: List<BleServiceInfo>?)

    /**
     * For a write with response: the peripheral's acknowledgement. For a write
     * without response there is no acknowledgement on any platform; it means the
     * local stack accepted the packet and can take another one.
     */
    fun onWriteCompleted(identifier: String, characteristicUuid: String, success: Boolean)

    /** Null [value] means the read failed. */
    fun onCharacteristicRead(identifier: String, characteristicUuid: String, value: ByteArray?)
    fun onNotifyStateChanged(identifier: String, characteristicUuid: String, enabled: Boolean, success: Boolean)
    fun onNotification(identifier: String, characteristicUuid: String, value: ByteArray)
}

/**
 * The central role as CoreBluetooth can actually provide it: peripherals are
 * opaque identifier strings (no MAC address), the ATT MTU cannot be requested,
 * only observed through [maximumWriteLength], and a request never fails
 * synchronously: every outcome, including "no such characteristic", is an
 * event. One GATT operation is outstanding at a time; the caller serialises.
 */
interface BleCentral {
    fun setListener(listener: BleCentralListener?)
    fun adapterState(): BleAdapterState

    /**
     * Brings the platform manager up. On iOS this is the call that shows the
     * Bluetooth permission prompt, so it is separate from construction; the
     * adapter state then arrives through the listener.
     */
    fun activate()

    /** Unfiltered scan reporting duplicates, so names and RSSI refresh. */
    fun startScan()
    fun stopScan()

    /** Connects a scanned peripheral, or one the platform still remembers by identifier. */
    fun connect(identifier: String)

    /** Cancels a pending or established connection; [BleCentralListener.onDisconnected] follows. */
    fun cancelConnection(identifier: String)
    fun discoverServices(identifier: String)

    /** Largest single-write payload (`ATT_MTU - 3`), or 0 when not connected. */
    fun maximumWriteLength(identifier: String, withResponse: Boolean): Int
    fun write(identifier: String, serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean)
    fun read(identifier: String, serviceUuid: String, characteristicUuid: String)
    fun setNotify(identifier: String, serviceUuid: String, characteristicUuid: String, enabled: Boolean)
}
