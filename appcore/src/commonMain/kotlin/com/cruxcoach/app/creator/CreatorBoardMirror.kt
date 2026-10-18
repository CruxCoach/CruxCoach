package com.cruxcoach.app.creator

import com.cruxcoach.app.ble.BoardConnectionPresenter
import com.cruxcoach.app.ble.BoardConnectionUiState
import com.cruxcoach.app.ble.ConnectionState
import com.cruxcoach.app.setup.BoardConfigurationMismatch
import com.cruxcoach.app.setup.BoardMismatchPolicy
import com.cruxcoach.app.setup.BoardSendIdentity
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.community.encodeFrames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The board link as the editor's mirror needs it; [BoardConnectionPresenter] in the app. */
internal interface EditorPreviewLink {
    val state: StateFlow<BoardConnectionUiState>
    fun sendPreview(holds: List<BoardHold>, placementToLed: Map<Int, Int>, roleColors: Map<Int, Int>, expectedBrand: BoardBrand)
    fun sendMoonBoardPreview(frames: String, layoutId: Long, ledModeWire: String?)
}

internal object EditorPreviewLinks {
    fun of(presenter: BoardConnectionPresenter): EditorPreviewLink = object : EditorPreviewLink {
        override val state: StateFlow<BoardConnectionUiState> get() = presenter.state
        override fun sendPreview(
            holds: List<BoardHold>,
            placementToLed: Map<Int, Int>,
            roleColors: Map<Int, Int>,
            expectedBrand: BoardBrand,
        ) = presenter.sendEditorPreview(holds, placementToLed, roleColors, expectedBrand)
        override fun sendMoonBoardPreview(frames: String, layoutId: Long, ledModeWire: String?) =
            presenter.sendEditorPreviewMoonBoard(frames, layoutId, ledModeWire)
    }
}

/** The LED map and the role colours a preview of [brandWire] on size [productSizeId] is lit with. */
internal class EditorLedMaps(val placementToLed: Map<Int, Int>, val roleColors: Map<Int, Int>)

/**
 * Android's live LED mirror of the climb editor (`ClimbEditorViewModel.syncLeds` plus the
 * screen's `LaunchedEffect(bleConnected)`): while a board is connected, the holds being
 * drafted are on the wall — pushed once when the board connects and again after every
 * change to the holds. It runs only between [start] and [stop]; the editor screen stops
 * it when it closes.
 *
 * The write is the detail's fenced send. One send at a time, and the latest holds win:
 * a change made while a write is still going out is sent as soon as it finished.
 * Quantum is never mirrored (its projections are controller-owned layers), and a board
 * of another family is not written to; [mismatch] says why, as Android's dialog does.
 */
class CreatorBoardMirror internal constructor(
    private val editor: StateFlow<ClimbEditorUiState>,
    private val link: EditorPreviewLink,
    private val ledMaps: suspend (productSizeId: Int, brandWire: String) -> EditorLedMaps,
    private val moonLedMode: () -> String?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val _mismatch = MutableStateFlow<BoardConfigurationMismatch?>(null)

    /** The connected board does not belong to the board the climb is set on; null otherwise. */
    internal val mismatch: StateFlow<BoardConfigurationMismatch?> = _mismatch.asStateFlow()

    private var jobs: List<Job> = emptyList()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The holds the board shows from this editor; null = nothing sent on this link yet. */
    private var lastSent: Map<Int, Int>? = null
    private var mapsKey: String = ""
    private var maps: EditorLedMaps? = null

    fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(
            scope.launch { editor.collect { wake.trySend(Unit) } },
            scope.launch { link.state.collect { wake.trySend(Unit) } },
            scope.launch { for (signal in wake) pump() },
        )
    }

    /** The editor closed: nothing more goes to the board from it. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    private suspend fun pump() {
        val connection = link.state.value
        val up = connection.connection == ConnectionState.CONNECTED || connection.connection == ConnectionState.SENDING
        if (!up) {
            // A new link starts with whatever its board shows: push again once it is back.
            lastSent = null
            _mismatch.value = null
            return
        }
        val state = editor.value
        if (state.isLoading) return
        val brand = BoardBrand.fromWire(state.editor.boardBrand)
        val mismatch = BoardMismatchPolicy.resolve(
            BoardSendIdentity(
                climbBrand = brand,
                climbLayoutId = state.layoutId,
                activeBrand = brand,
                activeLayoutId = state.layoutId,
                activeProductSizeId = null,
                connectedBrand = connection.connectedBoard?.boardBrand,
            ),
        )
        _mismatch.value = mismatch
        if (mismatch != null) return
        // Quantum projections need a rack slot and a route identity the editor has not got.
        if (brand == BoardBrand.QUANTUM) return
        if (connection.connection != ConnectionState.CONNECTED || connection.sending) return
        val holds = state.editor.selectedHolds
        if (holds == lastSent) return
        if (brand == BoardBrand.MOONBOARD) {
            // An unknown variant has no known LED serpentine: Android sends nothing then.
            if (com.cruxcoach.domain.board.MoonBoardVariant.fromLayoutId(state.layoutId) == null) return
            lastSent = holds
            link.sendMoonBoardPreview(state.editor.encodeFrames(), state.layoutId, moonLedMode())
            return
        }
        val resolved = mapsFor(state.productSizeId, brand.wireValue) ?: return
        if (resolved.placementToLed.isEmpty()) return
        // The maps were read off the main thread: the link may have changed meanwhile.
        val now = link.state.value
        if (now.connection != ConnectionState.CONNECTED || now.sending) return
        val latest = editor.value.editor.selectedHolds
        lastSent = latest
        link.sendPreview(latest.map { (pid, role) -> BoardHold(pid, role) }, resolved.placementToLed, resolved.roleColors, brand)
    }

    private suspend fun mapsFor(productSizeId: Int, brandWire: String): EditorLedMaps? {
        val key = "$brandWire/$productSizeId"
        if (key == mapsKey) return maps
        val loaded = try {
            withContext(io) { ledMaps(productSizeId, brandWire) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No LED map, no preview; the editor itself is unaffected.
            return null
        }
        mapsKey = key
        maps = loaded
        return loaded
    }
}
