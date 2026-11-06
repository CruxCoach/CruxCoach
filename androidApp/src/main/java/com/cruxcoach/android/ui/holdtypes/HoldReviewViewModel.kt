package com.cruxcoach.android.ui.holdtypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.holdtypes.HoldReviewStore
import com.cruxcoach.android.data.holdtypes.HoldTypeAssets
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.domain.holdtypes.BlindAnswer
import com.cruxcoach.domain.holdtypes.ComputedSnapshot
import com.cruxcoach.domain.holdtypes.Disagreement
import com.cruxcoach.domain.holdtypes.FieldSummary
import com.cruxcoach.domain.holdtypes.HoldReviewSession
import com.cruxcoach.domain.holdtypes.HoldTypeDataset
import com.cruxcoach.domain.holdtypes.HoldTypeEntry
import com.cruxcoach.domain.holdtypes.ReviewDecision
import com.cruxcoach.domain.holdtypes.ReviewField
import com.cruxcoach.domain.holdtypes.disagreements
import com.cruxcoach.domain.holdtypes.summary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ReviewStage { BLIND, DECIDE, RESULTS }

/** The board around one hold: size, its hold-set images and the placements of its layout. */
data class HoldBoardView(
    val boardSize: BoardSize,
    val boardImages: List<BoardImage>,
    val placements: Map<Int, BoardPlacement>,
)

/** The reviewer's selections for the hold on screen, before they are saved. */
data class AnswerDraft(val type: String? = null, val direction: String? = null, val footOnly: String? = null) {
    val complete: Boolean get() = type != null && direction != null && footOnly != null
}

data class HoldReviewUiState(
    val loading: Boolean = true,
    /** True when this build ships no hold types for the board. */
    val missingDataset: Boolean = false,
    val datasetVersion: String = "",
    val stage: ReviewStage = ReviewStage.BLIND,
    val referenceSize: Int = 0,
    val answered: Int = 0,
    /** Index into the reference set (blind) or into [disagreementHolds] (decide). */
    val index: Int = 0,
    val entry: HoldTypeEntry? = null,
    val board: HoldBoardView? = null,
    val draft: AnswerDraft = AnswerDraft(),
    /** The saved blind answer for the hold on screen; non-null means its computed label is shown. */
    val answer: BlindAnswer? = null,
    val disagreementHolds: List<Int> = emptyList(),
    val disagreementsHere: List<Disagreement> = emptyList(),
    val session: HoldReviewSession? = null,
    val summary: List<FieldSummary> = emptyList(),
)

