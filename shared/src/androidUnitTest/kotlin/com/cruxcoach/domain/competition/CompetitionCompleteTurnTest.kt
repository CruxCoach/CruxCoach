package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

class CompetitionCompleteTurnTest {
    private val fixture = CompetitionFixtures.stream("happy-sync.json")
    private val competition = (
        CompetitionProtocol.parseCompetition(fixture.competitionEvent, 1789020000L)
            as CompetitionProtocol.ParsedCompetition.Valid
        ).competition.copy(rules = (
            CompetitionProtocol.parseCompetition(fixture.competitionEvent, 1789020000L)
                as CompetitionProtocol.ParsedCompetition.Valid
            ).competition.rules.copy(minRestSec = 0))
    private val climbId = (competition.climbs + competition.climbPool).first().id
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    private fun state(cursor: Int) = CompetitionReducer.initialState(
        competition, fixture.competitionEvent.id,
    ).copy(
        status = "running",
        round = 3,
        cursor = cursor,
        turnOpenedAt = competition.startsAt,
        turnDeadlineAt = competition.startsAt + competition.rules.turnDeadlineSec,
        order = listOf(alice, bob),
        participants = listOf(alice, bob).map { pubkey ->
            Participant(
                pubkey = pubkey,
                registration = "accepted",
                payment = "settled",
                checkin = "checked_in",
                defersUsedThisRound = 1,
                consecutiveDefers = 1,
            )
        },
    )

    private fun complete(state: CompetitionState, pubkey: String): CompetitionState =
        CompetitionReducer.applyEntry(
            state,
            LogEntry(
                seq = 1,
                prev = state.head,
                epoch = state.epoch,
                at = competition.startsAt + 1,
                op = "complete_turn",
                actor = "authority",
                reason = null,
                data = JsonObject(mapOf(
                    "pubkey" to JsonPrimitive(pubkey),
                    "climb_id" to JsonPrimitive(climbId),
                    "outcome" to JsonPrimitive("top"),
                    "attempt_no" to JsonPrimitive(1),
                )),
            ),
            competition,
        )

    @Test fun `complete turn records exactly one result and opens next eligible climber`() {
        val result = complete(state(cursor = 0), alice)
        assertTrue(result.rejected.isEmpty())
        assertEquals(1, result.participant(alice)?.climb(climbId)?.attemptsUsed)
        assertEquals(1, result.cursor)
        assertEquals(3, result.round)

        val duplicate = complete(result, alice)
        assertEquals("not_current_turn", duplicate.rejected.single().code)
        assertEquals(1, duplicate.participant(alice)?.climb(climbId)?.attemptsUsed)
    }

    @Test fun `complete final turn starts next round at first eligible climber and resets defers`() {
        val result = complete(state(cursor = 1), bob)
        assertTrue(result.rejected.isEmpty())
        assertEquals(4, result.round)
        assertEquals(0, result.cursor)
        assertTrue(result.participants.all { it.defersUsedThisRound == 0 && it.consecutiveDefers == 0 })
    }

    @Test fun `complete turn rejects a participant who does not own the open turn`() {
        val result = complete(state(cursor = 0), bob)
        assertEquals("not_current_turn", result.rejected.single().code)
        assertTrue(result.participants.all { it.climbs.isEmpty() })
        assertEquals(0, result.cursor)
    }

    @Test fun `default queue order is deterministic and competition scoped`() {
        val entrants = listOf(bob, alice, "c".repeat(64))
        val first = CompetitionProtocol.defaultQueueOrder("0123456789abcdef", entrants)
        assertEquals(first, CompetitionProtocol.defaultQueueOrder("0123456789abcdef", entrants.reversed()))
        assertEquals(listOf(bob, "c".repeat(64), alice), first, "must match the Web sha256(comp_id + pubkey) order")
        assertEquals(entrants.toSet(), first.toSet())
    }

    @Test fun `seed open validates and opens the first eligible turn atomically`() {
        val before = state(cursor = -1).copy(round = 0, order = emptyList(), turnOpenedAt = 0, turnDeadlineAt = 0)
        val at = competition.startsAt + 1
        val result = CompetitionReducer.applyEntry(
            before,
            LogEntry(
                seq = 1, prev = before.head, epoch = before.epoch, at = at,
                op = "queue", actor = "authority", reason = null,
                data = JsonObject(mapOf(
                    "action" to JsonPrimitive("seed_open"),
                    "order" to JsonArray(listOf(JsonPrimitive(bob), JsonPrimitive(alice))),
                )),
            ),
            competition,
        )
        assertTrue(result.rejected.isEmpty())
        assertEquals(listOf(bob, alice), result.order)
        assertEquals(1, result.round)
        assertEquals(0, result.cursor)
        assertEquals(at, result.turnOpenedAt)
        assertEquals(at + competition.rules.turnDeadlineSec, result.turnDeadlineAt)
    }
}
