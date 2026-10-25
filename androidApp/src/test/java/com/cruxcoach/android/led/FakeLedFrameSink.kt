package com.cruxcoach.android.led

import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.ble.LitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [LedFrameSink]: records what the engine sent and lets a test decide
 * what the link answers.
 */
internal class FakeLedFrameSink(
    frame: LitFrame? = null,
    canAnimate: Boolean = true,
) : LedFrameSink {

    override val litFrame = MutableStateFlow(frame)

    /** What the board means for an effect; [canAnimate] reads and writes it. */
    val availabilityState = MutableStateFlow(
        if (canAnimate) LedBoardAvailability.READY else LedBoardAvailability.UNSUPPORTED,
    )

    override val availability: Flow<LedBoardAvailability> get() = availabilityState

    override var canAnimate: Boolean
        get() = availabilityState.value == LedBoardAvailability.READY
        set(value) {
            availabilityState.value = if (value) LedBoardAvailability.READY else LedBoardAvailability.UNSUPPORTED
        }

    /** Virtual milliseconds each accepted write takes. */
    var writeMillis = 0L

    /** Frames the sink accepted, in order. */
    val sent = mutableListOf<List<Pair<Int, Int>>>()

    /** Every call made, including the ones it turned down. */
    val attempts = mutableListOf<List<Pair<Int, Int>>>()

    /**
     * What [sendFrame] reports. Set it before the engine is wired up to decide what
     * the very first beat meets; flip it mid-test to watch the loop react.
     */
    var result = FrameSendResult.SENT

    /** Frames the engine tried to send since the last [reset]. */
    fun reset() {
        sent.clear()
        attempts.clear()
        bases.clear()
    }

    /** The lit frame each attempt was made from, in order. */
    val bases = mutableListOf<LitFrame>()

    override suspend fun sendFrame(basedOn: LitFrame, frame: List<Pair<Int, Int>>): FrameSendResult {
        bases += basedOn
        attempts += frame
        if (result != FrameSendResult.SENT) return result
        if (writeMillis > 0) delay(writeMillis)
        sent += frame
        return FrameSendResult.SENT
    }
}
