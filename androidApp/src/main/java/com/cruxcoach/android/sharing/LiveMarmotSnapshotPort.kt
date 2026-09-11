package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.Nip01SignedEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import java.security.MessageDigest

@Serializable internal data class NativeFence(
    val group: String, val binding: String, val epoch: Long,
    val local_account: String, val local_leaf: String, val peer_account: String, val peer_leaf: String,
)
@Serializable internal data class NativeInbox(
    val source: String, val fence: NativeFence, val kind: Int, val tags: List<List<String>>,
    val content: String, val acknowledged: Boolean, val invalidated: Boolean, val retained_until: Long,
)
@Serializable internal data class NativePending(
    val event_id: String, val fence: NativeFence, val kind: Int, val tags: List<List<String>>,
    val content: String, val expires_at: Long,
)
@Serializable data class MarmotPeerStatus(val account: String, val group: String, val accepted: Boolean, val inbox: List<String>, val ready: Boolean = false)
@Serializable private data class NativeResponse(val ok: Boolean, val value: JsonElement? = null, val error: String? = null)

class MarmotTransportFailure(val code: String) : IllegalStateException("Marmot transport operation refused")

/** Account-signed binding of this authority device's proof of possession to an
 * actual MDK leaf. It grants transport identity only, never content permission. */
@Serializable internal data class LeafDeviceBinding(
    val version: Int = 1, val purpose: String = "cc.marmot.device.v1",
    val account: String, val device: String, val leaf: String, val binding: String,
    val expiresAt: Long,
)
@Serializable internal data class BindingEvent(
    val id: String, val pubkey: String, val created_at: Long, val kind: Int,
    val tags: List<List<String>>, val content: String, val sig: String,
)

/** All callers, JNI commands and callback effects share this monitor. The
 * native engine has no independent mutator. It additionally compares the full
 * expected fence in the transaction that persists an exact encrypted handoff. */
