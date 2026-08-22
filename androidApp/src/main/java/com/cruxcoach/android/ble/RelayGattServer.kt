package com.cruxcoach.android.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.cruxcoach.domain.relay.CompleteClimb
import com.cruxcoach.domain.relay.RelayFrameReassembler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A complete climb an official-app client wrote to the emulated board char.
 *
 * [pendingResponse] is the ATT transaction still waiting on a verdict, when the
 * guest asked for one. Whoever consumes this owes it exactly one
 * [RelayGattServer.settle] — the response is not sent until the relay knows
 * whether it will deliver the bytes.
 *
 * One write can complete more than one climb, and then every one of them
 * carries the same transaction and owes it its own report. The guest is
 * answered when the last of them has reported, and negative if any of them
 * failed — see [RelayGattServer.settle].
 */
data class RelayInboundClimb(
    val deviceAddress: String,
    val climb: CompleteClimb,
    val pendingResponse: Int? = null,
    /**
     * When this write runs out of time, on the monotonic clock, absolute.
     *
     * Set where the bytes arrived and carried from there, because the guest's
     * answer is timed from the same instant. A consumer that started its own
     * clock — which is what the manager did — measured from after the flow hop,
     * the catalogue lookup and two preference reads, so it could still be
     * inside "its" twenty seconds and about to write a board for somebody who
     * had already been told the write failed.
     *
     * [Long.MAX_VALUE] means unbounded, which is only ever a test's choice.
     */
    val deadlineAtMs: Long = Long.MAX_VALUE,
)

/** One Nordic-UART write exactly as a guest sent it. MoonBoard uses an ASCII
 * stream instead of Aurora's framed packets, so these writes are forwarded
 * byte-for-byte without [RelayFrameReassembler]. */
data class RelayInboundWrite(val deviceAddress: String, val value: ByteArray)

/**
 * The board-emulation GATT server of CruxRelay (FEAT-044).
 *
 * Mirrors the write-only board: exposes the Nordic UART service
 * ([BoardBleUuids.DATA_TRANSFER_SERVICE] = 6E400001) with a single writable
 * characteristic ([BoardBleUuids.DATA_TRANSFER_CHAR] = 6E400002,
 * WRITE | WRITE_NO_RESPONSE) and NO read/notify/CCCD. The official Kilter app
 * connects here (it found us by the 4488B571 advertising UUID) and writes climb
 * packets exactly as it would to a real board.
 *
 * Inbound writes are reassembled PER CLIENT ([RelayFrameReassembler]) into
 * complete climbs and emitted on [climbs]; [CruxRelayManager] forwards them
 * byte-faithfully to the real board. Structure
 * mirrors [SessionGattServer] (server lifecycle, device tracking, stale-liveness
 * check) minus everything notify/read.
 */
class RelayGattServer(private val context: Context) {

