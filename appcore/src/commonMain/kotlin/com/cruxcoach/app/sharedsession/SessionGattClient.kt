// Port of androidApp/.../ble/SessionGattClient.kt (0e1bad0c6) onto the shared [BleCentral]
// (CoreBluetooth on iOS). Same lifecycle — connect, discover, subscribe to the five notifying
// characteristics, CONNECTED; serialized reads and writes with Android's timeouts; explicit
// disconnect. Differences forced by CoreBluetooth: the ATT MTU cannot be requested (iOS
// negotiates it), only observed through maximumWriteLength; a read of a characteristic that is
// already notifying arrives through the notification path, so any value for the awaited
// characteristic completes the read.
package com.cruxcoach.app.sharedsession

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.ble.BleCentral
import com.cruxcoach.app.ble.BleCentralListener
import com.cruxcoach.app.ble.BleServiceInfo
import com.cruxcoach.app.ble.BoardBleUuids
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal enum class SessionClientState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * GATT client of a participant. [scope] must be single-threaded (the main scope on iOS): every
 * listener event hops onto it, so the fields below are only touched from one thread. Every
 * request ends in an answer (false, null or DISCONNECTED), never in an exception.
 */
internal class SessionGattClient(
    private val central: BleCentral,
    private val scope: CoroutineScope,
) : BleCentralListener {

    companion object {
        const val CONNECTION_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        private const val DEFAULT_ATT_MTU = 23

        private val NOTIFY_UUIDS = listOf(
            SessionGattUuids.QUEUE_EVENT,
            SessionGattUuids.QUEUE_STATE,
            SessionGattUuids.SESSION_INFO,
            SessionGattUuids.CURRENT_CLIMB,
            SessionGattUuids.PARTICIPANT_LIST,
        )
        private val INITIAL_READ_UUIDS = listOf(
            SessionGattUuids.SESSION_INFO,
            SessionGattUuids.QUEUE_STATE,
            SessionGattUuids.CURRENT_CLIMB,
            SessionGattUuids.PARTICIPANT_LIST,
        )
    }

    private val _connectionState = MutableStateFlow(SessionClientState.DISCONNECTED)
    val connectionState: StateFlow<SessionClientState> = _connectionState.asStateFlow()

    // Sized like Android's: a multi-device burst must not drop a notification.
    private val _queueEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val queueEvents: SharedFlow<ByteArray> = _queueEvents.asSharedFlow()
    private val _sessionInfoUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val sessionInfoUpdates: SharedFlow<ByteArray> = _sessionInfoUpdates.asSharedFlow()
    private val _currentClimbUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val currentClimbUpdates: SharedFlow<ByteArray> = _currentClimbUpdates.asSharedFlow()
    private val _participantListUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val participantListUpdates: SharedFlow<ByteArray> = _participantListUpdates.asSharedFlow()
    private val _queueStateUpdates = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val queueStateUpdates: SharedFlow<ByteArray> = _queueStateUpdates.asSharedFlow()

    private var device: String? = null
    private var commandWritable = false
    private var readable = emptySet<String>()
    private var notifiable = emptySet<String>()
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var readDeferred: CompletableDeferred<Boolean>? = null
    private var readUuid: String? = null
    private var notifyDeferred: CompletableDeferred<Boolean>? = null
    private var timeoutJob: Job? = null
    private var setupJob: Job? = null

    /** CoreBluetooth takes one request at a time per peripheral in this abstraction. */
    private val operationMutex = Mutex()

    /** The peripheral identifier of the current (or pending) host link. */
    val connectedIdentifier: String? get() = device

    fun connect(identifier: String) {
        if (_connectionState.value != SessionClientState.DISCONNECTED) disconnect()
        _connectionState.value = SessionClientState.CONNECTING
        device = identifier
        commandWritable = false
        readable = emptySet()
        notifiable = emptySet()
        central.connect(identifier)
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            if (_connectionState.value == SessionClientState.CONNECTING && device == identifier) disconnect()
        }
    }

    fun disconnect() {
        timeoutJob?.cancel()
        timeoutJob = null
        setupJob?.cancel()
        setupJob = null
        failPending()
        val id = device
        device = null
        commandWritable = false
        _connectionState.value = SessionClientState.DISCONNECTED
        // The later onDisconnected no longer matches [device] and is ignored.
        if (id != null) central.cancelConnection(id)
    }

    suspend fun sendCommand(command: ByteArray): Boolean = operationMutex.withLock {
        val id = device ?: return false
        if (!commandWritable) return false
        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred
        central.write(id, SessionGattUuids.SERVICE, SessionGattUuids.QUEUE_COMMAND, command, withResponse = true)
        val ok = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } == true
        if (writeDeferred === deferred) writeDeferred = null
        ok
    }

    /**
     * True when this link carries the payload in ONE write. The host's GATT server does not
     * handle prepared (long) writes, so the limit is the single-packet size ATT_MTU-3, which
     * CoreBluetooth reports as the without-response maximum.
     */
    fun supportsCommandSize(size: Int): Boolean {
        val id = device ?: return false
        val limit = central.maximumWriteLength(id, withResponse = false)
        return size <= (if (limit > 0) limit else DEFAULT_ATT_MTU - 3)
    }

    /** Reads session info, queue page 0, current climb and participants, one after another. */
    suspend fun readInitialState() {
        for (uuid in INITIAL_READ_UUIDS) read(uuid)
    }

    /** One more read of queue page 0 (a long read, so never truncated by the MTU). */
    suspend fun readQueueState(): Boolean = read(SessionGattUuids.QUEUE_STATE)

    private suspend fun read(uuid: String): Boolean = operationMutex.withLock {
        val id = device ?: return false
        if (uuid !in readable) return false
        val deferred = CompletableDeferred<Boolean>()
        readDeferred = deferred
        readUuid = uuid
        central.read(id, SessionGattUuids.SERVICE, uuid)
        val ok = withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() } == true
        if (readDeferred === deferred) {
            readDeferred = null
            readUuid = null
        }
        ok
    }

    private fun failPending() {
        writeDeferred?.complete(false)
        readDeferred?.complete(false)
        notifyDeferred?.complete(false)
    }

    private fun dropLink() {
        timeoutJob?.cancel()
        timeoutJob = null
        setupJob?.cancel()
        setupJob = null
        failPending()
        device = null
        commandWritable = false
        _connectionState.value = SessionClientState.DISCONNECTED
    }

    private suspend fun subscribe(identifier: String) {
        for (uuid in NOTIFY_UUIDS) {
            if (uuid !in notifiable) continue
            operationMutex.withLock {
                if (device != identifier) return
                val deferred = CompletableDeferred<Boolean>()
                notifyDeferred = deferred
                central.setNotify(identifier, SessionGattUuids.SERVICE, uuid, true)
                // Like Android: a subscription that does not confirm is not fatal.
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { deferred.await() }
                if (notifyDeferred === deferred) notifyDeferred = null
            }
        }
    }

    private fun emit(uuid: String, value: ByteArray) {
        val payload = value.copyOf()
        val flow = when {
            same(uuid, SessionGattUuids.QUEUE_EVENT) -> _queueEvents
            same(uuid, SessionGattUuids.QUEUE_STATE) -> _queueStateUpdates
            same(uuid, SessionGattUuids.SESSION_INFO) -> _sessionInfoUpdates
            same(uuid, SessionGattUuids.CURRENT_CLIMB) -> _currentClimbUpdates
            same(uuid, SessionGattUuids.PARTICIPANT_LIST) -> _participantListUpdates
            else -> return
        }
        flow.tryEmit(payload)
    }

    private fun onValue(identifier: String, uuid: String, value: ByteArray?) {
        if (identifier != device) return
        if (value != null) emit(uuid, value)
        val awaited = readUuid
        if (awaited != null && same(awaited, uuid)) {
            readDeferred?.complete(value != null)
        }
    }

    private fun same(a: String, b: String) = BoardBleUuids.sameUuid(a, b)

    // ── BleCentralListener: hop onto the scope, ignore other peripherals ──

    override fun onAdapterState(state: BleAdapterState) {
        scope.launch {
            if (state != BleAdapterState.POWERED_ON && device != null) dropLink()
        }
    }

    override fun onAdvertisement(identifier: String, advertisedName: String?, peripheralName: String?, rssi: Int) = Unit

    override fun onConnected(identifier: String) {
        scope.launch {
            if (identifier != device || _connectionState.value != SessionClientState.CONNECTING) return@launch
            central.discoverServices(identifier)
        }
    }

    override fun onDisconnected(identifier: String) {
        scope.launch { if (identifier == device) dropLink() }
    }

    override fun onServicesDiscovered(identifier: String, services: List<BleServiceInfo>?) {
        scope.launch {
            if (identifier != device || _connectionState.value != SessionClientState.CONNECTING) return@launch
            val service = services?.firstOrNull { same(it.uuid, SessionGattUuids.SERVICE) }
            if (service == null) {
                // Not a session host (or discovery failed): end the link like Android does.
                disconnect()
                return@launch
            }
            fun canonical(uuid: String): String? =
                (NOTIFY_UUIDS + SessionGattUuids.QUEUE_COMMAND).firstOrNull { same(it, uuid) }
            commandWritable = service.characteristics.any { it.canWrite && same(it.uuid, SessionGattUuids.QUEUE_COMMAND) }
            readable = service.characteristics.filter { it.canRead }.mapNotNull { canonical(it.uuid) }.toSet()
            notifiable = service.characteristics.filter { it.canNotify }.mapNotNull { canonical(it.uuid) }.toSet()
            setupJob = scope.launch {
                subscribe(identifier)
                if (device == identifier && _connectionState.value == SessionClientState.CONNECTING) {
                    timeoutJob?.cancel()
                    timeoutJob = null
                    _connectionState.value = SessionClientState.CONNECTED
                }
            }
        }
    }

    override fun onWriteCompleted(identifier: String, characteristicUuid: String, success: Boolean) {
        scope.launch { if (identifier == device) writeDeferred?.complete(success) }
    }

    override fun onCharacteristicRead(identifier: String, characteristicUuid: String, value: ByteArray?) {
        val copy = value?.copyOf()
        scope.launch { onValue(identifier, characteristicUuid, copy) }
    }

    override fun onNotifyStateChanged(identifier: String, characteristicUuid: String, enabled: Boolean, success: Boolean) {
        scope.launch { if (identifier == device) notifyDeferred?.complete(success && enabled) }
    }

    override fun onNotification(identifier: String, characteristicUuid: String, value: ByteArray) {
        val copy = value.copyOf()
        scope.launch { onValue(identifier, characteristicUuid, copy) }
    }
}