class LiveMarmotSnapshotPort(
    private val account: String,
    private val device: () -> DeviceIdentity?,
    private val currentAccount: () -> String,
    private val authorised: () -> Boolean,
    private val nativeLoaded: () -> Boolean,
    private val open: () -> Long,
    private val invoke: (Long, String) -> String,
    private val close: (Long) -> Unit,
    private val accountSign: (Long, Int, List<List<String>>, String) -> Nip01SignedEvent?,
    private val deviceSign: (ByteArray) -> ByteArray?,
    private val verify: Bip340Verifier,
    private val now: () -> Long = System::currentTimeMillis,
) : MarmotSnapshotPort {
    private val lock = Any()
    private var handle: Long? = null
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val helloTags = listOf(listOf("l", "cc.marmot.device.v1", "cruxcoach.private"))
    override val nativeAvailable get() = nativeLoaded() && currentAccount() == account
    override val durableIdempotentHandoff get() = true

    private fun ensureAccount() {
        check(nativeAvailable && authorised()) { "native_authority_unavailable" }
    }
    private fun command(op: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonElement {
        ensureAccount()
        val id = handle ?: open().also { check(it > 0); handle = it }
        val request = buildJsonObject { put("op", op); fields() }
        val response = runCatching { json.decodeFromString<NativeResponse>(invoke(id, request.toString())) }
            .getOrElse { throw MarmotTransportFailure("native_format") }
        check(currentAccount() == account && authorised()) { "native_account_changed" }
        if (!response.ok) throw MarmotTransportFailure(response.error ?: "native_failed")
        return response.value ?: JsonNull
    }
    private inline fun <reified T> nativeValue(value: JsonElement): T =
        runCatching { json.decodeFromJsonElement<T>(value) }
            .getOrElse { throw MarmotTransportFailure("native_format") }
    fun shutdown() = synchronized(lock) { handle?.let(close); handle = null }
    fun bootstrap() = synchronized(lock) { command("bootstrap") { put("refresh", true) }; command("sync"); Unit }
    fun discoveryEnabled(): Boolean = synchronized(lock) { command("discovery_enabled").jsonPrimitive.boolean }
    fun peers(): List<MarmotPeerStatus> = synchronized(lock) {
        nativeValue<List<MarmotPeerStatus>>(command("peers")).map { peer ->
            peer.copy(ready = peer.accepted && runCatching { session(fence(peer.account)) != null }.getOrDefault(false))
        }
    }
    fun invite(peer: String) = synchronized(lock) {
        command("invite") { put("peer", peer) }; sendHello(peer); command("sync"); Unit
    }
    fun acceptInvitation(peer: String, group: String? = null) = synchronized(lock) {
        command("accept") { put("peer", peer); group?.let { put("group", it) } }; sendHello(peer); command("sync"); Unit
    }
    fun resetPeer(peer: String) = synchronized(lock) { command("reset") { put("peer", peer) }; Unit }
    fun relaySummary(): Map<String, String> = synchronized(lock) {
        val byRelay: Map<String, List<String>> = nativeValue(command("relay_summary"))
        byRelay.mapValues { (_, states) ->
            when {
                "limited" in states -> "limited"
                "auth_required" in states -> "auth_required"
                "unavailable" in states -> "unavailable"
                "rejected" in states -> "rejected"
                states.isNotEmpty() && states.all { it == "accepted" || it == "read_complete" } -> "accepted"
                else -> "pending"
            }
        }
    }
    override fun refresh() = synchronized(lock) {
        command("sync")
        peers().filter { it.accepted }.forEach { peer -> runCatching { sendHello(peer.account) } }
        Unit
    }

    private fun fence(peer: String): NativeFence = nativeValue(command("fence") { put("peer", peer) })
    // Hold the port monitor while consuming these sequences. A page remains
    // bounded even when the encrypted journal contains a large offline backlog.
    private fun inbox(kind: Int): Sequence<NativeInbox> = sequence {
        var after: String? = null
        do {
            val page: List<NativeInbox> = nativeValue(command("inbox") { put("kind", kind); after?.let { put("after", it) } })
            page.forEach { yield(it) }
            after = page.lastOrNull()?.source
        } while (page.size == 16)
    }
    private fun pending(kind: Int): Sequence<NativePending> = sequence {
        var after: String? = null
        do {
            val page: List<NativePending> = nativeValue(command("pending") { put("kind", kind); after?.let { put("after", it) } })
            page.forEach { yield(it) }
            after = page.lastOrNull()?.event_id
        } while (page.size == 16)
    }
    private fun eventId(pubkey: String, time: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val preimage = buildJsonArray {
            add(0); add(pubkey); add(time); add(kind); add(json.encodeToJsonElement(tags)); add(content)
        }.toString().encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(preimage).hex()
    }
    private fun validEvent(event: BindingEvent): Boolean = runCatching {
        event.id == eventId(event.pubkey, event.created_at, event.kind, event.tags, event.content) &&
            event.kind == 1221 && event.tags == helloTags && event.created_at >= 0 &&
            event.created_at <= now() / 1000 + 60 &&
            verify.verify(event.sig.unhex(), event.id.unhex(), event.pubkey.unhex())
    }.getOrDefault(false)

    private fun sendHello(peer: String) {
        val fence = fence(peer)
        val local = device() ?: error("native_device_unavailable")
        // The exact operation is durable in native storage. Reuse the original
        // signed binding on reconnect; no repeated account prompts per epoch.
        val cached = command("binding") { put("binding", fence.binding) }
        if (cached is JsonArray && cached[0].jsonPrimitive.long > now()) {
            if (cachedDevice(cached) != local.publicKey) {
                resetPeer(peer)
                throw MarmotTransportFailure("native_device_changed")
            }
            val event = send(fence, 1221, helloTags, cached[1].jsonPrimitive.content, cached[0].jsonPrimitive.long)
            publish(event, fence); return
        }
        val expiry = now() + 24 * 60 * 60 * 1000
        val binding = LeafDeviceBinding(account = account, device = local.publicKey,
            leaf = fence.local_leaf, binding = fence.binding, expiresAt = expiry)
        val time = now() / 1000
        val content = json.encodeToString(binding)
        val deviceId = eventId(local.publicKey, time, 1221, helloTags, content)
        val deviceEvent = BindingEvent(deviceId, local.publicKey, time, 1221, helloTags, content,
            deviceSign(deviceId.unhex())?.hex() ?: error("device_signer_refused"))
        check(validEvent(deviceEvent))
        val signed = accountSign(time, 1221, helloTags, json.encodeToString(deviceEvent)) ?: error("account_signer_refused")
        val accountEvent = BindingEvent(signed.id, signed.pubKey, signed.createdAt, signed.kind, signed.tags, signed.content, signed.sig)
        check(accountEvent.pubkey == account && validEvent(accountEvent))
        check(fence(peer) == fence && device()?.publicKey == local.publicKey)
        val wire = json.encodeToString(accountEvent)
        publish(send(fence, 1221, helloTags, wire, expiry), fence)
    }

    private fun peerDevice(fence: NativeFence): String? = inbox(1221)
        .filter { !it.invalidated && it.fence == fence && it.kind == 1221 && it.tags == helloTags }
        .mapNotNull { row -> runCatching {
            val accountEvent = json.decodeFromString<BindingEvent>(row.content)
            if (!validEvent(accountEvent) || accountEvent.pubkey != fence.peer_account) return@runCatching null
            val deviceEvent = json.decodeFromString<BindingEvent>(accountEvent.content)
            if (!validEvent(deviceEvent)) return@runCatching null
            val proof = json.decodeFromString<LeafDeviceBinding>(deviceEvent.content)
            if (proof.version != 1 || proof.purpose != "cc.marmot.device.v1" || proof.account != fence.peer_account ||
                proof.device != deviceEvent.pubkey || proof.leaf != fence.peer_leaf || proof.binding != fence.binding ||
                proof.expiresAt <= now() || proof.expiresAt > accountEvent.created_at * 1000 + 86_461_000 ||
                deviceEvent.created_at != accountEvent.created_at) null else proof.device
        }.getOrNull() }.distinct().toList().singleOrNull()

    private fun cachedDevice(cached: JsonArray): String? = runCatching {
        val accountEvent = json.decodeFromString<BindingEvent>(cached[1].jsonPrimitive.content)
        val deviceEvent = json.decodeFromString<BindingEvent>(accountEvent.content)
        deviceEvent.pubkey
    }.getOrNull()

    private fun session(fence: NativeFence): MarmotSnapshotSession? {
        val own = device() ?: return null
        val cached = command("binding") { put("binding", fence.binding) } as? JsonArray ?: return null
        if (cachedDevice(cached) != own.publicKey) {
            resetPeer(fence.peer_account)
            return null
        }
        val peerDevice = peerDevice(fence) ?: return null
        val local = MarmotSnapshotLeaf(account, own.publicKey)
        val peer = MarmotSnapshotLeaf(fence.peer_account, peerDevice)
        return MarmotSnapshotSession(local, peer, fence.binding, setOf(local, peer))
    }
    override fun <T> withSession(peer: String, block: (MarmotSnapshotSession) -> T): T? = synchronized(lock) {
        val current = runCatching { fence(peer) }.getOrNull() ?: return@synchronized null
        val session = runCatching { session(current) }.getOrNull() ?: return@synchronized null
        block(session)
    }
    private fun send(fence: NativeFence, kind: Int, tags: List<List<String>>, content: String, expires: Long): String {
        return command("handoff") {
            put("fence", json.encodeToJsonElement(fence)); put("kind", kind); put("tags", json.encodeToJsonElement(tags))
            put("content", content); put("expires_at", expires)
        }.jsonPrimitive.content
    }
    private fun publish(event: String, fence: NativeFence) {
        command("publish") { put("event_id", event); put("fence", json.encodeToJsonElement(fence)) }
    }
    override fun handoff(session: MarmotSnapshotSession, kind: Int, tags: List<List<String>>, content: String) = synchronized(lock) {
        val current = fence(session.peer.account)
        check(session(current) == session) { "native_stale_session" }
        val expiry = if (kind == 1220) json.parseToJsonElement(content).jsonObject["offer"]!!.jsonObject["expiresAt"]!!.jsonPrimitive.long
            else if (kind == 1222) json.parseToJsonElement(content).jsonObject["expiresAt"]!!.jsonPrimitive.long
            else now() + 24 * 60 * 60 * 1000
        send(current, kind, tags, content, expiry)
        Unit
    }
    override fun flush(kind: Int, tags: List<List<String>>, authorize: (MarmotSnapshotSession, String, () -> Unit) -> Unit) = synchronized(lock) {
        for (row in pending(kind)) {
            if (row.kind != kind || row.tags != tags || row.expires_at <= now()) continue
            val fence = runCatching { fence(row.fence.peer_account) }.getOrNull() ?: continue
            if (fence != row.fence) continue
            val session = session(fence) ?: continue
            authorize(session, row.content) { publish(row.event_id, fence) }
        }
    }
    override fun drain(kind: Int, tags: List<List<String>>, consume: (MarmotSnapshotSession, String) -> Unit) = synchronized(lock) {
        for (row in inbox(kind)) {
            if (row.invalidated || row.acknowledged || row.kind != kind || row.tags != tags) continue
            val fence = runCatching { fence(row.fence.peer_account) }.getOrNull() ?: continue
            if (fence != row.fence) continue
            val session = session(fence) ?: continue
            consume(session, row.content) // app DB transaction has committed when this returns
            command("ack") { put("source", row.source); put("fence", json.encodeToJsonElement(fence)) }
        }
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
private fun String.unhex(): ByteArray {
    require(length % 2 == 0 && length <= 128)
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
