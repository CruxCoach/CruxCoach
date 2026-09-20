package com.cruxcoach.app.playlist

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.browse.ClimbStatusFilter
import com.cruxcoach.app.browse.OriginFilter
import com.cruxcoach.app.browse.parseStatusFilter
import com.cruxcoach.app.logbook.BoardStatsComputer
import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import com.cruxcoach.app.settings.GradeScale
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.ClimbWithStats
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.playlist.CandidateSelection
import com.cruxcoach.domain.playlist.CandidateSource
import com.cruxcoach.domain.playlist.GeneratedEntry
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.domain.playlist.LoggedSend
import com.cruxcoach.domain.playlist.LogbookProfile
import com.cruxcoach.domain.playlist.PlanSection
import com.cruxcoach.domain.playlist.PlanSlot
import com.cruxcoach.domain.playlist.PlaylistCandidate
import com.cruxcoach.domain.playlist.PlaylistFiller
import com.cruxcoach.domain.playlist.PlaylistGeneratorParams
import com.cruxcoach.domain.playlist.PlaylistPlan
import com.cruxcoach.domain.playlist.PlaylistPlanner
import com.cruxcoach.domain.playlist.PyramidShape
import com.cruxcoach.domain.playlist.SessionPosition
import com.cruxcoach.domain.playlist.TrainingRanges
import com.cruxcoach.domain.playlist.estimatedMinutes
import com.cruxcoach.domain.playlist.structureRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlin.random.Random

data class GeneratorState(
    val type: GeneratorType = GeneratorType.PYRAMID,
    val durationMinutes: Int = 60,
    val selection: CandidateSelection = CandidateSelection.NEW,
    val pyramidShape: PyramidShape = PyramidShape.ASCENDING,
    val pyramidClimbsPerTier: Int = 2,
    /** Recommended from the logbook plan until the climber edits it. */
    val targetMinDifficulty: Double? = null,
    val targetMaxDifficulty: Double? = null,
    val gradeRangeCustomized: Boolean = false,
    /** Manual only — 0 means "seed me from the profile on first load". */
    val manualMinDifficulty: Double = 0.0,
    val manualMaxDifficulty: Double = 0.0,
    val manualRepeats: Int = 1,
    val problemsPerSet: Int = TrainingRanges.PE_PROBLEMS_PER_SET,
    val manualRestSeconds: Int = TrainingRanges.MANUAL_DEFAULT_REST,
    val manualRepeatRestSeconds: Int = TrainingRanges.MANUAL_DEFAULT_REPEAT_REST,
    /** What the size control sets: problems, projects, sets or tiers. */
    val structureSize: Int = GeneratorType.PYRAMID.structureRange().first,
    val position: SessionPosition = SessionPosition.START_COLD,
    val angle: Int = 40,
    /** MoonBoard walls are fixed-angle — hide the angle control. */
    val angleAdjustable: Boolean = true,
    val boardBrand: String = "kilter",
    val layoutId: Int = 0,
    val productSizeId: Int = 0,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    val browserMinDifficulty: Double = TrainingRanges.MIN_DIFFICULTY,
    val browserMaxDifficulty: Double = TrainingRanges.MAX_DIFFICULTY,
    val minAscensionists: Int = 0,
    val benchmarkOnly: Boolean = false,
    val originFilter: OriginFilter = OriginFilter.ALL,
    val statusFilter: Set<ClimbStatusFilter> = emptySet(),
    val climbType: ClimbTypeFilter = ClimbTypeFilter.BOULDER,
    /** Live plan preview, recomputed on every parameter change. */
    val plan: PlaylistPlan? = null,
    val estimatedMinutes: Int = 0,
    val maxGradeLabel: String? = null,
    val flashGradeLabel: String? = null,
    val profilePersonalized: Boolean = false,
    val isGenerating: Boolean = false,
    /** Set after a successful generate — the screen navigates to it. */
    val createdListId: Long? = null,
    /** Post-generate feedback: slots dropped for lack of candidates. */
    val droppedClimbs: Int = 0,
    val error: Boolean = false,
)

/** Aurora product-size ids have no meaning for MoonBoard catalogue rows. */
internal fun playlistProductSizeFilter(boardBrand: String, productSizeId: Int): Int =
    if (BoardBrand.fromWire(boardBrand).usesAuroraPlacements) productSizeId else 0

