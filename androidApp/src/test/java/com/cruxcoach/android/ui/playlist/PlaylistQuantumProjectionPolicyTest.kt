package com.cruxcoach.android.ui.playlist

import com.cruxcoach.android.ble.BoardClimbLayer
import com.cruxcoach.android.ble.BoardLayerManager
import com.cruxcoach.android.ble.BoardLayerState
import com.cruxcoach.android.ble.BoardLayerStatus
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistQuantumProjectionPolicyTest {
    private val layer = BoardClimbLayer(
        slot = 0,
        climbUuid = "climb",
        routeUuid = "route",
        climbName = "Climb",
        angle = 40,
        userUuid = "user",
        color = BoardLayerManager.LAYER_COLORS[0],
        holds = listOf(BoardHold(10, 1)),
        status = BoardLayerStatus.PREVIEW,
    )

    @Test fun `running Quantum playlist renders the persistent local rack`() {
        assertEquals(
            listOf(layer),
            playlistProjectionLayers(
                BoardBrand.QUANTUM,
                BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(layer)),
            ),
        )
    }

    @Test fun `every non Quantum playlist keeps its legacy single climb render`() {
        BoardBrand.entries.filterNot { it == BoardBrand.QUANTUM }.forEach { brand ->
            assertTrue(
                brand.wireValue,
                playlistProjectionLayers(
                    brand,
                    BoardLayerState(brand = BoardBrand.QUANTUM, layers = listOf(layer)),
                ).isEmpty(),
            )
        }
    }
}
