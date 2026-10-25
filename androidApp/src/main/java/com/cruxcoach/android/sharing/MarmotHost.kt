package com.cruxcoach.android.sharing

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put

/** The raw JNI calls behind the chokepoint. Production and the loopback
 * harness both use [MarmotNative]; tests may substitute a fake. */
interface MarmotBinding {
    fun call(handle: Long, command: String): String
    fun close(handle: Long)
}

object NativeMarmotBinding : MarmotBinding {
    override fun call(handle: Long, command: String): String = MarmotNative.call(handle, command)
    override fun close(handle: Long) = MarmotNative.close(handle)
}

/** A named section around every native operation (Android systrace in the app). */
interface MarmotTrace {
    fun <T> section(name: String, block: () -> T): T

    object None : MarmotTrace {
        override fun <T> section(name: String, block: () -> T): T = block()
    }
}

class MarmotFailure(val code: String) : IllegalStateException("Marmot operation refused: $code")

@Serializable data class MarmotRelay(
    val url: String,
    val connection: String,
    @SerialName("last_result") val lastResult: String,
    val nip77: Boolean,
)

@Serializable data class MarmotPeer(
    val account: String,
    val group: String,
    val state: String,
    @SerialName("invited_by_me") val invitedByMe: Boolean,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_seen") val lastSeen: Long,
    @SerialName("cancelled_at") val cancelledAt: Long,
    @SerialName("ended_at") val endedAt: Long,
    val reason: String,
    @SerialName("outbound_pending") val outboundPending: Long = 0,
) {
    val active get() = state == "active"
    val ended get() = state == "ended"
}

@Serializable data class MarmotDiscovery(
    val enabled: Boolean,
    @SerialName("key_package_until") val keyPackageUntil: Long,
)

@Serializable data class MarmotLocal(
    val events: Long,
    val inbox: Long,
    @SerialName("outbound_pending") val outboundPending: Long,
)

@Serializable data class MarmotStatus(
    val account: String,
    val online: Boolean,
    val discovery: MarmotDiscovery,
    val local: MarmotLocal,
    val peers: List<MarmotPeer>,
    val relays: List<MarmotRelay>,
    @SerialName("local_relay") val localRelay: Boolean,
    val generation: Long,
    @SerialName("database_bytes") val databaseBytes: Long = 0,
)

@Serializable data class MarmotMessage(
    val seq: Long,
    val peer: String,
    val content: String,
    @SerialName("received_at") val receivedAt: Long,
)

@Serializable data class MarmotBatch(val items: List<MarmotMessage>, val generation: Long)

@Serializable data class MarmotSent(val event: String, val duplicate: Boolean)

@Serializable private data class NativeResponse(val ok: Boolean, val value: JsonElement? = null, val error: String? = null)

/**
 * The one chokepoint between Kotlin and the native Marmot host.
 *
 * Every operation runs on the IO dispatcher inside a trace section, decodes a
 * bounded DTO and turns native refusals into [MarmotFailure] with a fixed
 * code. Kotlin keeps no copy of protocol data (groups, relays, events,
 * delivery state, undelivered messages); it asks [status] instead.
 */
class MarmotHost(
    private val open: () -> Long,
    private val binding: MarmotBinding = NativeMarmotBinding,
    private val trace: MarmotTrace = MarmotTrace.None,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var handle = 0L
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun handle(): Long = synchronized(lock) {
        if (handle == 0L) handle = open().also { check(it > 0) { "native_open" } }
        handle
    }

    private fun raw(op: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonElement {
        val request = buildJsonObject { put("op", op); fields() }.toString()
        val id = handle()
        val encoded = trace.section("marmot.$op") { binding.call(id, request) }
        val response = runCatching { json.decodeFromString<NativeResponse>(encoded) }
            .getOrElse { throw MarmotFailure("native_format") }
        if (!response.ok) {
            // A rolled-back native transaction cannot reuse its in-memory
            // ratchet: close now; the next call reopens and rehydrates.
            if (response.error == "reopen_required") close(id)
            throw MarmotFailure(response.error ?: "native_failed")
        }
        return response.value ?: JsonNull
    }

    private inline fun <reified T> value(element: JsonElement): T =
        runCatching { json.decodeFromJsonElement<T>(element) }.getOrElse { throw MarmotFailure("native_format") }

    private suspend fun <T> io(block: () -> T): T = withContext(io) { block() }

    suspend fun status(): MarmotStatus = io { value(raw("status")) }
    suspend fun setOnline(online: Boolean) { io { raw("set_online") { put("online", online) } } }
    suspend fun setDiscovery(enabled: Boolean) { io { raw("set_discovery") { put("enabled", enabled) } } }
    suspend fun setRelays(relays: List<String>) {
        io { raw("set_relays") { put("relays", buildJsonArray { relays.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) } }
    }
    suspend fun invite(peer: String): String = io { raw("invite") { put("peer", peer) }.jsonObject["group"]!!.jsonPrimitive.content }
    suspend fun accept(peer: String) { io { raw("accept") { put("peer", peer) } } }
    suspend fun decline(peer: String) { io { raw("decline") { put("peer", peer) } } }
    suspend fun send(peer: String, token: String, content: String): MarmotSent =
        io { value(raw("send") { put("peer", peer); put("token", token); put("content", content) }) }
    suspend fun cancel(peer: String): Int = io { raw("cancel") { put("peer", peer) }.jsonObject["cancelled"]!!.jsonPrimitive.int }
    suspend fun end(peer: String) { io { raw("end") { put("peer", peer) } } }
    suspend fun ack(seqs: List<Long>) {
        if (seqs.isEmpty()) return
        io { raw("ack") { put("seqs", buildJsonArray { seqs.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) } }
    }
    suspend fun sync() { io { raw("sync") } }

    /** Pull stream step: waits natively (without the engine lock) for new
     * messages or a changed generation, at most [timeoutMs]. */
    suspend fun next(after: Long, generation: Long, timeoutMs: Long, limit: Int = 32): MarmotBatch = io {
        value(raw("next") { put("after", after); put("generation", generation); put("timeout_ms", timeoutMs); put("limit", limit) })
    }

    /** Harness-only operations exist only in the loopback test library. */
    internal fun harness(op: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonElement = raw(op, fields)

    private fun close(id: Long) = synchronized(lock) {
        if (handle == id) {
            binding.close(id)
            handle = 0
        }
    }

    fun close() = synchronized(lock) {
        if (handle != 0L) binding.close(handle)
        handle = 0
    }

    val isOpen: Boolean get() = synchronized(lock) { handle != 0L }
}
