package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class CompetitionHostProtocolTest {
    private val json = Json { isLenient = false }

    @Test fun `competition builder emits website-compatible envelope`() {
        val config = JsonObject(mapOf(
            "comp_id" to JsonPrimitive("0123456789abcdef"),
            "authority" to JsonPrimitive("a".repeat(64)),
            "title" to JsonPrimitive("Mobile comp"), "status" to JsonPrimitive("draft"),
            "visibility" to JsonPrimitive("public"), "starts_at" to JsonPrimitive(1000),
            "ends_at" to JsonPrimitive(2000),
            "board" to JsonObject(mapOf("brand" to JsonPrimitive("kilter"))),
        ))
        val body = json.parseToJsonElement(CompetitionHostProtocol.competitionContent(config)) as JsonObject
        assertEquals("competition", body["type"]?.jsonPrimitive?.content)
        assertEquals(1, body["v"]?.jsonPrimitive?.content?.toInt())
        val tags = CompetitionHostProtocol.competitionTags(config)
        assertTrue(listOf("t", "cruxcoach-competition") in tags)
        assertTrue(listOf("l", "competition", CompetitionProtocol.NAMESPACE) in tags)
    }

    @Test fun `unlisted definition has no discovery hashtag`() {
        val config = JsonObject(mapOf(
            "comp_id" to JsonPrimitive("0123456789abcdef"), "authority" to JsonPrimitive("a".repeat(64)),
            "title" to JsonPrimitive("Private"), "status" to JsonPrimitive("draft"), "visibility" to JsonPrimitive("unlisted"),
            "starts_at" to JsonPrimitive(1), "ends_at" to JsonPrimitive(2),
            "board" to JsonObject(mapOf("brand" to JsonPrimitive("moonboard"))),
        ))
        assertFalse(CompetitionHostProtocol.competitionTags(config).any { it.firstOrNull() == "t" })
    }

    @Test fun `authority log content and tags bind the chain`() {
        val data = JsonObject(mapOf("status" to JsonPrimitive("published"), "at" to JsonPrimitive(10)))
        val content = CompetitionHostProtocol.logContent(
            "0123456789abcdef", 1, "b".repeat(64), 1, 10, "lifecycle", data,
        )
        val body = json.parseToJsonElement(content) as JsonObject
        assertEquals("log", body["type"]?.jsonPrimitive?.content)
        assertEquals("b".repeat(64), body["prev"]?.jsonPrimitive?.content)
        val tags = CompetitionHostProtocol.logTags(
            "0123456789abcdef", "a".repeat(64), 1, "b".repeat(64), 1, "lifecycle",
        )
        assertTrue(listOf("a", "30078:${"a".repeat(64)}:cruxcoach:comp:0123456789abcdef") in tags)
        assertTrue(listOf("d", "cruxcoach:comp:0123456789abcdef:log:000001") in tags)
    }

    @Test fun `deletion keeps a tombstone and targets the concrete definition`() {
        val compId = "0123456789abcdef"
        val body = json.parseToJsonElement(
            CompetitionHostProtocol.tombstoneContent(compId, 1234),
        ) as JsonObject
        assertEquals(true, body["deleted"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals(compId, body["comp_id"]?.jsonPrimitive?.content)
        assertTrue(listOf("d", CompetitionProtocol.compDTag(compId)) in CompetitionHostProtocol.tombstoneTags(compId))
        assertEquals(
            listOf(listOf("e", "b".repeat(64)), listOf("k", CompetitionProtocol.KIND.toString())),
            CompetitionHostProtocol.deletionTags("b".repeat(64)),
        )
    }

    @Test fun `participant intent is bound to signer authority and competition`() {
        val organizer = "a".repeat(64)
        val entrant = "b".repeat(64)
        val compId = "0123456789abcdef"
        val nonce = "1234abcd"
        val competition = Competition.from(fixtureConfig(compId, organizer))
        val content = Ccj.encode(JsonObject(mapOf(
            "v" to JsonPrimitive(1), "type" to JsonPrimitive("intent"),
            "comp_id" to JsonPrimitive(compId), "op" to JsonPrimitive("checkin_request"),
            "at" to JsonPrimitive(10), "nonce" to JsonPrimitive(nonce), "data" to JsonObject(emptyMap()),
        )))
        val event = CompetitionEvent("c".repeat(64), entrant, 10, CompetitionProtocol.KIND, listOf(
            listOf("d", CompetitionProtocol.intentDTag(compId, entrant, nonce)),
            listOf("L", CompetitionProtocol.NAMESPACE), listOf("l", "intent", CompetitionProtocol.NAMESPACE),
            listOf("cc-schema", CompetitionProtocol.SCHEMA),
            listOf("a", CompetitionProtocol.competitionAddress(organizer, compId)), listOf("p", organizer),
        ), content)
        assertTrue(CompetitionProtocol.parseIntent(event, competition, organizer, 10) is CompetitionProtocol.ParsedIntent.Valid)
    }

    private fun fixtureConfig(compId: String, authority: String): JsonObject = JsonObject(mapOf(
        "comp_id" to JsonPrimitive(compId), "authority" to JsonPrimitive(authority), "authority_epoch" to JsonPrimitive(1),
        "title" to JsonPrimitive("Test"), "summary" to JsonPrimitive(""), "description" to JsonPrimitive(""),
        "visibility" to JsonPrimitive("unlisted"), "status" to JsonPrimitive("draft"), "timezone" to JsonPrimitive("UTC"),
        "starts_at" to JsonPrimitive(100), "ends_at" to JsonPrimitive(200), "registration_opens_at" to JsonPrimitive(1),
        "registration_closes_at" to JsonPrimitive(90), "checkin_opens_at" to JsonPrimitive(90), "checkin_closes_at" to JsonPrimitive(100),
        "capacity" to JsonPrimitive(10), "waitlist_enabled" to JsonPrimitive(true), "fee_msat" to JsonPrimitive(0),
        "waiver_required" to JsonPrimitive(false), "revision" to JsonPrimitive(1),
        "venue" to JsonObject(mapOf("kind" to JsonPrimitive("physical"), "name" to JsonPrimitive("Gym"))),
        "board" to JsonObject(mapOf("brand" to JsonPrimitive("kilter"), "model" to JsonPrimitive("Kilter"), "size" to JsonPrimitive("12x12"), "angle" to JsonPrimitive(40), "layout_id" to JsonPrimitive(1))),
        "divisions" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("open"), "label" to JsonPrimitive("Open"))))),
        "climbs" to kotlinx.serialization.json.JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("b1"), "climb_uuid" to JsonPrimitive("12345678-1234-4123-8123-123456789abc"), "angle" to JsonPrimitive(40), "label" to JsonPrimitive("One"), "points" to JsonPrimitive(0))))),
        "prizes" to kotlinx.serialization.json.JsonArray(emptyList()),
        "rules" to JsonObject(mapOf("climb_source" to JsonPrimitive("organizer_set"), "climb_count" to JsonPrimitive(1), "counted_climb_count" to JsonPrimitive(1), "selection_uniqueness" to JsonPrimitive("none"), "progression" to JsonPrimitive("asynchronous_turns"), "attempts_per_climb" to JsonPrimitive(5), "turn_deadline_sec" to JsonPrimitive(180), "attempt_deadline_sec" to JsonPrimitive(0), "min_rest_sec" to JsonPrimitive(0), "defer_budget_per_round" to JsonPrimitive(1), "max_consecutive_defers" to JsonPrimitive(1), "defer_slots" to JsonPrimitive(1), "scoring" to JsonPrimitive("tops_then_attempts"), "tiebreaks" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("fewest_attempts"))), "late_entry_allowed" to JsonPrimitive(false))),
        "relays" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("wss://relay.example"))),
    ))
}
