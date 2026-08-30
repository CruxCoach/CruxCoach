package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.HistoryRetention
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HistoryRetentionPeriod
import com.cruxcoach.domain.board.ProgressHistoryIssue
import com.cruxcoach.domain.board.ProgressHistoryScreenState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProgressHistoryStateMapperTest {
    @Test
    fun `loading empty and fatal failure remain distinct`() {
        val source = BoardClimbHistoryState(retention = HistoryRetention.DAYS_90)

        assertIs<ProgressHistoryScreenState.Loading>(source.toPortableState(isLoading = true))
        assertEquals(
            HistoryRetentionPeriod.DAYS_90,
            assertIs<ProgressHistoryScreenState.Empty>(source.toPortableState()).retention,
        )
        assertEquals(
            ProgressHistoryIssue.LOAD_FAILED,
            assertIs<ProgressHistoryScreenState.Error>(
                source.toPortableState(issue = ProgressHistoryIssue.LOAD_FAILED),
            ).issue,
        )
    }

    @Test
    fun `content maps row identity retention and selection`() {
        val portable = BoardClimbHistoryState(
            entries = listOf(historyEntry()),
            retention = HistoryRetention.DAYS_365,
            selectedIds = setOf(7L),
        ).toPortableState()

        val content = assertIs<ProgressHistoryScreenState.Content>(portable)
        assertEquals(HistoryRetentionPeriod.DAYS_365, content.retention)
        assertEquals(BoardBrand.TENSION, content.entries.single().boardBrand)
        assertEquals("2026-08-30T11:45:00", content.entries.single().recordedAt)
        assertEquals(setOf(7L), content.selectedIds)
    }

    @Test
    fun `action failure keeps existing content usable`() {
        val portable = BoardClimbHistoryState(
            entries = listOf(historyEntry()),
        ).toPortableState(issue = ProgressHistoryIssue.DELETE_FAILED)

        assertEquals(
            ProgressHistoryIssue.DELETE_FAILED,
            assertIs<ProgressHistoryScreenState.Content>(portable).transientIssue,
        )
    }
}

private fun historyEntry() = ClimbHistoryEntry(
    id = 7,
    climbUuid = "tension-climb",
    climbName = "Measured Progress",
    angle = 35,
    difficultyAverage = 20.0,
    boardBrand = "tension",
    layoutId = 7,
    climbedAt = "2026-08-30T11:40:00",
    recordedAt = "2026-08-30T11:45:00",
)
