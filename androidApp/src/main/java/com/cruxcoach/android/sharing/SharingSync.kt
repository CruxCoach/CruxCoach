package com.cruxcoach.android.sharing

import com.cruxcoach.domain.sharing.PeerId
import com.cruxcoach.domain.sharing.SharePolicy
import com.cruxcoach.domain.sharing.ShareRule
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneOffset

/** The native host plus the scope in which a person-initiated action may
 * show an external signer's dialog. */
interface SharingHost {
    val host: MarmotHost
    suspend fun <T> interactive(block: suspend () -> T): T
    /** Invitations travel as links and codes (Olm); otherwise by npub (MLS). */
    val linkInvites: Boolean get() = false
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
    @Volatile private var foreground = false
    /** Per requested person: when to try the invitation next, and the pause that led there. */
    private val inviteRetry = mutableMapOf<String, Pair<Long, Long>>()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /** An explicit new request is attempted at once, not after the retry pause. */
    suspend fun retryInvitationNow(peer: String) = mutex.withLock { inviteRetry.remove(peer) }

    override suspend fun beforeOnline(host: MarmotHost) = mutex.withLock { cancelPending(host) }

    override fun setForeground(foreground: Boolean) { this.foreground = foreground }

    override fun nextFlushAt(): Long? = store.streams().mapNotNull { s ->
        val since = s.pendingSince ?: return@mapNotNull null
        minOf((s.pendingAt ?: since) + QUIET_MS, since + HOLD_MAX_MS)
    }.minOrNull()

    override suspend fun run(host: MarmotHost) = mutex.withLock {
        returned(clock())
        expire(host)
        cancelPending(host)
        requests(host, host.status())
        receive(host)
        val status = host.status()
        reconcile(host, status)
        endings(host, status)
        val policy = store.policy()
        join(policy, clock())
        val peers = status.peers.associateBy { it.account }
        for (stream in store.streams()) send(host, stream, peers, policy)
        replies(host, status)
    }

    // ---- local obligations --------------------------------------------------------

    /** Back after a long silence (longer than a mailbox keeps anything): tell
     * every friend where we stand, so they can send what expired on the way. */
    private fun returned(now: Long) {
        val last = store.lastPass()
        if (last > 0 && now - last > RETURN_AFTER_MS) store.transaction {
            for ((peer, state) in store.incomingStates()) {
                if (state.complete) store.putIncoming(peer, state.copy(ackDue = true, ackSentAt = 0))
            }
        }
        store.setLastPass(now)
    }

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
        val peers = status.peers.associateBy { it.account }
        for (person in store.persons().filter { it.state == PersonState.REQUESTED }) {
            val native = peers[person.peer]
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
        val peers = status.peers.associateBy { it.account }
        for (person in store.persons().filter { it.state == PersonState.CONNECTED }) {
            val native = peers[person.peer] ?: continue
            if (native.ended) store.transaction {
                store.clearExchange(person.peer)
                store.setState(person.peer, PersonState.ENDED, now)
            }
        }
    }

