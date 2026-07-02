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
import com.cruxcoach.data.repository.NewPlaylistEntry
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.SortDirection
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
     * Logbook → profile. Prefers ascents at the target [angle] when there
     * are enough of them (angle-specific strength differs a lot); falls
     * back to the whole logbook otherwise. Open projects = attempted but
     * never sent, most recent bid first.
     */
    private fun loadProfile(angle: Int): LogbookProfile {
        val ascents = personalBoardRepo.getUserAscentsAll().filter { it.isSend }
        val atAngle = ascents.filter { it.angle.toInt() == angle }
        val pool = if (atAngle.size >= LogbookProfile.MIN_SAMPLE) atAngle else ascents

        val sends = pool.mapNotNull { it.difficultyAverage }
        val flashes = pool.filter { it.bidCount <= 1L }.mapNotNull { it.difficultyAverage }

        val sentUuids = personalBoardRepo.getUserSentClimbUuids()
        val openProjects = personalBoardRepo.getRawBidsForUser()
            .sortedByDescending { it.climbedAt }
            .map { it.climbUuid }
            .filter { it !in sentUuids }
            .distinct()

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
                            )
                        }
                    }
                }

                val filled = PlaylistFiller.fill(
                    plan = plan,
                    source = source,
                    openProjects = profile.openProjectUuids,
                    random = Random(System.currentTimeMillis()),
                )
                if (filled.entries.none { it is GeneratedEntry.Climb }) return@withContext null

                val listId = personalBoardRepo.createPlaylist(name, params.toJson())
                personalBoardRepo.replacePlaylistEntries(
                    listId,
                    filled.entries.map { entry ->
                        when (entry) {
                            is GeneratedEntry.Climb -> NewPlaylistEntry(
                                climbUuid = entry.climbUuid,
                                angle = params.angle.toLong(),
                            )
                            is GeneratedEntry.Rest -> NewPlaylistEntry(
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

        /** Candidate quality gate: at least a handful of community ascents
         *  so junk climbs don't land in a training plan. Community-sparse
         *  boards still work — the filler widens and the browse fallback
         *  keeps minAsc low. */
        private const val MIN_ASCENSIONISTS = 3
        private const val CANDIDATE_POOL_SIZE = 120
    }
}
