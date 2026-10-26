package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.SharePolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneOffset

/** The native host plus the scope in which a person-initiated action may
 * show an external signer's dialog. */
interface SharingHost {
    val host: MarmotHost
    suspend fun <T> interactive(block: suspend () -> T): T
}

/**
 * The sync semantics (earlier, per person: docs/architecture/marmot-permissions-v2-target.md §2.3, §7).
 *
 * The owner syncs per stream, not per person: a stream has one generation,
 * sequence, digest and sent set, however many friends are in it. A friend
 * joins a stream by a snapshot of its current (generation, sequence) handed to
 * them alone ([ShareMessage.assign] > 0); after that they receive the
 * stream's deltas, heartbeats and new generations like every other member. A
 * friend with a gap of their own (resync, wrong digest, withdrawn messages, a
 * failed send) gets a new snapshot; the others are not disturbed.
 *
 * The receiver keeps its own state, verifies count and digest after every step,
 * buffers a bounded number of out-of-order messages (also those of a stream
 * whose snapshot is still on its way) and asks for a resync on a gap or
 * mismatch. A heartbeat ahead of the received sequence is a gap as well, so
 * nothing is lost silently when a relay drops messages or they expire.
 * Idempotence comes from (stream, assign, generation, sequence), per-record
 * revisions and native send tokens; no transaction spans the app database and
 * the native host.
 *
 * Every step runs under one mutex. Narrowing ([exclusive]) holds the same
 * mutex, so no message is computed under an old policy after the new one is
 * committed.
 */