    companion object {
        private const val TAG = "CruxRelay/Server"
        /**
         * The server's own ceiling. Not the answer on its own: the radio is
         * shared with the mesh and the board link, so [availableSlots] is what
         * actually decides — this only bounds it.
         */
        const val MAX_CONNECTED_DEVICES = 4

        /**
         * How long one relayed guest write may take, end to end.
         *
         * One number for the ATT transaction and for the operation behind it,
         * because two numbers drift and the gap between them is where a guest
         * gets an error for a climb that then appears on the wall. Sized for a
         * real multi-chunk write — several `WRITE_TIMEOUT_MS` waits plus the
         * canonical round trips — and still comfortably inside the ATT
         * transaction timeout of 30 s.
         *
         * Past it the relay stops *starting* work; a board write already under
         * way is never cancelled, because a half-written climb leaves the wall
         * in a state the protocol has no way to undo. A write that lands late
         * is committed canonically and made good by the success replay: the
         * guest's retry is answered `AlreadyDelivered`.
         */
        const val RELAY_OPERATION_DEADLINE_MS = 20_000L
        private const val LIVENESS_CHECK_INTERVAL_MS = 10_000L
        private const val CLIENT_DISCONNECT_GRACE_MS = 300L
        private const val SERVICE_REGISTRATION_TIMEOUT_MS = 2_000L
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private var gattServer: BluetoothGattServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var livenessJob: Job? = null
    private var serviceRegistration: CompletableDeferred<Boolean>? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /**
     * Whether a guest write can be delivered at all, answered synchronously.
     *
     * Called on the GATT callback thread, so it must not touch the database or
     * suspend — it is the board-path question ("is there a wall on the other
     * side of me right now"), not the catalogue question ("is this a climb
     * this board can show"), which needs IO and happens after reassembly in
     * [CruxRelayManager]. Null means admit, so a server nobody has configured
     * behaves exactly as it did before.
     */
    var admitWrite: (() -> Boolean)? = null

    private val _climbs = MutableSharedFlow<RelayInboundClimb>(extraBufferCapacity = 64)
    val climbs: SharedFlow<RelayInboundClimb> = _climbs.asSharedFlow()

    private val _writes = MutableSharedFlow<RelayInboundWrite>(extraBufferCapacity = 256)
    val writes: SharedFlow<RelayInboundWrite> = _writes.asSharedFlow()

    private val _connectionEvents = MutableSharedFlow<GattConnectionEvent>(extraBufferCapacity = 32)
    val connectionEvents: SharedFlow<GattConnectionEvent> = _connectionEvents.asSharedFlow()

    // All BLE callbacks run on the binder thread — guard shared state on [lock].
    private val lock = Any()
    private val connectedDevices = mutableSetOf<String>()

    /**
     * ATT transactions whose verdict is not in yet.
     *
     * A with-response write that completes a climb is answered when the relay
     * has decided what happens to it, not when the bytes arrive. Everything
     * that decides — board, layout and angle against the connected board, the
     * rate limit, deduplication, the canonical intent barrier, the board write
     * itself — is asynchronous and was previously reported as success before it
     * had run.
     */
    /**
     * One waiting ATT transaction, with a deadline and a verdict of its own.
     *
     * [outstanding] is how many commands of that one write have still to
     * report. A feed can complete several climbs
     * ([RelayFrameReassembler.offer]), and each of them is decided separately
     * and asynchronously; the transaction belongs to the write, not to any one
     * climb in it. [accepted] therefore accumulates: success is what is left
     * when nothing in the write failed.
     */
    private class PendingResponse(
        val device: BluetoothDevice?,
        val deadlineMs: Long,
        var outstanding: Int,
        var accepted: Boolean = true,
    )

    private val pendingResponses = HashMap<Int, PendingResponse>()
    private var responseTimeoutJob: Job? = null

    /**
     * How an ATT verdict actually leaves this process.
     *
     * A seam rather than a direct call, because the contract this class now
     * carries — which write gets which status, and when — is the part worth
     * testing, and a real `BluetoothGattServer` cannot be asked what it
     * answered.
     */
    @VisibleForTesting
    internal var attResponder: (BluetoothDevice?, Int, Boolean) -> Unit =
        { device, requestId, accepted ->
            val status = if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
            runCatching { gattServer?.sendResponse(device, requestId, status, 0, null) }
                .onFailure { Log.w(TAG, "could not answer ATT request $requestId", it) }
        }

    /** Emission seams, so "the buffer was full" is a branch a test can reach. */
    @VisibleForTesting
    internal var emitClimb: (RelayInboundClimb) -> Boolean = { _climbs.tryEmit(it) }

    @VisibleForTesting
    internal var emitWrite: (RelayInboundWrite) -> Boolean = { _writes.tryEmit(it) }

    /**
     * How long a guest's write may wait for a verdict.
     *
     * Per request, on a monotonic clock, and the *same* number the relay gives
     * the operation itself — see [RELAY_OPERATION_DEADLINE_MS]. Six seconds was
     * shorter than a legitimate board write: one BLE chunk alone may wait five
     * (`BoardBleConnection.WRITE_TIMEOUT_MS`), and a climb is many chunks. The
     * guest was told "failed" while their climb went on to reach the wall.
     */
    private val responseDeadlineMs = RELAY_OPERATION_DEADLINE_MS

    @VisibleForTesting
    internal var monotonicMs: () -> Long = SystemClock::elapsedRealtime

    /**
     * How many guests the radio can still take, asked at accept time.
     *
     * A guest arriving is a radio slot leaving, and the mesh is what makes the
     * cell converge — so this is re-evaluated per connection rather than once
     * at start, and the relay yields rather than the mesh being starved.
     * Defaults to the server ceiling so a server used without a manager
     * behaves as it always did.
     */
    var availableSlots: () -> Int = { MAX_CONNECTED_DEVICES - getConnectedCount() }
    private val reassemblers = HashMap<String, RelayFrameReassembler>()

    /**
     * Address of the phone's own board link, when there is one.
     *
     * Android reports every device already connected to the local adapter to a
     * freshly opened GATT server — including the board WE are the client of.
     * It arrives as a "client connected" before [start] has even returned, and
     * it never disconnects while the board link is up, so the chip read
     * "2 verbunden" with a single real client and settled at 1 with none.
     * The board is a peer of this relay, never a client of it.
     */
    var boardAddressProvider: () -> String? = { null }

    private fun isOwnBoard(address: String): Boolean =
        boardAddressProvider()?.equals(address, ignoreCase = true) == true

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid == BoardBleUuids.DATA_TRANSFER_SERVICE) {
                val ready = status == BluetoothGatt.GATT_SUCCESS
                Log.d(TAG, "Relay service registration finished: status=$status ready=$ready")
                serviceRegistration?.complete(ready)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (isOwnBoard(address)) {
                Log.d(TAG, "Ignoring own board link on the relay server: $address (newState=$newState)")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) {
                        if (connectedDevices.size >= MAX_CONNECTED_DEVICES ||
                            runCatching { availableSlots() }.getOrDefault(0) <= 0
                        ) {
                            Log.w(TAG, "Max devices reached, rejecting $address")
                            gattServer?.cancelConnection(device)
                            return
                        }
                        connectedDevices.add(address)
                        reassemblers[address] = RelayFrameReassembler()
                    }
                    // Count + members: the chip shows this number, and a stale
                    // entry is the difference between "one client" and "two".
                    Log.d(TAG, "Client connected: $address — devices=$connectedDevices")
                    _connectionEvents.tryEmit(GattConnectionEvent.Connected(address))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    synchronized(lock) {
                        connectedDevices.remove(address)
                        reassemblers.remove(address)?.reset()
                    }
                    Log.d(TAG, "Client disconnected: $address — devices=$connectedDevices")
                    _connectionEvents.tryEmit(GattConnectionEvent.Disconnected(address))
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean,
            offset: Int, value: ByteArray?
        ) {
            // What an ATT answer means here, in full, because the previous
            // version documented a weaker contract than the protocol allows and
            // then failed to keep even that one:
            //
            //  - WRITE_NO_RESPONSE promises nothing; there is no answer to give.
            //  - A with-response write that completes nothing is answered at
            //    once with success, which means "received and buffered" and
            //    cannot mean more, because nothing is decidable yet.
            //  - A with-response write that completes one or more climbs is
            //    answered when the relay knows what happens to *all* of them —
            //    the board, layout and angle checks, the rate limit,
            //    deduplication, the canonical intent barrier and the board
            //    write itself all run first, for each. A GATT server may hold a
            //    response open; the previous claim that the result "cannot ride
            //    in the GATT response" was a limit of the shape I had built,
            //    not of the protocol.
            //  - Anything refused, dropped or unanswered is an ATT error.
            handleGuestWrite(
                device = device,
                address = device.address,
                requestId = requestId,
                responseNeeded = responseNeeded,
                isBoardCharacteristic = characteristic.uuid == BoardBleUuids.DATA_TRANSFER_CHAR,
                value = value,
            )
        }
    }

    /**
     * One guest write, and the ATT answer it earns.
     *
     * Separated from the callback so the contract can be driven directly: the
     * decision of which write gets which status, and when, is the part that
     * went wrong, and it is not reachable through a real GATT stack.
     */
    @VisibleForTesting
    internal fun handleGuestWrite(
        device: BluetoothDevice?,
        address: String,
        requestId: Int,
        responseNeeded: Boolean,
        isBoardCharacteristic: Boolean,
        value: ByteArray?,
    ) {
            val admitted = admitWrite?.invoke() ?: true
            if (!admitted) {
                Log.i(TAG, "refusing a write from $address: no usable board path")
                synchronized(lock) { reassemblers[address]?.reset() }
                respond(device, requestId, responseNeeded, accepted = false)
                return
            }
            if (!isBoardCharacteristic || value == null) {
                respond(device, requestId, responseNeeded, accepted = true)
                return
            }
            // Preserve the exact write for protocols such as MoonBoard.
            // CruxRelayManager selects this stream only when the physical
            // board is not using Aurora packet framing.
            if (!emitWrite(RelayInboundWrite(address, value.copyOf()))) {
                // Dropped on the floor. Reporting that as a delivered write is
                // the plainest lie this server could tell.
                Log.w(TAG, "writes buffer full — dropping a write from $address")
                respond(device, requestId, responseNeeded, accepted = false)
                return
            }
            // Reassemble per client; a complete climb (ONLY / FIRST..LAST)
            // may span many writes. Never act on a partial write.
            // `getOrPut` rather than a lookup: a write from a device whose
            // connect event this server never saw used to reassemble into
            // nothing at all, silently, for as long as the link lasted.
            val completed = synchronized(lock) {
                reassemblers.getOrPut(address) { RelayFrameReassembler() }.offer(value)
            }
            if (completed.isEmpty()) {
                // A fragment. "Received" is the whole of what it can mean.
                respond(device, requestId, responseNeeded, accepted = true)
                return
            }
            // The last write of a climb carries the verdict for the whole of
            // it — and for everything else that write completed. One request
            // has one answer, but that answer is about the whole write: a feed
            // that finishes two climbs was previously answered after the
            // first, so a valid climb could report `GATT_SUCCESS` while a
            // second climb of the same write was still to be refused for
            // layout, angle, pacing, a handover or a refused commit.
            // One instant for the whole write: the ATT sweep and everything
            // downstream measure from here.
            val deadlineAt = monotonicMs() + responseDeadlineMs
            val pending = if (responseNeeded) requestId else null
            if (pending != null) {
                // Registered *before* the first emission, because a collector
                // may settle synchronously inside it. Registering after — which
                // is what this did — meant the verdict arrived to find nothing
                // waiting, was dropped, and the request was then failed by the
                // deadline despite having been handled. And registered with the
                // whole count, so an early settle cannot finish the transaction
                // while later climbs of the same write are still to be emitted.
                registerPending(requestId, device, deadlineAt, outstanding = completed.size)
            }
            completed.forEach { climb ->
                if (!emitClimb(RelayInboundClimb(address, climb, pending, deadlineAt))) {
                    Log.w(TAG, "climbs buffer full — dropping a climb from $address")
                    // Nothing is going to decide this one, so it reports itself
                    // — as the failure a dropped climb is. The write is still
                    // answered exactly once, when its last command reports,
                    // whether that report came from the relay or from here.
                    settle(pending, accepted = false)
                }
            }
    }

    @SuppressLint("MissingPermission")
    suspend fun start(): Boolean {
        if (_isRunning.value) return true
        val manager = bluetoothManager ?: return false
        val server = manager.openGattServer(context, gattCallback) ?: run {
            Log.e(TAG, "Failed to open GATT server"); return false
        }
        gattServer = server

        val service = BluetoothGattService(
            BoardBleUuids.DATA_TRANSFER_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val writeChar = BluetoothGattCharacteristic(
            BoardBleUuids.DATA_TRANSFER_CHAR,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(writeChar)

        val registration = CompletableDeferred<Boolean>()
        serviceRegistration = registration
        if (!server.addService(service)) {
            Log.e(TAG, "Failed to add relay GATT service")
            serviceRegistration = null
            server.close(); gattServer = null; return false
        }
        val serviceReady = withTimeoutOrNull(SERVICE_REGISTRATION_TIMEOUT_MS) {
            registration.await()
        } == true
        serviceRegistration = null
        if (!serviceReady) {
            Log.e(TAG, "Relay GATT service was not registered before timeout")
            server.close(); gattServer = null; return false
        }
        _isRunning.value = true

        livenessJob?.cancel()
        livenessJob = scope.launch {
            while (isActive) { delay(LIVENESS_CHECK_INTERVAL_MS); checkForStaleConnections() }
        }
        Log.d(TAG, "Relay GATT server started")
        return true
    }

    @SuppressLint("MissingPermission")
    suspend fun stop() {
        // Before the running guard: whatever is waiting on a verdict is not
        // going to get one, and saying so beats letting the guest's ATT
        // transaction expire — whichever state this server is in.
        responseTimeoutJob?.cancel(); responseTimeoutJob = null
        val unanswered = synchronized(lock) {
            val entries = pendingResponses.entries.toList()
            pendingResponses.clear()
            entries
        }
        unanswered.forEach { sendVerdict(it.value.device, it.key, accepted = false) }
        if (!_isRunning.value) return
        serviceRegistration?.cancel(); serviceRegistration = null
        livenessJob?.cancel(); livenessJob = null

        // BluetoothGattServer.close() only releases the local server object on
        // some Android stacks; it does not reliably tell existing centrals
        // that their link is gone. Cancel every client explicitly first so a
        // guest CruxCoach immediately leaves CONNECTED instead of keeping a
        // stale "connected to CruxRelay" state until the supervision timeout.
        val server = gattServer
        val clientAddresses = synchronized(lock) { connectedDevices.toList() }
        clientAddresses.forEach { address ->
            runCatching {
                bluetoothManager?.adapter?.getRemoteDevice(address)?.let { device ->
                    server?.cancelConnection(device)
                }
            }.onFailure { Log.w(TAG, "Could not disconnect relay client $address", it) }
        }
        if (clientAddresses.isNotEmpty()) {
            // Let the controller put the disconnect over the air before close
            // tears down the server callback and its underlying native slot.
            delay(CLIENT_DISCONNECT_GRACE_MS)
        }
        synchronized(lock) {
            connectedDevices.clear()
            reassemblers.values.forEach { it.reset() }
            reassemblers.clear()
        }
        server?.close(); gattServer = null
        _isRunning.value = false
        Log.d(TAG, "Relay GATT server stopped")
    }

    /**
     * One command of a deferred write has been decided.
     *
     * Called once per command the write completed — which is once per
     * [RelayInboundClimb] carrying this transaction. The ATT response goes out
     * when the last of them has reported, and it is a success only if none of
     * them failed: a write is delivered when everything in it was, and a guest
     * told otherwise would have no way to know which half to send again.
     *
     * Reports beyond the last are ignored rather than sending a second ATT
     * response for one request.
     */
    fun settle(requestId: Int?, accepted: Boolean) {
        if (requestId == null) return
        val finished = synchronized(lock) {
            val waiting = pendingResponses[requestId] ?: return
            if (!accepted) waiting.accepted = false
            waiting.outstanding -= 1
            if (waiting.outstanding > 0) return
            pendingResponses.remove(requestId)
            waiting
        }
        sendVerdict(finished.device, requestId, finished.accepted)
    }

    private fun registerPending(
        requestId: Int,
        device: BluetoothDevice?,
        deadlineAtMs: Long,
        outstanding: Int,
    ) {
        synchronized(lock) {
            pendingResponses[requestId] = PendingResponse(device, deadlineAtMs, outstanding)
        }
        scheduleResponseSweep()
    }

    private fun respond(
        device: BluetoothDevice?,
        requestId: Int,
        responseNeeded: Boolean,
        accepted: Boolean,
    ) {
        if (!responseNeeded) return
        sendVerdict(device, requestId, accepted)
    }

    private fun sendVerdict(device: BluetoothDevice?, requestId: Int, accepted: Boolean) {
        attResponder(device, requestId, accepted)
    }

    /**
     * Nothing waits forever.
     *
     * A verdict that never arrives — a collector that died, a board that went
     * away mid-decision — is reported as a failure while the ATT transaction is
     * still alive, rather than being left to time out and look like a broken
     * link.
     */
    private fun scheduleResponseSweep() {
        if (responseTimeoutJob?.isActive == true) return
        responseTimeoutJob = scope.launch {
            while (isActive) {
                // Sleep until the *earliest* deadline, then fail only what has
                // genuinely run out. A single shared timer that cleared every
                // pending request gave a late arrival whatever was left of
                // somebody else's window.
                val nextDeadline = synchronized(lock) {
                    pendingResponses.values.minOfOrNull { it.deadlineMs }
                } ?: return@launch
                val wait = nextDeadline - monotonicMs()
                if (wait > 0) delay(wait)
                val now = monotonicMs()
                val expired = synchronized(lock) {
                    val due = pendingResponses.filterValues { it.deadlineMs <= now }
                    due.keys.forEach { pendingResponses.remove(it) }
                    due
                }
                if (expired.isNotEmpty()) {
                    Log.w(TAG, "${expired.size} relay write(s) went unanswered; failing them closed")
                    expired.forEach { sendVerdict(it.value.device, it.key, accepted = false) }
                }
            }
        }
    }

    fun getConnectedCount(): Int = synchronized(lock) { connectedDevices.size }

    /** Who is attached right now — used to tell a reconnect from a second guest. */
    fun connectedAddresses(): Set<String> = synchronized(lock) { connectedDevices.toSet() }

    @SuppressLint("MissingPermission")
    private fun checkForStaleConnections() {
        val manager = bluetoothManager ?: return
        val osConnected = try {
            manager.getConnectedDevices(BluetoothProfile.GATT_SERVER).map { it.address }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query connected devices", e); return
        }
        val stale = synchronized(lock) {
            val s = connectedDevices.filter { it !in osConnected }
            for (a in s) { connectedDevices.remove(a); reassemblers.remove(a)?.reset() }
            s
        }
        for (a in stale) {
            Log.w(TAG, "Stale relay connection: $a")
            _connectionEvents.tryEmit(GattConnectionEvent.Disconnected(a))
        }
    }
}
