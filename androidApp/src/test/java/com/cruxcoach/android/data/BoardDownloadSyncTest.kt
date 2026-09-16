package com.cruxcoach.android.data

import android.app.Application
import com.cruxcoach.android.data.blossom.BlossomManifest
import com.cruxcoach.android.data.blossom.BlossomSyncManager
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import com.cruxcoach.android.notification.BoardSyncWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class BoardDownloadSyncTest {
    private val blossom = mockk<BlossomSyncManager>(relaxed = true)
    private val moon = mockk<MoonBoardCatalogueSync>(relaxed = true)
    private val aurora = mockk<AuroraCatalogueSync>(relaxed = true)
    private val quantum = mockk<QuantumCatalogueSync>(relaxed = true)
    private val media = mockk<BoardBetaMediaSync>(relaxed = true)
    private val repo = mockk<BoardRepository>(relaxed = true)
    private val importer = mockk<BoardDatabaseImporter>(relaxed = true)

    private fun TestScope.manager(prefs: UserPreferences): BoardSyncManager {
        every { importer.isImported() } returns true
        every { repo.getClimbCountsByBrand() } returns mapOf("kilter" to 100L, "moonboard" to 50L, "quantum" to 20L)
        val manifest = BlossomManifest(v = 1, board = "kilter", createdAt = 1L, compression = "gzip", chunks = emptyList())
        coEvery { blossom.fetchManifest() } returns manifest
        every { blossom.canApplyManifest(any()) } returns true
        every { blossom.getChangedChunks(any(), any(), any()) } returns emptyList()
        coEvery { moon.sync(any()) } returns MoonBoardCatalogueSync.Result.AlreadyCurrent
        coEvery { quantum.sync(any()) } returns QuantumCatalogueSync.Result.AlreadyCurrent
        coEvery { aurora.sync(any(), any()) } returns AuroraCatalogueSync.Result.AlreadyCurrent
        return BoardSyncManager(importer, blossom, prefs, RuntimeEnvironment.getApplication(), repo,
            mockk(relaxed = true), mockk(relaxed = true), moon, aurora, quantum,
            mockk(relaxed = true), moonBoardBetaSync = mockk(relaxed = true), boardBetaMediaSync = media,
            scope = backgroundScope)
    }

    @Test fun `picker download confirmed during an import waits then enqueues the added board`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.MOONBOARD))
        val manager = manager(prefs)
        val finish = CompletableDeferred<Unit>()
        coEvery { moon.sync(any()) } coAnswers {
            finish.await()
            MoonBoardCatalogueSync.Result.AlreadyCurrent
        }
        mockkObject(BoardSyncWorker.Companion)
        every { BoardSyncWorker.enqueueExpedited(any(), any(), any()) } just Runs
        try {
            runCurrent()
            manager.startBackgroundSync()
            runCurrent()
            assertTrue(manager.state.value.isSyncing)
            manager.loadBoardCatalogue(BoardBrand.KILTER)
            runCurrent()
            verify(exactly = 0) { BoardSyncWorker.enqueueExpedited(any(), any(), any()) }
            assertEquals(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD), prefs.boardDownloadBrands.first())
            finish.complete(Unit)
            runCurrent()
            verify(exactly = 1) { BoardSyncWorker.enqueueExpedited(any(), true, BoardBrand.KILTER) }
        } finally {
            unmockkObject(BoardSyncWorker.Companion)
        }
    }

    @Test fun `Kilter only excludes other catalogues and previously downloaded media`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER))
        val manager = manager(prefs)
        runCurrent()
        manager.startBackgroundSync()
        runCurrent()
        coVerify(exactly = 1) { blossom.fetchManifest() }
        coVerify(exactly = 0) { moon.sync(any()); aurora.sync(any(), any()); quantum.sync(any()) }
        coVerify(exactly = 1) { media.sync("kilter", any()) }
        coVerify(exactly = 0) { media.sync("moonboard", any()); media.sync("quantum", any()) }
        assertFalse(manager.state.value.isSyncing)
    }

    @Test fun `MoonBoard only never fetches Kilter and stays independent of active board`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.MOONBOARD))
        val manager = manager(prefs)
        runCurrent()
        manager.startBackgroundSync()
        runCurrent()
        coVerify(exactly = 1) { moon.sync(any()) }
        coVerify(exactly = 0) { blossom.fetchManifest(); aurora.sync(any(), any()); quantum.sync(any()) }
        assertTrue(manager.state.value.alreadyImported)
        assertFalse(manager.state.value.isSyncing)
    }

    @Test fun `empty selection performs no downloads`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(emptySet())
        val manager = manager(prefs)
        runCurrent()
        manager.startBackgroundSync()
        runCurrent()
        coVerify(exactly = 0) { blossom.fetchManifest(); moon.sync(any()); aurora.sync(any(), any()); quantum.sync(any()); media.sync(any(), any()) }
        assertFalse(manager.state.value.isSyncing)
        assertNull(prefs.lastSyncTimestamp.first())
    }

    @Test fun `unchanged Kilter still checks other selected boards`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER, BoardBrand.QUANTUM))
        val manager = manager(prefs)
        runCurrent()
        manager.startBackgroundSync()
        runCurrent()
        coVerify(exactly = 1) { blossom.fetchManifest(); quantum.sync(any()) }
        coVerify(exactly = 0) { moon.sync(any()); aurora.sync(any(), any()) }
    }

    @Test fun `explicit single Kilter download never downloads other selected boards`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        val manager = manager(prefs)
        runCurrent()
        manager.startBackgroundSync(BoardBrand.KILTER)
        runCurrent()
        coVerify(exactly = 1) { blossom.fetchManifest() }
        coVerify(exactly = 0) { moon.sync(any()); aurora.sync(any(), any()); quantum.sync(any()) }
        // Updating one board must not postpone freshness checks for the others.
        assertNull(prefs.lastSyncTimestamp.first())
    }

    @Test fun `successful deletion excludes board before next background sync`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))
        val manager = manager(prefs)
        runCurrent()
        manager.deleteBoardData(setOf(BoardBrand.MOONBOARD))
        runCurrent()
        assertEquals(setOf(BoardBrand.KILTER), prefs.boardDownloadBrands.first())
        manager.startBackgroundSync()
        runCurrent()
        coVerify(exactly = 0) { moon.sync(any()) }
    }
    @Test fun `failed Kilter does not block Quantum or advance success timestamp`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER, BoardBrand.QUANTUM))
        val manager = manager(prefs)
        coEvery { blossom.fetchManifest() } throws java.io.IOException("offline")
        runCurrent()
        manager.startBackgroundSync()
        runCurrent()
        coVerify(exactly = 1) { quantum.sync(any()) }
        assertNull(prefs.lastSyncTimestamp.first())
        assertFalse(manager.state.value.syncComplete)
        assertNotNull(manager.state.value.errorMessage)
    }

    @Test fun `failed deletion preserves download selection`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.MOONBOARD))
        val manager = manager(prefs)
        every { repo.deleteBoardDataForBrands(any()) } throws IllegalStateException("test failure")
        runCurrent()
        manager.deleteBoardData(setOf(BoardBrand.MOONBOARD))
        runCurrent()
        assertEquals(setOf(BoardBrand.MOONBOARD), prefs.boardDownloadBrands.first())
        assertEquals(0, manager.boardDataDeletion.value.completions)
    }

    @Test fun `queued single board request cannot undo a later exclusion`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.MOONBOARD))
        val manager = manager(prefs)
        runCurrent()
        manager.startBackgroundSync(BoardBrand.KILTER)
        runCurrent()
        coVerify(exactly = 0) { blossom.fetchManifest(); moon.sync(any()) }
        assertEquals(setOf(BoardBrand.MOONBOARD), prefs.boardDownloadBrands.first())
    }

    @Test fun `deletion waits for in flight download before excluding and removing data`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.MOONBOARD))
        val manager = manager(prefs)
        val downloaded = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { moon.sync(any()) } coAnswers {
            downloaded.await()
            MoonBoardCatalogueSync.Result.AlreadyCurrent
        }
        runCurrent()
        manager.startBackgroundSync()
        runCurrent()
        manager.deleteBoardData(setOf(BoardBrand.MOONBOARD))
        runCurrent()
        verify(exactly = 0) { repo.deleteBoardDataForBrands(any()) }
        downloaded.complete(Unit)
        runCurrent()
        verify(exactly = 1) { repo.deleteBoardDataForBrands(setOf("moonboard")) }
        coVerify(exactly = 0) { media.sync(any(), any()) }
        assertEquals(emptySet<BoardBrand>(), prefs.boardDownloadBrands.first())
    }

    @Test fun `fresh periodic worker cannot race onboarding and download legacy all boards`() = runTest {
        val prefs = createTestUserPreferences(backgroundScope)
        val manager = manager(prefs)
        every { importer.isImported() } returns false
        runCurrent()
        manager.startBackgroundSync()
        runCurrent()
        coVerify(exactly = 0) { blossom.fetchManifest(); moon.sync(any()); aurora.sync(any(), any()); quantum.sync(any()); media.sync(any(), any()) }
        assertFalse(manager.state.value.isSyncing)
        assertFalse(manager.state.value.syncComplete)
    }

}
