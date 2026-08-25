package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
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

    @Test fun `replacement chip distinguishes planned and still live colors`() {
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

        compose.onNodeWithContentDescription("Planned colour: Cyan", substring = true)
            .assertHasClickAction()
        compose.onNodeWithTag("quantum_layer_status_1", useUnmergedTree = true)
            .assertContentDescriptionEquals(
                "Layer 1: replacement planned; previous climb is still on the board, " +
                    "Planned colour: Cyan, New climb. Still on the board: Old climb · Green",
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
        compose.onNodeWithText("Your Quantum layer plans").assertExists()
        compose.onNodeWithText("Wall 1/4 active").assertExists()
        compose.onNodeWithText("Other apps:").assertExists()
        compose.onNodeWithText("O1?", useUnmergedTree = true).assertExists()
    }


    @Test
    @Config(qualifiers = "w320dp")
    fun `narrow rack keeps unknown state as an unclipped visible cue`() {
        val unknown = BoardClimbLayer(
            slot = 0,
            climbUuid = "unknown-route",
            routeUuid = "unknown-route",
            climbName = "Unknown route",
            angle = 0,
            userUuid = "owned-user",
            color = BoardLayerManager.LAYER_COLORS[0],
            holds = emptyList(),
            status = BoardLayerStatus.CONFIRMED,
            confirmedRouteUuid = "unknown-route",
            confirmedColor = BoardLayerManager.LAYER_COLORS[0],
            controllerDetailsKnown = false,
        )
        compose.setContent {
            QuantumLayerStatusStrip(
                state = BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(unknown)),
            )
        }

        compose.onNodeWithTag("quantum_layer_visible_state_1", useUnmergedTree = true)
            .assertTextEquals("?")
    }
}
