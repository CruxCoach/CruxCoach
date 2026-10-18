package com.cruxcoach.app.browse

import com.cruxcoach.app.gym.GymBoardCatalog
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldHeatmapComputer
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One board the statistics' hold heatmap can draw on (Android `BoardConstants.HeatmapBoardOption`). */
data class HeatmapBoardOption(
    val brandWire: String,
    val layoutId: Int,
    val sizeId: Int,
    val displayName: String,
) {
    val key: String get() = "$brandWire:$layoutId:$sizeId"
}

/**
 * The board selector of Android's statistics heatmap (FEAT-039): only boards
 * the user has actually logged on, one entry per physically distinct board.
 */
object HeatmapBoards {
    const val KILTER_ORIGINAL_LAYOUT = 1
    const val KILTER_DEFAULT_SIZE = 10
    const val KILTER_HOMEWALL_LAYOUT = 8
    const val KILTER_HOMEWALL_DEFAULT_SIZE = 21

    /**
     * Android `heatmapLoggedBoardKey`: a row without a layout is only placed
     * on Kilter Original when it is a Kilter row (written before board context
     * existed); any other brand without a layout is an unknown board.
     */
    fun loggedBoardKey(boardBrandWire: String, layoutId: Long?): Pair<String, Int>? {
        val brand = BoardBrand.fromWire(boardBrandWire)
        val layout = layoutId?.toInt() ?: if (brand == BoardBrand.KILTER) KILTER_ORIGINAL_LAYOUT else return null
        return brand.wireValue to layout
    }

    /** Android `BoardConstants.heatmapBoardOptions`, in its order. */
    fun options(loggedBoards: Set<Pair<String, Int>>): List<HeatmapBoardOption> {
        val options = mutableListOf<HeatmapBoardOption>()
        val loggedBrands = loggedBoards.mapTo(mutableSetOf()) { it.first }
        val kilter = BoardBrand.KILTER.wireValue
        if ((kilter to KILTER_ORIGINAL_LAYOUT) in loggedBoards) {
            options += HeatmapBoardOption(kilter, KILTER_ORIGINAL_LAYOUT, KILTER_DEFAULT_SIZE, "Kilter Original")
        }
        if ((kilter to KILTER_HOMEWALL_LAYOUT) in loggedBoards) {
            options += HeatmapBoardOption(kilter, KILTER_HOMEWALL_LAYOUT, KILTER_HOMEWALL_DEFAULT_SIZE, "Kilter Homewall")
        }
        for (brand in BoardBrand.entries) {
            if (!brand.usesAuroraProtocol || brand == BoardBrand.KILTER) continue
            val variants = GymBoardCatalog.auroraVariants(brand)
            if (variants.isNotEmpty()) {
                for (v in variants) {
                    if ((brand.wireValue to v.layoutId) in loggedBoards) {
                        options += HeatmapBoardOption(brand.wireValue, v.layoutId, v.defaultSizeId, v.displayName)
                    }
                }
            } else if (brand.wireValue in loggedBrands) {
                // Single-layout board: its one layout comes from the logged ascent; the
                // size is the board's largest bundled one (Android orders them by area,
                // which is the single-layout default size for each of these brands).
                val loggedLayout = loggedBoards.firstOrNull { it.first == brand.wireValue }?.second
                val defaults = GymBoardCatalog.singleLayoutDefault(brand)
                options += HeatmapBoardOption(
                    brand.wireValue,
                    loggedLayout ?: KILTER_ORIGINAL_LAYOUT,
                    defaults?.second ?: 1,
                    brand.displayName,
                )
            }
        }
        if (BoardBrand.MOONBOARD.hasHeatmap) {
            for (v in MoonBoardVariant.entries) {
                if ((BoardBrand.MOONBOARD.wireValue to v.layoutId.toInt()) in loggedBoards) {
                    options += HeatmapBoardOption(BoardBrand.MOONBOARD.wireValue, v.layoutId.toInt(), 0, v.displayName)
                }
            }
        }
        return options
    }

    /** The active board is drawn on the user's configured size, not the representative one. */
    fun withActiveSize(options: List<HeatmapBoardOption>, activeBrandWire: String, activeLayoutId: Int, activeSizeId: Int) =
        options.map { opt ->
            if (opt.brandWire == activeBrandWire && opt.layoutId == activeLayoutId &&
                opt.brandWire != BoardBrand.MOONBOARD.wireValue && activeSizeId > 0
            ) opt.copy(sizeId = activeSizeId) else opt
        }

    /** Android `resolveDefaultHeatmapSelection`: the active board, else its brand, else the first. */
    fun defaultSelection(options: List<HeatmapBoardOption>, activeBrandWire: String, activeLayoutId: Int): HeatmapBoardOption? {
        val wire = BoardBrand.fromWire(activeBrandWire).wireValue
        return options.firstOrNull { it.brandWire == wire && it.layoutId == activeLayoutId }
            ?: options.firstOrNull { it.brandWire == wire }
            ?: options.firstOrNull()
    }
}

/**
 * Android `HeatmapMode` without OFF, as lowercase codes in its chip order.
 * "personal" counts the user's own logged ascents on the board's brand; the
 * others count the board catalogue at every angle ("global": any hold, the
 * rest: one hold role).
 */
object HeatmapModes {
    val CODES: List<String> = listOf("global", "personal", "start", "hand", "foot", "finish")
    const val DEFAULT = "personal"

    fun isValid(code: String): Boolean = code in CODES

