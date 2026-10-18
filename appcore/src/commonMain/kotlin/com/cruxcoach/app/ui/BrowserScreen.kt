package com.cruxcoach.app.ui

import com.cruxcoach.app.browse.BoardBrowserPresenter
import com.cruxcoach.app.browse.BrowseFilterEdit
import com.cruxcoach.app.browse.BoardBrowserUiState
import com.cruxcoach.app.setup.BoardOption
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.util.GradeConverter

class ClimbRowUi(
    val uuid: String,
    val name: String,
    val setter: String,
    /** Already in the user's scale; empty when the climb has no community grade. */
    val grade: String,
    val quality: String,
    val sends: Long,
)

class BrowserScreenState(
    val boardTitle: String,
    val brandWire: String,
    val angle: Int,
    /** Empty = continuous slider (Kilter 0…70). */
    val angleChips: List<Int>,
    val climbs: List<ClimbRowUi>,
    /** -1 while the count is still being computed. */
    val totalCount: Long,
    val canLoadMore: Boolean,
    /** loading | loadingMore | ready | failed */
    val loadState: String,
    val hasCatalogue: Boolean,
    val excludesSent: Boolean,
    val statusNew: Boolean,
    val statusAttempted: Boolean,
    val statusSent: Boolean,
    val minGradeIndex: Int,
    val maxGradeIndex: Int,
    val minAscensionists: Int,
    val benchmarkOnly: Boolean,
    val myClimbsOnly: Boolean,
    val ungradedOnly: Boolean,
    val sortCode: String,
    val randomClimbUuid: String,
    /** Placement ids currently required by the hold search; empty = off. */
    val holdFilter: List<Int>,
    /** Every hold of the active board, for the hold-search canvas. */
    val boardHolds: List<BrowseBoardHoldUi>,
    val boardImagePaths: List<String>,
    val boardAspect: Float,
    /** An area is part of the hold search: climbs must lie fully inside it. */
    val zoneActive: Boolean,
    /** The area as a rectangle normalized over the board image (origin top-left); 0 while inactive. */
    val zoneLeft: Float,
    val zoneTop: Float,
    val zoneRight: Float,
    val zoneBottom: Float,
    /** Quantum board: rule and wall-fit filters apply, benchmark/type filters do not (Android BoardBrowsePolicy). */
    val isQuantum: Boolean,
    /** Required route rules as lowercase codes (standard, campusing, edge, kickplate, matching). */
    val quantumRules: List<String>,
    /** "Fits the wall": off | none | atMostOne */
    val quantumOverlap: String,
    /** False while no layer rack feeds this browser: Swift shows the filter as unavailable. */
    val quantumWallKnown: Boolean,
    /** The wall's lit holds are known: the wall-fit choices can be enabled. */
    val quantumLayersAvailable: Boolean,
    val quantumLayersOccupied: Boolean,
    val quantumLayersComplete: Boolean,
    /** Climbs that fit under the active wall-fit filter; -1 while unknown. */
    val quantumFitCount: Long,
    /**
     * Android's active hold-filter banner ("N holds → M climbs", Change / Clear): shown while the
     * applied hold search narrows the list. [holdFilter]/[zoneActive] say what it consists of.
     */
    val holdFilterActive: Boolean,
    /** Climbs the applied hold search matches on its own (the banner's M). */
    val holdFilterMatches: Int,
)

/** One selectable hold: [x]/[y] are normalized over the board image. */
class BrowseBoardHoldUi(val placementId: Int, val x: Float, val y: Float)

/** Board browser screen. Wraps the ported Android filter/count/pagination pipeline. */
class BrowserScreenModel(private val presenter: BoardBrowserPresenter, private val grades: GradeFormatter) {

    val maxGradeIndex: Int = GradeConverter.MAX_INDEX

    /** Snapshot for SwiftUI's initial render; [watch] delivers every change after it. */
    val currentState: BrowserScreenState get() = map(presenter.state.value)

    fun watch(onState: (BrowserScreenState) -> Unit): Subscription {
        val watch = presenter.watch { onState(map(it)) }
        return Subscription { watch.close() }
    }

