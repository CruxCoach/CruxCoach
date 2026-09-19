package com.cruxcoach.app.browse

import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardClimbParser
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.board.QuantumOverlapFilter
import com.cruxcoach.domain.board.QuantumOverlapIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

// Port of the pure parts of Android's ui/board/BoardBrowserViewModel.kt. Names and
// semantics are kept identical so the Android golden tests apply unchanged.

/** Status of a climb relative to the local user. The three buckets are DISJOINT
 *  (`getUserAttemptedClimbUuids` EXCEPTs sent climbs). The browser filter is a
 *  multi-select set: EMPTY means "no status constraint". */
enum class ClimbStatusFilter { NEW, ATTEMPTED, SENT }

fun statusFilterExcludesSent(statuses: Set<ClimbStatusFilter>): Boolean =
    statuses.isNotEmpty() && ClimbStatusFilter.SENT !in statuses

/** The exclude-sent switch edits the same status set; it is not a second filter.
 *  Excluding the only selected SENT bucket selects both unsent buckets instead of
 *  accidentally resetting to "all". */
fun statusFilterWithSentExcluded(
    statuses: Set<ClimbStatusFilter>,
    exclude: Boolean,
): Set<ClimbStatusFilter> {
    val effective = statuses.ifEmpty { ClimbStatusFilter.entries.toSet() }
    return if (exclude) {
        (effective - ClimbStatusFilter.SENT).ifEmpty {
            setOf(ClimbStatusFilter.NEW, ClimbStatusFilter.ATTEMPTED)
        }
    } else {
        (effective + ClimbStatusFilter.SENT).let {
            if (it.size == ClimbStatusFilter.entries.size) emptySet() else it
        }
    }
}

/** Accepts "NEW,SENT", "" and the legacy tokens "ALL" / "UNSENT". Unknown tokens are ignored. */
fun parseStatusFilter(raw: String): Set<ClimbStatusFilter> {
    if (raw.isBlank()) return emptySet()
    val out = LinkedHashSet<ClimbStatusFilter>()
    for (token in raw.split(',')) {
        when (val t = token.trim()) {
            "", "ALL" -> { /* no constraint */ }
            "UNSENT" -> { out += ClimbStatusFilter.NEW; out += ClimbStatusFilter.ATTEMPTED }
            else -> ClimbStatusFilter.entries.firstOrNull { it.name == t }?.let { out += it }
        }
    }
    return out
}

fun serializeStatusFilter(statuses: Set<ClimbStatusFilter>): String =
    statuses.joinToString(",") { it.name }

/** Provenance filter; corresponds to the `origin` column on `climbs`. */
enum class OriginFilter { ALL, CRUXCOACH, KILTER, BOARDSESH }

/** Positive eWalls route rules. Imported Quantum climbs store a bit when a rule
 *  is MISSING, so `(hsm & mask) = 0` applies the rule before COUNT/LIMIT. */
enum class QuantumRuleFilter(val bit: Long) {
    CAMPUSING(1L),
    EDGE(2L),
    KICKPLATE(4L),
    MATCHING(8L),
    STANDARD(16L),
}

object BrowserOriginFilter {
    fun apply(climbs: List<ClimbWithStats>, filter: OriginFilter): List<ClimbWithStats> = when (filter) {
        OriginFilter.ALL -> climbs
        // Local drafts are cruxcoach-side even on legacy rows whose origin still reads 'kilter'.
        OriginFilter.CRUXCOACH -> climbs.filter { it.origin == "cruxcoach" || it.source == "local" }
        // KILTER is the persisted legacy name for "official vendor catalogue".
        OriginFilter.KILTER -> climbs.filter {
            it.origin in setOf("kilter", "quantum") && it.source != "local"
        }
        OriginFilter.BOARDSESH -> climbs.filter { it.origin == "boardsesh" }
    }
}

/** One row per identity, preserving visible order. OFFSET pages can overlap. */
fun mergeBrowseClimbs(
    existing: List<ClimbWithStats>,
    incoming: List<ClimbWithStats>,
): List<ClimbWithStats> = (existing + incoming).distinctBy { it.uuid }

/** Whole-set results must not advertise a continuation after discarding rows. */
fun completeBrowseResults(climbs: List<ClimbWithStats>): Triple<List<ClimbWithStats>, Int, Boolean> {
    val unique = climbs.distinctBy { it.uuid }
    return Triple(unique, unique.size, true)
}

/** Count exactly the same filtered identities as browsing, across empty or overlapping pages. */
suspend fun countBrowseMatches(
    fetchPage: suspend (Int) -> Triple<List<ClimbWithStats>, Int, Boolean>,
): Long {
    val uuids = HashSet<String>()
    var offset = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val (page, nextOffset, exhausted) = fetchPage(offset)
        page.forEach { uuids.add(it.uuid) }
        if (exhausted) return uuids.size.toLong()
        check(nextOffset > offset) { "Browse count source did not advance" }
        offset = nextOffset
    }
}

