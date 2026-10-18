// Port of the bookkeeping in androidApp/.../ble/NearbyClimbScanner.kt (0e1bad0c6): the same
// RSSI floor, stale timeouts, ClimbData → LastClimb conversion, GONE de-duplication and
// per-UUID / per-session de-duplication. The radio part lives in the platform central; this
// class only consumes "CRUX" manufacturer data, so it is fully testable on the JVM.
//
// One iOS difference: CoreBluetooth reports one manufacturer-data field per callback, so the
// session's scan response (company 0xFFFE, the climb on the host's wall) can arrive in its own
// callback instead of beside the session advertisement. It is attached to the session of the
// same peripheral when that arrives, exactly as Android retains the last seen scan response.
package com.cruxcoach.app.nearby

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NearbySession(
    val sessionId: Int,
    val participantCount: Int,
    val hostName: String,
    val rssi: Int,
    val lastSeenMs: Long,
    /** Platform peripheral identifier (iOS has no MAC address). */
    val identifier: String,
    /** Climb the host currently projects (from the session scan response). */
    val currentClimbUuid: String? = null,
    val currentClimbAngle: Int = 0,
)

internal data class NearbyClimb(
    val climbUuid: String,
    val angle: Int,
    val rssi: Int,
    val lastSeenMs: Long,
    val identifier: String,
    val connectedOnly: Boolean = false,
    /** The sender has disconnected and this is its last projection. */
    val isLastClimb: Boolean = false,
    val acceptsDisconnectRequests: Boolean = true,
    val supportsConcurrentConnections: Boolean = false,
    val projectionSurvivesDisconnect: Boolean = true,
    val senderToken: Int? = null,
)

/** Not thread-safe: the owner calls everything from one thread (its single-threaded scope). */
internal class NearbyScanner(private val now: () -> Long) {

    companion object {
        const val STALE_TIMEOUT_MS = 3_000L
        const val NON_RETAINED_LAST_TIMEOUT_MS = 30_000L
        const val SESSION_STALE_TIMEOUT_MS = 5_000L
        const val CLEANUP_INTERVAL_MS = 1_000L
        const val DEFAULT_RSSI_THRESHOLD = -82
        const val GONE_DEDUP_MS = 10_000L
    }

    private val _nearbyClimbs = MutableStateFlow<List<NearbyClimb>>(emptyList())
    val nearbyClimbs: StateFlow<List<NearbyClimb>> = _nearbyClimbs.asStateFlow()

    private val _nearbySessions = MutableStateFlow<List<NearbySession>>(emptyList())
    val nearbySessions: StateFlow<List<NearbySession>> = _nearbySessions.asStateFlow()

    var rssiThreshold: Int = DEFAULT_RSSI_THRESHOLD

    private val rawEntries = LinkedHashMap<String, NearbyClimb>()
    private val rawSessionEntries = LinkedHashMap<String, NearbySession>()
    private val goneProcessedAt = HashMap<String, Long>()

    /** Last session scan response per peripheral (uuid, angle), for the iOS split delivery. */
    private val sessionClimbs = HashMap<String, Pair<String, Int>>()

    fun onManufacturerData(identifier: String, companyId: Int, payload: ByteArray, rssi: Int) {
        if (rssi < rssiThreshold) return
        when (companyId) {
            NearbyClimbProtocol.COMPANY_ID -> onPayload(identifier, payload, rssi)
            NearbyClimbProtocol.SESSION_CLIMB_COMPANY_ID -> onSessionClimb(identifier, payload)
        }
    }

    private fun onSessionClimb(identifier: String, payload: ByteArray) {
        val climb = NearbyClimbProtocol.decode(payload) as? NearbyPayload.ClimbData ?: return
        sessionClimbs[identifier] = climb.climbUuid to climb.angle
        val existing = rawSessionEntries[identifier] ?: return
        rawSessionEntries[identifier] = existing.copy(currentClimbUuid = climb.climbUuid, currentClimbAngle = climb.angle)
        publishDedupedSessions()
    }

