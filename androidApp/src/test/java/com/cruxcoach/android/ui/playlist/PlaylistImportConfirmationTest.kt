package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.SavedStateHandle
import com.cruxcoach.android.fakes.FakePersonalBoardRepository
import com.cruxcoach.android.util.PlaylistShareLink
import com.cruxcoach.data.repository.ListPlaybackAdvance
import com.cruxcoach.data.repository.ListPlaybackOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistImportConfirmationTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening a valid app link previews without writing and confirmation persists once`() = runTest {
        val repo = FakePersonalBoardRepository()
        val payload = payload()
        val viewModel = PlaylistImportViewModel(
            SavedStateHandle(mapOf("payload" to payload)),
            repo,
            dispatcher,
        )

        val preview = viewModel.state.value.preview
        assertNotNull(preview)
        assertEquals("Shared circuit", preview?.name)
        assertEquals(2, preview?.climbCount)
        assertEquals(1, preview?.restCount)
        assertTrue(repo.listMeta.isEmpty())
        assertTrue(repo.playbackSteps.isEmpty())

        viewModel.confirmImport()
        viewModel.confirmImport()
        val imported = withTimeout(5_000) {
            viewModel.state.first { it.importedListId != null }
        }

        assertFalse(imported.error)
        assertEquals(1, repo.listMeta.size)
        assertEquals(3, repo.playbackSteps.getValue(requireNotNull(imported.importedListId)).size)
    }

    @Test
    fun `invalid app link offers no confirmation and never writes`() {
        val repo = FakePersonalBoardRepository()
        val viewModel = PlaylistImportViewModel(
            SavedStateHandle(mapOf("payload" to "damaged")),
            repo,
            dispatcher,
        )

        assertTrue(viewModel.state.value.error)
        assertEquals(null, viewModel.state.value.preview)
        viewModel.confirmImport()
        assertTrue(repo.listMeta.isEmpty())
    }

    private fun payload(): String {
        val first = "305ecf35-4ab5-4c9c-afd5-91af0848004b"
        val second = "00000000-1111-2222-3333-444444444444"
        return PlaylistShareLink.buildPlan(
            name = "Shared circuit",
            steps = listOf(
                PlaylistShareLink.SharedStep.Climb(first, 40),
                PlaylistShareLink.SharedStep.Rest(90),
                PlaylistShareLink.SharedStep.Climb(second, 35),
            ),
            order = ListPlaybackOrder.LIST,
            advance = ListPlaybackAdvance.MANUAL,
            defaultRestSeconds = 60,
        )!!.substringAfterLast("/l/")
    }
}