/** Refill to [targetSize] rows (same-query refresh keeps the scrolled depth). */
suspend fun refillBrowsePages(
    targetSize: Int,
    fetchPage: suspend (dbOffset: Int) -> Triple<List<ClimbWithStats>, Int, Boolean>,
): Triple<List<ClimbWithStats>, Int, Boolean> {
    var (results, offset, exhausted) = fetchPage(0)
    results = mergeBrowseClimbs(emptyList(), results)
    while (results.size < targetSize && !exhausted) {
        val (more, nextOffset, nextExhausted) = fetchPage(offset)
        // A duplicate page must still advance the cursor or dedup could spin forever.
        if (nextOffset <= offset && !nextExhausted) break
        results = mergeBrowseClimbs(results, more)
        offset = nextOffset
        exhausted = nextExhausted
    }
    return Triple(results, offset, exhausted)
}

data class BrowserFilterState(
    val angle: Int = 40,
    val layoutId: Int = 1,
    val boardBrand: String = "kilter",
    /** Discrete angle options; empty = continuous Kilter 0-70 slider. */
    val angleChips: List<Int> = emptyList(),
    val minGradeIndex: Int = DEFAULT_MIN_GRADE_INDEX,
    val maxGradeIndex: Int = DEFAULT_MAX_GRADE_INDEX,
    val minAscensionists: Int = 0,
    val searchQuery: String = "",
    val sortField: ClimbSortField = ClimbSortField.ASCENSIONISTS,
    val sortDirection: SortDirection = SortDirection.DESC,
    /** Multi-select status filter; empty = no constraint. */
    val statusFilter: Set<ClimbStatusFilter> = emptySet(),
    val climbTypeFilter: ClimbTypeFilter = ClimbTypeFilter.BOULDER,
    val benchmarkOnly: Boolean = false,
    val originFilter: OriginFilter = OriginFilter.ALL,
    val quantumRuleMask: Long = 0L,
    val quantumOverlapFilter: QuantumOverlapFilter = QuantumOverlapFilter.OFF,
    val myClimbsOnly: Boolean = false,
    /** Only ungraded climbs; the grade range is inert while true. */
    val ungradedOnly: Boolean = false,
) {
    companion object {
        const val DEFAULT_MIN_GRADE_INDEX = 0
        const val DEFAULT_MAX_GRADE_INDEX = 16
    }
}

object BoardAnglePicker {
    const val KILTER_MIN_ANGLE = 0
    const val KILTER_MAX_ANGLE = 70

    fun chipsFor(brand: BoardBrand, layoutId: Int, supportedAngles: List<Int>): List<Int> =
        when {
            brand == BoardBrand.MOONBOARD ->
                MoonBoardVariant.fromLayoutId(layoutId.toLong())?.angles.orEmpty()
            brand.usesCatalogueAngles -> supportedAngles
            else -> emptyList()
        }

    fun clampAngle(angle: Int, chips: List<Int>): Int =
        if (chips.isEmpty() || angle in chips) angle
        else chips.minBy { abs(it - angle) }

    fun sliderIndex(chips: List<Int>, angle: Int): Int {
        require(chips.isNotEmpty())
        val exact = chips.indexOf(angle)
        return if (exact >= 0) exact else chips.indices.minBy { abs(chips[it] - angle) }
    }

    fun angleAtSliderIndex(chips: List<Int>, index: Int): Int {
        require(chips.isNotEmpty())
        return chips[index.coerceIn(chips.indices)]
    }

    /** Android's slider release rounding: nearest 5 degrees. */
    fun snapSliderAngle(angle: Int): Int = ((angle + 2) / 5) * 5
}

/** A stale filter persisted on one board must not invisibly empty another board. */
object BoardBrowsePolicy {
    fun climbType(brand: BoardBrand, requested: ClimbTypeFilter): ClimbTypeFilter =
        if (brand.supportsClimbTypeFilter) requested else ClimbTypeFilter.BOULDER

    fun benchmarkOnly(brand: BoardBrand, requested: Boolean): Boolean =
        brand.supportsBenchmarkFilter && requested

    fun origin(brand: BoardBrand, requested: OriginFilter): OriginFilter =
        if (!brand.supportsBoardSeshOrigin && requested == OriginFilter.BOARDSESH) {
            OriginFilter.ALL
        } else requested

    fun productSizeId(brand: BoardBrand, selectedId: Int): Int =
        if (brand.usesProductSizeEdgeFit) selectedId else 0

    fun exclusionMask(brand: BoardBrand, holdSetMask: Long, quantumRuleMask: Long): Long =
        if (brand == BoardBrand.QUANTUM) quantumRuleMask else holdSetMask

