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
                reduce()
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

        relayPool.subscribe(filter).collect { json ->
            val event = runCatching { Event.fromJson(json) }.getOrNull() ?: return@collect
            if (!acceptsBody(event)) return@collect
            if (entries.containsKey(event.id)) return@collect
            val parsedIntent = CompetitionProtocol.parseIntent(
                event.toCompetitionEvent(), competition, organizerPubkey, nowSeconds(),
            )
            if (parsedIntent is CompetitionProtocol.ParsedIntent.Valid) {
                val key = "${parsedIntent.pubkey}:${parsedIntent.op}"
                val old = intents[key]
                if (old == null || parsedIntent.createdAt >= old.createdAt) intents[key] = parsedIntent
                reduce()
                emit(entries.size)
                return@collect
            }
            if (event.pubKey != competition.authority) return@collect
            when (
                val parsed = CompetitionProtocol.parseLogEntry(
                    event.toCompetitionEvent(), competition, organizerPubkey, nowSeconds(),
                )
            ) {
                is CompetitionProtocol.ParsedLogEntry.Invalid -> {
                    // Recorded, not swallowed. An entry we cannot read is
                    // exactly the thing a competition screen must not hide.
                    Log.w(TAG, "log entry rejected: ${parsed.error}")
                    if (parsed.needsUpgrade) _snapshot.update { it.copy(problem = Problem.NEEDS_UPGRADE) }
                }
                is CompetitionProtocol.ParsedLogEntry.Valid -> {
                    entries[event.id] = CompetitionReducer.Chained(
                        parsed.entry, parsed.eventId, parsed.createdAt,
                    )
                    reduce()
                    emit(entries.size)
                }
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

    private fun reduce() {
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
                loading = false,
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
    }
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