internal fun playlistCandidateMatchesBrowserFilters(
    origin: String,
    benchmarkDifficulty: Double,
    uuid: String,
    sent: Set<String>,
    attempted: Set<String>,
    benchmarkOnly: Boolean,
    originFilter: String,
    statusFilter: String,
    source: String = "kilter",
): Boolean {
    if (benchmarkOnly && benchmarkDifficulty <= 0.0) return false
    val originMatches = when (
        runCatching { OriginFilter.valueOf(originFilter) }.getOrDefault(OriginFilter.ALL)
    ) {
        OriginFilter.ALL -> true
        OriginFilter.CRUXCOACH -> origin == "cruxcoach" || source == "local"
        OriginFilter.KILTER -> origin in setOf("kilter", "quantum") && source != "local"
        OriginFilter.BOARDSESH -> origin == "boardsesh"
    }
    if (!originMatches) return false
    val statuses = parseStatusFilter(statusFilter)
    if (statuses.isEmpty()) return true
    return (ClimbStatusFilter.SENT in statuses && uuid in sent) ||
        (ClimbStatusFilter.ATTEMPTED in statuses && uuid in attempted) ||
        (ClimbStatusFilter.NEW in statuses && uuid !in sent && uuid !in attempted)
}

internal fun playlistCandidatesInBand(
    candidates: List<PlaylistCandidate>,
    minDifficulty: Double,
    maxDifficulty: Double,
    targetMinDifficulty: Double?,
    targetMaxDifficulty: Double?,
    browserMinDifficulty: Double,
    browserMaxDifficulty: Double,
    limit: Int,
): List<PlaylistCandidate> {
    val low = maxOf(
        minDifficulty,
        targetMinDifficulty ?: TrainingRanges.MIN_DIFFICULTY,
        browserMinDifficulty,
    )
    val high = minOf(
        maxDifficulty,
        targetMaxDifficulty ?: TrainingRanges.MAX_DIFFICULTY,
        browserMaxDifficulty,
    )
    if (low > high || limit <= 0) return emptyList()
    return candidates.asSequence()
        .filter { it.difficulty in low..high }
        .take(limit)
        .toList()
}

/**
 * Port of Android `PlaylistGeneratorViewModel`: reads the logbook into a
 * [LogbookProfile], plans a session skeleton with [PlaylistPlanner], fills it
 * from the catalogue with [PlaylistFiller] and persists the result as a climb
 * list with its playback steps.
 *
 * The planning and filling logic itself is shared with Android — this class
 * only gathers the inputs and writes the outcome.
 */
