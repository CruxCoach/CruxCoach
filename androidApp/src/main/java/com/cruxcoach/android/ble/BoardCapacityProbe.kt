package com.cruxcoach.android.ble

import android.content.Context
import android.util.Log
import com.cruxcoach.android.data.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns what happens to the scanners around a board connect, and the
 * controller-capacity observation that follows it.
 *
 * This used to live in [com.cruxcoach.android.ui.board.BleConnectionViewModel],
 * which is scoped per nav-backstack entry: the browser and the detail screen
 * hold separate instances, each overwriting the connection's single callback
 * slot in its `init`. Navigate away from the screen that installed it last and
 * its `viewModelScope` is cancelled — the callback still fires, but every
 * coroutine it starts dies on arrival. Silently: no probe, and no restart of
 * the nearby scanner either, for the rest of the process.
 *
 * A connection is not a screen, so neither is this. One instance, one scope,
 * alive as long as the app is.
 */
@Singleton
class BoardCapacityProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val bleConnection: BoardBleConnection,
    private val bleScanner: BoardBleScanner,
    private val nearbyClimbScanner: NearbyClimbScanner,
    private val userPreferences: UserPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var probeJob: Job? = null

    /** Installs the callbacks on the connection. Idempotent. */
    fun install() {
        // Radio contention on Android <12: a scan running into connectGatt
        // makes the connect fail. preserveEntries/clearExisting keep the
        // nearby banner from flashing empty across the connect.
        bleConnection.onStopScannersForConnect = {
            bleScanner.stopScan()
            nearbyClimbScanner.stopScan(preserveEntries = true)
        }
        bleConnection.onRestartScannersAfterConnect = { onConnected() }
    }

    private fun onConnected() {
        probeJob?.cancel()
        val board = bleConnection.connectedBoard
        val wanted = bleConnection.connectionState.value == ConnectionState.CONNECTED &&
            board != null && !board.isCruxRelay &&
            BlePermissionHelper.wantsCapacityProbe(
                // Recheck on every connection so replacing a controller, or a
                // controller changing mode, corrects a remembered result.
                capacityKnown = false,
                hasScanPermission = BlePermissionHelper.hasScanPermission(context),
                locationEnabled = BlePermissionHelper.isLocationServicesEnabled(context),
            )
        if (!wanted) {
            nearbyClimbScanner.startScan(clearExisting = false)
            return
        }

        probeJob = scope.launch {
            // Avoid competing registrations on older Android BLE stacks. Board
            // writes remain available; this scan only observes the controller.
            nearbyClimbScanner.stopScan(preserveEntries = true)
            try {
                when (bleScanner.probeAdvertisingWhileConnected(board.address)) {
                    ConnectedAdvertisingProbeResult.CONNECTABLE_ADVERTISEMENT_OBSERVED -> {
                        bleConnection.recordAdvertisingWhileConnected(board.address)
                        userPreferences.setRememberedBoardAdvertisesWhileConnected(
                            brand = board.boardBrand,
                            address = board.address,
                        )
                    }
                    ConnectedAdvertisingProbeResult.NOT_OBSERVED -> {
                        bleConnection.recordAdvertisingWhileConnected(
                            board.address,
                            advertises = false,
                        )
                        userPreferences.setRememberedBoardAdvertisesWhileConnected(
                            board.boardBrand,
                            address = board.address,
                            observed = false,
                        )
                        Log.d(TAG, "Scanned, no advertisement — controller is exclusive")
                    }
                    ConnectedAdvertisingProbeResult.INCONCLUSIVE ->
                        Log.d(TAG, "Probe inconclusive — stored capacity untouched")
                }
            } finally {
                nearbyClimbScanner.startScan(clearExisting = false)
            }
        }
    }

    private companion object {
        const val TAG = "CruxBLE/Capacity"
    }
}
