package com.cruxcoach.app.browse

import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.BoardZone
import com.cruxcoach.domain.board.BoardZoneFilter
import com.cruxcoach.domain.board.HoldHeatmapComputer
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.board.QuantumOverlapIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

/** Everything outside [BrowserFilterState] that changes the browse match set. */
data class BrowseContext(
    /** True when the user's grade scale is French (changes the grade-index bounds). */
    val frenchGrades: Boolean = true,
    /** Configured product size id of the active board; 0 = none. */
    val boardSizeId: Int = 0,
    val hsmExcludedMask: Long = 0L,
    val holdFilterActive: Boolean = false,
    val holdFilterUuids: Set<String> = emptySet(),
    val quantumLayers: BrowserQuantumLayerState = BrowserQuantumLayerState(),
)

/**
 * Port of Android's `BoardBrowserViewModel.fetchFiltered` / `resolveCount` /
 * random pick. Blocking repository calls: run it off the main thread.
 * Not thread-safe; the presenter serialises access.
 */
class BoardBrowsePipeline(
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    /** Hex pubkey of the local Nostr identity, or null when none exists. Must not throw. */
    private val ownPubkeyHex: () -> String?,
    private val random: Random = Random.Default,
) {
    companion object {
        const val PAGE_SIZE = 50
        const val MAX_STATUS_SCAN_PAGES = 10
        const val RANDOM_PICK_MAX_ROLLS = 8
        const val COUNT_PAGE_SIZE = 500
        const val COUNT_DEBOUNCE_MS = 750L

        // Ungraded-only mode: an IMPOSSIBLE range + showUngraded=true leaves
        // exactly the NULL-grade rows on the shared SQL grade predicate.
        const val UNGRADED_ONLY_MIN_DIFF = 9999.0
        const val UNGRADED_ONLY_MAX_DIFF = -9999.0
    }

    data class GradeBounds(val minDiff: Double, val maxDiff: Double, val showUngraded: Boolean)

    private var sentUuids: Set<String> = emptySet()
    private var attemptedUuids: Set<String> = emptySet()
    private var statusLoaded = false
    private var hiddenUuids: Set<String> = emptySet()
    private var hiddenLoaded = false

    private var randomKey: String? = null
    private var randomPage1: List<ClimbWithStats>? = null
    private var randomRest: List<String>? = null

    /** New ascents / ignore toggles must be re-read on the next fetch. */
    fun invalidateUserData() {
        statusLoaded = false
        hiddenLoaded = false
    }

    /** Every explicit sort pick re-rolls RANDOM. */
    fun invalidateRandomCache() {
        randomKey = null
        randomPage1 = null
        randomRest = null
    }

    fun gradeBounds(f: BrowserFilterState, ctx: BrowseContext): GradeBounds {
        if (f.ungradedOnly) return GradeBounds(UNGRADED_ONLY_MIN_DIFF, UNGRADED_ONLY_MAX_DIFF, true)
        return GradeBounds(
            KilterGradeMapper.indexToFilterMin(f.minGradeIndex, ctx.frenchGrades),
            KilterGradeMapper.indexToFilterMax(f.maxGradeIndex, ctx.frenchGrades),
            false,
        )
    }

    private fun ensureStatusLoaded() {
        if (statusLoaded) return
        sentUuids = boardRepository.canonicalizeClimbUuids(personalBoardRepo.getUserSentClimbUuids())
        attemptedUuids = boardRepository.canonicalizeClimbUuids(personalBoardRepo.getUserAttemptedClimbUuids())
        statusLoaded = true
    }

    private fun ensureHiddenLoaded() {
        if (hiddenLoaded) return
        hiddenUuids = boardRepository.canonicalizeClimbUuids(personalBoardRepo.getIgnoredClimbUuids())
        hiddenLoaded = true
    }

    private fun applyHiddenFilter(climbs: List<ClimbWithStats>): List<ClimbWithStats> =
        if (hiddenUuids.isEmpty()) climbs else climbs.filterNot { it.uuid in hiddenUuids }

    fun statusOf(uuid: String): ClimbStatusFilter = when {
        uuid in sentUuids -> ClimbStatusFilter.SENT
        uuid in attemptedUuids -> ClimbStatusFilter.ATTEMPTED
        else -> ClimbStatusFilter.NEW
    }

    private fun applyStatusFilter(climbs: List<ClimbWithStats>, statuses: Set<ClimbStatusFilter>) =
        if (statuses.isEmpty()) climbs else climbs.filter { statusOf(it.uuid) in statuses }

    /** NEW is the unbounded complement of the logged sets, so it must page-scan. */
    private fun isDirectUuidStatus(statuses: Set<ClimbStatusFilter>): Boolean =
        statuses.isNotEmpty() && ClimbStatusFilter.NEW !in statuses

    private fun directStatusUuids(statuses: Set<ClimbStatusFilter>): Set<String> {
        val out = HashSet<String>(sentUuids.size + attemptedUuids.size)
        if (ClimbStatusFilter.SENT in statuses) out += sentUuids
        if (ClimbStatusFilter.ATTEMPTED in statuses) out += attemptedUuids
        return out
    }

    private fun applyBenchmarkFilter(climbs: List<ClimbWithStats>, benchmarkOnly: Boolean) =
        if (benchmarkOnly) climbs.filter { it.benchmarkDifficulty > 0.0 } else climbs

    private fun applyQuantumRuleFilter(climbs: List<ClimbWithStats>, f: BrowserFilterState): List<ClimbWithStats> {
        val mask = if (BoardBrand.fromWire(f.boardBrand) == BoardBrand.QUANTUM) f.quantumRuleMask else 0L
        return if (mask == 0L) climbs else climbs.filter { (it.hsm and mask) == 0L }
    }

    private fun overlapIndex(f: BrowserFilterState, ctx: BrowseContext): QuantumOverlapIndex? {
        val layers = ctx.quantumLayers
        val effective = BoardBrowsePolicy.overlapFilter(
            BoardBrand.fromWire(f.boardBrand), f.quantumOverlapFilter, layers.litPlacements,
        )
        return if (effective.active) QuantumOverlapIndex(layers.litPlacements, layers.complete) else null
    }

    private fun applyQuantumOverlapFilter(
        climbs: List<ClimbWithStats>,
        f: BrowserFilterState,
        ctx: BrowseContext,
    ): List<ClimbWithStats> {
        val index = overlapIndex(f, ctx) ?: return climbs
        val missingFrames = climbs.asSequence().filter { it.frames.isBlank() }.map { it.uuid }.toSet()
        val hydratedFrames = boardRepository.getClimbFramesByUuids(missingFrames)
        return filterQuantumOverlapClimbs(climbs, hydratedFrames, index, f.quantumOverlapFilter)
    }

    private fun selSizeId(f: BrowserFilterState, ctx: BrowseContext): Int =
        BoardBrowsePolicy.productSizeId(BoardBrand.fromWire(f.boardBrand), ctx.boardSizeId)

    private fun hsmMask(f: BrowserFilterState, ctx: BrowseContext): Long =
        BoardBrowsePolicy.exclusionMask(BoardBrand.fromWire(f.boardBrand), ctx.hsmExcludedMask, f.quantumRuleMask)

    private fun sort(climbs: List<ClimbWithStats>, f: BrowserFilterState) =
        boardBrowserSortInKotlin(climbs, f.sortField, f.sortDirection)

    /** First page(s) of a query; [targetSize] > 0 refills to a previously scrolled depth. */
    suspend fun search(
        f: BrowserFilterState,
        ctx: BrowseContext,
        targetSize: Int = 0,
    ): Triple<List<ClimbWithStats>, Int, Boolean> =
        refillBrowsePages(targetSize) { offset -> fetchFiltered(f, ctx, offset) }

    /**
     * Returns (filteredResults, newDbOffset, dbExhausted). Whole-set branches
     * return every match at once with exhausted=true; the paged branches may
     * return MORE than [pageSize] rows and never truncate.
     */
    suspend fun fetchFiltered(
        f: BrowserFilterState,
        ctx: BrowseContext,
        dbOffset: Int,
        pageSize: Int = PAGE_SIZE,
    ): Triple<List<ClimbWithStats>, Int, Boolean> {
        if (f.statusFilter.isNotEmpty()) ensureStatusLoaded()
        ensureHiddenLoaded()

        // MY-CLIMBS: one whole-set call; drafts saved at any angle stay visible.
        if (f.myClimbsOnly) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val pubkey = ownPubkeyHex()
            if (pubkey.isNullOrBlank()) return Triple(emptyList(), 0, true)
            val all = boardRepository.getOwnClimbsForBrowse(pubkey, f.layoutId, f.angle, f.boardBrand)
            val nameFiltered = if (f.searchQuery.isBlank()) all
            else all.filter { it.name.contains(f.searchQuery, ignoreCase = true) }
            val statusFiltered = applyStatusFilter(nameFiltered, f.statusFilter)
            val benchFiltered = applyBenchmarkFilter(statusFiltered, f.benchmarkOnly)
            val originFiltered = applyQuantumRuleFilter(BrowserOriginFilter.apply(benchFiltered, f.originFilter), f)
            return completeBrowseResults(sort(applyQuantumOverlapFilter(applyHiddenFilter(originFiltered), f, ctx), f))
        }

        // CRUXCOACH origin: whole set, otherwise send-less community climbs sink out of reach.
        if (f.originFilter == OriginFilter.CRUXCOACH) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f, ctx)
            val all = boardRepository.getCruxCoachClimbs(
                f.layoutId, f.boardBrand, f.angle, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter,
                selProductSizeId = selSizeId(f, ctx), hsmExcludedMask = hsmMask(f, ctx), showUngraded = gb.showUngraded,
            )
            return completeBrowseResults(sort(wholeSetTail(all, f, ctx), f))
        }

        // BOARDSESH origin: inherently ungraded imports, so the IS NULL escape stays on.
        if (f.originFilter == OriginFilter.BOARDSESH) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f, ctx)
            val all = boardRepository.getBoardSeshClimbs(
                f.layoutId, f.boardBrand, f.angle, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter,
                selProductSizeId = selSizeId(f, ctx), hsmExcludedMask = hsmMask(f, ctx), showUngraded = true,
            )
            return completeBrowseResults(sort(wholeSetTail(all, f, ctx), f))
        }

        // Official Quantum provenance must be exact before pagination.
        if (f.originFilter == OriginFilter.KILTER && BoardBrand.fromWire(f.boardBrand) == BoardBrand.QUANTUM) {
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f, ctx)
            val all = boardRepository.getQuantumOfficialClimbs(
                f.layoutId, f.angle, gb.minDiff, gb.maxDiff, f.minAscensionists,
                f.climbTypeFilter, hsmExcludedMask = hsmMask(f, ctx), showUngraded = gb.showUngraded,
            )
            val nameFiltered = if (f.searchQuery.isBlank()) all else all.filter {
                it.name.contains(f.searchQuery, ignoreCase = true) ||
                    it.setterUsername?.contains(f.searchQuery, ignoreCase = true) == true
            }
            val holdFiltered = if (ctx.holdFilterActive) {
                nameFiltered.filter { it.uuid in ctx.holdFilterUuids }
            } else nameFiltered
            val statusFiltered = applyStatusFilter(holdFiltered, f.statusFilter)
            return completeBrowseResults(sort(applyQuantumOverlapFilter(applyHiddenFilter(statusFiltered), f, ctx), f))
        }

        // HOLD FILTER: direct UUID query. Range-only predicate, so ungraded-only yields nothing.
        if (ctx.holdFilterActive) {
            if (ctx.holdFilterUuids.isEmpty()) return Triple(emptyList(), 0, true)
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f, ctx)
            val all = boardRepository.getClimbsByUuids(
                ctx.holdFilterUuids, f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff,
                f.minAscensionists, f.climbTypeFilter,
            )
            val filtered = applyQuantumRuleFilter(
                BrowserOriginFilter.apply(
                    applyBenchmarkFilter(applyStatusFilter(all, f.statusFilter), f.benchmarkOnly), f.originFilter,
                ),
                f,
            )
            return completeBrowseResults(sort(applyQuantumOverlapFilter(applyHiddenFilter(filtered), f, ctx), f))
        }

        // Non-empty subset of {SENT, ATTEMPTED}: query the logged UUIDs directly, exact count.
        if (isDirectUuidStatus(f.statusFilter)) {
            val uuids = directStatusUuids(f.statusFilter)
            if (uuids.isEmpty()) return Triple(emptyList(), 0, true)
            if (dbOffset > 0) return Triple(emptyList(), dbOffset, true)
            val gb = gradeBounds(f, ctx)
            val all = boardRepository.getClimbsByUuids(
                uuids, f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter,
            )
            val filtered = applyQuantumRuleFilter(
                BrowserOriginFilter.apply(applyBenchmarkFilter(all, f.benchmarkOnly), f.originFilter), f,
            )
            return completeBrowseResults(sort(applyQuantumOverlapFilter(applyHiddenFilter(filtered), f, ctx), f))
        }

        // No status constraint: one DB page, post-filtered.
        if (f.statusFilter.isEmpty()) {
            val rawPage = fetchPage(f, ctx, dbOffset, pageSize)
            return Triple(postFilterPage(rawPage, f, ctx), dbOffset + rawPage.size, rawPage.size < pageSize)
        }

        // Selection includes NEW: bounded page scan.
        val collected = mutableListOf<ClimbWithStats>()
        var currentOffset = dbOffset
        repeat(MAX_STATUS_SCAN_PAGES) {
            currentCoroutineContext().ensureActive()
            val page = fetchPage(f, ctx, currentOffset, pageSize)
            if (page.isEmpty()) return Triple(collected, currentOffset, true)
            currentOffset += page.size
            collected.addAll(postFilterPage(applyStatusFilter(page, f.statusFilter), f, ctx))
            // Never truncate to pageSize: the offset already moved past the overflow rows.
            if (collected.size >= pageSize) return Triple(collected, currentOffset, false)
            if (page.size < pageSize) return Triple(collected, currentOffset, true)
        }
        // Scan cap hit mid-DB: a continuation, not exhaustion.
        return Triple(collected, currentOffset, false)
    }

    private fun wholeSetTail(all: List<ClimbWithStats>, f: BrowserFilterState, ctx: BrowseContext): List<ClimbWithStats> {
        val nameFiltered = if (f.searchQuery.isBlank()) all
        else all.filter { it.name.contains(f.searchQuery, ignoreCase = true) }
        val statusFiltered = applyStatusFilter(nameFiltered, f.statusFilter)
        val benchFiltered = applyBenchmarkFilter(statusFiltered, f.benchmarkOnly)
        return applyQuantumOverlapFilter(applyHiddenFilter(applyQuantumRuleFilter(benchFiltered, f)), f, ctx)
    }

    private fun postFilterPage(page: List<ClimbWithStats>, f: BrowserFilterState, ctx: BrowseContext) =
        applyQuantumOverlapFilter(
            applyHiddenFilter(
                applyQuantumRuleFilter(
                    BrowserOriginFilter.apply(applyBenchmarkFilter(page, f.benchmarkOnly), f.originFilter), f,
                ),
            ),
            f, ctx,
        )

    private fun fetchPage(f: BrowserFilterState, ctx: BrowseContext, offset: Int, pageSize: Int): List<ClimbWithStats> {
        if (f.sortField == ClimbSortField.RANDOM) return fetchRandomPage(f, ctx, offset)
        return if (f.searchQuery.isNotBlank()) {
            boardRepository.searchClimbsByName(
                f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.sortField, f.sortDirection, pageSize, offset,
                f.climbTypeFilter, selProductSizeId = selSizeId(f, ctx), hsmExcludedMask = hsmMask(f, ctx),
            )
        } else {
            val gb = gradeBounds(f, ctx)
            boardRepository.searchClimbsSorted(
                f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.sortField,
                f.sortDirection, pageSize, offset, f.climbTypeFilter, selProductSizeId = selSizeId(f, ctx),
                hsmExcludedMask = hsmMask(f, ctx), showUngraded = gb.showUngraded,
            )
        }
    }

    // RANDOM: page 1 is a SQL random sample; pages 2+ index one Kotlin shuffle of
    // every OTHER matching uuid, which keeps the scroll duplicate- and gap-free.
    private fun fetchRandomPage(f: BrowserFilterState, ctx: BrowseContext, offset: Int): List<ClimbWithStats> {
        val gb = gradeBounds(f, ctx)
        val sel = selSizeId(f, ctx)
        val hm = hsmMask(f, ctx)
        val key = "${f.boardBrand}|${f.angle}|${f.layoutId}|${gb.minDiff}|${gb.maxDiff}|${f.minAscensionists}|" +
            "${f.climbTypeFilter}|$sel|$hm|${gb.showUngraded}|${f.searchQuery}"
        if (key != randomKey) {
            randomKey = key
            randomPage1 = null
            randomRest = null
        }

        if (offset == 0) {
            randomPage1?.let { return it }
            val page1 = if (f.searchQuery.isNotBlank()) {
                boardRepository.searchClimbsByName(
                    f.searchQuery, f.angle, f.layoutId, f.boardBrand, ClimbSortField.RANDOM, SortDirection.DESC,
                    PAGE_SIZE, 0, f.climbTypeFilter, selProductSizeId = sel, hsmExcludedMask = hm,
                )
            } else boardRepository.searchClimbsSorted(
                f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists,
                ClimbSortField.RANDOM, SortDirection.DESC, PAGE_SIZE, 0, f.climbTypeFilter,
                selProductSizeId = sel, hsmExcludedMask = hm, showUngraded = gb.showUngraded,
            )
            randomPage1 = page1
            return page1
        }

        val cache = randomRest ?: run {
            val page1Uuids = randomPage1?.mapTo(HashSet()) { it.uuid } ?: emptySet()
            val all = if (f.searchQuery.isNotBlank()) {
                boardRepository.getAllSearchMatchingUuids(
                    f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.climbTypeFilter,
                    selProductSizeId = sel, hsmExcludedMask = hm,
                )
            } else boardRepository.getAllBrowseMatchingUuids(
                f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists,
                f.climbTypeFilter, selProductSizeId = sel, hsmExcludedMask = hm, showUngraded = gb.showUngraded,
            )
            all.distinct().filterNot { it in page1Uuids }.shuffled(random).also { randomRest = it }
        }
        val cacheIdx = offset - (randomPage1?.size ?: 0)
        if (cacheIdx < 0 || cacheIdx >= cache.size) return emptyList()
        val slice = cache.subList(cacheIdx, minOf(cacheIdx + PAGE_SIZE, cache.size))
        val byUuid = boardRepository.getClimbsByUuids(slice, f.angle).associateBy { it.uuid }
        return slice.mapNotNull { byUuid[it] }
    }

    /** True when SQL's count describes exactly the displayed set. */
    fun isPlainCount(f: BrowserFilterState, ctx: BrowseContext): Boolean {
        ensureHiddenLoaded()
        return f.statusFilter.isEmpty() && f.originFilter == OriginFilter.ALL && !f.myClimbsOnly &&
            !ctx.holdFilterActive && !f.quantumOverlapFilter.active && hiddenUuids.isEmpty()
    }

    /** Fast SQL count only without client-side restrictions; otherwise re-page the browse pipeline. */
    suspend fun resolveCount(f: BrowserFilterState, ctx: BrowseContext): Long {
        ensureHiddenLoaded()
        if (f.statusFilter.isNotEmpty()) ensureStatusLoaded()
        if (isPlainCount(f, ctx)) return fetchDbCount(f, ctx)
        // Counting must not reroll or mutate the random browse cache.
        val ordered = f.copy(sortField = ClimbSortField.NAME, sortDirection = SortDirection.ASC)
        return countBrowseMatches { offset -> fetchFiltered(ordered, ctx, offset, pageSize = COUNT_PAGE_SIZE) }
    }

    private fun fetchDbCount(f: BrowserFilterState, ctx: BrowseContext): Long {
        val sel = selSizeId(f, ctx)
        val hm = hsmMask(f, ctx)
        if (f.searchQuery.isNotBlank()) {
            return if (f.benchmarkOnly) boardRepository.countBenchmarkSearchClimbs(
                f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.climbTypeFilter, selProductSizeId = sel, hsmExcludedMask = hm,
            ) else boardRepository.countSearchClimbs(
                f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.climbTypeFilter, selProductSizeId = sel, hsmExcludedMask = hm,
            )
        }
        val gb = gradeBounds(f, ctx)
        return if (f.benchmarkOnly) boardRepository.countBenchmarkFilteredClimbs(
            f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter,
            selProductSizeId = sel, hsmExcludedMask = hm, showUngraded = gb.showUngraded,
        ) else boardRepository.countFilteredClimbs(
            f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists, f.climbTypeFilter,
            selProductSizeId = sel, hsmExcludedMask = hm, showUngraded = gb.showUngraded,
        )
    }

    /**
     * Random climb uuid. Plain mode samples the WHOLE catalogue match with a bounded
     * re-roll past ignored climbs (falling back to an ignored one rather than nothing);
     * every other mode picks from the already filtered [loaded] list.
     */
    fun pickRandom(f: BrowserFilterState, ctx: BrowseContext, loaded: List<ClimbWithStats>): String? {
        val plainMode = f.statusFilter.isEmpty() && f.originFilter == OriginFilter.ALL && !f.myClimbsOnly &&
            !f.quantumOverlapFilter.active && !(ctx.holdFilterActive && ctx.holdFilterUuids.isNotEmpty())
        if (!plainMode) return loaded.randomOrNull(random)?.uuid
        ensureHiddenLoaded()
        val randomized = f.copy(sortField = ClimbSortField.RANDOM)
        var result: String? = null
        var fallback: String? = null
        repeat(RANDOM_PICK_MAX_ROLLS) {
            if (result == null) {
                val candidate = pickOne(randomized, ctx)
                if (candidate != null) {
                    fallback = candidate
                    if (candidate !in hiddenUuids) result = candidate
                }
            }
        }
        return result ?: fallback
    }

    private fun pickOne(f: BrowserFilterState, ctx: BrowseContext): String? {
        val sel = selSizeId(f, ctx)
        val hm = hsmMask(f, ctx)
        val rows = if (f.searchQuery.isNotBlank()) {
            boardRepository.searchClimbsByName(
                f.searchQuery, f.angle, f.layoutId, f.boardBrand, f.sortField, f.sortDirection,
                limit = 1, offset = 0, climbType = f.climbTypeFilter, selProductSizeId = sel, hsmExcludedMask = hm,
            )
        } else {
            val gb = gradeBounds(f, ctx)
            boardRepository.searchClimbsSorted(
                f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists,
                f.sortField, f.sortDirection, limit = 1, offset = 0, climbType = f.climbTypeFilter,
                selProductSizeId = sel, hsmExcludedMask = hm, showUngraded = gb.showUngraded,
            )
        }
        return rows.firstOrNull()?.uuid
    }

    /**
     * Hold/zone search: climbs whose frames contain ALL selected holds AND lie fully
     * inside [zone]. Range-only grade predicate, as on Android.
     */
    fun findUuidsMatchingHoldFilter(
        f: BrowserFilterState,
        ctx: BrowseContext,
        selectedHolds: Set<Int>,
        zone: BoardZone?,
        placementXy: Map<Int, Pair<Long, Long>>,
    ): Set<String> {
        if (selectedHolds.isEmpty() && zone == null) return emptySet()
        val gb = gradeBounds(f, ctx)
        val patterns = selectedHolds.map { HoldHeatmapComputer.holdLikePattern(it) }
        val xy = if (zone != null) placementXy else emptyMap()
        return boardRepository.getAllFramesForHeatmap(
            f.angle, f.layoutId, f.boardBrand, gb.minDiff, gb.maxDiff, f.minAscensionists,
            f.climbTypeFilter, hsmExcludedMask = hsmMask(f, ctx),
        ).asSequence()
            .filter { row -> patterns.all { row.frames.contains(it) } }
            .filter { row -> zone == null || BoardZoneFilter.climbInZone(row.frames, xy, zone) }
            .map { it.uuid }
            .toSet()
    }
}
