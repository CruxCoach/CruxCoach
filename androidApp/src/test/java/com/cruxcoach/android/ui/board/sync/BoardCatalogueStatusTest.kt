package com.cruxcoach.android.ui.board.sync

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.domain.board.BoardBrand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, qualifiers = "en")
class BoardCatalogueStatusTest {
    @get:Rule val compose = createComposeRule()

    private fun show(counts: Map<String, Long>, state: BoardSyncState = BoardSyncState(alreadyImported = true)) {
        compose.setContent {
            MaterialTheme {
                CompactDatabasePreparation(
                    state = state, boardCounts = counts,
                    activeBrand = BoardBrand.KILTER,
                    selectedBrands = setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD),
                    onRetry = {}, onLoadBoard = {},
                )
            }
        }
    }

    @Test fun `one available catalogue does not mark all selected boards ready`() {
        show(mapOf("kilter" to 100L))
        compose.onNodeWithText("Offline catalogues are ready.").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_offline_retry").assertIsDisplayed()
    }

    @Test fun `all selected catalogues are ready after successful imports`() {
        show(mapOf("kilter" to 100L, "moonboard" to 50L))
        compose.onNodeWithText("Offline catalogues are ready.").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_offline_retry").assertDoesNotExist()
    }

    @Test fun `board error stays retryable even when older data is available`() {
        show(mapOf("kilter" to 100L, "moonboard" to 50L),
            BoardSyncState(alreadyImported = true, moonBoardError = "Failed"))
        compose.onNodeWithText("Offline catalogues are ready.").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_offline_retry").assertIsDisplayed()
    }

    @Test fun `zero done count cannot claim an absent catalogue is ready`() {
        show(mapOf("kilter" to 100L), BoardSyncState(alreadyImported = true,
            moonBoardStep = ImportStep.Done(0, 0, 0)))
        compose.onNodeWithText("Offline catalogues are ready.").assertDoesNotExist()
    }

    @Test fun `completed board total replaces stale count while unchanged import keeps stored count`() {
        assertEquals(50L, catalogueDisplayCount(1L, ImportStep.Done(50, 0, 0)))
        assertEquals(50L, catalogueDisplayCount(50L, ImportStep.Done(0, 0, 0)))
        assertEquals(0L, catalogueDisplayCount(0L, ImportStep.Done(0, 0, 0)))
    }
}
