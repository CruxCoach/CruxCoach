package com.cruxcoach.android.competition

import android.util.Log
import com.cruxcoach.android.nostr.NostrEventPolicy
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
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
import kotlinx.coroutines.delay

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
    private val signer: NostrSigner,
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
        /** Encrypted host confirmations for this identity; never canonical state. */
        val privateReceipts: Map<String, CompetitionPrivateReceipt> = emptyMap(),
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
    private val authorityEvents = linkedMapOf<String, Event>()
    private var definitionEvent: Event? = null
    private val intents = linkedMapOf<String, CompetitionProtocol.ParsedIntent.Valid>()
    private val privateReceipts = linkedMapOf<String, CompetitionPrivateReceipt>()
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
        authorityEvents.clear()
        definitionEvent = null
        intents.clear()
        privateReceipts.clear()
        definitionCompetition = null

        // Explicit open joins the local BoardCell first and requests complete
        // signed history. No relay is contacted when the definition arrives.
        if (CompetitionMeshTransport.current?.joinLocal(compId) == true) {
            repeat(15) {
                delay(100)
                val local = _snapshot.value
                if (local.competition?.compId == compId) return true
            }
            // Nobody nearby had the definition. Continue with its public
            // relay copy; joining a quiet local realm must not make remote
            // registration pages look deleted.
        }

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
                definitionEvent = found
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
            if (event.tags.any { it.size >= 2 && it[0] == "enc" && it[1] == "nip44" }) {
                val localPubkey = signer.getPublicKeyHex()
                val counterparty = if (event.pubKey == localPubkey) competition.authority else event.pubKey
                val privateReceipt = event.tags.any {
                    it.size >= 3 && it[0] == "l" && it[1] == "private_receipt" &&
                        it[2] == CompetitionProtocol.NAMESPACE
                }
                val accepted = if (privateReceipt) {
                    ingestPrivateReceipt(event, nowSeconds())
                } else ingestEncryptedIntent(event, counterparty, nowSeconds())
                if (accepted) emit(entries.size)
                return@collect
            }
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
                    authorityEvents[event.id] = event
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
            authorityEvents[event.id] = event
            entries[event.id] = CompetitionReducer.Chained(parsed.entry, parsed.eventId, parsed.createdAt)
            reduce()
        }
    }

    /**
     * Decrypt a relay-carried private intent only on one of its endpoints.
     * Signature/id verification applies to both the ciphertext envelope and
     * the decrypted, independently signed local intent before parsing.
     */
    suspend fun ingestEncryptedIntent(event: Event, counterpartyPubkey: String, nowSeconds: Long): Boolean {
        if (!acceptsBody(event) ||
            event.tags.none { it.size >= 2 && it[0] == "enc" && it[1] == "nip44" }
        ) return false
        val competition = definitionCompetition ?: return false
        val organizer = _snapshot.value.organizerPubkey ?: return false
        val localPubkey = signer.getPublicKeyHex()
        if (localPubkey != competition.authority && event.pubKey != localPubkey) return false
        if (event.pubKey == competition.authority) return false
        val plaintext = runCatching {
            signer.signer.nip44Decrypt(event.content, counterpartyPubkey)
        }.getOrNull() ?: return false
        val clearEvent = runCatching { Event.fromJson(plaintext) }.getOrNull() ?: return false
        if (clearEvent.pubKey != event.pubKey || !acceptsBody(clearEvent)) return false
        val parsed = CompetitionProtocol.parseIntent(
            clearEvent.toCompetitionEvent(), competition, organizer, nowSeconds,
        )
        if (parsed !is CompetitionProtocol.ParsedIntent.Valid || parsed.op != "register") return false
        upsertIntent(parsed)
        reduce()
        return true
    }

    private suspend fun ingestPrivateReceipt(event: Event, nowSeconds: Long): Boolean {
        if (!acceptsBody(event)) return false
        val competition = definitionCompetition ?: return false
        val localPubkey = signer.getPublicKeyHex()
        if (event.pubKey != competition.authority ||
            event.tags.none { it.size >= 2 && it[0] == "p" && it[1] == localPubkey }
        ) return false
        val plaintext = runCatching {
            signer.signer.nip44Decrypt(event.content, competition.authority)
        }.getOrNull() ?: return false
        val receipt = CompetitionPrivateReceiptCodec.parse(
            plaintext, competition.compId, localPubkey,
        ) ?: return false
        if (receipt.at > nowSeconds + CompetitionProtocol.MAX_FUTURE_SKEW_SECONDS) return false
        val old = privateReceipts[receipt.op]
        if (old == null || receipt.at >= old.at) privateReceipts[receipt.op] = receipt
        reduce()
        return true
    }

    /** Ingest a signed event received from an authenticated, joined FIPS cell. */
    fun ingestMesh(event: Event, nowSeconds: Long): Boolean {
        if (!acceptsBody(event)) return false
        val existing = definitionCompetition
        if (existing == null) {
            return when (val parsed = CompetitionProtocol.parseCompetition(event.toCompetitionEvent(), nowSeconds)) {
                is CompetitionProtocol.ParsedCompetition.Invalid -> false
                is CompetitionProtocol.ParsedCompetition.Valid -> {
                    definitionCompetition = parsed.competition
                    definitionEvent = event
                    _snapshot.value = Snapshot(
                        competition = parsed.competition,
                        competitionEventId = parsed.eventId,
                        organizerPubkey = parsed.organizerPubkey,
                        lastSyncedAt = nowSeconds,
                    )
                    reduce(); true
                }
            }
        }
        val organizer = _snapshot.value.organizerPubkey ?: return false
        val intent = CompetitionProtocol.parseIntent(event.toCompetitionEvent(), existing, organizer, nowSeconds)
        if (intent is CompetitionProtocol.ParsedIntent.Valid) {
            upsertIntent(intent)
            reduce(); return true
        }
        if (event.pubKey != existing.authority || entries.containsKey(event.id)) return false
        val parsed = CompetitionProtocol.parseLogEntry(event.toCompetitionEvent(), existing, organizer, nowSeconds)
        if (parsed !is CompetitionProtocol.ParsedLogEntry.Valid) return false
        authorityEvents[event.id] = event
        entries[event.id] = CompetitionReducer.Chained(parsed.entry, parsed.eventId, parsed.createdAt)
        reduce(); return true
    }

    /** Canonical signed authority chain only. Participant intents deliberately never leave the local mesh. */
    fun eventsForOnlinePublication(): List<Event> {
        val definition = definitionEvent ?: return emptyList()
        val state = snapshot.value.state ?: return emptyList()
        if (!state.chainComplete || state.forkDetected) return emptyList()
        val byId = entries.values.associateBy { it.eventId }
        val reversed = mutableListOf<Event>()
        var cursor = state.head
        while (cursor != definition.id) {
            val chained = byId[cursor] ?: return emptyList()
            reversed += authorityEvents[cursor] ?: return emptyList()
            cursor = chained.entry.prev
        }
        return buildList {
            add(definition)
            addAll(reversed.asReversed())
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
                privateReceipts = privateReceipts.toMap(),
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

    private fun upsertIntent(intent: CompetitionProtocol.ParsedIntent.Valid) {
        val key = "${intent.pubkey}:${intent.op}"
        val old = intents[key]
        if (old == null || intent.createdAt >= old.createdAt) intents[key] = intent
    }

    companion object {
        private const val TAG = "CompetitionRelay"
    }
}

/** Quartz's event, reduced to the shape the shared protocol layer speaks. */
fun Event.toCompetitionEvent(content: String = this.content) = CompetitionEvent(
    id = id,
    pubkey = pubKey,
    createdAt = createdAt,
    kind = kind,
    tags = tags.map { it.toList() },
    content = content,
)
