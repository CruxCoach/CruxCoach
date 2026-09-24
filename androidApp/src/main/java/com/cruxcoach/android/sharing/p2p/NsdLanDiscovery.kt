package com.cruxcoach.android.sharing.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * NSD (mDNS/DNS-SD) discovery of CruxCoach share endpoints on the current
 * Wi-Fi. The instance name is random per start and carries no identity; who
 * is behind an endpoint is learned only from its rotating beacons.
 *
 * Spike M0: compiled, not tested on a device.
 */
class NsdLanDiscovery(context: Context) : LanDiscovery {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = ConcurrentHashMap<String, String>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var ownName: String? = null
    private val resolving = java.util.concurrent.atomic.AtomicBoolean(false)
    private val queue = java.util.concurrent.ConcurrentLinkedQueue<NsdServiceInfo>()

    @Synchronized override fun start(port: Int) {
        if (registration != null) return
        val name = "cc-" + ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        ownName = name
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val register = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) { ownName = info.serviceName }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        val discover = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) = Unit
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceName == ownName || found.size >= MAX_FOUND) return
                queue += info
                resolveNext()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                found.remove(info.serviceName)
            }
        }
        registration = register
        discoveryListener = discover
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, register)
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discover)
    }

    /** Legacy resolve handles one request at a time. */
    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!resolving.compareAndSet(false, true)) return
        val next = queue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        nsd.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                resolving.set(false)
                resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host
                if (host != null && (host.isSiteLocalAddress || host.isLinkLocalAddress)) {
                    val address = host.hostAddress?.let { if (':' in it) "[${it.substringBefore('%')}]" else it }
                    if (address != null) found[info.serviceName] = "http://$address:${info.port}"
                }
                resolving.set(false)
                resolveNext()
            }
        })
    }

    @Synchronized override fun stop() {
        registration?.let { runCatching { nsd.unregisterService(it) } }
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        registration = null
        discoveryListener = null
        found.clear()
        queue.clear()
    }

    override fun urls(): List<String> = found.values.toList()

    companion object {
        const val SERVICE_TYPE = "_ccshare._tcp."
        const val MAX_FOUND = 32
    }
}
