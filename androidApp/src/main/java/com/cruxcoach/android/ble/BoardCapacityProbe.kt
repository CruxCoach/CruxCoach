package com.cruxcoach.android.ble

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns what happens to the scanners around a board connect.
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
    private val bleConnection: BoardBleConnection,
    private val bleScanner: BoardBleScanner,
    private val nearbyClimbScanner: NearbyClimbScanner,
) {
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
        // Advertising while connected is not evidence that a second central
        // can establish GATT: real exclusive controllers may keep advertising
        // and reject it. Do not run the old capacity probe. Resume nearby
        // session discovery after the board connection has settled.
        nearbyClimbScanner.startScan(clearExisting = false)
    }
}
