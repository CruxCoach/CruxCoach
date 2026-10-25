package com.cruxcoach.android.sharing.p2p

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One direct path to nearby CruxCoach devices: LAN HTTP (same Wi-Fi, hotspot,
 * Wi-Fi Direct) or BLE GATT; later perhaps a direct QUIC path. A transport
 * moves opaque containers that the native host builds and parses. It never
 * parses, stores or logs them and never learns which person an endpoint
 * belongs to (the native host identifies peers from rotating beacons).
 *
 * There is deliberately no relay transport. Delivery happens only while both
 * devices are in reach of each other at the same time; nothing is stored and
 * forwarded by a third party. An opt-in store-and-forward transport could be
 * added behind this interface later without changing anything above
 * [com.cruxcoach.android.sharing.MarmotHost].
 */
interface ShareTransport {
    /** Short name used in the connection projection ("lan", "ble"). */
    val name: String

    /** Largest frame budget of one exchange step on this path. */
    val maxContainer: Int

    val running: Boolean

    /** Listen and advertise; [server] answers incoming hello/exchange requests. */
    fun start(server: ShareExchangeServer)

    fun stop()

    /** Endpoints in reach right now, with what each advertises. */
    fun endpoints(): List<ShareEndpoint>

    /** One request/response step; null when the endpoint did not answer. */
    fun exchange(endpoint: ShareEndpoint, request: ByteArray): ByteArray?
}

/** The local side every transport serves: the native host's hello and exchange. */
interface ShareExchangeServer {
    fun hello(): ShareHello?
    fun exchange(request: ByteArray, maxBytes: Int): ByteArray?
}

/** An endpoint's advertisement: rotating beacons only (device, contact code, invitations). */
data class ShareHello(val beacons: List<String>) {
    fun toJson(): String = buildJsonObject {
        put("beacons", buildJsonArray { beacons.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    companion object {
        private val beacon = Regex("[0-9a-f]{16}")

        fun from(value: JsonElement): ShareHello? = runCatching {
            val o = value.jsonObject
            val beacons = o["beacons"]!!.jsonArray.map { it.jsonPrimitive.content }
            require(beacons.size in 1..4 && beacons.all { beacon.matches(it) })
            ShareHello(beacons)
        }.getOrNull()

        fun parse(raw: String): ShareHello? =
            if (raw.length > 1024) null else runCatching { from(kotlinx.serialization.json.Json.parseToJsonElement(raw)) }.getOrNull()
    }
}

/** A reachable endpoint. [address] is transport-specific (URL, BLE address). */
data class ShareEndpoint(val transport: String, val address: String, val hello: ShareHello?)
