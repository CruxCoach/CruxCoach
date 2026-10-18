// The iPhone's side of Android's shared sessions: one CoreBluetooth central (its own manager,
// never the board connection's) that scans for "CRUX" advertisements and connects to a host's
// GATT server as a participant. Owns the nearby scanner, the GATT client and the participant,
// resolves climb names from the local catalogue, and publishes one UI state.
package com.cruxcoach.app.sharedsession

import com.cruxcoach.app.ble.BleAdapterState
import com.cruxcoach.app.ble.BleCentral
import com.cruxcoach.app.ble.BleCentralListener
import com.cruxcoach.app.ble.BleServiceInfo
import com.cruxcoach.app.nearby.NearbyClimb
import com.cruxcoach.app.nearby.NearbyScanner
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.getClimbByUuidAnySpelling
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [uuid] is the catalogue's own spelling of the id (for opening the climb); empty when not known. */
internal class ClimbLabel(val name: String, val grade: String, val uuid: String = "")

/** Resolves a climb against the local catalogue; null when this phone does not have it. */
internal fun interface ClimbLookup {
    fun label(uuid: String, angle: Int): ClimbLabel?
}

internal class CatalogueClimbLookup(
    private val repository: BoardRepository,
    private val gradeLabel: (Double?) -> String,
) : ClimbLookup {
    override fun label(uuid: String, angle: Int): ClimbLabel? = try {
        repository.getClimbByUuidAnySpelling(uuid, angle)?.let { ClimbLabel(it.name, gradeLabel(it.difficultyAverage), it.uuid) }
    } catch (e: Exception) {
        null
    }
}

internal data class SharedSessionsSnapshot(
    val adapter: BleAdapterState,
    val scanning: Boolean,
    val participant: ParticipantState,
    val sessions: List<com.cruxcoach.app.nearby.NearbySession>,
    val climbs: List<NearbyClimb>,
    /** "uuid@angle" → label; a key with a null value was looked up and is not in the catalogue. */
    val labels: Map<String, ClimbLabel?>,
)

