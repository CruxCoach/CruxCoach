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
 * capacity observation that follows it.
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
    @ApplicationContext private val context: Context,
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
        // Runs after every connect that has not established the capacity yet —
        // this is the only moment the evidence exists. It can only raise a
        // controller to "accepts several clients", never lower it.
        val wanted = bleConnection.connectionState.value == ConnectionState.CONNECTED &&
            board != null && !board.isCruxRelay &&
            BlePermissionHelper.wantsCapacityProbe(
                // Was `advertisesWhileConnected == true`, which stopped the
                // probe running as soon as a positive existed — so a positive
                // could never be revisited.
                capacityKnown = false,
                hasScanPermission = BlePermissionHelper.hasScanPermission(context),
                locationEnabled = BlePermissionHelper.isLocationServicesEnabled(context),
            )
        if (!wanted || board == null) {
            nearbyClimbScanner.startScan(clearExisting = false)
            return
        }
        probeJob = scope.launch {
            // Avoid competing scan registrations on legacy Android stacks.
            // Board writes are available throughout — this only observes.
            nearbyClimbScanner.stopScan(preserveEntries = true)
            try {
                when (bleScanner.probeAdvertisingWhileConnected(board.address)) {
                    ConnectedAdvertisingProbeResult.CONNECTABLE_ADVERTISEMENT_OBSERVED -> {
                        bleConnection.recordAdvertisingWhileConnected(board.address)
                        // Persist the positive so later connections know the
                        // capacity without observing again. Only positives are
                        // stored — see
                        // PreferenceKeys.lastUsedBoardAdvertisesWhileConnected.
                        userPreferences.setRememberedBoardAdvertisesWhileConnected(board.boardBrand)
                    }
                    // A completed scan that saw nothing is evidence, and the
                    // only thing that can correct a stale "accepts several".
                    ConnectedAdvertisingProbeResult.NOT_OBSERVED -> {
                        userPreferences.setRememberedBoardAdvertisesWhileConnected(
                            board.boardBrand,
                            observed = false,
                        )
                        Log.d(TAG, "Scanned, no advertisement — controller is exclusive")
                    }
                    // The scan could not settle it. Silence, not a negative:
                    // overwriting a verified capacity on this would be the old
                    // bug with the sign flipped.
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
