package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable enum class SnapshotEndpointRole { USER, SERVER }
@Serializable enum class SnapshotMessageKind { OFFER, CONSENT, CONTENT, RECEIPT, REVOKE, DECLINE }
enum class SnapshotState { OFFERED, CONSENTED, ACCEPTED, RECEIVED, DELIVERED, REVOKED, DECLINED, INVALIDATED, EXPIRED }

/** All fields are authenticated by the enclosing Marmot app event. No local row IDs leave the app. */
@Serializable
internal data class SnapshotOffer(
    val id: String,
    val owner: String,
    val ownerRole: SnapshotEndpointRole,
    val recipient: String,
    val recipientRole: SnapshotEndpointRole,
    val ownerDevice: String,
    val recipientDevice: String,
    val binding: String,
    val category: SharingCategory,
    val resourceEpoch: Long,
    val digest: String,
    val expiresAt: Long,
)

@Serializable
internal data class SnapshotMessage(
    val version: Int = 1,
    val purpose: String = "cc.sharing.snapshot.v1",
    val action: SnapshotMessageKind,
    val offer: SnapshotOffer,
    val content: String? = null,
) {
    override fun toString(): String = "SnapshotMessage(action=$action, payload=redacted)"
}

data class SharingSnapshotView(
    val id: String,
    val peer: String,
    val role: SnapshotEndpointRole,
    val category: SharingCategory,
    val incoming: Boolean,
    val state: SnapshotState,
    val expiresAt: Long,
    /** A handoff may have occurred; this is not a delivery claim. */
    val attempted: Boolean,
)

/**
 * An immutable text-snapshot exchange atop the existing owner policy and vault.
 * Membership gives no grant: sender access is rechecked at handoff; recipient
 * approval binds the whole offer, including the exact content digest and leaf.
 * The injected port is the authentication/MLS boundary, never a JSON-supplied
 * assertion of identity. Production uses LiveMarmotSnapshotPort with the pinned native engine.
 */
