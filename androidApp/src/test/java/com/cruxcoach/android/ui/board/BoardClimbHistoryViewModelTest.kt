package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.HistoryRetention
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.ClimbHistoryEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.domain.board.ProgressHistoryIssue
import com.cruxcoach.domain.board.ProgressHistoryScreenState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoardClimbHistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<PersonalBoardRepository>(relaxed = true)
    private val preferences = mockk<UserPreferences>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { preferences.historyRetention } returns flowOf(HistoryRetention.OFF)
        every { preferences.gradeScale } returns flowOf(GradeScale.FRENCH)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `failed history stream exposes typed error and retry restarts it`() {
        var subscriptions = 0
        every { repository.observeClimbHistory() } answers {
            if (subscriptions++ == 0) {
                flow<List<ClimbHistoryEntry>> { throw IllegalStateException("database detail") }
            } else {
                flowOf(listOf(historyEntry()))
            }
        }

        val viewModel = BoardClimbHistoryViewModel(repository, preferences)

        assertFalse(viewModel.state.value.isLoading)
        assertEquals(ProgressHistoryIssue.LOAD_FAILED, viewModel.state.value.issue)
        assertTrue(
            viewModel.state.value.toPortableState(
                isLoading = viewModel.state.value.isLoading,
                issue = viewModel.state.value.issue,
            ) is ProgressHistoryScreenState.Error,
        )

        viewModel.retryCurrentIssue()

        assertEquals(2, subscriptions)
        assertEquals(null, viewModel.state.value.issue)
        assertEquals("retry-climb", viewModel.state.value.entries.single().climbUuid)
    }

    @Test
    fun `failed retention update stays non blocking and retries the same choice`() {
        every { repository.observeClimbHistory() } returns flowOf(emptyList())
        var writes = 0
        coEvery { preferences.setHistoryRetention(HistoryRetention.DAYS_90) } answers {
            if (writes++ == 0) throw IllegalStateException("storage detail")
        }
        val viewModel = BoardClimbHistoryViewModel(repository, preferences)

        viewModel.setRetention(HistoryRetention.DAYS_90)

        assertEquals(ProgressHistoryIssue.RETENTION_UPDATE_FAILED, viewModel.state.value.issue)
        assertTrue(
            viewModel.state.value.toPortableState(issue = viewModel.state.value.issue) is
                ProgressHistoryScreenState.Empty,
        )

        viewModel.retryCurrentIssue()

        assertEquals(null, viewModel.state.value.issue)
        assertEquals(2, writes)
    }

    @Test
    fun `failed delete preserves selection and retry uses the same ids`() {
        every { repository.observeClimbHistory() } returns flowOf(listOf(historyEntry()))
        var deletes = 0
        coEvery { repository.deleteClimbHistory(listOf(41L)) } answers {
            if (deletes++ == 0) throw IllegalStateException("database detail")
        }
        val viewModel = BoardClimbHistoryViewModel(repository, preferences)
        viewModel.toggleSelection(41L)

        viewModel.deleteSelected()

        assertEquals(ProgressHistoryIssue.DELETE_FAILED, viewModel.state.value.issue)
        assertEquals(setOf(41L), viewModel.state.value.selectedIds)

        viewModel.retryCurrentIssue()

        assertEquals(null, viewModel.state.value.issue)
        assertTrue(viewModel.state.value.selectedIds.isEmpty())
        coVerify(exactly = 2) { repository.deleteClimbHistory(listOf(41L)) }
    }
}

private fun historyEntry() = ClimbHistoryEntry(
    id = 41,
    climbUuid = "retry-climb",
    climbName = "Second try",
    angle = 40,
    difficultyAverage = 20.0,
    boardBrand = "kilter",
    layoutId = 1,
    climbedAt = "2026-08-31T09:30:00",
    recordedAt = "2026-08-31T09:31:00",
)
