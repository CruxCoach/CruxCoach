package com.cruxcoach.android.competition

import com.cruxcoach.android.ui.competition.CompetitionCreateDraftPolicy
import com.cruxcoach.android.ui.competition.CompetitionCreateViewModel
import java.io.File
import java.time.Instant
import java.time.ZoneId
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
        assertEquals("automatic", rules.getValue("queue_policy").jsonPrimitive.content)
        val points = rules.getValue("score_points") as JsonObject
        assertEquals(11, points.getValue("zone").jsonPrimitive.int)
        assertEquals(29, points.getValue("top").jsonPrimitive.int)
        assertEquals(7, points.getValue("flash").jsonPrimitive.int)
        assertEquals(false, placed.containsKey("climbs"))
        assertEquals(1, ((placed.getValue("climb_pool") as JsonObject).getValue("options") as JsonArray).size)
    }

    @Test
    fun `exact local schedule preserves timezone and overlapping automatic windows`() {
        val draft = validDraft().copy(
            timezone = "Europe/Berlin",
            registrationOpensAt = "2026-10-24 09:00",
            registrationClosesAt = "2026-10-25 11:00",
            checkinOpensAt = "2026-10-25 09:00",
            checkinClosesAt = "2026-10-25 11:30",
            startsAt = "2026-10-25 10:00",
            endsAt = "2026-10-25 14:00",
        )

        val schedule = requireNotNull(CompetitionCreateDraftPolicy.schedule(draft))
        assertEquals("Europe/Berlin", schedule.timezone)
        assertEquals(90 * 60, schedule.checkinClosesAt - schedule.startsAt)
        assertEquals(4 * 3_600, schedule.endsAt - schedule.startsAt,
            "local fields are interpreted in the organizer's timezone")
        assertNull(CompetitionCreateDraftPolicy.validateStep(draft, 1))
    }

    @Test
    fun `schedule defaults are explicit local minutes and invalid ordering fails closed`() {
        val defaulted = CompetitionCreateDraftPolicy.withDefaultSchedule(
            validDraft(), Instant.parse("2026-08-14T12:34:56Z"), ZoneId.of("UTC"),
        )
        assertEquals("2026-08-15 12:34", defaulted.startsAt)
        assertEquals("2026-08-15 15:34", defaulted.endsAt)
        assertEquals("UTC", defaulted.timezone)
        val migrated = CompetitionCreateDraftPolicy.withDefaultSchedule(
            validDraft().copy(startsInHours = "48", durationHours = "4"),
            Instant.parse("2026-08-14T12:34:56Z"), ZoneId.of("UTC"),
        )
        assertEquals("2026-08-16 12:34", migrated.startsAt)
        assertEquals("2026-08-16 16:34", migrated.endsAt)
        assertEquals(
            "invalid_schedule",
            CompetitionCreateDraftPolicy.validateStep(
                defaulted.copy(registrationClosesAt = "2026-08-16 12:34"), 1,
            ),
        )
        assertEquals(
            "invalid_schedule",
            CompetitionCreateDraftPolicy.validateStep(defaulted.copy(timezone = "Mars/Olympus"), 1),
        )
    }

    @Test
    fun `divisions fees and participant policy validate as authored`() {
        val draft = CompetitionCreateDraftPolicy.withDefaultSchedule(validDraft()).copy(
            divisions = "open | Open\nwomen | Women",
            feeSats = "250", feeLnurl = "climber@example.com",
            prizes = "open_first | 1 | cash | First place | 50000 | open\nwomen_goods | 1 | non_cash | Shoes | 0 | women",
            prizeClaimDays = "21",
            eligibility = "Age 16+", participantInstructions = "Arrive early",
            spectatorInfo = "Balcony", refundPolicy = "Until Friday",
            waiverRequired = true, waiver = "I accept the risks.",
        )
        val divisions = requireNotNull(CompetitionCreateDraftPolicy.parseDivisions(draft))
        assertEquals(listOf("open", "women"), divisions.map { it.getValue("id").jsonPrimitive.content })
        val prizes = requireNotNull(CompetitionCreateDraftPolicy.parsePrizes(draft, divisions))
        assertEquals(50_000_000L, prizes.first().getValue("value_msat").jsonPrimitive.content.toLong())
        assertEquals(false, prizes.last().containsKey("value_msat"))
        assertNull(CompetitionCreateDraftPolicy.validateStep(draft, 3))
        assertEquals("invalid_fee", CompetitionCreateDraftPolicy.validateStep(draft.copy(feeLnurl = ""), 3))
        assertEquals("required_waiver", CompetitionCreateDraftPolicy.validateStep(draft.copy(waiver = ""), 3))
        assertEquals("invalid_divisions", CompetitionCreateDraftPolicy.validateStep(draft.copy(divisions = "bad id | A"), 3))
        assertEquals("invalid_prizes", CompetitionCreateDraftPolicy.validateStep(draft.copy(prizes = "p | 0 | cash | Bad | 1 | open"), 3))
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
            "registrationOpensAt", "registrationClosesAt", "checkinOpensAt",
            "checkinClosesAt", "startsAt", "endsAt", "timezone",
            "divisions", "feeSats", "feeLnurl", "eligibility", "waiver",
            "participantInstructions", "spectatorInfo", "refundPolicy",
            "prizes", "prizeClaimDays",
        ).forEach { assertContains(source, it) }
        assertContains(source, "searchCatalogue")
        assertContains(source, "addCatalogueClimb")
    }

    @Test
    fun `catalogue picks append safe unique advanced rows`() {
        val first = CompetitionCreateDraftPolicy.appendCatalogueClimb("", uuid, "Blue | slab\nleft")
        assertEquals("$uuid | Blue   slab left", first)
        assertEquals(first, CompetitionCreateDraftPolicy.appendCatalogueClimb(first, uuid.uppercase(), "Duplicate"))
        assertEquals(first, CompetitionCreateDraftPolicy.appendCatalogueClimb(first, "not-a-uuid", "Invalid"))
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
