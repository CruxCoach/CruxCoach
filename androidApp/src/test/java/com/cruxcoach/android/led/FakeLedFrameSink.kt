package com.cruxcoach.android.led

import com.cruxcoach.android.ble.FrameSendResult
import com.cruxcoach.android.ble.LitFrame
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * An in-memory [LedFrameSink]: records what the engine sent and lets a test decide
 * what the link answers.
 */
internal class FakeLedFrameSink(
    frame: LitFrame? = null,
    override var canAnimate: Boolean = true,
) : LedFrameSink {

    override val litFrame = MutableStateFlow(frame)

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
        sent += frame
        return FrameSendResult.SENT
    }
}
