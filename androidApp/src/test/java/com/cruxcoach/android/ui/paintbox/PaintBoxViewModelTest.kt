package com.cruxcoach.android.ui.paintbox

import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.R
import com.cruxcoach.android.ble.*
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.*
import com.cruxcoach.domain.board.MoonBoardHoldSets
import com.cruxcoach.domain.board.MoonBoardVariant
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
    private val savedBrand = MutableStateFlow("kilter")
    private val savedLayout = MutableStateFlow(1)
    private val savedSize = MutableStateFlow(10)
    private val busy = MutableStateFlow<Int?>(null)
    private lateinit var vm: PaintBoxViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { prefs.boardBrand } returns savedBrand
        every { prefs.boardLayoutId } returns savedLayout
        every { prefs.boardProductSizeId } returns savedSize
        every { boards.getProductSize(12, "kilter") } returns BoardSize(12, 1, "Other", 0, 100, 0, 100, null)
        every { boards.getPlacementsForLayout(12, 1, "kilter") } returns listOf(BoardPlacement(3, 3, 1, 30, 30))
        every { boards.getProductSize(10, "kilter") } returns BoardSize(10, 1, "Test", 0, 100, 0, 100, null)
        // Hold 4 has no LED on this size.
        every { boards.getPlacementsForLayout(10, 1, "kilter") } returns listOf(
            BoardPlacement(1, 1, 1, 10, 10), BoardPlacement(2, 2, 1, 20, 20), BoardPlacement(4, 4, 1, 40, 40))
        every { boards.getPlacementLedMap(10, "kilter") } returns mapOf(1 to 10, 2 to 20)
        every { boards.getPlacementLedMap(12, "kilter") } returns mapOf(3 to 30)
        // Size 14: the layout is known, its catalogue (LED data) is not on the phone.
        every { boards.getProductSize(14, "kilter") } returns BoardSize(14, 1, "Missing", 0, 100, 0, 100, null)
        every { boards.getPlacementsForLayout(14, 1, "kilter") } returns listOf(BoardPlacement(5, 5, 1, 50, 50))
        every { boards.getPlacementLedMap(14, "kilter") } returns emptyMap()
        every { ble.connectionState } returns connection
        every { ble.connectedBoard } returns DiscoveredBoard("Test", "test", 3, "AA", -40)
        coEvery { projector.send(any(), any(), any()) } returns true
        every { projector.busy } returns busy
        every { projector.boardBusy() } answers { busy.value }
        every { projector.lastSendDimmed } returns false
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
    @Test fun `pipette takes the color of a hold and hands the brush back`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        // 0xf1 is a board color the color sheet has no named swatch for, as a photo brings them.
        vm.brush(0xf1); vm.beginStroke(); vm.paint(setOf(1)); vm.endStroke()
        vm.eraser()
        vm.pipette()
        assertTrue(vm.state.value.pipette)
        // The stroke goes on over hold 2: only the pick counts.
        vm.beginStroke(); vm.paint(setOf(1)); vm.paint(setOf(2)); vm.endStroke()
        assertEquals(0xf1, vm.state.value.brush)
        assertFalse(vm.state.value.pipette)
        assertFalse(vm.state.value.eraser)
        assertNull(vm.state.value.message)
        assertEquals(mapOf(1 to 0xf1), vm.state.value.document!!.colors)
        vm.beginStroke(); vm.paint(setOf(2)); vm.endStroke()
        assertEquals(mapOf(1 to 0xf1, 2 to 0xf1), vm.state.value.document!!.colors)
        // The color sheet offers it under "In this picture"; named colors stay in their families.
        val kilter = com.cruxcoach.domain.board.BoardBrand.KILTER
        assertEquals(listOf(0xf1), PaintPalette.pictureColors(0xf1, vm.state.value.document!!.colors, kilter))
        assertEquals(listOf(0x91, 0xf1), PaintPalette.pictureColors(0x91, mapOf(1 to 0xf1, 2 to 0x1c), kilter))
        assertTrue(PaintPalette.pictureColors(0x1c, mapOf(1 to 0xe0), com.cruxcoach.domain.board.BoardBrand.MOONBOARD).isEmpty())
    }
    @Test fun `pipette on a hold without color picks nothing and says so`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.beginStroke(); vm.paint(setOf(1)); vm.endStroke()
        vm.brush(0xe0)
        vm.pipette()
        vm.beginStroke(); vm.paint(setOf(2)); vm.endStroke()
        assertTrue(vm.state.value.pipette)
        assertEquals(0xe0, vm.state.value.brush)
        assertEquals(R.string.paintbox_pipette_empty, vm.state.value.message)
        assertEquals(mapOf(1 to 28), vm.state.value.document!!.colors)
        // A stroke that starts on an empty hold and reaches a colored one picks that one.
        vm.message(null)
        vm.beginStroke(); vm.paint(setOf(2)); vm.paint(setOf(1)); vm.endStroke()
        assertEquals(28, vm.state.value.brush)
        assertFalse(vm.state.value.pipette)
        assertNull(vm.state.value.message)
        // Choosing brush or eraser leaves the pipette.
        vm.pipette(); vm.eraser()
        assertFalse(vm.state.value.pipette)
        vm.pipette(); vm.brush(0xe0)
        assertFalse(vm.state.value.pipette)
    }
    @Test fun `background starts dark for a clear border and a picked color replaces the detected one`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.startImage(mockk(), detected = PaintBackground(color = 0xffffff, clear = true))
        val first = vm.state.value.imageImport!!
        assertTrue(first.backgroundOff)
        assertEquals(0xffffff, first.darkBackground)
        vm.imagePreview(first, mapOf(1 to 224))
        vm.imageBackgroundOff(false)
        assertNull(vm.state.value.imageImport!!.darkBackground)
        assertFalse(vm.state.value.imageImport!!.ready)
        vm.imageBackground(0xff112233.toInt())
        assertTrue(vm.state.value.imageImport!!.backgroundOff)
        assertEquals(0x112233, vm.state.value.imageImport!!.darkBackground)
        vm.imageBackgroundOff(false)
        vm.imageBackgroundOff(true)
        assertEquals(0x112233, vm.state.value.imageImport!!.darkBackground)
        vm.imageBackgroundTool()
        assertEquals(0xffffff, vm.state.value.imageImport!!.darkBackground)
        // A photo without a clear border starts with the whole picture lit.
        vm.startImage(mockk(), detected = PaintBackground(color = 0x808080, clear = false))
        assertFalse(vm.state.value.imageImport!!.backgroundOff)
        assertNull(vm.state.value.imageImport!!.darkBackground)
        vm.imageBackgroundOff(true)
        assertEquals(0x808080, vm.state.value.imageImport!!.darkBackground)
        // Taking a picked color back leaves such a photo fully lit again, as it was loaded.
        vm.imageBackground(0xff112233.toInt())
        assertEquals(0x112233, vm.state.value.imageImport!!.darkBackground)
        vm.imageBackgroundTool()
        assertFalse(vm.state.value.imageImport!!.backgroundOff)
        assertNull(vm.state.value.imageImport!!.darkBackground)
    }
    @Test fun `a new photo is put in place once and Align asks again`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.startImage(mockk())
        val loaded = vm.state.value.imageImport!!
        assertFalse(loaded.placed)
        vm.imageAlign()
        assertFalse("nothing to align before the first placement", vm.state.value.imageImport!!.aligning)
        // The size of the view becoming known is not the user moving the photo.
        vm.imageResized()
        vm.imageTransform(loaded.transform)
        assertFalse(vm.state.value.imageImport!!.placed)
        assertFalse(vm.state.value.imageImport!!.moved)
        val fitted = PaintImageTransform(center = androidx.compose.ui.geometry.Offset(.4f, .6f), zoom = 1.5f)
        vm.imagePlaced(vm.state.value.imageImport!!, fitted)
        val placed = vm.state.value.imageImport!!
        assertTrue(placed.placed)
        assertEquals(fitted, placed.transform)
        assertFalse(placed.ready)
        vm.imageAlign()
        val aligning = vm.state.value.imageImport!!
        assertTrue(aligning.aligning)
        // A result for a photo that has changed meanwhile is dropped ...
        vm.imagePlaced(placed, PaintImageTransform(zoom = 3f))
        assertEquals(fitted, vm.state.value.imageImport!!.transform)
        assertTrue(vm.state.value.imageImport!!.aligning)
        // ... the one for the current photo applies and ends the request.
        val settled = fitted.copy(zoom = 1.45f)
        vm.imagePlaced(aligning, settled)
        assertEquals(settled, vm.state.value.imageImport!!.transform)
        assertFalse(vm.state.value.imageImport!!.aligning)
        // Another background before the user has moved the photo: it is fitted again.
        vm.imageBackground(0xff112233.toInt())
        assertFalse(vm.state.value.imageImport!!.placed)
        vm.imagePlaced(vm.state.value.imageImport!!, fitted)
        // Moving the photo while a request to settle it runs ends the request: the hand wins.
        vm.imageAlign()
        assertTrue(vm.state.value.imageImport!!.aligning)
        val byHand = fitted.copy(zoom = 2f)
        vm.imageTransform(byHand)
        assertFalse(vm.state.value.imageImport!!.aligning)
        assertTrue(vm.state.value.imageImport!!.moved)
        // A photo the user has moved stays where it is when the background changes.
        vm.imageBackgroundOff(false)
        assertTrue(vm.state.value.imageImport!!.placed)
        assertEquals(byHand, vm.state.value.imageImport!!.transform)
        // Moving a photo by hand before its first placement is done keeps it where the user put it.
        vm.startImage(mockk())
        val moved = PaintImageTransform(center = androidx.compose.ui.geometry.Offset(.3f, .3f))
        vm.imageTransform(moved)
        assertTrue(vm.state.value.imageImport!!.placed)
        assertEquals(moved, vm.state.value.imageImport!!.transform)
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
    @Test fun `a picked board opens once the picker has saved it`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.paint(setOf(1))
        vm.boardPicked()
        advanceUntilIdle()
        // The picker saves in the background: until then the picture stays.
        assertEquals(10, vm.state.value.document!!.board.sizeId)
        assertEquals(mapOf(1 to 28), vm.state.value.document!!.colors)
        savedSize.value = 12
        // Board data loads on the IO dispatcher, outside the test scheduler.
        val opened = vm.state.first { it.document?.board?.sizeId == 12 && !it.loading }
        assertEquals(setOf(3), opened.placements.keys)
        assertTrue(opened.document!!.colors.isEmpty())
    }
    @Test fun `opening another board keeps the brush color it can show`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.brush(0xe0)
        vm.boardPicked()
        savedSize.value = 12
        val opened = vm.state.first { it.document?.board?.sizeId == 12 && !it.loading }
        assertEquals(0xe0, opened.brush)
        assertFalse(opened.eraser)
    }
    @Test fun `a board saved elsewhere asks first when the picture has changes`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.paint(setOf(1))
        // E.g. the connection sheet connected another board and saved it.
        savedSize.value = 12
        val asked = vm.state.first { it.boardChange != null }
        assertEquals(12, asked.boardChange!!.sizeId)
        assertEquals(10, asked.document!!.board.sizeId)
        assertEquals(mapOf(1 to 28), asked.document!!.colors)
        vm.openBoardChange()
        val opened = vm.state.first { it.document?.board?.sizeId == 12 && !it.loading }
        assertNull(opened.boardChange)
        assertTrue(opened.document!!.colors.isEmpty())
    }
    @Test fun `keeping the picture leaves it on its board`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.paint(setOf(1))
        savedSize.value = 12
        vm.state.first { it.boardChange != null }
        vm.keepBoard()
        advanceUntilIdle()
        assertNull(vm.state.value.boardChange)
        assertEquals(10, vm.state.value.document!!.board.sizeId)
        assertEquals(mapOf(1 to 28), vm.state.value.document!!.colors)
        // Picking the app's board later opens it, although the picker saves nothing new.
        vm.boardPicked()
        val opened = vm.state.first { it.document?.board?.sizeId == 12 && !it.loading }
        assertTrue(opened.document!!.colors.isEmpty())
    }
    @Test fun `a kept picture of another board sends nothing and offers the app's board`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.paint(setOf(1))
        // The connection sheet connected size 12; the picture stays on size 10.
        savedSize.value = 12
        vm.state.first { it.boardChange != null }
        vm.keepBoard()
        advanceUntilIdle()
        vm.sendOnce()
        advanceUntilIdle()
        assertTrue(vm.state.value.otherBoard)
        vm.dismissOtherBoard()
        vm.toggleLive()
        advanceUntilIdle()
        assertFalse(vm.state.value.live)
        assertTrue(vm.state.value.otherBoard)
        coVerify(exactly = 0) { projector.send(any(), any(), any()) }
        vm.openAppBoard()
        val opened = vm.state.first { it.document?.board?.sizeId == 12 && !it.loading }
        assertFalse(opened.otherBoard)
        vm.paint(setOf(3))
        vm.sendOnce()
        advanceUntilIdle()
        coVerify { projector.send(match { it.board.sizeId == 12 && it.colors == mapOf(3 to 28) }, mapOf(3 to 30), any()) }
        assertFalse(vm.state.value.otherBoard)
    }
    @Test fun `loading a picture of the app's board leaves the board selection alone`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.load(PaintDocument(board = PaintBoard("kilter", 1, 10), name = "Same"))
        vm.state.first { it.document?.name == "Same" && !it.loading }
        coVerify(exactly = 0) { prefs.setBoardSelection(any(), any(), any(), any()) }
        vm.load(PaintDocument(board = PaintBoard("kilter", 1, 12), name = "Other", colors = mapOf(3 to 28)))
        vm.state.first { it.document?.name == "Other" && !it.loading }
        coVerify(exactly = 1) { prefs.setBoardSelection("kilter", 1, 12, null) }
        // The switched picture is on the app's board at once, before the saved board arrives.
        vm.sendOnce()
        advanceUntilIdle()
        assertFalse(vm.state.value.otherBoard)
        coVerify { projector.send(match { it.board.sizeId == 12 }, mapOf(3 to 30), any()) }
    }
    @Test fun `a MoonBoard picture keeps the angle of the open MoonBoard`() = runTest(dispatcher) {
        val variant = MoonBoardVariant.MOONBOARD_2016
        val sets = MoonBoardHoldSets.setIdsFor(variant)
        every { prefs.moonBoardHoldSets(variant) } returns flowOf(sets)
        vm.state.first { !it.loading }
        vm.boardPicked()
        savedBrand.value = "moonboard"
        savedLayout.value = variant.layoutId.toInt()
        vm.state.first { it.document?.board?.brand == "moonboard" && !it.loading }
        // Selecting the MoonBoard again would set its angle to 40.
        vm.load(PaintDocument(board = PaintBoard("moonboard", variant.layoutId.toInt(), 0, sets), name = "Moon"))
        vm.state.first { it.document?.name == "Moon" && !it.loading }
        coVerify(exactly = 0) { prefs.setMoonBoardSelection(any(), any()) }
        coVerify(exactly = 0) { prefs.setMoonBoardHoldSets(any(), any()) }
        // Other hold sets of the same MoonBoard change only the sets.
        vm.load(PaintDocument(board = PaintBoard("moonboard", variant.layoutId.toInt(), 0, sets.take(1)), name = "Part"))
        vm.state.first { it.document?.name == "Part" && !it.loading }
        coVerify { prefs.setMoonBoardHoldSets(variant, setOf(sets.first())) }
        coVerify(exactly = 0) { prefs.setMoonBoardSelection(any(), any()) }
    }
    @Test fun `a board saved elsewhere opens directly when nothing would be lost`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        savedSize.value = 12
        val opened = vm.state.first { it.document?.board?.sizeId == 12 && !it.loading }
        assertNull(opened.boardChange)
    }
    @Test fun `only a picture of another board switches the app's board`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        assertFalse(vm.switchesBoard(PaintDocument(board = PaintBoard("kilter", 1, 10))))
        assertTrue(vm.switchesBoard(PaintDocument(board = PaintBoard("kilter", 1, 12))))
        assertTrue(vm.switchesBoard(PaintDocument(board = PaintBoard("tension", 1, 10))))
    }
    @Test fun `holds without an LED on this size cannot be painted and drop out of a loaded picture`() = runTest(dispatcher) {
        val opened = vm.state.first { !it.loading }
        assertEquals(setOf(1, 2), opened.placements.keys)
        vm.paint(setOf(4))
        assertTrue(vm.state.value.document!!.colors.isEmpty())
        vm.load(PaintDocument(board = PaintBoard("kilter", 1, 10), name = "Old", colors = mapOf(1 to 28, 4 to 28)))
        val loaded = vm.state.first { it.document?.name == "Old" && !it.loading }
        assertEquals(mapOf(1 to 28), loaded.document!!.colors)
        assertFalse(loaded.dirty)
    }
    @Test fun `a picture opened without the board's catalogue keeps its holds for the retry`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.load(PaintDocument(board = PaintBoard("kilter", 1, 14), name = "Away", colors = mapOf(5 to 28)))
        val opened = vm.state.first { it.document?.name == "Away" && !it.loading }
        assertTrue(opened.missingBoard)
        assertEquals(mapOf(5 to 28), opened.document!!.colors)
    }
    @Test fun `the connection sheet ends an earlier agreement to leave the picture`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.paint(setOf(1))
        // Discard was chosen for the picker, which then kept the open board.
        vm.boardPicked()
        vm.connectionSheetOpened()
        savedSize.value = 12
        val asked = vm.state.first { it.boardChange != null }
        assertEquals(mapOf(1 to 28), asked.document!!.colors)
    }
    @Test fun `a session or board sharing stops live and keeps it off`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.toggleLive()
        advanceUntilIdle()
        assertTrue(vm.state.value.live)
        busy.value = R.string.paintbox_session_active
        advanceUntilIdle()
        assertFalse(vm.state.value.live)
        assertEquals(R.string.paintbox_session_active, vm.state.value.message)
        vm.message(null)
        vm.toggleLive()
        assertFalse(vm.state.value.live)
        assertEquals(R.string.paintbox_session_active, vm.state.value.message)
        busy.value = R.string.paintbox_relay_active
        vm.toggleLive()
        assertEquals(R.string.paintbox_relay_active, vm.state.value.message)
        busy.value = null
        vm.toggleLive()
        assertTrue(vm.state.value.live)
    }
    @Test fun `a board PaintBox cannot light opens as not supported and paints nothing`() = runTest(dispatcher) {
        vm.state.first { !it.loading }
        vm.boardPicked()
        savedBrand.value = "shinywall"
        val state = vm.state.first { it.document?.board?.brand == "shinywall" && !it.loading }
        assertTrue(state.unsupportedBoard)
        assertFalse(state.missingBoard)
        assertFalse(state.boardReady)
        vm.paint(setOf(1))
        vm.sendOnce()
        advanceUntilIdle()
        assertTrue(vm.state.value.document!!.colors.isEmpty())
        coVerify(exactly = 0) { projector.send(any(), any(), any()) }
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
