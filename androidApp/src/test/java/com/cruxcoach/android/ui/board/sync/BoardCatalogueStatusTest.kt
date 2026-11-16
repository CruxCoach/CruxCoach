package com.cruxcoach.android.ui.board.sync

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun `catalogues still being counted show no warning and no download button`() {
        // The grouped count over a large catalogue takes seconds on a phone. Until it is in,
        // the active board must not read as "not loaded" and offer a download it already has.
        val counts = MutableStateFlow<Map<String, Long>?>(null)
        val vm = mockk<BoardSyncViewModel>(relaxed = true)
        every { vm.state } returns MutableStateFlow(BoardSyncState(alreadyImported = true))
        every { vm.modelState } returns MutableStateFlow(BoardModelSelectionState())
        every { vm.boardCounts } returns counts
        every { vm.downloadBrands } returns MutableStateFlow(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))
        every { vm.activeBrand } returns MutableStateFlow(BoardBrand.KILTER)
        compose.setContent { MaterialTheme { BoardSyncInlineCard(viewModel = vm) } }

        compose.onNodeWithTag("board_status_counting_kilter").assertExists()
        compose.onNodeWithTag("board_status_counting_moonboard").assertExists()
        compose.onNodeWithText("not loaded").assertDoesNotExist()
        compose.onNodeWithTag("board_status_load_kilter").assertDoesNotExist()
        compose.onNodeWithTag("board_status_load_moonboard").assertDoesNotExist()

        counts.value = mapOf("kilter" to 246_096L)
        compose.onNodeWithText("246,096").assertExists()
        compose.onNodeWithTag("board_status_counting_kilter").assertDoesNotExist()
        compose.onNodeWithTag("board_status_load_kilter").assertExists()
        // Counted and empty: now really not loaded, with its download button.
        compose.onNodeWithTag("board_status_counting_moonboard").assertDoesNotExist()
        compose.onNodeWithTag("board_status_load_moonboard").assertExists()
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
        compose.onNodeWithTag("board_sync_compact_details").performClick()
        compose.onNodeWithText("Download failed").assertExists()
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
    @Test fun `offline status never promises automatic wifi scheduling`() {
        show(emptyMap(), BoardSyncState(networkAvailable = false))
        compose.onNodeWithText("No internet connection").assertIsDisplayed()
        compose.onNodeWithText("Queued — starts automatically on Wi-Fi").assertDoesNotExist()
    }

    @Test fun `details contain only selected catalogues`() {
        show(mapOf("kilter" to 100L))
        compose.onNodeWithTag("board_sync_compact_details").performClick()
        compose.onNodeWithTag("board_status_kilter").assertExists()
        compose.onNodeWithTag("board_status_moonboard").assertExists()
        compose.onNodeWithTag("board_status_tension").assertDoesNotExist()
    }

    @Test fun `a board ticked off mid-download stays in the card until it is done`() {
        // The running download is not aborted by a deselection; the card must not act as
        // if nothing were loading and drop the count and the details for it.
        compose.setContent {
            MaterialTheme {
                CompactDatabasePreparation(
                    state = BoardSyncState(isSyncing = true, moonBoardStep = ImportStep.Decompress(1L, 2L)),
                    boardCounts = emptyMap(),
                    activeBrand = BoardBrand.MOONBOARD,
                    selectedBrands = emptySet(),
                    onRetry = {}, onLoadBoard = {},
                )
            }
        }
        compose.onNodeWithText("0 of 1 boards ready").assertIsDisplayed()
        compose.onNodeWithTag("board_sync_compact_details").performClick()
        compose.onNodeWithTag("board_status_moonboard").assertExists()
    }

    @Test fun `old counts cannot claim readiness while import is running`() {
        show(mapOf("kilter" to 100L, "moonboard" to 50L), BoardSyncState(isSyncing = true))
        compose.onNodeWithText("Offline catalogues are ready.").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_offline_retry").assertDoesNotExist()
    }

    @Test fun `a nearby share names its step while its boards all wait`() {
        // Onboarding shows only this card. A share keeps every board at "0 ready" until it is
        // done, so without the step a running import looked stuck for ten minutes.
        val sharing = BoardSyncState(
            isSyncing = true,
            localShareInProgress = true,
            importStep = ImportStep.ImportClimbs(0, 120_000, 532_675),
            localShareBoardSteps = mapOf(
                BoardBrand.KILTER to ImportStep.ImportClimbs(0, 120_000, 532_675),
                BoardBrand.MOONBOARD to ImportStep.ImportClimbs(0, 120_000, 532_675),
            ),
        )
        show(emptyMap(), sharing)
        compose.onNodeWithTag("local_share_progress").assertIsDisplayed()
        compose.onNodeWithText("Loading board data from nearby device").assertIsDisplayed()
        compose.onNodeWithText("Import climbs").assertIsDisplayed()
        compose.onNodeWithText("0 of 2 boards ready").assertIsDisplayed()
    }

    @Test fun `an internet sync keeps the plain bar`() {
        show(emptyMap(), BoardSyncState(isSyncing = true, importStep = ImportStep.ImportClimbs(0, 1, 2)))
        compose.onNodeWithTag("local_share_progress").assertDoesNotExist()
    }

}
