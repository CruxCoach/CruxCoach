package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.android.ble.ExternalBoardLayer
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class QuantumLayerStatusStripSemanticsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `replacement chip names physical color status and previous climb`() {
        val replacing = BoardClimbLayer(
            slot = 0,
            climbUuid = "new-climb",
            routeUuid = "new-route",
            climbName = "New climb",
            angle = 40,
            userUuid = "owned-user",
            color = BoardLayerManager.LAYER_COLORS[1],
            holds = listOf(BoardHold(20, 1)),
            status = BoardLayerStatus.PREVIEW,
            confirmedRouteUuid = "old-route",
            confirmedColor = BoardLayerManager.LAYER_COLORS[0],
            confirmedClimbName = "Old climb",
            confirmedHolds = listOf(BoardHold(10, 1)),
        )
        compose.setContent {
            QuantumLayerStatusStrip(
                state = BoardLayerState(
                    brand = BoardBrand.QUANTUM,
                    layers = listOf(replacing),
                ),
                currentClimbUuid = replacing.climbUuid,
                currentPlacements = setOf(20),
                onOpen = {},
            )
        }

        compose.onNodeWithContentDescription("Open board layers").assertExists()
        compose.onNodeWithTag("quantum_layer_status_1", useUnmergedTree = true)
            .assertContentDescriptionEquals(
                "Layer 1: replacement planned; previous climb is still on the board, " +
                    "Green, New climb. Still on the board: Old climb",
            )
        compose.onNodeWithTag("quantum_layer_visible_state_1", useUnmergedTree = true)
            .assertTextEquals("swap")
    }

    @Test fun `persistent header distinguishes unknown foreign geometry`() {
        compose.setContent {
            QuantumLayerStatusStrip(
                state = BoardLayerState(
                    brand = BoardBrand.QUANTUM,
                    externalLayers = listOf(
                        ExternalBoardLayer(
                            routeUuid = "foreign-route",
                            userUuid = "foreign-user",
                            color = BoardLayerManager.LAYER_COLORS[0],
                            remainingSeconds = 30,
                            holds = null,
                        ),
                    ),
                ),
            )
        }

        compose.onNodeWithText("1 other · 1 unknown").assertExists()
    }
}
