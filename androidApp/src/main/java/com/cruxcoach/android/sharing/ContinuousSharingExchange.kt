package com.cruxcoach.android.sharing

import com.cruxcoach.db.secure.SecureDatabase
import com.cruxcoach.domain.sharing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.channels.awaitClose

/** Durable owner-authoritative replicas. The port authenticates the real MLS
 * source; this class decides consent and the exact fields at each data door. */
class ContinuousSharingExchange(
    private val database: SecureDatabase,
    private val repository: SecureDbSharingRepository,
    private val account: String,
    private val role: SnapshotEndpointRole,
    private val port: MarmotSnapshotPort,
    val source: ContinuousSourceAdapter,
    private val peerRole: (String) -> SnapshotEndpointRole?,
    private val now: () -> Long = System::currentTimeMillis,
    private val friendshipCrypto: AsyncLedgerCrypto? = null,
) {
    private val lock = SharingSessionCoordination.forDatabase(database)
    private val q get() = database.continuousSharingQueries
    private val json = ContinuousCodec.json
    // Cache only the cryptographic result for immutable signed bytes, never a
    // permission/session decision. Every data door still reads current authority.
    private val verifiedSignatures = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 256
    }
    private fun ready(write: Boolean = false) = repository.accountIdentity == account &&
        repository.canUseSnapshots(if (write) DeviceCapability.MUTATE_PERMISSIONS else DeviceCapability.READ) &&
        !repository.administrativeWritesLocked()
    private fun states(): List<ContinuousState> = q.grants(account).executeAsList().mapNotNull { row -> decode(row.body)?.takeIf {
        it.offer.id == row.grant_id && it.offer.owner == row.owner && it.offer.expiresAt == row.expires_at && peer(it.offer) == row.peer
    } }
    private fun decode(raw: String): ContinuousState? = if (raw.length > 4 * ContinuousCodec.MAX_BYTES) null else runCatching {
        json.decodeFromString<ContinuousState>(raw).takeIf { verified(it.offer) && verified(it.initialOffer) &&
            it.offer.selection >= it.initialOffer.selection &&
            it.offer.copy(scope = it.initialOffer.scope, selection = it.initialOffer.selection, signature = it.initialOffer.signature) == it.initialOffer &&
            it.offer.scope.categories.containsAll(it.categories) &&
            it.status in setOf("OFFERED", "INVITED", "ACCEPTED", "ACTIVE", "PAUSED", "ENDED", "DECLINED", "CONFLICT") }
    }.getOrNull()
    private fun load(id: String) = q.grantById(account, id).executeAsOneOrNull()?.body?.let(::decode)
    private fun save(state: ContinuousState) {
        val raw = json.encodeToString(state)
        require(raw.encodeToByteArray().size <= 4 * ContinuousCodec.MAX_BYTES)
        val o = state.offer
        q.putGrant(account, o.id, peer(o), o.owner, o.expiresAt, raw)
    }
    private fun peer(o: ContinuousOffer) = if (o.owner == account) o.recipient else o.owner
    private fun live(state: ContinuousState) = state.status in setOf("OFFERED", "INVITED", "ACCEPTED", "ACTIVE") && state.offer.expiresAt > now()
    private fun sessionMatches(o: ContinuousOffer, s: MarmotSnapshotSession, requireRole: Boolean = true): Boolean =
        s.local.account == account && s.peer.account == peer(o) && s.binding == o.binding &&
            s.members == setOf(s.local, s.peer) &&
            if (o.owner == account) s.local.device == o.ownerDevice && s.peer.device == o.recipientDevice && o.ownerRole == role && (!requireRole || peerRole(peer(o)) == o.recipientRole)
            else o.recipient == account && s.local.device == o.recipientDevice && s.peer.device == o.ownerDevice && o.recipientRole == role && (!requireRole || peerRole(peer(o)) == o.ownerRole)
    private fun currentSession(state: ContinuousState): Boolean = port.withSession(peer(state.offer)) { sessionMatches(state.offer, it) } == true
    private fun ownerAuthority(o: ContinuousOffer): Boolean {
        if (o.owner != account || !ready(true) || source.version().first != o.sourceGeneration ||
            repository.loadDeviceAuthority().authorityGeneration != o.authorityGeneration || !verified(o)) return false
        val relationship = repository.loadProjection().relationships[PeerId(o.recipient)] ?: return false
        return !relationship.status.isTerminal && relationship.failClosedReason == null && !relationship.awaitingRevokeSync &&
            (relationship.expiresAt?.let { now() < it } != false) && DeviceId(o.recipientDevice) !in relationship.revokedDevices
    }
    // Friendship reception authority replaces only the new path's category
    // acceptance. Legacy EffectiveAccessResolver and snapshot rules stay intact.
    private fun allowed(state: ContinuousState, category: SharingCategory, id: String? = null): Boolean {
        val policy = repository.loadPolicy()
        val peer = PeerId(state.offer.recipient)
        if (SharingPolicyResolver.resolve(policy, peer, category, id?.let(::ObjectId)).isAllowed) return true
        // A retained personal object exception may make one record eligible
        // even when the circle denies its category. The record-level resolver
        // below still excludes every other record; the signed selection is the
        // outer ceiling and can never be widened by an object exception.
        return id == null && policy.peers[peer]?.objectRules?.any { (key, effect) ->
            key.category == category && effect == AccessEffect.ALLOW
        } == true
    }
    private fun selected(state: ContinuousState): List<ContinuousRecord> {
        val policy = repository.loadPolicy()
        return source.read(state.offer.scope.copy(categories = state.categories)).filter {
            SharingPolicyResolver.resolve(policy, PeerId(state.offer.recipient), it.category, ObjectId(it.id)).isAllowed
        }
    }
    private fun offerHash(o: ContinuousOffer) = ContinuousCodec.hash(json.encodeToString(o))
    private fun verified(o: ContinuousOffer): Boolean = ContinuousCodec.valid(o) && verify(json.encodeToString(o.copy(signature = "")), o.signature, o.owner)
    private fun verify(raw: String, signature: String, signer: String): Boolean = runCatching {
        val bytes = signature.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val key = signer + signature + ContinuousCodec.hash(raw)
        verifiedSignatures.getOrPut(key) {
            bytes.size == 64 && friendshipCrypto?.verify(bytes, friendshipCrypto.hash(raw.encodeToByteArray()), signer) == true
        }
    }.getOrDefault(false)
    private fun sign(raw: String): String? = friendshipCrypto?.let { crypto ->
        kotlinx.coroutines.runBlocking { crypto.signCanonical(crypto.hash(raw.encodeToByteArray())) }?.joinToString("") { "%02x".format(it) }
    }
    private fun sign(o: ContinuousOffer): ContinuousOffer? = sign(json.encodeToString(o.copy(signature = "")))?.let { o.copy(signature = it) }?.takeIf(::verified)
    private fun family(s: ContinuousState) = states().filter { it.offer.friendship == s.offer.friendship && peer(it.offer) == peer(s.offer) }
    private fun paired(s: ContinuousState): Boolean {
        val members = family(s).filter(::live)
        val request = members.singleOrNull { it.initialOffer.requestHash.isEmpty() }?.initialOffer ?: return false
        val response = members.singleOrNull { it.initialOffer.requestHash.isNotEmpty() }?.initialOffer ?: return false
        return verified(request) && verified(response) && request.id == request.friendship &&
            response.requestHash == offerHash(request) && response.owner == request.recipient && response.recipient == request.owner &&
            response.ownerDevice == request.recipientDevice && response.recipientDevice == request.ownerDevice && response.binding == request.binding &&
            response.ownerRole == request.recipientRole && response.recipientRole == request.ownerRole
    }
    private fun signedEnd(s: ContinuousState): ContinuousEnd? {
        val request = family(s).firstOrNull { it.initialOffer.requestHash.isEmpty() }?.initialOffer ?: s.initialOffer
        val e = ContinuousEnd(friendship = s.offer.friendship, requester = request.owner, acceptor = request.recipient, signer = account, createdAt = now())
        return sign(json.encodeToString(e))?.let { e.copy(signature = it) }
    }
    private fun validEnd(e: ContinuousEnd, s: ContinuousState): Boolean = e.purpose == "cc.friendship.end.v1" &&
        e.friendship == s.offer.friendship && setOf(e.requester, e.acceptor) == setOf(account, peer(s.offer)) &&
        e.signer in setOf(account, peer(s.offer)) && e.createdAt > 0 && e.createdAt <= now() + 60_000 &&
        verify(json.encodeToString(e.copy(signature = "")), e.signature, e.signer)
    private fun endFamily(s: ContinuousState, e: ContinuousEnd?, action: String = "END") {
        family(s).forEach { member -> save(terminate(member).copy(control =
            if (member.offer.id == s.offer.id && e != null) control(member, action).copy(end = e) else null,
            error = if (e == null) "NEEDS_OPEN" else null)) }
    }
    private fun control(state: ContinuousState, action: String, sequence: Long = 0, digest: String = "") =
        ContinuousWire(action = action, offer = state.offer, expiresAt = minOf(state.offer.expiresAt, now() + ContinuousCodec.DAY), sequence = sequence, digest = digest)
    private fun terminate(state: ContinuousState, status: String = "ENDED", action: String = "END"): ContinuousState = state.copy(
        status = status, records = emptyList(), sending = null, receiving = emptyMap(), sentPages = 0,
        control = control(state, action), error = null,
    )

    private fun requests(): List<ContinuousRequest> = q.requests(account).executeAsList().mapNotNull { row -> runCatching {
        json.decodeFromString<ContinuousRequest>(row.body).takeIf { r -> r.account == account && r.peer == row.peer &&
            r.purpose == "cc.friendship.request-intent.v1" && ContinuousCodec.valid(r.scope) && r.createdAt <= now() + 60_000 &&
            r.createdAt + 7 * ContinuousCodec.DAY > now() && r.source == source.version().first &&
            r.authority == repository.loadDeviceAuthority().authorityGeneration && r.role == peerRole(r.peer) &&
            verify(json.encodeToString(r.copy(signature = "")), r.signature, account) }
    }.getOrNull() }
    fun pendingRequests(): List<PendingFriendshipView> = synchronized(lock) { if (!ready()) emptyList() else requests().map { PendingFriendshipView(it.id,it.peer,it.scope) } }
    fun cancelRequest(peer: String) = synchronized(lock) { q.deleteRequest(account, peer) }
    private fun queueRequest(peer: String, scope: ContinuousScope, remoteRole: SnapshotEndpointRole): String? {
        if (q.requests(account).executeAsList().size >= ContinuousCodec.MAX_ACTIVE) return null
        val r = ContinuousRequest(id = UUID.randomUUID().toString(), account = account, peer = peer, role = remoteRole,
            scope = scope, source = source.version().first, authority = repository.loadDeviceAuthority().authorityGeneration, createdAt = now())
        val signature = sign(json.encodeToString(r)) ?: return null
        if (!verify(json.encodeToString(r), signature, account) || !ready(true)) return null
        q.putRequest(account, peer, json.encodeToString(r.copy(signature = signature))); return r.id
    }
    /** The controller's local policy/registry and signed selection are one
     * SQLite commit. No native handoff/publication occurs inside this edit.
     * A late signer refusal or process death cannot leave an earlier ALLOW live.
     * The callback runs on this transaction's thread under the session lock. */
    internal fun selectionTransaction(block: () -> SharingWriteResult): SharingWriteResult = synchronized(lock) {
        database.transactionWithResult {
            val result = block()
            if (!result.isSuccess) rollback(result)
            result
        }
    }
    /** A request is new explicit friendship; editing an active direction only
     * signs that owner's selection, never invents the peer's acceptance. */
    fun offer(peer: String, scope: ContinuousScope): String? = synchronized(lock) {
        if (!ready(true) || !ContinuousCodec.valid(scope)) return@synchronized null
        val remoteRole = peerRole(peer) ?: return@synchronized null
        source.read(scope)
        val existing = states().firstOrNull { it.offer.owner == account && peer(it.offer) == peer && live(it) && paired(it) }
        if (existing != null) {
            if (!ownerAuthority(existing.offer) || !currentSession(existing) || existing.offer.selection == Long.MAX_VALUE - 1) return@synchronized null
            val next = sign(existing.offer.copy(scope = scope, selection = existing.offer.selection + 1, signature = "")) ?: return@synchronized null
            database.transaction {
                val state = existing.copy(offer = next, categories = scope.categories, records = emptyList(), acknowledged = 0,
                    sending = null, receiving = emptyMap(), sentPages = 0, sourceRevision = -1)
                save(state.copy(control = control(state, "SELECT")))
            }
            return@synchronized next.id
        }
        if (states().any { peer(it.offer) == peer && live(it) } || q.grants(account).executeAsList().size >= ContinuousCodec.MAX_GRANTS ||
            states().count(::live) >= ContinuousCodec.MAX_ACTIVE * 2) return@synchronized null
        if (port.withSession(peer) { true } != true) return@synchronized queueRequest(peer, scope, remoteRole)
        port.withSession(peer) { session ->
            val unsigned = ContinuousOffer(id = requests().firstOrNull { it.peer == peer }?.id ?: UUID.randomUUID().toString(), owner = account, recipient = peer,
                ownerDevice = session.local.device, recipientDevice = session.peer.device, ownerRole = role, recipientRole = remoteRole,
                sourceGeneration = source.version().first, authorityGeneration = repository.loadDeviceAuthority().authorityGeneration,
                binding = session.binding, policyHead = repository.authorisedHead(PeerId(peer))?.id?.value ?: return@withSession null,
                scope = scope, createdAt = now(), expiresAt = Long.MAX_VALUE)
            val o = sign(unsigned) ?: return@withSession null
            if (!ownerAuthority(o)) return@withSession null
            database.transaction { val state = ContinuousState(o, "OFFERED"); save(state.copy(control = control(state, "OFFER"))); q.deleteRequest(account, peer) }
            o.id
        }
    }
    fun accept(id: String, ownScope: ContinuousScope = ContinuousScope(emptySet(), "1970-01-01")): Boolean = synchronized(lock) {
        val s = load(id) ?: return@synchronized false
        if (!ready(true) || !ContinuousCodec.valid(ownScope) || s.offer.recipient != account || s.status != "INVITED" ||
            !live(s) || !currentSession(s) || s.offer.createdAt + 7 * ContinuousCodec.DAY <= now() ||
            q.grants(account).executeAsList().size >= ContinuousCodec.MAX_GRANTS) return@synchronized false
        source.read(ownScope)
        val o = s.offer
        val own = sign(ContinuousOffer(id = UUID.randomUUID().toString(), owner = account, recipient = o.owner,
            ownerDevice = o.recipientDevice, recipientDevice = o.ownerDevice, ownerRole = role, recipientRole = o.ownerRole,
            sourceGeneration = source.version().first, authorityGeneration = repository.loadDeviceAuthority().authorityGeneration,
            binding = o.binding, policyHead = repository.authorisedHead(PeerId(o.owner))?.id?.value ?: return@synchronized false,
            scope = ownScope, createdAt = now(), expiresAt = Long.MAX_VALUE, friendship = o.friendship, requestHash = offerHash(o))) ?: return@synchronized false
        if (!ownerAuthority(own)) return@synchronized false
        database.transaction {
            save(s.copy(status = "ACCEPTED", control = control(s, "ACCEPT").copy(counterOffer = own)))
            save(ContinuousState(own, "ACCEPTED"))
        }
        true
    }
    fun end(id: String, pause: Boolean = false): Boolean = synchronized(lock) {
        val s = load(id) ?: return@synchronized false
        if (!ready() || !live(s)) return@synchronized false
        if (pause && s.offer.owner == account && paired(s)) return@synchronized offer(peer(s.offer), s.offer.scope.copy(categories = emptySet())) != null
        // Local deletion/stop commits BEFORE asking an external signer. A
        // refused signature cannot leave already received payloads readable.
        database.transaction { endFamily(s, null) }
        discardSuperseded()
        val e = signedEnd(s)
        if (e != null) database.transaction { endFamily(s, e) }
        true
    }
    fun endAll() = synchronized(lock) {
        database.transaction { states().filter(::live).groupBy { it.offer.friendship }.values.forEach { group ->
            val s = group.first(); endFamily(s, if (ready(true)) signedEnd(s) else null)
        } }
        q.clearRequests(account)
        // Malformed/older local formats also lose their payload, keeping a
        // closed ID. A clock/device gate may temporarily prohibit native reads.
        q.grants(account).executeAsList().filter { it.body.isNotEmpty() && decode(it.body) == null }.forEach {
            q.putGrant(account, it.grant_id, it.peer, it.owner, Long.MAX_VALUE, "")
        }
        runCatching { discardSuperseded() }
    }
    internal fun discardRetiredPayloads() = synchronized(lock) { discardSuperseded() }
    fun requestResync(id: String): Boolean = synchronized(lock) {
        val s = load(id) ?: return@synchronized false
        if (!ready() || !live(s) || s.offer.recipient != account || !paired(s)) return@synchronized false
        save(s.copy(receiving = emptyMap(), control = control(s, "RESYNC", s.acknowledged))); true
    }
    fun endPeer(peer: String) = synchronized(lock) { q.deleteRequest(account, peer); states().filter { peer(it.offer) == peer && live(it) }.firstOrNull()?.let { end(it.offer.id) } }
    fun hasFriendshipPeer(peer: String): Boolean = synchronized(lock) { q.grants(account).executeAsList().any { it.peer == peer } || q.requests(account).executeAsList().any { it.peer == peer } }
    private fun collect() {
        database.transaction {
            val validRequests = requests().map { it.peer }.toSet()
            q.requests(account).executeAsList().filter { it.peer !in validRequests }.forEach { q.deleteRequest(account,it.peer) }
            q.grants(account).executeAsList().filter { it.body.isNotEmpty() && decode(it.body) == null }.forEach {
                q.putGrant(account, it.grant_id, it.peer, it.owner, Long.MAX_VALUE, "")
            }
            for (before in states()) {
                val s = load(before.offer.id) ?: continue
                if (live(s) && runCatching { port.bindingRetired(s.offer.binding) }.getOrDefault(false)) {
                    endFamily(s, null); continue
                }
                if (live(s) && s.status != "INVITED" && port.withSession(peer(s.offer)) { !sessionMatches(s.offer, it) } == true) {
                    endFamily(s, null); continue
                }
                if (s.offer.recipient == account && s.records.isNotEmpty() && s.asOf + 7 * ContinuousCodec.DAY <= now())
                    save(s.copy(records = emptyList(), receiving = emptyMap(), error = "STALE", control = control(s, "RESYNC", s.acknowledged)))
                if (s.status in setOf("OFFERED", "INVITED") && s.offer.createdAt + 7 * ContinuousCodec.DAY <= now()) endFamily(s, null)
            }
        }
    }
    fun hasWork(): Boolean = synchronized(lock) {
        if (!ready()) return@synchronized false
        // Local expiry collection must also run when the last share ends; it
        // cannot depend on choosing to perform another network synchronization.
        collect()
        requests().isNotEmpty() || states().any { it.status in setOf("OFFERED", "ACCEPTED", "ACTIVE") || it.control != null || it.error == "NEEDS_OPEN" }
    }
    fun preview(scope: ContinuousScope): List<ContinuousRecord> = synchronized(lock) { if (ready()) source.read(scope) else emptyList() }

    fun changes(): Flow<Unit> = callbackFlow {
        val grants = q.grants(account)
        val source = q.sourceVersion()
        val requests = q.requests(account)
        val listener = app.cash.sqldelight.Query.Listener { trySend(Unit) }
        grants.addListener(listener); source.addListener(listener); requests.addListener(listener); trySend(Unit)
        awaitClose { grants.removeListener(listener); source.removeListener(listener); requests.removeListener(listener) }
    }.conflate()

    fun automationResult(error: String?) = synchronized(lock) {
        require(error == null || error in setOf("NEEDS_OPEN", "OFFLINE", "RETRY", "CAPACITY"))
        database.transaction { states().filter(::live).forEach { s ->
            val next = if (error == null && s.error !in setOf("NEEDS_OPEN", "OFFLINE", "RETRY", "CAPACITY")) s.error else error
            if (s.error != next) save(s.copy(error = next))
        } }
    }

    fun views(): List<ContinuousView> = synchronized(lock) {
        if (!ready()) return@synchronized emptyList()
        collect()
        states().map { s ->
            val session = live(s) && currentSession(s)
            val readable = session && paired(s) && s.status == "ACTIVE" && s.offer.recipient == account && s.asOf + 7 * ContinuousCodec.DAY > now()
            ContinuousView(s.offer, s.offer.owner == account,
                if (s.offer.expiresAt <= now()) "EXPIRED" else if (s.status == "INVITED") "INVITED" else if (live(s) && !session) "RECONNECT" else if (s.status == "ACTIVE" && s.offer.recipient == account && s.asOf + 7 * ContinuousCodec.DAY <= now()) "STALE" else s.status,
                s.categories, s.asOf, s.confirmedAt,
                live(s) && (s.sending != null || s.status in setOf("OFFERED", "ACCEPTED") || s.offer.owner == account && s.sourceRevision != source.version().second),
                if (readable) s.records else emptyList(), s.error, s.cleanupConfirmed)
        }
    }

    /** Source mutations are coalesced here; no mutation exports a whole dataset
     * or writes to another account's canonical application tables. */
    fun synchronize() = synchronized(lock) {
        if (!ready()) return@synchronized
        collect()
        for (request in requests().take(2)) if (port.withSession(request.peer) { true } == true) offer(request.peer, request.scope)
        database.transaction { q.pruneDeletedResourceVersions() }
        port.drain(KIND, listOf(listOf("l", "cc.continuous.sync.v1", "cruxcoach.private"))) { _, _ -> }
        port.drain(KIND, TAGS) { session, raw ->
            ContinuousCodec.decode(raw)?.let { wire -> database.transaction { receive(session, wire) } }
        }
        discardSuperseded()
        var remaining = 8
        val queue = states().filter { live(it) || it.control != null || it.error == "NEEDS_OPEN" }.sortedBy { it.scheduledAt }
        var scheduling = queue.maxOfOrNull { it.scheduledAt } ?: 0
        for (before in queue) {
            var grantBudget = 2
            if (before.offer.expiresAt <= now()) continue
            port.withSession(peer(before.offer)) { session ->
                if (before.status == "INVITED") return@withSession
                if (live(before) && !sessionMatches(before.offer, session)) {
                    database.transaction { endFamily(before, signedEnd(before)) }
                    return@withSession
                }
                val prepared = database.transactionWithResult<ContinuousState?> {
                    var s = load(before.offer.id) ?: return@transactionWithResult null
                    if (s.offer.owner == account && live(s) && !ownerAuthority(s.offer)) {
                        endFamily(s, signedEnd(s)); s = load(s.offer.id)!!
                    }
                    if (!live(s) && s.error == "NEEDS_OPEN") {
                        signedEnd(s)?.let { endFamily(s, it) }; s = load(s.offer.id)!!
                    }
                    if (s.offer.owner == account && s.status in setOf("ACCEPTED", "ACTIVE")) {
                        val narrowed = s.offer.scope.categories.filterTo(linkedSetOf()) { allowed(s, it) }
                        s = s.copy(categories = narrowed)
                        if (live(s) && paired(s) && s.categories.all { allowed(s, it) }) {
                            s = try { prepare(s) } catch (_: IllegalArgumentException) { s.copy(sending = null, sentPages = 0, error = "LIMIT") }
                        }
                    }
                    if (s.control?.expiresAt?.let { it <= now() || it > now() + ContinuousCodec.DAY } == true) {
                        s = s.copy(control = if (s.control?.action == "END_ACK") null else s.control?.copy(expiresAt = now() + ContinuousCodec.DAY))
                    }
                    if (remaining > 0) { scheduling = (scheduling + 1).coerceAtLeast(scheduling); s = s.copy(scheduledAt = scheduling) }
                    save(s)
                    s
                } ?: return@withSession
                // Committed obligation survives a process death/ambiguous JNI
                // return. Authorization is then held again at the real handoff.
                discardSuperseded() // Reclaim superseded data before consuming native quota.
                prepared.control?.let { wire -> if (remaining > 0) database.transaction {
                    val current = load(prepared.offer.id) ?: return@transaction
                    if (authorized(current, session, wire)) {
                        port.handoff(session, KIND, TAGS, json.encodeToString(wire)); remaining--; grantBudget--
                    }
                } }
                prepared.sending?.let { transfer ->
                    var cursor = prepared.sentPages
                    while (cursor < transfer.pages.size && remaining > 0 && grantBudget > 0) {
                        val wire = page(prepared, transfer, cursor)
                        val sent = database.transactionWithResult {
                            val current = load(prepared.offer.id) ?: return@transactionWithResult false
                            if (!authorized(current, session, wire)) return@transactionWithResult false
                            port.handoff(session, KIND, TAGS, json.encodeToString(wire))
                            save(current.copy(sentPages = cursor + 1))
                            true
                        }
                        if (!sent) break
                        remaining--; grantBudget--; cursor++
                    }
                }
            }
        }
        discardSuperseded()
        var publishes = 8
        port.flush(KIND, TAGS) { session, raw, publish ->
            if (publishes > 0) database.transaction {
                val wire = ContinuousCodec.decode(raw) ?: return@transaction
                val state = load(wire.offer.id) ?: return@transaction
                if (authorized(state, session, wire)) { publish(); publishes-- }
            }
        }
    }
    private fun discardSuperseded() {
        port.discardContinuousInbox { raw ->
            val wire = ContinuousCodec.decode(raw)
            val state = wire?.let { load(it.offer.id) }
            wire != null && (wire.action != "PAGE" || state != null && live(state) && wire.offer.selection >= state.offer.selection ||
                state == null && wire.counterOffer?.let { load(it.id)?.status == "OFFERED" } == true)
        }
        port.discardSupersededContinuous { raw ->
            database.transactionWithResult {
                val wire = ContinuousCodec.decode(raw) ?: return@transactionWithResult false
                val state = load(wire.offer.id) ?: return@transactionWithResult false
                if (wire.offer != state.offer || wire.expiresAt <= now()) return@transactionWithResult false
                wire == state.control || state.sending?.let { transfer ->
                    wire.action == "PAGE" && wire.page in transfer.pages.indices && wire == page(state, transfer, wire.page)
                } == true
            }
        }
    }

    private fun page(s: ContinuousState, t: ContinuousTransfer, index: Int) = ContinuousWire(
        action = "PAGE", offer = s.offer, expiresAt = t.wireExpiry, sequence = t.version, base = t.base, full = t.full,
        sourceRevision = t.sourceRevision, asOf = t.asOf, categories = t.categories, digest = t.digest,
        page = index, pages = t.pages.size, changes = t.pages[index],
        // Receiver-owned direction may arrive before its ACCEPT on redundant
        // relays. Carry both initial certificates so the requester can admit
        // the same acceptance atomically; never bootstrap an unknown request.
        counterOffer = if (s.offer.requestHash.isNotEmpty()) family(s).firstOrNull { it.initialOffer.requestHash.isEmpty() }?.initialOffer else null,
        initialSelection = s.initialOffer.takeIf { it.requestHash.isNotEmpty() },
    )
    private fun prepare(state: ContinuousState): ContinuousState {
        val sourceVersion = source.version()
        require(sourceVersion.second < Long.MAX_VALUE)
        val target = selected(state)
        val digest = ContinuousCodec.digest(target)
        val pending = state.sending
        if (pending != null && pending.target == target && pending.categories == state.categories && pending.wireExpiry > now()) return state
        if (pending == null && state.acknowledged > 0 && state.records == target && state.asOf + ContinuousCodec.DAY > now())
            return state.copy(sourceRevision = sourceVersion.second, error = null)
        val full = state.acknowledged == 0L
        val old = if (full) emptyMap() else state.records.associateBy { it.id }
        val current = target.associateBy { it.id }
        val changes = (old.keys - current.keys).sorted().map { ContinuousChange(it) } +
            target.filter { old[it.id] != it }.map { ContinuousChange(it.id, it) }
        require(state.sequence < Long.MAX_VALUE)
        val transfer = ContinuousTransfer(state.sequence + 1, if (full) 0 else state.acknowledged, full,
            sourceVersion.second, now(), minOf(state.offer.expiresAt, now() + ContinuousCodec.DAY), state.categories,
            digest, target, ContinuousCodec.pages(changes))
        return state.copy(sequence = transfer.version, sending = transfer, sentPages = 0, control = state.control?.takeIf { it.action == "ACCEPT" }, error = null)
    }
    private fun authorized(state: ContinuousState, session: MarmotSnapshotSession, wire: ContinuousWire): Boolean {
        if (!ready() || wire.offer != state.offer || wire.expiresAt <= now() || (if (wire.action in setOf("END", "END_ACK")) session.local.account != account || session.peer.account != peer(state.offer) else !sessionMatches(state.offer, session, requireRole = live(state)))) return false
        if (wire.action == "PAGE") {
            val t = state.sending ?: return false
            return state.status in setOf("ACCEPTED", "ACTIVE") && paired(state) && ownerAuthority(state.offer) &&
                t.categories.all { allowed(state, it) } && wire.page in t.pages.indices && wire == page(state, t, wire.page) &&
                runCatching { selected(state) == t.target }.getOrDefault(false)
        }
        if (wire != state.control) return false
        return when (wire.action) {
            "OFFER" -> state.status == "OFFERED" && ownerAuthority(state.offer)
            "SELECT" -> live(state) && paired(state) && ownerAuthority(state.offer)
            "ACCEPT" -> state.status == "ACCEPTED" && state.offer.recipient == account && ready(true)
            "ACK", "RESYNC" -> live(state) && state.offer.recipient == account
            "END", "END_ACK" -> !live(state) && wire.end?.let { validEnd(it, state) } == true
            else -> false
        }
    }
    private fun receive(session: MarmotSnapshotSession, wire: ContinuousWire) {
        val o = wire.offer
        if (!ready() || !verified(o) || o.createdAt > now() + 60_000 || wire.expiresAt <= now() ||
            wire.expiresAt > now() + ContinuousCodec.DAY + 60_000 || session.local.account != account || session.peer.account != peer(o)) return
        var state = load(o.id)
        if (state == null && wire.action == "PAGE" && wire.counterOffer != null && wire.initialSelection != null) {
            val request = load(wire.counterOffer.id)
            if (request?.status == "OFFERED" && request.offer == wire.counterOffer && wire.initialSelection.id == o.id) {
                receive(session, ContinuousWire(action = "ACCEPT", offer = wire.counterOffer, counterOffer = wire.initialSelection, expiresAt = wire.expiresAt))
                state = load(o.id)
            }
        }
        if (state != null && peer(state.offer) != session.peer.account) return
        if (wire.action in setOf("END", "END_ACK")) {
            val e = wire.end ?: return
            if (state == null && wire.action == "END" && q.grantById(account,o.id).executeAsOneOrNull() == null &&
                q.grants(account).executeAsList().size < ContinuousCodec.MAX_GRANTS) {
                val closed = ContinuousState(o, "ENDED")
                if (!validEnd(e, closed) || e.signer != session.peer.account) return
                save(closed); state = closed
            }
            state ?: return
            if (!validEnd(e, state)) return
            if (wire.action == "END" && e.signer == session.peer.account) endFamily(state, e, "END_ACK")
            if (wire.action == "END_ACK" && e.signer == account && !live(state) && family(state).any { it.control?.end == e })
                family(state).forEach { save(it.copy(control = null, cleanupConfirmed = true, error = null)) }
            return
        }
        if (!sessionMatches(o, session, requireRole = wire.action != "OFFER")) return
        if (wire.action == "OFFER") {
            if (o.recipient != account || q.grantById(account, o.id).executeAsOneOrNull() != null || o.selection != 1L || o.id != o.friendship || o.requestHash.isNotEmpty() ||
                o.createdAt + 7 * ContinuousCodec.DAY <= now()) return
            if (q.grants(account).executeAsList().size >= ContinuousCodec.MAX_GRANTS || states().count(::live) >= ContinuousCodec.MAX_ACTIVE * 2 ||
                states().any { peer(it.offer) == o.owner && live(it) }) return
            save(ContinuousState(o, "INVITED")); return
        }
        state ?: return // An unknown generation never bootstraps on PAGE/ACCEPT/SELECT.
        if (!live(state)) return
        if (wire.action == "ACCEPT") {
            val own = wire.counterOffer ?: return
            if (o.owner != account || state.status != "OFFERED" || o != state.offer || !ownerAuthority(o) || !verified(own) ||
                own.owner != session.peer.account || own.recipient != account || own.friendship != o.friendship ||
                own.requestHash != offerHash(state.initialOffer) || own.selection != 1L || own.id == o.id ||
                own.ownerDevice != o.recipientDevice || own.recipientDevice != o.ownerDevice || own.binding != o.binding ||
                own.ownerRole != o.recipientRole || own.recipientRole != o.ownerRole || own.createdAt > now() + 60_000 ||
                q.grantById(account, own.id).executeAsOneOrNull() != null || q.grants(account).executeAsList().size >= ContinuousCodec.MAX_GRANTS) return
            save(state.copy(status = "ACCEPTED", control = null)); save(ContinuousState(own, "ACCEPTED")); return
        }
        if (o != state.offer) {
            if (o.owner != session.peer.account || !paired(state) || o.selection <= state.offer.selection ||
                o.copy(scope = state.offer.scope, selection = state.offer.selection, signature = state.offer.signature) != state.offer) return
            // Owner-signed scope change: immediately delete newly excluded data;
            // invalidate staging and demand a full projection under this selection.
            val next = state.copy(offer = o, categories = o.scope.categories,
                records = state.records.filter { ContinuousCodec.valid(it, o.scope) }, receiving = emptyMap(),
                control = null, sourceRevision = -1)
            save(next); state = next
        }
        when (wire.action) {
            "SELECT" -> Unit
            "ACK" -> if (o.owner == account && state.sending?.let { wire.sequence == it.version && wire.digest == it.digest } == true) {
                val t = state.sending!!
                save(state.copy(status = "ACTIVE", acknowledged = t.version, records = t.target, sourceRevision = t.sourceRevision,
                    asOf = t.asOf, confirmedAt = now(), sending = null, sentPages = 0, control = null))
            }
            "RESYNC" -> if (o.owner == account && state.status in setOf("ACTIVE", "ACCEPTED") && wire.sequence <= state.sequence) {
                save(state.copy(acknowledged = 0, records = emptyList(), sending = null, sentPages = 0))
            }
            "PAGE" -> if (o.recipient == account && paired(state) && state.status in setOf("ACCEPTED", "ACTIVE")) receivePage(state, wire)
        }
    }
    private fun conflict(state: ContinuousState) {
        endFamily(state, null)
        family(state).forEach { save(it.copy(status = "CONFLICT")) }
    }
    private fun receivePage(state: ContinuousState, wire: ContinuousWire) {
        // A lease-expired replica has no base payload left. A matching old
        // sequence alone cannot make a delta applicable to an empty replica.
        if (state.error == "STALE" && !wire.full) {
            save(state.copy(receiving = emptyMap(), control = control(state, "RESYNC", state.acknowledged))); return
        }
        if (wire.asOf > now() + 60_000 || wire.asOf < state.asOf || wire.sourceRevision < state.sourceRevision || wire.asOf + 7 * ContinuousCodec.DAY <= now() ||
            !state.offer.scope.categories.containsAll(wire.categories)) return
        if (wire.sequence <= state.acknowledged) {
            if (wire.sequence == state.acknowledged && wire.digest == ContinuousCodec.digest(state.records))
                save(state.copy(control = control(state, "ACK", state.acknowledged, wire.digest)))
            return
        }
        if (!wire.full && wire.base != state.acknowledged) {
            save(state.copy(receiving = emptyMap(), control = control(state, "RESYNC", state.acknowledged))); return
        }
        val existing = state.receiving.values.firstOrNull()
        if (existing != null && existing.sequence > wire.sequence) return
        val pages = if (existing == null || existing.sequence < wire.sequence) mutableMapOf() else state.receiving.toMutableMap()
        if (pages.isNotEmpty() && pages.values.first().copy(page = wire.page, changes = wire.changes) != wire) {
            conflict(state); return
        }
        if (pages[wire.page]?.let { it != wire } == true) { conflict(state); return }
        pages[wire.page] = wire
        if (pages.size < wire.pages) { save(state.copy(receiving = pages)); return }
        val changes = (0 until wire.pages).flatMap { pages[it]?.changes ?: return }
        if (changes.map { it.id }.distinct().size != changes.size) { conflict(state); return }
        val records = (if (wire.full) emptyList() else state.records).associateBy { it.id }.toMutableMap()
        for (change in changes) {
            val record = change.record
            val old = state.records.firstOrNull { it.id == change.id }
            if (record != null && (record.revision > wire.sourceRevision || old != null &&
                (record.revision < old.revision || record.revision == old.revision && record != old))) {
                conflict(state); return
            }
            if (record == null) records.remove(change.id) else records[change.id] = record
        }
        val target = records.values.sortedBy { it.id }
        if (runCatching { ContinuousCodec.checkRecords(target, state.offer.scope.copy(categories = wire.categories)) }.isFailure ||
            ContinuousCodec.digest(target) != wire.digest) { conflict(state); return }
        val next = state.copy(status = "ACTIVE", sequence = wire.sequence, acknowledged = wire.sequence,
            sourceRevision = wire.sourceRevision, asOf = wire.asOf, records = target, categories = wire.categories,
            receiving = emptyMap(), error = null)
        save(next.copy(control = control(next, "ACK", wire.sequence, wire.digest)))
    }
    companion object {
        const val KIND = 1223
        val TAGS = listOf(listOf("l", "cc.friendship.sync.v1", "cruxcoach.private"))
    }
}
