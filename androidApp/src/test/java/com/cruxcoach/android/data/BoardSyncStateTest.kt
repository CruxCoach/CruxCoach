package com.cruxcoach.android.data

import com.cruxcoach.android.data.BoardDatabaseImporter.ImportStep
import com.cruxcoach.domain.board.BoardBrand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the FEAT-031 per-board sync-state map. [BoardSyncState]
 * unifies the Kilter + MoonBoard streams and the Aurora map into one
 * brand-keyed [BoardSyncState.boardSteps] / [BoardSyncState.boardErrors] view
 * that drives the per-board sync card (replacing the two hardcoded sections).
 *
 * The unification is the testable logic; a full Turbine test of
 * BoardSyncManager.reportBoardStep would require the manager's whole Hilt
 * graph (9 collaborators incl. Android Context), so the computed-map contract
 * is verified directly here instead.
 */
class BoardSyncStateTest {

    @Test
    fun boardStepsUnifiesAllStreamsOrderedKilterMoonboardAurora() {
        val kilter = ImportStep.FetchingManifest
        val moon = ImportStep.Done(1, 2, 3)
        val tension = ImportStep.Finalizing
        val state = BoardSyncState(
            importStep = kilter,
            moonBoardStep = moon,
            auroraSteps = mapOf(BoardBrand.TENSION to tension),
        )
        assertEquals(
            listOf(BoardBrand.KILTER, BoardBrand.MOONBOARD, BoardBrand.TENSION),
            state.boardSteps.keys.toList(),
        )
        assertEquals(kilter, state.boardSteps[BoardBrand.KILTER])
        assertEquals(moon, state.boardSteps[BoardBrand.MOONBOARD])
        assertEquals(tension, state.boardSteps[BoardBrand.TENSION])
    }

    @Test
    fun boardStepsOmitsNullStreams() {
        val state = BoardSyncState(
            importStep = null,
            moonBoardStep = null,
            auroraSteps = mapOf(BoardBrand.DECOY to ImportStep.Finalizing),
        )
        assertEquals(listOf(BoardBrand.DECOY), state.boardSteps.keys.toList())
    }

    @Test
    fun boardErrorsUnifiesMoonboardAndAurora() {
        val state = BoardSyncState(
            moonBoardError = "moon fail",
            auroraErrors = mapOf(BoardBrand.SOILL to "soill fail"),
        )
        assertEquals("moon fail", state.boardErrors[BoardBrand.MOONBOARD])
        assertEquals("soill fail", state.boardErrors[BoardBrand.SOILL])
    }

    @Test
    fun emptyStateHasNoBoardStepsOrErrors() {
        val state = BoardSyncState()
        assertTrue(state.boardSteps.isEmpty())
        assertTrue(state.boardErrors.isEmpty())
    }
}
