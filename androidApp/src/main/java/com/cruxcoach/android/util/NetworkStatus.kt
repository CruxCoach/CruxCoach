package com.cruxcoach.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class NetworkStatus(
    val available: Boolean = false,
    val wifiConnected: Boolean = false,
)

private fun NetworkCapabilities?.toNetworkStatus(): NetworkStatus {
    val available = this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    return NetworkStatus(available, available && hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
}

/** Observes the app's default network, including validation after Wi-Fi connects. */
internal fun observeNetworkStatus(context: Context): Flow<NetworkStatus> = callbackFlow {
    val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    if (connectivity == null) {
        trySend(NetworkStatus())
        close()
        return@callbackFlow
    }
    val lock = Any()
    var currentNetwork: Network? = null
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(lock) { currentNetwork = network }
            // Android delivers capabilities next; querying them here can return stale data.
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            synchronized(lock) {
                if (network == currentNetwork) trySend(capabilities.toNetworkStatus())
            }
        }

        override fun onLost(network: Network) {
            synchronized(lock) {
                // A late loss of the previous network must not hide a new connection.
                if (network == currentNetwork) {
                    currentNetwork = null
                    trySend(NetworkStatus())
                }
            }
        }
    }
    synchronized(lock) {
        // Register before the initial snapshot so a disconnect cannot fall in a gap.
        // Serializing callbacks with that snapshot prevents an old initial value
        // from overwriting a connection event that arrived during registration.
        connectivity.registerDefaultNetworkCallback(callback)
        currentNetwork = connectivity.activeNetwork
        trySend(currentNetwork?.let(connectivity::getNetworkCapabilities).toNetworkStatus())
    }
    awaitClose { connectivity.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
