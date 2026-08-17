package com.cruxcoach.android.fips

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.os.Build
import com.cruxcoach.android.boardcell.AuthenticatedMeshLink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AuthenticatedFipsMessage(val senderNpub: String, val payload: ByteArray)
data class FipsPeer(val npub: String, val connected: Boolean, val transport: String, val lastSeenMs: Long)
enum class FipsConnectionStage { IDLE, ADVERTISEMENT_SEEN, CHANNEL_OPEN, PEER_AUTHENTICATED, DIRECT_AUTHENTICATED }
data class FipsConnectionProgress(
    val realmId: String? = null,
    val cellId: String? = null,
    val stage: FipsConnectionStage = FipsConnectionStage.IDLE,
    val updatedAtMs: Long = 0,
)
data class FipsNearbyMesh(
    val address: String,
    val realmTag: String,
    val cellTag: String,
    val rssi: Int,
    val lastSeenMs: Long,
    val matchesActiveRealm: Boolean,
    val joinableBoardCellId: String? = null,
    val boardName: String? = null,
)

internal class FipsNearbyMeshTracker(private val ttlMs: Long = 8_000L) {
    private var meshes = emptyList<FipsNearbyMesh>()

    @Synchronized
    fun record(mesh: FipsNearbyMesh): List<FipsNearbyMesh> {
        val cutoff = mesh.lastSeenMs - ttlMs
        val previous = meshes.firstOrNull { it.realmTag == mesh.realmTag && it.cellTag == mesh.cellTag }
        val merged = mesh.copy(
            boardName = mesh.boardName ?: previous?.boardName,
            joinableBoardCellId = mesh.joinableBoardCellId ?: previous?.joinableBoardCellId,
        )
        // A mesh can advertise through several members. Present one board card,
        // retaining the strongest/current address only as a proximity hint.
        meshes = (meshes.filter { it.lastSeenMs >= cutoff &&
            !(it.realmTag == mesh.realmTag && it.cellTag == mesh.cellTag) } + merged)
            .sortedWith(compareByDescending<FipsNearbyMesh> { it.matchesActiveRealm }.thenByDescending { it.rssi })
        return meshes
    }

    @Synchronized
    fun prune(nowMs: Long): List<FipsNearbyMesh> {
        meshes = meshes.filter { nowMs - it.lastSeenMs <= ttlMs }
        return meshes
    }

    @Synchronized
    fun clear() { meshes = emptyList() }
}

