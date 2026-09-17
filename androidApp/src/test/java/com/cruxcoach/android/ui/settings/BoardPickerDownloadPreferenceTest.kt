package com.cruxcoach.android.ui.settings

import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.*
import com.cruxcoach.android.fakes.createTestUserPreferences
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardPickerDownloadPreferenceTest {
    @Test fun `only missing excluded boards require consent and approval persists without discarding others`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = createTestUserPreferences(backgroundScope)
        prefs.setBoardDownloadBrands(setOf(BoardBrand.KILTER))
        val repo = mockk<BoardRepository>(relaxed = true)
        every { repo.hasClimbsForBrand("moonboard") } returns false
        every { repo.hasClimbsForBrand("tension") } returns true
        val manager = mockk<BoardSyncManager>(relaxed = true)
        every { manager.state } returns MutableStateFlow(BoardSyncState())
        val vm = BoardPickerViewModel(mockk(relaxed = true), prefs, repo,
            mockk(relaxed = true), mockk(relaxed = true), manager)
        try {
            assertTrue(vm.needsDownloadConsent(BoardBrand.MOONBOARD))
            assertFalse(vm.needsDownloadConsent(BoardBrand.KILTER))
            assertFalse(vm.needsDownloadConsent(BoardBrand.TENSION))
            vm.enableDownloads(BoardBrand.MOONBOARD)
            assertEquals(setOf(BoardBrand.KILTER, BoardBrand.MOONBOARD), prefs.boardDownloadBrands.first())
            assertFalse(vm.needsDownloadConsent(BoardBrand.MOONBOARD))
        } finally {
            // IO work must finish cancellation before Main is removed.
            vm.viewModelScope.coroutineContext.job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