    private fun map(state: BoardBrowserUiState): BrowserScreenState {
        val filter = state.filter
        val brand = BoardBrand.fromWire(filter.boardBrand)
        val zoneBox = zoneRect(state)
        return BrowserScreenState(
            boardTitle = state.board.boardSize?.name?.takeIf { it.isNotBlank() } ?: UiCodes.brandTitle(brand),
            brandWire = filter.boardBrand,
            angle = filter.angle,
            angleChips = filter.angleChips,
            climbs = state.climbs.map { row(it) },
            totalCount = state.totalCount,
            canLoadMore = state.canLoadMore,
            loadState = UiCodes.loadState(state.loadState),
            hasCatalogue = state.board.hasCatalogue,
            excludesSent = state.excludesSent,
            statusNew = UiCodes.status("new") in filter.statusFilter,
            statusAttempted = UiCodes.status("attempted") in filter.statusFilter,
            statusSent = UiCodes.status("sent") in filter.statusFilter,
            minGradeIndex = filter.minGradeIndex,
            maxGradeIndex = filter.maxGradeIndex,
            minAscensionists = filter.minAscensionists,
            benchmarkOnly = filter.benchmarkOnly,
            myClimbsOnly = filter.myClimbsOnly,
            ungradedOnly = filter.ungradedOnly,
            sortCode = UiCodes.sortCode(filter.sortField, filter.sortDirection),
            randomClimbUuid = state.randomClimb?.uuid ?: "",
            holdFilter = holdFilter.toList(),
            boardHolds = boardHolds(state),
            boardImagePaths = state.board.boardSize?.let {
                com.cruxcoach.app.render.boardImageCandidatePaths(brand, it.id, filter.layoutId.toLong())
            } ?: emptyList(),
            boardAspect = if (quantumModel(state) != null) com.cruxcoach.app.render.QuantumRender.ASPECT
            else state.board.boardSize?.let { size ->
                val w = (size.edgeRight - size.edgeLeft).toFloat()
                val h = (size.edgeTop - size.edgeBottom).toFloat()
                if (w > 0f && h > 0f) w / h else DEFAULT_BOARD_ASPECT
            } ?: DEFAULT_BOARD_ASPECT,
            zoneActive = zone != null,
            zoneLeft = zoneBox[0],
            zoneTop = zoneBox[1],
            zoneRight = zoneBox[2],
            zoneBottom = zoneBox[3],
            isQuantum = brand == BoardBrand.QUANTUM,
            quantumRules = QUANTUM_RULES.filter { (_, rule) -> (filter.quantumRuleMask and rule.bit) != 0L }.map { it.first },
            quantumOverlap = QUANTUM_OVERLAPS.firstOrNull { it.second == filter.quantumOverlapFilter }?.first ?: "off",
            quantumWallKnown = presenter.followsQuantumLayers,
            quantumLayersAvailable = state.core.quantumLayers.available,
            quantumLayersOccupied = state.core.quantumLayers.occupied,
            quantumLayersComplete = state.core.quantumLayers.complete,
            quantumFitCount = state.core.quantumLayers.matchCount,
            holdFilterActive = state.holdFilterActive,
            holdFilterMatches = state.holdFilterMatches,
        )
    }

    /** Android `toggleQuantumRuleFilter`: one positive rule on or off; ignored off a Quantum board. */
    fun toggleQuantumRule(code: String) {
        val rule = QUANTUM_RULES.firstOrNull { it.first == code }?.second ?: return
        val current = presenter.state.value.filter
        if (BoardBrand.fromWire(current.boardBrand) != BoardBrand.QUANTUM) return
        presenter.setFilters(edit(current).copy(quantumRuleMask = current.quantumRuleMask xor rule.bit))
    }