@Singleton
class FipsMeshRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: FipsRealmKeyStore,
) : AuthenticatedMeshLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assembler = FipsFrameAssembler()
    private val owners = FipsRuntimeOwners()
    private val rejectingRealmLink = AtomicBoolean(false)
    private val restartingForPermissions = AtomicBoolean(false)
    private val suspendedForBulkTransfer = AtomicBoolean(false)
    private var restartAfterBulkTransfer = false
    private var radio: FipsBleRadio? = null
    private var bridge = 0L
    private var receiveJob: Job? = null
    private var peerJob: Job? = null
    private var permissionWatchJob: Job? = null
    private var discoveryPruneJob: Job? = null
    private var passiveDiscovery: FipsNearbyDiscovery? = null
    @Volatile private var discoveryRequested = false
    @Volatile private var realm: FipsRealmContext? = null
    @Volatile private var permissionPromptedRealm: String? = null
    private val permissionRequestChannel = Channel<List<String>>(Channel.CONFLATED)
    /** One automatic request per active realm; denial never creates a prompt loop. */
    val permissionRequests = permissionRequestChannel.receiveAsFlow()
    private val validatedDirectPeers = mutableSetOf<String>()
    private val helloSentAt = mutableMapOf<String, Long>()
    private val loggedNativeBleAttempts = linkedSetOf<String>()
    private val json = Json { ignoreUnknownKeys = false }
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _bluetoothAvailable = MutableStateFlow(
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.isEnabled == true)
    val bluetoothAvailable = _bluetoothAvailable.asStateFlow()
    private val _messages = MutableSharedFlow<AuthenticatedFipsMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()
    private val _peers = MutableStateFlow<List<FipsPeer>>(emptyList())
    val peers = _peers.asStateFlow()
    private val _nearbyMeshes = MutableStateFlow<List<FipsNearbyMesh>>(emptyList())
    private val nearbyMeshTracker = FipsNearbyMeshTracker()
    /** CruxCoach FIPS advertisements observed by the active low-power scan.
     * Foreign realms are visible here but are never delivered to the native
     * node or admitted as transit peers. */
    val nearbyMeshes = _nearbyMeshes.asStateFlow()
    private val _connectionProgress = MutableStateFlow(FipsConnectionProgress())
    val connectionProgress = _connectionProgress.asStateFlow()
    private val crossProbeModeAnnounced = AtomicBoolean(false)
    override val localNpub: String get() = if (_running.value) runCatching { NativeFips.npub() }.getOrDefault("") else ""

    init {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF -> synchronized(this@FipsMeshRuntime) {
                        _bluetoothAvailable.value = false
                        passiveDiscovery?.stop(); passiveDiscovery = null
                        if (_running.value) shutdownNative()
                        FipsDebugLog.warning("runtime", "bluetooth_off",
                            "ownersActive" to owners.isActive(), "realm" to FipsDebugLog.id(realm?.realmId))
                    }
                    BluetoothAdapter.STATE_ON -> scope.launch {
                        _bluetoothAvailable.value = true
                        // Give GrapheneOS/Android's BLE stack a brief settle
                        // interval, then resume the retained realm/owner.
                        delay(1_000)
                        synchronized(this@FipsMeshRuntime) {
                            if (owners.isActive()) ensureStarted()
                            else if (discoveryRequested) startNearbyDiscovery()
                        }
                    }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    @Synchronized
    fun activateRealm(value: FipsRealmContext): Boolean {
        FipsDebugLog.event("runtime", "realm_activate_requested",
            "realm" to FipsDebugLog.id(value.realmId), "cell" to FipsDebugLog.id(value.boardCellId),
            "kind" to value.kind, "running" to _running.value)
        if (realm == value && _running.value) {
            FipsDebugLog.event("runtime", "realm_already_active", "npub" to FipsDebugLog.id(localNpub))
            return true
        }
        if (realm != value && _running.value) {
            FipsDebugLog.event("runtime", "realm_switch", "from" to FipsDebugLog.id(realm?.realmId),
                "to" to FipsDebugLog.id(value.realmId))
            receiveJob?.cancel(); receiveJob = null
            shutdownNative()
        }
        if (realm != value) {
            permissionPromptedRealm = null
            crossProbeModeAnnounced.set(false)
        }
        realm = value
        _connectionProgress.value = FipsConnectionProgress(value.realmId, value.boardCellId)
        stopNearbyDiscovery()
        return ensureStarted()
    }

    /** Discovery is intentionally independent from board ownership: opening
     * the mesh overview is enough to see public BoardCell meshes nearby. */
    @Synchronized
    fun startNearbyDiscovery() {
        discoveryRequested = true
        if (_running.value || suspendedForBulkTransfer.get() || passiveDiscovery != null ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val missing = FipsPermissionPolicy.missingPermissions(context)
        if (missing.isNotEmpty()) {
            permissionRequestChannel.trySend(missing)
            return
        }
        lateinit var candidate: FipsNearbyDiscovery
        candidate = FipsNearbyDiscovery(context, ::recordNearbyMesh) { errorCode ->
            passiveDiscoveryFailed(candidate, errorCode)
        }
        // Publish the generation before startScan: Android may report a scan
        // failure immediately from the callback. Assigning afterwards could
        // resurrect the already-failed scanner and wedge future retries.
        passiveDiscovery = candidate
        if (!candidate.start() && passiveDiscovery === candidate) passiveDiscovery = null
        if (passiveDiscovery === candidate) startDiscoveryPruning()
    }

    @Synchronized
    fun stopNearbyDiscovery() {
        discoveryRequested = false
        discoveryPruneJob?.cancel(); discoveryPruneJob = null
        passiveDiscovery?.stop()
        passiveDiscovery = null
    }

    @Synchronized
    fun endRealm(realmId: String) {
        if (realm?.realmId != realmId) {
            FipsDebugLog.warning("runtime", "realm_end_ignored", "requested" to FipsDebugLog.id(realmId),
                "active" to FipsDebugLog.id(realm?.realmId))
            return
        }
        FipsDebugLog.event("runtime", "realm_end", "realm" to FipsDebugLog.id(realmId))
        receiveJob?.cancel(); receiveJob = null
        shutdownNative()
        realm = null
        crossProbeModeAnnounced.set(false)
        keyStore.end(realmId)
    }

    @Synchronized
    fun ensureStarted(): Boolean {
        if (_running.value) return true
        if (!owners.isActive()) {
            FipsDebugLog.event("runtime", "start_blocked", "reason" to "no logical owner")
            return false
        }
        if (suspendedForBulkTransfer.get()) return false
        if (!_bluetoothAvailable.value) {
            FipsDebugLog.event("runtime", "start_blocked", "reason" to "bluetooth off")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            FipsDebugLog.event("runtime", "gatt_fallback_selected", "api" to Build.VERSION.SDK_INT,
                "reason" to "FIPS L2CAP requires API 29+")
            return false
        }
        val activeRealm = realm ?: run {
            FipsDebugLog.warning("runtime", "start_blocked", "reason" to "no active realm")
            return false
        }
        val missingPermissions = FipsPermissionPolicy.missingPermissions(context)
        FipsDebugLog.event("runtime", "native_start_begin",
            "api" to Build.VERSION.SDK_INT, "realm" to FipsDebugLog.id(activeRealm.realmId),
            "cell" to FipsDebugLog.id(activeRealm.boardCellId),
            "realmTag" to FipsDebugLog.tag(activeRealm.realmTag),
            "cellTag" to FipsDebugLog.tag(activeRealm.cellTag),
            "missingPermissions" to missingPermissions.joinToString().ifEmpty { "none" })
        if (missingPermissions.isNotEmpty() && permissionPromptedRealm != activeRealm.realmId) {
            permissionPromptedRealm = activeRealm.realmId
            permissionRequestChannel.trySend(missingPermissions)
        }
        if (missingPermissions.isNotEmpty()) {
            FipsDebugLog.event("runtime", "start_blocked", "reason" to "permissions missing")
            return false
        }
        return runCatching {
            val candidate = FipsBleRadio(context, activeRealm, ::recordNearbyMesh, ::recordConnectionStage)
            radio = candidate
            bridge = NativeFips.bleBridgeNew(candidate)
            check(bridge != 0L)
            candidate.bindBridge(bridge)
            check(NativeFips.start(keyStore.activate(activeRealm.realmId), MAX_DIRECT_CONNECTIONS))
            _running.value = true
            FipsDebugLog.event("runtime", "native_started", "npub" to FipsDebugLog.id(localNpub),
                "maxDirectPeers" to MAX_DIRECT_CONNECTIONS, "bridge" to bridge)
            receiveJob = scope.launch { receiveLoop() }
            peerJob = scope.launch { peerLoop() }
            runCatching { FipsMeshService.start(context) }
            true
        }.getOrElse {
            FipsDebugLog.warning("runtime", "native_start_failed", "error" to (it.message ?: it.javaClass.simpleName))
            shutdownNative()
            false
        }
    }

    /** Hold background-capable mesh operation for a session/cell/competition. */
    fun acquire(owner: String) {
        val change = owners.acquire(owner)
        FipsDebugLog.event("runtime", "owner_acquired", "owner" to owner,
            "owners" to change.count, "new" to change.changed)
        // Realm activation is the single startup edge. Keeping lease acquisition
        // side-effect free prevents a race that could start a stale realm while
        // the caller is switching to a competition or participant realm.
    }

    fun release(owner: String) {
        val change = owners.release(owner)
        FipsDebugLog.event("runtime", "owner_released", "owner" to owner,
            "owners" to change.count, "removed" to change.changed)
        if (change.becameIdle) {
            FipsMeshService.stop(context)
            scope.launch {
                synchronized(this@FipsMeshRuntime) {
                    if (!owners.isActive()) {
                        receiveJob?.cancel(); receiveJob = null
                        peerJob?.cancel(); peerJob = null
                        shutdownNative()
                    }
                }
            }
        }
    }

    /** Called by the Activity result callback; also covered by the watcher for grants from other UI. */
    fun onPermissionsChanged() { scope.launch {
        restartForGrantedPermissions()
        if (discoveryRequested && !_running.value) startNearbyDiscovery()
    } }

    override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean {
        if (!ensureStarted()) {
            FipsDebugLog.warning("runtime", "send_blocked", "peer" to FipsDebugLog.id(authenticatedPeerNpub),
                "bytes" to payload.size, "reason" to "runtime unavailable")
            return false
        }
        val fragments = FipsFrameCodec.fragment(payload, FipsFrameCodec.messageId(payload))
        val sent = sendBatchNative(authenticatedPeerNpub, fragments)
        FipsDebugLog.event("runtime", if (sent) "payload_sent" else "payload_send_failed",
            "peer" to FipsDebugLog.id(authenticatedPeerNpub), "bytes" to payload.size,
            "fragments" to fragments.size)
        return sent
    }

    override fun directAuthenticatedPeers(): Set<String> = _peers.value.asSequence()
        .filter { it.connected && it.transport == "ble" && it.npub in synchronized(validatedDirectPeers) {
            validatedDirectPeers.toSet()
        } }.map { it.npub }.toSet()

    override fun activeRealmId(): String? = realm?.realmId

    /** FIPS resolves simultaneous BLE probes from both peers by comparing the
     * authenticated node keys. Keep cross-probing enabled after membership is
     * established; otherwise half of all joiners lose their sole outbound
     * channel while the member never creates the required opposite channel. */
    @Synchronized
    fun settleActiveMembership(cellId: String) {
        if (realm?.boardCellId != cellId || !_running.value ||
            !crossProbeModeAnnounced.compareAndSet(false, true)) return
        FipsDebugLog.event("runtime", "membership_transport_active",
            "cell" to FipsDebugLog.id(cellId), "mode" to "fips_cross_probe")
    }

    @Synchronized
    fun shutdown() {
        stopNearbyDiscovery()
        receiveJob?.cancel(); receiveJob = null
        peerJob?.cancel(); peerJob = null
        shutdownNative()
    }

    /** Give an explicitly requested local APK/database transfer exclusive use
     * of the phone's radios and CPU. Logical owners stay registered so the
     * mesh can be restored when the share session ends. */
    @Synchronized
    fun suspendForBulkTransfer() {
        if (!suspendedForBulkTransfer.compareAndSet(false, true)) return
        restartAfterBulkTransfer = _running.value
        if (_running.value) {
            receiveJob?.cancel(); receiveJob = null
            shutdownNative()
        }
    }

    fun isSuspendedForBulkTransfer(): Boolean = suspendedForBulkTransfer.get()

    @Synchronized
    fun resumeAfterBulkTransfer() {
        if (!suspendedForBulkTransfer.compareAndSet(true, false)) return
        val shouldRestart = restartAfterBulkTransfer || owners.isActive()
        restartAfterBulkTransfer = false
        if (shouldRestart && ensureStarted() && owners.isActive()) {
            FipsMeshService.start(context)
        }
    }

    /** Recreate L2CAP listeners/scans after Android rebuilt the Bluetooth stack. */
    @Synchronized
    fun restartAfterBluetoothAvailable(): Boolean {
        val shouldRestart = _running.value || owners.isActive()
        if (!shouldRestart) return false
        // The pinned FIPS bridge is looked up per operation, but an already
        // running accept/scanner future still owns the previous bridge's
        // receiver and has no retirement signal. A bridge-only swap would
        // therefore look successful while discovery remained stuck. Keep the
        // bounded full restart until upstream exposes an explicit radio-reset
        // control-plane operation.
        receiveJob?.cancel(); receiveJob = null
        shutdownNative()
        return ensureStarted()
    }

    /** Rebuild a retained realm after its final remote member disappeared.
     *
     * Android can leave an authenticated FIPS transport entry alive briefly
     * after the underlying phone/app stopped producing BoardCell heartbeats.
     * That ghost entry rejects a fresh L2CAP channel from the same node as a
     * duplicate. Once no remote canonical member remains, rebuilding is safe
     * and makes the next explicit join start with an empty transport pool. */
    @Synchronized
    fun recycleIdleMeshTransport(reason: String): Boolean {
        val active = realm ?: return false
        if (!_running.value || !owners.isActive()) return false
        FipsDebugLog.event(
            "runtime", "idle_transport_recycle",
            "realm" to FipsDebugLog.id(active.realmId),
            "reason" to reason,
            "peers" to _peers.value.size,
        )
        receiveJob?.cancel(); receiveJob = null
        shutdownNative()
        return ensureStarted()
    }

    private fun shutdownNative() {
        FipsDebugLog.event("runtime", "native_shutdown", "running" to _running.value,
            "peers" to _peers.value.size, "bridge" to bridge)
        // Centralize worker cancellation so every shutdown path (realm switch,
        // permission restart, bulk transfer and last-owner release) retires the
        // old generation before a new one can be launched.
        receiveJob?.cancel(); receiveJob = null
        peerJob?.cancel(); peerJob = null
        permissionWatchJob?.cancel(); permissionWatchJob = null
        runCatching { NativeFips.stop() }
        radio?.shutdown(); radio = null
        if (bridge != 0L) runCatching { NativeFips.bleBridgeFree(bridge) }
        bridge = 0
        _running.value = false
        _peers.value = emptyList()
        nearbyMeshTracker.clear()
        _nearbyMeshes.value = emptyList()
        _connectionProgress.value = FipsConnectionProgress()
        synchronized(validatedDirectPeers) { validatedDirectPeers.clear() }
        synchronized(helloSentAt) { helloSentAt.clear() }
    }

    private fun watchForPermissionGrant(realmId: String) {
        if (permissionWatchJob?.isActive == true) return
        permissionWatchJob = scope.launch {
            while (isActive && realm?.realmId == realmId &&
                FipsPermissionPolicy.missingPermissions(context).isNotEmpty()) {
                delay(PERMISSION_POLL_MS)
            }
            if (isActive && realm?.realmId == realmId &&
                FipsPermissionPolicy.missingPermissions(context).isEmpty()) {
                permissionWatchJob = null
                restartForGrantedPermissions()
            }
        }
    }

    private fun restartForGrantedPermissions() {
        if (FipsPermissionPolicy.missingPermissions(context).isNotEmpty() ||
            !restartingForPermissions.compareAndSet(false, true)) return
        try {
            FipsDebugLog.event("runtime", "permissions_granted_restart")
            permissionPromptedRealm = null
            permissionWatchJob?.cancel(); permissionWatchJob = null
            restartAfterBluetoothAvailable()
        } finally {
            restartingForPermissions.set(false)
        }
    }

    private fun passiveDiscoveryFailed(failed: FipsNearbyDiscovery, errorCode: Int) {
        scope.launch {
            val cleared = synchronized(this@FipsMeshRuntime) {
                if (passiveDiscovery !== failed) return@synchronized false
                failed.stop()
                passiveDiscovery = null
                discoveryPruneJob?.cancel(); discoveryPruneJob = null
                true
            }
            if (cleared && discoveryRequested && !_running.value) {
                // Android reports transient scanner contention asynchronously.
                // Clear the wedged instance and retry instead of making every
                // later ensureDiscovery call a no-op forever.
                delay(if (errorCode == 6) 30_000L else 5_000L)
                if (discoveryRequested && !_running.value) startNearbyDiscovery()
            }
        }
    }

    private suspend fun receiveLoop() {
        while (scope.isActive && _running.value) {
            // A long bounded wait avoids polling while still letting a cancelled
            // Kotlin job leave JNI even if the native sender has not disconnected.
            val framed = runCatching { NativeFips.receive(RECEIVE_WAIT_MS) }.getOrDefault(ByteArray(0))
            if (framed.size >= 2) {
                val length = ((framed[0].toInt() and 255) shl 8) or (framed[1].toInt() and 255)
                if (length > 0 && framed.size >= 2 + length) {
                    val sender = framed.copyOfRange(2, 2 + length).decodeToString()
                    assembler.accept(sender, framed.copyOfRange(2 + length, framed.size))?.let { payload ->
                        FipsDebugLog.event("runtime", "payload_received", "peer" to FipsDebugLog.id(sender),
                            "bytes" to payload.size)
                        if (!acceptJoinHello(sender, payload)) {
                            _messages.emit(AuthenticatedFipsMessage(sender, payload))
                        }
                    }
                }
            }
        }
    }

    private suspend fun peerLoop() {
        var peerSummary = ""
        while (scope.isActive && _running.value) {
            if (!NativeFips.isAlive()) {
                FipsDebugLog.warning("runtime", "native_node_exited",
                    "action" to "full restart")
                synchronized(this@FipsMeshRuntime) {
                    if (_running.value && !NativeFips.isAlive()) {
                        shutdownNative()
                        ensureStarted()
                    }
                }
                return
            }
            _peers.value = runCatching { NativeFips.peers().lineSequence().filter(String::isNotBlank).mapNotNull { line ->
                val p = line.split('\t'); if (p.size != 4) null else FipsPeer(p[0], p[1].toBoolean(), p[2], p[3].toLongOrNull() ?: 0)
            }.toList() }.getOrDefault(emptyList())
            logNewNativeBleAttempts()
            val connectedBlePeers = _peers.value.asSequence()
                .filter { it.connected && it.transport == "ble" }.map { it.npub }.toSet()
            synchronized(validatedDirectPeers) { validatedDirectPeers.retainAll(connectedBlePeers) }
            synchronized(helloSentAt) { helloSentAt.keys.retainAll(connectedBlePeers) }
            if (_peers.value.any { it.connected && it.transport == "ble" }) {
                recordConnectionStage(FipsConnectionStage.PEER_AUTHENTICATED)
            }
            pruneNearbyMeshes()
            val nextSummary = _peers.value.sortedBy { it.npub }.joinToString { peer ->
                "${FipsDebugLog.id(peer.npub)}:${peer.connected}:${peer.transport}"
            }
            if (nextSummary != peerSummary) {
                peerSummary = nextSummary
                FipsDebugLog.event("runtime", "peer_set_changed", "count" to _peers.value.size,
                    "peers" to nextSummary.ifEmpty { "none" },
                    "validatedDirect" to synchronized(validatedDirectPeers) {
                        validatedDirectPeers.joinToString { FipsDebugLog.id(it) }.ifEmpty { "none" }
                    })
            }
            sendJoinHellosToNewDirectPeers()
            delay(PEER_REFRESH_MS)
        }
    }

    private fun logNewNativeBleAttempts() {
        runCatching { NativeFips.bleAttempts() }.getOrDefault("")
            .lineSequence().filter(String::isNotBlank).forEach { line ->
                val isNew = synchronized(loggedNativeBleAttempts) {
                    if (!loggedNativeBleAttempts.add(line)) false else {
                        while (loggedNativeBleAttempts.size > MAX_LOGGED_NATIVE_ATTEMPTS) {
                            loggedNativeBleAttempts.remove(loggedNativeBleAttempts.first())
                        }
                        true
                    }
                }
                if (!isNew) return@forEach
                val fields = line.split('\t')
                if (fields.size != 7) {
                    FipsDebugLog.warning("native_ble", "attempt_decode_failed", "fields" to fields.size)
                    return@forEach
                }
                FipsDebugLog.event(
                    "native_ble", "attempt_resolved",
                    "atMs" to fields[0],
                    "address" to fields[1].substringAfter('/'),
                    "peer" to FipsDebugLog.id(fields[2]),
                    "role" to fields[3],
                    "discoveryMs" to fields[4],
                    "outcome" to fields[5],
                    "sendFailures" to fields[6],
                )
            }
    }

    private fun sendJoinHellosToNewDirectPeers() {
        val activeRealm = realm ?: return
        val activeRadio = radio ?: return
        val now = System.currentTimeMillis()
        _peers.value.asSequence().filter { it.connected && it.transport == "ble" }.forEach { peer ->
            if (synchronized(validatedDirectPeers) { peer.npub in validatedDirectPeers }) return@forEach
            val shouldSend = synchronized(helloSentAt) {
                val last = helloSentAt[peer.npub] ?: 0L
                if (now - last < JOIN_RETRY_MS) false else {
                    helloSentAt[peer.npub] = now
                    true
                }
            }
            if (!shouldSend) return@forEach
            val hello = DirectJoinHello(activeRealm.realmId, activeRealm.boardCellId,
                activeRadio.localNonceHex(), now)
            val sent = sendFramedNative(peer.npub, JOIN_PREFIX + json.encodeToString(hello).encodeToByteArray())
            FipsDebugLog.event("admission", "direct_join_hello_sent", "peer" to FipsDebugLog.id(peer.npub),
                "sent" to sent, "realm" to FipsDebugLog.id(activeRealm.realmId),
                "cell" to FipsDebugLog.id(activeRealm.boardCellId))
        }
    }

    @Synchronized
    private fun recordNearbyMesh(mesh: FipsNearbyMesh) {
        _nearbyMeshes.value = nearbyMeshTracker.record(mesh)
    }

    @Synchronized
    private fun pruneNearbyMeshes(nowMs: Long = System.currentTimeMillis()) {
        _nearbyMeshes.value = nearbyMeshTracker.prune(nowMs)
    }

    /** Join hello is accepted only from a native direct BLE edge with fresh, exact full realm scope. */
    private fun acceptJoinHello(sender: String, payload: ByteArray): Boolean {
        if (!payload.startsWith(JOIN_PREFIX)) return false
        val direct = _peers.value.any { it.npub == sender && it.connected && it.transport == "ble" }
        val hello = runCatching {
            json.decodeFromString<DirectJoinHello>(payload.copyOfRange(JOIN_PREFIX.size, payload.size).decodeToString())
        }.getOrNull()
        val valid = direct && hello != null && DirectJoinProof.isFresh(hello.issuedAtMs, System.currentTimeMillis()) &&
            radio?.validateDirectJoin(hello) == true
        if (valid) {
            synchronized(validatedDirectPeers) { validatedDirectPeers.add(sender) }
            recordConnectionStage(FipsConnectionStage.DIRECT_AUTHENTICATED)
            FipsDebugLog.event("admission", "direct_join_accepted", "peer" to FipsDebugLog.id(sender),
                "realm" to FipsDebugLog.id(hello.realmId), "cell" to FipsDebugLog.id(hello.boardCellId))
        }
        else if (direct && rejectingRealmLink.compareAndSet(false, true)) {
            FipsDebugLog.warning("admission", "direct_join_rejected", "peer" to FipsDebugLog.id(sender),
                "nativeDirectBle" to direct, "helloDecoded" to (hello != null),
                "fresh" to (hello?.let { DirectJoinProof.isFresh(it.issuedAtMs, System.currentTimeMillis()) } ?: false),
                "realm" to FipsDebugLog.id(hello?.realmId), "cell" to FipsDebugLog.id(hello?.boardCellId),
                "action" to "rebuild realm radio and close foreign edge")
            // FIPS authenticated the node, but the full CruxCoach realm/cell proof did not.
            // Rebuilding the realm radio closes the underlying link; no foreign collision
            // is allowed to remain as a durable transit edge.
            scope.launch {
                try { restartAfterBluetoothAvailable() } finally { rejectingRealmLink.set(false) }
            }
        }
        return true // control frames are never exposed to/relayed by the BoardCell transport
    }

    private fun sendFramedNative(peer: String, payload: ByteArray): Boolean =
        sendBatchNative(peer, FipsFrameCodec.fragment(payload, FipsFrameCodec.messageId(payload)))

    /** One JNI call + one native capacity decision: either every fragment is
     * admitted to the app-owned FIPS queue, or none is. */
    private fun sendBatchNative(peer: String, fragments: List<ByteArray>): Boolean {
        val packed = ByteBuffer.allocate(fragments.sumOf { Int.SIZE_BYTES + it.size })
        fragments.forEach { packed.putInt(it.size).put(it) }
        return NativeFips.sendBatch(peer, packed.array())
    }

    @Synchronized
    private fun recordConnectionStage(stage: FipsConnectionStage) {
        val active = realm ?: return
        val current = _connectionProgress.value
        if (current.realmId == active.realmId && current.cellId == active.boardCellId &&
            current.stage.ordinal >= stage.ordinal) return
        _connectionProgress.value = FipsConnectionProgress(
            realmId = active.realmId,
            cellId = active.boardCellId,
            stage = stage,
            updatedAtMs = System.currentTimeMillis(),
        )
        FipsDebugLog.event("runtime", "connection_progress", "stage" to stage,
            "realm" to FipsDebugLog.id(active.realmId), "cell" to FipsDebugLog.id(active.boardCellId))
    }

    private fun startDiscoveryPruning() {
        if (discoveryPruneJob?.isActive == true) return
        discoveryPruneJob = scope.launch {
            while (isActive && discoveryRequested && !_running.value) {
                delay(1_000)
                pruneNearbyMeshes()
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { this[it] == prefix[it] }

    companion object {
        const val MAX_DIRECT_CONNECTIONS = 7
        const val OWNER_BOARD_CELL = "board-cell"
        const val OWNER_SESSION = "session"
        const val OWNER_NEARBY_BOARD_CELL = "nearby-board-cell"
        const val OWNER_HANDOVER = "board-cell-handover"
        fun competitionOwner(compId: String) = "competition:$compId"
        private const val JOIN_RETRY_MS = 5_000L
        private const val PEER_REFRESH_MS = 2_000L
        private const val MAX_LOGGED_NATIVE_ATTEMPTS = 256
        private const val RECEIVE_WAIT_MS = 5_000
        private const val PERMISSION_POLL_MS = 2_000L
        private val JOIN_PREFIX = byteArrayOf(0x43, 0x43, 0x4a, 0x31) // CCJ1
    }
}
