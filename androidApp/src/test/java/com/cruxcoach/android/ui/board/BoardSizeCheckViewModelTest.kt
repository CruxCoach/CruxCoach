package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.BoardConnectionOwner
import com.cruxcoach.android.ble.BoardWrite
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.DiscoveredBoard
import com.cruxcoach.android.data.BoardConstants
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardLocationRepository
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

/** The view model's wiring: board writes, the background and the size order reach the check. */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardSizeCheckViewModelTest {

    private val board = DiscoveredBoard("Kilter Board#1", "1", 3, "AA:BB", -50)
    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    private val descriptor = MutableStateFlow<DiscoveredBoard?>(board)
    private val writes = MutableStateFlow(0L)
    private var time = 0L
    private val connection = mockk<BoardBleConnection>(relaxed = true) {
        every { connectionState } returns this@BoardSizeCheckViewModelTest.connectionState
        every { connectedBoardBrand } returns MutableStateFlow(BoardBrand.KILTER)
        every { connectedBoardDescriptor } returns descriptor
        every { boardWrites } returns writes
        coEvery { sendRawFrame(any(), any()) } answers { BoardWrite(true, ++writes.value) }
        coEvery { clearBoardWrite(any(), any()) } answers { BoardWrite(true, ++writes.value) }
        every { hasOtherKeepAliveOwners(any()) } returns false
        every { disconnect() } answers { dropLink() }
        every { connect(any(), any()) } answers {
            descriptor.value = firstArg()
            this@BoardSizeCheckViewModelTest.connectionState.value = ConnectionState.CONNECTED
        }
    }

    private fun dropLink() {
        connectionState.value = ConnectionState.DISCONNECTED
        descriptor.value = null
    }

    private fun kilter(id: Int) = BoardSize(id.toLong(), BoardConstants.KILTER_PRODUCT_ID.toLong(), "size $id", 0, 144, 0, 156, null)
    private val sizes = listOf(kilter(7), kilter(8), kilter(10))
    private val placements = (0 until 12).map { BoardPlacement(it.toLong(), it.toLong(), 1, it % 4 * 40L, it / 4 * 50L) }
    private val repository = mockk<BoardRepository>(relaxed = true) {
        sizes.forEach { size -> every { getProductSize(size.id.toInt(), "kilter") } returns size }
        every { getAllProductSizes(BoardConstants.KILTER_PRODUCT_ID.toLong(), "kilter") } returns sizes
        every { getPlacementsForLayout(any(), any(), "kilter") } returns placements
        // Other LEDs on every size, so each one lights holds of its own.
        every { getPlacementLedMap(any(), "kilter") } answers {
            val sizeId = firstArg<Int>()
            placements.associate { it.placementId.toInt() to sizeId * 100 + it.placementId.toInt() }
        }
    }
    private val preferences = mockk<UserPreferences>(relaxed = true) {
        every { boardBrand } returns flowOf("kilter")
        every { boardLayoutId } returns flowOf(1)
        every { boardProductSizeId } returns flowOf(10)
    }
    private val locations = mockk<BoardLocationRepository> {
        every { productSizeFrequency() } returns mapOf(10 to 900L, 7 to 50L, 8 to 10L)
    }

    // The catalogue is read on the same dispatcher: every call has run when it returns, and
    // nothing is left to resume after the test.
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(io: CoroutineDispatcher = dispatcher) =
        BoardSizeCheckViewModel(connection, repository, preferences, locations, io, now = { time })

    private fun openLit(): BoardSizeCheckViewModel = viewModel().apply {
        open(BoardBrand.KILTER, 10, null)
        await { it.lit }
    }

    private fun BoardSizeCheckViewModel.await(condition: (BoardSizeCheckState) -> Boolean): BoardSizeCheckState =
        runBlocking { withTimeout(5_000L) { state.first(condition) } }

    // The view model runs each call after the previous one; a call queued last is a barrier, and
    // its keep-alive release (the last step of close and pause) says it has run.
    private fun BoardSizeCheckViewModel.closeAndWait(releases: Int) {
        close()
        verify(timeout = 5_000L, exactly = releases) { connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
    }

    @Test fun `another sender's frame marks the holds replaced and stays when the sheet closes`() {
        val viewModel = openLit()
        writes.value++ // a session sends its climb
        viewModel.await { it.replaced }
        viewModel.lightIfDark()
        viewModel.closeAndWait(releases = 1)
        coVerify(exactly = 1) { connection.sendRawFrame(any(), any()) }
        coVerify(exactly = 0) { connection.clearBoardWrite(any(), any()) }
    }

    @Test fun `the check's own frames are not someone else's`() {
        val viewModel = openLit()
        viewModel.light()
        viewModel.reject() // only counts while the holds are lit: a wrong "replaced" would block it
        viewModel.await { it.step == BoardSizeCheckStep.PICK_OTHER }
        viewModel.closeAndWait(releases = 1)
        coVerify(exactly = 2) { connection.sendRawFrame(any(), any()) }
        coVerify(exactly = 1) { connection.clearBoardWrite(BoardBrand.KILTER, any()) }
    }

    @Test fun `in the background the board is dark and free, back in the app it lights again`() {
        val viewModel = openLit()
        viewModel.pause()
        verify(timeout = 5_000L, exactly = 1) { connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        coVerify(exactly = 1) { connection.clearBoardWrite(BoardBrand.KILTER, any()) }
        verify(exactly = 1) { connection.disconnect() }
        assertTrue(viewModel.state.value.paused)

        viewModel.resume() // after its own clear was noted: a wrong "replaced" would keep it dark
        viewModel.await { it.lit }
        verify(exactly = 1) { connection.connect(board, any()) }
        verify(exactly = 2) { connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        coVerify(exactly = 2) { connection.sendRawFrame(any(), any()) }
    }

    @Test fun `a climb sent while the app was away is not lit over on return`() {
        val viewModel = openLit()
        viewModel.pause()
        verify(timeout = 5_000L, exactly = 1) { connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        writes.value++
        viewModel.await { it.replaced }
        viewModel.resume()
        viewModel.closeAndWait(releases = 2)
        coVerify(exactly = 1) { connection.sendRawFrame(any(), any()) }
    }

    @Test fun `a check that opens while the app is away stays dark until it is back`() {
        val viewModel = viewModel()
        viewModel.pause() // ON_STOP before the sheet's first open ran
        viewModel.open(BoardBrand.KILTER, 10, null)
        verify(timeout = 5_000L, exactly = 1) { connection.releaseKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        assertTrue(viewModel.state.value.paused)
        coVerify(exactly = 0) { connection.sendRawFrame(any(), any()) }
        coVerify(exactly = 0) { connection.clearBoardWrite(any(), any()) }
        verify(exactly = 1) { connection.disconnect() } // free for other phones, as on ON_STOP
        viewModel.resume()
        viewModel.await { it.lit && !it.paused }
        verify(exactly = 2) { connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
    }

    @Test fun `a check the user is back for before it has opened lights and keeps the link`() {
        val io = StandardTestDispatcher(dispatcher.scheduler)
        val viewModel = viewModel(io)
        viewModel.pause()
        viewModel.open(BoardBrand.KILTER, 10, null) // waits for the catalogue
        viewModel.resume()
        viewModel.pause() // locked and unlocked again while it still opens
        viewModel.resume()
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.await { it.lit && !it.paused }
        verify(exactly = 1) { connection.acquireKeepAlive(BoardConnectionOwner.SIZE_CHECK) }
        verify(exactly = 0) { connection.releaseKeepAlive(any()) }
        verify(exactly = 0) { connection.disconnect() }
    }

    @Test fun `back after a long time away, the holds wait for a tap`() {
        val viewModel = openLit()
        viewModel.pause()
        verify(timeout = 5_000L, exactly = 1) { connection.disconnect() }
        time = BoardSizeCheckConnection.GONE_LIMIT_MS + 1 // another phone may have used the board
        viewModel.resume()
        verify(timeout = 5_000L, exactly = 1) { connection.connect(board, any()) }
        val back = viewModel.await { !it.paused }
        assertTrue(back.replaced)
        assertFalse(back.lit)
        viewModel.lightIfDark()
        coVerify(exactly = 1) { connection.sendRawFrame(any(), any()) }
        viewModel.light()
        viewModel.await { it.lit }
    }

    @Test fun `a board whose link dropped long before closing is not reconnected to`() {
        val viewModel = openLit()
        dropLink() // the view model notes when
        time = BoardSizeCheckConnection.GONE_LIMIT_MS + 1
        viewModel.closeAndWait(releases = 1)
        verify(exactly = 0) { connection.connect(any(), any()) }
        coVerify(exactly = 0) { connection.clearBoardWrite(any(), any()) }
    }

    @Test fun `a board whose link dropped just before closing is cleared`() {
        val viewModel = openLit()
        dropLink()
        time = BoardSizeCheckConnection.GONE_LIMIT_MS - 1
        viewModel.closeAndWait(releases = 1)
        verify(exactly = 1) { connection.connect(board, 1) }
        coVerify(exactly = 1) { connection.clearBoardWrite(BoardBrand.KILTER, 1L) }
    }

    @Test fun `kilter sizes are listed most common first`() {
        val viewModel = openLit()
        assertEquals(listOf(10, 7, 8), viewModel.state.value.sizes.map { it.id.toInt() })
    }

    @Test fun `other brands keep the catalogue's order`() {
        val tension = listOf(10, 7, 8).map { BoardSize(it.toLong(), 5L, "size $it", 0, 144, 0, 156, null) }
        assertEquals(tension, sizeCheckOrder(BoardBrand.TENSION, tension, mapOf(8 to 900L, 7 to 50L)))
        assertEquals(listOf(8, 7, 10), sizeCheckOrder(BoardBrand.KILTER, tension, mapOf(8 to 900L, 7 to 50L)).map { it.id.toInt() })
    }

    @Test fun `the climb menu learns whether to offer the check`() {
        val viewModel = viewModel()
        val saved = runBlocking { withTimeout(5_000L) { viewModel.savedBoard.first { it != null } } }
        assertTrue(saved!!.offered)
        assertEquals(10, saved.sizeId)
    }
}
