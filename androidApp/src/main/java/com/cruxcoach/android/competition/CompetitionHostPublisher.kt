package com.cruxcoach.android.competition

import android.util.Log
import com.cruxcoach.android.boardcell.BoardCellManager
import com.cruxcoach.android.nostr.NostrPublicEventBuilder
import com.cruxcoach.android.nostr.NostrRelayPool
import com.cruxcoach.android.nostr.NostrSigner
import com.cruxcoach.domain.competition.CompetitionHostProtocol
import com.cruxcoach.domain.competition.CompetitionConfigUpdate
import com.cruxcoach.domain.competition.CompetitionProtocol
import com.cruxcoach.domain.competition.CompetitionReducer
import com.cruxcoach.domain.competition.CompetitionValidation
import com.cruxcoach.domain.competition.LogEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Publishes organizer decisions on the same authority chain as the website. */
@Singleton
class CompetitionHostPublisher @Inject constructor(
    private val signer: NostrSigner,
    private val relayPool: NostrRelayPool,
    private val client: CompetitionRelayClient,
    private val mesh: CompetitionMeshTransport,
) {
    sealed interface Result {
        data class Published(val accepted: Int, val attempted: Int) : Result
        data class Failed(val reason: String) : Result
    }

    data class CleanupResult(
        val tombstoneAccepted: Int,
        val deletionAccepted: Int,
        val attempted: Int,
    )

    private val writes = Mutex()
    private val receiptScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun participantDataOnline(): Boolean =
        client.snapshot.value.competition?.let(CompetitionProtocol::participantDataOnline) == true

    suspend fun create(config: JsonObject): Result {
        val competition = runCatching { com.cruxcoach.domain.competition.Competition.from(config) }
            .getOrElse { return Result.Failed(it.message ?: "invalid_config") }
        CompetitionValidation.validate(competition).firstOrNull()?.let {
            return Result.Failed("${it.field}: ${it.message}")
        }
        if (competition.authority != signer.getPublicKeyHex()) return Result.Failed("wrong_authority")
        val configuredCell = (competition.raw["board"] as? JsonObject)
            ?.get("cell_id")?.let { it as? JsonPrimitive }?.content
            ?: return Result.Failed("board.cell_id: is required for a new competition")
        val boardCell = BoardCellManager.current ?: return Result.Failed("board_cell_unavailable")
        val activeCell = boardCell.snapshot()?.cellId?.value
            ?: return Result.Failed("board_cell_unavailable")
        if (configuredCell != activeCell) return Result.Failed("wrong_board_cell")
        if (!boardCell.isLocalController()) return Result.Failed("host_must_control_board")
        if (!mesh.joinLocal(competition.compId)) return Result.Failed("board_cell_unavailable")
        return publish(
            competition.compId,
            CompetitionHostProtocol.competitionContent(config),
            CompetitionHostProtocol.competitionTags(config),
            publishDefinitionOnline = true,
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
        if (!state.chainComplete) return Result.Failed("incomplete_chain")
        val seq = state.seq + 1
        val at = System.currentTimeMillis() / 1000
        // Never publish an authority entry our own reducer will reject. In
        // particular, an invalid attempt_result must not be followed by an
        // otherwise valid queue advance that silently skips the climber.
        val preview = CompetitionReducer.applyEntry(
            state,
            LogEntry(seq, state.head, state.epoch, at, op, "authority", reason, data),
            competition,
        )
        if (preview.rejected.size > state.rejected.size) {
            return Result.Failed(preview.rejected.last().code)
        }
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
        if (!mesh.isJoined(competition.compId) && !mesh.joinLocal(competition.compId)) {
            return Result.Failed("board_cell_unavailable")
        }
        val accepted = mesh.publish(competition.compId, event)
        client.ingestOwn(event, at)
        receiptScope.launch {
            publishPrivateReceipts(
                competition.compId, organizer, seq, at, op, data, subjects, event.id,
            )
        }
        if (participantDataOnline()) {
            val (attempted, relayAccepted) = relayPool.sendEventWithStats(event)
            Result.Published(relayAccepted, attempted)
        } else {
            Result.Published(accepted, accepted)
        }
    }

    /**
     * Explicit, irreversible privacy boundary: first record the host's choice in the
     * local authority chain, then copy the complete signed authority history online.
     * Participant intents are not part of that history and remain mesh-local.
     */
    suspend fun enableParticipantDataOnline(): Result {
        if (!participantDataOnline()) {
            when (val updated = updateConfig(
                JsonObject(mapOf("participant_data_visibility" to JsonPrimitive("online"))),
                "Host enabled online participant data and results",
            )) {
                is Result.Failed -> return updated
                is Result.Published -> Unit
            }
        }
        val events = client.eventsForOnlinePublication()
        if (events.isEmpty()) return Result.Failed("not_loaded")
        var attempted = 0
        var accepted = 0
        for (event in events) {
            val stats = relayPool.sendEventWithStats(event)
            attempted += stats.first
            accepted += stats.second
        }
        return Result.Published(accepted, attempted)
    }

    /**
     * Remote registration needs a private answer even while the canonical
     * participant log remains on site. A receipt contains only the decision;
     * it cannot be used as an alternate result chain.
     */
    private suspend fun publishPrivateReceipts(
        compId: String,
        organizerPubkey: String,
        seq: Int,
        at: Long,
        op: String,
        data: JsonObject,
        subjects: List<String>,
        sourceEventId: String,
    ) {
        val state = when (op) {
            "registration_decision" -> (data["decision"] as? JsonPrimitive)?.content
            "checkin" -> (data["state"] as? JsonPrimitive)?.content
            else -> null
        } ?: return
        subjects.distinct().filter(CompetitionProtocol::isHex32).forEach { recipient ->
            runCatching {
                val plaintext = CompetitionPrivateReceiptCodec.content(
                    compId, recipient, op, state, sourceEventId, at,
                )
                val ciphertext = signer.signer.nip44Encrypt(plaintext, recipient)
                val tags = listOf(
                    listOf("d", CompetitionPrivateReceiptCodec.dTag(compId, recipient, seq)),
                    listOf("L", CompetitionProtocol.NAMESPACE),
                    listOf("l", "private_receipt", CompetitionProtocol.NAMESPACE),
                    listOf("cc-schema", CompetitionProtocol.SCHEMA),
                    listOf("alt", "Encrypted CruxCoach competition confirmation"),
                    listOf("a", CompetitionProtocol.competitionAddress(organizerPubkey, compId)),
                    listOf("p", recipient),
                    listOf("enc", "nip44"),
                )
                val receipt = NostrPublicEventBuilder(signer)
                    .buildSignedEvent(CompetitionProtocol.KIND, ciphertext, tags)
                relayPool.sendEventWithStats(receipt)
            }.onFailure {
                // The canonical local decision already succeeded. Failure to
                // deliver this convenience receipt must not roll it back.
                Log.w(TAG, "private competition receipt delivery failed", it)
            }
        }
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

    /** Broadcast signed local tombstone/deletion records through the joined cell. */
    suspend fun deleteCompetition(): kotlin.Result<CleanupResult> = runCatching {
        val snapshot = client.snapshot.value
        val competition = requireNotNull(snapshot.competition) { "not_loaded" }
        val definitionId = requireNotNull(snapshot.competitionEventId) { "not_loaded" }
        val organizer = requireNotNull(snapshot.organizerPubkey) { "not_loaded" }
        require(competition.authority == signer.getPublicKeyHex() && organizer == signer.getPublicKeyHex()) {
            "not_authority"
        }
        require(snapshot.state?.status == "cancelled") { "cancel_first" }

        val now = System.currentTimeMillis() / 1000
        val tombstone = NostrPublicEventBuilder(signer).buildSignedEvent(
            CompetitionProtocol.KIND,
            CompetitionHostProtocol.tombstoneContent(competition.compId, now),
            CompetitionHostProtocol.tombstoneTags(competition.compId),
        )
        val deletion = NostrPublicEventBuilder(signer).buildSignedEvent(
            5,
            "CruxCoach test competition cleanup",
            CompetitionHostProtocol.deletionTags(definitionId),
        )
        require(mesh.isJoined(competition.compId) || mesh.joinLocal(competition.compId)) {
            "board_cell_unavailable"
        }
        mesh.publish(competition.compId, tombstone)
        mesh.publish(competition.compId, deletion)
        // The definition is always public/unlisted on Nostr, independently of
        // participant privacy, so cleanup must reach those same relays too.
        val tombstoneStats = relayPool.sendEventWithStats(tombstone)
        val deletionStats = relayPool.sendEventWithStats(deletion)
        CleanupResult(
            tombstoneAccepted = tombstoneStats.second,
            deletionAccepted = deletionStats.second,
            attempted = maxOf(tombstoneStats.first, deletionStats.first),
        )
    }

    private suspend fun publish(
        compId: String,
        content: String,
        tags: List<List<String>>,
        publishDefinitionOnline: Boolean = false,
    ): Result = runCatching {
        val event = NostrPublicEventBuilder(signer).buildSignedEvent(CompetitionProtocol.KIND, content, tags)
        if (!mesh.isJoined(compId) && !mesh.joinLocal(compId)) {
            return@runCatching Result.Failed("board_cell_unavailable")
        }
        val accepted = mesh.publish(compId, event)
        client.ingestMesh(event, System.currentTimeMillis() / 1_000)
        if (publishDefinitionOnline) {
            val (attempted, relayAccepted) = relayPool.sendEventWithStats(event)
            Result.Published(relayAccepted, attempted)
        } else Result.Published(accepted, accepted)
    }.getOrElse { Result.Failed(it.message ?: "publish_failed") }

    private companion object {
        const val TAG = "CompetitionHost"
    }
}
