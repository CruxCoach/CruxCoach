package com.cruxcoach.android.competition

import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verifyId
import com.vitorpamplona.quartz.nip01Core.crypto.verifySignature
import com.cruxcoach.domain.competition.CompetitionHostProtocol
import com.cruxcoach.domain.competition.CompetitionConfigUpdate
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.CompetitionReducer
import com.cruxcoach.domain.competition.CompetitionValidation
import com.cruxcoach.domain.competition.LogEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Publishes organizer decisions on the same authority chain as the website. */
@Singleton
class CompetitionHostPublisher @Inject constructor(
    private val signer: NostrSigner,
    private val relayPool: NostrRelayPool,
    private val client: CompetitionRelayClient,
    private val cleanupStore: CompetitionCleanupStore,
) {
    sealed interface Result {
        data class Published(val accepted: Int, val attempted: Int) : Result
        data class Failed(val reason: String) : Result
    }

    data class CleanupResult(
        val tombstoneAccepted: Int,
        val deletionAccepted: Int,
        val attempted: Int,
        val complete: Boolean,
        val job: CompetitionCleanupJob,
    )

    private val writes = Mutex()

    suspend fun create(config: JsonObject): Result {
        val competition = runCatching { com.cruxcoach.domain.competition.Competition.from(config) }
            .getOrElse { return Result.Failed(it.message ?: "invalid_config") }
        CompetitionValidation.validate(competition).firstOrNull()?.let {
            return Result.Failed("${it.field}: ${it.message}")
        }
        if (competition.authority != signer.getPublicKeyHex()) return Result.Failed("wrong_authority")
        return publish(
            CompetitionHostProtocol.competitionContent(config),
            CompetitionHostProtocol.competitionTags(config),
            competition,
        )
    }

    suspend fun append(
        op: String,
        data: JsonObject,
        reason: String? = null,
        subjects: List<String> = emptyList(),
    ): Result = writes.withLock {
        if (op !in CompetitionProtocol.LOG_OPS) return Result.Failed("unknown_op")
        if (op in CompetitionProtocol.REASON_REQUIRED_OPS && reason.isNullOrBlank()) {
            return Result.Failed("reason_required")
        }
        val snapshot = client.snapshot.value
        val competition = snapshot.competition ?: return Result.Failed("not_loaded")
        val state = snapshot.state ?: return Result.Failed("not_loaded")
        val organizer = snapshot.organizerPubkey ?: return Result.Failed("not_loaded")
        if (competition.authority != signer.getPublicKeyHex()) return Result.Failed("not_authority")
        if (!snapshot.trustworthy) return Result.Failed("incomplete_chain")
        val seq = state.seq + 1
        val at = System.currentTimeMillis() / 1000
        // Never publish an authority entry our own reducer will reject. In
        // particular, an invalid attempt_result must not be followed by an
        // otherwise valid queue advance that silently skips the climber.
        val entry = LogEntry(seq, state.head, state.epoch, at, op, "authority", reason, data)
        val preview = if (op == "correction") {
            client.previewAuthorityEntry(entry) ?: return Result.Failed("not_loaded")
        } else {
            CompetitionReducer.applyEntry(state, entry, competition)
        }
        preview.rejected.lastOrNull { it.seq == seq }?.let { return Result.Failed(it.code) }
        val event = runCatching {
            NostrPublicEventBuilder(signer).buildSignedEvent(
                CompetitionProtocol.KIND,
                CompetitionHostProtocol.logContent(
                    competition.compId, seq, state.head, state.epoch, at, op, data, reason,
                ),
                CompetitionHostProtocol.logTags(
                    competition.compId, organizer, seq, state.head, state.epoch, op, subjects,
                ),
            )
        }.getOrElse { return Result.Failed(it.message ?: "sign_failed") }
        val (attempted, accepted) = relayPool.sendEventWithStats(event, relayScope(competition))
        if (accepted == 0) return Result.Failed("no_relay")
        client.ingestOwn(event, at)
        Result.Published(accepted, attempted)
    }

    /** Publish a permanent, reasoned edit without replacing the chain root. */
    suspend fun updateConfig(patch: JsonObject, reason: String): Result {
        if (reason.isBlank()) return Result.Failed("reason_required")
        val impact = CompetitionConfigUpdate.impact(patch) ?: return Result.Failed("immutable_or_empty_patch")
        val snapshot = client.snapshot.value
        val competition = snapshot.competition ?: return Result.Failed("not_loaded")
        val state = snapshot.state ?: return Result.Failed("not_loaded")
        val revision = state.configRevision + 1
        val merged = CompetitionConfigUpdate.merge(
            CompetitionConfigUpdate.rootConfig(competition), patch,
        ).toMutableMap().also { it["revision"] = JsonPrimitive(revision) }.let(::JsonObject)
        val candidate = runCatching { com.cruxcoach.domain.competition.Competition.from(merged) }
            .getOrElse { return Result.Failed("invalid_config") }
        CompetitionValidation.validate(candidate).firstOrNull()?.let {
            return Result.Failed("${it.field}: ${it.message}")
        }
        return append(
            "config_update",
            JsonObject(mapOf(
                "revision" to JsonPrimitive(revision),
                "patch" to patch,
                "impact" to JsonPrimitive(impact),
            )),
            reason.trim(),
        )
    }

    /**
     * Best-effort removal from the configured write relays.
     *
     * The addressable tombstone makes CruxCoach clients stop rendering the
     * definition even on relays that ignore NIP-09. The kind-5 request points
     * at the concrete original event id, not the address, so a compliant relay
     * does not immediately delete the newer tombstone as well.
     */
    suspend fun deleteCompetition(): kotlin.Result<CleanupResult> = runCatching {
        val snapshot = client.snapshot.value
        val competition = requireNotNull(snapshot.competition) { "not_loaded" }
        val definitionId = requireNotNull(snapshot.competitionEventId) { "not_loaded" }
        val organizer = requireNotNull(snapshot.organizerPubkey) { "not_loaded" }
        require(competition.authority == signer.getPublicKeyHex() && organizer == signer.getPublicKeyHex()) {
            "not_authority"
        }
        cleanupTrustError(snapshot)?.let { error(it) }

        cleanupStore.get(organizer, competition.compId)?.let { return@runCatching executeCleanup(it) }

        val now = System.currentTimeMillis() / 1000
        val tombstoneAt = CompetitionProtocol.nextReplaceableCreatedAt(now, snapshot.competitionCreatedAt)
        val tombstone = NostrPublicEventBuilder(signer).buildSignedEvent(
            CompetitionProtocol.KIND,
            CompetitionHostProtocol.tombstoneContent(competition.compId, tombstoneAt),
            CompetitionHostProtocol.tombstoneTags(competition.compId),
            createdAt = tombstoneAt,
        )
        val deletion = NostrPublicEventBuilder(signer).buildSignedEvent(
            5,
            "CruxCoach test competition cleanup",
            CompetitionHostProtocol.deletionTags(definitionId),
        )
        val job = CompetitionCleanupJob(
            ownerPubkey = organizer,
            compId = competition.compId,
            title = competition.title,
            definitionEventId = definitionId,
            relays = competition.relays,
            tombstoneEventJson = tombstone.toJson(),
            deletionEventJson = deletion.toJson(),
            updatedAt = now,
        )
        // A synchronous durable commit is a precondition for the first send.
        cleanupStore.save(job)
        executeCleanup(job)
    }

    /** Resume from the overview after the addressable definition became a tombstone. */
    suspend fun retryCleanup(job: CompetitionCleanupJob): kotlin.Result<CleanupResult> = runCatching {
        require(job.ownerPubkey == signer.getPublicKeyHex()) { "not_authority" }
        executeCleanup(job)
    }

    private suspend fun executeCleanup(initial: CompetitionCleanupJob): CleanupResult {
        val (tombstone, deletion) = validateCleanupJob(initial)
        val scopedRelays = competitionRelayScope(initial.relays, emptyList())
        require(scopedRelays.map { it.url } == initial.relays) { "invalid_cleanup_relays" }

        val tombstoneLatest = relayPool.sendEventWithOutcomes(tombstone, scopedRelays)
        var job = initial.copy(
            tombstoneOutcomes = mergeOutcomes(initial.tombstoneOutcomes, tombstoneLatest),
            updatedAt = System.currentTimeMillis() / 1000,
        )
        cleanupStore.save(job)
        val deletionLatest = relayPool.sendEventWithOutcomes(deletion, scopedRelays)
        job = job.copy(
            deletionOutcomes = mergeOutcomes(job.deletionOutcomes, deletionLatest),
            updatedAt = System.currentTimeMillis() / 1000,
        )
        cleanupStore.save(job)
        val complete = job.relays.all { job.tombstoneOutcomes[it] == true && job.deletionOutcomes[it] == true }
        if (complete) cleanupStore.remove(job)
        return CleanupResult(
            tombstoneAccepted = job.tombstoneOutcomes.count { it.value },
            deletionAccepted = job.deletionOutcomes.count { it.value },
            attempted = job.relays.size,
            complete = complete,
            job = job,
        )
    }

    private fun mergeOutcomes(
        previous: Map<String, Boolean>, latest: List<NostrRelayPool.PublishOutcome>,
    ) = latest.associate { outcome ->
        outcome.url to (previous[outcome.url] == true || outcome.accepted)
    }

    private fun validateCleanupJob(job: CompetitionCleanupJob): Pair<Event, Event> {
        require(job.ownerPubkey.matches(Regex("[0-9a-f]{64}"))) { "invalid_cleanup_owner" }
        require(job.compId.matches(Regex("[0-9a-f]{16}"))) { "invalid_cleanup_coordinate" }
        require(job.definitionEventId.matches(Regex("[0-9a-f]{64}"))) { "invalid_cleanup_definition" }
        require(job.relays.isNotEmpty() && job.relays.distinct().size == job.relays.size) { "invalid_cleanup_relays" }
        require(job.relays.all(CompetitionProtocol::isAllowedRelayUrl)) { "invalid_cleanup_relays" }
        val tombstone = Event.fromJson(job.tombstoneEventJson)
        val deletion = Event.fromJson(job.deletionEventJson)
        require(tombstone.verifyId() && tombstone.verifySignature() &&
            deletion.verifyId() && deletion.verifySignature()) { "invalid_cleanup_signature" }
        require(tombstone.pubKey == job.ownerPubkey && deletion.pubKey == job.ownerPubkey &&
            tombstone.kind == CompetitionProtocol.KIND && deletion.kind == 5) { "invalid_cleanup_event" }
        require(tombstone.tags.any { it.size >= 2 && it[0] == "d" && it[1] == CompetitionProtocol.compDTag(job.compId) }) {
            "invalid_cleanup_coordinate"
        }
        require(deletion.tags.any { it.size >= 2 && it[0] == "e" && it[1] == job.definitionEventId } &&
            deletion.tags.any { it.size >= 2 && it[0] == "k" && it[1] == CompetitionProtocol.KIND.toString() }) {
            "invalid_cleanup_target"
        }
        val content = Json.parseToJsonElement(tombstone.content).jsonObject
        require(content["deleted"]?.jsonPrimitive?.boolean == true &&
            content["comp_id"]?.jsonPrimitive?.content == job.compId) { "invalid_cleanup_tombstone" }
        return tombstone to deletion
    }

    private suspend fun publish(
        content: String,
        tags: List<List<String>>,
        competition: com.cruxcoach.domain.competition.Competition,
    ): Result = runCatching {
        val event = NostrPublicEventBuilder(signer).buildSignedEvent(CompetitionProtocol.KIND, content, tags)
        val (attempted, accepted) = relayPool.sendEventWithStats(event, relayScope(competition))
        if (accepted == 0) Result.Failed("no_relay") else Result.Published(accepted, attempted)
    }.getOrElse { Result.Failed(it.message ?: "publish_failed") }

    private fun relayScope(competition: com.cruxcoach.domain.competition.Competition) =
        competitionRelayScope(
            competition.relays,
            relayPool.readRelays() + relayPool.writeRelays(),
        )

    companion object {
        internal fun cleanupTrustError(snapshot: CompetitionRelayClient.Snapshot): String? = when {
            !snapshot.trustworthy -> "history_incomplete"
            snapshot.state?.status != "cancelled" -> "cancel_first"
            else -> null
        }
    }
}