class SharingSync(
    private val store: SharingStore,
    private val source: SharingSource,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) },
    private val newStreamId: () -> String = ::randomStreamId,
) : SharingStep {
    private val mutex = Mutex()
    /** Per requested person: when to try the invitation next, and the pause that led there. */
    private val inviteRetry = mutableMapOf<String, Pair<Long, Long>>()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** An explicit new request is attempted at once, not after the retry pause. */
    suspend fun retryInvitationNow(peer: String) = mutex.withLock { inviteRetry.remove(peer) }

    override suspend fun beforeOnline(host: MarmotHost) = mutex.withLock { cancelPending(host) }

    override suspend fun run(host: MarmotHost) = mutex.withLock {
        expire(host)
        cancelPending(host)
        requests(host, host.status())
        receive(host)
        val status = host.status()
        reconcile(host, status)
        endings(host, status)
        join(clock())
        val policy = store.policy()
        for (stream in store.streams()) send(host, stream, status, policy)
        replies(host, status)
    }

    // ---- local obligations --------------------------------------------------------

    /** Grants that ended: received records go now (the friend need not be in
     * reach), and for own rules that ended, what is still queued is withdrawn so
     * nothing computed under the old grant arrives late. */
    private suspend fun expire(host: MarmotHost) {
        val now = clock()
        store.purgeExpiredRecords(now)
        for (peer in store.takeExpiredRules(now)) {
            store.addHistory(now, peer, HistoryEvent.EXPIRED)
            runCatching { host.cancel(peer) }
                .onSuccess { withdrawn -> if (withdrawn > 0) store.requireSnapshot(peer) }
                .onFailure { store.setCancelPending(peer, true) }
        }
    }

    /** A narrowing whose native withdrawal did not happen yet: withdraw first. */
    private suspend fun cancelPending(host: MarmotHost) {
        for (person in store.persons().filter { it.cancelPending }) {
            runCatching { host.cancel(person.peer) }.onSuccess { withdrawn ->
                store.transaction {
                    if (withdrawn > 0) store.requireSnapshot(person.peer)
                    store.setCancelPending(person.peer, false)
                }
            }
        }
    }

    private suspend fun requests(host: MarmotHost, status: MarmotStatus) {
        val now = clock()
        for (person in store.persons().filter { it.state == PersonState.REQUESTED }) {
            val native = status.peers.firstOrNull { it.account == person.peer }
            when {
                native != null && !native.ended && native.invitedByMe -> store.setState(person.peer, PersonState.CONNECTED, now)
                // Both asked each other: accept theirs instead of a second group.
                native != null && native.state == "invited" -> {
                    runCatching { host.accept(person.peer) }.onSuccess { store.setState(person.peer, PersonState.CONNECTED, now) }
                }
                // The friend's KeyPackage may not have reached a relay yet:
                // retry soon, then back off to the long pause.
                status.online && now >= (inviteRetry[person.peer]?.first ?: 0) -> {
                    val pause = ((inviteRetry[person.peer]?.second ?: (INVITE_FIRST_RETRY_MS / 2)) * 2).coerceAtMost(INVITE_RETRY_MS)
                    inviteRetry[person.peer] = (now + pause) to pause
                    runCatching { host.invite(person.peer) }.onSuccess { store.setState(person.peer, PersonState.CONNECTED, now) }
                }
            }
        }
    }

    /** The native layer ended a group (the friend ended it, or it lost its
     * two-account shape): forget what we received. */
    private fun reconcile(host: MarmotHost, status: MarmotStatus) {
        val now = clock()
        for (person in store.persons().filter { it.state == PersonState.CONNECTED }) {
            val native = status.peers.firstOrNull { it.account == person.peer } ?: continue
            if (native.ended) store.transaction {
                store.clearExchange(person.peer)
                store.setState(person.peer, PersonState.ENDED, now)
            }
        }
    }

    private suspend fun endings(host: MarmotHost, status: MarmotStatus) {
        val now = clock()
        for (person in store.persons()) {
            val native = status.peers.firstOrNull { it.account == person.peer }
            when (person.state) {
                PersonState.ENDING -> {
                    if (native != null && !native.ended) {
                        val sent = runCatching { host.send(person.peer, "end", ShareProtocol.encode(ShareMessage(type = "end"))) }
                        if (sent.isFailure && (sent.exceptionOrNull() as? MarmotFailure)?.code != "session_not_active") continue
                        runCatching { host.end(person.peer) }.getOrElse { continue }
                    }
                    store.setState(person.peer, PersonState.ENDED, now)
                }
                PersonState.ENDED -> if (native != null && !native.ended) runCatching { host.end(person.peer) }
                else -> Unit
            }
        }
    }

    // ---- receiving ---------------------------------------------------------------------

    private suspend fun receive(host: MarmotHost) {
        repeat(64) {
            val batch = host.next(after = 0, generation = 0, timeoutMs = 0, limit = 32)
            if (batch.items.isEmpty()) return
            for (item in batch.items) {
                // A malformed or unexpected message is dropped; divergence of
                // any cause is caught by digests and repaired by a resync.
                runCatching { store.transaction { apply(item.peer, item.content) } }
            }
            host.ack(batch.items.map { it.seq })
        }
    }

    private fun apply(peer: String, content: String) {
        val person = store.person(peer) ?: return
        if (person.state == PersonState.ENDED || person.state == PersonState.REQUESTED) return
        val message = ShareProtocol.decode(content) ?: return
        val now = clock()
        when (message.type) {
            "end" -> {
                store.clearExchange(peer)
                store.setState(peer, PersonState.ENDED, now)
            }
            "ack" -> acknowledged(peer, message, now)
            "resync" -> resyncRequested(peer, message, now)
            else -> if (person.state == PersonState.CONNECTED) data(peer, message, now)
        }
    }

    private fun acknowledged(peer: String, m: ShareMessage, now: Long) {
        val member = store.member(peer) ?: return
        // An ack of an earlier snapshot or stream says nothing about the current one.
        if (m.stream != member.stream || m.assign != member.assign) return
        val stream = store.stream(member.stream) ?: return
        if (m.gen != stream.generation || m.seq > stream.sequence) return
        if (m.seq == stream.sequence && m.digest != stream.digest) {
            store.requireSnapshot(peer)
            return
        }
        if (m.gen > member.ackedGeneration || m.seq >= member.ackedSequence) store.recordAck(peer, m.gen, m.seq, now)
    }

    private fun resyncRequested(peer: String, m: ShareMessage, now: Long) {
        val member = store.member(peer) ?: return
        val stream = store.stream(member.stream)
        // What was sent since the request answers it, unless it is too old to still be on its way.
        val answered = m.assign < member.assign && now - member.assignedAt < STALL_MS ||
            stream != null && m.stream == stream.id && m.assign == member.assign && m.gen < stream.generation &&
            now - stream.generationStartedAt < STALL_MS
        if (!answered) store.requireSnapshot(peer)
    }

    private fun data(peer: String, message: ShareMessage, now: Long) {
        val start = store.incoming(peer)
        if (message.assign == 0L && message.stream in start.retired) return
        val state = when {
            message.assign > 0 -> assigned(peer, start, message, now)
            // A stream whose snapshot has not arrived yet: hold the message.
            message.stream != start.stream -> buffer(start, message, now)
            message.type == "manifest" -> manifest(peer, start, message, now)
            message.type == "full" -> full(peer, start, message, now)
            else -> delta(peer, start, message, now)
        }
        store.putIncoming(peer, state)
    }

    /** A message for us alone: a snapshot that replaces everything from this friend. */
    private fun assigned(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState = when {
        m.assign < state.assign -> state
        m.assign == state.assign -> if (m.type == "full" && m.stream == state.stream) full(peer, state, m, now) else state
        // Pages may overtake their manifest.
        m.type != "manifest" -> buffer(state, m, now)
        else -> begin(peer, state, m, now)
    }

    /** A manifest that starts a complete state: a generation of the whole stream or a snapshot for us. */
    private fun begin(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        val scope = m.scope ?: return state
        // Narrowing takes effect immediately, before the new state arrives.
        narrow(peer, scope)
        val switched = state.stream.isNotEmpty() && state.stream != m.stream
        var next = state.copy(
            stream = m.stream,
            assign = if (m.assign > 0) m.assign else state.assign,
            generation = m.gen,
            sequence = -1,
            base = m.seq,
            stagedAssign = m.assign,
            scope = scope,
            expectedDigest = m.digest,
            expectedCount = m.count,
            staging = emptyMap(),
            ackDue = false,
            receivedAt = now,
            retired = ((state.retired - m.stream) + listOfNotNull(state.stream.takeIf { switched })).takeLast(MAX_RETIRED),
        )
        if (m.count == 0) {
            store.deleteRecords(peer)
            next = if (m.digest == SharingRecords.digest(emptyList())) next.copy(sequence = m.seq, ackDue = true) else requestResync(next, now)
        }
        return drain(peer, next, now)
    }

    private fun manifest(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        val scope = m.scope ?: return state
        if (m.gen < state.generation) return state
        // A heartbeat of a generation whose start never arrived is a gap.
        if (m.gen > state.generation) return if (m.seq == 0L) begin(peer, state, m, now) else buffer(state, m, now)
        if (!state.complete) return state
        narrow(peer, scope)
        val next = state.copy(scope = scope)
        return when {
            m.seq == state.sequence -> verify(peer, next, m, now)
            // Deltas are missing (lost, or expired in a mailbox); they may still come.
            m.seq > state.sequence -> buffer(next, m, now)
            else -> next
        }
    }

    /** A heartbeat at our own position: count and digest must match what we hold. */
    private fun verify(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        val local = store.records(peer)
        return if (local.size != m.count || SharingRecords.digest(local) != m.digest) requestResync(state, now) else state
    }

    private fun full(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        if (m.assign == 0L && m.gen > state.generation) return buffer(state, m, now)
        if (state.complete || m.gen != state.generation || m.assign != state.stagedAssign || m.seq != state.base) return state
        if (!m.records.all { SharingRecords.valid(it, state.scope.asSourceScope()) }) return requestResync(state.copy(staging = emptyMap()), now)
        val staging = state.staging + (m.part to m.records)
        if ((0 until m.parts).any { it !in staging } || staging.keys.any { it >= m.parts }) return state.copy(staging = staging)
        val all = (0 until m.parts).flatMap { staging.getValue(it) }
        if (all.size != state.expectedCount || all.map { it.id }.distinct().size != all.size ||
            SharingRecords.digest(all) != state.expectedDigest) {
            return requestResync(state.copy(staging = emptyMap()), now)
        }
        store.deleteRecords(peer)
        // A record whose grant already ended is never stored (it may have been delayed).
        all.filterNot { store.receivedExpired(it, now) }.forEach { store.putRecord(peer, it) }
        return drain(peer, state.copy(sequence = state.base, staging = emptyMap(), ackDue = true, receivedAt = now), now)
    }

    private fun delta(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        if (m.gen > state.generation || m.gen == state.generation && (!state.complete || m.seq > state.sequence + 1)) {
            return buffer(state, m, now)
        }
        if (m.gen < state.generation || m.seq <= state.sequence) return state
        return drain(peer, applyDelta(peer, state, m, now), now)
    }

    private fun applyDelta(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        val scope = m.scope ?: return state
        if (!m.records.all { SharingRecords.valid(it, scope.asSourceScope()) }) return requestResync(state.copy(sequence = m.seq), now)
        narrow(peer, scope)
        // Deltas arrive here strictly in (generation, sequence) order, so the
        // newest message wins; a revision is a content hash, not an order.
        for (record in m.records) {
            if (store.receivedExpired(record, now)) store.deleteRecord(peer, record.id) else store.putRecord(peer, record)
        }
        m.deletes.forEach { store.deleteRecord(peer, it) }
        val local = store.records(peer)
        val next = state.copy(sequence = m.seq, scope = scope, ackDue = true, receivedAt = now)
        return if (local.size != m.count || SharingRecords.digest(local) != m.digest) requestResync(next, now) else next
    }

    /** Hold an out-of-order message; too many means a real gap: resync. */
    private fun buffer(state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        if (state.pending.any { it == m }) return state
        if (state.pending.size >= MAX_PENDING) {
            return requestResync(state.copy(pending = emptyList(), gapSince = 0), now, maxOf(state.generation, m.gen))
        }
        return state.copy(pending = state.pending + m, gapSince = if (state.pending.isEmpty()) now else state.gapSince)
    }

    /** Apply what the buffer holds for the current position, drop what is stale. */
    private fun drain(peer: String, start: IncomingState, now: Long): IncomingState {
        var state = start
        while (true) {
            val pending = state.pending.filter { relevant(it, state) }
            state = state.copy(pending = pending, gapSince = if (pending.isEmpty()) 0 else state.gapSince)
            val next = pending.firstOrNull { ready(it, state) } ?: return state
            val rest = pending - next
            state = state.copy(pending = rest, gapSince = if (rest.isEmpty()) 0 else now)
            state = when (next.type) {
                "full" -> full(peer, state, next, now)
                "delta" -> applyDelta(peer, state, next, now)
                else -> verify(peer, state, next, now)
            }
        }
    }

    /** Whether a buffered message may still matter at [state]. */
    private fun relevant(m: ShareMessage, state: IncomingState): Boolean = when {
        m.assign > 0 -> m.assign > state.assign ||
            m.assign == state.assign && m.stream == state.stream && !state.complete && m.gen == state.generation && m.seq == state.base
        m.stream in state.retired -> false
        // Its snapshot may still come; if not, the gap leads to a resync.
        m.stream != state.stream -> true
        m.gen != state.generation -> m.gen > state.generation
        m.type == "full" -> !state.complete && state.stagedAssign == 0L
        m.type == "manifest" -> m.seq > state.sequence || state.complete && m.seq == state.sequence
        else -> m.seq > state.sequence
    }

    /** Whether a buffered message applies exactly at [state]. */
    private fun ready(m: ShareMessage, state: IncomingState): Boolean =
        m.stream == state.stream && m.gen == state.generation && when (m.type) {
            "full" -> !state.complete && m.assign == state.stagedAssign && m.seq == state.base
            "delta" -> state.complete && m.seq == state.sequence + 1
            else -> m.assign == 0L && state.complete && m.seq == state.sequence
        }

    /** Delete received records outside [scope]: categories no longer shared and
     * dated records older than the cutoff. */
    private fun narrow(peer: String, scope: ShareScope) {
        for (record in store.records(peer)) {
            val outside = record.category !in scope.categories ||
                record.category.dated && scope.cutoff != null &&
                (record.fields["date"]?.take(10) ?: "") < scope.cutoff
            if (outside) store.deleteRecord(peer, record.id)
        }
    }

    private fun requestResync(state: IncomingState, now: Long, generation: Long = state.generation): IncomingState {
        val recentlyAsked = state.resyncGeneration == generation && (state.resyncAt == 0L || now - state.resyncAt < RESYNC_RETRY_MS)
        return if (recentlyAsked) state else state.copy(resyncGeneration = generation, resyncAt = 0)
    }

    // ---- replies (acks, resync requests) ---------------------------------------------------

    private suspend fun replies(host: MarmotHost, status: MarmotStatus) {
        val now = clock()
        for ((peer, start) in store.incomingStates()) {
            val person = store.person(peer) ?: continue
            if (person.state != PersonState.CONNECTED || status.peers.none { it.account == peer && it.active }) continue
            var state = start
            // A full state that never completes, or buffered messages whose gap
            // does not close, are gaps as well.
            val stalledStaging = !state.complete && state.generation > 0 && now - state.receivedAt > STALL_MS
            val stalledGap = state.pending.isNotEmpty() && state.gapSince > 0 && now - state.gapSince > STALL_MS
            if (stalledStaging || stalledGap) {
                state = requestResync(state, now, maxOf(state.generation, state.pending.maxOfOrNull { it.gen } ?: 0))
            }
            if (state.resyncGeneration > 0 && state.resyncAt == 0L) {
                val message = ShareMessage(type = "resync", stream = state.stream, assign = state.assign,
                    gen = state.resyncGeneration, seq = state.sequence)
                val token = "rs:${state.stream.take(8)}:a${state.assign}:g${state.resyncGeneration}:s${state.sequence}:h${now / 3_600_000}"
                if (runCatching { host.send(peer, token, ShareProtocol.encode(message)) }.isSuccess) state = state.copy(resyncAt = now)
            }
            if (state.ackDue && state.complete && state.assign > 0) {
                val digest = SharingRecords.digest(store.records(peer))
                val message = ShareMessage(type = "ack", stream = state.stream, assign = state.assign, gen = state.generation,
                    seq = state.sequence, digest = digest)
                val token = "ack:${state.stream.take(8)}:a${state.assign}:g${state.generation}:s${state.sequence}:${digest.take(16)}"
                if (runCatching { host.send(peer, token, ShareProtocol.encode(message)) }.isSuccess) state = state.copy(ackDue = false)
            }
            if (state != start) store.putIncoming(peer, state)
        }
    }

    // ---- sending -----------------------------------------------------------------------------

    /** The audience a connected person belongs to. For now everyone has a stream of their own. */
    private fun ruleKey(person: SharingPerson): String = "person:${person.peer}"

    /** Every connected person is in exactly one stream: the one of their audience. */
    private fun join(now: Long) = store.transaction {
        val connected = store.persons().filter { it.state == PersonState.CONNECTED }.associateBy { it.peer }
        for (member in store.members()) if (member.peer !in connected) store.clearMember(member.peer)
        for (person in connected.values) {
            val key = ruleKey(person)
            val member = store.member(person.peer)
            val current = member?.let { store.stream(it.stream) }
            if (current != null && current.ruleKey == key) continue
            val stream = store.streamByKey(StreamState.RULE, key) ?: StreamState.fresh(newStreamId(), key, now).also { store.putStream(it) }
            // Leaving a stream raises its epoch: from 0.2.7 its group key changes before the next message.
            if (current != null) store.putStream(current.copy(epoch = current.epoch + 1))
            store.putMember(StreamMember(person.peer, stream.id, member?.assign ?: 0, member?.assignedAt ?: 0, snapshotDue = true,
                ackedGeneration = 0, ackedSequence = -1, ackedAt = member?.ackedAt ?: 0, joinedAt = now))
        }
        store.dropEmptyStreams()
    }

    private suspend fun send(host: MarmotHost, stream: StreamState, status: MarmotStatus, policy: SharePolicy) {
        val now = clock()
        val native = status.peers.associateBy { it.account }
        val members = store.members(stream.id)
        val reachable = members.filter { native[it.peer]?.active == true }
        // Nothing moves while no member can be reached.
        val first = reachable.firstOrNull()?.let { store.person(it.peer) } ?: return
        // Every member shares the rule, so one of them stands for all.
        val scope = store.scope(first, policy, now, today())
        val target = runCatching { store.selected(first, scope, policy, source, now) }.getOrElse { return }
        val targetMap = target.associate { it.id to it.revision }
        val sent = store.sent(stream.id)
        val broadcast = mutableListOf<Pair<String, ShareMessage>>()
        var next = stream
        var full = stream.generation == 0L || stream.fullRequired
        if (!full) {
            val upserts = target.filter { sent[it.id] != it.revision }
            val deletes = (sent.keys - targetMap.keys).toList()
            if (upserts.isEmpty() && deletes.isEmpty() && scope == stream.scope) {
                // At most one heartbeat a day by the stored time, not one per
                // process start: Android restarts processes often, and every
                // manifest is one more event whose timing a relay sees.
                if (now - stream.manifestAt >= HEARTBEAT_MS) {
                    broadcast += "${stream.id}:hb:g${stream.generation}:s${stream.sequence}:h${now / 3_600_000}" to
                        ShareProtocol.manifest(stream.id, 0, stream.generation, stream.sequence, scope, target)
                    next = stream.copy(manifestAt = now)
                }
            } else {
                val delta = ShareProtocol.delta(stream.id, stream.generation, stream.sequence + 1, scope, upserts, deletes, target)
                if (upserts.size > ShareProtocol.MAX_DELTA_UPSERTS || deletes.size > ShareProtocol.MAX_DELTA_DELETES || !ShareProtocol.fits(delta)) {
                    full = true
                } else {
                    broadcast += "${stream.id}:g${stream.generation}:s${delta.seq}" to delta
                    next = stream.copy(sequence = delta.seq, scope = scope, digest = delta.digest, count = target.size)
                }
            }
        }
        if (full) {
            broadcast.clear()
            val generation = stream.generation + 1
            // A new stream has no members yet who could follow a generation: they join by snapshot.
            if (stream.generation > 0) {
                broadcast += "${stream.id}:g$generation:m" to ShareProtocol.manifest(stream.id, 0, generation, 0, scope, target)
                ShareProtocol.full(stream.id, 0, generation, 0, target).forEach { broadcast += "${stream.id}:g$generation:f${it.part}" to it }
            }
            next = stream.copy(generation = generation, sequence = 0, generationStartedAt = now, scope = scope,
                digest = SharingRecords.digest(target), count = target.size, manifestAt = now, fullRequired = false)
        }
        val advanced = next.generation != stream.generation || next.sequence != stream.sequence
        val restarted = full && stream.generation > 0
        val updated = mutableMapOf<String, StreamMember>()
        val failed = mutableSetOf<String>()
        var delivered = false
        for (member in members) {
            val peer = native[member.peer]
            // Messages withdrawn after this member's last starting point leave a
            // gap only this member has (for example a crash between the native
            // withdrawal and the app commit); a new generation covers it.
            val withdrawn = peer != null && peer.cancelledAt > maxOf(member.assignedAt, stream.generationStartedAt) && !restarted
            val due = member.snapshotDue || stream.generation == 0L || withdrawn
            if (peer?.active != true) {
                // Unreachable members miss what moves now and join again by snapshot.
                if (due != member.snapshotDue || advanced && !due) updated[member.peer] = member.copy(snapshotDue = true)
                continue
            }
            if (due) {
                if (!member.snapshotDue) updated[member.peer] = member.copy(snapshotDue = true)
                continue
            }
            for ((token, message) in broadcast) {
                val failure = runCatching { host.send(member.peer, token, ShareProtocol.encode(message)) }.exceptionOrNull() ?: continue
                if ((failure as? MarmotFailure)?.code == "token_conflict") {
                    // An earlier attempt with this token carried other content (a
                    // crash between native admission and our commit): start over
                    // with a generation no earlier attempt used.
                    store.putStream(stream.copy(generation = maxOf(stream.generation, message.gen), fullRequired = true, generationStartedAt = now))
                    return
                }
                failed += member.peer
                updated[member.peer] = member.copy(snapshotDue = true)
                break
            }
            if (member.peer !in failed) delivered = true
        }
        val snapshots = members.map { updated[it.peer] ?: it }
            .filter { it.snapshotDue && native[it.peer]?.active == true && it.peer !in failed }
        // Nobody could take what moved: try again next time, as if nothing happened.
        if (broadcast.isNotEmpty() && !delivered && snapshots.isEmpty()) return
        for (member in snapshots) {
            // A snapshot for this member alone, at the state every other member now has.
            val assign = member.assign + 1
            val prefix = "${stream.id}:a$assign:g${next.generation}:s${next.sequence}"
            val messages = listOf("$prefix:m" to ShareProtocol.manifest(stream.id, assign, next.generation, next.sequence, next.scope, target)) +
                ShareProtocol.full(stream.id, assign, next.generation, next.sequence, target).map { "$prefix:f${it.part}" to it }
            val complete = messages.all { (token, message) -> runCatching { host.send(member.peer, token, ShareProtocol.encode(message)) }.isSuccess }
            // A number once used is never used again, even when only part of it went out.
            updated[member.peer] = if (complete) member.copy(assign = assign, assignedAt = now, snapshotDue = false) else member.copy(assign = assign)
        }
        store.transaction {
            if (next != stream) store.putStream(next)
            if (advanced) store.putSent(stream.id, sent, targetMap)
            updated.values.forEach(store::putMember)
        }
    }

    companion object {
        const val MAX_PENDING = 64
        const val MAX_RETIRED = 8
        const val INVITE_FIRST_RETRY_MS = 15_000L
        const val INVITE_RETRY_MS = 10 * 60_000L
        const val RESYNC_RETRY_MS = 60 * 60_000L
        const val STALL_MS = 10 * 60_000L
        const val HEARTBEAT_MS = 24 * 60 * 60_000L

        private val random = java.security.SecureRandom()

        /** 128 random bits as 32 lowercase hex: a stream id that says nothing about its audience. */
        fun randomStreamId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
