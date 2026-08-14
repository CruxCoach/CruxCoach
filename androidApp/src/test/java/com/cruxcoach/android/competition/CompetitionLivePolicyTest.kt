package com.cruxcoach.android.competition

import com.cruxcoach.android.ui.competition.CompetitionLivePolicy
import com.cruxcoach.domain.competition.ClimbProgress
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionState
import com.cruxcoach.domain.competition.Participant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class CompetitionLivePolicyTest {
    private val mine = "a".repeat(64)
    private val other = "b".repeat(64)
    private val third = "c".repeat(64)

    private val competition = Competition.from(Json.parseToJsonElement(
        """
        {
          "comp_id":"aa00bb11cc22dd33","authority":"${"0".repeat(64)}","authority_epoch":1,
          "title":"Live","status":"running","capacity":8,"fee_msat":0,
          "divisions":[{"id":"open","label":"Open"}],
          "climbs":[
            {"id":"one","climb_uuid":"5a7c2e18-9d40-4a37-8b61-4f2e0c95d713","angle":40,"label":"One"},
            {"id":"two","climb_uuid":"a1c93f57-6e28-4b04-9d75-2f8a1e63c0b9","angle":40,"label":"Two"},
            {"id":"three","climb_uuid":"b6d0428e-1f75-4c93-a208-7e35d1b49c60","angle":40,"label":"Three"}
          ],
          "rules":{"climb_source":"organizer_set","climb_count":3,"selection_uniqueness":"none",
            "progression":"synchronous_rounds","attempts_per_climb":3,"turn_deadline_sec":120,
            "min_rest_sec":0,"defer_budget_per_round":2,"max_consecutive_defers":1,"defer_slots":2,
            "scoring":"tops_then_attempts","tiebreaks":["fewest_attempts"],"late_entry_allowed":false},
          "relays":[]
        }
        """.trimIndent(),
    ).jsonObject)

    private fun participant(
        pubkey: String,
        used: Int = 0,
        consecutive: Int = 0,
        climbs: List<ClimbProgress> = emptyList(),
    ) = Participant(
        pubkey = pubkey,
        display = pubkey.take(1).uppercase(),
        registration = "accepted",
        checkin = "checked_in",
        defersUsedThisRound = used,
        consecutiveDefers = consecutive,
        climbs = climbs,
    )

    private fun state(status: String = "running", cursor: Int = 0) = CompetitionState(
        compId = competition.compId,
        authority = competition.authority,
        epoch = 1,
        head = "0".repeat(64),
        status = status,
        paused = status == "paused",
        cursor = cursor,
        currentClimbId = "two",
        order = listOf(other, mine, third),
        participants = listOf(participant(mine), participant(other), participant(third)),
    )

    @Test
    fun `lifecycle and queue matrix produces one personal cue`() {
        for (phase in listOf("draft", "published", "registration_open", "registration_closed", "checkin_open")) {
            assertEquals(CompetitionLivePolicy.Cue.WAITING, CompetitionLivePolicy.personalCue(state(phase), mine).kind)
        }
        assertEquals(CompetitionLivePolicy.Cue.NEXT, CompetitionLivePolicy.personalCue(state(), mine).kind)
        assertEquals(CompetitionLivePolicy.Cue.CURRENT, CompetitionLivePolicy.personalCue(state(cursor = 1), mine).kind)
        assertEquals(CompetitionLivePolicy.Cue.QUEUED, CompetitionLivePolicy.personalCue(state(cursor = 0), third).kind)
        assertEquals(CompetitionLivePolicy.Cue.NOT_QUEUED, CompetitionLivePolicy.personalCue(state(), "d".repeat(64)).kind)
        assertEquals(CompetitionLivePolicy.Cue.PAUSED, CompetitionLivePolicy.personalCue(state("paused"), mine).kind)
        assertEquals(CompetitionLivePolicy.Cue.FINISHED, CompetitionLivePolicy.personalCue(state("finished"), mine).kind)
        assertEquals(CompetitionLivePolicy.Cue.CANCELLED, CompetitionLivePolicy.personalCue(state("cancelled"), mine).kind)
        assertEquals(CompetitionLivePolicy.Cue.SPECTATOR, CompetitionLivePolicy.personalCue(state(), "").kind)
    }

    @Test
    fun `queue and rotation previews are bounded event-derived views`() {
        val queue = CompetitionLivePolicy.queue(state(), limit = 2)
        assertEquals(listOf(other, mine), queue.entries.map { it.pubkey })
        assertTrue(queue.entries.first().current)
        assertTrue(queue.entries.last().next)
        assertEquals(1, queue.hidden)

        val rotation = CompetitionLivePolicy.rotation(competition, state(), participant(mine), limit = 2)
        assertEquals(listOf("two", "three"), rotation.entries.map { it.climb.id })
        assertTrue(rotation.entries.first().current)
        assertTrue(rotation.entries.last().next)
        assertEquals(1, rotation.hidden)

        val asynchronous = competition.copy(
            rules = competition.rules.copy(progression = "asynchronous_turns"),
        )
        val personal = CompetitionLivePolicy.rotation(
            asynchronous,
            state(),
            participant(mine, climbs = listOf(ClimbProgress("one", 1, "top"))),
            limit = 3,
        )
        assertEquals(listOf("two", "three"), personal.entries.map { it.climb.id })
        assertTrue(personal.entries.first().next)
    }

    @Test
    fun `a passed position stays queued for the next round instead of becoming zero ahead`() {
        val passed = state(cursor = 2)
        val cue = CompetitionLivePolicy.personalCue(passed, mine)
        assertEquals(CompetitionLivePolicy.Cue.QUEUED, cue.kind)
        assertEquals(2, cue.ahead)
        assertEquals(1, cue.roundOffset)

        val queue = CompetitionLivePolicy.queue(passed, limit = 3)
        assertEquals(listOf(third, other, mine), queue.entries.map { it.pubkey })
        assertTrue(queue.entries.first().current)
        assertEquals(listOf(0, 1, 1), queue.entries.map { it.roundOffset })
    }

    @Test
    fun `eta is only estimated inside the currently open round`() {
        val open = state(cursor = 0).copy(turnOpenedAt = 100, turnDeadlineAt = 220)
        assertEquals(210, CompetitionLivePolicy.etaSeconds(open, third, 130))
        assertEquals(0, CompetitionLivePolicy.etaSeconds(open, other, 130))
        assertEquals(null, CompetitionLivePolicy.etaSeconds(open, mine, 221))
        assertEquals(null, CompetitionLivePolicy.etaSeconds(open.copy(cursor = 2), mine, 130))
        assertEquals(null, CompetitionLivePolicy.etaSeconds(open.copy(turnDeadlineAt = 0), mine, 130))
    }

    @Test
    fun `defer is offered only for the real current eligible request`() {
        assertTrue(CompetitionLivePolicy.defer(state(cursor = 1), competition, participant(mine), mine).allowed)
        assertEquals(
            CompetitionLivePolicy.DeferReason.NOT_YOUR_TURN,
            CompetitionLivePolicy.defer(state(), competition, participant(mine), mine).reason,
        )
        assertEquals(
            CompetitionLivePolicy.DeferReason.PAUSED,
            CompetitionLivePolicy.defer(state("paused", 1), competition, participant(mine), mine).reason,
        )
        assertEquals(
            CompetitionLivePolicy.DeferReason.BUDGET,
            CompetitionLivePolicy.defer(state(cursor = 1), competition, participant(mine, used = 2), mine).reason,
        )
        assertEquals(
            CompetitionLivePolicy.DeferReason.CONSECUTIVE,
            CompetitionLivePolicy.defer(state(cursor = 1), competition, participant(mine, consecutive = 1), mine).reason,
        )
    }

    @Test
    fun `offline and stale are transport hints not competition states`() {
        assertEquals(CompetitionLivePolicy.Sync.LIVE, CompetitionLivePolicy.syncHealth(true, 2, 100, 300).kind)
        assertEquals(CompetitionLivePolicy.Sync.OFFLINE, CompetitionLivePolicy.syncHealth(true, 0, 295, 300).kind)
        assertEquals(CompetitionLivePolicy.Sync.STALE, CompetitionLivePolicy.syncHealth(true, 0, 100, 300).kind)
        assertEquals(CompetitionLivePolicy.Sync.CONNECTING, CompetitionLivePolicy.syncHealth(false, 0, 0, 300).kind)
        assertFalse(CompetitionLivePolicy.syncHealth(true, 0, 100, 300).connectedRelays > 0)
    }
}
