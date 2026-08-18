package com.cruxcoach.android.data

import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.ui.board.BoardSendModePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two send-mode defaults are a safety statement, not a preference:
 * a board only you can hold may light up as you browse, a board others are
 * on may not.
 */
class BoardSendModeDefaultsTest {

    private val singleDefault = BoardSendMode.AUTOMATIC
    private val multiDefault = BoardSendMode.EXPLICIT

    @Test
    fun `an exclusive board sends as you browse`() {
        assertEquals(
            BoardSendMode.AUTOMATIC,
            BoardSendModePolicy.resolve(
                connectionCapacity = BoardConnectionCapacity.SINGLE,
                singleConnectionMode = singleDefault,
                multiConnectionMode = multiDefault,
            ),
        )
    }

    @Test
    fun `a shared board waits for the tap`() {
        assertEquals(
            BoardSendMode.EXPLICIT,
            BoardSendModePolicy.resolve(
                connectionCapacity = BoardConnectionCapacity.MULTIPLE,
                singleConnectionMode = singleDefault,
                multiConnectionMode = multiDefault,
            ),
        )
    }

    /** A CruxRelay guest is on a multi-connection endpoint by definition, so
     *  the same default protects the host's wall. */
    @Test
    fun `differing defaults never auto-send before the capacity is settled`() {
        assertEquals(
            BoardSendMode.EXPLICIT,
            BoardSendModePolicy.resolve(
                connectionCapacity = BoardConnectionCapacity.UNKNOWN,
                singleConnectionMode = singleDefault,
                multiConnectionMode = multiDefault,
            ),
        )
    }
}
