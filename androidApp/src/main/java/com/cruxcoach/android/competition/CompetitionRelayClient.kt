package com.cruxcoach.android.competition

import android.util.Log
import com.cruxcoach.android.nostr.NostrEventPolicy
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionEvent
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.CompetitionReducer
import com.cruxcoach.domain.competition.CompetitionScoring
import com.cruxcoach.domain.competition.CompetitionState
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

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
class CompetitionRelayClient @Inject constructor(
    private val relayPool: NostrRelayPool,
) {

    /** What a screen needs, all of it derived, none of it stored twice. */
    data class Snapshot(
        val competition: Competition? = null,
        val competitionEventId: String? = null,
        val organizerPubkey: String? = null,
        val state: CompetitionState? = null,
        val standings: List<CompetitionScoring.Standing> = emptyList(),
        val chainBreakAt: Int? = null,
        val loading: Boolean = false,
        val problem: Problem? = null,
        val entryCount: Int = 0,
        val usesDevelopmentRelay: Boolean = false,
        val pendingIntents: List<CompetitionProtocol.ParsedIntent.Valid> = emptyList(),
        /** Local receipt time, never part of the reduced competition state. */
        val lastSyncedAt: Long = 0,
    ) {
        /** Whether the record is complete and unforked enough to show standings. */
        val trustworthy: Boolean
            get() = state != null && state.chainComplete && !state.forkDetected
    }

    /** Named so the UI can say something specific instead of "error". */
    enum class Problem { NOT_FOUND, UNREACHABLE, INVALID, NEEDS_UPGRADE }

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()
    val connectedRelayCount: StateFlow<Int> = relayPool.connectedRelayCount

    private val entries = linkedMapOf<String, CompetitionReducer.Chained>()
    private val intents = linkedMapOf<String, CompetitionProtocol.ParsedIntent.Valid>()
    /** Immutable signed definition used as the log chain's root. */
    private var definitionCompetition: Competition? = null

    /**
     * Fetch the competition definition.
     *
     * `max(created_at)` decides, never first answer: a relay that missed the
     * last edit still replies, and replying first does not make it current.
     * That is the same rule the manifest path learned the hard way.
     */
    suspend fun load(organizerPubkey: String, compId: String, nowSeconds: Long): Boolean {
        _snapshot.update { Snapshot(loading = true) }
        entries.clear()
        intents.clear()
        definitionCompetition = null

        val filter = """{"kinds":[${CompetitionProtocol.KIND}],""" +
            """"authors":["$organizerPubkey"],""" +
            """"#d":["${CompetitionProtocol.compDTag(compId)}"],"limit":4}"""

        var newest: Event? = null
        runCatching {
            // Discovery may have delivered this exact addressable event only
            // moments ago. Its process-wide event-id cache is correct for live
            // subscriptions, but an explicit address lookup must be allowed to
            // read the same stored event again; otherwise tapping a competition
            // from the discovery list deterministically turns it into NOT_FOUND.
            relayPool.fetchStored(filter).collect { json ->
                val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return@collect
                if (!accepts(event, organizerPubkey)) return@collect
                if (newest == null || event.createdAt > newest!!.createdAt) newest = event
            }
        }.onFailure { Log.w(TAG, "competition fetch failed", it) }

        val found = newest
        if (found == null) {
            _snapshot.update { Snapshot(problem = Problem.NOT_FOUND) }
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

        // A normal live subscription goes through NostrRelayPool's process-wide
        // event-id cache. That cache is desirable for feeds, but is not durable
        // competition state: after load() clears this client's in-memory log,
        // a restarted collector would suppress every event observed by the old
        // collector and leave the reducer at "waiting for entry 1". Always
        // hydrate the authority chain explicitly, in d-tag pages, before the
        // live subscription becomes the source of truth. Exact d-tags also
        // avoid a relay's silently clamped result limit dropping the beginning
        // of a long chain.
        backfillLog(competition, organizerPubkey, address, nowSeconds)
        recoverBoundedGap(competition, organizerPubkey, address, nowSeconds)

        // Intents are not part of the authority chain, but restoring them makes
        // the organizer view complete after a screen/process restart. The live
        // subscription below closes the EOSE-to-follow race.
        withTimeoutOrNull(BACKFILL_TIMEOUT_MS) {
            relayPool.fetchStored(intentFilter(address)).collect { json ->
                ingest(json, competition, organizerPubkey, nowSeconds())
            }
        }
        reduce(loading = false)
        emit(entries.size)

        // Bypass the global cache here as well. This REQ first replays whatever
        // was published between the final backfill EOSE and this subscription,
        // then remains open for genuinely live events. Local maps dedupe relay
        // duplicates without confusing transport history with client state.
        relayPool.subscribe(filter, skipDedup = true).collect { json ->
            val countBefore = entries.size
            val changed = ingest(json, competition, organizerPubkey, nowSeconds())
            // Run this even for a duplicate replay: the initial exact page may
            // have exposed a hole, while the broad live REQ is now replaying
            // already-present entries above it.
            recoverBoundedGap(competition, organizerPubkey, address, nowSeconds)
            if (changed || entries.size != countBefore) {
                emit(entries.size)
            }
        }
    }

    private suspend fun backfillLog(
        competition: Competition,
        organizerPubkey: String,
        address: String,
        nowSeconds: () -> Long,
    ) {
        var firstSeq = 1
        while (firstSeq <= MAX_LOG_SEQUENCE) {
            val wanted = logPageDTags(competition.compId, firstSeq)
            val completed = withTimeoutOrNull(BACKFILL_TIMEOUT_MS) {
                relayPool.fetchStored(logPageFilter(competition.authority, address, wanted)).collect { json ->
                    ingest(json, competition, organizerPubkey, nowSeconds())
                }
                true
            } ?: false
            if (!completed) {
                Log.w(TAG, "stored log backfill timed out at seq $firstSeq; live REQ will continue recovery")
                break
            }
            val present = entrySequences()
            val firstMissing = wanted.indices.firstOrNull { wantedIndex ->
                firstSeq + wantedIndex !in present
            }
            if (firstMissing != null) break
            firstSeq += wanted.size
        }
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
                relayPool.fetchStored(
                    logPageFilter(
                        competition.authority,
                        address,
                        listOf(CompetitionProtocol.logDTag(competition.compId, missing)),
                    ),
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
            val key = "${parsed.pubkey}:${parsed.op}"
            val old = intents[key]
            if (old == null || parsed.createdAt >= old.createdAt) intents[key] = parsed
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

    private fun reduce(loading: Boolean = false) {
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
                entryCount = entries.size,
                loading = loading,
                lastSyncedAt = System.currentTimeMillis() / 1000,
                pendingIntents = unansweredIntents(),
            )
        }
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
        private const val LOG_PAGE_SIZE = 100
        private const val MAX_LOG_SEQUENCE = 999_999
        private const val MAX_GAP_RECOVERY_PER_EVENT = LOG_PAGE_SIZE
        private const val BACKFILL_TIMEOUT_MS = 12_000L

        internal fun logPageDTags(compId: String, firstSeq: Int): List<String> =
            (firstSeq until minOf(firstSeq + LOG_PAGE_SIZE, MAX_LOG_SEQUENCE + 1))
                .map { CompetitionProtocol.logDTag(compId, it) }

        internal fun logPageFilter(authority: String, address: String, dTags: List<String>): String =
            """{"kinds":[${CompetitionProtocol.KIND}],"authors":["$authority"],"#a":["$address"],"#d":[${dTags.joinToString(",") { "\"$it\"" }}],"limit":${dTags.size}}"""

        internal fun intentFilter(address: String): String =
            """{"kinds":[${CompetitionProtocol.KIND}],"#a":["$address"],"#l":["intent"],"limit":1000}"""

        internal fun boundedMissingSequence(sequences: Set<Int>): Int? {
            val max = sequences.maxOrNull() ?: return null
            return (1..max).firstOrNull { it !in sequences }
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