    /** Android `setQuantumOverlapFilter`: off | none | atMostOne; ignored off a Quantum board. */
    fun setQuantumOverlap(code: String) {
        val overlap = QUANTUM_OVERLAPS.firstOrNull { it.first == code }?.second ?: return
        val current = presenter.state.value.filter
        if (BoardBrand.fromWire(current.boardBrand) != BoardBrand.QUANTUM || current.quantumOverlapFilter == overlap) return
        presenter.setFilters(edit(current).copy(quantumOverlapFilter = overlap))
    }

    /** Feeds the wall's layers into "Fits the wall" (AppCore wires the process-wide rack). */
    internal fun followQuantumLayers(layers: kotlinx.coroutines.flow.Flow<com.cruxcoach.app.browse.BrowserQuantumLayerState>) =
        presenter.followQuantumLayers(layers)

    private fun edit(f: com.cruxcoach.app.browse.BrowserFilterState) = BrowseFilterEdit(
        minGradeIndex = f.minGradeIndex,
        maxGradeIndex = f.maxGradeIndex,
        minAscensionists = f.minAscensionists,
        climbTypeFilter = f.climbTypeFilter,
        benchmarkOnly = f.benchmarkOnly,
        originFilter = f.originFilter,
        myClimbsOnly = f.myClimbsOnly,
        ungradedOnly = f.ungradedOnly,
        quantumRuleMask = f.quantumRuleMask,
        quantumOverlapFilter = f.quantumOverlapFilter,
    )

    /** left, top, right, bottom of [zone] over the board image; zeros while no area is set. */
    private fun zoneRect(state: BoardBrowserUiState): List<Float> = zoneBox(state, zone)