class GeneratorPresenter(
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val preferences: BrowsePreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Deterministic in tests; time-seeded in the app. */
    private val randomSeed: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow(GeneratorState())
    val state: StateFlow<GeneratorState> = _state.asStateFlow()

    private var profile: LogbookProfile = LogbookProfile(null, null, 0)
    private var profileRequest = 0

    init {
        scope.launch {
            val board = withContext(ioDispatcher) { preferences.boardSelection() }
            val filter = withContext(ioDispatcher) { preferences.loadFilter() }
            val brand = BoardBrand.fromWire(board.boardBrand)
            // Publish the selected board before the slower catalogue/logbook
            // enrichment, so touching a control never plans against layoutId=0.
            _state.update {
                it.copy(
                    angle = board.angle,
                    angleAdjustable = board.boardBrand != "moonboard",
                    structureSize = it.type.structureRange().midpoint(),
                    boardBrand = board.boardBrand,
                    layoutId = board.layoutId,
                    productSizeId = playlistProductSizeFilter(
                        board.boardBrand, board.productSizeId,
                    ),
                    gradeScale = if (preferences.frenchGrades()) {
                        GradeScale.FRENCH
                    } else GradeScale.V_SCALE,
                    browserMinDifficulty = TrainingRanges.MIN_DIFFICULTY + filter.minGradeIndex,
                    browserMaxDifficulty = TrainingRanges.MIN_DIFFICULTY + filter.maxGradeIndex,
                    minAscensionists = filter.minAscensionists,
                    benchmarkOnly = brand.supportsBenchmarkFilter && filter.benchmarkOnly,
                    originFilter = if (!brand.supportsBoardSeshOrigin &&
                        filter.originFilter == OriginFilter.BOARDSESH
                    ) OriginFilter.ALL else filter.originFilter,
                    statusFilter = filter.statusFilter,
                    climbType = if (brand.supportsClimbTypeFilter) {
                        filter.climbTypeFilter
                    } else ClimbTypeFilter.BOULDER,
                )
            }
            refreshPlan()
            refreshProfile(++profileRequest)
        }
    }

    /**
     * Logbook → profile, three-stage pool selection exactly as Android:
     * angle (exact, then ±[NEARBY_ANGLE_TOLERANCE]°, then everything),
     * recency window, and true flashes only.
     */
    private fun loadProfile(angle: Int, boardBrand: String, layoutId: Int): LogbookProfile {
        val everything = personalBoardRepo.getUserLogbookAllLight()
        // A grade is not a grade across board families, so the profile stays
        // on the selected brand. Rows with no layout are kept: the Aurora
        // migration writes none.
        val all = everything.filter {
            it.boardBrand == boardBrand &&
                (layoutId == 0 || it.layoutId?.toInt()?.equals(layoutId) != false)
        }
        val allSends = all.filter { it.isSend }

        val exact = allSends.filter { it.angle.toInt() == angle }
        val near = allSends.filter {
            kotlin.math.abs(it.angle.toInt() - angle) <= NEARBY_ANGLE_TOLERANCE
        }
        val anglePool = when {
            exact.size >= LogbookProfile.MIN_SAMPLE -> exact
            near.size >= LogbookProfile.MIN_SAMPLE -> near
            else -> allSends
        }

        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        val recencyCutoffs = PROFILE_RECENCY_WINDOWS_MONTHS.map {
            today.minus(DatePeriod(months = it)).toString()
        }

        // First-contact check runs on the FULL history: a prior attempt
        // outside the pool still disqualifies a flash inside it.
        val flashUuids = BoardStatsComputer.trueFlashUuids(all)
        // Ascents logged by the app carry the community grade; the Aurora
        // migration writes none, so fill those gaps from the catalogue.
        val missing = all.filter { it.difficultyAverage == null }.map { it.climbUuid }.distinct()
        val catalogueDifficulty: Map<String, Double> =
            if (missing.isEmpty()) emptyMap()
            else boardRepository.getClimbsByUuids(missing, angle)
                .mapNotNull { c -> c.difficultyAverage?.let { c.uuid to it } }
                .toMap()

        fun List<AscentWithClimb>.toSends() = mapNotNull { row ->
            val diff = row.difficultyAverage ?: catalogueDifficulty[row.climbUuid]
            diff?.let { LoggedSend(row.climbUuid, it, row.climbedAt) }
        }
        val sends = anglePool.toSends()
        // trueFlashUuids keys on the ASCENT row's uuid, not the climb's.
        val flashes = anglePool.filter { it.uuid in flashUuids }.toSends()

        val sentUuids = allSends.asSequence().map { it.climbUuid }.toSet()
        val openProjects = all.asSequence()
            .filter { !it.isSend }
            .filter { it.climbUuid !in sentUuids }
            .groupBy { it.climbUuid }
            // A project is something you went back to: projects may be planned
            // past the safety ceiling, so the choice has to be evident.
            .filter { (_, tries) -> tries.sumOf { it.bidCount } >= MIN_PROJECT_ATTEMPTS }
            .map { (uuid, tries) ->
                Triple(
                    uuid,
                    tries.any { it.angle.toInt() == angle },
                    tries.sumOf { it.bidCount } to tries.maxOf { it.climbedAt },
                )
            }
            .sortedWith(
                compareByDescending<Triple<String, Boolean, Pair<Long, String>>> { it.second }
                    .thenByDescending { it.third.first }
                    .thenByDescending { it.third.second }
            )
            .map { it.first }
            .toList()

        return LogbookProfile.fromLogbook(sends, flashes, openProjects, recencyCutoffs)
    }

    fun watch(onState: (GeneratorState) -> Unit): StateWatch = scope.watchState(state, onState)

    fun setType(type: GeneratorType) {
        _state.update {
            // Each type counts something else — re-seat the size on the new
            // type's midpoint rather than carry a number that meant something
            // different a moment ago.
            val seeded = if (type == GeneratorType.MANUAL && it.manualMinDifficulty == 0.0) {
                val anchor = profile.effectiveRepeatableMax
                it.copy(
                    manualMinDifficulty = anchor - TrainingRanges.MANUAL_SEED_HALF_BAND,
                    manualMaxDifficulty = anchor + TrainingRanges.MANUAL_SEED_HALF_BAND,
                )
            } else it
            seeded.copy(
                type = type,
                structureSize = type.structureRange().midpoint(),
                targetMinDifficulty = null,
                targetMaxDifficulty = null,
                gradeRangeCustomized = false,
            )
        }
        refreshPlan()
    }

    fun setDuration(minutes: Int) {
        _state.update {
            it.copy(
                durationMinutes = minutes.coerceIn(
                    TrainingRanges.MIN_DURATION_MINUTES,
                    TrainingRanges.MAX_DURATION_MINUTES,
                )
            )
        }
        refreshPlan()
    }

    fun setManualRange(low: Double, high: Double) {
        _state.update {
            it.copy(
                manualMinDifficulty = low.coerceIn(
                    TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY,
                ),
                manualMaxDifficulty = high.coerceIn(low, TrainingRanges.MAX_DIFFICULTY),
            )
        }
        refreshPlan()
    }

    fun setProblemsPerSet(count: Int) {
        _state.update {
            it.copy(problemsPerSet = count.coerceIn(TrainingRanges.PE_PROBLEMS_PER_SET_RANGE))
        }
        refreshPlan()
    }

    fun setManualRepeats(repeats: Int) {
        _state.update { it.copy(manualRepeats = repeats.coerceIn(TrainingRanges.MANUAL_REPEATS)) }
        refreshPlan()
    }

    fun setManualRest(seconds: Int) {
        _state.update {
            it.copy(manualRestSeconds = seconds.coerceIn(TrainingRanges.MANUAL_REST_SECONDS))
        }
        refreshPlan()
    }

    fun setManualRepeatRest(seconds: Int) {
        _state.update {
            it.copy(manualRepeatRestSeconds = seconds.coerceIn(TrainingRanges.MANUAL_REST_SECONDS))
        }
        refreshPlan()
    }

    fun setStructureSize(size: Int) {
        _state.update { it.copy(structureSize = size.coerceIn(it.type.structureRange())) }
        refreshPlan()
    }

    fun setPyramidShape(shape: PyramidShape) {
        _state.update { it.copy(pyramidShape = shape) }
        refreshPlan()
    }

    fun setPyramidClimbsPerTier(count: Int) {
        _state.update {
            it.copy(pyramidClimbsPerTier = count.coerceIn(TrainingRanges.PYRAMID_CLIMBS_PER_TIER))
        }
        refreshPlan()
    }

    fun setTargetRange(low: Double, high: Double) {
        _state.update {
            it.copy(
                targetMinDifficulty = low.coerceIn(
                    TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY,
                ),
                targetMaxDifficulty = high.coerceIn(low, TrainingRanges.MAX_DIFFICULTY),
                gradeRangeCustomized = true,
            )
        }
        refreshPlan()
    }

    fun useRecommendedRange() {
        _state.update {
            it.copy(
                targetMinDifficulty = null,
                targetMaxDifficulty = null,
                gradeRangeCustomized = false,
            )
        }
        refreshPlan()
    }

    fun setBrowserGradeRange(low: Double, high: Double) {
        _state.update {
            it.copy(
                browserMinDifficulty = low.coerceIn(
                    TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY,
                ),
                browserMaxDifficulty = high.coerceIn(low, TrainingRanges.MAX_DIFFICULTY),
            )
        }
    }

    fun setMinAscensionists(count: Int) {
        _state.update { it.copy(minAscensionists = count.coerceIn(0, 50)) }
    }

    fun setBenchmarkOnly(enabled: Boolean) {
        _state.update { it.copy(benchmarkOnly = enabled) }
    }

    fun setStatusFilter(statuses: Set<ClimbStatusFilter>) {
        _state.update { it.copy(statusFilter = statuses) }
    }

    fun setOriginFilter(origin: OriginFilter) {
        _state.update { it.copy(originFilter = origin) }
    }

    fun setClimbType(type: ClimbTypeFilter) {
        _state.update { it.copy(climbType = type) }
    }

    fun setSelection(selection: CandidateSelection) {
        _state.update { it.copy(selection = selection) }
    }

    fun setPosition(position: SessionPosition) {
        _state.update { it.copy(position = position) }
        refreshPlan()
    }

    fun setAngle(angle: Int) {
        if (!_state.value.angleAdjustable) return
        _state.update { it.copy(angle = angle.coerceIn(0, 70)) }
        val request = ++profileRequest
        refreshPlan()
        scope.launch { refreshProfile(request) }
    }

    private suspend fun refreshProfile(request: Int) {
        val selection = _state.value
        val loadedProfile = try {
            withContext(ioDispatcher) {
                val range = loadBoardGradeRange(
                    selection.angle, selection.boardBrand, selection.layoutId,
                    selection.productSizeId,
                )
                loadProfile(selection.angle, selection.boardBrand, selection.layoutId)
                    .adaptedToBoardGrades(range.first, range.second)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A thin or unreadable logbook must not block planning: the
            // default profile stands in and the header says so.
            return
        }
        // Catalogue queries may finish out of order after rapid angle changes.
        if (request != profileRequest) return
        profile = loadedProfile
        _state.update {
            it.copy(
                maxGradeLabel = loadedProfile.maxDifficulty?.let { difficulty ->
                    BoardStatsComputer.formatDifficulty(difficulty, it.gradeScale)
                },
                flashGradeLabel = loadedProfile.flashDifficulty?.let { difficulty ->
                    BoardStatsComputer.formatDifficulty(difficulty, it.gradeScale)
                },
                profilePersonalized = loadedProfile.isPersonalized,
            )
        }
        refreshPlan()
    }

    /** Lowest/highest graded climb that physically fits this board setup. */
    private fun loadBoardGradeRange(
        angle: Int,
        boardBrand: String,
        layoutId: Int,
        productSizeId: Int,
    ): Pair<Double?, Double?> {
        fun edge(direction: SortDirection) = boardRepository.searchClimbsSorted(
            angle = angle,
            layoutId = layoutId,
            boardBrand = boardBrand,
            minDifficulty = TrainingRanges.MIN_DIFFICULTY,
            maxDifficulty = TrainingRanges.MAX_DIFFICULTY,
            minAscensionists = 0,
            sortField = ClimbSortField.DIFFICULTY,
            sortDirection = direction,
            limit = 1,
            climbType = ClimbTypeFilter.BOULDER,
            selProductSizeId = productSizeId,
        ).firstOrNull()?.difficultyAverage
        return edge(SortDirection.ASC) to edge(SortDirection.DESC)
    }

    private fun currentParams(includeTargetRange: Boolean = true): PlaylistGeneratorParams {
        val s = _state.value
        return PlaylistGeneratorParams(
            type = s.type,
            durationMinutes = s.durationMinutes,
            position = s.position,
            selection = s.selection,
            angle = s.angle,
            boardBrand = s.boardBrand,
            layoutId = s.layoutId,
            productSizeId = s.productSizeId,
            pyramidShape = s.pyramidShape,
            targetMinDifficulty = if (includeTargetRange) s.targetMinDifficulty else null,
            targetMaxDifficulty = if (includeTargetRange) s.targetMaxDifficulty else null,
            pyramidClimbsPerTier = s.pyramidClimbsPerTier,
            structureSize = s.structureSize,
            manualMinDifficulty = s.manualMinDifficulty,
            manualMaxDifficulty = s.manualMaxDifficulty,
            manualRepeats = s.manualRepeats,
            problemsPerSet = s.problemsPerSet,
            manualRestSeconds = s.manualRestSeconds,
            manualRepeatRestSeconds = s.manualRepeatRestSeconds,
            minAscensionists = s.minAscensionists,
            browserMinDifficulty = s.browserMinDifficulty,
            browserMaxDifficulty = s.browserMaxDifficulty,
            benchmarkOnly = s.benchmarkOnly,
            originFilter = s.originFilter.name,
            statusFilter = s.statusFilter.joinToString(",") { it.name },
            climbType = s.climbType.name,
        )
    }

    private fun refreshPlan() {
        if (!_state.value.gradeRangeCustomized) {
            val recommended =
                PlaylistPlanner.plan(currentParams(includeTargetRange = false), profile)
            val work = recommended.slots.filterIsInstance<PlanSlot.ClimbSlot>()
                .filter { it.section != PlanSection.WARM_UP }
            if (work.isNotEmpty()) {
                _state.update {
                    it.copy(
                        targetMinDifficulty = work.minOf { slot -> slot.minDifficulty }
                            .coerceIn(TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY),
                        targetMaxDifficulty = work.maxOf { slot -> slot.maxDifficulty }
                            .coerceIn(TrainingRanges.MIN_DIFFICULTY, TrainingRanges.MAX_DIFFICULTY),
                    )
                }
            }
        }
        val plan = PlaylistPlanner.plan(currentParams(), profile)
        _state.update { it.copy(plan = plan, estimatedMinutes = plan.estimatedMinutes()) }
    }

    /** Runs the filler against the live catalogue and persists the result. */
    fun generate(name: String) {
        val plan = _state.value.plan ?: return
        val params = currentParams()
        if (_state.value.isGenerating) return
        _state.update { it.copy(isGenerating = true, error = false) }
        scope.launch {
            try {
                val result = withContext(ioDispatcher) { fill(plan, params, name) }
                if (result == null) {
                    _state.update { it.copy(isGenerating = false, error = true) }
                } else {
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            createdListId = result.first,
                            droppedClimbs = result.second,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isGenerating = false, error = true) }
            }
        }
    }

    /** The blocking half of [generate]; null when nothing could be filled. */
    private fun fill(
        plan: PlaylistPlan,
        params: PlaylistGeneratorParams,
        name: String,
    ): Pair<Long, Int>? {
        val ignored = boardRepository.canonicalizeClimbUuids(
            personalBoardRepo.getIgnoredClimbUuids()
        )
        val logbook = personalBoardRepo.getUserLogbookAllLight()
        val sent = boardRepository.canonicalizeClimbUuids(
            logbook.asSequence().filter { it.isSend }.map { it.climbUuid }.toSet()
        )
        val attempted = boardRepository.canonicalizeClimbUuids(
            logbook.asSequence().filter { !it.isSend }.map { it.climbUuid }.toSet()
        ) - sent
        // Fresh-stimulus bias: anything logged in the last ~2 weeks ranks
        // behind untouched material of equal quality.
        val recentCutoff = Clock.System.todayIn(TimeZone.currentSystemDefault())
            .minus(DatePeriod(days = RECENT_REPEAT_DAYS))
            .toString()
        val recentUuids = boardRepository.canonicalizeClimbUuids(
            logbook.asSequence()
                .filter { it.climbedAt.take(10) >= recentCutoff }
                .map { it.climbUuid }
                .toSet()
        )

        val climbType = runCatching { ClimbTypeFilter.valueOf(params.climbType) }
            .getOrDefault(ClimbTypeFilter.BOULDER)

        fun matches(climb: ClimbWithStats) = climb.uuid !in ignored &&
            playlistCandidateMatchesBrowserFilters(
                origin = climb.origin,
                benchmarkDifficulty = climb.benchmarkDifficulty,
                uuid = climb.uuid,
                sent = sent,
                attempted = attempted,
                benchmarkOnly = params.benchmarkOnly,
                originFilter = params.originFilter,
                statusFilter = params.statusFilter,
                source = climb.source,
            )

        fun loadCandidateSnapshot(
            minDifficulty: Double,
            maxDifficulty: Double,
            limit: Int,
        ): List<PlaylistCandidate> {
            if (minDifficulty > maxDifficulty) return emptyList()
            return boardRepository.searchClimbsSorted(
                angle = params.angle,
                layoutId = params.layoutId,
                boardBrand = params.boardBrand,
                minDifficulty = minDifficulty,
                maxDifficulty = maxDifficulty,
                minAscensionists = params.minAscensionists,
                sortField = ClimbSortField.DIFFICULTY,
                sortDirection = SortDirection.ASC,
                limit = limit,
                climbType = climbType,
                selProductSizeId = params.productSizeId,
            ).filter(::matches).mapNotNull { climb ->
                climb.difficultyAverage?.let { diff ->
                    PlaylistCandidate(
                        climbUuid = climb.uuid,
                        difficulty = diff,
                        quality = climb.qualityAverage,
                        ascensionistCount = climb.ascensionistCount,
                        sent = climb.uuid in sent,
                        attempted = climb.uuid in attempted,
                        recentlyTried = climb.uuid in recentUuids,
                    )
                }
            }
        }

        val overallLow = maxOf(
            params.targetMinDifficulty ?: TrainingRanges.MIN_DIFFICULTY,
            params.browserMinDifficulty,
        )
        val overallHigh = minOf(
            params.targetMaxDifficulty ?: TrainingRanges.MAX_DIFFICULTY,
            params.browserMaxDifficulty,
        )
        // Query each distinct planned band once: a large board's easiest grade
        // can consume a single broad query's whole row limit before the upper
        // tiers ever appear.
        val plannedCandidates = plan.slots
            .filterIsInstance<PlanSlot.ClimbSlot>()
            .map { slot ->
                maxOf(slot.minDifficulty, overallLow) to minOf(slot.maxDifficulty, overallHigh)
            }
            .filter { (low, high) -> low <= high }
            .distinct()
            .flatMap { (low, high) -> loadCandidateSnapshot(low, high, CANDIDATE_POOL_SIZE) }
        // Real board distribution for last-resort grade adaptation.
        val boardCandidates = loadCandidateSnapshot(overallLow, overallHigh, BOARD_GRADE_POOL_SIZE)
        val candidateSnapshot = (plannedCandidates + boardCandidates).distinctBy { it.climbUuid }

        // Widening is cheap and deterministic over the immutable snapshot;
        // browser, logbook and ignored filters were applied exactly once above.
        val source = CandidateSource { minDiff, maxDiff ->
            playlistCandidatesInBand(
                candidates = candidateSnapshot,
                minDifficulty = minDiff,
                maxDifficulty = maxDiff,
                targetMinDifficulty = params.targetMinDifficulty,
                targetMaxDifficulty = params.targetMaxDifficulty,
                browserMinDifficulty = params.browserMinDifficulty,
                browserMaxDifficulty = params.browserMaxDifficulty,
                limit = CANDIDATE_POOL_SIZE,
            )
        }

        val filled = PlaylistFiller.fill(
            plan = plan,
            source = source,
            openProjects = profile.openProjectUuids,
            // Resolve projects by uuid: they may sit outside the planned bands
            // and therefore not be present in the bounded snapshot.
            projectCandidates = boardRepository
                .getClimbsByUuids(profile.openProjectUuids, params.angle)
                .filter { climb ->
                    val diff = climb.difficultyAverage
                    diff != null &&
                        diff >= params.browserMinDifficulty &&
                        diff <= params.browserMaxDifficulty &&
                        diff >= (params.targetMinDifficulty ?: TrainingRanges.MIN_DIFFICULTY) &&
                        diff <= (params.targetMaxDifficulty ?: TrainingRanges.MAX_DIFFICULTY) &&
                        (climb.ascensionistCount ?: 0) >= params.minAscensionists &&
                        matches(climb)
                }
                .mapNotNull { climb ->
                    climb.difficultyAverage?.let { diff ->
                        PlaylistCandidate(
                            climbUuid = climb.uuid,
                            difficulty = diff,
                            quality = climb.qualityAverage,
                            ascensionistCount = climb.ascensionistCount,
                            sent = false,
                            attempted = true,
                        )
                    }
                },
            boardCandidates = boardCandidates,
            selection = params.selection,
            random = Random(randomSeed()),
        )
        if (filled.entries.none { it is GeneratedEntry.Climb }) return null

        val listId = personalBoardRepo.createClimbList(name, params.toJson())
        personalBoardRepo.replacePlaybackSteps(
            listId,
            filled.entries.map { entry ->
                when (entry) {
                    is GeneratedEntry.Climb -> NewListPlaybackStep(
                        climbUuid = entry.climbUuid,
                        angle = params.angle.toLong(),
                    )
                    is GeneratedEntry.Rest -> NewListPlaybackStep(
                        climbUuid = null,
                        restSeconds = entry.seconds.toLong(),
                    )
                }
            },
        )
        return listId to filled.droppedClimbs
    }

    fun consumeCreatedList() {
        _state.update { it.copy(createdListId = null, droppedClimbs = 0) }
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        /** Anchor windows, newest first: current ability, not all-time best. */
        private val PROFILE_RECENCY_WINDOWS_MONTHS = listOf(12, 24)

        /** Climbs logged within this window rank behind untouched material. */
        private const val RECENT_REPEAT_DAYS = 14

        /** Middle angle tier: ±10° of the target. */
        private const val NEARBY_ANGLE_TOLERANCE = 10

        private const val CANDIDATE_POOL_SIZE = 120
        private const val BOARD_GRADE_POOL_SIZE = 1000

        /** Bids across all sessions before a climb counts as a project. */
        private const val MIN_PROJECT_ATTEMPTS = 3L
    }
}

/** Where a fresh size control starts: the middle of what the type offers. */
private fun IntRange.midpoint(): Int = first + (last - first) / 2
