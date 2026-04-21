package com.cruxcoach.android.nostr

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges system connectivity events into [NostrRelayPool.reconnectAll].
 *
 * The pool's per-relay reconnect loop gives up after
 * [NostrConfig.MAX_RECONNECT_ATTEMPTS], setting `reconnectExhausted = true`.
 * Without an external trigger the app would then stay offline even after
 * Wi-Fi / cellular returns. This observer listens for `onAvailable` and
 * resets the exhaustion flag so subscriptions come back automatically.
 *
 * Owns a process-lifetime callback — never unregistered, because the
 * relay pool is a process-singleton itself. Safe because the callback
 * count per process is bounded (one per app) and the OS tolerates that.
 */
@Singleton
class NostrRelayConnectivityObserver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val relayPool: NostrRelayPool
) {
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, "Network available — resetting relay reconnect state")
            // Idempotent: no-op if all relays are already connected.
            relayPool.reconnectAll()
        }
    }

    fun start() {
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register connectivity callback", e)
        }
    }

    companion object {
        private const val TAG = "NostrRelayConnectivityObserver"
    }
}