    internal fun zoneBox(state: BoardBrowserUiState, area: com.cruxcoach.domain.board.BoardZone?): List<Float> {
        val z = area ?: return listOf(0f, 0f, 0f, 0f)
        quantumModel(state)?.let { return com.cruxcoach.app.render.QuantumRender.zoneRect(it, z) }
        val geometry = com.cruxcoach.app.render.AuroraBoardGeometry.of(state.board.boardSize)
        // y grows upwards on the board: the zone's top edge is maxY.
        val topLeft = geometry.normalized(z.minX, z.maxY)
        val bottomRight = geometry.normalized(z.maxX, z.minY)
        return listOf(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }

    private fun row(climb: ClimbWithStats) = ClimbRowUi(
        uuid = climb.uuid,
        name = climb.name,
        setter = climb.setterUsername ?: "",
        grade = grades.label(climb.difficultyAverage),
        quality = climb.qualityAverage?.let { grades.oneDecimal(it) } ?: "",
        sends = climb.ascensionistCount ?: 0L,
    )

    private var holdFilter: MutableSet<Int> = linkedSetOf()
    private var zone: com.cruxcoach.domain.board.BoardZone? = null

    /**
     * Hold search: a climb matches when it uses every selected hold (and lies
     * inside the area, when one is set). Tapping a selected hold clears it; no
     * holds and no area turns the filter off.
     */
    fun toggleHoldFilter(placementId: Int) {
        if (!holdFilter.add(placementId)) holdFilter.remove(placementId)
        presenter.applyHoldFilter(holdFilter.toSet(), zone)
    }

    /** Android's "Reset": drops the holds and the area. */
    fun clearHoldFilter() {
        if (holdFilter.isEmpty() && zone == null) return
        holdFilter = linkedSetOf()
        zone = null
        presenter.applyHoldFilter(emptySet(), null)
    }

    /**
     * Android's "Select area" drag: two points normalized over the board image
     * (any diagonal) become the area, in the board's placement coordinates as on
     * Android. The drag mode itself is view state; a drag that is barely a tap
     * is dropped by Swift (Android: below twice the touch slop).
     */
    fun setZone(ax: Float, ay: Float, bx: Float, by: Float) {
        zone = zoneFromCanvas(ax, ay, bx, by) ?: return
        presenter.applyHoldFilter(holdFilter.toSet(), zone)
    }

    /** The drag's two image-normalized points as a board area; null for points that are not numbers. */
    internal fun zoneFromCanvas(ax: Float, ay: Float, bx: Float, by: Float): com.cruxcoach.domain.board.BoardZone? {
        val values = listOf(ax, ay, bx, by)
        if (values.any { it.isNaN() || it.isInfinite() }) return null
        val clamped = values.map { it.coerceIn(0f, 1f) }
        val geometry = com.cruxcoach.app.render.AuroraBoardGeometry.of(presenter.state.value.board.boardSize)
        return geometry.zoneFromCanvas(clamped[0], clamped[1], clamped[2], clamped[3], 1f, 1f)
    }

    /** Android's hold search sheet: a pending selection counted on its own and applied by "Filter". */
    fun makeHoldSearch(): HoldSearchScreenModel = HoldSearchScreenModel(this, kotlinx.coroutines.Dispatchers.Main)

    internal val browserState: BoardBrowserUiState get() = presenter.state.value
    internal fun appliedHolds(): Set<Int> = holdFilter.toSet()
    internal fun appliedZone(): com.cruxcoach.domain.board.BoardZone? = zone
    internal suspend fun holdMatchCount(holds: Set<Int>, area: com.cruxcoach.domain.board.BoardZone?): Int =
        presenter.holdMatchCount(holds, area)

    /** Android `applyHoldFilter`/`clearHoldSelection`: the sheet's selection becomes the browser's filter. */
    internal fun applyHoldSearch(holds: Set<Int>, area: com.cruxcoach.domain.board.BoardZone?) {
        holdFilter = LinkedHashSet(holds)
        zone = area
        presenter.applyHoldFilter(holds, area)
    }

    /** Android's "Remove area": the hold selection stays. */
    fun clearZone() {
        if (zone == null) return
        zone = null
        presenter.applyHoldFilter(holdFilter.toSet(), null)
    }

    /** Non-null only on a Quantum board: its holds follow the model's own mapping, not the edge box. */
    private fun quantumModel(state: BoardBrowserUiState): com.cruxcoach.domain.board.QuantumBoardModel? =
        if (BoardBrand.fromWire(state.filter.boardBrand) != BoardBrand.QUANTUM) null
        else com.cruxcoach.app.render.QuantumRender.model(state.board.boardSize, state.filter.layoutId.toLong())

    private fun boardHolds(state: BoardBrowserUiState): List<BrowseBoardHoldUi> {
        quantumModel(state)?.let { model ->
            return state.board.placements.mapNotNull { (id, placement) ->
                com.cruxcoach.app.render.QuantumRender.normalized(model, placement.x, placement.y)
                    .takeIf(com.cruxcoach.app.render.QuantumRender::isVisible)
                    ?.let { BrowseBoardHoldUi(id, it.x, it.y) }
            }
        }
        val size = state.board.boardSize ?: return emptyList()
        val geometry = com.cruxcoach.app.render.AuroraBoardGeometry.of(size)
        return state.board.placements.map { (id, placement) ->
            val point = geometry.normalized(placement.x, placement.y)
            BrowseBoardHoldUi(id, point.x, point.y)
        }
    }

    /**
     * Android returns to the browser with the board the guided fix chose elsewhere (climb detail,
     * Quantum rack): reload it, dropping the old board's hold search. A no-op while it is shown.
     */
    fun refreshIfBoardChanged() {
        if (!presenter.storedBoardDiffers()) return
        holdFilter = linkedSetOf()
        zone = null
        presenter.refresh()
    }

    fun start() = presenter.start()
    fun close() = presenter.close()
    fun refresh() = presenter.refresh()
    fun loadMore() = presenter.loadMore()
    fun pickRandom() = presenter.pickRandom()
    fun consumeRandom() = presenter.consumeRandomClimb()
    fun setAngle(angle: Int) = presenter.setAngle(angle)
    fun setSearch(query: String) = presenter.setSearch(query)
    fun setExcludeSent(exclude: Boolean) = presenter.setExcludeSent(exclude)
    fun clearFilters() = presenter.clearAllFilters()

    fun setSort(code: String) {
        val sort = UiCodes.sort(code) ?: return
        presenter.setSort(sort.first, sort.second)
    }

    fun toggleStatus(code: String) {
        val status = UiCodes.status(code) ?: return
        presenter.toggleStatus(status)
    }

    fun switchBoard(option: BoardOption) {
        // Placement ids and an area in the old board's coordinates mean nothing on
        // another board; the presenter drops its filter on a real switch, Android
        // clears both. Re-picking the same board keeps them, as the presenter does.
        val s = presenter.state.value
        val sameBoard = s.filter.boardBrand == option.brandWire && s.filter.layoutId == option.layoutId &&
            (option.productSizeId <= 0 || s.board.productSizeId == option.productSizeId)
        if (!sameBoard) {
            holdFilter = linkedSetOf()
            zone = null
        }
        presenter.switchBoard(
            boardBrand = option.brandWire,
            layoutId = option.layoutId,
            productSizeId = option.productSizeId.takeIf { it > 0 },
            angle = null,
        )
    }

    /**
     * Applies the whole filter form at once, exactly as the Android filter sheet does.
     * Grade indexes are clamped by the presenter.
     */
    fun applyFilters(
        minGradeIndex: Int,
        maxGradeIndex: Int,
        minAscensionists: Int,
        benchmarkOnly: Boolean,
        myClimbsOnly: Boolean,
        ungradedOnly: Boolean,
    ) {
        val current = (presenter.state.value).filter
        presenter.setFilters(
            BrowseFilterEdit(
                minGradeIndex = minGradeIndex,
                maxGradeIndex = maxGradeIndex,
                minAscensionists = minAscensionists,
                climbTypeFilter = current.climbTypeFilter,
                benchmarkOnly = benchmarkOnly,
                originFilter = current.originFilter,
                myClimbsOnly = myClimbsOnly,
                ungradedOnly = ungradedOnly,
                quantumRuleMask = current.quantumRuleMask,
                quantumOverlapFilter = current.quantumOverlapFilter,
            )
        )
    }

    /** Quantum rule codes in Android's chip order, for the filter sheet. */
    val quantumRuleCodes: List<String> = QUANTUM_RULES.map { it.first }

    /** Sort codes the UI may offer, in Android's order. */
    val sortCodes: List<String> = listOf("popular", "quality", "qualitySends", "hardest", "easiest", "name", "newest", "random")

    companion object {
        const val DEFAULT_BOARD_ASPECT = 0.65f

        /** Android's chip order in the filter sheet. */
        private val QUANTUM_RULES = listOf(
            "standard" to com.cruxcoach.app.browse.QuantumRuleFilter.STANDARD,
            "campusing" to com.cruxcoach.app.browse.QuantumRuleFilter.CAMPUSING,
            "edge" to com.cruxcoach.app.browse.QuantumRuleFilter.EDGE,
            "kickplate" to com.cruxcoach.app.browse.QuantumRuleFilter.KICKPLATE,
            "matching" to com.cruxcoach.app.browse.QuantumRuleFilter.MATCHING,
        )
        private val QUANTUM_OVERLAPS = listOf(
            "off" to com.cruxcoach.domain.board.QuantumOverlapFilter.OFF,
            "none" to com.cruxcoach.domain.board.QuantumOverlapFilter.NONE,
            "atMostOne" to com.cruxcoach.domain.board.QuantumOverlapFilter.AT_MOST_ONE,
        )

        /** Kilter's continuous angle range; other boards use [BrowserScreenState.angleChips]. */
        const val MIN_ANGLE = 0
        const val MAX_ANGLE = 70
        const val ANGLE_STEP = 5
    }
}

/** Grade rendering in the user's selected scale. Kept in Kotlin so both platforms agree. */
class GradeFormatter(private val french: Boolean) {
    fun label(difficultyAverage: Double?): String {
        val value = difficultyAverage ?: return ""
        return if (french) {
            com.cruxcoach.domain.board.KilterGradeMapper.difficultyToFont(value)
        } else {
            com.cruxcoach.domain.board.KilterGradeMapper.difficultyToVScale(value)
        }
    }

    fun oneDecimal(value: Double): String = com.cruxcoach.util.formatDecimal(value, 1)
}
