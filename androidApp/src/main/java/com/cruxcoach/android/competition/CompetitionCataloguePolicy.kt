package com.cruxcoach.android.competition

import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.competition.Competition
import com.cruxcoach.domain.competition.CompetitionClimb

/** Pure gates shared by rendering and tests; protocol data never becomes UI trust by itself. */
object CompetitionCataloguePolicy {
    private val HAND_ROLES = setOf(13, 43)

    fun validZoneHold(option: CompetitionClimb, holds: List<BoardHold>): Int? =
        option.zoneHold?.takeIf { zone ->
            zone > 0 && holds.any { it.placementId == zone && it.roleId in HAND_ROLES }
        }

    fun entrantChooses(competition: Competition): Boolean =
        competition.rules.climbSource == "participant_choice"
}