    private fun onPayload(identifier: String, data: ByteArray, rssi: Int) {
        when (val payload = NearbyClimbProtocol.decode(data) ?: return) {
            is NearbyPayload.ClimbData -> onClimb(
                identifier, rssi, payload.climbUuid, payload.angle, last = false,
                projectionSurvivesDisconnect = payload.projectionSurvivesDisconnect,
                acceptsDisconnect = payload.acceptsDisconnect,
                supportsConcurrentConnections = payload.supportsConcurrentConnections,
            )
            is NearbyPayload.LastClimb -> onClimb(
                identifier, rssi, payload.climbUuid, payload.angle, last = true,
                projectionSurvivesDisconnect = payload.projectionSurvivesDisconnect,
                acceptsDisconnect = false,
                supportsConcurrentConnections = false,
            )
            is NearbyPayload.BoardConnected -> {
                val time = now()
                convertOrRemoveStale(time, identifier)
                rawEntries[identifier] = NearbyClimb(
                    climbUuid = "",
                    angle = 0,
                    rssi = rssi,
                    lastSeenMs = time,
                    identifier = identifier,
                    connectedOnly = true,
                    acceptsDisconnectRequests = payload.acceptsDisconnect,
                    supportsConcurrentConnections = payload.supportsConcurrentConnections,
                    senderToken = payload.senderToken,
                )
                publishDeduped()
            }
            // The iPhone cannot answer disconnect requests (it does not advertise); ignored.
            is NearbyPayload.DisconnectRequest, is NearbyPayload.DisconnectResponse -> Unit
            is NearbyPayload.Gone -> {
                val time = now()
                val previous = goneProcessedAt[identifier]
                if (previous != null && time - previous < GONE_DEDUP_MS) return
                goneProcessedAt[identifier] = time
                rawEntries.remove(identifier)
                publishDeduped()
                if (rawSessionEntries.remove(identifier) != null) publishDedupedSessions()
            }
            is NearbyPayload.SessionAdvertisement -> {
                val existing = rawSessionEntries[identifier]
                // A scan response may be missed; keep the previous climb instead of clearing it.
                val split = sessionClimbs[identifier]
                rawSessionEntries[identifier] = NearbySession(
                    sessionId = payload.sessionId,
                    participantCount = payload.participantCount,
                    hostName = payload.hostName,
                    rssi = rssi,
                    lastSeenMs = now(),
                    identifier = identifier,
                    currentClimbUuid = split?.first ?: existing?.currentClimbUuid,
                    currentClimbAngle = split?.second ?: existing?.currentClimbAngle ?: 0,
                )
                publishDedupedSessions()
            }
        }
    }

    private fun onClimb(
        identifier: String,
        rssi: Int,
        uuid: String,
        angle: Int,
        last: Boolean,
        projectionSurvivesDisconnect: Boolean,
        acceptsDisconnect: Boolean,
        supportsConcurrentConnections: Boolean,
    ) {
        val time = now()
        val entry = NearbyClimb(
            climbUuid = uuid,
            angle = angle,
            rssi = rssi,
            lastSeenMs = time,
            identifier = identifier,
            isLastClimb = last,
            acceptsDisconnectRequests = acceptsDisconnect,
            supportsConcurrentConnections = supportsConcurrentConnections,
            projectionSurvivesDisconnect = projectionSurvivesDisconnect,
        )
        convertOrRemoveStale(time, identifier)
        if (!last) {
            // ClimbData: the LEDs were replaced — every LastClimb is obsolete.
            rawEntries.keys.filter { rawEntries[it]!!.isLastClimb }.forEach { rawEntries.remove(it) }
        } else {
            // A new LastClimb supersedes older board-state metadata.
            rawEntries.keys.filter { rawEntries[it]!!.isLastClimb && it != identifier }.forEach { rawEntries.remove(it) }
        }
        rawEntries[identifier] = entry
        publishDeduped()
    }

