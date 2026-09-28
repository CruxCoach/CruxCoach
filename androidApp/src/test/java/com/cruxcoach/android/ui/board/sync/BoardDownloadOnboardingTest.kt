package com.cruxcoach.android.ui.board.sync

import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.BoardSyncState
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardDownloadOnboardingTest {
    @Test fun `fresh onboarding suggests active board but cannot download until confirmed`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardBrand(BoardBrand.MOONBOARD.wireValue)
        val sync = mockk<BoardSyncManager>(relaxed = true)
        every { sync.state } returns MutableStateFlow(BoardSyncState())
        val vm = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
        try {
            assertEquals(setOf(BoardBrand.MOONBOARD), vm.initialDownloadSelection())
            assertEquals(emptySet<BoardBrand>(), prefs.boardDownloadBrands.first())
            verify(exactly = 0) { sync.startInitialSyncIfNeeded() }
            vm.confirmOnboardingDownloads(setOf(BoardBrand.MOONBOARD))
            runCurrent()
            assertEquals(setOf(BoardBrand.MOONBOARD), prefs.boardDownloadBrands.first())
            verify(exactly = 1) { sync.startInitialSyncIfNeeded() }
        } finally {
            // IO work must finish cancellation before Main is removed.
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun `leaving the first step before continuing suggests the board again`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardBrand(BoardBrand.MOONBOARD.wireValue)
        val sync = mockk<BoardSyncManager>(relaxed = true)
        every { sync.state } returns MutableStateFlow(BoardSyncState())
        try {
            // The first visit leaves its empty placeholder behind ...
            val first = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
            assertEquals(setOf(BoardBrand.MOONBOARD), first.initialDownloadSelection())
            first.viewModelScope.coroutineContext.job.cancelAndJoin()
            // ... which the next one, after Back and a relaunch, must not read as a choice.
            val again = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
            assertEquals(setOf(BoardBrand.MOONBOARD), again.initialDownloadSelection())
            again.viewModelScope.coroutineContext.job.cancelAndJoin()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test fun `a confirmed empty choice holds when returning to the first step`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        val sync = mockk<BoardSyncManager>(relaxed = true)
        every { sync.state } returns MutableStateFlow(BoardSyncState())
        val vm = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
        try {
            vm.initialDownloadSelection()
            vm.confirmOnboardingDownloads(emptySet())
            assertEquals(emptySet<BoardBrand>(), vm.initialDownloadSelection())
        } finally {
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun `continuing with no boards saves opt out without starting a download`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        val sync = mockk<BoardSyncManager>(relaxed = true)
        every { sync.state } returns MutableStateFlow(BoardSyncState())
        val vm = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
        try {
            vm.initialDownloadSelection()
            vm.confirmOnboardingDownloads(emptySet())
            assertEquals(emptySet<BoardBrand>(), prefs.boardDownloadBrands.first())
            verify(exactly = 0) { sync.startInitialSyncIfNeeded() }
        } finally {
            // IO work must finish cancellation before Main is removed.
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun `adding a board on returning to setup schedules a follow up rather than losing it to initial sync guard`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER))
        val sync = mockk<BoardSyncManager>(relaxed = true)
        every { sync.state } returns MutableStateFlow(BoardSyncState(isSyncing = true))
        val vm = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
        try {
            vm.confirmOnboardingDownloads(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))
            assertEquals(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD), prefs.boardDownloadBrands.first())
            verify(exactly = 1) { sync.startSelectedSyncAfterCurrent() }
            verify(exactly = 0) { sync.startInitialSyncIfNeeded() }
            vm.confirmOnboardingDownloads(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD))
            verify(exactly = 1) { sync.startSelectedSyncAfterCurrent() }
        } finally {
            // IO work must finish cancellation before Main is removed.
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun `confirmed additions queue during sync but unchanged and reduced selections do not`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER))
        val sync = mockk<BoardSyncManager>(relaxed = true)
        every { sync.state } returns MutableStateFlow(BoardSyncState(isSyncing = true))
        val vm = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
        try {
            val both = setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD)
            vm.saveDownloadSelection(both)
            runCurrent()
            assertEquals(both, prefs.boardDownloadBrands.first())
            verify(exactly = 1) { sync.startSelectedSyncAfterCurrent() }
            vm.saveDownloadSelection(both)
            runCurrent()
            vm.saveDownloadSelection(emptySet())
            runCurrent()
            assertEquals(emptySet<BoardBrand>(), prefs.boardDownloadBrands.first())
            verify(exactly = 1) { sync.startSelectedSyncAfterCurrent() }
            verify(exactly = 0) { sync.startInitialSyncIfNeeded() }
        } finally {
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    @Test fun `onboarding revisit preserves an explicit download choice`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.QUANTUM))
        val sync = mockk<BoardSyncManager>(relaxed = true)
        every { sync.state } returns MutableStateFlow(BoardSyncState())
        val vm = BoardSyncViewModel(sync, prefs, mockk(relaxed = true))
        try {
            assertEquals(setOf(BoardBrand.QUANTUM), vm.initialDownloadSelection())
            assertEquals(setOf(BoardBrand.QUANTUM), prefs.boardDownloadBrands.first())
        } finally {
            // IO work must finish cancellation before Main is removed.
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