@HiltViewModel
class HoldReviewViewModel @Inject constructor(
    private val assets: HoldTypeAssets,
    private val store: HoldReviewStore,
    private val boardRepository: BoardRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HoldReviewUiState())
    val state: StateFlow<HoldReviewUiState> = _state.asStateFlow()

    private var dataset: HoldTypeDataset? = null
    private var session: HoldReviewSession = HoldReviewSession(BOARD, "")
    private val boardViews = mutableMapOf<Int, HoldBoardView?>()
    /** Saves run one at a time, in the order they were made. */
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { assets.dataset(BOARD) }
            if (loaded == null) {
                _state.update { it.copy(loading = false, missingDataset = true) }
                return@launch
            }
            dataset = loaded
            session = withContext(Dispatchers.IO) { store.load(BOARD) } ?: HoldReviewSession(BOARD, loaded.version)
            val reference = loaded.referenceSet
            val firstOpen = reference.indexOfFirst { session.answerFor(it) == null }
            if (firstOpen >= 0) showBlind(firstOpen) else showStageAfterBlind()
        }
    }

    private fun reference(): List<Int> = dataset?.referenceSet.orEmpty()

    private suspend fun boardFor(entry: HoldTypeEntry): HoldBoardView? {
        if (boardViews.containsKey(entry.viewSizeId)) return boardViews[entry.viewSizeId]
        val view = withContext(Dispatchers.IO) {
            val size = boardRepository.getProductSize(entry.viewSizeId, BOARD) ?: return@withContext null
            HoldBoardView(
                boardSize = size,
                boardImages = boardRepository.getBoardImages(entry.viewSizeId, entry.layoutId, BOARD),
                placements = boardRepository.getPlacementsForLayout(entry.viewSizeId, entry.layoutId, BOARD)
                    .associateBy { it.placementId.toInt() },
            )
        }
        boardViews[entry.viewSizeId] = view
        return view
    }

    private fun baseState(stage: ReviewStage) = _state.value.copy(
        loading = false,
        datasetVersion = dataset?.version.orEmpty(),
        stage = stage,
        referenceSize = reference().size,
        answered = reference().count { session.answerFor(it) != null },
        session = session,
        disagreementHolds = session.disagreements().map { it.placement }.distinct(),
        summary = session.summary(),
    )

    private fun showBlind(index: Int) {
        val placement = reference().getOrNull(index) ?: return
        val entry = dataset?.get(placement) ?: return
        viewModelScope.launch {
            val board = boardFor(entry)
            val answer = session.answerFor(placement)
            _state.value = baseState(ReviewStage.BLIND).copy(
                index = index, entry = entry, board = board, answer = answer,
                draft = answer?.let { AnswerDraft(it.type, it.direction, it.footOnly) } ?: AnswerDraft(),
                disagreementsHere = emptyList(),
            )
        }
    }

    private fun showDecide(index: Int) {
        val holds = session.disagreements().map { it.placement }.distinct()
        val placement = holds.getOrNull(index) ?: return showResults()
        val entry = dataset?.get(placement) ?: return
        viewModelScope.launch {
            val board = boardFor(entry)
            _state.value = baseState(ReviewStage.DECIDE).copy(
                index = index, entry = entry, board = board, answer = session.answerFor(placement),
                disagreementsHere = session.disagreements().filter { it.placement == placement },
            )
        }
    }

    private fun showResults() {
        _state.value = baseState(ReviewStage.RESULTS).copy(entry = null, board = null, answer = null)
    }

    private fun showStageAfterBlind() {
        val holds = session.disagreements().map { it.placement }.distinct()
        val open = holds.indexOfFirst { p -> session.disagreements().any { it.placement == p && session.decisionFor(p, it.field) == null } }
        if (open >= 0) showDecide(open) else showResults()
    }

    fun selectType(value: String) = editDraft { it.copy(type = value) }
    fun selectDirection(value: String) = editDraft { it.copy(direction = value) }
    fun selectFootOnly(value: String) = editDraft { it.copy(footOnly = value) }

    private fun editDraft(change: (AnswerDraft) -> AnswerDraft) {
        // A shown label is final: changing the answer afterwards would no longer be blind.
        if (_state.value.answer != null) return
        _state.update { it.copy(draft = change(it.draft)) }
    }

    /** Saves the blind answer and reveals the computed label. */
    fun submit() {
        val current = _state.value
        val entry = current.entry ?: return
        val draft = current.draft
        if (!draft.complete || current.answer != null) return
        val answer = BlindAnswer(
            placement = entry.placement,
            type = draft.type!!, direction = draft.direction!!, footOnly = draft.footOnly!!,
            computed = ComputedSnapshot(entry.type, entry.direction, entry.footOnly, dataset?.version.orEmpty()),
            answeredAtEpochMs = System.currentTimeMillis(),
        )
        session = session.withAnswer(answer)
        persist()
        _state.value = baseState(ReviewStage.BLIND).copy(answer = answer)
    }

    fun next() {
        val current = _state.value
        when (current.stage) {
            ReviewStage.BLIND -> {
                val ref = reference()
                val nextOpen = (current.index + 1 until ref.size).firstOrNull { session.answerFor(ref[it]) == null }
                    ?: ref.indices.firstOrNull { session.answerFor(ref[it]) == null }
                if (nextOpen != null) showBlind(nextOpen) else showStageAfterBlind()
            }
            ReviewStage.DECIDE -> showDecide(current.index + 1)
            ReviewStage.RESULTS -> Unit
        }
    }

    fun previous() {
        val current = _state.value
        when (current.stage) {
            ReviewStage.BLIND -> if (current.index > 0) showBlind(current.index - 1)
            ReviewStage.DECIDE -> if (current.index > 0) showDecide(current.index - 1)
            ReviewStage.RESULTS -> Unit
        }
    }

    fun openStage(stage: ReviewStage) {
        when (stage) {
            ReviewStage.BLIND -> {
                val ref = reference()
                showBlind(ref.indices.firstOrNull { session.answerFor(ref[it]) == null } ?: 0)
            }
            // Deciding before the blind round is finished would show computed labels of holds still to answer.
            ReviewStage.DECIDE -> if (_state.value.answered == _state.value.referenceSize) showDecide(0)
            ReviewStage.RESULTS -> showResults()
        }
    }

    fun decide(field: ReviewField, verdict: String, corrected: String? = null) {
        val placement = _state.value.entry?.placement ?: return
        session = session.withDecision(ReviewDecision(placement, field.wire, verdict, corrected))
        persist()
        _state.update {
            it.copy(session = session, summary = session.summary())
        }
    }

    fun resultsJson(): String = session.toJson()

    fun startOver() {
        session = HoldReviewSession(BOARD, dataset?.version.orEmpty())
        viewModelScope.launch(saveDispatcher) { store.clear(BOARD) }
        showBlind(0)
    }

    private fun persist() {
        val snapshot = session
        viewModelScope.launch(saveDispatcher) { store.save(snapshot) }
    }

    companion object {
        const val BOARD = "kilter"
    }
}
