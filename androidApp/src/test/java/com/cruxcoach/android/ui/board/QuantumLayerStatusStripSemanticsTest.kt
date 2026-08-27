package com.cruxcoach.android.ui.board

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import com.cruxcoach.android.ble.QuantumControllerSyncStatus
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
    @get:Rule
    val compose = createComposeRule()

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
                    quantumSyncStatus = QuantumControllerSyncStatus.LIVE,
                ),
            )
        }

        compose.onNodeWithText("1 other · 1 unknown").assertExists()
        compose.onNodeWithText("Your Quantum layer plans").assertExists()
        compose.onNodeWithText("Wall 1/4 active").assertExists()
        compose.onNodeWithText("Other apps:").assertExists()
        compose.onNodeWithText("foreign- ?", useUnmergedTree = true).assertExists()
    }

    @Test fun `rack distinguishes initial sync live truth and retained stale truth`() {
        compose.setContent {
            Column {
                QuantumLayerStatusStrip(
                    state = BoardLayerState(
                        brand = BoardBrand.QUANTUM,
                        quantumSyncStatus = QuantumControllerSyncStatus.SYNCING,
                    ),
                    onOpen = {},
                    testTag = "syncing-rack",
                )
                QuantumLayerStatusStrip(
                    state = BoardLayerState(
                        brand = BoardBrand.QUANTUM,
                        externalLayers = listOf(foreign("route", emptyList())),
                        quantumSyncStatus = QuantumControllerSyncStatus.STALE,
                    ),
                    onOpen = {},
                    testTag = "stale-rack",
                )
            }
        }

        compose.onNodeWithText("Reading board state…").assertExists()
        compose.onNodeWithText("Last known: 1/4 active").assertExists()
        compose.onNodeWithTag("syncing-rack")
            .assert(contentDescriptionContains("Reading board state"))
        compose.onNodeWithTag("stale-rack")
            .assert(contentDescriptionContains("Last known"))
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

    @Test
    fun `merged rack label names every local visual state`() {
        val states = listOf(
            "free" to BoardLayerState(brand = BoardBrand.QUANTUM),
            "planned" to stateWith(layer(BoardLayerStatus.PREVIEW)),
            "transmitting" to stateWith(layer(BoardLayerStatus.SENDING)),
            "not confirmed" to stateWith(layer(BoardLayerStatus.FAILED)),
            "replacement planned" to stateWith(
                layer(BoardLayerStatus.PREVIEW).copy(
                    confirmedRouteUuid = "previous-route",
                    confirmedColor = BoardLayerManager.LAYER_COLORS[1],
                    confirmedClimbName = "Previous climb",
                    confirmedHolds = listOf(BoardHold(11, 1)),
                ),
            ),
            "route details unknown" to stateWith(
                layer(BoardLayerStatus.CONFIRMED).copy(
                    confirmedRouteUuid = "route-0",
                    confirmedColor = BoardLayerManager.LAYER_COLORS[0],
                    controllerDetailsKnown = false,
                ),
            ),
            "confirmed on the board" to stateWith(
                layer(BoardLayerStatus.CONFIRMED).copy(
                    confirmedRouteUuid = "route-0",
                    confirmedColor = BoardLayerManager.LAYER_COLORS[0],
                    confirmedClimbName = "Climb 0",
                    confirmedHolds = listOf(BoardHold(10, 1)),
                ),
            ),
        )
        compose.setContent {
            Column {
                states.forEachIndexed { index, (_, state) ->
                    QuantumLayerStatusStrip(
                        state = state,
                        onOpen = {},
                        testTag = "rack-$index",
                    )
                }
            }
        }

        states.forEachIndexed { index, (expected, _) ->
            compose.onNodeWithTag("rack-$index").assert(contentDescriptionContains(expected))
        }
    }

    @Test
    fun `foreign labels say known or unknown and remain read only`() {
        val known = foreign("known-route", listOf(BoardHold(20, 1)))
        val unknown = foreign("unknown-route", null)
        compose.setContent {
            QuantumLayerStatusStrip(
                state = BoardLayerState(
                    brand = BoardBrand.QUANTUM,
                    externalLayers = listOf(known, unknown),
                ),
                onOpen = {},
                testTag = "foreign-rack",
            )
        }

        compose.onNodeWithTag("foreign-rack")
            .assert(contentDescriptionContains("holds known, read-only"))
            .assert(contentDescriptionContains("holds unknown, read-only; new layers are blocked"))
    }

    private fun contentDescriptionContains(expected: String) = SemanticsMatcher(
        "content description contains '$expected'",
    ) { node ->
        node.config[SemanticsProperties.ContentDescription]
            .any { it.contains(expected, ignoreCase = true) }
    }

    private fun stateWith(layer: BoardClimbLayer) = BoardLayerState(
        brand = BoardBrand.QUANTUM,
        layers = listOf(layer),
    )

    private fun layer(status: BoardLayerStatus) = BoardClimbLayer(
        slot = 0,
        climbUuid = "climb-0",
        routeUuid = "route-0",
        climbName = "Climb 0",
        angle = 40,
        userUuid = "user-0",
        color = BoardLayerManager.LAYER_COLORS[0],
        holds = listOf(BoardHold(10, 1)),
        status = status,
    )

    private fun foreign(route: String, holds: List<BoardHold>?) = ExternalBoardLayer(
        routeUuid = route,
        userUuid = "foreign-$route",
        color = 0xff123456.toInt(),
        remainingSeconds = 20,
        climbName = route,
        holds = holds,
    )
}
