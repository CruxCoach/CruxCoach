package com.cruxcoach.android.led

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.domain.board.BoardBrand
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * What [BoardBleLedSink] promises the engine about the real link: which boards may
 * animate at all, and that the answer to "can I write now?" is kept apart from the
 * answer to "did the board take that frame?" — conflating them is how an animation
 * ends on a link that is merely busy.
 */
class BoardBleLedSinkTest {

    private val connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    private val brand = MutableStateFlow<BoardBrand?>(BoardBrand.KILTER)
    private val ble = mockk<BoardBleConnection>(relaxed = true)
    private val sink = BoardBleLedSink(ble)

    init {
        every { ble.connectionState } returns connectionState
        every { ble.connectedBoardBrand } returns brand
    }

    @Test
    fun aKilterBoardCanAnimateInEitherLiveState() {
        brand.value = BoardBrand.KILTER
        for (state in listOf(ConnectionState.CONNECTED, ConnectionState.SENDING)) {
            connectionState.value = state
            assertTrue(sink.canAnimate, "$state is a board the animation can still use")
        }
        for (state in listOf(ConnectionState.CONNECTING, ConnectionState.DISCONNECTED)) {
            connectionState.value = state
            assertFalse(sink.canAnimate, "$state is not a board to push frames to")
        }
    }

    @Test
    fun aBoardWithoutPerLedColourIsLeftStatic() {
        connectionState.value = ConnectionState.CONNECTED
        for (other in listOf(BoardBrand.MOONBOARD, BoardBrand.TENSION)) {
            brand.value = other
            assertFalse(sink.canAnimate, "$other stays on static colours until device testing says otherwise")
        }
        brand.value = null
        assertFalse(sink.canAnimate, "nothing is paired yet")
    }

    @Test
    fun aFrameIsHandedToTheLinkAsIsAndItsAnswerAsGiven() = runTest {
        val frame = listOf(11 to RED, 22 to GREEN)
        coEvery { ble.sendAnimationLeds(any()) } returns FrameSendResult.DEFERRED
        assertEquals(
            FrameSendResult.DEFERRED,
            sink.sendFrame(frame),
            "busy or broken is the link's call; the sink must not round it to success or error",
        )
        coVerify { ble.sendAnimationLeds(frame) }
    }
}
