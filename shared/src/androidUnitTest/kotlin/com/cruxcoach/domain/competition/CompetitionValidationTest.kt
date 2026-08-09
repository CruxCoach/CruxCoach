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
    private val real = "aaaaaaaa-1111-4111-8111-111111111111"

    private fun config(
        /** Empty means the key is omitted, which is what "absent" means on the wire. */
        climbs: String = """[{"id":"c1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100}]""",
        pool: String = "",
        climbSource: String = "organizer_set",
        uniqueness: String = "none",
        climbCount: Int = 1,
        capacity: Int = 8,
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
          "registration_closes_at": 1789003600,
          "checkin_opens_at": 1789003600,
          "checkin_closes_at": 1789005400,
          "starts_at": 1789005400,
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
            "selection_uniqueness": "$uniqueness",
            "progression": "synchronous_rounds",
            "attempts_per_climb": 3,
            "turn_deadline_sec": 120,
            "attempt_deadline_sec": 0,
            "min_rest_sec": 0,
            "defer_budget_per_round": 1,
            "max_consecutive_defers": 1,
            "defer_slots": 2,
            "scoring": "tops_then_attempts",
            "tiebreaks": ["fewest_attempts"],
            "late_entry_allowed": false
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
    fun `a unique pool has to be big enough for everyone to get a full set`() {
        val one = """{"source":"organizer_list","options":[
            {"id":"p1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100}]}"""
        val tooSmall = problems(
            config(
                climbs = "",
                pool = one,
                climbSource = "participant_choice",
                uniqueness = "unique_per_competition",
                capacity = 4,
            ),
        )
        assertTrue(tooSmall.isNotEmpty(), "somebody would lose a race they can never win")

        val two = """{"source":"organizer_list","options":[
            {"id":"p1","climb_uuid":"$real","angle":40,"label":"Blue slab","points":100},
            {"id":"p2","climb_uuid":"bbbbbbbb-2222-4222-8222-222222222222","angle":40,
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
            {"id":"p2","climb_uuid":"bbbbbbbb-2222-4222-8222-222222222222","angle":45,
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
        assertEquals(listOf("p2"), competition.climbsFor(listOf("p2")).map { it.id })
    }
}
