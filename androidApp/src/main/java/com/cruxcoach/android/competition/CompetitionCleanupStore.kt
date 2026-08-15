package com.cruxcoach.android.competition

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

data class CompetitionCleanupJob(
    val ownerPubkey: String,
    val compId: String,
    val title: String,
    val definitionEventId: String,
    val relays: List<String>,
    val tombstoneEventJson: String,
    val deletionEventJson: String,
    val tombstoneOutcomes: Map<String, Boolean> = emptyMap(),
    val deletionOutcomes: Map<String, Boolean> = emptyMap(),
    val updatedAt: Long,
)

/** Public signed events only; synchronously committed before cleanup can publish. */
@Singleton
class CompetitionCleanupStore internal constructor(
    private val prefs: SharedPreferences,
) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
    )

    fun save(job: CompetitionCleanupJob) {
        check(prefs.edit().putString(key(job.ownerPubkey, job.compId), encode(job)).commit()) {
            "cleanup_storage_unavailable"
        }
    }

    fun remove(job: CompetitionCleanupJob) {
        check(prefs.edit().remove(key(job.ownerPubkey, job.compId)).commit()) {
            "cleanup_storage_unavailable"
        }
    }

    fun get(ownerPubkey: String, compId: String): CompetitionCleanupJob? =
        prefs.getString(key(ownerPubkey, compId), null)?.let(::decode)

    fun list(ownerPubkey: String): List<CompetitionCleanupJob> = prefs.all
        .filterKeys { it.startsWith("$PREFIX$ownerPubkey:") }
        .values.mapNotNull { (it as? String)?.let(::decode) }
        .filter { it.ownerPubkey == ownerPubkey }
        .sortedByDescending { it.updatedAt }

    private fun encode(job: CompetitionCleanupJob) = buildJsonObject {
        put("version", JsonPrimitive(1)); put("owner_pubkey", JsonPrimitive(job.ownerPubkey))
        put("comp_id", JsonPrimitive(job.compId)); put("title", JsonPrimitive(job.title))
        put("definition_event_id", JsonPrimitive(job.definitionEventId))
        put("relays", buildJsonArray { job.relays.forEach { add(JsonPrimitive(it)) } })
        put("tombstone_event", Json.parseToJsonElement(job.tombstoneEventJson))
        put("deletion_event", Json.parseToJsonElement(job.deletionEventJson))
        put("tombstone_outcomes", outcomes(job.tombstoneOutcomes))
        put("deletion_outcomes", outcomes(job.deletionOutcomes))
        put("updated_at", JsonPrimitive(job.updatedAt))
    }.toString()

    private fun outcomes(values: Map<String, Boolean>) = buildJsonObject {
        values.forEach { (url, accepted) -> put(url, JsonPrimitive(accepted)) }
    }

    private fun decode(raw: String): CompetitionCleanupJob? = runCatching {
        val root = Json.parseToJsonElement(raw).jsonObject
        check(root["version"]?.jsonPrimitive?.content == "1")
        CompetitionCleanupJob(
            ownerPubkey = root.required("owner_pubkey"), compId = root.required("comp_id"),
            title = root.required("title"), definitionEventId = root.required("definition_event_id"),
            relays = root["relays"]!!.jsonArray.map { it.jsonPrimitive.content },
            tombstoneEventJson = root["tombstone_event"]!!.toString(),
            deletionEventJson = root["deletion_event"]!!.toString(),
            tombstoneOutcomes = root.outcomes("tombstone_outcomes"),
            deletionOutcomes = root.outcomes("deletion_outcomes"),
            updatedAt = root["updated_at"]!!.jsonPrimitive.long,
        )
    }.getOrNull()

    private fun JsonObject.required(name: String) = getValue(name).jsonPrimitive.content
    private fun JsonObject.outcomes(name: String) = (get(name) as? JsonObject).orEmpty()
        .mapValues { it.value.jsonPrimitive.boolean }

    private fun key(ownerPubkey: String, compId: String) = "$PREFIX$ownerPubkey:$compId"

    companion object {
        private const val PREFERENCES = "competition_cleanup_jobs"
        private const val PREFIX = "competition-cleanup-v1:"
    }
}
