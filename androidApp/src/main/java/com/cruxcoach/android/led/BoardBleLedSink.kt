package com.cruxcoach.android.led

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.ble.LitFrame
import com.cruxcoach.domain.board.BoardBrand
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * [LedFrameSink] on the real link.
 *
 * Kilter-only, exactly like the Easter animations: the LED positions of a frame
 * come from the climb that was sent, so the geometry argument that kept other
 * Aurora boards out does not apply here — but a MoonBoard cannot parse Aurora
 * packets at all, and the non-Kilter boards have not been exercised with
 * animation-rate traffic, so both stay on static colours until device testing
 * says otherwise.
 *
 * Frames go out through [BoardBleConnection.sendAnimationLeds], which encodes with
 * the *connected* board's apiLevel (so an `@2` board keeps its 18 W power scaling),
 * writes under the same mutex as user-initiated sends, and gives way to them
 * instead of queueing behind them — an animation frame can neither interleave
 * half-written packets with a climb send nor overwrite a climb the user just sent.
 */
class BoardBleLedSink @Inject constructor(
    private val bleConnection: BoardBleConnection,
) : LedFrameSink {

    override val litFrame: StateFlow<LitFrame?> get() = bleConnection.litFrame

    /**
     * Capability, not availability. [ConnectionState.SENDING] means a write is in
     * flight, not that the board has gone, and animation frames give way to it
     * inside [BoardBleConnection.sendAnimationLeds]; reading this as false whenever
     * the link is busy would end the loop on the first editor sync.
     */
    override val canAnimate: Boolean
        get() = bleConnection.connectionState.value.let {
            it == ConnectionState.CONNECTED || it == ConnectionState.SENDING
        } && bleConnection.connectedBoardBrand.value == BoardBrand.KILTER

    override suspend fun sendFrame(frame: List<Pair<Int, Int>>): FrameSendResult =
        bleConnection.sendAnimationLeds(frame)
}
