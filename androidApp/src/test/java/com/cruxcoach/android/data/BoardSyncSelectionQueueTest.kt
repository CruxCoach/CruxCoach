package com.cruxcoach.android.data

import android.app.Application
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSyncSelectionQueueTest {
    private fun checkQueue(clearSelection: Boolean) = runTest {
        val release = CompletableDeferred<Unit>()
        val selected = MutableStateFlow(setOf(BoardBrand.KILTER))
        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.lastSyncTimestamp } returns flowOf(null)
        every { prefs.boardDownloadBrands } returns selected
        val importer = mockk<BoardDatabaseImporter>(relaxed = true)
        every { importer.isImported() } returns false
        val manager = spyk(BoardSyncManager(
            importer = importer, blossomSyncManager = mockk(relaxed = true),
            userPreferences = prefs, appContext = RuntimeEnvironment.getApplication(),
            boardRepository = mockk(relaxed = true), personalBoardRepo = mockk(relaxed = true),
            boardLocationRepository = mockk(relaxed = true), moonBoardCatalogueSync = mockk(relaxed = true),
            auroraCatalogueSync = mockk(relaxed = true), quantumCatalogueSync = mockk(relaxed = true),
            integrityVerifier = mockk(relaxed = true), scope = backgroundScope,
            discoverInitialShare = { release.await(); null }, initialOnlineFallback = {},
        ))
        var downloaded: Set<BoardBrand>? = null
        every { manager.startApiSync(any(), any()) } answers { downloaded = selected.value }
        manager.startInitialSyncIfNeeded()
        runCurrent()
        assertTrue(manager.state.value.isSyncing)
        selected.value += BoardBrand.MOONBOARD
        manager.startSelectedSyncAfterCurrent()
        runCurrent()
        selected.value += BoardBrand.QUANTUM
        manager.startSelectedSyncAfterCurrent()
        runCurrent()
        verify(exactly = 0) { manager.startApiSync(any(), any()) }
        if (clearSelection) selected.value = emptySet()
        release.complete(Unit)
        runCurrent()
        verify(exactly = if (clearSelection) 0 else 1) { manager.startApiSync(false, true) }
        assertEquals(if (clearSelection) null else selected.value, downloaded)
    }

    @Test fun `confirmed additions wait and coalesce using latest choice`() = checkQueue(false)
    @Test fun `empty latest choice cancels pending follow up`() = checkQueue(true)
}
