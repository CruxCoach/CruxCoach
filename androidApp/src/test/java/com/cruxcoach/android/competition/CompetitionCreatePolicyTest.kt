package com.cruxcoach.android.competition

import com.cruxcoach.android.ui.competition.CompetitionCreateDraftPolicy
import com.cruxcoach.android.ui.competition.CompetitionCreateViewModel
import java.io.File
import kotlinx.serialization.json.int
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompetitionCreatePolicyTest {
    private val uuid = "f47ac10b-58cc-4372-a567-0e02b2c3d479"

    private fun validDraft() = CompetitionCreateViewModel.Draft(
        title = "Fixture competition",
        venue = "Fixture gym",
        boardSize = "12x12",
        climbs = "$uuid | Test boulder | 120 | 42",
    )

    @Test
    fun `climb rows keep point values separate from zone holds`() {
        val climb = CompetitionCreateDraftPolicy.parseClimbs(validDraft()).single()

        assertEquals(120, climb.getValue("points").jsonPrimitive.int)
        assertEquals(42, climb.getValue("zone_hold").jsonPrimitive.int)
        assertEquals(uuid, climb.getValue("climb_uuid").jsonPrimitive.content)
    }

    @Test
    fun `best N must fit the configured result count and pool`() {
        assertNull(CompetitionCreateDraftPolicy.validateStep(validDraft(), 2))
        assertEquals(
            "invalid_climb_count",
            CompetitionCreateDraftPolicy.validateStep(validDraft().copy(climbCount = "2"), 2),
        )
        assertEquals(
            "invalid_counted_climb_count",
            CompetitionCreateDraftPolicy.validateStep(validDraft().copy(countedClimbCount = "2"), 2),
        )
    }

    @Test
    fun `participant choice rejects per boulder scoring modes`() {
        val draft = validDraft().copy(climbSource = "participant_choice", scoring = "points_sum")
        assertEquals("invalid_choice_scoring", CompetitionCreateDraftPolicy.validateStep(draft, 2))
    }

    @Test
    fun `participant choice writes a pool and the selected scoring rules`() {
        val draft = validDraft().copy(
            climbSource = "participant_choice",
            scoring = "achievement_points",
            progression = "synchronous_rounds",
            selectionUniqueness = "unique_per_competition",
            lateEntryAllowed = true,
            zonePoints = "11",
            topPoints = "29",
            flashPoints = "7",
        )
        val climbs = CompetitionCreateDraftPolicy.parseClimbs(draft)
        val rules = CompetitionCreateDraftPolicy.rules(draft, 1, 1)
        val placed = CompetitionCreateDraftPolicy.placeClimbs(
            JsonObject(mapOf("climbs" to JsonArray(climbs))), draft.climbSource, climbs,
        )

        assertEquals("participant_choice", rules.getValue("climb_source").jsonPrimitive.content)
        assertEquals("synchronous_rounds", rules.getValue("progression").jsonPrimitive.content)
        assertEquals("unique_per_competition", rules.getValue("selection_uniqueness").jsonPrimitive.content)
        assertEquals(true, rules.getValue("late_entry_allowed").jsonPrimitive.boolean)
        val points = rules.getValue("score_points") as JsonObject
        assertEquals(11, points.getValue("zone").jsonPrimitive.int)
        assertEquals(29, points.getValue("top").jsonPrimitive.int)
        assertEquals(7, points.getValue("flash").jsonPrimitive.int)
        assertEquals(false, placed.containsKey("climbs"))
        assertEquals(1, ((placed.getValue("climb_pool") as JsonObject).getValue("options") as JsonArray).size)
    }

    @Test
    fun `creation UI exposes the protocol format axes instead of hard coding them`() {
        val source = listOf(
            File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionCreateScreen.kt"),
            File("androidApp/src/main/java/com/cruxcoach/android/ui/competition/CompetitionCreateScreen.kt"),
        ).first(File::isFile).readText()

        listOf(
            "participant_choice", "organizer_set", "asynchronous_turns", "synchronous_rounds",
            "tops_then_attempts", "achievement_points", "points_sum", "hardest_n",
            "waitlistEnabled", "lateEntryAllowed", "countedClimbCount",
        ).forEach { assertContains(source, it) }
    }

    @Test
    fun `unpublished wizard state is restorable and explicitly discardable`() {
        val viewModel = listOf(
            File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionCreateViewModel.kt"),
            File("androidApp/src/main/java/com/cruxcoach/android/ui/competition/CompetitionCreateViewModel.kt"),
        ).first(File::isFile).readText()
        val screen = listOf(
            File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionCreateScreen.kt"),
            File("androidApp/src/main/java/com/cruxcoach/android/ui/competition/CompetitionCreateScreen.kt"),
        ).first(File::isFile).readText()

        assertContains(viewModel, "SavedStateHandle")
        assertContains(viewModel, "competition_create_draft_v1")
        assertContains(viewModel, "fun discardDraft()")
        assertContains(screen, "comp_create_discard_title")
        assertContains(screen, "competition_create_discard")
    }
}
