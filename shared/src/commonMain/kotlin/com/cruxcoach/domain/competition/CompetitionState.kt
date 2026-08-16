package com.cruxcoach.domain.competition

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reduced competition state — FEAT-058 §7.1.
 *
 * [toCanonicalJson] must produce exactly the object the website's reducer
 * produces, because that object is what gets hashed and compared. Field names
 * are therefore the wire names, not Kotlin ones, and every collection has a
 * specified order.
 */
data class ClimbProgress(
    val climbId: String,
    val attemptsUsed: Int = 0,
    val outcome: String = "none",
    val at: Long = 0,
) {
    fun toCanonicalJson(): JsonObject = JsonObject(
        mapOf(
            "at" to JsonPrimitive(at),
            "attempts_used" to JsonPrimitive(attemptsUsed),
            "climb_id" to JsonPrimitive(climbId),
            "outcome" to JsonPrimitive(outcome),
        ),
    )
}

/**
 * Who holds a prize, and where the claim has got to.
 *
 * Deliberately two fields. Anything richer would be a payout detail, and a
 * payout detail in public state is exactly what §11.7 exists to prevent.
 */
data class PrizeStatus(val pubkey: String, val state: String) {
    fun toCanonicalJson(): JsonObject = JsonObject(
        mapOf(
            "pubkey" to JsonPrimitive(pubkey),
            "state" to JsonPrimitive(state),
        ),
    )
}

data class Participant(
    val pubkey: String,
    val display: String = "",
    val division: String = "",
    val registration: String = "pending",
    val waitlistPosition: Int = 0,
    val payment: String = "not_required",
    val checkin: String = "none",
    val selections: List<String> = emptyList(),
    val defersUsedThisRound: Int = 0,
    val consecutiveDefers: Int = 0,
    val result: String = "active",
    val climbs: List<ClimbProgress> = emptyList(),
    val lastAttemptAt: Long = 0,
) {
    fun climb(climbId: String): ClimbProgress? = climbs.firstOrNull { it.climbId == climbId }

    fun withClimb(progress: ClimbProgress): Participant {
        val without = climbs.filterNot { it.climbId == progress.climbId }
        return copy(climbs = (without + progress).sortedBy { it.climbId })
    }

    fun toCanonicalJson(): JsonObject = JsonObject(
        mapOf(
            "checkin" to JsonPrimitive(checkin),
            "climbs" to JsonArray(climbs.map { it.toCanonicalJson() }),
            "consecutive_defers" to JsonPrimitive(consecutiveDefers),
            "defers_used_this_round" to JsonPrimitive(defersUsedThisRound),
            "display" to JsonPrimitive(display),
            "division" to JsonPrimitive(division),
            "last_attempt_at" to JsonPrimitive(lastAttemptAt),
            "payment" to JsonPrimitive(payment),
            "pubkey" to JsonPrimitive(pubkey),
            "registration" to JsonPrimitive(registration),
            "result" to JsonPrimitive(result),
            "selections" to JsonArray(selections.map { JsonPrimitive(it) }),
            "waitlist_position" to JsonPrimitive(waitlistPosition),
        ),
    )
}

data class Announcement(val seq: Int, val text: String, val at: Long) {
    fun toCanonicalJson(): JsonObject = JsonObject(
        mapOf("at" to JsonPrimitive(at), "seq" to JsonPrimitive(seq), "text" to JsonPrimitive(text)),
    )
}

data class AuditEntry(
    val seq: Int,
    val op: String,
    val reason: String?,
    val at: Long,
    val supersedesSeq: Int? = null,
    val supersedesResults: Boolean = false,
    val revision: Int? = null,
    val impact: String? = null,
) {
    fun toCanonicalJson(): JsonObject {
        val fields = mutableMapOf<String, JsonElement>(
            "at" to JsonPrimitive(at),
            "op" to JsonPrimitive(op),
            "seq" to JsonPrimitive(seq),
        )
        // CCJ omits absent values rather than writing null, so an override
        // (no supersedes_seq) and a correction must serialize differently.
        if (reason != null) fields["reason"] = JsonPrimitive(reason)
        if (supersedesSeq != null) fields["supersedes_seq"] = JsonPrimitive(supersedesSeq)
        if (supersedesResults) fields["supersedes_results"] = JsonPrimitive(true)
        if (revision != null) fields["revision"] = JsonPrimitive(revision)
        if (impact != null) fields["impact"] = JsonPrimitive(impact)
        return JsonObject(fields)
    }
}

