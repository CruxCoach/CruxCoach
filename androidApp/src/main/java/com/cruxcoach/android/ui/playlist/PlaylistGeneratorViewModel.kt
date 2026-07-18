package com.cruxcoach.android.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.android.util.safeLaunch
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.ClimbSortField
import com.cruxcoach.data.repository.ClimbTypeFilter
import com.cruxcoach.data.repository.NewListPlaybackStep
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
import com.cruxcoach.domain.playlist.CandidateSelection
import com.cruxcoach.domain.playlist.CandidateSource
import com.cruxcoach.domain.playlist.GeneratedEntry
import com.cruxcoach.domain.playlist.GeneratorType
import com.cruxcoach.domain.playlist.LogbookProfile
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
            profile = withContext(Dispatchers.IO) { loadProfile(snapshot.angle) }
            _state.update {
                it.copy(
                    angle = snapshot.angle,
                    angleAdjustable = snapshot.boardBrand != "moonboard",
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
    private fun loadProfile(angle: Int): LogbookProfile {
        val all = personalBoardRepo.getUserAscentsAll()
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

        val recencyCutoff = java.time.LocalDate.now()
            .minusWeeks(PROFILE_RECENCY_WEEKS)
            .toString()
        val recent = anglePool.filter { it.climbedAt.take(10) >= recencyCutoff }
        val pool = if (recent.size >= LogbookProfile.MIN_SAMPLE) recent else anglePool

        // First-contact check runs on the FULL history (a prior attempt
        // outside the pool still disqualifies a flash inside it).
        val flashUuids = com.cruxcoach.android.ui.board.BoardStatsComputer.trueFlashUuids(all)
        val sends = pool.mapNotNull { it.difficultyAverage }
        val flashes = pool.filter { it.uuid in flashUuids }.mapNotNull { it.difficultyAverage }

        val sentUuids = personalBoardRepo.getUserSentClimbUuids()
        val openProjects = personalBoardRepo.getRawBidsForUser()
            .filter { it.climbUuid !in sentUuids }
            .groupBy { it.climbUuid }
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

        return LogbookProfile.fromLogbook(sends, flashes, openProjects)
    }

    fun setType(type: GeneratorType) {
        _state.update { it.copy(type = type) }
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
            profile = withContext(Dispatchers.IO) { loadProfile(_state.value.angle) }
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
            val result = withContext(Dispatchers.IO) {
                val ignored = personalBoardRepo.getIgnoredClimbUuids()
                val sent = personalBoardRepo.getUserSentClimbUuids()
                val attempted = personalBoardRepo.getUserAttemptedClimbUuids()
                // Fresh-stimulus bias: anything logged in the last ~2 weeks
                // ranks behind untouched material of equal quality.
                val recentCutoff = java.time.LocalDate.now()
                    .minusDays(RECENT_REPEAT_DAYS)
                    .toString()
                val recentUuids = personalBoardRepo.getUserAscentsAll()
                    .asSequence()
                    .filter { it.climbedAt.take(10) >= recentCutoff }
                    .map { it.climbUuid }
                    .toSet()

                val source = CandidateSource { minDiff, maxDiff ->
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

                val filled = PlaylistFiller.fill(
                    plan = plan,
                    source = source,
                    openProjects = profile.openProjectUuids,
                    selection = params.selection,
                    random = Random(System.currentTimeMillis()),
                )
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
            if (result == null) {
                _state.update { it.copy(isGenerating = false, error = true) }
            } else {
                _state.update {
                    it.copy(isGenerating = false, createdListId = result.first, droppedClimbs = result.second)
                }
            }
        }
    }

    fun consumeCreatedList() {
        _state.update { it.copy(createdListId = null) }
    }

    companion object {
        private const val TAG = "PlaylistGeneratorVM"

        /** Max/flash anchor window (~6 months): current ability, not the
         *  all-time best — falls back to the full logbook below
         *  [LogbookProfile.MIN_SAMPLE] recent sends. */
        private const val PROFILE_RECENCY_WEEKS = 26L

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
    }
}
