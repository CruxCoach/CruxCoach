package com.cruxcoach.android.led

import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.ble.ConnectionState
import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.ble.LitFrame
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * [LedFrameSink] on the real link.
 *
 * Only boards in [LedEffectSupport] animate. Frames go out through
 * [BoardBleConnection.sendAnimationLeds], which encodes with the *connected*
 * board's apiLevel (so an `@2` board keeps its 18 W power scaling), writes under
 * the same mutex as user-initiated sends and gives way to them, refuses a frame
 * whose climb is no longer on the wall, and never touches the idle-disconnect
 * timer.
 *
 * Frames are handed over on the main thread, like every other send:
 * [BoardBleConnection] keeps its link state there, and a write timeout ends in
 * `disconnect()`.
 */
class BoardBleLedSink internal constructor(
    private val bleConnection: BoardBleConnection,
    private val sendContext: CoroutineContext,
) : LedFrameSink {

    @Inject
    constructor(bleConnection: BoardBleConnection) : this(bleConnection, Dispatchers.Main.immediate)

    override val litFrame: StateFlow<LitFrame?> get() = bleConnection.litFrame

    /**
     * Capability, not availability. [ConnectionState.SENDING] means a write is in
     * flight, not that the board has gone, and animation frames give way to it
     * inside [BoardBleConnection.sendAnimationLeds]; reading this as false whenever
     * the link is busy would end the loop on the first editor sync. Hold cleaning
     * does own the wall, so the effect waits until it is finished.
     */
    override val canAnimate: Boolean
        get() = bleConnection.connectionState.value.let {
            it == ConnectionState.CONNECTED || it == ConnectionState.SENDING
        } && LedEffectSupport.supports(bleConnection.connectedBoardBrand.value) &&
            !bleConnection.cleaningState.value.active

    override suspend fun sendFrame(basedOn: LitFrame, frame: List<Pair<Int, Int>>): FrameSendResult {
        // Read before waiting for the link: a board swapped in meanwhile is fenced
        // off inside sendAnimationLeds.
        val brand = bleConnection.connectedBoardBrand.value
        if (!LedEffectSupport.supports(brand)) return FrameSendResult.DEFERRED
        return withContext(sendContext) {
            bleConnection.sendAnimationLeds(basedOn, frame, expectedBrand = brand)
        }
    }
}
