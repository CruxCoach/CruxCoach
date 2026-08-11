package com.cruxcoach.android.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Requests the exact ephemeral Wi-Fi named by a local-share QR and exposes the
 * resulting [Network] for per-connection routing. Closing the session releases
 * the request, at which point Android returns to the network it was using
 * before the share without changing process-wide routing.
 */
class LocalShareNetwork(private val context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    interface Session : Closeable {
        val network: Network
    }

    suspend fun connect(invitation: LocalShareProtocol.Invitation): Session =
        withTimeout(CONNECT_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                connectWithSpecifier(invitation)
            } else {
                connectLegacy(invitation)
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun connectWithSpecifier(invitation: LocalShareProtocol.Invitation): Session =
        suspendCancellableCoroutine { continuation ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(invitation.ssid)
                .setWpa2Passphrase(invitation.password)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                // The sender hotspot is intentionally local-only. Requiring
                // INTERNET makes this request unsatisfiable on some Android
                // builds and is exactly what tempts the system to keep using
                // mobile data instead.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            lateinit var callback: ConnectivityManager.NetworkCallback
            val handedOff = AtomicBoolean(false)
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!handedOff.compareAndSet(false, true)) return
                    if (!continuation.isActive) {
                        runCatching { connectivityManager.unregisterNetworkCallback(this) }
                        return
                    }
                    continuation.resume(object : Session {
                        override val network: Network = network
                        private var closed = false

                        override fun close() {
                            if (closed) return
                            closed = true
                            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                                .onFailure { Log.w(TAG, "Could not release share Wi-Fi", it) }
                        }
                    })
                }

                override fun onUnavailable() {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("Share Wi-Fi unavailable"))
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
            try {
                connectivityManager.requestNetwork(request, callback, CONNECT_TIMEOUT_MS.toInt())
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    /** Android 8/9 compatibility. The modern path above is used everywhere
     * Android can provide scoped Wi-Fi requests. */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun connectLegacy(invitation: LocalShareProtocol.Invitation): Session {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val previousNetworkId = wifiManager.connectionInfo?.networkId ?: -1
        val quotedSsid = quote(invitation.ssid)
        val existing = wifiManager.configuredNetworks
            ?.firstOrNull { it.SSID == quotedSsid }
        val created = existing == null
        val targetNetworkId = existing?.networkId ?: wifiManager.addNetwork(
            WifiConfiguration().apply {
                SSID = quotedSsid
                preSharedKey = quote(invitation.password)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            },
        )
        if (targetNetworkId < 0 || !wifiManager.enableNetwork(targetNetworkId, true)) {
            throw IOException("Could not select share Wi-Fi")
        }
        wifiManager.reconnect()

        try {
            while (unquote(wifiManager.connectionInfo?.ssid) != invitation.ssid) {
                delay(POLL_MS)
            }
            val selectedNetwork = connectivityManager.allNetworks.firstOrNull { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: throw IOException("Selected Wi-Fi has no routable network")
            return object : Session {
                override val network: Network = selectedNetwork
                private var closed = false

                override fun close() {
                    if (closed) return
                    closed = true
                    if (previousNetworkId >= 0) {
                        wifiManager.enableNetwork(previousNetworkId, true)
                        wifiManager.reconnect()
                    } else {
                        wifiManager.disconnect()
                    }
                    if (created) wifiManager.removeNetwork(targetNetworkId)
                }
            }
        } catch (error: Exception) {
            if (previousNetworkId >= 0) {
                wifiManager.enableNetwork(previousNetworkId, true)
                wifiManager.reconnect()
            }
            if (created) wifiManager.removeNetwork(targetNetworkId)
            throw error
        }
    }

    private fun quote(value: String): String = "\"${value.replace("\"", "\\\"")}\""
    private fun unquote(value: String?): String? = value?.removeSurrounding("\"")

    private companion object {
        const val TAG = "LocalShareNetwork"
        const val CONNECT_TIMEOUT_MS = 45_000L
        const val POLL_MS = 250L
    }
}
