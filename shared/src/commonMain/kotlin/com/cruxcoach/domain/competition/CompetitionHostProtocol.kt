package com.cruxcoach.domain.competition

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Wire builders shared by every Android organizer surface. */
object CompetitionHostProtocol {
    fun competitionContent(config: JsonObject): String = Ccj.encode(
        JsonObject(linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "v" to JsonPrimitive(CompetitionProtocol.SCHEMA_MAJOR),
            "type" to JsonPrimitive("competition"),
        ).apply { putAll(config) }),
    )

    fun competitionTags(config: JsonObject): List<List<String>> {
        val compId = config.string("comp_id")
        val title = config.string("title")
        val visibility = config.string("visibility")
        val tags = mutableListOf(
            listOf("d", CompetitionProtocol.compDTag(compId)),
            listOf("L", CompetitionProtocol.NAMESPACE),
            listOf("l", "competition", CompetitionProtocol.NAMESPACE),
            listOf("cc-schema", CompetitionProtocol.SCHEMA),
            listOf("alt", "CruxCoach competition: $title"),
            listOf("status", config.string("status")),
            listOf("visibility", visibility),
            listOf("board_brand", (config["board"] as JsonObject).string("brand")),
            listOf("starts", config.longValue("starts_at").toString()),
            listOf("ends", config.longValue("ends_at").toString()),
            listOf("authority", config.string("authority")),
            listOf("p", config.string("authority")),
        )
        if (visibility == "public") {
            tags += listOf("t", "cruxcoach-competition")
            tags += listOf("t", "climbing")
        }
        return tags
    }

    /**
     * A durable client-side deletion marker at the competition's own
     * addressable coordinate. Relays that ignore NIP-09 still replace the
     * readable definition with this deliberately non-config payload.
     */
    fun tombstoneContent(compId: String, deletedAt: Long): String = Ccj.encode(
        JsonObject(linkedMapOf(
            "v" to JsonPrimitive(CompetitionProtocol.SCHEMA_MAJOR),
            "type" to JsonPrimitive("competition"),
            "comp_id" to JsonPrimitive(compId),
            "deleted" to JsonPrimitive(true),
            "deleted_at" to JsonPrimitive(deletedAt),
        )),
    )

    fun tombstoneTags(compId: String): List<List<String>> = listOf(
        listOf("d", CompetitionProtocol.compDTag(compId)),
        listOf("L", CompetitionProtocol.NAMESPACE),
        listOf("l", "competition", CompetitionProtocol.NAMESPACE),
        listOf("cc-schema", CompetitionProtocol.SCHEMA),
        listOf("alt", "Deleted CruxCoach competition"),
        listOf("status", "deleted"),
    )

    /** NIP-09 request for the concrete definition, leaving the tombstone intact. */
    fun deletionTags(definitionEventId: String): List<List<String>> = listOf(
        listOf("e", definitionEventId),
        listOf("k", CompetitionProtocol.KIND.toString()),
    )

    fun logContent(
        compId: String, seq: Int, prev: String, epoch: Int, at: Long,
        op: String, data: JsonObject, reason: String? = null, actor: String = "authority",
    ): String = Ccj.encode(JsonObject(linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
        "v" to JsonPrimitive(CompetitionProtocol.SCHEMA_MAJOR),
        "type" to JsonPrimitive("log"), "comp_id" to JsonPrimitive(compId),
        "seq" to JsonPrimitive(seq), "prev" to JsonPrimitive(prev),
        "epoch" to JsonPrimitive(epoch), "at" to JsonPrimitive(at),
        "op" to JsonPrimitive(op), "actor" to JsonPrimitive(actor), "data" to data,
    ).apply { if (reason != null) put("reason", JsonPrimitive(reason)) }))

    fun logTags(
        compId: String, organizerPubkey: String, seq: Int, prev: String,
        epoch: Int, op: String, subjects: List<String> = emptyList(),
    ): List<List<String>> = buildList {
        add(listOf("d", CompetitionProtocol.logDTag(compId, seq)))
        add(listOf("L", CompetitionProtocol.NAMESPACE))
        add(listOf("l", "log", CompetitionProtocol.NAMESPACE))
        add(listOf("cc-schema", CompetitionProtocol.SCHEMA))
        add(listOf("alt", "CruxCoach competition log entry $seq: $op"))
        add(listOf("a", CompetitionProtocol.competitionAddress(organizerPubkey, compId)))
        add(listOf("seq", seq.toString())); add(listOf("prev", prev))
        add(listOf("op", op)); add(listOf("epoch", epoch.toString()))
        subjects.forEach { add(listOf("p", it)) }
    }

    private fun JsonObject.string(key: String) = (get(key) as JsonPrimitive).content
    private fun JsonObject.longValue(key: String) = (get(key) as JsonPrimitive).content.toLong()
}
