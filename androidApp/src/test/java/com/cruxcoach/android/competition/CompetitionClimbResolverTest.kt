package com.cruxcoach.android.competition

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionClimb
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Whether a competition climb can actually go on the wall.
 *
 * The failure this guards against is the quiet one: navigating to a board
 * screen for a climb the phone has never downloaded, which draws an empty board
 * and reads as the app being broken. Each case here has a different fix, so
 * each has to be distinguishable.
 */
class CompetitionClimbResolverTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val repository: BoardRepository = mockk(relaxed = true)
    private val resolver = CompetitionClimbResolver(repository)

    private val real = "aaaaaaaa-1111-4111-8111-111111111111"

    private fun competition(brand: String = "kilter"): Competition = Competition.from(
        json.parseToJsonElement(
            """
            {
              "comp_id": "aa00bb11cc22dd33",
              "authority": "${"0".repeat(64)}",
              "authority_epoch": 1,
              "title": "Resolver test",
              "status": "running",
              "board": {"brand": "$brand", "model": "kilterboard-og", "layout_id": 1,
                        "size": "12x12", "angle": 40},
              "divisions": [{"id": "open", "label": "Open"}],
              "climbs": [],
              "rules": {
                "climb_source": "organizer_set", "climb_count": 1,
                "selection_uniqueness": "none", "progression": "synchronous_rounds",
                "attempts_per_climb": 3, "turn_deadline_sec": 120, "attempt_deadline_sec": 0,
                "min_rest_sec": 0, "defer_budget_per_round": 1, "max_consecutive_defers": 1,
                "defer_slots": 2, "scoring": "tops_then_attempts",
                "tiebreaks": ["fewest_attempts"], "late_entry_allowed": false
              },
              "relays": []
            }
            """.trimIndent(),
        ).jsonObject,
    )

    private fun climb(uuid: String = real, angle: Int = 40) =
        CompetitionClimb(id = "c1", climbUuid = uuid, angle = angle, label = "Blue slab", points = 100)

    @Test
    fun `a downloaded renderable climb is ready at the competition's angle`() {
        every { repository.climbExistsByUuid(real) } returns true
        every { repository.getProductSizeForClimbRender(real, "kilter") } returns 10
        every { repository.canRenderClimbOnSize(real, 10, "kilter") } returns true

        assertEquals(
            CompetitionClimbResolver.Result.Ready(real, 40),
            resolver.resolve(competition(), climb()),
        )
    }

    @Test
    fun `a climb whose board is not downloaded says so, and is retryable`() {
        every { repository.climbExistsByUuid(real) } returns false

        assertEquals(
            CompetitionClimbResolver.Result.NotInCatalogue("kilter"),
            resolver.resolve(competition(), climb()),
        )
    }

    @Test
    fun `a climb no held board size can draw is not opened`() {
        every { repository.climbExistsByUuid(real) } returns true
        every { repository.getProductSizeForClimbRender(real, "tension") } returns 4
        every { repository.canRenderClimbOnSize(real, 4, "tension") } returns false

        assertEquals(
            CompetitionClimbResolver.Result.WrongBoard("tension"),
            resolver.resolve(competition(brand = "tension"), climb()),
        )
    }

    @Test
    fun `a climb with no renderable size at all is not opened`() {
        every { repository.climbExistsByUuid(real) } returns true
        every { repository.getProductSizeForClimbRender(real, "kilter") } returns null

        assertEquals(
            CompetitionClimbResolver.Result.WrongBoard("kilter"),
            resolver.resolve(competition(), climb()),
        )
    }

    @Test
    fun `placeholder and malformed uuids never reach the board screen`() {
        for (uuid in listOf(
            "00000000-0000-0000-0000-000000000000",
            "00000001-0000-4000-8000-000000000000",
            "not-a-uuid",
            "",
        )) {
            assertEquals(
                CompetitionClimbResolver.Result.Unusable,
                resolver.resolve(competition(), climb(uuid = uuid)),
                "must refuse $uuid",
            )
        }
    }

    @Test
    fun `the brand comes from the competition, not from a default`() {
        assertEquals("tension", resolver.brandOf(competition(brand = "tension")))
        assertEquals("kilter", resolver.brandOf(competition()))
    }
}
