package com.cruxcoach.domain.board

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProgressHistoryContractTest {
    @Test
    fun content_fixture_preserves_identity_order_and_selection_without_platform_types() {
        val content = historyContentFixture(selectedIds = setOf(11, 12))

        assertEquals(listOf("Quiet Riot", "A deliberately long project name"), content.entries.map { it.climbName })
        assertEquals(BoardBrand.KILTER, content.entries.first().boardBrand)
        assertEquals("2026-08-30T11:45:00", content.entries.first().recordedAt)
        assertTrue(content.hasSelection)
        assertTrue(content.allSelected)
    }

    @Test
    fun partial_selection_is_not_all_selected() {
        val content = historyContentFixture(selectedIds = setOf(11))

        assertTrue(content.hasSelection)
        assertFalse(content.allSelected)
    }

    @Test
    fun empty_and_error_keep_retention_context_distinct() {
        val empty: ProgressHistoryScreenState = ProgressHistoryScreenState.Empty(
            HistoryRetentionPeriod.DAYS_90,
        )
        val error: ProgressHistoryScreenState = ProgressHistoryScreenState.Error(
            issue = ProgressHistoryIssue.LOAD_FAILED,
            retention = null,
        )

        assertEquals(HistoryRetentionPeriod.DAYS_90, assertIs<ProgressHistoryScreenState.Empty>(empty).retention)
        assertTrue(assertIs<ProgressHistoryScreenState.Error>(error).canRetry)
    }

    @Test
    fun actions_express_intent_without_repository_or_navigation_types() {
        val actions: List<ProgressHistoryAction> = listOf(
            ProgressHistoryAction.ChooseRetention(HistoryRetentionPeriod.DAYS_365),
            ProgressHistoryAction.OpenClimb("climb-quiet", 40),
            ProgressHistoryAction.ToggleSelection(11),
            ProgressHistoryAction.ConfirmDeleteSelection,
            ProgressHistoryAction.Retry,
        )

        assertEquals(5, actions.size)
        assertEquals(
            HistoryRetentionPeriod.DAYS_365,
            (actions.first() as ProgressHistoryAction.ChooseRetention).retention,
        )
    }
}

private fun historyContentFixture(
    selectedIds: Set<Long> = emptySet(),
) = ProgressHistoryScreenState.Content(
    entries = listOf(
        ProgressHistoryEntry(
            id = 11,
            climbUuid = "climb-quiet",
            climbName = "Quiet Riot",
            angle = 40,
            boardBrand = BoardBrand.KILTER,
            layoutId = 1,
            difficultyAverage = 21.0,
            recordedAt = "2026-08-30T11:45:00",
        ),
        ProgressHistoryEntry(
            id = 12,
            climbUuid = "climb-project",
            climbName = "A deliberately long project name",
            angle = 25,
            boardBrand = BoardBrand.TENSION,
            layoutId = 7,
            difficultyAverage = null,
            recordedAt = "2026-08-29T18:10:00",
        ),
    ),
    retention = HistoryRetentionPeriod.DAYS_30,
    selectedIds = selectedIds,
)
