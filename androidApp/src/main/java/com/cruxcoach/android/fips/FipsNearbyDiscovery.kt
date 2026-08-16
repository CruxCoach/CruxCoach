package com.cruxcoach.android.fips

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

/** Read-only scanner used by the mesh overview before any realm is active. */
@SuppressLint("MissingPermission")
internal class FipsNearbyDiscovery(
    context: Context,
    private val onMesh: (FipsNearbyMesh) -> Unit,
) {
    private val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
        ?.adapter?.bluetoothLeScanner
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            FipsDebugLog.warning("discovery", "passive_scan_failed", "code" to errorCode)
        }
    }

    fun start() {
        val filter = ScanFilter.Builder().setServiceData(CRUXCOACH_FIPS_UUID, byteArrayOf()).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        runCatching { scanner?.startScan(listOf(filter), settings, callback) }
            .onSuccess { FipsDebugLog.event("discovery", "passive_scan_started") }
            .onFailure { FipsDebugLog.warning("discovery", "passive_scan_failed", "error" to it.message) }
    }

    fun stop() {
        runCatching { scanner?.stopScan(callback) }
        FipsDebugLog.event("discovery", "passive_scan_stopped")
    }

    private fun handle(result: ScanResult) {
        val bytes = result.scanRecord?.getServiceData(CRUXCOACH_FIPS_UUID) ?: return
        val advertisement = FipsAdvertisementCodec.decode(bytes) ?: return
        val boardName = result.scanRecord?.getServiceData(CRUXCOACH_FIPS_NAME_UUID)
            ?.decodeToString()?.trim()?.takeIf(String::isNotEmpty)
        onMesh(FipsNearbyMesh(
            address = result.device.address,
            realmTag = advertisement.realmTag.toHex(),
            cellTag = advertisement.cellTag.toHex(),
            rssi = result.rssi,
            lastSeenMs = System.currentTimeMillis(),
            matchesActiveRealm = false,
            joinableBoardCellId = advertisement.joinableBoardCellId,
            boardName = boardName,
        ))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        private val CRUXCOACH_FIPS_UUID =
            ParcelUuid(UUID.fromString("0000ccf1-0000-1000-8000-00805f9b34fb"))
        private val CRUXCOACH_FIPS_NAME_UUID =
            ParcelUuid(UUID.fromString("0000ccf2-0000-1000-8000-00805f9b34fb"))
    }
}
