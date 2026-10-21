package com.cruxcoach.android.ble

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Bluetooth going off ends a scan without onScanFailed. A stale isScanning
 * made every later startScan() return early, and the sheet spun forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [31])
class BoardBleScannerBluetoothToggleTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun bluetoothOn() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        shadowOf(adapter).setEnabled(true)
    }

    @Test
    fun `a scan interrupted by bluetooth going off resumes when it is back`() {
        val scanner = BoardBleScanner(context)
        scanner.startScan()
        assertTrue(scanner.isScanning.value)

        scanner.onBluetoothStateChanged(BluetoothAdapter.STATE_TURNING_OFF)
        assertFalse(scanner.isScanning.value)
        assertTrue(scanner.discoveredBoards.value.isEmpty())

        scanner.onBluetoothStateChanged(BluetoothAdapter.STATE_ON)
        assertTrue(scanner.isScanning.value)
    }

    @Test
    fun `an explicit stop cancels the resume`() {
        val scanner = BoardBleScanner(context)
        scanner.startScan()
        scanner.onBluetoothStateChanged(BluetoothAdapter.STATE_OFF)
        scanner.stopScan()
        scanner.onBluetoothStateChanged(BluetoothAdapter.STATE_ON)
        assertFalse(scanner.isScanning.value)
    }

    @Test
    fun `bluetooth coming on starts no scan nobody asked for`() {
        val scanner = BoardBleScanner(context)
        scanner.onBluetoothStateChanged(BluetoothAdapter.STATE_ON)
        assertFalse(scanner.isScanning.value)
    }

    @Test
    fun `the system broadcast reaches the scanner`() {
        val scanner = BoardBleScanner(context)
        scanner.startScan()
        context.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(scanner.isScanning.value)
    }
}
