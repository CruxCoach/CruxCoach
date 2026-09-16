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
import kotlinx.coroutines.cancel
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
            vm.saveDownloadSelection(setOf(BoardBrand.MOONBOARD), startInitial = true)
            runCurrent()
            assertEquals(setOf(BoardBrand.MOONBOARD), prefs.boardDownloadBrands.first())
            verify(exactly = 1) { sync.startInitialSyncIfNeeded() }
        } finally {
            vm.viewModelScope.cancel()
            runCurrent()
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
            vm.viewModelScope.cancel()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
