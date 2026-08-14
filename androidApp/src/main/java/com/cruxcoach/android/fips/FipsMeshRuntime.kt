package com.cruxcoach.android.fips

import android.content.Context
import android.os.Build
import com.cruxcoach.android.boardcell.AuthenticatedMeshLink
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AuthenticatedFipsMessage(val senderNpub: String, val payload: ByteArray)
data class FipsPeer(val npub: String, val connected: Boolean, val transport: String, val lastSeenMs: Long)

@Singleton
class FipsMeshRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: FipsRealmKeyStore,
) : AuthenticatedMeshLink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assembler = FipsFrameAssembler()
    private val owners = AtomicInteger(0)
    private val rejectingRealmLink = AtomicBoolean(false)
    private var radio: FipsBleRadio? = null
    private var bridge = 0L
    private var receiveJob: Job? = null
    @Volatile private var realm: FipsRealmContext? = null
    private val validatedDirectPeers = mutableSetOf<String>()
    private val helloSentAt = mutableMapOf<String, Long>()
    private val json = Json { ignoreUnknownKeys = false }
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _messages = MutableSharedFlow<AuthenticatedFipsMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()
    private val _peers = MutableStateFlow<List<FipsPeer>>(emptyList())
    val peers = _peers.asStateFlow()
    override val localNpub: String get() = if (_running.value) runCatching { NativeFips.npub() }.getOrDefault("") else ""

    @Synchronized
    fun activateRealm(value: FipsRealmContext): Boolean {
        if (realm == value && _running.value) return true
        if (realm != value && _running.value) {
            receiveJob?.cancel(); receiveJob = null
            shutdownNative()
        }
        realm = value
        return ensureStarted()
    }

    @Synchronized
    fun endRealm(realmId: String) {
        if (realm?.realmId != realmId) return
        receiveJob?.cancel(); receiveJob = null
        shutdownNative()
        realm = null
        keyStore.end(realmId)
    }

    @Synchronized
    fun ensureStarted(): Boolean {
        if (_running.value) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false // GATT session fallback remains active.
        val activeRealm = realm ?: return false
        return runCatching {
            val candidate = FipsBleRadio(context, activeRealm)
            radio = candidate
            bridge = NativeFips.bleBridgeNew(candidate)
            check(bridge != 0L)
            candidate.bindBridge(bridge)
            check(NativeFips.start(keyStore.activate(activeRealm.realmId), MAX_DIRECT_CONNECTIONS))
            _running.value = true
            receiveJob = scope.launch { receiveLoop() }
            true
        }.getOrElse {
            shutdownNative()
            false
        }
    }

    /** Hold background-capable mesh operation for a session/cell/competition. */
    fun acquire() {
        owners.incrementAndGet()
        // Retry transient native/Bluetooth startup failures even when another
        // logical owner already holds the runtime.
        if (ensureStarted()) FipsMeshService.start(context)
    }

    fun release() {
        if (owners.updateAndGet { maxOf(0, it - 1) } == 0) FipsMeshService.stop(context)
    }

    override fun send(authenticatedPeerNpub: String, payload: ByteArray): Boolean {
        if (!ensureStarted()) return false
        return FipsFrameCodec.fragment(payload).all { NativeFips.send(authenticatedPeerNpub, it) }
    }

    override fun directAuthenticatedPeers(): Set<String> = _peers.value.asSequence()
        .filter { it.connected && it.transport == "ble" && it.npub in synchronized(validatedDirectPeers) {
            validatedDirectPeers.toSet()
        } }.map { it.npub }.toSet()

    override fun activeRealmId(): String? = realm?.realmId

    @Synchronized
    fun shutdown() {
        receiveJob?.cancel(); receiveJob = null
        shutdownNative()
    }

    /** Recreate L2CAP listeners/scans after Android rebuilt the Bluetooth stack. */
    @Synchronized
    fun restartAfterBluetoothAvailable(): Boolean {
        val shouldRestart = _running.value || owners.get() > 0
        if (!shouldRestart) return false
        receiveJob?.cancel(); receiveJob = null
        shutdownNative()
        return ensureStarted()
    }

    private fun shutdownNative() {
        runCatching { NativeFips.stop() }
        radio?.shutdown(); radio = null
        if (bridge != 0L) runCatching { NativeFips.bleBridgeFree(bridge) }
        bridge = 0
        _running.value = false
        _peers.value = emptyList()
        synchronized(validatedDirectPeers) { validatedDirectPeers.clear() }
        synchronized(helloSentAt) { helloSentAt.clear() }
    }

    private suspend fun receiveLoop() {
        while (scope.isActive && _running.value) {
            val framed = runCatching { NativeFips.receive(500) }.getOrDefault(ByteArray(0))
            if (framed.size >= 2) {
                val length = ((framed[0].toInt() and 255) shl 8) or (framed[1].toInt() and 255)
                if (length > 0 && framed.size >= 2 + length) {
                    val sender = framed.copyOfRange(2, 2 + length).decodeToString()
                    assembler.accept(sender, framed.copyOfRange(2 + length, framed.size))?.let { payload ->
                        if (!acceptJoinHello(sender, payload)) {
                            _messages.emit(AuthenticatedFipsMessage(sender, payload))
                        }
                    }
                }
            }
            _peers.value = runCatching { NativeFips.peers().lineSequence().filter(String::isNotBlank).mapNotNull { line ->
                val p = line.split('\t'); if (p.size != 4) null else FipsPeer(p[0], p[1].toBoolean(), p[2], p[3].toLongOrNull() ?: 0)
            }.toList() }.getOrDefault(emptyList())
            sendJoinHellosToNewDirectPeers()
            delay(10)
        }
    }

    private fun sendJoinHellosToNewDirectPeers() {
        val activeRealm = realm ?: return
        val activeRadio = radio ?: return
        val now = System.currentTimeMillis()
        _peers.value.asSequence().filter { it.connected && it.transport == "ble" }.forEach { peer ->
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
            sendFramedNative(peer.npub, JOIN_PREFIX + json.encodeToString(hello).encodeToByteArray())
        }
    }

    /** Join hello is accepted only from a native direct BLE edge and a fresh, locally scanned nonce. */
    private fun acceptJoinHello(sender: String, payload: ByteArray): Boolean {
        if (!payload.startsWith(JOIN_PREFIX)) return false
        val direct = _peers.value.any { it.npub == sender && it.connected && it.transport == "ble" }
        val hello = runCatching {
            json.decodeFromString<DirectJoinHello>(payload.copyOfRange(JOIN_PREFIX.size, payload.size).decodeToString())
        }.getOrNull()
        val valid = direct && hello != null && DirectJoinProof.isFresh(hello.issuedAtMs, System.currentTimeMillis()) &&
            radio?.validateDirectJoin(hello) == true
        if (valid) synchronized(validatedDirectPeers) { validatedDirectPeers.add(sender) }
        else if (direct && rejectingRealmLink.compareAndSet(false, true)) {
            // FIPS authenticated the node, but the full CruxCoach realm/cell/nonce did not.
            // Rebuilding the realm radio closes the underlying link; no foreign collision
            // is allowed to remain as a durable transit edge.
            scope.launch {
                try { restartAfterBluetoothAvailable() } finally { rejectingRealmLink.set(false) }
            }
        }
        return true // control frames are never exposed to/relayed by the BoardCell transport
    }

    private fun sendFramedNative(peer: String, payload: ByteArray): Boolean =
        FipsFrameCodec.fragment(payload).all { NativeFips.send(peer, it) }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { this[it] == prefix[it] }

    companion object {
        const val MAX_DIRECT_CONNECTIONS = 7
        private const val JOIN_RETRY_MS = 5_000L
        private val JOIN_PREFIX = byteArrayOf(0x43, 0x43, 0x4a, 0x31) // CCJ1
    }
}
