package com.cruxcoach.domain.competition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The climb rules, as this client enforces them.
 *
 * These have to agree with `tools/competition-climb-ref.test.mjs` on the
 * website exactly. A competition the website refuses to publish and this client
 * happily reads — or the reverse — is a competition where the two disagree
 * about what is legal, which is the failure the whole conformance set exists to
 * prevent.
 */
class CompetitionValidationTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val real = "a1c93f57-6e28-4b04-9d75-2f8a1e63c0b9"

    private fun config(
        /** Empty means the key is omitted, which is what "absent" means on the wire. */
        climbs: String = """[{"id":"c1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100}]""",
        pool: String = "",
        climbSource: String = "organizer_set",
        uniqueness: String = "none",
        climbCount: Int = 1,
        countedClimbCount: Int? = null,
        capacity: Int = 8,
        registrationClose: Long = 1789003600,
        checkinOpen: Long = 1789003600,
        checkinClose: Long = 1789005400,
        startsAt: Long = 1789005400,
        lateEntryAllowed: Boolean = false,
        scoring: String = "tops_then_attempts",
        scorePoints: String = "",
    ) = json.parseToJsonElement(
        """
        {
          "comp_id": "aa00bb11cc22dd33",
          "authority": "${"0".repeat(64)}",
          "authority_epoch": 1,
          "title": "Climb reference test",
          "summary": "x",
          "description": "x",
          "organizer": {"name": "Test", "contact": "test@example.invalid"},
          "visibility": "public",
          "status": "draft",
          "timezone": "Europe/Berlin",
          "registration_opens_at": 1789000000,
          "registration_closes_at": $registrationClose,
          "checkin_opens_at": $checkinOpen,
          "checkin_closes_at": $checkinClose,
          "starts_at": $startsAt,
          "ends_at": 1789012600,
          "capacity": $capacity,
          "waitlist_enabled": true,
          "venue": {"kind": "physical", "name": "Test wall", "address": "Loopback 1"},
          "board": {"brand": "kilter", "model": "kilterboard-og", "layout_id": 1,
                    "size": "12x12", "angle": 40},
          "divisions": [{"id": "open", "label": "Open"}],
          "eligibility": "x",
          "waiver": "x",
          "waiver_required": false,
          "participant_instructions": "x",
          "spectator_info": "x",
          "refund_policy": "x",
          "fee_msat": 0,
          "prizes": [],
          "rules": {
            "climb_source": "$climbSource",
            "climb_count": $climbCount,
            ${if (countedClimbCount == null) "" else "\"counted_climb_count\": $countedClimbCount,"}
            "selection_uniqueness": "$uniqueness",
            "progression": "synchronous_rounds",
            "attempts_per_climb": 3,
            "turn_deadline_sec": 120,
            "attempt_deadline_sec": 0,
            "min_rest_sec": 0,
            "defer_budget_per_round": 1,
            "max_consecutive_defers": 1,
            "defer_slots": 2,
            "scoring": "$scoring",
            ${if (scorePoints.isEmpty()) "" else "\"score_points\": $scorePoints,"}
            "tiebreaks": ["fewest_attempts"],
            "late_entry_allowed": $lateEntryAllowed
          },
          ${if (climbs.isEmpty()) "" else "\"climbs\": $climbs,"}
          ${if (pool.isEmpty()) "" else "\"climb_pool\": $pool,"}
          "relays": ["wss://relay.example.invalid"],
          "created_at": 1789000000,
          "revision": 1
        }
        """.trimIndent(),
    ).jsonObject

    private fun problems(payload: kotlinx.serialization.json.JsonObject) =
        CompetitionValidation.validate(Competition.from(payload))

    @Test
    fun `a competition naming a real climb validates`() {
        val found = problems(config())
        assertTrue(found.isEmpty(), found.joinToString())
    }

    @Test
    fun `zone hold is parsed and must be positive when present`() {
        val marked = Competition.from(
            config(climbs = """[{"id":"c1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100,"zone_hold":321}]"""),
        )
        assertEquals(321, marked.climbs.single().zoneHold)
        assertTrue(CompetitionValidation.validate(marked).isEmpty())

        val invalid = problems(
            config(climbs = """[{"id":"c1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100,"zone_hold":0}]"""),
        )
        assertTrue(invalid.any { it.field == "climbs" && "zone_hold" in it.message })
    }

    @Test
    fun `registration and check-in may overlap`() {
        val found = problems(config(registrationClose = 1789004200, checkinOpen = 1789003600))
        assertTrue(found.isEmpty(), found.joinToString())
    }

    @Test
    fun `registration check-in and running windows may overlap`() {
        val allowed = problems(config(registrationClose = 1789005500, checkinClose = 1789005600))
        assertTrue(allowed.isEmpty(), allowed.joinToString())
    }

    @Test
    fun `Zone Top and Flash scoring requires explicit points`() {
        assertFalse(problems(config(scoring = "achievement_points")).isEmpty())
        val valid = problems(
            config(
                scoring = "achievement_points",
                scorePoints = """{"zone":10,"top":15,"flash":5}""",
            ),
        )
        assertTrue(valid.isEmpty(), valid.joinToString())
    }

    @Test
    fun `Zone Top and Flash points stack exactly once per climb`() {
        val competition = Competition.from(
            config(
                scoring = "achievement_points",
                scorePoints = """{"zone":10,"top":15,"flash":5}""",
                climbs = """[
                  {"id":"zone","climb_uuid":"$real","angle":40,"label":"Zone","points":0},
                  {"id":"top","climb_uuid":"2a9d3f57-6e28-4b04-9d75-2f8a1e63c0b8","angle":40,"label":"Top","points":0},
                  {"id":"flash","climb_uuid":"3a9d3f57-6e28-4b04-9d75-2f8a1e63c0b7","angle":40,"label":"Flash","points":0}
                ]""",
                climbCount = 3,
            ),
        )
        val participant = Participant(
            pubkey = "p", display = "Pat", division = "open",
            registration = "accepted", checkin = "checked_in",
            climbs = listOf(
                ClimbProgress("zone", 1, "zone", 1),
                ClimbProgress("top", 2, "top", 2),
                ClimbProgress("flash", 1, "top", 3),
            ),
        )
        val state = CompetitionState(
            compId = competition.compId, authority = competition.authority,
            epoch = 1, head = "head", status = "running",
            participants = listOf(participant), order = listOf("p"),
        )
        assertEquals(65, CompetitionScoring.standings(state, competition).single().points)
    }

    @Test
    fun `best N is explicit and old events still count M`() {
        val climbs = """[
          {"id":"a","climb_uuid":"$real","angle":40,"label":"A","points":500},
          {"id":"b","climb_uuid":"2a9d3f57-6e28-4b04-9d75-2f8a1e63c0b8","angle":40,"label":"B","points":100},
          {"id":"c","climb_uuid":"3a9d3f57-6e28-4b04-9d75-2f8a1e63c0b7","angle":40,"label":"C","points":300}
        ]"""
        val bestTwo = Competition.from(config(climbs = climbs, climbCount = 2, countedClimbCount = 2))
        assertEquals(2, bestTwo.rules.countedClimbCount)
        assertTrue(CompetitionValidation.validate(bestTwo).isEmpty())
        assertTrue(problems(config(climbs = climbs, climbCount = 3, countedClimbCount = 4))
            .any { it.field == "rules.counted_climb_count" })

        val legacy = Competition.from(config(climbs = climbs, climbCount = 3))
        assertEquals(3, legacy.rules.countedClimbCount)

        val participant = Participant(
            pubkey = "p", display = "Pat", division = "open",
            registration = "accepted", checkin = "checked_in",
            climbs = listOf(
                ClimbProgress("a", 3, "top", 30),
                ClimbProgress("b", 1, "top", 10),
                ClimbProgress("c", 2, "top", 20),
            ),
        )
        fun standing(competition: Competition) = CompetitionScoring.standings(
            CompetitionState(
                compId = competition.compId, authority = competition.authority,
                epoch = 1, head = "head", status = "running",
                participants = listOf(participant), order = listOf("p"),
            ),
            competition,
        ).single()
        val selected = standing(bestTwo)
        assertEquals(2, selected.tops)
        assertEquals(3, selected.attempts)
        assertEquals(3, standing(legacy).tops)
    }

    @Test
    fun `a placeholder climb uuid is refused`() {
        for (uuid in listOf(
            "00000000-0000-0000-0000-000000000000",
            "00000001-0000-4000-8000-000000000000",
            "11111111-1111-1111-1111-111111111111",
        )) {
            val found = problems(
                config(climbs = """[{"id":"c1","climb_uuid":"$uuid","angle":40,"label":"Q1","points":100}]"""),
            )
            assertTrue(found.isNotEmpty(), "$uuid must be refused")
        }
    }

    @Test
    fun `the same climb twice is refused`() {
        val found = problems(
            config(
                climbs = """
                [{"id":"c1","climb_uuid":"$real","angle":40,"label":"One","points":100},
                 {"id":"c2","climb_uuid":"$real","angle":40,"label":"Two","points":100}]
                """.trimIndent(),
                climbCount = 2,
            ),
        )
        assertTrue(found.isNotEmpty(), "two rounds on one climb is a paste error, not a format")
    }

    @Test
    fun `participant choice needs a pool`() {
        val found = problems(
            config(climbs = "", climbSource = "participant_choice", uniqueness = "unique_per_competition"),
        )
        assertTrue(found.isNotEmpty())
    }

    @Test
    fun `a legacy unique pool is sized for Best-N not participant capacity`() {
        val one = """{"source":"organizer_list","options":[
            {"id":"p1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100}]}"""
        val enoughForBestN = problems(
            config(
                climbs = "",
                pool = one,
                climbSource = "participant_choice",
                uniqueness = "unique_per_competition",
                capacity = 4,
            ),
        )
        assertTrue(enoughForBestN.isEmpty(), enoughForBestN.joinToString())

        val two = """{"source":"organizer_list","options":[
            {"id":"p1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100},
            {"id":"p2","climb_uuid":"b6d0428e-1f75-4c93-a208-7e35d1b49c60","angle":40,
             "label":"Red roof","points":100}]}"""
        val enough = problems(
            config(
                climbs = "",
                pool = two,
                climbSource = "participant_choice",
                uniqueness = "unique_per_competition",
                capacity = 2,
            ),
        )
        assertTrue(enough.isEmpty(), enough.joinToString())
    }

    @Test
    fun `the uuid rules agree with the website's, character for character`() {
        // Both clients decide this the same way or a competition is publishable
        // on one and unreadable on the other.
        assertTrue(CompetitionProtocol.isClimbUuid(real))
        assertTrue(CompetitionProtocol.isClimbUuid("a".repeat(32)))
        assertFalse(CompetitionProtocol.isClimbUuid("a".repeat(31)))
        assertFalse(CompetitionProtocol.isClimbUuid("not a uuid"))

        assertTrue(CompetitionProtocol.isPlaceholderUuid("00000000-0000-0000-0000-000000000000"))
        assertTrue(CompetitionProtocol.isPlaceholderUuid("00000007-0000-4000-8000-000000000000"))
        assertTrue(CompetitionProtocol.isPlaceholderUuid("ffffffff-ffff-ffff-ffff-ffffffffffff"))
        assertFalse(CompetitionProtocol.isPlaceholderUuid(real))
        assertFalse(CompetitionProtocol.isPlaceholderUuid("not a uuid"))
    }

    @Test
    fun `the pool is parsed into the model, so the app can offer it`() {
        val pool = """{"source":"organizer_list","options":[
            {"id":"p1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100},
            {"id":"p2","climb_uuid":"b6d0428e-1f75-4c93-a208-7e35d1b49c60","angle":45,
             "label":"Red roof","points":150}]}"""
        val competition = Competition.from(
            config(
                climbs = "",
                pool = pool,
                climbSource = "participant_choice",
                uniqueness = "unique_per_competition",
                capacity = 2,
            ),
        )
        assertEquals(listOf("p1", "p2"), competition.climbPool.map { it.id })
        assertEquals(45, competition.climbPool[1].angle)
        assertEquals("Red roof", competition.climb("p2")?.label)
        assertEquals(listOf("p1", "p2"), competition.climbsFor(listOf("p2")).map { it.id })
    }
}