    fun overlapFilter(
        brand: BoardBrand,
        requested: QuantumOverlapFilter,
        litPlacements: Set<Int>,
    ): QuantumOverlapFilter = when {
        brand != BoardBrand.QUANTUM -> QuantumOverlapFilter.OFF
        litPlacements.isEmpty() -> QuantumOverlapFilter.OFF
        else -> requested
    }
}

/** Unknown or malformed geometry fails closed: it cannot be proven safe beside the live wall. */
fun filterQuantumOverlapClimbs(
    climbs: List<ClimbWithStats>,
    hydratedFrames: Map<String, String>,
    index: QuantumOverlapIndex,
    filter: QuantumOverlapFilter,
): List<ClimbWithStats> = climbs.filter { climb ->
    val frames = climb.frames.takeIf { it.isNotBlank() }
        ?: hydratedFrames[climb.uuid]?.takeIf { it.isNotBlank() }
        ?: return@filter false
    val placements = BoardClimbParser.parseFrames(frames)
        .mapTo(HashSet()) { it.placementId }
    placements.isNotEmpty() && index.matches(placements, filter)
}

/** Rejects pages produced for a browse query that has since been replaced. */
class BrowseRequestGate {
    private var generation = 0L

    fun current(): Long = generation
    fun invalidate(): Long = ++generation
    fun accepts(requestGeneration: Long): Boolean = requestGeneration == generation
}

data class BrowserQuantumLayerState(
    val litPlacements: Set<Int> = emptySet(),
    val layerCount: Int = 0,
    val complete: Boolean = true,
    val matchCount: Long = -1,
) {
    val occupied: Boolean get() = layerCount > 0
    val available: Boolean get() = litPlacements.isNotEmpty()
}

/** Board-scoped part of the browser state that must change atomically on a board switch. */
data class BoardBrowserState(
    /** Hold-set exclusion mask of the active board; 0 = filter off. Bits are positional PER LAYOUT. */
    val hsmExcludedMask: Long = 0,
    val filter: BrowserFilterState = BrowserFilterState(),
    val quantumLayers: BrowserQuantumLayerState = BrowserQuantumLayerState(),
)

/** The board switch as ONE transition: new brand/layout/angle arrive together with a
 *  zeroed hold-set mask, so no read can see the new layout under the old board's bits. */
fun BoardBrowserState.onBoardSwitch(
    angle: Int,
    layoutId: Int,
    boardBrand: String,
    angleChips: List<Int>,
): BoardBrowserState {
    val brand = BoardBrand.fromWire(boardBrand)
    return copy(
        hsmExcludedMask = 0L,
        filter = filter.copy(
            angle = angle,
            layoutId = layoutId,
            boardBrand = boardBrand,
            angleChips = angleChips,
            climbTypeFilter = BoardBrowsePolicy.climbType(brand, filter.climbTypeFilter),
            benchmarkOnly = BoardBrowsePolicy.benchmarkOnly(brand, filter.benchmarkOnly),
            originFilter = BoardBrowsePolicy.origin(brand, filter.originFilter),
            quantumRuleMask = if (brand == BoardBrand.QUANTUM) filter.quantumRuleMask else 0L,
            quantumOverlapFilter = if (brand == BoardBrand.QUANTUM) filter.quantumOverlapFilter
            else QuantumOverlapFilter.OFF,
        ),
        quantumLayers = if (brand == BoardBrand.QUANTUM) quantumLayers else BrowserQuantumLayerState(),
    )
}

/** In-memory sort for the whole-set branches (port of BoardBrowserSorting.kt). */
fun boardBrowserSortInKotlin(
    climbs: List<ClimbWithStats>,
    field: ClimbSortField,
    dir: SortDirection,
): List<ClimbWithStats> {
    if (field == ClimbSortField.RANDOM) return climbs.shuffled()
    val comparator: Comparator<ClimbWithStats> = when (field) {
        ClimbSortField.QUALITY -> compareBy { it.qualityAverage ?: 0.0 }
        ClimbSortField.DIFFICULTY -> compareBy { it.difficultyAverage ?: 0.0 }
        ClimbSortField.ASCENSIONISTS -> compareBy { it.ascensionistCount ?: 0L }
        // Equivalent of String.CASE_INSENSITIVE_ORDER, which is JVM-only.
        ClimbSortField.NAME -> Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        ClimbSortField.BENCHMARK_DIFFICULTY -> compareBy { it.benchmarkDifficulty }
        ClimbSortField.QUALITY_SENDS -> compareBy { (it.ascensionistCount ?: 0L) * (it.qualityAverage ?: 0.0) }
        ClimbSortField.HOLDS -> compareBy { it.storedMoveCount }
        ClimbSortField.NEWEST -> compareBy { it.createdAt ?: "" }
        ClimbSortField.RANDOM -> compareBy { it.ascensionistCount ?: 0L }
    }
    return if (dir == SortDirection.DESC) climbs.sortedWith(comparator.reversed())
    else climbs.sortedWith(comparator)
}
