package com.cruxcoach.android.ui.settings

import android.app.Application
import com.cruxcoach.android.R
import com.cruxcoach.android.data.BoardSyncManager
import com.cruxcoach.android.data.shinywall.InMemoryShinyWallTokenStorage
import com.cruxcoach.android.data.shinywall.ShinyWallCatalogueMissingException
import com.cruxcoach.android.data.shinywall.ShinyWallSession
import com.cruxcoach.android.data.shinywall.ShinyWallSyncEngine
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The Shiny Wall account import waits for the Shiny Wall catalogue: the card says so and
 * offers to load it, instead of an import that skips entries for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ShinyWallAccountCatalogueTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `an import before the catalogue offers to load it`() {
        val engine = mockk<ShinyWallSyncEngine>()
        coEvery { engine.previewImport() } returns Result.failure(ShinyWallCatalogueMissingException())
        val sync = mockk<BoardSyncManager>(relaxed = true)
        val store = InMemoryShinyWallTokenStorage(ShinyWallSession("uid1", "id", "refresh", Long.MAX_VALUE, "a@b.c"))
        val viewModel = ShinyWallAccountViewModel(app, store, mockk(relaxed = true), engine, sync)

        viewModel.loadPreview()

        val waiting = viewModel.state.value
        assertTrue(waiting.catalogueMissing)
        assertFalse(waiting.isImporting)
        // Not an error message: the card itself carries the reason and the action.
        assertNull(waiting.resultMessage)
        assertNull(waiting.preview)

        viewModel.loadCatalogue()

        verify(exactly = 1) { sync.loadBoardCatalogue(BoardBrand.SHINYWALL) }
        val loading = viewModel.state.value
        assertFalse(loading.catalogueMissing)
        assertFalse(loading.resultIsError)
        assertEquals(app.getString(R.string.shinywall_catalogue_loading), loading.resultMessage)
    }
}
