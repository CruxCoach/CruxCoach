package com.cruxcoach.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds a CruxCoach sender on the Wi-Fi the user joined before installing the
 * app. This is the bridge that removes the old "return to the browser and tap
 * import" step: the first onboarding screen probes the Wi-Fi gateway and
 * validates the share manifest itself.
 */
class LocalShareDiscovery(
    context: Context,
    private val connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager,
) {
    data class Found(
        val network: Network,
        val baseUrl: String,
        val manifest: LocalShareProtocol.Manifest,
    )

    suspend fun discover(client: LocalShareClient = LocalShareClient()): Found? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(DISCOVERY_BUDGET_MS) {
                repeat(MAX_NETWORK_DISCOVERY_ATTEMPTS) { attempt ->
                    for (network in wifiNetworks()) {
                        for (host in candidateHosts(network)) {
                            val baseUrl = "http://$host:${LocalApkServer.LOCAL_SHARE_PORT}"
                            val manifest = runCatching {
                                client.fetchManifest(
                                    network = network,
                                    baseUrl = baseUrl,
                                    connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
                                    readTimeoutMs = PROBE_READ_TIMEOUT_MS,
                                )
                            }.onFailure {
                                Log.d(TAG, "No local share at $baseUrl: ${it.javaClass.simpleName}")
                            }.getOrNull()
                            if (manifest != null) {
                                return@withTimeoutOrNull Found(network, baseUrl, manifest)
                            }
                        }
                    }
                    if (attempt + 1 < MAX_NETWORK_DISCOVERY_ATTEMPTS) delay(NETWORK_SETTLE_MS)
                }
                null
            }
        }

    /**
     * Resolve a credential-free landing-page hand-off on the Wi-Fi to which
     * the browser is already connected. The exact validated origin is probed
     * only through Wi-Fi [Network] objects, never through the process default
     * (which may be cellular on a local-only hotspot).
     */
    suspend fun discoverAt(
        requestedBaseUrl: String,
        client: LocalShareClient = LocalShareClient(),
    ): Found? = withContext(Dispatchers.IO) {
        val baseUrl = LocalShareProtocol.normalizeHttpOrigin(requestedBaseUrl)
            ?: return@withContext null
        val host = android.net.Uri.parse(baseUrl).host
        if (!LocalShareProtocol.isPrivateIpv4(host)) return@withContext null
        withTimeoutOrNull(DISCOVERY_BUDGET_MS) {
            repeat(MAX_NETWORK_DISCOVERY_ATTEMPTS) { attempt ->
                for (network in wifiNetworks()) {
                    val manifest = runCatching {
                        client.fetchManifest(
                            network = network,
                            baseUrl = baseUrl,
                            connectTimeoutMs = PROBE_CONNECT_TIMEOUT_MS,
                            readTimeoutMs = PROBE_READ_TIMEOUT_MS,
                        )
                    }.onFailure {
                        Log.d(TAG, "No connected local share at $baseUrl: ${it.javaClass.simpleName}")
                    }.getOrNull()
                    if (manifest != null) return@withTimeoutOrNull Found(network, baseUrl, manifest)
                }
                if (attempt + 1 < MAX_NETWORK_DISCOVERY_ATTEMPTS) delay(NETWORK_SETTLE_MS)
            }
            null
        }
    }

    private fun wifiNetworks(): List<Network> {
        val activeNetwork = connectivityManager.activeNetwork
        return connectivityManager.allNetworks
            .filter { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            .sortedByDescending { it == activeNetwork }
    }

    private fun candidateHosts(network: Network): List<String> {
        val gateways = connectivityManager.getLinkProperties(network)
            ?.routes
            .orEmpty()
            .mapNotNull { route -> (route.gateway as? Inet4Address)?.hostAddress }
            .filter(LocalShareProtocol::isPrivateIpv4)
        // The explicit constants cover devices whose LinkProperties omit the
        // gateway for a local-only/P2P network. LinkedHashSet keeps the actual
        // DHCP gateway first and removes duplicates.
        return (gateways + WifiDirectHotspot.GROUP_OWNER_IP + LOCAL_ONLY_HOTSPOT_LEGACY_IP)
            .distinct()
    }

    private companion object {
        const val TAG = "LocalShareDiscovery"
        const val LOCAL_ONLY_HOTSPOT_LEGACY_IP = "192.168.43.1"
        const val DISCOVERY_BUDGET_MS = 4_500L
        const val PROBE_CONNECT_TIMEOUT_MS = 900
        const val PROBE_READ_TIMEOUT_MS = 1_200
        const val MAX_NETWORK_DISCOVERY_ATTEMPTS = 2
        const val NETWORK_SETTLE_MS = 350L
    }
}