    /** Android's 1 s cleanup tick; the owner calls it every [CLEANUP_INTERVAL_MS] while scanning. */
    fun cleanup() {
        val time = now()
        val staleCount = rawEntries.count { (_, e) -> !e.isLastClimb && time - e.lastSeenMs > STALE_TIMEOUT_MS }
        if (staleCount > 0) {
            convertOrRemoveStale(time)
            publishDeduped()
        }
        val stale = rawSessionEntries.entries.filter { time - it.value.lastSeenMs > SESSION_STALE_TIMEOUT_MS }.map { it.key }
        if (stale.isNotEmpty()) {
            stale.forEach { rawSessionEntries.remove(it) }
            publishDedupedSessions()
        }
        goneProcessedAt.keys.filter { time - goneProcessedAt[it]!! > GONE_DEDUP_MS }.forEach { goneProcessedAt.remove(it) }
    }

    /** Android `stopScan()` without preserveEntries, and Bluetooth turning off. */
    fun clear() {
        rawEntries.clear()
        rawSessionEntries.clear()
        goneProcessedAt.clear()
        sessionClimbs.clear()
        _nearbyClimbs.value = emptyList()
        _nearbySessions.value = emptyList()
    }

    private fun convertOrRemoveStale(time: Long, excludeIdentifier: String? = null) {
        rawEntries.entries
            .filter { (_, e) -> e.isLastClimb && !e.projectionSurvivesDisconnect && time - e.lastSeenMs > NON_RETAINED_LAST_TIMEOUT_MS }
            .map { it.key }
            .forEach { rawEntries.remove(it) }

        val stale = rawEntries.entries
            .filter { (id, e) -> id != excludeIdentifier && !e.isLastClimb && time - e.lastSeenMs > STALE_TIMEOUT_MS }
            .map { it.key to it.value }
        val toConvert = stale.filter { (_, e) -> e.climbUuid.isNotEmpty() && !e.connectedOnly }
        val toRemove = stale.filter { (_, e) -> e.climbUuid.isEmpty() || e.connectedOnly }
        toRemove.forEach { (id, _) -> rawEntries.remove(id) }
        if (toConvert.isNotEmpty()) {
            // The board shows one climb at a time — keep only the most recently seen one.
            rawEntries.keys.filter { rawEntries[it]!!.isLastClimb }.forEach { rawEntries.remove(it) }
            val newest = toConvert.maxBy { (_, e) -> e.lastSeenMs }
            toConvert.forEach { (id, _) ->
                if (id == newest.first) rawEntries[id] = newest.second.copy(isLastClimb = true) else rawEntries.remove(id)
            }
        }
    }

    /** One entry per climb UUID (strongest RSSI); every BoardConnected sender stays distinct. */
    private fun publishDeduped() {
        val deduped = rawEntries.values
            .groupBy { if (it.connectedOnly) "connected:${it.senderToken ?: it.identifier}" else "climb:${it.climbUuid}" }
            .map { (_, entries) -> entries.maxBy { it.rssi } }
        val current = _nearbyClimbs.value
        if (deduped.size == current.size && deduped.zip(current).all { (a, b) ->
                a.climbUuid == b.climbUuid && a.angle == b.angle &&
                    a.isLastClimb == b.isLastClimb && a.connectedOnly == b.connectedOnly &&
                    a.projectionSurvivesDisconnect == b.projectionSurvivesDisconnect &&
                    a.senderToken == b.senderToken && a.identifier == b.identifier
            }
        ) return
        _nearbyClimbs.value = deduped
    }

    /** One entry per session id (strongest RSSI); emits only on a visible change. */
    private fun publishDedupedSessions() {
        val deduped = rawSessionEntries.values.groupBy { it.sessionId }.map { (_, entries) -> entries.maxBy { it.rssi } }
        val current = _nearbySessions.value
        if (deduped.size == current.size && deduped.zip(current).all { (a, b) ->
                a.sessionId == b.sessionId && a.participantCount == b.participantCount &&
                    a.currentClimbUuid == b.currentClimbUuid && a.currentClimbAngle == b.currentClimbAngle &&
                    a.hostName == b.hostName
            }
        ) return
        _nearbySessions.value = deduped
    }
}
