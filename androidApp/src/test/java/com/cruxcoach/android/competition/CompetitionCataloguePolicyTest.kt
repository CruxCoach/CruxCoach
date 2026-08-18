package com.cruxcoach.android.competition

import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.competition.CompetitionClimb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CompetitionCataloguePolicyTest {
    private fun option(zone: Int?) = CompetitionClimb(
        id = "c1", climbUuid = "5a7c2e18-9d40-4a37-8b61-4f2e0c95d713",
        angle = 40, label = "Test", points = 0, zoneHold = zone,
    )

    @Test fun `zone must be a hand hold in the climb`() {
        val holds = listOf(BoardHold(10, 12), BoardHold(11, 13), BoardHold(12, 14))
        assertEquals(11, CompetitionCataloguePolicy.validZoneHold(option(11), holds))
        assertNull(CompetitionCataloguePolicy.validZoneHold(option(10), holds))
        assertNull(CompetitionCataloguePolicy.validZoneHold(option(99), holds))
    }

    @Test fun `moonboard hand role is accepted but start and finish are not`() {
        val holds = listOf(BoardHold(1, 42), BoardHold(2, 43), BoardHold(3, 44))
        assertEquals(2, CompetitionCataloguePolicy.validZoneHold(option(2), holds))
        assertNull(CompetitionCataloguePolicy.validZoneHold(option(1), holds))
        assertNull(CompetitionCataloguePolicy.validZoneHold(option(3), holds))
    }
}
