package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardLogbookScreenState
import com.cruxcoach.domain.board.LogbookIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BoardLogbookStateMapperTest {
    @Test
    fun `loading empty and initial failure remain distinct`() {
        assertIs<BoardLogbookScreenState.Loading>(BoardLogbookState().toPortableLogbookState())
        assertIs<BoardLogbookScreenState.Empty>(
            BoardLogbookState(isLoading = false).toPortableLogbookState(),
        )
        assertEquals(
            BoardLogbookScreenState.Error(LogbookIssue.INITIAL_LOAD_FAILED),
            BoardLogbookState(isLoading = false, error = "database unavailable")
                .toPortableLogbookState(),
        )
    }

    @Test
    fun `paging failure retains content selection and progress metadata`() {
        val mapped = BoardLogbookState(
            isLoading = false,
            ascents = listOf(entry()),
            totalCount = 51,
            canLoadMore = true,
            selectedUuids = setOf("entry-1", "stale"),
            error = "next page unavailable",
            stats = BoardLogbookStats(
                totalSends = 1,
                totalAttempts = 2,
                uniqueClimbs = 1,
                hardestDifficultyInt = 21,
            ),
        ).toPortableLogbookState()

        val content = assertIs<BoardLogbookScreenState.Content>(mapped)
        assertEquals(setOf("entry-1"), content.selectedEntryUuids)
        assertEquals(LogbookIssue.PAGE_LOAD_FAILED, content.pageIssue)
        assertEquals(51, content.summary.totalEntries)
        assertEquals(21, content.summary.hardestDifficulty)
    }

    @Test
    fun `legacy missing metadata maps to explicit fallback inputs`() {
        val content = assertIs<BoardLogbookScreenState.Content>(
            BoardLogbookState(
                isLoading = false,
                ascents = listOf(entry(climbName = "", boardBrand = "")),
                totalCount = 1,
            ).toPortableLogbookState(),
        )

        assertNull(content.entries.single().climbName)
        assertEquals(BoardBrand.KILTER, content.entries.single().boardBrand)
    }

    private fun entry(
        climbName: String = "Quiet Riot",
        boardBrand: String = "kilter",
    ) = AscentWithClimb(
        uuid = "entry-1",
        climbUuid = "quiet-riot",
        angle = 40,
        isMirror = false,
        bidCount = 2,
        quality = 4,
        difficulty = 21,
        comment = "  Matched the heel  ",
        climbedAt = "2026-08-30T11:45:00Z",
        climbName = climbName,
        climbFrames = "p1100r12",
        difficultyAverage = 21.0,
        isSend = true,
        boardBrand = boardBrand,
        layoutId = 1,
    )
}
