package com.cruxcoach.app.send

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.ble.resolveRoleColors
import com.cruxcoach.app.playlist.PlaybackTransport
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.settings.UserLedColors
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts the playlist's current climb on the wall.
 *
 * The player only knows a climb uuid and an angle, so the frames, LED map and
 * role colours are resolved here, off the main thread, from the active board
 * selection — the same inputs the detail screen's explicit send uses.
 */
class BoardPlaybackTransport(
    private val boardRepository: BoardRepository,
    private val connection: BoardConnectionPresenter,
    private val keyValues: KeyValueStore,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    /** The "Send to board" mode for the connected controller is automatic (Android `resolveSendMode`). */
    private val automaticMode: () -> Boolean = { true },
) : PlaybackTransport {

    private val scope = CoroutineScope(SupervisorJob() + main)

    /** Quantum: a playlist climb is lit as a layer of the shared rack, never as a plain send. */
    private val quantumPlaylist = com.cruxcoach.app.quantum.QuantumPlaylistProjection(
        connection.quantumLayers, connection.quantumLink, boardRepository, io,
    )

    override val isBoardConnected: Boolean
        get() = connection.state.value.connection.let {
            it == ConnectionState.CONNECTED || it == ConnectionState.SENDING
        }

    override val sendsAutomatically: Boolean
        get() = try {
            automaticMode()
        } catch (e: Exception) {
            // Android: a mode that cannot be read is no reason to leave the wall dark.
            true
        }

    override fun sendClimb(climbUuid: String, angle: Int) {
        val brandWire = keyValues.getString(BOARD_BRAND) ?: BoardBrand.KILTER.wireValue
        val brand = BoardBrand.fromWireOrNull(brandWire) ?: return
        val sizeId = keyValues.getString(BOARD_PRODUCT_SIZE_ID)?.toIntOrNull() ?: 0
        scope.launch {
            val prepared = try {
                withContext(io) {
                    val climb = boardRepository.getClimbByUuid(climbUuid, angle)
                        ?: boardRepository.getClimbByUuidNormalized(climbUuid, angle)
                        ?: return@withContext null
                    Prepared(
                        name = climb.name,
                        frames = climb.frames,
                        layoutId = climb.layoutId,
                        ledMap = if (brand == BoardBrand.MOONBOARD) emptyMap() else boardRepository.getPlacementLedMap(sizeId, brandWire),
                        roleColors = if (brand == BoardBrand.MOONBOARD) emptyMap() else boardRepository.getRoleColorMapForBrand(brandWire),
                    )
                }
            } catch (e: Exception) {
                null
            } ?: return@launch

            if (brand == BoardBrand.QUANTUM) {
                val holds = BoardClimbParser.parseFrames(prepared.frames)
                if (holds.isEmpty()) return@launch
                try {
                    quantumPlaylist.send(climbUuid, prepared.name, prepared.layoutId, angle, holds)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Unlit, as on Android: the playlist goes on and the detail rack can retry.
                }
            } else if (brand == BoardBrand.MOONBOARD) {
                connection.sendMoonBoardClimb(prepared.frames, prepared.layoutId, keyValues.getString(MOONBOARD_LED_MODE),
                    com.cruxcoach.app.ble.SentClimb(climbUuid, angle, prepared.name))
            } else {
                val holds = BoardClimbParser.parseFrames(prepared.frames)
                if (holds.isEmpty() || prepared.ledMap.isEmpty()) return@launch
                val colors = resolveRoleColors(brand, prepared.roleColors, UserLedColors.read(keyValues))
                connection.sendClimb(holds, prepared.ledMap, colors, brandWire,
                    com.cruxcoach.app.ble.SentClimb(climbUuid, angle, prepared.name))
            }
        }
    }

    private class Prepared(
        val name: String,
        val frames: String,
        val layoutId: Long,
        val ledMap: Map<Int, Int>,
        val roleColors: Map<Int, Int>,
    )

    companion object {
        const val BOARD_BRAND = "board_brand"
        const val BOARD_PRODUCT_SIZE_ID = "board_product_size_id"
        const val MOONBOARD_LED_MODE = "moonboard_led_mode"
    }
}