class SharingSnapshotExchange(
    private val database: SecureDatabase,
    private val repository: SecureDbSharingRepository,
    private val account: String,
    private val role: SnapshotEndpointRole,
    private val port: MarmotSnapshotPort,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    // Every exchange over the app's shared database uses one coordinator.
    // Otherwise one instance's clock write can collide with another instance's
    // authorization transaction after its durable handoff reservation.
    private val coordinator = synchronized(coordinators) { coordinators.getOrPut(database) { Any() } }
    private inline fun <T> coordinated(block: () -> T): T = synchronized(coordinator, block)
    private val q get() = database.snapshotQueries
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    val nativeAvailable: Boolean get() = port.nativeAvailable

    fun pinnedRole(peer: String): SnapshotEndpointRole? = coordinated { if (ready()) peerRole(peer) else null }

    /** Explicit local identity/role pin, not a grant. Changing a pin closes old offers. */
    fun pinPeer(peer: String, peerRole: SnapshotEndpointRole): Boolean = coordinated {
        database.transactionWithResult {
            if (!ready() || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) || !hex(peer) || peer == account || repository.administrativeWritesLocked()) return@transactionWithResult false
            if (q.selectSnapshots(account).executeAsList().size >= MAX_RECORDS) return@transactionWithResult false
            val previous = q.peerRole(account, peer).executeAsOneOrNull()
            if (previous != null && previous != peerRole.name) {
                rows().filter { counterpart(it.second) == peer }.forEach { setState(it.second.id, SnapshotState.INVALIDATED) }
            }
            q.pinPeer(account, peer, peerRole.name)
            true
        }
    }

    /** Prepares an offer from a real sealed local row after existing category/device consent. */
    fun offer(peer: String, localItemId: String, epoch: Long, expiresAt: Long): String? = coordinated {
        collectExpired()
        if (!ready() || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) || !validLifetime(expiresAt) || repository.administrativeWritesLocked()) return@coordinated null
        if (q.selectSnapshots(account).executeAsList().size >= MAX_RECORDS) return@coordinated null
        val peerRole = peerRole(peer) ?: return@coordinated null
        return@coordinated port.withSession(peer) { session ->
            if (!sessionValid(session) || session.peer.account != peer) return@withSession null
            repository.withPeerSnapshot(PeerId(peer), DeviceId(session.peer.device), localItemId, epoch, expiresAt) { category, bytes ->
                if (!ready() || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) ||
                    !validLifetime(expiresAt) || peerRole(peer) != peerRole ||
                    q.selectSnapshots(account).executeAsList().size >= MAX_RECORDS ||
                    bytes.size > MAX_CONTENT_BYTES || runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.isFailure) {
                    return@withPeerSnapshot null
                }
                val id = ByteArray(32).also(random::nextBytes).hex()
                val offer = SnapshotOffer(id, account, role, peer, peerRole, session.local.device,
                    session.peer.device, session.binding, category, epoch, digest(bytes), expiresAt)
                q.insertSnapshot(account, id, json.encodeToString(offer), localItemId, SnapshotState.OFFERED.name)
                id
            }
        }
    }

    /** Consent is local and explicit, and never inferred from opening or listing an offer. */
    fun accept(id: String): Boolean = coordinated {
        if (!ready() || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) || repository.administrativeWritesLocked()) return@coordinated false
        val first = q.selectSnapshot(account, id).executeAsOneOrNull() ?: return@coordinated false
        val offer = decodeOffer(first.offer_json) ?: return@coordinated false
        return@coordinated withOfferSession(offer) {
            database.transactionWithResult {
                val row = q.selectSnapshot(account, id).executeAsOneOrNull() ?: return@transactionWithResult false
                if (offer.recipient != account || row.state != SnapshotState.OFFERED.name ||
                    !ready() || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) ||
                    repository.administrativeWritesLocked() || !offerValid(offer, it)) return@transactionWithResult false
                setState(id, SnapshotState.CONSENTED)
                true
            }
        } ?: false
    }

    /** Stops local access immediately. Tombstones survive reopen and all duplicate messages. */
    fun revoke(id: String): Boolean = coordinated {
        database.transactionWithResult {
            if (!ready()) return@transactionWithResult false
            val row = q.selectSnapshot(account, id).executeAsOneOrNull() ?: return@transactionWithResult false
            val offer = decodeOffer(row.offer_json) ?: return@transactionWithResult false
            setState(id, if (offer.owner == account) SnapshotState.REVOKED else SnapshotState.DECLINED)
            true
        }
    }

    /**
     * Explicit synchronization; no background worker is installed. Every action
     * is reserved durably before handoff and attempted at most once. Unclear
     * delivery remains unclear across restart instead of minting a new MLS event.
     */
    fun synchronize() = coordinated {
        collectExpired()
        if (!ready()) return@coordinated
        port.refresh()
        port.drain(KIND, TAGS) { session, content -> receive(session, content) }
        // Narrowing the signed policy or removing a device also withdraws
        // existing snapshots, including ones already acknowledged by the peer.
        rows().forEach { (rawState, offer) ->
            if (offer.owner != account || terminal(state(rawState))) return@forEach
            withOfferSession(offer) {
                val row = q.selectSnapshot(account, offer.id).executeAsOneOrNull() ?: return@withOfferSession
                val allowed = row.local_item?.let { item ->
                    repository.withPeerSnapshot(PeerId(offer.recipient), DeviceId(offer.recipientDevice), item,
                        offer.resourceEpoch, offer.expiresAt) { category, bytes -> category == offer.category && digest(bytes) == offer.digest }
                } == true
                if (!allowed || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS)) setState(offer.id, SnapshotState.REVOKED)
            }
        }
        rows().forEach { (_, offer) ->
            val row = q.selectSnapshot(account, offer.id).executeAsOneOrNull() ?: return@forEach
            val state = state(row.state)
            val action = when {
                state == SnapshotState.REVOKED && offer.owner == account -> SnapshotMessageKind.REVOKE
                state == SnapshotState.DECLINED && offer.recipient == account -> SnapshotMessageKind.DECLINE
                state == SnapshotState.OFFERED && offer.owner == account -> SnapshotMessageKind.OFFER
                state == SnapshotState.CONSENTED && offer.recipient == account -> SnapshotMessageKind.CONSENT
                state == SnapshotState.ACCEPTED && offer.owner == account -> SnapshotMessageKind.CONTENT
                state == SnapshotState.RECEIVED && offer.recipient == account -> SnapshotMessageKind.RECEIPT
                else -> return@forEach
            }
            if (row.attempted and bit(action) != 0L && !port.durableIdempotentHandoff) return@forEach
            withOfferSession(offer) { session ->
                // A separate committed write precedes any external effect.
                val reserved = database.transactionWithResult {
                    val current = q.selectSnapshot(account, offer.id).executeAsOneOrNull()
                    if (current == null || current.state != row.state ||
                        current.attempted and bit(action) != 0L && !port.durableIdempotentHandoff) false
                    else { q.markAttempted(bit(action), account, offer.id); true }
                }
                if (!reserved) return@withOfferSession
                try {
                    if (action == SnapshotMessageKind.CONTENT || action == SnapshotMessageKind.OFFER) {
                        val localItem = row.local_item ?: return@withOfferSession
                        val sent = repository.withPeerSnapshot(PeerId(offer.recipient), DeviceId(offer.recipientDevice),
                            localItem, offer.resourceEpoch, offer.expiresAt) { category, bytes ->
                            if (!ready() || !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS) ||
                                !offerValid(offer, session) || q.selectSnapshot(account, offer.id).executeAsOneOrNull()?.state != row.state ||
                                category != offer.category || digest(bytes) != offer.digest || bytes.size > MAX_CONTENT_BYTES) false
                            else {
                                val text = if (action == SnapshotMessageKind.CONTENT) bytes.decodeToString(throwOnInvalidSequence = true) else null
                                port.handoff(session, KIND, TAGS, json.encodeToString(SnapshotMessage(action = action, offer = offer, content = text)))
                                true
                            }
                        }
                        if (sent != true) database.transaction {
                            val current = q.selectSnapshot(account, offer.id).executeAsOneOrNull()
                            if (current != null && !terminal(state(current.state))) setState(offer.id, SnapshotState.INVALIDATED)
                        }
                    } else {
                        database.transaction {
                            val current = q.selectSnapshot(account, offer.id).executeAsOneOrNull()
                            if (ready() && offerValid(offer, session) && current?.state == row.state) {
                                port.handoff(session, KIND, TAGS, json.encodeToString(SnapshotMessage(action = action, offer = offer)))
                            }
                        }
                    }
                } catch (_: Exception) {
                    // No payload, peer identity or provider exception in logs.
                    // attempted remains durable; only a new explicit offer may retry.
                }
            }
        }
        flushPending()
    }

    /** Revalidates the actual bytes and signed policy even for a queued send.
     * The native adapter invokes publish inside this DB transaction. */
    private fun flushPending() {
        port.flush(KIND, TAGS) { session, wire, publish ->
            val message = runCatching { json.decodeFromString<SnapshotMessage>(wire) }.getOrNull() ?: return@flush
            val offer = message.offer
            if (!offerValid(offer, session) || !ready()) return@flush
            database.transaction {
                val row = q.selectSnapshot(account, offer.id).executeAsOneOrNull() ?: return@transaction
                if (decodeOffer(row.offer_json) != offer) return@transaction
                val current = state(row.state)
                when (message.action) {
                    SnapshotMessageKind.OFFER, SnapshotMessageKind.CONTENT -> {
                        if (offer.owner != account || terminal(current) ||
                            !repository.canUseSnapshots(DeviceCapability.MUTATE_PERMISSIONS)) return@transaction
                        val item = row.local_item ?: return@transaction
                        repository.withPeerSnapshot(PeerId(offer.recipient), DeviceId(offer.recipientDevice), item,
                            offer.resourceEpoch, offer.expiresAt) { category, bytes ->
                            if (category == offer.category && digest(bytes) == offer.digest &&
                                (message.action != SnapshotMessageKind.CONTENT ||
                                    current in setOf(SnapshotState.ACCEPTED, SnapshotState.DELIVERED) &&
                                    message.content == bytes.decodeToString(throwOnInvalidSequence = true))) publish()
                        }
                    }
                    SnapshotMessageKind.REVOKE -> if (offer.owner == account && current == SnapshotState.REVOKED) publish()
                    SnapshotMessageKind.DECLINE -> if (offer.recipient == account && current == SnapshotState.DECLINED) publish()
                    SnapshotMessageKind.CONSENT -> if (offer.recipient == account && current in setOf(SnapshotState.CONSENTED, SnapshotState.RECEIVED)) publish()
                    SnapshotMessageKind.RECEIPT -> if (offer.recipient == account && current == SnapshotState.RECEIVED) publish()
                }
            }
        }
    }

    /** Only explicitly consented, current, unexpired and authenticated content is readable. */
    fun read(id: String): String? = coordinated {
        if (!ready() || repository.administrativeWritesLocked()) return@coordinated null
        val first = q.selectSnapshot(account, id).executeAsOneOrNull() ?: return@coordinated null
        val offer = decodeOffer(first.offer_json) ?: return@coordinated null
        return@coordinated withOfferSession(offer) {
            database.transactionWithResult {
                val row = q.selectSnapshot(account, id).executeAsOneOrNull() ?: return@transactionWithResult null
                if (offer.recipient != account || row.state != SnapshotState.RECEIVED.name ||
                    repository.administrativeWritesLocked()) return@transactionWithResult null
                val item = row.local_item ?: return@transactionWithResult null
                val handle = SharingKeyHandles.recipientObject(PeerId(offer.owner), ObjectId(item))
                val key = repository.readWrappedKey(handle, offer.resourceEpoch) ?: return@transactionWithResult null
                val bytes = repository.readOwnerSealedItem(item, key) ?: return@transactionWithResult null
                try {
                    if (!ready() || !validLifetime(offer.expiresAt) || digest(bytes) != offer.digest) null else bytes.decodeToString(throwOnInvalidSequence = true)
                } finally { bytes.fill(0) }
            }
        }
    }

    fun views(): List<SharingSnapshotView> = coordinated {
        if (!ready()) return@coordinated emptyList()
        return@coordinated rows().map { (rawState, offer) ->
            val stored = state(rawState)
            val state = if (terminal(stored)) stored else if (!validLifetime(offer.expiresAt)) SnapshotState.EXPIRED else stored
            val row = q.selectSnapshot(account, offer.id).executeAsOne()
            SharingSnapshotView(offer.id, counterpart(offer),
                if (offer.owner == account) offer.recipientRole else offer.ownerRole,
                offer.category, offer.recipient == account, state, offer.expiresAt, row.attempted != 0L)
        }
    }

    private fun receive(session: MarmotSnapshotSession, wire: String) {
        if (!ready() || !sessionValid(session) || wire.length > MAX_WIRE_CHARS) return
        val message = runCatching { json.decodeFromString<SnapshotMessage>(wire) }.getOrNull() ?: return
        if (message.version != 1 || message.purpose != "cc.sharing.snapshot.v1" || json.encodeToString(message) != wire) return
        val offer = message.offer
        if (!offerValid(offer, session)) return
        if (message.action != SnapshotMessageKind.CONTENT && message.content != null) return
        database.transaction {
            val row = q.selectSnapshot(account, offer.id).executeAsOneOrNull()
            if (row != null && row.offer_json != json.encodeToString(offer)) return@transaction
            val fromOwner = session.peer.account == offer.owner && account == offer.recipient
            val fromRecipient = session.peer.account == offer.recipient && account == offer.owner
            // Revocation can overtake the offer or content. It still establishes a tombstone.
            if ((message.action == SnapshotMessageKind.REVOKE && fromOwner) ||
                (message.action == SnapshotMessageKind.DECLINE && fromRecipient)) {
                if (row == null) {
                    if (q.selectSnapshots(account).executeAsList().size >= MAX_RECORDS) return@transaction
                    q.insertSnapshot(account, offer.id, json.encodeToString(offer), null, SnapshotState.REVOKED.name)
                } else setState(offer.id, SnapshotState.REVOKED)
                return@transaction
            }
            if (row != null && terminal(state(row.state))) return@transaction
            if (repository.administrativeWritesLocked()) return@transaction
            when (message.action) {
                SnapshotMessageKind.OFFER -> if (fromOwner && row == null && q.selectSnapshots(account).executeAsList().size < MAX_RECORDS) {
                    q.insertSnapshot(account, offer.id, json.encodeToString(offer), null, SnapshotState.OFFERED.name)
                }
                SnapshotMessageKind.CONSENT -> if (fromRecipient && row?.state == SnapshotState.OFFERED.name) {
                    setState(offer.id, SnapshotState.ACCEPTED)
                }
                SnapshotMessageKind.CONTENT -> if (fromOwner && row?.state == SnapshotState.CONSENTED.name) {
                    val bytes = message.content?.encodeToByteArray() ?: return@transaction
                    try {
                        if (bytes.size > MAX_CONTENT_BYTES || digest(bytes) != offer.digest) return@transaction
                        val item = "snapshot:${offer.id}"
                        val handle = SharingKeyHandles.recipientObject(PeerId(offer.owner), ObjectId(item))
                        val key = repository.readWrappedKey(handle, offer.resourceEpoch)
                            ?: repository.createDataKey(handle, offer.resourceEpoch)
                        repository.storeSealedItem(item, offer.category, handle, key, bytes, offer.resourceEpoch)
                        q.setLocalItem(item, account, offer.id)
                        setState(offer.id, SnapshotState.RECEIVED)
                    } finally { bytes.fill(0) }
                }
                SnapshotMessageKind.RECEIPT -> if (fromRecipient && row?.state == SnapshotState.ACCEPTED.name &&
                    row.attempted and bit(SnapshotMessageKind.CONTENT) != 0L) setState(offer.id, SnapshotState.DELIVERED)
                else -> Unit
            }
        }
    }

    private fun <T> withOfferSession(offer: SnapshotOffer, block: (MarmotSnapshotSession) -> T): T? {
        if (!ready() || !validLifetime(offer.expiresAt)) return null
        return port.withSession(counterpart(offer)) { session ->
            if (!sessionValid(session) || !offerValid(offer, session)) {
                setState(offer.id, SnapshotState.INVALIDATED)
                null
            } else block(session)
        }
    }

    private fun offerValid(o: SnapshotOffer, s: MarmotSnapshotSession): Boolean {
        if (!hex(o.id) || !hex(o.digest) || o.resourceEpoch <= 0 || !validLifetime(o.expiresAt)) return false
        if (o.owner == o.recipient || setOf(o.owner, o.recipient) != setOf(account, s.peer.account)) return false
        val localOwner = o.owner == account
        if ((if (localOwner) o.ownerRole else o.recipientRole) != role) return false
        if ((if (localOwner) o.recipientRole else o.ownerRole) != peerRole(s.peer.account)) return false
        val localDevice = if (localOwner) o.ownerDevice else o.recipientDevice
        val remoteDevice = if (localOwner) o.recipientDevice else o.ownerDevice
        return localDevice == s.local.device && remoteDevice == s.peer.device && o.binding == s.binding
    }

    private fun sessionValid(s: MarmotSnapshotSession): Boolean =
        s.local.account == account && s.peer.account != account && hex(s.local.account) && hex(s.peer.account) &&
            hex(s.local.device) && hex(s.peer.device) && s.binding.length in 1..512 &&
            s.members == setOf(s.local, s.peer)

    /** Tombstones remain until the signed offer expires. After that the wire
     * lifetime check rejects every replay, and bounded storage is reusable. */
    fun collectExpired(): Int = coordinated {
        if (!ready()) return@coordinated 0
        database.transactionWithResult {
            var removed = 0
            for (row in q.selectSnapshots(account).executeAsList()) {
                val offer = decodeOffer(row.offer_json) ?: continue
                if (offer.expiresAt > nowEpochMillis()) continue
                row.local_item?.let { repository.discardExpiredSnapshotItem(it, offer.owner, offer.id) }
                q.deleteExpiredSnapshot(account, offer.id); removed++
            }
            removed
        }
    }
    fun atCapacity(): Boolean = coordinated { q.selectSnapshots(account).executeAsList().size >= MAX_RECORDS }

    private fun ready(): Boolean {
        if (!repository.canUseSnapshots(DeviceCapability.READ) || account != repository.accountIdentity || !hex(account)) return false
        // One atomic write avoids a deferred read transaction racing another
        // writer. Do not introduce a JVM lock here: this also runs within DB
        // transactions, so an outside writer could invert the lock order.
        val now = nowEpochMillis()
        q.observeClock(account, now.coerceAtLeast(0), if (now < 0) 1L else 0L)
        return q.selectClock(account).executeAsOne().locked == 0L
    }

    private fun validLifetime(until: Long): Boolean {
        val now = nowEpochMillis()
        return now >= 0 && until > now && until - now <= MAX_LIFETIME_MILLIS
    }
    private fun peerRole(peer: String): SnapshotEndpointRole? =
        q.peerRole(account, peer).executeAsOneOrNull()?.let { name -> SnapshotEndpointRole.entries.firstOrNull { it.name == name } }
    private fun decodeOffer(wire: String): SnapshotOffer? =
        if (wire.length > MAX_WIRE_CHARS) null else runCatching {
            json.decodeFromString<SnapshotOffer>(wire).takeIf { json.encodeToString(it) == wire }
        }.getOrNull()
    private fun rows() = q.selectSnapshots(account).executeAsList().mapNotNull { row ->
        decodeOffer(row.offer_json)?.let { row.state to it }
    }
    private fun setState(id: String, value: SnapshotState) = q.setSnapshotState(value.name, account, id)
    private fun counterpart(o: SnapshotOffer) = if (o.owner == account) o.recipient else o.owner
    private fun state(raw: String) = SnapshotState.entries.firstOrNull { it.name == raw } ?: SnapshotState.INVALIDATED
    private fun terminal(state: SnapshotState) = state in setOf(SnapshotState.REVOKED, SnapshotState.DECLINED, SnapshotState.INVALIDATED, SnapshotState.EXPIRED)
    private fun bit(action: SnapshotMessageKind) = 1L shl action.ordinal
    private fun hex(value: String) = value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    companion object {
        private val coordinators = java.util.WeakHashMap<SecureDatabase, Any>()
        const val KIND = 1220
        val TAGS = listOf(listOf("l", "cc.sharing.snapshot.v1", "cruxcoach.private"))
        const val MAX_CONTENT_BYTES = 32_768
        const val MAX_WIRE_CHARS = 220_000 // worst-case JSON escapes for bounded text
        const val MAX_LIFETIME_MILLIS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_RECORDS = 256
    }
}
