package com.cruxcoach.android.ui.playlist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.android.util.PerfLogger
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.playlist.CandidateSelection
import com.cruxcoach.domain.playlist.PyramidShape
import com.cruxcoach.domain.playlist.structureRange
import com.cruxcoach.domain.playlist.CandidateSource
import com.cruxcoach.domain.playlist.GeneratedEntry
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.domain.playlist.LogbookProfile
import com.cruxcoach.domain.playlist.LoggedSend
import com.cruxcoach.domain.playlist.PlaylistCandidate
import com.cruxcoach.domain.playlist.PlaylistFiller
import com.cruxcoach.domain.playlist.PlaylistGeneratorParams
import com.cruxcoach.domain.playlist.PlaylistPlan
import com.cruxcoach.domain.playlist.PlaylistPlanner
import com.cruxcoach.domain.playlist.SessionPosition
import com.cruxcoach.domain.playlist.TrainingRanges
import com.cruxcoach.domain.playlist.estimatedMinutes
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

data class PlaylistGeneratorState(
    val type: GeneratorType = GeneratorType.PYRAMID,
    val durationMinutes: Int = 60,
    val selection: CandidateSelection = CandidateSelection.NEW,
    /** Pyramid only: build-up, or up and back down. */
    val pyramidShape: PyramidShape = PyramidShape.ASCENDING,
    /** Manual only — 0 means "seed me from the profile on first load". */
    val manualMinDifficulty: Double = 0.0,
    val manualMaxDifficulty: Double = 0.0,
    val manualRepeats: Int = 1,
    val manualRestSeconds: Int = TrainingRanges.MANUAL_DEFAULT_REST,
    val manualRepeatRestSeconds: Int = TrainingRanges.MANUAL_DEFAULT_REPEAT_REST,
    /**
     * What the slider sets: problems, projects, sets or tiers.
     *
     * Seeded from the type's own range — zero is not a session anywhere, and
     * as an initial value it showed "0 tiers" on screen while the planner,
     * seeing no size at all, quietly planned four.
     */
    val structureSize: Int = GeneratorType.PYRAMID.structureRange().first,
    val position: SessionPosition = SessionPosition.START_COLD,
    val angle: Int = 40,
    /** MoonBoard walls are fixed-angle — hide the angle stepper. */
    val angleAdjustable: Boolean = true,
    val boardBrand: String = "kilter",
    val layoutId: Int = 0,
    val productSizeId: Int = 0,
    val gradeScale: GradeScale = GradeScale.FRENCH,
    /** Live plan preview, recomputed on every parameter change. */
    val plan: PlaylistPlan? = null,
    val estimatedMinutes: Int = 0,
    /** Profile summary for the header ("dein Max: 7a · Flash: 6b"). */
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

@HiltViewModel
class PlaylistGeneratorViewModel @Inject constructor(
    private val boardRepository: BoardRepository,
    private val personalBoardRepo: PersonalBoardRepository,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(PlaylistGeneratorState())
    val state = _state.asStateFlow()

    private var profile: LogbookProfile = LogbookProfile(null, null, 0)

    init {
        viewModelScope.safeLaunch(TAG) {
            val snapshot = withContext(Dispatchers.IO) { userPreferences.getBoardFilterSnapshot() }
            val productSizeId = userPreferences.boardProductSizeId.first()
            profile = withContext(Dispatchers.IO) { loadProfile(snapshot.angle, snapshot.boardBrand, snapshot.layoutId) }
            _state.update {
                it.copy(
                    angle = snapshot.angle,
                    angleAdjustable = snapshot.boardBrand != "moonboard",
                    structureSize = it.type.structureRange().midpoint(),
                    boardBrand = snapshot.boardBrand,
                    layoutId = snapshot.layoutId,
                    productSizeId = productSizeId,
                    gradeScale = snapshot.gradeScale,
                    maxGradeLabel = profile.maxDifficulty?.let { d ->
                        GradeDisplayHelper.formatDifficulty(d, snapshot.gradeScale)
                    },
                    flashGradeLabel = profile.flashDifficulty?.let { d ->
                        GradeDisplayHelper.formatDifficulty(d, snapshot.gradeScale)
                    },
                    profilePersonalized = profile.isPersonalized,
                )
            }
            refreshPlan()
        }
    }

    /**
     * Logbook → profile. Three-stage pool selection so the anchors are
     * maximally representative of what the session will actually feel like:
     *
     *  1. ANGLE: exact target angle when it has enough sends, else nearby
     *     angles (± [NEARBY_ANGLE_TOLERANCE]° — steepness-adjacent strength
     *     transfers well), else the whole logbook.
     *  2. RECENCY: within that pool, the last ~6 months when they hold
     *     enough sends — plans must track CURRENT ability, not a stale
     *     all-time best.
     *  3. FLASH TRUTH: the flash anchor only counts TRUE flashes (first
     *     logbook contact per climb+angle, [BoardStatsComputer.trueFlashUuids])
     *     — a repeat send logged first-try must not inflate the volume band.
     *
     * Open projects = attempted but never sent, ranked by target-angle
     * match, then accumulated attempts (the project you keep returning to
     * beats a one-off try), then recency.
     */
    private fun loadProfile(angle: Int, boardBrand: String, layoutId: Int): LogbookProfile {
        // One light combined snapshot replaces three full-table reads. In
        // particular, getUserAscentsAll() includes every climb_frames blob,
        // even though profile generation only needs logbook metadata.
        val everything = personalBoardRepo.getUserLogbookAllLight()
        // Same board first. A grade is not a grade across board families —
        // holds, angle behaviour and community grading all differ — so a
        // MoonBoard 40° send said nothing useful about a Kilter 40° session
        // and was quietly averaged into the anchor anyway.
        val all = everything.filter {
            it.boardBrand == boardBrand && (layoutId == 0 || it.layoutId?.toInt() == layoutId)
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

        // The windows themselves; the profile picks the first one holding
        // enough sends and widens on its own. Date maths stays up here — the
        // shared module compares ISO strings and needs no calendar.
        val today = java.time.LocalDate.now()
        val recencyCutoffs = PROFILE_RECENCY_WINDOWS_MONTHS.map {
            today.minusMonths(it).toString()
        }

        // First-contact check runs on the FULL history (a prior attempt
        // outside the pool still disqualifies a flash inside it).
        val flashUuids = com.cruxcoach.android.ui.board.BoardStatsComputer.trueFlashUuids(all)
        fun List<com.cruxcoach.data.repository.AscentWithClimb>.toSends() = mapNotNull { row ->
            row.difficultyAverage?.let { LoggedSend(row.climbUuid, it, row.climbedAt) }
        }
        val sends = anglePool.toSends()
        // trueFlashUuids keys on the ASCENT row's uuid, not the climb's — it
        // identifies the first recorded attempt on a climb, and only that row
        // is the flash. Matching it against climbUuid found nothing, so the
        // flash list came out empty and both flash-anchored types silently
        // fell back to deriving one from the max.
        val flashes = anglePool.filter { it.uuid in flashUuids }.toSends()

        val sentUuids = allSends.asSequence().map { it.climbUuid }.toSet()
        val openProjects = all.asSequence()
            .filter { !it.isSend }
            .filter { it.climbUuid !in sentUuids }
            .groupBy { it.climbUuid }
            // A project is something you went back to. One bail on a climb
            // far above your level is not intent, and projects are allowed
            // past the safety ceiling precisely because you chose them —
            // so the choice has to be evident.
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

        return LogbookProfile.fromLogbook(sends, flashes, openProjects, recencyCutoffs)
    }

    fun setType(type: GeneratorType) {
        _state.update {
            // Each type counts something else, and the ranges barely overlap —
            // four 4x4 sets and four volume problems are not the same session.
            // Re-seat on the new type's midpoint rather than carry a number
            // that meant something different a moment ago.
            val seeded = if (type == GeneratorType.MANUAL && it.manualMinDifficulty == 0.0) {
                val anchor = profile.effectiveRepeatableMax
                it.copy(
                    manualMinDifficulty = anchor - TrainingRanges.MANUAL_SEED_HALF_BAND,
                    manualMaxDifficulty = anchor + TrainingRanges.MANUAL_SEED_HALF_BAND,
                )
            } else it
            seeded.copy(type = type, structureSize = type.structureRange().midpoint())
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

    fun setManualRepeats(repeats: Int) {
        _state.update { it.copy(manualRepeats = repeats.coerceIn(TrainingRanges.MANUAL_REPEATS)) }
        refreshPlan()
    }

    fun setManualRest(seconds: Int) {
        _state.update { it.copy(manualRestSeconds = seconds.coerceIn(TrainingRanges.MANUAL_REST_SECONDS)) }
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
        // The shape changes the plan, so the preview has to follow.
        refreshPlan()
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
        viewModelScope.safeLaunch(TAG) {
            // Angle changes the per-angle profile too.
            profile = withContext(Dispatchers.IO) { loadProfile(_state.value.angle, _state.value.boardBrand, _state.value.layoutId) }
            refreshPlan()
        }
    }

    private fun currentParams(): PlaylistGeneratorParams {
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
            structureSize = s.structureSize,
            manualMinDifficulty = s.manualMinDifficulty,
            manualMaxDifficulty = s.manualMaxDifficulty,
            manualRepeats = s.manualRepeats,
            manualRestSeconds = s.manualRestSeconds,
            manualRepeatRestSeconds = s.manualRepeatRestSeconds,
        )
    }

    private fun refreshPlan() {
        val plan = PlaylistPlanner.plan(currentParams(), profile)
        _state.update { it.copy(plan = plan, estimatedMinutes = plan.estimatedMinutes()) }
    }

    /** Runs the filler against the live catalogue and persists the result. */
    fun generate(name: String) {
        val plan = _state.value.plan ?: return
        val params = currentParams()
        if (_state.value.isGenerating) return
        _state.update { it.copy(isGenerating = true, error = false) }
        viewModelScope.safeLaunch(TAG) {
            try {
                val result = PerfLogger.traceSuspend("playlist.generate total") {
                    withContext(Dispatchers.IO) {
                        val ignored = PerfLogger.traceQuery("playlist.ignored") {
                            personalBoardRepo.getIgnoredClimbUuids()
                        }
                        val logbook = PerfLogger.traceQuery("playlist.logbookSnapshot") {
                            personalBoardRepo.getUserLogbookAllLight()
                        }
                        val sent = logbook.asSequence()
                            .filter { it.isSend }
                            .map { it.climbUuid }
                            .toSet()
                        val attempted = logbook.asSequence()
                            .filter { !it.isSend && it.climbUuid !in sent }
                            .map { it.climbUuid }
                            .toSet()
                        // Fresh-stimulus bias: anything logged in the last ~2 weeks
                        // ranks behind untouched material of equal quality.
                        val recentCutoff = java.time.LocalDate.now()
                            .minusDays(RECENT_REPEAT_DAYS)
                            .toString()
                        val recentUuids = logbook.asSequence()
                            .filter { it.climbedAt.take(10) >= recentCutoff }
                            .map { it.climbUuid }
                            .toSet()

                        val source = CandidateSource { minDiff, maxDiff ->
                            PerfLogger.traceQuery("playlist.candidateBand") {
                                boardRepository.searchClimbsSorted(
                                    angle = params.angle,
                                    layoutId = params.layoutId,
                                    boardBrand = params.boardBrand,
                                    minDifficulty = minDiff,
                                    maxDifficulty = maxDiff,
                                    minAscensionists = MIN_ASCENSIONISTS,
                                    sortField = ClimbSortField.QUALITY,
                                    sortDirection = SortDirection.DESC,
                                    limit = CANDIDATE_POOL_SIZE,
                                    climbType = ClimbTypeFilter.BOULDER,
                                    selProductSizeId = params.productSizeId,
                                ).filter { it.uuid !in ignored }.mapNotNull { climb ->
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
                        }

                        val filled = PerfLogger.trace("playlist.fill") {
                            PlaylistFiller.fill(
                                plan = plan,
                                source = source,
                                openProjects = profile.openProjectUuids,
                                // Resolved by uuid: the band query returns the
                                // best 120 by quality, so a project that is
                                // neither popular nor inside the projecting
                                // window never turned up in it.
                                projectCandidates = boardRepository.getClimbsByUuids(
                                    profile.openProjectUuids, params.angle,
                                ).mapNotNull { climb ->
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
                                selection = params.selection,
                                random = Random(System.currentTimeMillis()),
                            )
                        }
                        if (filled.entries.none { it is GeneratedEntry.Climb }) return@withContext null

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
                        listId to filled.droppedClimbs
                    }
                }
                if (result == null) {
                    _state.update { it.copy(isGenerating = false, error = true) }
                } else {
                    _state.update {
                        it.copy(isGenerating = false, createdListId = result.first, droppedClimbs = result.second)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Playlist generation failed", e)
                // safeLaunch contains the process-level failure, but the UI
                // must also leave its spinner and offer a retry.
                _state.update { it.copy(isGenerating = false, error = true) }
            }
        }
    }

    fun consumeCreatedList() {
        _state.update { it.copy(createdListId = null, droppedClimbs = 0) }
    }

    companion object {
        private const val TAG = "PlaylistGeneratorVM"

        /**
         * Anchor windows, newest first: current ability, not the all-time
         * best. [LogbookProfile.anchorOf] takes the first that holds enough
         * sends and falls back to the whole logbook if none does.
         */
        private val PROFILE_RECENCY_WINDOWS_MONTHS = listOf(12L, 24L)

        /** Climbs logged within this window rank behind untouched
         *  material — variety is the stronger training stimulus. */
        private const val RECENT_REPEAT_DAYS = 14L

        /** Middle angle tier: ±10° of the target — strength transfers
         *  well between adjacent steepness before falling back to the
         *  whole logbook. */
        private const val NEARBY_ANGLE_TOLERANCE = 10

        /** Candidate quality gate: at least a handful of community ascents
         *  so junk climbs don't land in a training plan. Community-sparse
         *  boards still work — the filler widens and the browse fallback
         *  keeps minAsc low. */
        private const val MIN_ASCENSIONISTS = 3
        private const val CANDIDATE_POOL_SIZE = 120

        /** Bids across all sessions before a climb counts as a project. */
        private const val MIN_PROJECT_ATTEMPTS = 3L
    }
}

/** Where a fresh slider starts: the middle of what the type offers. */
private fun IntRange.midpoint(): Int = first + (last - first) / 2