    private suspend fun endings(host: MarmotHost, status: MarmotStatus) {
        val now = clock()
        val peers = status.peers.associateBy { it.account }
        for (person in store.persons()) {
            val native = peers[person.peer]
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
        if (person.state == PersonState.CONNECTED && message.type != "ack") alive(peer, now)
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

    /** A friend who confirmed nothing for longer than a mailbox keeps anything
     * may have lost what was sent meanwhile: hand them a snapshot. */
    private fun alive(peer: String, now: Long) {
        val member = store.member(peer) ?: return
        if (member.snapshotDue || member.ackedAt == 0L || now - member.ackedAt <= MAILBOX_MAX_AGE_MS) return
        val stream = store.stream(member.stream) ?: return
        if (member.ackedGeneration != stream.generation || member.ackedSequence != stream.sequence) store.requireSnapshot(peer)
    }

    private fun acknowledged(peer: String, m: ShareMessage, now: Long) {
        val member = store.member(peer) ?: return
        // An ack of an earlier snapshot or stream says nothing about the current one.
        if (m.stream != member.stream || m.assign != member.assign) return
        val stream = store.stream(member.stream) ?: return
        // A friend back after a long silence (VS3) and behind: what was sent
        // meanwhile may have expired on the way.
        val behind = m.gen < stream.generation || m.gen == stream.generation && m.seq < stream.sequence
        if (behind && member.ackedAt > 0 && now - member.ackedAt > MAILBOX_MAX_AGE_MS) {
            store.requireSnapshot(peer)
            return
        }
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
        store.putIncoming(peer, track(state, now))
    }

    /** Since when a gap is known: a state still being staged, messages waiting
     * for a missing one, or a count or digest that did not match (VS2). */
    private fun track(state: IncomingState, now: Long): IncomingState {
        val gap = !state.complete || state.pending.isNotEmpty() || state.mismatch
        return when {
            gap && state.incompleteSince == 0L -> state.copy(incompleteSince = now)
            !gap && state.incompleteSince != 0L -> state.copy(incompleteSince = 0)
            else -> state
        }
    }

    /** A state that matched count and digest, as of the friend's time [at]. */
    private fun verified(state: IncomingState, at: Long) = state.copy(mismatch = false, completeAt = maxOf(state.completeAt, at))

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
            expectedAt = m.at,
            staging = emptyMap(),
            ackDue = false,
            receivedAt = now,
            retired = ((state.retired - m.stream) + listOfNotNull(state.stream.takeIf { switched })).takeLast(MAX_RETIRED),
        )
        if (m.count == 0) {
            store.deleteRecords(peer)
            next = if (m.digest == SharingRecords.digest(emptyList())) verified(next.copy(sequence = m.seq, ackDue = true, ackSentAt = 0), m.at)
            else requestResync(next, now)
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

    /** A heartbeat at our own position: count and digest must match what we hold.
     * A match is worth a (daily) confirmation, so a quiet friendship stays confirmed. */
    private fun verify(peer: String, state: IncomingState, m: ShareMessage, now: Long): IncomingState {
        val local = store.records(peer)
        return if (local.size != m.count || SharingRecords.digest(local) != m.digest) requestResync(state, now)
        else verified(state.copy(ackDue = true), m.at)
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
        // A complete state is confirmed at once; deltas only once a day.
        return drain(peer, verified(state.copy(sequence = state.base, staging = emptyMap(), ackDue = true, ackSentAt = 0, receivedAt = now),
            state.expectedAt), now)
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
        return if (local.size != m.count || SharingRecords.digest(local) != m.digest) requestResync(next, now) else verified(next, m.at)
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
        val marked = state.copy(mismatch = true)
        val recentlyAsked = state.resyncGeneration == generation && (state.resyncAt == 0L || now - state.resyncAt < RESYNC_RETRY_MS)
        return if (recentlyAsked) marked else marked.copy(resyncGeneration = generation, resyncAt = 0)
    }

    // ---- replies (acks, resync requests) ---------------------------------------------------

    private suspend fun replies(host: MarmotHost, status: MarmotStatus) {
        val now = clock()
        val persons = store.persons().associateBy { it.peer }
        val active = status.peers.filter { it.active }.map { it.account }.toSet()
        for ((peer, start) in store.incomingStates()) {
            val person = persons[peer] ?: continue
            if (person.state != PersonState.CONNECTED || peer !in active) continue
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
            if (state.ackDue && state.complete && state.assign > 0 && (state.ackSentAt == 0L || now - state.ackSentAt >= ACK_INTERVAL_MS)) {
                val digest = SharingRecords.digest(store.records(peer))
                val message = ShareMessage(type = "ack", stream = state.stream, assign = state.assign, gen = state.generation,
                    seq = state.sequence, digest = digest)
                val token = "ack:${state.stream.take(8)}:a${state.assign}:g${state.generation}:s${state.sequence}:${digest.take(16)}"
                if (runCatching { host.send(peer, token, ShareProtocol.encode(message)) }.isSuccess) state = state.copy(ackDue = false, ackSentAt = now)
            }
            state = track(state, now)
            if (state != start) store.putIncoming(peer, state)
        }
    }

    // ---- sending -----------------------------------------------------------------------------

    /**
     * The audience a connected person belongs to: everyone whose effective rule
     * is the same shares a stream. The rule is the template (by id, so editing
     * it keeps the audience together), the person's own period and their active
     * category and record rules. "Never share", the age and the catalogue are
     * the same for everyone. Switched-off people share the empty stream.
     */
    private fun ruleKey(person: SharingPerson, policy: SharePolicy, now: Long): String {
        if (!person.outgoing) return OFF
        val rules = policy.persons[PeerId(person.peer)] ?: return OFF
        fun rule(r: ShareRule) = buildJsonObject {
            put("effect", r.effect.name)
            r.expiresAt?.let { put("until", it) }
        }
        val canonical = buildJsonObject {
            put("v", 1)
            put("template", rules.template.value)
            person.periodDays?.let { put("period", it) }
            put("categories", buildJsonArray {
                rules.categoryRules.filterValues { it.activeAt(now) }.entries.sortedBy { it.key.name }.forEach { (category, r) ->
                    add(buildJsonObject { put("category", category.name); put("rule", rule(r)) })
                }
            })
            put("records", buildJsonArray {
                rules.recordRules.filterValues { it.activeAt(now) }.entries
                    .sortedWith(compareBy({ it.key.record.value }, { it.key.category.name })).forEach { (key, r) ->
                        add(buildJsonObject { put("record", key.record.value); put("category", key.category.name); put("rule", rule(r)) })
                    }
            })
        }.toString()
        return SharingRecords.hash(canonical)
    }

    /** Every connected person is in exactly one stream: the one of their audience. */
    private fun join(policy: SharePolicy, now: Long) = store.transaction {
        val connected = store.persons().filter { it.state == PersonState.CONNECTED }.associateBy { it.peer }
        val members = store.members().associateBy { it.peer }
        val streams = store.streams().associateBy { it.id }.toMutableMap()
        for (member in store.members()) if (member.peer !in connected) store.clearMember(member.peer)
        for (person in connected.values) {
            val key = ruleKey(person, policy, now)
            val member = members[person.peer]
            val current = member?.let { streams[it.stream] ?: store.stream(it.stream) }
            if (current != null && current.ruleKey == key) continue
            val stream = store.streamByKey(StreamState.RULE, key) ?: StreamState.fresh(newStreamId(), key, now).also { store.putStream(it) }
            // Leaving a stream raises its epoch: from 0.2.7 its group key changes before the next message.
            if (current != null) current.copy(epoch = current.epoch + 1).also { store.putStream(it); streams[it.id] = it }
            store.putMember(StreamMember(person.peer, stream.id, member?.assign ?: 0, member?.assignedAt ?: 0, snapshotDue = true,
                ackedGeneration = 0, ackedSequence = -1, ackedAt = member?.ackedAt ?: 0, joinedAt = now))
        }
        store.dropEmptyStreams()
    }

    private suspend fun send(host: MarmotHost, stream: StreamState, native: Map<String, MarmotPeer>, policy: SharePolicy) {
        val now = clock()
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
                    broadcast += "${stream.id}:hb:g${stream.generation}:s${stream.sequence}:m${now / 60_000}" to
                        ShareProtocol.manifest(stream.id, 0, stream.generation, stream.sequence, scope, target, now)
                    next = stream.copy(manifestAt = now)
                }
            } else if (hold(stream, members, native, upserts, deletes, scope, target, now)) {
                return
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
                broadcast += "${stream.id}:g$generation:m" to ShareProtocol.manifest(stream.id, 0, generation, 0, scope, target, now)
                ShareProtocol.full(stream.id, 0, generation, 0, target).forEach { broadcast += "${stream.id}:g$generation:f${it.part}" to it }
            }
            next = stream.copy(generation = generation, sequence = 0, generationStartedAt = now, scope = scope,
                digest = SharingRecords.digest(target), count = target.size, manifestAt = now, fullRequired = false)
        }
        // Whatever goes out now carries what was held back.
        if (next.generation != stream.generation || next.sequence != stream.sequence) {
            next = next.copy(pendingSince = null, pendingAt = null, pendingDigest = null)
        }
        val advanced = next.generation != stream.generation || next.sequence != stream.sequence
        val restarted = full && stream.generation > 0
        val updated = mutableMapOf<String, StreamMember>()
        val failed = mutableSetOf<String>()
        var delivered = false
        val receivers = mutableListOf<StreamMember>()
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
            receivers += member
        }
        if (receivers.isNotEmpty()) {
            // Everyone gets the same text: one string, many friends, few native commits (B13).
            val encoded = broadcast.map { (token, message) -> token to ShareProtocol.encode(message) }
            val results = host.sendMany(receivers.flatMap { m -> encoded.map { (token, content) -> Triple(m.peer, token, content) } })
            val conflict = results.indexOfFirst { (it.exceptionOrNull() as? MarmotFailure)?.code == "token_conflict" }
            if (conflict >= 0) {
                // An earlier attempt with this token carried other content (a
                // crash between native admission and our commit): start over
                // with a generation no earlier attempt used.
                val message = broadcast[conflict % encoded.size].second
                store.putStream(stream.copy(generation = maxOf(stream.generation, message.gen), fullRequired = true, generationStartedAt = now))
                return
            }
            receivers.forEachIndexed { index, member ->
                if (encoded.indices.all { results[index * encoded.size + it].isSuccess }) delivered = true
                else {
                    failed += member.peer
                    updated[member.peer] = member.copy(snapshotDue = true)
                }
            }
        }
        val snapshots = members.map { updated[it.peer] ?: it }
            .filter { it.snapshotDue && native[it.peer]?.active == true && it.peer !in failed }
        // Nobody could take what moved: try again next time, as if nothing happened.
        if (broadcast.isNotEmpty() && !delivered && snapshots.isEmpty()) return
        // Snapshots differ per member (their number), so they go in groups of bounded size.
        val group = mutableListOf<Pair<StreamMember, List<Pair<String, String>>>>()
        var groupBytes = 0
        suspend fun flushSnapshots() {
            if (group.isEmpty()) return
            // The numbers are taken before anything goes out: after a crash in
            // the middle, the next snapshot uses a new one. Reusing one at
            // another position would make the friend ignore the newer state.
            group.forEach { (member, _) -> store.putMember(member.copy(assign = member.assign + 1)) }
            val results = host.sendMany(group.flatMap { (member, messages) -> messages.map { (token, content) -> Triple(member.peer, token, content) } })
            var at = 0
            for ((member, messages) in group) {
                val assign = member.assign + 1
                val complete = (at until at + messages.size).all { results[it].isSuccess }
                at += messages.size
                // A number once used is never used again, even when only part of it went out.
                updated[member.peer] = if (complete) member.copy(assign = assign, assignedAt = now, snapshotDue = false) else member.copy(assign = assign)
            }
            group.clear()
            groupBytes = 0
        }
        for (member in snapshots) {
            // A snapshot for this member alone, at the state every other member now has.
            val assign = member.assign + 1
            val prefix = "${stream.id}:a$assign:g${next.generation}:s${next.sequence}"
            val messages = (listOf("$prefix:m" to ShareProtocol.manifest(stream.id, assign, next.generation, next.sequence, next.scope, target, now)) +
                ShareProtocol.full(stream.id, assign, next.generation, next.sequence, target).map { "$prefix:f${it.part}" to it })
                .map { (token, message) -> token to ShareProtocol.encode(message) }
            group += member to messages
            groupBytes += messages.sumOf { it.second.length }
            if (groupBytes >= SNAPSHOT_GROUP_BYTES) flushSnapshots()
        }
        flushSnapshots()
        store.transaction {
            if (next != stream) store.putStream(next)
            if (advanced) store.putSent(stream.id, sent, targetMap)
            updated.values.forEach(store::putMember)
        }
    }

    /**
     * In the foreground, new and changed records wait until nothing changed for
     * [QUIET_MS], at most [HOLD_MAX_MS], or the app leaves the foreground: a
     * session with ten attempts becomes one or two messages instead of ten.
     * Deletions, a narrower scope and members waiting for a snapshot never wait.
     */
    private fun hold(
        stream: StreamState,
        members: List<StreamMember>,
        native: Map<String, MarmotPeer>,
        upserts: List<SharingRecord>,
        deletes: List<String>,
        scope: ShareScope,
        target: List<SharingRecord>,
        now: Long,
    ): Boolean {
        if (!foreground || deletes.isNotEmpty() || scope != stream.scope || upserts.isEmpty()) return false
        if (members.any { it.snapshotDue && native[it.peer]?.active == true }) return false
        val digest = SharingRecords.digest(target)
        val since = stream.pendingSince ?: now
        val changedAt = if (digest != stream.pendingDigest) now else stream.pendingAt ?: now
        if (now - changedAt >= QUIET_MS || now - since >= HOLD_MAX_MS) return false
        val held = stream.copy(pendingSince = since, pendingAt = changedAt, pendingDigest = digest)
        if (held != stream) store.putStream(held)
        return true
    }

    companion object {
        const val MAX_PENDING = 64
        const val MAX_RETIRED = 8
        /** Snapshots for several members go out together up to about this much text. */
        const val SNAPSHOT_GROUP_BYTES = 2_000_000
        /** The audience of everyone who receives nothing from us. */
        const val OFF = "off"
        const val INVITE_FIRST_RETRY_MS = 15_000L
        const val INVITE_RETRY_MS = 10 * 60_000L
        const val RESYNC_RETRY_MS = 60 * 60_000L
        const val STALL_MS = 10 * 60_000L
        const val HEARTBEAT_MS = 24 * 60 * 60_000L
        /** A friend confirms deltas and heartbeats at most this often; complete states at once. */
        const val ACK_INTERVAL_MS = 24 * 60 * 60_000L
        const val QUIET_MS = 60_000L
        const val HOLD_MAX_MS = 120_000L
        /** How long a mailbox keeps a frame (owner question O1; the native expiry must match). */
        const val MAILBOX_MAX_AGE_MS = 14 * 24 * 60 * 60_000L
        /** Silence after which an installation tells its friends where it stands (VS3). */
        const val RETURN_AFTER_MS = MAILBOX_MAX_AGE_MS - 2 * 24 * 60 * 60_000L

        private val random = java.security.SecureRandom()

        /** 128 random bits as 32 lowercase hex: a stream id that says nothing about its audience. */
        fun randomStreamId(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    }
}
