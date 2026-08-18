package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.BoardSendMode
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueDeliveryPolicyTest {

    @Test
    fun `nothing is sent without a board, whatever the mode says`() {
        // The order this pins down: the connection is settled before any
        // preference is read. A queue advance with no board used to reach the
        // send path and be dropped there silently, while the lamp stayed on
        // screen inviting a tap that went nowhere.
        for (mode in BoardSendMode.entries) {
            for (explicit in listOf(true, false)) {
                assertEquals(
                    "mode=$mode explicit=$explicit",
                    QueueDeliveryPolicy.Decision.NONE,
                    QueueDeliveryPolicy.decide(
                        isHost = true, boardConnected = false,
                        sendMode = mode, explicitRequest = explicit,
                    ),
                )
            }
        }
    }

    @Test
    fun `a participant never writes to the board`() {
        assertEquals(
            QueueDeliveryPolicy.Decision.NONE,
            QueueDeliveryPolicy.decide(
                isHost = false, boardConnected = true,
                sendMode = BoardSendMode.AUTOMATIC, explicitRequest = true,
            ),
        )
    }

    @Test
    fun `advancing sends on automatic and waits on explicit`() {
        assertEquals(
            QueueDeliveryPolicy.Decision.SEND,
            QueueDeliveryPolicy.decide(
                isHost = true, boardConnected = true,
                sendMode = BoardSendMode.AUTOMATIC, explicitRequest = false,
            ),
        )
        assertEquals(
            QueueDeliveryPolicy.Decision.AWAIT_EXPLICIT,
            QueueDeliveryPolicy.decide(
                isHost = true, boardConnected = true,
                sendMode = BoardSendMode.EXPLICIT, explicitRequest = false,
            ),
        )
    }

    @Test
    fun `the lamp sends even under the explicit mode`() {
        // Otherwise the one control offered under EXPLICIT would do nothing.
        assertEquals(
            QueueDeliveryPolicy.Decision.SEND,
            QueueDeliveryPolicy.decide(
                isHost = true, boardConnected = true,
                sendMode = BoardSendMode.EXPLICIT, explicitRequest = true,
            ),
        )
    }

    @Test
    fun `the surfaces that draw the lamp ask the same question as the send path`() {
        assertEquals(false, QueueDeliveryPolicy.canSend(isHost = true, boardConnected = false))
        assertEquals(false, QueueDeliveryPolicy.canSend(isHost = false, boardConnected = true))
        assertEquals(true, QueueDeliveryPolicy.canSend(isHost = true, boardConnected = true))
    }
}
