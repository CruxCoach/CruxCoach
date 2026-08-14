package com.cruxcoach.android.competition

import android.os.SystemClock
import android.util.Log
import com.cruxcoach.android.nostr.NostrEventPolicy
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.RelaySubscriptionIntegrityException
import com.cruxcoach.android.nostr.model.RelayConfig
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionEvent
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.CompetitionReducer
import com.cruxcoach.domain.competition.CompetitionScoring
import com.cruxcoach.domain.competition.CompetitionState
import com.cruxcoach.domain.competition.LogEntry
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive

internal class CompetitionBackfillRetryGate(
    private val intervalMs: Long,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private var nextAttemptAt = Long.MIN_VALUE

    fun recordAttempt() {
        val nowMs = elapsedRealtimeMs()
        nextAttemptAt = if (nowMs > Long.MAX_VALUE - intervalMs) Long.MAX_VALUE else nowMs + intervalMs
    }

    fun claim(): Boolean {
        val nowMs = elapsedRealtimeMs()
        if (nowMs < nextAttemptAt) return false
        recordAttempt()
        return true
    }
}

internal fun staleAfterRelayLoss(
    snapshot: CompetitionRelayClient.Snapshot,
    allReadRelaysConnected: Boolean,
): CompetitionRelayClient.Snapshot = if (
    allReadRelaysConnected || (!snapshot.historyComplete && !snapshot.intentHistoryComplete)
) snapshot else snapshot.copy(
    historyComplete = false,
    intentHistoryComplete = false,
    pendingIntents = emptyList(),
)

internal fun competitionRelayScope(
    requiredUrls: List<String>,
    userRelays: List<RelayConfig>,
    limit: Int = 8,
): List<RelayConfig> {
    val merged = linkedMapOf<String, RelayConfig>()
    requiredUrls.filter(CompetitionProtocol::isAllowedRelayUrl).forEach { url ->
        merged[url] = RelayConfig(url = url, read = true, write = true)
    }
    userRelays.forEach { relay ->
        if (!CompetitionProtocol.isAllowedRelayUrl(relay.url)) return@forEach
        val prior = merged[relay.url]
        merged[relay.url] = if (prior == null) relay else prior.copy(
            read = prior.read || relay.read,
            write = prior.write || relay.write,
        )
    }
    return merged.values.take(limit)
}

/**
 * Reads a competition off the relays and reduces it.
 *
 * The trust boundary is the same one every other consumer in this app uses: a
 * relay can hand back a validly signed envelope whose body it swapped, so both
 * `verifySignature()` and `verifyId()` have to pass before any field is read
 * (`docs/nostr-architecture.md` §15). Everything after that is the shared
 * reducer, which the website runs too.
 */
