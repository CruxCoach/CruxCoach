package com.cruxcoach.domain.competition

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Shared policy and deterministic merge semantics for audit-log configuration edits. */
object CompetitionConfigUpdate {
    val SAFE_FIELDS = setOf(
        "title", "summary", "description", "eligibility", "waiver",
        "participant_instructions", "spectator_info", "refund_policy", "visibility",
        "venue", "timezone", "prize_claim_days",
    )
    val SCORING_FIELDS = setOf(
        "starts_at", "ends_at", "registration_opens_at", "registration_closes_at",
        "checkin_opens_at", "checkin_closes_at", "capacity", "waitlist_enabled",
        "fee_msat", "fee_lnurl", "waiver_required", "board", "divisions", "climbs",
        "climb_pool", "prizes", "rules",
    )
    val MUTABLE_FIELDS = SAFE_FIELDS + SCORING_FIELDS

    fun impact(patch: JsonObject): String? {
        if (patch.isEmpty() || patch.keys.any { it !in MUTABLE_FIELDS }) return null
        return if (patch.keys.any { it in SCORING_FIELDS }) "scoring" else "safe"
    }

    /** RFC-7396 merge-patch semantics: objects merge, arrays replace, null removes. */
    fun merge(target: JsonObject, patch: JsonObject): JsonObject {
        val result = target.toMutableMap()
        for ((key, value) in patch) {
            when {
                value is JsonNull -> result.remove(key)
                value is JsonObject && result[key] is JsonObject -> {
                    result[key] = merge(result[key] as JsonObject, value)
                }
                else -> result[key] = value
            }
        }
        return JsonObject(result)
    }

    fun rootConfig(competition: Competition): JsonObject = JsonObject(
        competition.raw.filterKeys { it != "v" && it != "type" },
    )
}
