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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.long
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

/** An open invitation link of this installation; [expiresAt] in epoch ms. */
data class MarmotLink(val id: String?, val payload: String, val createdAt: Long, val expiresAt: Long)

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
    /** The QR form of a fresh contact code (valid an hour). */
    suspend fun contactCode(): String = io { raw("contact_code").jsonObject["payload"]!!.jsonPrimitive.content }
    /** A scanned contact code or an opened link: returns the account invited. */
    suspend fun importContact(payload: String): String =
        io { raw("import_contact") { put("payload", payload) }.jsonObject["peer"]!!.jsonPrimitive.content }
    suspend fun linkCode(): MarmotLink = io {
        val value = raw("link_code").jsonObject
        MarmotLink(null, value["payload"]!!.jsonPrimitive.content, System.currentTimeMillis(), value["expires_at"]!!.jsonPrimitive.long * 1000)
    }
    suspend fun links(): List<MarmotLink> = io {
        raw("links").jsonObject["links"]!!.jsonArray.map { element ->
            val link = element.jsonObject
            MarmotLink(
                link["id"]!!.jsonPrimitive.content,
                link["payload"]!!.jsonPrimitive.content,
                link["created_at"]!!.jsonPrimitive.long,
                link["expires_at"]!!.jsonPrimitive.long * 1000,
            )
        }
    }
    suspend fun revokeLink(id: String): Boolean =
        io { raw("revoke_link") { put("id", id) }.jsonObject["revoked"]!!.jsonPrimitive.boolean }
    suspend fun invite(peer: String): String = io { raw("invite") { put("peer", peer) }.jsonObject["group"]!!.jsonPrimitive.content }
    suspend fun accept(peer: String) { io { raw("accept") { put("peer", peer) } } }
    suspend fun decline(peer: String) { io { raw("decline") { put("peer", peer) } } }
    suspend fun send(peer: String, token: String, content: String): MarmotSent =
        io { value(raw("send") { put("peer", peer); put("token", token); put("content", content) }) }
    /**
     * Many messages, admitted in as few native transactions as fit (one commit
     * instead of one per message). Per message the result or the refusal;
     * after a refusal the rest for that friend is skipped. A host without
     * batches gets them one by one.
     */
    suspend fun sendMany(items: List<Triple<String, String, String>>): List<Result<MarmotSent>> = io {
        val out = ArrayList<Result<MarmotSent>>(items.size)
        val failed = mutableSetOf<String>()
        var start = 0
        while (start < items.size) {
            var end = start
            var bytes = 0
            while (end < items.size && end - start < SEND_BATCH && (end == start || bytes + items[end].third.length < SEND_BATCH_BYTES)) {
                bytes += items[end].third.length + items[end].second.length + 160
                end++
            }
            // Friends that failed in an earlier chunk get nothing later.
            val chunk = items.subList(start, end)
            val asked = chunk.withIndex().filter { it.value.first !in failed }
            val results = if (batches) runCatching { batch(asked.map { it.value }) }.getOrElse { error ->
                if ((error as? MarmotFailure)?.code in UNBATCHED) { batches = false; single(asked.map { it.value }) } else throw error
            } else single(asked.map { it.value })
            val byIndex = asked.map { it.index }.zip(results).toMap()
            chunk.forEachIndexed { index, item ->
                val result = byIndex[index] ?: Result.failure(MarmotFailure("skipped"))
                if (result.isFailure) failed += item.first
                out += result
            }
            start = end
        }
        out
    }

    @Volatile private var batches = true

    private fun batch(items: List<Triple<String, String, String>>): List<Result<MarmotSent>> {
        if (items.isEmpty()) return emptyList()
        val answer = raw("send_many") {
            put("items", buildJsonArray {
                items.forEach { (peer, token, content) -> add(buildJsonObject { put("peer", peer); put("token", token); put("content", content) }) }
            })
        }
        return answer.jsonObject["results"]!!.jsonArray.map { element ->
            val result = element.jsonObject
            result["error"]?.let { Result.failure(MarmotFailure(it.jsonPrimitive.content)) }
                ?: Result.success(value<MarmotSent>(element))
        }
    }

    /** One by one, stopping a friend at its first refusal. */
    private fun single(items: List<Triple<String, String, String>>): List<Result<MarmotSent>> {
        val failed = mutableSetOf<String>()
        return items.map { (peer, token, content) ->
            if (peer in failed) Result.failure(MarmotFailure("skipped"))
            else runCatching { value<MarmotSent>(raw("send") { put("peer", peer); put("token", token); put("content", content) }) }
                .onFailure { failed += peer }
        }
    }

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

    private companion object {
        const val SEND_BATCH = 256
        // Escaped JSON grows; a native command takes at most 512 KiB.
        const val SEND_BATCH_BYTES = 300_000
        /** A host that does not know batches says so in one of these. */
        val UNBATCHED = setOf("command_format", "unsupported")
    }
}
