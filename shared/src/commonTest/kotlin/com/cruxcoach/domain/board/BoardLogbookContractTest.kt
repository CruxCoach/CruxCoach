package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertFailsWith

class BoardLogbookContractTest {
    @Test
    fun `content selection can only reference visible entries`() {
        assertFailsWith<IllegalArgumentException> {
            BoardLogbookScreenState.Content(
                entries = listOf(entry()),
                summary = summary(),
                selectedEntryUuids = setOf("missing"),
            )
        }
    }

    @Test
    fun `attempt cannot carry send-only quality`() {
        assertFailsWith<IllegalArgumentException> {
            entry(outcome = AttemptOutcome.ATTEMPT, quality = 4L)
        }
    }

    @Test
    fun `content only accepts a paging issue`() {
        assertFailsWith<IllegalArgumentException> {
            BoardLogbookScreenState.Content(
                entries = listOf(entry()),
                summary = summary(),
                pageIssue = LogbookIssue.INITIAL_LOAD_FAILED,
            )
        }
    }

    private fun entry(
        outcome: AttemptOutcome = AttemptOutcome.SEND,
        quality: Long? = 4L,
    ) = LogbookEntrySummary(
        entryUuid = "entry-1",
        climbUuid = "quiet-riot",
        climbName = "Quiet Riot",
        outcome = outcome,
        angle = 40,
        isMirrored = false,
        attemptCount = 2,
        quality = quality,
        difficultyAverage = 21.0,
        boardBrand = BoardBrand.KILTER,
        layoutId = 1L,
        climbedAt = "2026-08-30T11:45:00Z",
        comment = "Matched the heel",
    )

    private fun summary() = LogbookSummary(
        totalEntries = 3,
        totalSends = 2,
        totalAttempts = 1,
        uniqueClimbs = 2,
        hardestDifficulty = 21,
    )
}
