package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.SharingCategory
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
 * The sync semantics above MLS (see docs/architecture/marmot-permissions-v2-target.md §2.3, §7).
 *
 * Per direction the owner sends a manifest and full pages for a new generation,
 * then deltas; the receiver keeps its own state, verifies count and digest
 * after every step, buffers a bounded number of out-of-order messages and asks
 * for a resync on a gap or mismatch. Idempotence comes from generation and
 * sequence, per-record revisions and native send tokens; no transaction spans
 * the app database and the native host.
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
) : SharingStep {
    private val mutex = Mutex()
    private val heartbeatSent = mutableSetOf<String>()
    /** Per requested person: when to try the invitation next, and the pause that led there. */
    private val inviteRetry = mutableMapOf<String, Pair<Long, Long>>()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** An explicit new request is attempted at once, not after the retry pause. */
    suspend fun retryInvitationNow(peer: String) = mutex.withLock { inviteRetry.remove(peer) }

    override suspend fun beforeOnline(host: MarmotHost) = mutex.withLock { cancelPending(host) }

    override suspend fun run(host: MarmotHost) = mutex.withLock {
        cancelPending(host)
        requests(host, host.status())
        receive(host)
        val status = host.status()
        reconcile(host, status)
        endings(host, status)
        val policy = store.policy()
        for (person in store.persons()) send(host, person, status, policy)
        replies(host, status)
    }

    // ---- local obligations --------------------------------------------------------

    /** A narrowing whose native withdrawal did not happen yet: withdraw first. */
    private suspend fun cancelPending(host: MarmotHost) {
        for (person in store.persons().filter { it.cancelPending }) {
            runCatching { host.cancel(person.peer) }.onSuccess { withdrawn ->
                store.transaction {
                    if (withdrawn > 0) store.requireFull(person.peer)
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
            "resync" -> {
                val out = store.outgoing(peer) ?: return
                if (message.gen >= out.generation) store.requireFull(peer)
            }
            else -> if (person.state == PersonState.CONNECTED) data(peer, message, now)
        }
    }

    private fun acknowledged(peer: String, message: ShareMessage, now: Long) {
        val out = store.outgoing(peer) ?: return
        if (message.gen != out.generation || message.seq > out.sequence) return
        if (message.seq == out.sequence && message.digest != out.digest) {
            store.requireFull(peer)
            return
        }
        if (message.gen > out.ackedGeneration || message.seq >= out.ackedSequence) store.recordAck(peer, message.gen, message.seq, now)
    }

    private fun data(peer: String, message: ShareMessage, now: Long) {
        var state = store.incoming(peer)
        state = when (message.type) {
            "manifest" -> manifest(peer, state, message, now)
            "full" -> full(peer, state, message, now)
            "delta" -> delta(peer, state, message, now)
            else -> state
        }
        store.putIncoming(peer, state)
    }

    private fun manifest(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        val scope = m.scope ?: return state
        if (m.gen < state.generation) return state
        if (m.gen > state.generation) {
            // Narrowing takes effect immediately, before the new state arrives.
            narrow(peer, scope)
            var next = IncomingState(m.gen, -1, scope, m.digest, m.count, emptyMap(),
                state.pending.filter { it.gen >= m.gen }, state.resyncGeneration, state.resyncAt, false, now)
            if (m.count == 0) {
                store.deleteRecords(peer)
                next = if (m.digest == SharingRecords.digest(emptyList())) next.copy(sequence = 0, ackDue = true) else requestResync(next, now)
            }
            return drain(peer, next, now)
        }
        if (!state.complete) return state
        narrow(peer, scope)
        var next = state.copy(scope = scope)
        if (m.seq == state.sequence) {
            val local = store.records(peer)
            if (local.size != m.count || SharingRecords.digest(local) != m.digest) next = requestResync(next, now)
        }
        return next
    }

    private fun full(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        if (m.gen > state.generation) return buffer(state, m, now)
        if (m.gen != state.generation || state.complete) return state
        if (!m.records.all { SharingRecords.valid(it, state.scope.asSourceScope()) }) return requestResync(state.copy(staging = emptyMap()), now)
        val staging = state.staging + (m.part to m.records)
        if ((0 until m.parts).any { it !in staging } || staging.keys.any { it >= m.parts }) return state.copy(staging = staging)
        val all = (0 until m.parts).flatMap { staging.getValue(it) }
        if (all.size != state.expectedCount || all.map { it.id }.distinct().size != all.size ||
            SharingRecords.digest(all) != state.expectedDigest) {
            return requestResync(state.copy(staging = emptyMap()), now)
        }
        store.deleteRecords(peer)
        all.forEach { store.putRecord(peer, it) }
        return drain(peer, state.copy(sequence = 0, staging = emptyMap(), ackDue = true, receivedAt = now), now)
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
        val existing = store.records(peer).associateBy { it.id }
        for (record in m.records) {
            val old = existing[record.id]
            if (old == null || old.revision <= record.revision) store.putRecord(peer, record)
        }
        m.deletes.forEach { store.deleteRecord(peer, it) }
        val local = store.records(peer)
        val next = state.copy(sequence = m.seq, scope = scope, ackDue = true, receivedAt = now)
        return if (local.size != m.count || SharingRecords.digest(local) != m.digest) requestResync(next, now) else next
    }

    /** Hold an out-of-order message; too many means a real gap: resync. */
    private fun buffer(state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        if (state.pending.any { it == m }) return state
        if (state.pending.size >= MAX_PENDING) return requestResync(state.copy(pending = emptyList()), now, m.gen)
        return state.copy(pending = state.pending + m)
    }

    private fun drain(peer: String, start: IncomingState, now: Long): IncomingState {
        var state = start
        while (true) {
            val pending = state.pending.filter { it.gen > state.generation || it.gen == state.generation && (it.type == "full" && !state.complete || it.seq > state.sequence) }
            state = state.copy(pending = pending)
            val next = pending.firstOrNull { it.gen == state.generation && (it.type == "full" && !state.complete || it.type == "delta" && state.complete && it.seq == state.sequence + 1) }
                ?: return state
            val rest = pending - next
            state = when (next.type) {
                "full" -> full(peer, state.copy(pending = rest), next, now)
                else -> applyDelta(peer, state.copy(pending = rest), next, now)
            }
        }
    }

    /** Delete received records outside [scope]: categories no longer shared and
     * training older than the cutoff. */
    private fun narrow(peer: String, scope: ShareScope) {
        for (record in store.records(peer)) {
            val outside = record.category !in scope.categories ||
                record.category == SharingCategory.TRAINING_HISTORY && scope.cutoff != null &&
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
            // A generation whose full never completes, or buffered messages of an
            // unknown generation, are gaps as well.
            val stalled = now - state.receivedAt > STALL_MS
            if (stalled && (!state.complete && state.generation > 0 || state.pending.isNotEmpty())) {
                state = requestResync(state, now, maxOf(state.generation, state.pending.maxOfOrNull { it.gen } ?: 0))
            }
            if (state.resyncGeneration > 0 && state.resyncAt == 0L) {
                val token = "rs:g${state.resyncGeneration}:h${now / 3_600_000}"
                val message = ShareMessage(type = "resync", gen = state.resyncGeneration, seq = state.sequence)
                if (runCatching { host.send(peer, token, ShareProtocol.encode(message)) }.isSuccess) state = state.copy(resyncAt = now)
            }
            if (state.ackDue && state.complete) {
                val digest = SharingRecords.digest(store.records(peer))
                val message = ShareMessage(type = "ack", gen = state.generation, seq = state.sequence, digest = digest)
                val token = "ack:g${state.generation}:s${state.sequence}:${digest.take(16)}"
                if (runCatching { host.send(peer, token, ShareProtocol.encode(message)) }.isSuccess) state = state.copy(ackDue = false)
            }
            if (state != start) store.putIncoming(peer, state)
        }
    }

    // ---- sending -----------------------------------------------------------------------------

    private suspend fun send(host: MarmotHost, person: SharingPerson, status: MarmotStatus, policy: com.cruxcoach.domain.sharing.SharingPolicy) {
        if (person.state != PersonState.CONNECTED) return
        val native = status.peers.firstOrNull { it.account == person.peer } ?: return
        if (!native.active) return
        val now = clock()
        val scope = store.scope(person, policy, today())
        val target = runCatching { store.selected(person, scope, policy, source) }.getOrElse { return }
        val targetMap = target.associate { it.id to it.revision }
        val out = store.outgoing(person.peer)
        // A withdrawal after this generation started (for example one whose app
        // commit was lost in a crash) also leaves a gap: start a new generation.
        var full = out == null || out.fullRequired || native.cancelledAt > 0 && native.cancelledAt >= out.generationStartedAt
        val messages = mutableListOf<Pair<String, ShareMessage>>()
        var next: OutgoingState? = null
        if (!full && out != null) {
            val upserts = target.filter { out.sent[it.id] != it.revision }
            val deletes = (out.sent.keys - targetMap.keys).toList()
            if (upserts.isEmpty() && deletes.isEmpty() && scope == out.scope) {
                val hour = now / 3_600_000
                if (now - out.manifestAt < HEARTBEAT_MS && person.peer in heartbeatSent) return
                messages += "hb:g${out.generation}:s${out.sequence}:h$hour" to ShareProtocol.manifest(out.generation, out.sequence, scope, target)
                next = out.copy(manifestAt = now)
            } else {
                val delta = ShareProtocol.delta(out.generation, out.sequence + 1, scope, upserts, deletes, target)
                if (upserts.size > ShareProtocol.MAX_DELTA_UPSERTS || deletes.size > ShareProtocol.MAX_DELTA_DELETES || !ShareProtocol.fits(delta)) {
                    full = true
                } else {
                    messages += "g${out.generation}:s${out.sequence + 1}" to delta
                    next = out.copy(sequence = out.sequence + 1, scope = scope, sent = targetMap,
                        digest = delta.digest, count = target.size)
                }
            }
        }
        if (full) {
            val generation = (out?.generation ?: 0) + 1
            messages.clear()
            messages += "g$generation:m" to ShareProtocol.manifest(generation, 0, scope, target)
            ShareProtocol.full(generation, target).forEach { messages += "g$generation:f${it.part}" to it }
            next = OutgoingState(generation, 0, now, scope, targetMap, SharingRecords.digest(target), target.size, now,
                false, out?.ackedGeneration ?: 0, out?.ackedSequence ?: -1, out?.ackedAt ?: 0)
        }
        for ((token, message) in messages) {
            val sent = runCatching { host.send(person.peer, token, ShareProtocol.encode(message)) }
            val failure = sent.exceptionOrNull() ?: continue
            if ((failure as? MarmotFailure)?.code == "token_conflict") {
                // An earlier attempt with this token carried other content (a
                // crash between native admission and our commit): start over
                // with a generation no earlier attempt used.
                val used = message.gen
                store.putOutgoing(person.peer, (out ?: next!!).copy(generation = used, fullRequired = true, generationStartedAt = now))
            }
            return
        }
        next?.let {
            store.putOutgoing(person.peer, it)
            if (it.manifestAt == now) heartbeatSent += person.peer
        }
    }

    companion object {
        const val MAX_PENDING = 64
        const val INVITE_FIRST_RETRY_MS = 15_000L
        const val INVITE_RETRY_MS = 10 * 60_000L
        const val RESYNC_RETRY_MS = 60 * 60_000L
        const val STALL_MS = 10 * 60_000L
        const val HEARTBEAT_MS = 24 * 60 * 60_000L
    }
}
