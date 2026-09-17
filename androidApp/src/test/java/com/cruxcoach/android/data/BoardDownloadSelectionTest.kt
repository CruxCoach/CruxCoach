package com.cruxcoach.android.data

import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BoardDownloadSelectionTest {
    @Test fun `missing preference preserves all existing boards`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        assertEquals(BoardBrand.entries.filter { it.isInteractive }.toSet(), prefs.boardDownloadBrands.first())
    }

    @Test fun `selection persists independently of active board and can disable all downloads`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER))
        prefs.setBoardBrand(BoardBrand.MOONBOARD.wireValue)
        assertEquals(setOf(BoardBrand.KILTER), prefs.boardDownloadBrands.first())
        prefs.setBoardDownloadBrands(emptySet())
        assertEquals(emptySet(), prefs.boardDownloadBrands.first())
    }

    @Test fun `deletion excludes only deleted boards and explicit download opts one back in`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))
        prefs.excludeBoardDownloads(setOf(BoardBrand.MOONBOARD))
        assertEquals(setOf(BoardBrand.KILTER), prefs.boardDownloadBrands.first())
        prefs.includeBoardDownload(BoardBrand.QUANTUM)
        assertEquals(setOf(BoardBrand.KILTER, BoardBrand.QUANTUM), prefs.boardDownloadBrands.first())
    }

    @Test fun `unknown or map only preferences never expand into all boards`() {
        assertEquals(emptySet(), decodeBoardDownloadBrands(setOf("future", "aurora")))
        assertEquals(setOf(BoardBrand.KILTER), decodeBoardDownloadBrands(setOf("kilter", "future")))
    }

    @Test fun `sync order respects exclusion even for the active board`() {
        assertEquals(listOf(BoardBrand.KILTER), catalogueSyncOrder(BoardBrand.MOONBOARD, setOf(BoardBrand.KILTER)))
        assertEquals(listOf(BoardBrand.QUANTUM, BoardBrand.MOONBOARD),
            catalogueSyncOrder(BoardBrand.QUANTUM, setOf(BoardBrand.MOONBOARD, BoardBrand.QUANTUM)))
        assertEquals(emptyList(), catalogueSyncOrder(BoardBrand.KILTER, emptySet()))
    }
}