data class Rejection(val seq: Int, val op: String, val code: String) {
    fun toCanonicalJson(): JsonObject = JsonObject(
        mapOf("code" to JsonPrimitive(code), "op" to JsonPrimitive(op), "seq" to JsonPrimitive(seq)),
    )
}

data class CompetitionState(
    val compId: String,
    val schema: String = CompetitionProtocol.SCHEMA,
    val authority: String,
    val epoch: Int,
    val seq: Int = 0,
    val head: String,
    val status: String,
    val paused: Boolean = false,
    val configRevision: Int = 1,
    /** Present only after the first config_update, preserving legacy state hashes. */
    val effectiveConfig: JsonObject? = null,
    val round: Int = 0,
    val currentClimbId: String = "",
    val cursor: Int = -1,
    val turnOpenedAt: Long = 0,
    val turnDeadlineAt: Long = 0,
    val participants: List<Participant> = emptyList(),
    val order: List<String> = emptyList(),
    val claims: Map<String, String> = emptyMap(),
    /**
     * prize_id -> holder and status.
     *
     * The status of a prize and nothing else. The claim, the payout
     * destination and any contact detail travel NIP-44 encrypted between the
     * winner and the organizer and never reach this map.
     */
    val prizes: Map<String, PrizeStatus> = emptyMap(),
    val announcements: List<Announcement> = emptyList(),
    val audit: List<AuditEntry> = emptyList(),
    val rejected: List<Rejection> = emptyList(),
    val forkDetected: Boolean = false,
    val chainComplete: Boolean = true,
    /** Local bookkeeping: excluded from the hash so a snapshot-started client
     *  and a full-replay client still agree. */
    val fromSnapshot: Boolean = false,
) {
    fun participant(pubkey: String): Participant? = participants.firstOrNull { it.pubkey == pubkey }

    fun withParticipant(participant: Participant): CompetitionState {
        val without = participants.filterNot { it.pubkey == participant.pubkey }
        // Ascending by pubkey — never arrival order, which differs per client.
        return copy(participants = (without + participant).sortedBy { it.pubkey })
    }

    fun upsertParticipant(pubkey: String): Participant =
        participant(pubkey) ?: Participant(pubkey = pubkey)

    /** The exact structure the state hash is computed over (FEAT-058 §4.3). */
    fun toCanonicalJson(): JsonObject {
        val fields = mutableMapOf<String, JsonElement>(
            "announcements" to JsonArray(announcements.map { it.toCanonicalJson() }),
            "audit" to JsonArray(audit.map { it.toCanonicalJson() }),
            "authority" to JsonPrimitive(authority),
            "chain_complete" to JsonPrimitive(chainComplete),
            "claims" to JsonObject(claims.toSortedMap().mapValues { JsonPrimitive(it.value) }),
            "comp_id" to JsonPrimitive(compId),
            "config_revision" to JsonPrimitive(configRevision),
            "current_climb_id" to JsonPrimitive(currentClimbId),
            "cursor" to JsonPrimitive(cursor),
            "epoch" to JsonPrimitive(epoch),
            "fork_detected" to JsonPrimitive(forkDetected),
            "head" to JsonPrimitive(head),
            "order" to JsonArray(order.map { JsonPrimitive(it) }),
            "participants" to JsonArray(participants.map { it.toCanonicalJson() }),
            "paused" to JsonPrimitive(paused),
            "prizes" to JsonObject(prizes.toSortedMap().mapValues { it.value.toCanonicalJson() }),
            "rejected" to JsonArray(rejected.map { it.toCanonicalJson() }),
            "round" to JsonPrimitive(round),
            "schema" to JsonPrimitive(schema),
            "seq" to JsonPrimitive(seq),
            "status" to JsonPrimitive(status),
            "turn_deadline_at" to JsonPrimitive(turnDeadlineAt),
            "turn_opened_at" to JsonPrimitive(turnOpenedAt),
        )
        if (effectiveConfig != null) fields["effective_config"] = effectiveConfig
        return JsonObject(fields)
    }

    fun stateHash(): String = ccjHash(toCanonicalJson())
}
