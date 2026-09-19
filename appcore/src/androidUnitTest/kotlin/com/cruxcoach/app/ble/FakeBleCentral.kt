package com.cruxcoach.app.ble

/** Scripted central: records every request with its virtual timestamp; the test decides each outcome. */
class FakeBleCentral(private val now: () -> Long) : BleCentral {
    data class Write(val atMs: Long, val characteristic: String, val bytes: List<Byte>, val withResponse: Boolean)

    var listener: BleCentralListener? = null
        private set
    var adapter = BleAdapterState.POWERED_ON
    var scanning = false
    val connects = mutableListOf<Long>()
    val cancels = mutableListOf<Long>()
    val writes = mutableListOf<Write>()
    val operations = mutableListOf<String>()

    /** What a connect request does. */
    var onConnect: (String) -> Unit = { listener?.onConnected(it) }
    var services: List<BleServiceInfo>? = AURORA_SERVICES
    var acknowledgeWrites = true
    var writeSucceeds = true
    var maxWriteLength = 182
    var reads = mutableMapOf<String, ByteArray?>()
    var answerReads = true
    var notifySucceeds = true
    var onWrite: (Write) -> Unit = {}

    override fun setListener(listener: BleCentralListener?) { this.listener = listener }
    override fun adapterState() = adapter
    var activations = 0
    override fun activate() { activations += 1 }
    override fun startScan() { scanning = true }
    override fun stopScan() { scanning = false }

    override fun connect(identifier: String) {
        connects += now()
        operations += "connect"
        onConnect(identifier)
    }

    override fun cancelConnection(identifier: String) {
        cancels += now()
        operations += "cancel"
        listener?.onDisconnected(identifier)
    }

    override fun discoverServices(identifier: String) {
        operations += "discover"
        listener?.onServicesDiscovered(identifier, services)
    }

    override fun maximumWriteLength(identifier: String, withResponse: Boolean): Int {
        operations += "maxWriteLength"
        return maxWriteLength
    }

    override fun write(identifier: String, serviceUuid: String, characteristicUuid: String, value: ByteArray, withResponse: Boolean) {
        val write = Write(now(), characteristicUuid, value.toList(), withResponse)
        writes += write
        operations += "write:$characteristicUuid"
        if (acknowledgeWrites) listener?.onWriteCompleted(identifier, characteristicUuid, writeSucceeds)
        onWrite(write)
    }

    override fun read(identifier: String, serviceUuid: String, characteristicUuid: String) {
        operations += "read:$characteristicUuid"
        if (answerReads) listener?.onCharacteristicRead(
            identifier,
            characteristicUuid,
            reads.entries.firstOrNull { BoardBleUuids.sameUuid(it.key, characteristicUuid) }?.value,
        )
    }

    override fun setNotify(identifier: String, serviceUuid: String, characteristicUuid: String, enabled: Boolean) {
        operations += "notify:$characteristicUuid"
        listener?.onNotifyStateChanged(identifier, characteristicUuid, enabled, notifySucceeds)
    }

    companion object {
        fun characteristic(uuid: String, write: Boolean = false, writeNoResponse: Boolean = false,
                           read: Boolean = false, notify: Boolean = false) =
            BleCharacteristicInfo(uuid, read, write, writeNoResponse, notify)

        /** Nordic UART as CoreBluetooth reports it: 128-bit, upper case. */
        val AURORA_SERVICES = listOf(
            BleServiceInfo(
                BoardBleUuids.DATA_TRANSFER_SERVICE,
                listOf(characteristic(BoardBleUuids.DATA_TRANSFER_CHAR, write = true, writeNoResponse = true)),
            ),
        )
    }
}
