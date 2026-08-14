package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CompetitionWithdrawalTest {
    @Test
    fun `authority can honour a withdrawal after registration has closed`() {
        val fixture = CompetitionFixtures.stream("happy-sync.json")
        val parsed = CompetitionProtocol.parseCompetition(fixture.competitionEvent, 1789020000)
        assertTrue(parsed is CompetitionProtocol.ParsedCompetition.Valid)
        val competition = parsed.competition
        val pubkey = "ab".repeat(32)
        val ids = (1..7).map { it.toString().repeat(64) }
        val operations = listOf(
            "lifecycle" to obj("status" to "published", "at" to 1789019900L),
            "lifecycle" to obj("status" to "registration_open", "at" to 1789019910L),
            "registration_decision" to obj(
                "pubkey" to pubkey, "decision" to "accepted", "division" to "open", "display" to "Late leaver",
            ),
            "lifecycle" to obj("status" to "registration_closed", "at" to 1789019920L),
            "lifecycle" to obj("status" to "checkin_open", "at" to 1789019930L),
            "lifecycle" to obj("status" to "running", "at" to 1789019940L),
            "registration_decision" to obj("pubkey" to pubkey, "decision" to "withdrawn"),
        )
        val entries = operations.mapIndexed { index, (op, data) ->
            CompetitionReducer.Chained(
                entry = LogEntry(
                    seq = index + 1,
                    prev = if (index == 0) fixture.competitionEvent.id else ids[index - 1],
                    epoch = 1,
                    at = 1789019900L + index,
                    op = op,
                    actor = "authority",
                    reason = null,
                    data = data,
                ),
                eventId = ids[index],
                createdAt = 1789019900L + index,
            )
        }
        val state = CompetitionReducer.reduce(competition, fixture.competitionEvent.id, entries).state
        assertEquals("running", state.status)
        assertEquals("withdrawn", state.participants.first { it.pubkey == pubkey }.registration)
        assertFalse(state.rejected.any { it.seq == 7 })
    }

    private fun obj(vararg fields: Pair<String, Any>): JsonObject = JsonObject(
        fields.associate { (key, value) ->
            key to when (value) {
                is Long -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        },
    )
}
