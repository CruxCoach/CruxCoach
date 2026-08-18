package com.cruxcoach.android.competition

import com.cruxcoach.android.ui.competition.CompetitionDetailViewModel
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionState
import com.cruxcoach.domain.competition.Participant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * What the competition screen is allowed to offer.
 *
 * Every case here is one where drawing a control would be worse than drawing a
 * sentence: an attempt the reducer will reject, a climb somebody else holds, a
 * turn that is not this climber's. The screen asks these questions before it
 * draws anything, so they are tested without a device.
 */
class CompetitionSelectionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun competition(
        climbSource: String = "participant_choice",
        uniqueness: String = "unique_per_competition",
        climbCount: Int = 2,
        feeMsat: Long = 0,
        minRestSec: Int = 0,
    ): Competition = Competition.from(
        json.parseToJsonElement(
            """
            {
              "comp_id": "aa00bb11cc22dd33",
              "authority": "${"0".repeat(64)}",
              "authority_epoch": 1,
              "title": "Selection test",
              "status": "running",
              "registration_opens_at": 0,
              "registration_closes_at": 10000,
              "checkin_opens_at": 0,
              "checkin_closes_at": 10000,
              "starts_at": 0,
              "ends_at": 10000,
              "capacity": 4,
              "fee_msat": $feeMsat,
              "divisions": [{"id": "open", "label": "Open"}],
              "climbs": [
                {"id": "s1", "climb_uuid": "5a7c2e18-9d40-4a37-8b61-4f2e0c95d713",
                 "angle": 40, "label": "Organizer one", "points": 100}
              ],
              "climb_pool": {
                "source": "organizer_list",
                "options": [
                  {"id": "p1", "climb_uuid": "a1c93f57-6e28-4b04-9d75-2f8a1e63c0b9",
                   "angle": 40, "label": "Blue slab", "points": 100},
                  {"id": "p2", "climb_uuid": "b6d0428e-1f75-4c93-a208-7e35d1b49c60",
                   "angle": 40, "label": "Red roof", "points": 100},
                  {"id": "p3", "climb_uuid": "c8f24b06-3a91-4e57-b0d4-9c6153e8a2f7",
                   "angle": 45, "label": "Yellow arete", "points": 100}
                ]
              },
              "rules": {
                "climb_source": "$climbSource",
                "climb_count": $climbCount,
                "selection_uniqueness": "$uniqueness",
                "progression": "asynchronous_turns",
                "attempts_per_climb": 2,
                "turn_deadline_sec": 120,
                "attempt_deadline_sec": 0,
                "min_rest_sec": $minRestSec,
                "defer_budget_per_round": 1,
                "max_consecutive_defers": 1,
                "defer_slots": 2,
                "scoring": "tops_then_attempts",
                "tiebreaks": ["fewest_attempts"],
                "late_entry_allowed": false
              },
              "relays": []
            }
            """.trimIndent(),
        ).jsonObject,
    )

    private val mine = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun ui(
        competition: Competition = competition(),
        participants: List<Participant> = emptyList(),
        claims: Map<String, String> = emptyMap(),
        status: String = "running",
        cursor: Int = 0,
        order: List<String> = listOf(mine),
        paused: Boolean = false,
    ): CompetitionDetailViewModel.Ui = CompetitionDetailViewModel.Ui(
        snapshot = CompetitionRelayClient.Snapshot(
            competition = competition,
            state = CompetitionState(
                compId = competition.compId,
                authority = competition.authority,
                epoch = 1,
                head = "0".repeat(64),
                status = status,
                paused = paused,
                cursor = cursor,
                order = order,
                participants = participants,
                claims = claims,
            ),
        ),
        myPubkey = mine,
    )

    private fun entrant(
        selections: List<String> = listOf("p1", "p2"),
        checkin: String = "checked_in",
        registration: String = "accepted",
        payment: String = "not_required",
        result: String = "active",
        lastAttemptAt: Long = 0,
        climbs: List<com.cruxcoach.domain.competition.ClimbProgress> = emptyList(),
    ) = Participant(
        pubkey = mine,
        display = "Me",
        division = "open",
        registration = registration,
        payment = payment,
        checkin = checkin,
        selections = selections,
        result = result,
        climbs = climbs,
        lastAttemptAt = lastAttemptAt,
    )

    @Test
    fun `only climbs nobody holds are offered`() {
        val screen = ui(claims = mapOf("p2" to other))
        assertEquals(listOf("p1", "p3"), screen.freePoolClimbs.map { it.id })
    }

    @Test
    fun `without uniqueness every pool climb stays available`() {
        val screen = ui(competition = competition(uniqueness = "none"), claims = mapOf("p2" to other))
        assertEquals(listOf("p1", "p2", "p3"), screen.freePoolClimbs.map { it.id })
    }

    @Test
    fun `a climb this entrant already holds is not a loss`() {
        val screen = ui(participants = listOf(entrant()), claims = mapOf("p1" to mine, "p2" to mine))
        assertEquals(0, screen.climbsStillToPick)
    }

    @Test
    fun `losing one climb leaves exactly one to pick again`() {
        val screen = ui(
            participants = listOf(entrant(selections = listOf("p1"))),
            claims = mapOf("p1" to mine, "p2" to other),
        )
        assertEquals(1, screen.climbsStillToPick)
        assertEquals(listOf("p3"), screen.freePoolClimbs.filter { it.id !in listOf("p1") }.map { it.id })
    }

    @Test
    fun `legacy selections never narrow the live pool`() {
        val screen = ui(participants = listOf(entrant(selections = listOf("p1", "p3"))))
        assertEquals(listOf("p1", "p2", "p3"), screen.remainingClimbs.map { it.climb.id })
    }

    @Test
    fun `organizer-set competitions offer the competition's climbs`() {
        val screen = ui(
            competition = competition(climbSource = "organizer_set", uniqueness = "none"),
            participants = listOf(entrant(selections = emptyList())),
        )
        assertEquals(listOf("s1"), screen.remainingClimbs.map { it.climb.id })
    }

    @Test
    fun `a topped climb drops off the list and a partly used one keeps its count`() {
        val screen = ui(
            participants = listOf(
                entrant(
                    climbs = listOf(
                        com.cruxcoach.domain.competition.ClimbProgress("p1", attemptsUsed = 1, outcome = "top"),
                        com.cruxcoach.domain.competition.ClimbProgress("p2", attemptsUsed = 1, outcome = "attempted"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("p2", "p3"), screen.remainingClimbs.map { it.climb.id })
        assertEquals(listOf(1, 2), screen.remainingClimbs.map { it.attemptsLeft })
    }

    @Test
    fun `a climb with no attempts left is not offered`() {
        val screen = ui(
            participants = listOf(
                entrant(
                    selections = listOf("p1"),
                    climbs = listOf(
                        com.cruxcoach.domain.competition.ClimbProgress("p1", attemptsUsed = 2, outcome = "dnf"),
                    ),
                ),
            ),
        )
        assertEquals(listOf("p2", "p3"), screen.remainingClimbs.map { it.climb.id })
    }

    @Test
    fun `next person wraps to the first entrant for the next round`() {
        val screen = ui(
            participants = listOf(entrant()),
            order = listOf(other, mine),
            cursor = 1,
        )
        assertEquals(other, screen.nextClimber)
    }

    @Test
    fun `may act only when every rule the reducer applies is satisfied`() {
        assertTrue(ui(participants = listOf(entrant())).mayAct(1000), "their turn, checked in, active")

        assertFalse(ui(participants = listOf(entrant()), cursor = -1).mayAct(1000), "no turn open")
        assertFalse(
            ui(participants = listOf(entrant()), order = listOf(other, mine), cursor = 0).mayAct(1000),
            "somebody else's turn",
        )
        assertFalse(ui(participants = listOf(entrant()), status = "paused").mayAct(1000), "not running")
        assertFalse(ui(participants = listOf(entrant()), paused = true).mayAct(1000), "paused")
        assertFalse(ui(participants = listOf(entrant(checkin = "none"))).mayAct(1000), "not checked in")
        assertFalse(
            ui(participants = listOf(entrant(registration = "waitlisted"))).mayAct(1000),
            "not accepted",
        )
        assertFalse(ui(participants = listOf(entrant(result = "disqualified"))).mayAct(1000), "out")
        assertFalse(ui(participants = emptyList()).mayAct(1000), "not entered at all")
    }

    @Test
    fun `an unpaid entrant may not act in a paid competition`() {
        val paid = competition(feeMsat = 2_000_000)
        assertFalse(ui(paid, listOf(entrant(payment = "pending"))).mayAct(1000))
        assertTrue(ui(paid, listOf(entrant(payment = "settled"))).mayAct(1000))
    }

    @Test
    fun `a resting climber may not act until the rest is over`() {
        val resting = competition(minRestSec = 300)
        val screen = ui(resting, listOf(entrant(lastAttemptAt = 1000)))
        assertFalse(screen.mayAct(1100), "still resting")
        assertEquals(200, screen.restSecondsLeft(1100))
        assertTrue(screen.mayAct(1300), "rest is over")
        assertEquals(0, screen.restSecondsLeft(1300))
    }

    @Test
    fun `every offered climb carries the board uuid the app needs to load it`() {
        val screen = ui(participants = listOf(entrant()))
        screen.remainingClimbs.forEach {
            assertTrue(
                CompetitionProtocolUuid.isReal(it.climb.climbUuid),
                "a competition climb must be loadable on a board: ${it.climb.climbUuid}",
            )
        }
    }
}

/** Small indirection so the test reads as what it is checking. */
private object CompetitionProtocolUuid {
    fun isReal(value: String): Boolean =
        com.cruxcoach.domain.competition.CompetitionProtocol.isClimbUuid(value) &&
            !com.cruxcoach.domain.competition.CompetitionProtocol.isPlaceholderUuid(value)
}
