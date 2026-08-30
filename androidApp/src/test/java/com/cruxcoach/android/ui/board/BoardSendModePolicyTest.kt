package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ble.BoardConnectionCapacity
import com.cruxcoach.android.data.BoardSendMode
import com.cruxcoach.android.data.BoardSendModePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardSendModePolicyTest {
    @Test
    fun `single controller uses single preference`() {
        assertEquals(
            BoardSendMode.EXPLICIT,
            BoardSendModePolicy.resolve(
                BoardConnectionCapacity.SINGLE,
                singleConnectionMode = BoardSendMode.EXPLICIT,
                multiConnectionMode = BoardSendMode.AUTOMATIC,
            ),
        )
    }

    @Test
    fun `multi controller uses multi preference`() {
        assertEquals(
            BoardSendMode.EXPLICIT,
            BoardSendModePolicy.resolve(
                BoardConnectionCapacity.MULTIPLE,
                singleConnectionMode = BoardSendMode.AUTOMATIC,
                multiConnectionMode = BoardSendMode.EXPLICIT,
            ),
        )
    }

    @Test
    fun `unknown controller uses shared preference when modes match`() {
        assertEquals(
            BoardSendMode.AUTOMATIC,
            BoardSendModePolicy.resolve(
                BoardConnectionCapacity.UNKNOWN,
                singleConnectionMode = BoardSendMode.AUTOMATIC,
                multiConnectionMode = BoardSendMode.AUTOMATIC,
            ),
        )
    }

    @Test
    fun `unknown controller waits for probe when modes differ`() {
        assertEquals(
            BoardSendMode.EXPLICIT,
            BoardSendModePolicy.resolve(
                BoardConnectionCapacity.UNKNOWN,
                singleConnectionMode = BoardSendMode.AUTOMATIC,
                multiConnectionMode = BoardSendMode.EXPLICIT,
            ),
        )
    }

    @Test
    fun `automatic mode sends when capacity probe resolves`() {
        assertEquals(
            true,
            BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
                previousCapacity = BoardConnectionCapacity.SINGLE,
                currentCapacity = BoardConnectionCapacity.MULTIPLE,
                previousResolvedMode = BoardSendMode.EXPLICIT,
                resolvedMode = BoardSendMode.AUTOMATIC,
            ),
        )
    }

    @Test
    fun `preference change on known capacity does not itself send`() {
        assertEquals(
            false,
            BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
                previousCapacity = BoardConnectionCapacity.MULTIPLE,
                currentCapacity = BoardConnectionCapacity.MULTIPLE,
                previousResolvedMode = BoardSendMode.EXPLICIT,
                resolvedMode = BoardSendMode.AUTOMATIC,
            ),
        )
    }

    @Test
    fun `explicit mode does not send when capacity probe resolves`() {
        assertEquals(
            false,
            BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
                previousCapacity = BoardConnectionCapacity.SINGLE,
                currentCapacity = BoardConnectionCapacity.MULTIPLE,
                previousResolvedMode = BoardSendMode.EXPLICIT,
                resolvedMode = BoardSendMode.EXPLICIT,
            ),
        )
    }

    @Test
    fun `matching automatic modes do not resend after probe`() {
        assertEquals(
            false,
            BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
                previousCapacity = BoardConnectionCapacity.SINGLE,
                currentCapacity = BoardConnectionCapacity.MULTIPLE,
                previousResolvedMode = BoardSendMode.AUTOMATIC,
                resolvedMode = BoardSendMode.AUTOMATIC,
            ),
        )
    }

    @Test
    fun `a controller corrected down to single sends under the single preference`() {
        assertEquals(
            true,
            BoardSendModePolicy.shouldAutoSendAfterCapacityResolution(
                previousCapacity = BoardConnectionCapacity.MULTIPLE,
                currentCapacity = BoardConnectionCapacity.SINGLE,
                previousResolvedMode = BoardSendMode.EXPLICIT,
                resolvedMode = BoardSendMode.AUTOMATIC,
            ),
        )
    }

    @Test
    fun `hosting for others uses the multi preference, not the board capacity`() {
        // The relayed board is usually SINGLE — that is why it needs a relay —
        // but the situation is multi-user, so the multi preference applies.
        assertEquals(
            BoardSendMode.EXPLICIT,
            BoardSendModePolicy.resolve(
                connectionCapacity = BoardConnectionCapacity.SINGLE,
                singleConnectionMode = BoardSendMode.AUTOMATIC,
                multiConnectionMode = BoardSendMode.EXPLICIT,
                hostingForOthers = true,
            ),
        )
    }

    @Test
    fun `a host who chose automatic for shared boards keeps automatic`() {
        assertEquals(
            BoardSendMode.AUTOMATIC,
            BoardSendModePolicy.resolve(
                connectionCapacity = BoardConnectionCapacity.SINGLE,
                singleConnectionMode = BoardSendMode.EXPLICIT,
                multiConnectionMode = BoardSendMode.AUTOMATIC,
                hostingForOthers = true,
            ),
        )
    }
}
