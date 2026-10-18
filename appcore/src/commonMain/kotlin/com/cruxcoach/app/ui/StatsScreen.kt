package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.HeatmapModes
import com.cruxcoach.app.browse.StatsHeatmapPresenter
import com.cruxcoach.app.browse.StatsHeatmapState
import com.cruxcoach.app.browse.StatsHeatmapStatus
import com.cruxcoach.app.render.AuroraBoardGeometry
import com.cruxcoach.app.render.BoardHeatmap
import com.cruxcoach.app.render.BoardMarkerMultipliers
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** An entry of the heatmap's board selector; the name is a product name, not translated. */
class HeatmapBoardUi(val key: String, val name: String)

/** One hot hold, normalized over the board image (origin top-left), already coloured like Android. */
class HeatHoldUi(val placementId: Int, val x: Float, val y: Float, val argb: Long, val haloAlpha: Float, val coreAlpha: Float)

class StatsHeatmapScreenState(
    /** loading | failed | noBoards | pickBoard | unsupported | loadingBoard | ready */
    val status: String,
    val boards: List<HeatmapBoardUi>,
    /** "" = Android's "All" (no single grid). */
    val selectedKey: String,
    /** global | personal | start | hand | foot | finish */
    val modeCode: String,
    /** Android's chip order. */
    val modeCodes: List<String>,
    val holds: List<HeatHoldUi>,
    /** The heat is being computed for the shown board and mode. */
    val computing: Boolean,
    /** Computed and no hold reached the drawing threshold (Android's "no data" hint). */
    val isEmpty: Boolean,
    val imagePaths: List<String>,
    val aspect: Float,
    /** Halo and core radius as fractions of the canvas width (Android: 4.5 and 1.8 × the marker scale). */
    val haloRadius: Float,
    val coreRadius: Float,
)

/**
 * The hold heatmap section of Android's board statistics sheet: a board
 * selector ("All" plus each board the user logged on), the mode chips and the
 * heat drawn over that board's grid.
 */
class StatsHeatmapModel(
    private val presenter: StatsHeatmapPresenter,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    val currentState: StatsHeatmapScreenState get() = map(presenter.state.value)

    fun watch(onState: (StatsHeatmapScreenState) -> Unit): Subscription {
        val job = scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { job.cancel() }
    }

    /** Reads which boards were logged on; call again when the logbook changed. */
    fun load() = presenter.load()

    /** A key from [StatsHeatmapScreenState.boards], or "" for "All". */
    fun selectBoard(key: String) = presenter.selectBoard(key)

    fun setMode(code: String) = presenter.setMode(code)

    fun close() {
        scope.cancel()
        presenter.close()
    }

    private fun map(s: StatsHeatmapState): StatsHeatmapScreenState {
        val option = s.selection
        val size = s.boardSize
        val status = when {
            s.status == StatsHeatmapStatus.LOADING -> "loading"
            s.status == StatsHeatmapStatus.FAILED -> "failed"
            s.options.isEmpty() -> "noBoards"
            option == null -> "pickBoard"
            !BoardBrand.fromWire(option.brandWire).hasHeatmap -> "unsupported"
            s.placements.isEmpty() || size == null -> "loadingBoard"
            else -> "ready"
        }
        val geometry = AuroraBoardGeometry.of(size)
        val holds = if (status != "ready") emptyList() else s.placements.mapNotNull { (id, placement) ->
            val intensity = s.heat[id] ?: return@mapNotNull null
            if (intensity < BoardHeatmap.MIN_INTENSITY) return@mapNotNull null
            val p = geometry.normalized(placement.x, placement.y)
            if (p.x !in 0f..1f || p.y !in 0f..1f) return@mapNotNull null
            HeatHoldUi(id, p.x, p.y, BoardHeatmap.colorArgb(intensity), BoardHeatmap.haloAlpha(intensity), BoardHeatmap.coreAlpha(intensity))
        }
        val unit = if (geometry.boardWidth > 0f) 1f / geometry.boardWidth else 0f
        return StatsHeatmapScreenState(
            status = status,
            boards = s.options.map { HeatmapBoardUi(it.key, it.displayName) },
            selectedKey = option?.key ?: "",
            modeCode = s.mode,
            modeCodes = HeatmapModes.CODES,
            holds = holds,
            computing = s.computing,
            isEmpty = status == "ready" && !s.computing && holds.isEmpty(),
            imagePaths = if (option != null && size != null) {
                com.cruxcoach.app.render.boardImageCandidatePaths(BoardBrand.fromWire(option.brandWire), size.id, option.layoutId.toLong())
            } else emptyList(),
            aspect = if (geometry.boardHeight > 0f) geometry.aspectRatio else BrowserScreenModel.DEFAULT_BOARD_ASPECT,
            haloRadius = BoardMarkerMultipliers.HEAT_HALO_RADIUS * unit,
            coreRadius = BoardMarkerMultipliers.HEAT_CORE_RADIUS * unit,
        )
    }
}
