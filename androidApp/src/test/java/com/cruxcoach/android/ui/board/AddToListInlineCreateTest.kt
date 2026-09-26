package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.PlaylistPlaybackCoordinator
import com.cruxcoach.android.data.PlaylistPlaybackState
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.Climb_lists
import com.cruxcoach.data.repository.PersonalBoardRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** "Neue Liste…" in the add-to-list dialog, given the name of a list that exists. */
@OptIn(ExperimentalCoroutinesApi::class)
class AddToListInlineCreateTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    /** The IO hops inside the model are real threads; poll briefly for their result. */
    private fun awaitUntil(check: () -> Boolean) {
        repeat(200) { if (check()) return; Thread.sleep(10) }
    }

    @Test
    fun `naming a list the climb is already in keeps it there`() {
        val repo = mockk<PersonalBoardRepository>(relaxed = true)
        every { repo.getAllClimbLists() } returns listOf(Climb_lists(5, "QA-Liste", false, "2026-09-24", 1))
        every { repo.getListIdsForClimb("u1") } returns setOf(5L)
        val board = mockk<BoardRepository>(relaxed = true)
        every { board.equivalentClimbUuids("u1") } returns setOf("u1")
        val playback = mockk<PlaylistPlaybackCoordinator>(relaxed = true)
        every { playback.state } returns MutableStateFlow(PlaylistPlaybackState())
        val vm = AddToListViewModel(repo, board, mockk(relaxed = true), playback)

        vm.open("u1", 40)
        awaitUntil { vm.state.value.climbInListIds == setOf(5L) }
        vm.updateNewListName("qa-liste")
        vm.createNewListAndAdd()
        Thread.sleep(200)

        // It used to toggle the existing list, taking the climb out of it.
        verify(exactly = 0) { repo.removeClimbFromList(any(), any()) }
        verify(exactly = 0) { repo.createClimbList(any(), any()) }
        assertEquals(setOf(5L), vm.state.value.climbInListIds)
        assertEquals("", vm.state.value.newListName)
    }
}
