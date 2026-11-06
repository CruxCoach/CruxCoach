package com.cruxcoach.android.ui.paintbox

import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaintBoxViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val ble = mockk<BoardBleConnection>(relaxed = true)
    private val prefs = mockk<UserPreferences>(relaxed = true)
    private val boards = mockk<BoardRepository>(relaxed = true)
    private val pictures = mockk<PaintRepository>(relaxed = true)
    private val projector = mockk<PaintProjector>()
    private val connection = MutableStateFlow(ConnectionState.CONNECTED)
    private lateinit var vm: PaintBoxViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { prefs.boardBrand } returns flowOf("kilter")
        every { prefs.boardLayoutId } returns flowOf(1)
        every { prefs.boardProductSizeId } returns flowOf(10)
        every { boards.getProductSize(10, "kilter") } returns BoardSize(10, 1, "Test", 0, 100, 0, 100, null)
        every { boards.getPlacementsForLayout(10, 1, "kilter") } returns listOf(
            BoardPlacement(1, 1, 1, 10, 10), BoardPlacement(2, 2, 1, 20, 20))
        every { boards.getPlacementLedMap(10, "kilter") } returns mapOf(1 to 10, 2 to 20)
        every { ble.connectionState } returns connection
        every { ble.connectedBoard } returns DiscoveredBoard("Test", "test", 3, "AA", -40)
        coEvery { projector.send(any(), any(), any()) } returns true
        vm = PaintBoxViewModel(pictures, boards, prefs, ble, projector)
    }
    @After fun teardown() {
        vm.pause()
        vm.viewModelScope.cancel()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    @Test fun `live starts off and enabling sends the whole current picture`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.enter()
        verify { ble.pauseAutomaticProjection("paintbox") }
        vm.beginStroke(); vm.paint(setOf(1)); vm.endStroke()
        advanceUntilIdle()
        coVerify(exactly = 0) { projector.send(any(), any(), any()) }
        assertFalse(vm.state.value.live)
        vm.toggleLive()
        advanceUntilIdle()
        coVerify { projector.send(match { it.colors == mapOf(1 to 28) }, any(), any()) }
        assertTrue(vm.state.value.live)
        vm.leave()
        assertFalse(vm.state.value.live)
        verify { ble.resumeAutomaticProjection("paintbox") }
    }
    @Test fun `pause drops pending updates and reconnect never enables live`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.toggleLive()
        vm.paint(setOf(1, 2))
        vm.pause()
        advanceUntilIdle()
        coVerify(exactly = 0) { projector.send(any(), any(), any()) }
        connection.value = ConnectionState.DISCONNECTED
        runCurrent()
        connection.value = ConnectionState.CONNECTED
        advanceUntilIdle()
        assertFalse(vm.state.value.live)
        coVerify(exactly = 0) { projector.send(any(), any(), any()) }
    }
    @Test fun `pointer samples coalesce and erasing transmits the complete resulting frame`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.toggleLive()
        vm.beginStroke(); vm.paint(setOf(1)); vm.paint(setOf(2)); vm.endStroke()
        advanceUntilIdle()
        coVerify(exactly = 1) { projector.send(match { it.colors.size == 2 }, any(), any()) }
        vm.eraser(); vm.beginStroke(); vm.paint(setOf(1)); vm.endStroke()
        advanceUntilIdle()
        coVerify { projector.send(match { it.colors == mapOf(2 to 28) }, any(), any()) }
        vm.undo()
        assertEquals(2, vm.state.value.document!!.colors.size)
    }
    @Test fun `single send stays offline and send failure preserves the draft`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.paint(setOf(1))
        vm.sendOnce()
        advanceUntilIdle()
        assertFalse(vm.state.value.live)
        coEvery { projector.send(any(), any(), any()) } returns false
        vm.toggleLive()
        advanceUntilIdle()
        assertFalse(vm.state.value.live)
        assertEquals(mapOf(1 to 28), vm.state.value.document!!.colors)
        assertTrue(vm.state.value.dirty)
        assertNotNull(vm.state.value.message)
    }
    @Test fun `accept image is one undo step and stale sampling cannot overwrite it`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.beginStroke(); vm.paint(setOf(1)); vm.endStroke()
        vm.startImage(mockk())
        val source = vm.state.value.imageImport!!
        vm.imagePreview(source, mapOf(2 to 224))
        assertEquals(mapOf(1 to 28), vm.state.value.document!!.colors)
        vm.acceptImage()
        vm.imagePreview(source, mapOf(1 to 3))
        assertNull(vm.state.value.imageImport)
        assertEquals(mapOf(2 to 224), vm.state.value.document!!.colors)
        vm.undo()
        assertEquals(mapOf(1 to 28), vm.state.value.document!!.colors)
        vm.redo()
        assertEquals(mapOf(2 to 224), vm.state.value.document!!.colors)
    }
    @Test fun `cancel restores live board even after lamp was disabled and preserves redo`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.beginStroke(); vm.paint(setOf(1)); vm.endStroke(); vm.undo()
        val before = vm.state.value
        vm.startImage(mockk())
        vm.imagePreview(vm.state.value.imageImport!!, mapOf(2 to 224))
        vm.toggleLive()
        advanceUntilIdle()
        coVerify { projector.send(match { it.colors == mapOf(2 to 224) }, any(), any()) }
        vm.pause()
        vm.cancelImage()
        advanceUntilIdle()
        coVerify { projector.send(match { it.colors.isEmpty() }, any(), any()) }
        assertEquals(before.document, vm.state.value.document)
        assertEquals(before.dirty, vm.state.value.dirty)
        assertTrue(vm.state.value.canRedo)
    }
    @Test fun `cancel invalidates pending preview and does not send when no preview reached board`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.startImage(mockk())
        vm.imagePreview(vm.state.value.imageImport!!, mapOf(1 to 224))
        vm.toggleLive()
        vm.cancelImage()
        advanceUntilIdle()
        coVerify(exactly = 0) { projector.send(any(), any(), any()) }
    }
    @Test fun `background tool selects resets and rearms`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.startImage(mockk())
        vm.imageBackgroundTool()
        assertTrue(vm.state.value.imageImport!!.pickingBackground)
        vm.imageBackground(0xff112233.toInt())
        assertEquals(0x112233, vm.state.value.imageImport!!.background)
        assertFalse(vm.state.value.imageImport!!.pickingBackground)
        vm.imageBackgroundTool()
        assertNull(vm.state.value.imageImport!!.background)
        vm.imageBackgroundTool()
        assertTrue(vm.state.value.imageImport!!.pickingBackground)
    }
    @Test fun `cancel fences an in flight preview and restores after transport finishes`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        val release = CompletableDeferred<Unit>()
        var oldFence: (() -> Boolean)? = null
        coEvery { projector.send(any(), any(), any()) } coAnswers {
            if (firstArg<PaintDocument>().colors.isNotEmpty()) {
                oldFence = thirdArg()
                release.await()
            }
            true
        }
        vm.startImage(mockk())
        vm.imagePreview(vm.state.value.imageImport!!, mapOf(1 to 224))
        vm.toggleLive()
        advanceTimeBy(101); runCurrent()
        assertTrue(oldFence!!())
        vm.cancelImage()
        assertFalse(oldFence!!())
        release.complete(Unit)
        advanceUntilIdle()
        coVerifyOrder {
            projector.send(match { it.colors == mapOf(1 to 224) }, any(), any())
            projector.send(match { it.colors.isEmpty() }, any(), any())
        }
    }
}
