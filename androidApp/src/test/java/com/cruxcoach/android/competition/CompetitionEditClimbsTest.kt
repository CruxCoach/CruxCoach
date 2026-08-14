package com.cruxcoach.android.competition

import com.cruxcoach.android.ui.competition.competitionClimbsEditorPatch
import com.cruxcoach.android.ui.competition.competitionClimbsEditorText
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionClimb
import com.cruxcoach.domain.competition.CompetitionRules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContains
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CompetitionEditClimbsTest {
    private val firstUuid = "11111111-1111-4111-8111-111111111111"
    private val secondUuid = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `editor prefill round trips stable climb fields`() {
        val competition = competition(participantChoice = false, counted = 1)
        assertEquals("$firstUuid | First | 10 | 7", competitionClimbsEditorText(competition.climbs))

        val patch = competitionClimbsEditorPatch(competition, "$firstUuid | Renamed | 99")!!
        val row = (patch["climbs"] as JsonArray).single() as JsonObject
        assertEquals("b1", (row["id"] as JsonPrimitive).content)
        assertEquals("Renamed", (row["label"] as JsonPrimitive).content)
        assertEquals("keep-me", (row["naddr"] as JsonPrimitive).content)
        assertEquals("99", (row["points"] as JsonPrimitive).content)
        assertEquals(null, row["zone_hold"])
    }

    @Test
    fun `participant pool patch updates available count without losing pool source`() {
        val competition = competition(participantChoice = true, counted = 1)
        val patch = competitionClimbsEditorPatch(
            competition,
            "$firstUuid | First | 10 | 7\n$secondUuid | Second | 20",
        )!!
        val pool = patch["climb_pool"] as JsonObject
        assertEquals(2, (pool["options"] as JsonArray).size)
        assertEquals(null, patch["rules"])
    }

    @Test
    fun `editor rejects duplicates invalid zones and fewer than counted results`() {
        val competition = competition(participantChoice = true, counted = 2)
        assertNull(competitionClimbsEditorPatch(competition, "$firstUuid | First"))
        assertNull(competitionClimbsEditorPatch(competition, "$firstUuid | A\n$firstUuid | B"))
        assertNull(competitionClimbsEditorPatch(competition, "$firstUuid | A | 10 | zero\n$secondUuid | B"))
    }

    @Test
    fun `edit dialog exposes effective scoring format controls`() {
        val source = listOf(
            File("src/main/java/com/cruxcoach/android/ui/competition/CompetitionDetailScreen.kt"),
            File("androidApp/src/main/java/com/cruxcoach/android/ui/competition/CompetitionDetailScreen.kt"),
        ).first(File::isFile).readText()

        listOf(
            "competition.rules.climbCount", "competition.rules.countedClimbCount",
            "competition.rules.progression", "competition.rules.scoring",
            "competition.waitlistEnabled", "competition.rules.lateEntryAllowed",
            "score_points", "points_sum", "hardest_n",
            "competition.registrationOpensAt", "competition.registrationClosesAt",
            "competition.checkinOpensAt", "competition.checkinClosesAt",
            "competition.startsAt", "competition.endsAt", "competition.timezone",
        ).forEach { assertContains(source, it) }
    }

    private fun competition(participantChoice: Boolean, counted: Int): Competition {
        val climb = CompetitionClimb("b1", firstUuid, 40, "First", 10, 7)
        val rawClimb = JsonObject(mapOf(
            "id" to JsonPrimitive("b1"), "climb_uuid" to JsonPrimitive(firstUuid),
            "angle" to JsonPrimitive(40), "label" to JsonPrimitive("First"),
            "points" to JsonPrimitive(10), "zone_hold" to JsonPrimitive(7),
            "naddr" to JsonPrimitive("keep-me"),
        ))
        val rules = CompetitionRules(
            climbSource = if (participantChoice) "participant_choice" else "organizer_set",
            climbCount = 1, countedClimbCount = counted, selectionUniqueness = "none",
            progression = "asynchronous_turns", attemptsPerClimb = 5,
            turnDeadlineSec = 180, attemptDeadlineSec = 0, minRestSec = 0,
            deferBudgetPerRound = 1, maxConsecutiveDefers = 1, deferSlots = 1,
            scoring = "tops_then_attempts", scorePoints = null,
            tiebreaks = listOf("fewest_attempts"), lateEntryAllowed = false,
        )
        val raw = JsonObject(mapOf(
            "board" to JsonObject(mapOf("angle" to JsonPrimitive(40))),
            (if (participantChoice) "climb_pool" else "climbs") to if (participantChoice) {
                JsonObject(mapOf("source" to JsonPrimitive("organizer_list"), "options" to JsonArray(listOf(rawClimb))))
            } else JsonArray(listOf(rawClimb)),
        ))
        return Competition(
            compId = "edit-test", authority = "a".repeat(64), authorityEpoch = 1,
            title = "Edit test", summary = "", description = "", visibility = "unlisted",
            status = "published", timezone = "UTC", startsAt = 1, endsAt = 2,
            registrationOpensAt = 0, registrationClosesAt = 1,
            checkinOpensAt = 0, checkinClosesAt = 1, capacity = 20,
            waitlistEnabled = true, feeMsat = 0, feeLnurl = null,
            waiverRequired = false, revision = 1, divisions = emptyList(),
            climbs = if (participantChoice) emptyList() else listOf(climb),
            climbPool = if (participantChoice) listOf(climb) else emptyList(),
            prizes = emptyList(), prizeClaimDays = 14, rules = rules,
            relays = emptyList(), raw = raw,
        )
    }
}
