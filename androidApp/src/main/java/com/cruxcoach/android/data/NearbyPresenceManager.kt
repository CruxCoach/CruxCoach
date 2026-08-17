package com.cruxcoach.android.data

import android.util.Log
import com.cruxcoach.android.ble.NearbyClimb
import com.cruxcoach.android.ble.NearbyClimbScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.cruxcoach.android.util.PerfLogger
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized nearby-climb presence tracking with name resolution.
 * Eliminates the duplicate resolveNearbyClimbNames() from 3 ViewModels.
 */
@Singleton
class NearbyPresenceManager @Inject constructor(
    private val nearbyClimbScanner: NearbyClimbScanner,
    private val climbNameResolver: ClimbNameResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _climbs = MutableStateFlow<List<NearbyClimb>>(emptyList())
    val climbs: StateFlow<List<NearbyClimb>> = _climbs.asStateFlow()

    private val _climbInfos = MutableStateFlow<Map<String, ClimbDisplayInfo>>(emptyMap())
    val climbInfos: StateFlow<Map<String, ClimbDisplayInfo>> = _climbInfos.asStateFlow()

    /** Convenience: uuid → name only (backward compat for callers that don't need grade). */
    val climbNames: StateFlow<Map<String, String>> get() = _climbNamesDerived
    private val _climbNamesDerived = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        PerfLogger.log("📡 NearbyPresenceManager.init — starting BLE scan")
        nearbyClimbScanner.startScan()
        Log.d(TAG, "Scanner autostart requested")

        scope.launch {
            nearbyClimbScanner.nearbyClimbs.collect { newClimbs ->
                _climbs.update { newClimbs }
                resolveNames(newClimbs)
            }
        }
        // Also resolve climb names embedded in session advertisements (scan response).
        // Without this, non-participants see "Unbekannter Climb" for session climbs.
        scope.launch {
            nearbyClimbScanner.nearbySessions.collect { sessions ->
                resolveSessionClimbNames(sessions)
            }
        }
    }

    private suspend fun resolveNames(climbs: List<NearbyClimb>) {
        val uuids = climbs.filter { !it.connectedOnly && it.climbUuid.isNotEmpty() }
            .map { it.climbUuid to it.angle }
        resolveUuids(uuids)
    }

    /** Resolve a canonical BoardCell projection just like a BLE observation. */
    fun resolveMeshProjection(uuid: String, angle: Int) {
        scope.launch { resolveUuids(listOf(uuid to angle)) }
    }

    private suspend fun resolveSessionClimbNames(sessions: List<com.cruxcoach.android.ble.NearbySession>) {
        val uuids = sessions.mapNotNull { s ->
            s.currentClimbUuid?.let { it to s.currentClimbAngle }
        }
        resolveUuids(uuids)
    }

    private suspend fun resolveUuids(entries: List<Pair<String, Int>>) {
        val known = _climbInfos.value
        val unknown = entries.filter { it.first !in known }
        if (unknown.isEmpty()) return

        val resolved = withContext(Dispatchers.IO) {
            unknown.mapNotNull { (uuid, angle) ->
                val info = climbNameResolver.resolveInfo(uuid, angle)
                if (info != null) uuid to info else null
            }.toMap()
        }
        if (resolved.isNotEmpty()) {
            _climbInfos.update { it + resolved }
            _climbNamesDerived.update { current ->
                current + resolved.mapValues { it.value.name }
            }
            Log.d(TAG, "Resolved ${resolved.size} climb infos")
        }
    }

    /** Retry scanning if it failed due to missing permissions at init time. */
    fun retryScan() {
        if (!nearbyClimbScanner.isScanning.value) {
            nearbyClimbScanner.startScan()
            Log.d(TAG, "retryScan: scanner restarted")
        }
    }

    companion object {
        private const val TAG = "CruxBLE/Nearby"
    }
}