    fun compute(code: String, frameRows: List<String>): Map<Int, Float> {
        val raw = when (code) {
            "start" -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.START)
            "hand" -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.HAND)
            "foot" -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.FOOT)
            "finish" -> HoldHeatmapComputer.computeHeatmapByRole(frameRows, HoldRole.FINISH)
            else -> HoldHeatmapComputer.computeGlobalHeatmap(frameRows)
        }
        return HoldHeatmapComputer.normalizeHeatmap(raw)
    }

    /** The frames a mode counts: own ascents of the board's brand, or the layout's catalogue. */
    fun frames(code: String, option: HeatmapBoardOption, ownAscents: () -> List<AscentWithClimb>, board: BoardRepository): List<String> =
        if (code == "personal") {
            val brand = BoardBrand.fromWire(option.brandWire)
            ownAscents().filter { it.brand == brand }.map { it.climbFrames }.filter { it.isNotBlank() }
        } else {
            board.getAllFramesForHeatmapAllAngles(
                layoutId = option.layoutId,
                boardBrand = option.brandWire,
                minDifficulty = 0.0,
                maxDifficulty = Double.MAX_VALUE,
                minAscensionists = 0,
                climbType = ClimbTypeFilter.ALL,
            ).map { it.frames }
        }
}

data class StatsHeatmapState(
    /** loading | ready | failed */
    val status: StatsHeatmapStatus = StatsHeatmapStatus.LOADING,
    val options: List<HeatmapBoardOption> = emptyList(),
    /** null = Android's "All": no single grid, only a hint. */
    val selection: HeatmapBoardOption? = null,
    val mode: String = HeatmapModes.DEFAULT,
    val placements: Map<Int, BoardPlacement> = emptyMap(),
    val boardSize: BoardSize? = null,
    val heat: Map<Int, Float> = emptyMap(),
    /** The heat of [mode] on [selection] is being computed. */
    val computing: Boolean = false,
)

enum class StatsHeatmapStatus { LOADING, READY, FAILED }

/**
 * The hold heatmap of Android's board statistics sheet (`BoardLogbookViewModel`
 * `preloadStats` / `loadBoardData` / `recomputeHeatmap`). Never throws.
 */
class StatsHeatmapPresenter(
    private val boardRepository: BoardRepository,
    private val personalRepo: PersonalBoardRepository,
    private val prefs: BrowsePreferences,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val _state = MutableStateFlow(StatsHeatmapState())
    val state: StateFlow<StatsHeatmapState> = _state.asStateFlow()
    private var boardJob: Job? = null
    private var heatJob: Job? = null

    fun load() {
        scope.launch {
            try {
                val logged = withContext(io) {
                    personalRepo.getUserLogbookAllLight()
                        .mapNotNull { HeatmapBoards.loggedBoardKey(it.boardBrand, it.layoutId) }
                        .toSet()
                }
                val active = prefs.boardSelection()
                val activeWire = BoardBrand.fromWire(active.boardBrand).wireValue
                val options = HeatmapBoards.withActiveSize(
                    HeatmapBoards.options(logged), activeWire, active.layoutId, active.productSizeId,
                )
                val previous = _state.value.selection
                // Keep a pick across reloads while its board is still offered.
                val selection = previous?.takeIf { it in options }
                    ?: HeatmapBoards.defaultSelection(options, active.boardBrand, active.layoutId)
                _state.update { it.copy(status = StatsHeatmapStatus.READY, options = options, selection = selection) }
                loadBoard()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = StatsHeatmapStatus.FAILED) }
            }
        }
    }

    /** [key] of an offered board, or "" for "All". An unknown key is ignored. */
    fun selectBoard(key: String) {
        val s = _state.value
        val next = if (key.isEmpty()) null else s.options.firstOrNull { it.key == key } ?: return
        if (next == s.selection) return
        _state.update { it.copy(selection = next, placements = emptyMap(), boardSize = null, heat = emptyMap()) }
        loadBoard()
    }

    fun setMode(code: String) {
        if (!HeatmapModes.isValid(code) || code == _state.value.mode) return
        _state.update { it.copy(mode = code, heat = emptyMap()) }
        recompute()
    }

    fun close() = scope.cancel()

    private fun loadBoard() {
        boardJob?.cancel()
        val option = _state.value.selection
        if (option == null) {
            heatJob?.cancel()
            _state.update { it.copy(placements = emptyMap(), boardSize = null, heat = emptyMap(), computing = false) }
            return
        }
        boardJob = scope.launch {
            try {
                val (placements, size) = withContext(io) {
                    boardRepository.getPlacementsForLayout(option.sizeId, option.layoutId, option.brandWire)
                        .associateBy { it.placementId.toInt() } to
                        boardRepository.getProductSize(option.sizeId, option.brandWire)
                }
                _state.update { if (it.selection == option) it.copy(placements = placements, boardSize = size) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An empty canvas, as Android leaves it when the board data fails to load.
            }
        }
        recompute()
    }

    private fun recompute() {
        heatJob?.cancel()
        val s = _state.value
        val option = s.selection ?: return
        val mode = s.mode
        if (!BoardBrand.fromWire(option.brandWire).hasHeatmap) return
        _state.update { it.copy(computing = true) }
        heatJob = scope.launch {
            try {
                val heat = withContext(io) {
                    HeatmapModes.compute(mode, HeatmapModes.frames(mode, option, { personalRepo.getUserAscentsAll() }, boardRepository))
                }
                _state.update {
                    if (it.selection == option && it.mode == mode) it.copy(heat = heat, computing = false) else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.selection == option && it.mode == mode) it.copy(heat = emptyMap(), computing = false) else it }
            }
        }
    }
}
