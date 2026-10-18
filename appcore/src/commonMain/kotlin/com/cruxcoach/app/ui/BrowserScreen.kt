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
            boardAspect = state.board.boardSize?.let { size ->
                val w = (size.edgeRight - size.edgeLeft).toFloat()
                val h = (size.edgeTop - size.edgeBottom).toFloat()
                if (w > 0f && h > 0f) w / h else DEFAULT_BOARD_ASPECT
            } ?: DEFAULT_BOARD_ASPECT,
            zoneActive = zone != null,
            zoneLeft = zoneBox[0],
            zoneTop = zoneBox[1],
            zoneRight = zoneBox[2],
            zoneBottom = zoneBox[3],
        )
    }

    /** left, top, right, bottom of [zone] over the board image; zeros while no area is set. */
    private fun zoneRect(state: BoardBrowserUiState): List<Float> {
        val z = zone ?: return listOf(0f, 0f, 0f, 0f)
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
        val values = listOf(ax, ay, bx, by)
        if (values.any { it.isNaN() || it.isInfinite() }) return
        val clamped = values.map { it.coerceIn(0f, 1f) }
        val geometry = com.cruxcoach.app.render.AuroraBoardGeometry.of(presenter.state.value.board.boardSize)
        zone = geometry.zoneFromCanvas(clamped[0], clamped[1], clamped[2], clamped[3], 1f, 1f)
        presenter.applyHoldFilter(holdFilter.toSet(), zone)
    }

    /** Android's "Remove area": the hold selection stays. */
    fun clearZone() {
        if (zone == null) return
        zone = null
        presenter.applyHoldFilter(holdFilter.toSet(), null)
    }

    private fun boardHolds(state: BoardBrowserUiState): List<BrowseBoardHoldUi> {
        val size = state.board.boardSize ?: return emptyList()
        val geometry = com.cruxcoach.app.render.AuroraBoardGeometry.of(size)
        return state.board.placements.map { (id, placement) ->
            val point = geometry.normalized(placement.x, placement.y)
            BrowseBoardHoldUi(id, point.x, point.y)
        }
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

    /** Sort codes the UI may offer, in Android's order. */
    val sortCodes: List<String> = listOf("popular", "quality", "qualitySends", "hardest", "easiest", "name", "newest", "random")

    companion object {
        const val DEFAULT_BOARD_ASPECT = 0.65f

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