@Singleton
class CompetitionRelayClient internal constructor(
    private val relayPool: NostrRelayPool,
    private val scopeResolver: (List<String>, List<RelayConfig>) -> List<RelayConfig>,
) {
    @Inject constructor(relayPool: NostrRelayPool) : this(relayPool, ::competitionRelayScope)

    /** What a screen needs, all of it derived, none of it stored twice. */
    data class Snapshot(
        val competition: Competition? = null,
        val competitionEventId: String? = null,
        val organizerPubkey: String? = null,
        val state: CompetitionState? = null,
        val standings: List<CompetitionScoring.Standing> = emptyList(),
        val chainBreakAt: Int? = null,
        /** True only after exact d-tag paging reached the first absent sequence. */
        val historyComplete: Boolean = false,
        /** True only after every configured relay completed every intent lane. */
        val intentHistoryComplete: Boolean = false,
        val loading: Boolean = false,
        val problem: Problem? = null,
        val entryCount: Int = 0,
        val usesDevelopmentRelay: Boolean = false,
        val pendingIntents: List<CompetitionProtocol.ParsedIntent.Valid> = emptyList(),
        /** Parsed authority entries for correction/audit UI; state still comes only from the reducer. */
        val logEntries: List<LogEntry> = emptyList(),
        /** Local receipt time, never part of the reduced competition state. */
        val lastSyncedAt: Long = 0,
    ) {
        /** Whether the record is complete and unforked enough to show standings. */
        val trustworthy: Boolean
            get() = historyComplete && state != null && state.chainComplete && !state.forkDetected
    }

    /** Named so the UI can say something specific instead of "error". */
    enum class Problem { NOT_FOUND, UNREACHABLE, INVALID, NEEDS_UPGRADE }

    data class OwnIntent(val trustworthy: Boolean, val intent: CompetitionProtocol.ParsedIntent.Valid? = null)

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    val connectedRelayUrls: StateFlow<Set<String>> = relayPool.connectedRelayUrls

    private val entries = linkedMapOf<String, CompetitionReducer.Chained>()
    private val intents = linkedMapOf<String, CompetitionProtocol.ParsedIntent.Valid>()
    /** Immutable signed definition used as the log chain's root. */
    private var definitionCompetition: Competition? = null
    private var relayScope: List<RelayConfig>? = null

    /**
     * Fetch the competition definition.
     *
     * `max(created_at)` decides, never first answer: a relay that missed the
     * last edit still replies, and replying first does not make it current.
     * That is the same rule the manifest path learned the hard way.
     */
    suspend fun load(
        organizerPubkey: String,
        compId: String,
        nowSeconds: Long,
        relayHints: List<String> = emptyList(),
    ): Boolean {
        _snapshot.update { Snapshot(loading = true) }
        entries.clear()
        intents.clear()
        definitionCompetition = null
        relayScope = null

        val filter = """{"kinds":[${CompetitionProtocol.KIND}],""" +
            """"authors":["$organizerPubkey"],""" +
            """"#d":["${CompetitionProtocol.compDTag(compId)}"],"limit":4}"""

        var newest: Event? = null
        val definitionHistoryComplete = runCatching {
            // Discovery may have delivered this exact addressable event only
            // moments ago. Its process-wide event-id cache is correct for live
            // subscriptions, but an explicit address lookup must be allowed to
            // read the same stored event again; otherwise tapping a competition
            // from the discovery list deterministically turns it into NOT_FOUND.
            relayPool.fetchStoredComplete(
                filter,
                scopeResolver(relayHints, relayPool.readRelays() + relayPool.writeRelays()),
            ).collect { json ->
                val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return@collect
                if (!accepts(event, organizerPubkey)) return@collect
                if (newest == null || event.createdAt > newest!!.createdAt) newest = event
            }
        }.onFailure { Log.w(TAG, "competition fetch failed", it) }.isSuccess

        val found = newest
        if (found == null) {
            _snapshot.update {
                Snapshot(problem = if (definitionHistoryComplete) Problem.NOT_FOUND else Problem.UNREACHABLE)
            }
            return false
        }

        return when (val parsed = CompetitionProtocol.parseCompetition(found.toCompetitionEvent(), nowSeconds)) {
            is CompetitionProtocol.ParsedCompetition.Invalid -> {
                Log.w(TAG, "competition rejected: ${parsed.error}")
                _snapshot.update {
                    Snapshot(problem = if (parsed.needsUpgrade) Problem.NEEDS_UPGRADE else Problem.INVALID)
                }
                false
            }
            is CompetitionProtocol.ParsedCompetition.Valid -> {
                definitionCompetition = parsed.competition
                relayScope = scopeResolver(
                    parsed.competition.relays,
                    relayPool.readRelays() + relayPool.writeRelays(),
                )
                _snapshot.update {
                    Snapshot(
                        competition = parsed.competition,
                        competitionEventId = parsed.eventId,
                        organizerPubkey = parsed.organizerPubkey,
                        usesDevelopmentRelay = CompetitionProtocol.usesDevelopmentRelay(parsed.competition.relays),
                        lastSyncedAt = nowSeconds,
                    )
                }
                // The definition alone is not a usable participant snapshot.
                // Keep the screen in its loading state until follow() has
                // completed the explicit stored-log backfill; otherwise a
                // cold client briefly looks unregistered at seq 0.
                reduce(loading = true)
                true
            }
        }
    }

    /**
     * Follow the authority's log.
     *
     * One subscription for the whole competition. Twenty concurrent
     * subscriptions is the tightest relay budget observed, and a phone with two
     * competitions open would otherwise start losing them silently.
     */
    fun follow(nowSeconds: () -> Long): Flow<Int> = kotlinx.coroutines.flow.flow {
        val current = _snapshot.value
        val competition = current.competition ?: return@flow
        val organizerPubkey = current.organizerPubkey ?: return@flow
        val address = CompetitionProtocol.competitionAddress(organizerPubkey, competition.compId)
        val filter = """{"kinds":[${CompetitionProtocol.KIND}],"#a":["$address"]}"""

        while (currentCoroutineContext().isActive) try {
            coroutineScope {
            // Start collecting the cache-bypassing stored+live REQ
            // undispatched, before any exact history query can reach EOSE.
            // Its replay is buffered and reduced after hydration, closing the
            // EOSE-to-follow race without concurrently mutating reducer maps.
            val liveEvents = Channel<String>(capacity = LIVE_BUFFER_CAPACITY)
            val liveJob = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    relayPool.subscribe(
                        filter,
                        skipDedup = true,
                        failOnRelayError = true,
                        relays = relayScope,
                    ).collect(liveEvents::send)
                } catch (cancelled: CancellationException) {
                    val integrity = generateSequence<Throwable>(cancelled) { it.cause }
                        .filterIsInstance<RelaySubscriptionIntegrityException>()
                        .firstOrNull()
                    if (integrity == null) throw cancelled
                    liveEvents.close(integrity)
                    return@launch
                } catch (failure: Throwable) {
                    liveEvents.close(failure)
                    return@launch
                } finally {
                    liveEvents.close()
                }
            }
            try {
                reduce(loading = true, historyComplete = false, intentHistoryComplete = false)
                val retryGate = CompetitionBackfillRetryGate(RECOVERY_RETRY_INTERVAL_MS)
                retryGate.recordAttempt()
                var historyComplete = backfillLog(competition, organizerPubkey, address, nowSeconds)
                recoverBoundedGap(competition, organizerPubkey, address, nowSeconds)

                // Intents are not authority state, but a complete inbox is
                // needed before organizer/public request projections are used.
                val intentHistoryComplete = runCatching {
                    withTimeoutOrNull(BACKFILL_TIMEOUT_MS) {
                        coroutineScope {
                            intentFilters(address).map { intentFilter ->
                                async {
                                    relayPool.fetchStoredComplete(intentFilter, relayScope).collect { json ->
                                        ingest(json, competition, organizerPubkey, nowSeconds())
                                    }
                                }
                            }.awaitAll()
                        }
                        true
                    } ?: false
                }.onFailure { Log.w(TAG, "stored intent backfill failed", it) }.getOrDefault(false)
                reduce(
                    loading = false,
                    historyComplete = historyComplete,
                    intentHistoryComplete = intentHistoryComplete,
                )
                emit(entries.size)

                for (json in liveEvents) {
                    val countBefore = entries.size
                    val changed = ingest(json, competition, organizerPubkey, nowSeconds())
                    // A broad replay may expose an internal hole. Exact
                    // one-sequence recovery is bounded independently.
                    recoverBoundedGap(competition, organizerPubkey, address, nowSeconds)
                    // A failed full walk is expensive. At most one collector
                    // can run here, and replay bursts may request only one retry
                    // per interval; Reload remains an immediate manual retry.
                    if (!historyComplete && retryGate.claim()) {
                        historyComplete = backfillLog(competition, organizerPubkey, address, nowSeconds)
                        reduce(loading = false, historyComplete = historyComplete)
                    }
                    if (changed || entries.size != countBefore) emit(entries.size)
                }
            } finally {
                liveJob.cancelAndJoin()
                liveEvents.cancel()
            }
            }
            return@flow
        } catch (failure: RelaySubscriptionIntegrityException) {
            Log.w(TAG, "strict live subscription lost integrity; recovering", failure)
            reduce(loading = false, historyComplete = false, intentHistoryComplete = false)
            delay(RECOVERY_RESTART_DELAY_MS)
        }
    }

    private suspend fun backfillLog(
        competition: Competition,
        organizerPubkey: String,
        address: String,
        nowSeconds: () -> Long,
    ): Boolean {
        var firstSeq = 1
        while (firstSeq <= MAX_LOG_SEQUENCE) {
            val wanted = logPageDTags(competition.compId, firstSeq)
            val completed = withTimeoutOrNull(BACKFILL_TIMEOUT_MS) {
                relayPool.fetchStoredComplete(
                    logPageFilter(competition.authority, address, wanted), relayScope,
                ).collect { json ->
                    ingest(json, competition, organizerPubkey, nowSeconds())
                }
                true
            } ?: false
            if (!completed) {
                Log.w(TAG, "stored log backfill timed out at seq $firstSeq; live REQ will continue recovery")
                return false
            }
            val present = entrySequences()
            val firstMissing = wanted.indices.firstOrNull { wantedIndex ->
                firstSeq + wantedIndex !in present
            }
            if (firstMissing != null) return true
            firstSeq += wanted.size
        }
        return false
    }

    /** Fetch a known internal hole without mistaking an absent next head for a gap. */
    private suspend fun recoverBoundedGap(
        competition: Competition,
        organizerPubkey: String,
        address: String,
        nowSeconds: () -> Long,
    ) {
        var attempts = 0
        while (attempts < MAX_GAP_RECOVERY_PER_EVENT) {
            val missing = boundedMissingSequence(entrySequences()) ?: return
            val completed = withTimeoutOrNull(BACKFILL_TIMEOUT_MS) {
                relayPool.fetchStoredComplete(
                    logPageFilter(
                        competition.authority,
                        address,
                        listOf(CompetitionProtocol.logDTag(competition.compId, missing)),
                    ), relayScope,
                ).collect { json -> ingest(json, competition, organizerPubkey, nowSeconds()) }
                true
            } ?: false
            if (!completed || missing !in entrySequences()) return
            attempts++
        }
    }

    /**
     * Parse one already signature-checked competition event by its d-tag type.
     * Snapshots/results intentionally do not fall through to log parsing: they
     * are optional documents, not evidence that the participant is absent.
     */
    private fun ingest(
        json: String,
        competition: Competition,
        organizerPubkey: String,
        nowSeconds: Long,
    ): Boolean {
        val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return false
        if (!acceptsBody(event)) return false
        val dTag = event.tags.firstOrNull { it.firstOrNull() == "d" }?.getOrNull(1)
        val classified = CompetitionProtocol.parseDTag(dTag) ?: return false
        if (classified.compId != competition.compId) return false

        if (classified.kind == "intent") {
            val parsed = CompetitionProtocol.parseIntent(
                event.toCompetitionEvent(), competition, organizerPubkey, nowSeconds,
            )
            if (parsed !is CompetitionProtocol.ParsedIntent.Valid) return false
            val key = intentLaneKey(parsed)
            val old = intents[key]
            if (shouldReplaceIntent(old, parsed)) intents[key] = parsed
            reduce(loading = _snapshot.value.loading)
            return true
        }
        if (classified.kind != "log" || event.pubKey != competition.authority) return false
        if (entries.containsKey(event.id)) return false
        return when (
            val parsed = CompetitionProtocol.parseLogEntry(
                event.toCompetitionEvent(), competition, organizerPubkey, nowSeconds,
            )
        ) {
            is CompetitionProtocol.ParsedLogEntry.Invalid -> {
                Log.w(TAG, "log entry rejected: ${parsed.error}")
                if (parsed.needsUpgrade) _snapshot.update { it.copy(problem = Problem.NEEDS_UPGRADE) }
                false
            }
            is CompetitionProtocol.ParsedLogEntry.Valid -> {
                entries[event.id] = CompetitionReducer.Chained(parsed.entry, parsed.eventId, parsed.createdAt)
                reduce(loading = _snapshot.value.loading)
                true
            }
        }
    }

    /** Apply a locally published event immediately, so the UI does not wait for the echo. */
    fun ingestOwn(event: Event, nowSeconds: Long) {
        val current = _snapshot.value
        val competition = definitionCompetition ?: return
        val organizerPubkey = current.organizerPubkey ?: return
        val parsed = CompetitionProtocol.parseLogEntry(
            event.toCompetitionEvent(), competition, organizerPubkey, nowSeconds,
        )
        if (parsed is CompetitionProtocol.ParsedLogEntry.Valid) {
            entries[event.id] = CompetitionReducer.Chained(parsed.entry, parsed.eventId, parsed.createdAt)
            reduce()
        }
    }

    /** A dropped read relay invalidates every projection until exact recovery. */
    fun connectionChanged(connectedUrls: Set<String> = relayPool.connectedRelayUrls.value) {
        _snapshot.update {
            staleAfterRelayLoss(
                it,
                relayPool.allReadRelaysConnected(
                    relayScope ?: relayPool.readRelays(), connectedUrls,
                ),
            )
        }
    }

    fun connectedCompetitionRelayCount(connectedUrls: Set<String>): Int =
        (relayScope ?: relayPool.readRelays()).count { it.read && it.url in connectedUrls }

    /** Restore one replaceable personal request without relying on a capped broad history query. */
    suspend fun loadOwnIntent(
        competition: Competition,
        organizerPubkey: String,
        pubkey: String,
        op: String,
        nonce: String,
        nowSeconds: Long,
    ): OwnIntent {
        var newest: CompetitionProtocol.ParsedIntent.Valid? = null
        val complete = runCatching {
            withTimeoutOrNull(BACKFILL_TIMEOUT_MS) {
                relayPool.fetchStoredComplete(
                    ownIntentFilter(
                        competition.compId, competition.authority, organizerPubkey, pubkey, op, nonce,
                    ),
                    scopeResolver(
                        competition.relays,
                        relayPool.readRelays() + relayPool.writeRelays(),
                    ),
                )
                    .collect { json ->
                        val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return@collect
                        if (!acceptsBody(event) || event.pubKey != pubkey) return@collect
                        val parsed = CompetitionProtocol.parseIntent(
                            event.toCompetitionEvent(), competition, organizerPubkey, nowSeconds,
                        ) as? CompetitionProtocol.ParsedIntent.Valid ?: return@collect
                        if (parsed.op != op) return@collect
                        if (shouldReplaceIntent(newest, parsed)) newest = parsed
                    }
                true
            } ?: false
        }.getOrDefault(false)
        return OwnIntent(complete, newest.takeIf { complete })
    }

    private fun reduce(
        loading: Boolean = false,
        historyComplete: Boolean = _snapshot.value.historyComplete,
        intentHistoryComplete: Boolean = _snapshot.value.intentHistoryComplete,
    ) {
        val current = _snapshot.value
        val competition = definitionCompetition ?: return
        val rootId = current.competitionEventId ?: return
        val reduction = CompetitionReducer.reduce(competition, rootId, entries.values.toList())
        _snapshot.update {
            it.copy(
                state = reduction.state,
                competition = reduction.effectiveCompetition,
                standings = CompetitionScoring.standings(reduction.state, reduction.effectiveCompetition),
                chainBreakAt = reduction.chainBreakAt,
                historyComplete = historyComplete,
                intentHistoryComplete = intentHistoryComplete,
                entryCount = entries.size,
                loading = loading,
                lastSyncedAt = System.currentTimeMillis() / 1000,
                pendingIntents = if (intentHistoryComplete) unansweredIntents() else emptyList(),
                logEntries = reduction.chosenEntries,
            )
        }
    }

    /** Full-history preview for operations such as corrections that rewrite an earlier effect. */
    internal fun previewAuthorityEntry(entry: LogEntry): CompetitionState? {
        val competition = definitionCompetition ?: return null
        val rootId = _snapshot.value.competitionEventId ?: return null
        val candidate = CompetitionReducer.Chained(
            entry = entry,
            eventId = "preview-${entry.seq}",
            createdAt = entry.at,
        )
        return CompetitionReducer.reduce(
            competition,
            rootId,
            entries.values.toList() + candidate,
        ).state
    }

    /**
     * Both checks, in this order, before any field is read.
     *
     * `verifySignature()` alone is not enough: it authenticates the wire id, and
     * only recomputing the id from the body binds that id to what we are about
     * to parse.
     */
    private fun accepts(event: Event, expectedAuthor: String): Boolean {
        val signatureValid = runCatching { event.verifySignature() }.getOrDefault(false)
        val idValid = signatureValid && runCatching { event.verifyId() }.getOrDefault(false)
        return NostrEventPolicy.accepts(
            actualPubkey = event.pubKey,
            actualKind = event.kind,
            expectedPubkey = expectedAuthor,
            expectedKind = CompetitionProtocol.KIND,
            signatureValid = signatureValid,
            idValid = idValid,
        )
    }

    private fun acceptsBody(event: Event): Boolean {
        val signatureValid = runCatching { event.verifySignature() }.getOrDefault(false)
        return signatureValid && runCatching { event.verifyId() }.getOrDefault(false) &&
            event.kind == CompetitionProtocol.KIND
    }

    private fun unansweredIntents(): List<CompetitionProtocol.ParsedIntent.Valid> {
        val answeredIds = entries.values.mapNotNull { chained ->
            (chained.entry.data["intent_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        }.toSet()
        return intents.values.filter { it.eventId !in answeredIds }.sortedBy { it.createdAt }
    }

    companion object {
        private const val TAG = "CompetitionRelay"
        // A relay that clamps every response to twenty events can still return
        // one event for every exact d-tag in the page. The larger filter limit
        // below leaves room for fork evidence at a sequence.
        private const val LOG_PAGE_SIZE = 20
        private const val MAX_LOG_SEQUENCE = 999_999
        private const val MAX_GAP_RECOVERY_PER_EVENT = LOG_PAGE_SIZE
        private const val BACKFILL_TIMEOUT_MS = 12_000L
        private const val RECOVERY_RETRY_INTERVAL_MS = 15_000L
        private const val RECOVERY_RESTART_DELAY_MS = 1_000L
        internal const val LIVE_BUFFER_CAPACITY = 256

        internal fun logPageDTags(compId: String, firstSeq: Int): List<String> =
            (firstSeq until minOf(firstSeq + LOG_PAGE_SIZE, MAX_LOG_SEQUENCE + 1))
                .map { CompetitionProtocol.logDTag(compId, it) }

        internal fun logPageFilter(authority: String, address: String, dTags: List<String>): String =
            """{"kinds":[${CompetitionProtocol.KIND}],"authors":["$authority"],"#a":["$address"],"#d":[${dTags.joinToString(",") { "\"$it\"" }}],"limit":100}"""

        internal fun intentFilters(address: String): List<String> = CompetitionProtocol.INTENT_OPS.map { op ->
            """{"kinds":[${CompetitionProtocol.KIND}],"#a":["$address"],"#l":["intent"],"#op":["$op"],"limit":1000}"""
        }

        internal fun ownIntentFilter(
            compId: String,
            authority: String,
            organizerPubkey: String,
            pubkey: String,
            op: String,
            nonce: String,
        ): String {
            val address = CompetitionProtocol.competitionAddress(organizerPubkey, compId)
            val dTag = CompetitionProtocol.intentDTag(compId, pubkey, nonce)
            return """{"kinds":[${CompetitionProtocol.KIND}],"authors":["$pubkey"],"#d":["$dTag"],"#a":["$address"],"#p":["$authority"],"#op":["$op"],"limit":8}"""
        }

        internal fun boundedMissingSequence(sequences: Set<Int>): Int? {
            val max = sequences.maxOrNull() ?: return null
            return (1..max).firstOrNull { it !in sequences }
        }

        internal fun shouldReplaceIntent(
            old: CompetitionProtocol.ParsedIntent.Valid?,
            candidate: CompetitionProtocol.ParsedIntent.Valid,
        ): Boolean = old == null || candidate.createdAt > old.createdAt ||
            (candidate.createdAt == old.createdAt && candidate.eventId > old.eventId)

        /** Prize requests are independently replaceable; one winner may hold several prizes. */
        internal fun intentLaneKey(intent: CompetitionProtocol.ParsedIntent.Valid): String {
            val prizeLane = if (intent.op in listOf("prize_claim", "prize_receipt")) {
                ":${(intent.data["prize_id"] as? JsonPrimitive)?.content.orEmpty()}"
            } else ""
            return "${intent.pubkey}:${intent.op}$prizeLane"
        }
    }

    private fun entrySequences(): Set<Int> = entries.values.mapTo(linkedSetOf()) { it.entry.seq }
}

/** Quartz's event, reduced to the shape the shared protocol layer speaks. */
fun Event.toCompetitionEvent() = CompetitionEvent(
    id = id,
    pubkey = pubKey,
    createdAt = createdAt,
    kind = kind,
    tags = tags.map { it.toList() },
    content = content,
)
