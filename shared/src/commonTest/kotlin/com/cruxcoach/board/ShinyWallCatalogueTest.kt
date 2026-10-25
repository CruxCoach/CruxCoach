package com.cruxcoach.board

import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.board.ShinyWallCatalogue
import com.cruxcoach.domain.board.ShinyWallVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShinyWallCatalogueTest {

    @Test
    fun publishedGradesMapOntoTheSharedScale() {
        assertEquals(16, ShinyWallCatalogue.difficultyForGrade("6A"))
        assertEquals(17, ShinyWallCatalogue.difficultyForGrade("6a+"))
        assertEquals(22, ShinyWallCatalogue.difficultyForGrade(" 7A "))
        assertEquals(33, ShinyWallCatalogue.difficultyForGrade("8C+"))
        assertEquals("6a", KilterGradeMapper.difficultyToFont(16.0))
        assertEquals("8c+", KilterGradeMapper.difficultyToFont(33.0))
    }

    @Test
    fun plainLowerGradesTakeTheMiddleOfTheirBand() {
        assertEquals(11, ShinyWallCatalogue.difficultyForGrade("4"))
        assertEquals(12, ShinyWallCatalogue.difficultyForGrade("4+"))
        assertEquals(14, ShinyWallCatalogue.difficultyForGrade("5"))
        assertEquals(15, ShinyWallCatalogue.difficultyForGrade("5+"))
    }

    @Test
    fun projectsAndUnknownGradesAreUngraded() {
        assertNull(ShinyWallCatalogue.difficultyForGrade("PROJECT"))
        assertNull(ShinyWallCatalogue.difficultyForGrade("JOKER"))
        assertNull(ShinyWallCatalogue.difficultyForGrade(null))
        assertNull(ShinyWallCatalogue.difficultyForGrade("V5"))
    }

    @Test
    fun averageVotesInterpolateAlongTheGradeList() {
        assertEquals(11.0, ShinyWallCatalogue.difficultyForAverageVote(0.0))
        assertEquals(16.0, ShinyWallCatalogue.difficultyForAverageVote(4.0))
        assertEquals(16.5, ShinyWallCatalogue.difficultyForAverageVote(4.5))
        assertEquals(33.0, ShinyWallCatalogue.difficultyForAverageVote(25.0))
        assertNull(ShinyWallCatalogue.difficultyForAverageVote(null))
        assertNull(ShinyWallCatalogue.difficultyForAverageVote(-1.0))
        assertNull(ShinyWallCatalogue.difficultyForAverageVote(Double.POSITIVE_INFINITY))
    }

    @Test
    fun qualityFiveScaleBecomesThreeStars() {
        assertEquals(1.0, ShinyWallCatalogue.qualityForAverageVote(1.0))
        assertEquals(2.0, ShinyWallCatalogue.qualityForAverageVote(3.0))
        assertEquals(3.0, ShinyWallCatalogue.qualityForAverageVote(5.0))
        assertNull(ShinyWallCatalogue.qualityForAverageVote(0.0))
        assertNull(ShinyWallCatalogue.qualityForAverageVote(Double.NaN))
    }

    @Test
    fun framesUseSharedRolesAndVariantPlacementIds() {
        val v = ShinyWallVariant.ST1
        val frames = ShinyWallCatalogue.frames(
            listOf(310 to "top", 4 to "foot", 9 to "hand", 2 to "start", 1 to "start"),
            v,
        )!!
        assertEquals(
            "p${v.placementId(1)}r12p${v.placementId(2)}r12p${v.placementId(9)}r13" +
                "p${v.placementId(4)}r15p${v.placementId(310)}r14",
            frames,
        )
        val holds = BoardClimbParser.parseFrames(frames)
        assertEquals(listOf(HoldRole.START, HoldRole.START, HoldRole.HAND, HoldRole.FOOT, HoldRole.FINISH), holds.map { it.roleId })
    }

    @Test
    fun corruptHoldsSkipTheRoute() {
        assertNull(ShinyWallCatalogue.frames(listOf(311 to "hand"), ShinyWallVariant.ST1))
        assertNull(ShinyWallCatalogue.frames(listOf(3 to "middle"), ShinyWallVariant.ST1))
        assertEquals("", ShinyWallCatalogue.frames(emptyList(), ShinyWallVariant.ST1))
    }
}
