package com.cruxcoach.android.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.cruxcoach.android.R
import kotlin.random.Random

/**
 * Creates a local hotspot for APK sharing.
 * Tries two strategies:
 * 1. WiFi Direct createGroup (Briar approach) — fixed IP 192.168.49.1
 * 2. LocalOnlyHotspot (fallback) — system-assigned IP
 */
class WifiDirectHotspot(context: Context) {

    companion object {
        const val GROUP_OWNER_IP = "192.168.49.1"
        private const val TAG = "WifiDirectHotspot"
        private const val LOCK_TAG = "CruxCoach:WifiDirectHotspot"
        private const val MAX_FRAMEWORK_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 1000L
        // Delay between initialize() and createGroup() — the P2P framework
        // needs time to bring up the interface after channel init (Briar has
        // an implicit delay here via an async DB read).
        private const val INIT_SETTLE_MS = 300L
        private const val LOCK_TIMEOUT_MS = 5 * 60 * 1000L  // 5 min

        private fun reasonToString(reason: Int): String = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
            WifiP2pManager.BUSY -> "BUSY"
            WifiP2pManager.ERROR -> "ERROR"
            else -> "UNKNOWN($reason)"
        }
    }

    private val appContext = context.applicationContext
    private val wifiP2pManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE)
            as? WifiP2pManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE)
            as? WifiManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE)
            as? PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var running = false
    private var usedStrategy = ""
    private val failureLog = mutableListOf<String>()

    data class HotspotInfo(val ssid: String, val password: String, val ip: String)

    @SuppressLint("MissingPermission")
    fun start(onStarted: (HotspotInfo) -> Unit, onError: (String) -> Unit) {
        val wifiOn = wifiManager?.isWifiEnabled
        Log.d(TAG, "start: API=${Build.VERSION.SDK_INT}, wifi=$wifiOn")

        if (wifiOn == false) {
            onError(appContext.getString(R.string.wifi_disabled_error))
            return
        }
        running = true
        failureLog.clear()
        acquireLocks()
        tryWifiDirect(1, onStarted, onError)
    }

    // ---- Strategy 1: WiFi Direct (Briar approach) ----

    @SuppressLint("MissingPermission")
    private fun tryWifiDirect(
        attempt: Int,
        onStarted: (HotspotInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!running) return
        if (wifiP2pManager == null) {
            failureLog.add("P2P: WifiP2pManager null")
            Log.w(TAG, "WifiP2pManager null, skipping to LocalOnlyHotspot")
            tryLocalOnlyHotspot(onStarted, onError)
            return
        }

        Log.d(TAG, "WiFi Direct attempt $attempt/$MAX_FRAMEWORK_ATTEMPTS")

        // Re-initialize channel for every attempt (Briar pattern: stale
        // channels keep returning BUSY).
        val ch = wifiP2pManager.initialize(appContext, Looper.getMainLooper(), null)
        channel = ch

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "WiFi Direct createGroup OK")
                requestGroupInfoWithRetry(ch, 1, onStarted, onError)
            }

            override fun onFailure(reason: Int) {
                failureLog.add("P2P #$attempt: ${reasonToString(reason)}")
                Log.w(TAG, "WiFi Direct failed: ${reasonToString(reason)}, attempt $attempt")

                // Retry on both BUSY and ERROR — the P2P framework may need
                // time to bring up its interface after initialize().
                if ((reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR)
                    && attempt < MAX_FRAMEWORK_ATTEMPTS
                ) {
                    if (Build.VERSION.SDK_INT >= 27) channel?.close()
                    channel = null
                    handler.postDelayed({
                        if (running) tryWifiDirect(attempt + 1, onStarted, onError)
                    }, RETRY_DELAY_MS)
                } else {
                    // All retries exhausted → try LocalOnlyHotspot
                    if (Build.VERSION.SDK_INT >= 27) channel?.close()
                    channel = null
                    tryLocalOnlyHotspot(onStarted, onError)
                }
            }
        }

        // Delay createGroup() after initialize() — the P2P state machine
        // needs time to transition from P2pDisabledState to InactiveState.
        // Briar has this delay implicitly via an async database read between
        // initialize() and createGroup().
        handler.postDelayed({
            if (!running) return@postDelayed
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    val suffix = Random.nextInt(1000, 9999)
                    // WiFi Direct SSID must match DIRECT-xx-... (2 random chars + dash)
                    val r1 = ('a'..'z').random()
                    val r2 = ('a'..'z').random()
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-${r1}${r2}-CruxCoach$suffix")
                        .setPassphrase(generatePassphrase())
                        .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                        .build()
                    wifiP2pManager.createGroup(ch, config, listener)
                } else {
                    wifiP2pManager.createGroup(ch, listener)
                }
            } catch (e: Exception) {
                failureLog.add("P2P exception: ${e.message}")
                Log.e(TAG, "createGroup exception", e)
                tryLocalOnlyHotspot(onStarted, onError)
            }
        }, INIT_SETTLE_MS)
    }

    // Briar retries requestGroupInfo up to 5 times because on some devices
    // the group info isn't available immediately after createGroup succeeds.
    @SuppressLint("MissingPermission")
    private fun requestGroupInfoWithRetry(
        ch: WifiP2pManager.Channel,
        attempt: Int,
        onStarted: (HotspotInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!running) return
        Log.d(TAG, "requestGroupInfo attempt $attempt/$MAX_FRAMEWORK_ATTEMPTS")
        wifiP2pManager?.requestGroupInfo(ch) { group ->
            if (group?.networkName != null && group.passphrase != null) {
                usedStrategy = "WiFi Direct"
                onStarted(HotspotInfo(group.networkName, group.passphrase, GROUP_OWNER_IP))
            } else if (attempt < MAX_FRAMEWORK_ATTEMPTS) {
                handler.postDelayed({
                    if (running) requestGroupInfoWithRetry(ch, attempt + 1, onStarted, onError)
                }, RETRY_DELAY_MS)
            } else {
                failureLog.add("P2P: groupInfo null after $attempt attempts")
                tryLocalOnlyHotspot(onStarted, onError)
            }
        }
    }

    // ---- Strategy 2: LocalOnlyHotspot (fallback) ----

    @SuppressLint("MissingPermission")
    private fun tryLocalOnlyHotspot(
        onStarted: (HotspotInfo) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!running) return
        Log.d(TAG, "Trying LocalOnlyHotspot fallback")

        try {
            wifiManager?.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val config = reservation.wifiConfiguration
                        ?: if (Build.VERSION.SDK_INT >= 30) {
                            reservation.softApConfiguration?.let { sac ->
                                Log.d(TAG, "LocalOnlyHotspot started: ${sac.ssid}")
                                usedStrategy = "LocalOnlyHotspot"
                                val ip = ApkShareHelper.getDeviceIpAddress() ?: "192.168.43.1"
                                val ssid = sac.ssid ?: "unknown"
                                val pass = sac.passphrase ?: ""
                                onStarted(HotspotInfo(ssid, pass, ip))
                                return
                            } ?: run {
                                failureLog.add("LOH: no config")
                                reportFinalError(onError)
                                return
                            }
                        } else {
                            failureLog.add("LOH: no wifiConfiguration")
                            reportFinalError(onError)
                            return
                        }

                    @Suppress("DEPRECATION")
                    val ssid = config.SSID?.trim('"') ?: "unknown"
                    @Suppress("DEPRECATION")
                    val pass = config.preSharedKey?.trim('"') ?: ""
                    val ip = ApkShareHelper.getDeviceIpAddress() ?: "192.168.43.1"
                    Log.d(TAG, "LocalOnlyHotspot started: $ssid")
                    usedStrategy = "LocalOnlyHotspot"
                    onStarted(HotspotInfo(ssid, pass, ip))
                }

                override fun onStopped() {
                    Log.d(TAG, "LocalOnlyHotspot stopped")
                }

                override fun onFailed(reason: Int) {
                    failureLog.add("LOH failed: reason=$reason")
                    Log.w(TAG, "LocalOnlyHotspot failed: $reason")
                    reportFinalError(onError)
                }
            }, handler)
        } catch (e: Exception) {
            failureLog.add("LOH exception: ${e.message}")
            Log.e(TAG, "LocalOnlyHotspot exception", e)
            reportFinalError(onError)
        }
    }

    // ---- Error & cleanup ----

    private fun reportFinalError(onError: (String) -> Unit) {
        val details = failureLog.joinToString("\n")
        val msg = appContext.getString(
            R.string.hotspot_start_failed,
            Build.VERSION.SDK_INT,
            wifiManager?.isWifiEnabled.toString(),
            details
        )
        releaseLocks()
        closeChannel()
        onError(msg)
    }

    private fun acquireLocks() {
        try {
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG
            )?.apply { acquire(LOCK_TIMEOUT_MS) }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed", e)
        }
        try {
            @Suppress("DEPRECATION")
            wifiLock = wifiManager?.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, LOCK_TAG
            )?.apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock failed", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed", e)
        }
        wakeLock = null
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock release failed", e)
        }
        wifiLock = null
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        running = false
        // Stop WiFi Direct group
        channel?.let { ch ->
            try {
                wifiP2pManager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { Log.d(TAG, "Group removed") }
                    override fun onFailure(reason: Int) { Log.w(TAG, "removeGroup: ${reasonToString(reason)}") }
                })
            } catch (e: Exception) { Log.w(TAG, "removeGroup error", e) }
        }
        closeChannel()
        // Stop LocalOnlyHotspot
        try { hotspotReservation?.close() } catch (e: Exception) { Log.w(TAG, "LOH close error", e) }
        hotspotReservation = null
        releaseLocks()
    }

    private fun closeChannel() {
        if (Build.VERSION.SDK_INT >= 27) channel?.close()
        channel = null
    }

    private fun generatePassphrase(): String {
        // 16 chars from 30-char alphabet ≈ 78 bits entropy.
        // WPA2 PBKDF2 makes offline brute force infeasible at this length.
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }
}