/** [scope] must be single-threaded (main on iOS); [io] runs the catalogue lookups. */
internal class SharedSessionsPresenter(
    private val central: BleCentral,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val millis: () -> Long,
    private val lookup: ClimbLookup,
) : BleCentralListener {

    val scanner = NearbyScanner(millis)
    val client = SessionGattClient(central, scope)
    val participant = SharedSessionParticipant(client, { scanner.nearbySessions.value }, scope, millis)

    private val adapter = MutableStateFlow(BleAdapterState.UNKNOWN)
    private val scanning = MutableStateFlow(false)
    private val labels = MutableStateFlow<Map<String, ClimbLabel?>>(emptyMap())
    private val inFlight = mutableSetOf<String>()
    /** A climb outside the scan whose label the nearby area shows: this phone's own last send. */
    private var ownLabel: Pair<String, Int>? = null
    private var wantScan = false
    /** A board screen's nearby area is visible (it starts only once Bluetooth is allowed). */
    private var wantPassiveScan = false
    private var cleanupJob: Job? = null

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<SharedSessionsSnapshot> = _state.asStateFlow()

    init {
        central.setListener(Fanout(this, client))
        scope.launch { adapter.collect { publish() } }
        scope.launch { scanning.collect { publish() } }
        scope.launch { labels.collect { publish() } }
        scope.launch { scanner.nearbySessions.collect { publish() } }
        scope.launch { scanner.nearbyClimbs.collect { publish() } }
        scope.launch {
            participant.state.collect {
                applyScan()
                publish()
            }
        }
    }

    /** The screen is visible: scan (after the Bluetooth prompt on first use). */
    fun startScan() {
        wantScan = true
        central.activate()
        adapter.value = central.adapterState()
        applyScan()
    }

    /** The screen went away. A joined session keeps scanning: a successor host must be found. */
    fun stopScan() {
        wantScan = false
        applyScan()
    }

    /**
     * A board screen shows the nearby area: scan like Android's app-wide nearby scanner. The
     * caller asks only once Bluetooth is allowed, so this never brings up the permission prompt.
     */
    fun startPassiveScan() {
        wantPassiveScan = true
        central.activate()
        adapter.value = central.adapterState()
        applyScan()
    }

    fun stopPassiveScan() {
        wantPassiveScan = false
        applyScan()
    }

    private fun participantActive(): Boolean = when (participant.state.value.phase) {
        ParticipantPhase.CONNECTING, ParticipantPhase.JOINED, ParticipantPhase.HOST_LOST -> true
        else -> false
    }

    private fun applyScan() {
        val desired = wantScan || wantPassiveScan || participantActive()
        if (desired && adapter.value == BleAdapterState.POWERED_ON && !scanning.value) {
            central.startScan()
            scanning.value = true
            cleanupJob?.cancel()
            cleanupJob = scope.launch {
                while (isActive) {
                    delay(NearbyScanner.CLEANUP_INTERVAL_MS)
                    scanner.cleanup()
                }
            }
        } else if (!desired && scanning.value) {
            central.stopScan()
            stopped()
        }
    }

    private fun stopped() {
        scanning.value = false
        cleanupJob?.cancel()
        cleanupJob = null
        scanner.clear()
    }

    /** Looks up [uuid] at [angle] like a scanned climb (this phone's own last send). */
    fun wantLabel(uuid: String, angle: Int) {
        if (ownLabel == (uuid to angle)) return
        ownLabel = uuid to angle
        publish()
    }

    /** The live values; [state] publishes the same one dispatch later. */
    fun current(): SharedSessionsSnapshot = snapshot()

    private fun snapshot() = SharedSessionsSnapshot(
        adapter = adapter.value,
        scanning = scanning.value,
        participant = participant.state.value,
        sessions = scanner.nearbySessions.value,
        climbs = scanner.nearbyClimbs.value,
        labels = labels.value,
    )

    private fun publish() {
        val next = snapshot()
        requestLabels(next)
        _state.value = next
    }

    private fun requestLabels(s: SharedSessionsSnapshot) {
        val wanted = buildList {
            s.participant.queue.forEach { add(it.climbUuid to it.angle) }
            s.climbs.filter { it.climbUuid.isNotEmpty() }.forEach { add(it.climbUuid to it.angle) }
            s.sessions.forEach { session -> session.currentClimbUuid?.let { add(it to session.currentClimbAngle) } }
            ownLabel?.let { add(it) }
        }
        val missing = wanted.distinct().filter { (uuid, angle) ->
            val key = labelKey(uuid, angle)
            key !in s.labels && key !in inFlight
        }
        if (missing.isEmpty()) return
        missing.forEach { (uuid, angle) -> inFlight += labelKey(uuid, angle) }
        scope.launch {
            val resolved = withContext(io) {
                missing.associate { (uuid, angle) -> labelKey(uuid, angle) to lookup.label(uuid, angle) }
            }
            resolved.keys.forEach { inFlight -= it }
            labels.value = labels.value + resolved
        }
    }

    // ── Scanner half of the listener (the GATT half goes to [client]) ──

    override fun onAdapterState(state: BleAdapterState) {
        scope.launch {
            adapter.value = state
            if (state == BleAdapterState.POWERED_ON) {
                applyScan()
            } else if (scanning.value) {
                // CoreBluetooth stops scanning by itself when the adapter leaves poweredOn.
                stopped()
            }
        }
    }

    override fun onManufacturerData(identifier: String, companyId: Int, payload: ByteArray, rssi: Int) {
        val copy = payload.copyOf()
        scope.launch { if (scanning.value) scanner.onManufacturerData(identifier, companyId, copy, rssi) }
    }

    override fun onAdvertisement(identifier: String, advertisedName: String?, peripheralName: String?, rssi: Int) = Unit
    override fun onConnected(identifier: String) = Unit
    override fun onDisconnected(identifier: String) = Unit
    override fun onServicesDiscovered(identifier: String, services: List<BleServiceInfo>?) = Unit
    override fun onWriteCompleted(identifier: String, characteristicUuid: String, success: Boolean) = Unit
    override fun onCharacteristicRead(identifier: String, characteristicUuid: String, value: ByteArray?) = Unit
    override fun onNotifyStateChanged(identifier: String, characteristicUuid: String, enabled: Boolean, success: Boolean) = Unit
    override fun onNotification(identifier: String, characteristicUuid: String, value: ByteArray) = Unit

    companion object {
        fun labelKey(uuid: String, angle: Int): String = "${uuid.replace("-", "").uppercase()}@$angle"
    }
}

/** One central, two consumers: the scanner (advertisements) and the GATT client (the link). */
internal class Fanout(private vararg val listeners: BleCentralListener) : BleCentralListener {
    override fun onAdapterState(state: BleAdapterState) = listeners.forEach { it.onAdapterState(state) }
    override fun onAdvertisement(identifier: String, advertisedName: String?, peripheralName: String?, rssi: Int) =
        listeners.forEach { it.onAdvertisement(identifier, advertisedName, peripheralName, rssi) }
    override fun onConnected(identifier: String) = listeners.forEach { it.onConnected(identifier) }
    override fun onDisconnected(identifier: String) = listeners.forEach { it.onDisconnected(identifier) }
    override fun onServicesDiscovered(identifier: String, services: List<BleServiceInfo>?) =
        listeners.forEach { it.onServicesDiscovered(identifier, services) }
    override fun onWriteCompleted(identifier: String, characteristicUuid: String, success: Boolean) =
        listeners.forEach { it.onWriteCompleted(identifier, characteristicUuid, success) }
    override fun onCharacteristicRead(identifier: String, characteristicUuid: String, value: ByteArray?) =
        listeners.forEach { it.onCharacteristicRead(identifier, characteristicUuid, value) }
    override fun onNotifyStateChanged(identifier: String, characteristicUuid: String, enabled: Boolean, success: Boolean) =
        listeners.forEach { it.onNotifyStateChanged(identifier, characteristicUuid, enabled, success) }
    override fun onNotification(identifier: String, characteristicUuid: String, value: ByteArray) =
        listeners.forEach { it.onNotification(identifier, characteristicUuid, value) }
    override fun onManufacturerData(identifier: String, companyId: Int, payload: ByteArray, rssi: Int) =
        listeners.forEach { it.onManufacturerData(identifier, companyId, payload, rssi) }
}
