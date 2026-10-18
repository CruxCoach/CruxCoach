package com.cruxcoach.app.creator

import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.DiscoveredBoard
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.community.ClimbEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Android's live LED mirror of the climb editor: what reaches the board, when, and
 * what never does. The link double behaves like the app's presenter: a send marks the
 * link busy until the test lets it finish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreatorBoardMirrorTest {

    private class FakeLink : EditorPreviewLink {
        val link = MutableStateFlow(BoardConnectionUiState())
        val sent = mutableListOf<Map<Int, Int>>()
        val moonSent = mutableListOf<String>()
        override val state: StateFlow<BoardConnectionUiState> get() = link

        override fun sendPreview(
            holds: List<BoardHold>,
            placementToLed: Map<Int, Int>,
            roleColors: Map<Int, Int>,
            expectedBrand: BoardBrand,
        ) {
            sent += holds.associate { it.placementId to it.roleId }
            link.update { it.copy(sending = true) }
        }

        override fun sendMoonBoardPreview(frames: String, layoutId: Long, ledModeWire: String?) {
            moonSent += frames
            link.update { it.copy(sending = true) }
        }

        fun connect(brand: BoardBrand) = link.update {
            it.copy(connection = ConnectionState.CONNECTED, connectedBoard = DiscoveredBoard("Board", "S1", 3, "id-1", -40, brand))
        }

        fun finishSend() = link.update { it.copy(sending = false) }

        fun disconnect() = link.update { it.copy(connection = ConnectionState.DISCONNECTED, connectedBoard = null, sending = false) }
    }

    private fun editorState(brand: BoardBrand, holds: Map<Int, Int>) = ClimbEditorUiState(
        isLoading = false,
        layoutId = 1L,
        productSizeId = 10,
        editor = ClimbEditorState(selectedHolds = holds, boardBrand = brand.wireValue),
    )

    private fun TestScope.mirror(editor: StateFlow<ClimbEditorUiState>, link: FakeLink, mapsRead: MutableList<String> = mutableListOf()) =
        CreatorBoardMirror(
            editor = editor,
            link = link,
            ledMaps = { size, brand ->
                mapsRead += "$brand/$size"
                EditorLedMaps((1..20).associateWith { it + 100 }, mapOf(HoldRole.START to 0x1C))
            },
            moonLedMode = { "BELOW" },
            scope = backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
        )

    @Test
    fun `the holds go to the board once it connects and after every change`() = runTest(UnconfinedTestDispatcher()) {
        val editor = MutableStateFlow(editorState(BoardBrand.KILTER, mapOf(1 to HoldRole.START)))
        val link = FakeLink()
        val reads = mutableListOf<String>()
        val mirror = mirror(editor, link, reads)
        mirror.start()
        runCurrent()
        assertEquals(emptyList(), link.sent, "nothing is written without a board")

        link.connect(BoardBrand.KILTER)
        runCurrent()
        assertEquals(listOf(mapOf(1 to HoldRole.START)), link.sent, "the connect pushes the climb so far")
        link.finishSend()
        runCurrent()
        assertEquals(1, link.sent.size, "the same holds are not written twice")

        editor.update { it.copy(editor = it.editor.copy(selectedHolds = mapOf(1 to HoldRole.START, 2 to HoldRole.HAND))) }
        runCurrent()
        assertEquals(mapOf(1 to HoldRole.START, 2 to HoldRole.HAND), link.sent.last())

        // A name is not on the wall: no write for it.
        link.finishSend()
        editor.update { it.copy(editor = it.editor.copy(name = "Neu")) }
        runCurrent()
        assertEquals(2, link.sent.size)
        assertEquals(listOf("kilter/10"), reads, "the LED map is read once per board")
        mirror.stop()
    }

    @Test
    fun `edits made during a write are sent once it finished and the latest wins`() = runTest(UnconfinedTestDispatcher()) {
        val editor = MutableStateFlow(editorState(BoardBrand.KILTER, mapOf(1 to HoldRole.START)))
        val link = FakeLink()
        val mirror = mirror(editor, link)
        mirror.start()
        link.connect(BoardBrand.KILTER)
        runCurrent()
        assertEquals(1, link.sent.size)

        // Two edits while the first write is still going out.
        editor.update { it.copy(editor = it.editor.copy(selectedHolds = mapOf(1 to HoldRole.START, 3 to HoldRole.HAND))) }
        editor.update { it.copy(editor = it.editor.copy(selectedHolds = mapOf(1 to HoldRole.START, 4 to HoldRole.FINISH))) }
        runCurrent()
        assertEquals(1, link.sent.size, "the presenter would drop a write while one is running")

        link.finishSend()
        runCurrent()
        assertEquals(2, link.sent.size)
        assertEquals(mapOf(1 to HoldRole.START, 4 to HoldRole.FINISH), link.sent.last())

        // Removing every hold clears the wall, as on Android.
        link.finishSend()
        editor.update { it.copy(editor = it.editor.copy(selectedHolds = emptyMap())) }
        runCurrent()
        assertEquals(emptyMap(), link.sent.last())
        mirror.stop()
    }

    @Test
    fun `a new link gets the climb again and a stopped mirror writes nothing`() = runTest(UnconfinedTestDispatcher()) {
        val editor = MutableStateFlow(editorState(BoardBrand.KILTER, mapOf(1 to HoldRole.START)))
        val link = FakeLink()
        val mirror = mirror(editor, link)
        mirror.start()
        link.connect(BoardBrand.KILTER)
        runCurrent()
        link.disconnect()
        runCurrent()
        link.connect(BoardBrand.KILTER)
        runCurrent()
        assertEquals(2, link.sent.size, "the reconnected board shows the draft again")

        link.finishSend()
        mirror.stop()
        editor.update { it.copy(editor = it.editor.copy(selectedHolds = mapOf(9 to HoldRole.FOOT))) }
        link.disconnect()
        link.connect(BoardBrand.KILTER)
        runCurrent()
        assertEquals(2, link.sent.size, "the closed editor no longer drives the board")
    }

    @Test
    fun `quantum is never mirrored and another family is refused with the mismatch`() = runTest(UnconfinedTestDispatcher()) {
        val quantumEditor = MutableStateFlow(editorState(BoardBrand.QUANTUM, mapOf(1 to HoldRole.START)))
        val quantumLink = FakeLink()
        val quantum = mirror(quantumEditor, quantumLink)
        quantum.start()
        quantumLink.connect(BoardBrand.QUANTUM)
        runCurrent()
        assertEquals(emptyList(), quantumLink.sent, "a Quantum layer needs a rack slot the editor has not got")
        assertNull(quantum.mismatch.value)
        quantum.stop()

        val kilterEditor = MutableStateFlow(editorState(BoardBrand.KILTER, mapOf(1 to HoldRole.START)))
        val tensionLink = FakeLink()
        val kilter = mirror(kilterEditor, tensionLink)
        kilter.start()
        tensionLink.connect(BoardBrand.TENSION)
        runCurrent()
        assertEquals(emptyList(), tensionLink.sent)
        val mismatch = assertNotNull(kilter.mismatch.value)
        assertEquals(BoardBrand.TENSION, mismatch.connectedBrand)

        tensionLink.disconnect()
        runCurrent()
        assertNull(kilter.mismatch.value, "no board, no mismatch")
        kilter.stop()
    }

    @Test
    fun `a moonboard problem goes out as its frames`() = runTest(UnconfinedTestDispatcher()) {
        val editor = MutableStateFlow(editorState(BoardBrand.MOONBOARD, mapOf(12 to HoldRole.ROUTE_START, 24 to HoldRole.ROUTE_FINISH)))
        val link = FakeLink()
        val reads = mutableListOf<String>()
        val mirror = mirror(editor, link, reads)
        mirror.start()
        link.connect(BoardBrand.MOONBOARD)
        runCurrent()
        assertEquals(1, link.moonSent.size)
        assertTrue(link.moonSent.single().contains("p12r42"))
        assertTrue(link.moonSent.single().contains("p24r44"))
        assertEquals(emptyList(), reads, "a MoonBoard has no LED map to read")
        mirror.stop()
    }
}
