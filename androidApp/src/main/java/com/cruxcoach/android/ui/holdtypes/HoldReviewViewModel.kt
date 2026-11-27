package com.cruxcoach.android.ui.holdtypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.holdtypes.HoldReviewStore
import com.cruxcoach.android.data.holdtypes.HoldTypeAssets
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ReviewStage { BLIND, DECIDE, RESULTS }

/** The reviewer's selections for the hold on screen, before they are saved. */
data class AnswerDraft(val type: String? = null, val direction: String? = null, val footOnly: String? = null) {
    val complete: Boolean get() = type != null && direction != null && footOnly != null
}

data class HoldReviewUiState(
    val loading: Boolean = true,
    /** Boards whose hold types come with a reference set, and the one on screen. */
    val boards: List<String> = emptyList(),
    val selectedBoard: String = "",
    /** True when this build ships no hold types for the board. */
    val missingDataset: Boolean = false,
    val datasetVersion: String = "",
    val stage: ReviewStage = ReviewStage.BLIND,
    val referenceSize: Int = 0,
    val answered: Int = 0,
    /** Index into the reference set (blind) or into [disagreementHolds] (decide); both hold reference keys. */
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
    private val boardViews: HoldBoardViews,
) : ViewModel() {

    private val _state = MutableStateFlow(HoldReviewUiState())
    val state: StateFlow<HoldReviewUiState> = _state.asStateFlow()

    private var board: String = DEFAULT_BOARD
    private var dataset: HoldTypeDataset? = null
    private var session: HoldReviewSession = HoldReviewSession(DEFAULT_BOARD, "")
    /** Each board's session once loaded: switching back never rereads a file that a queued save has not reached. */
    private val sessions = mutableMapOf<String, HoldReviewSession>()
    /** The one load that may still change the screen; a newer board or hold cancels it. */
    private var navJob: Job? = null
    /** Saves run one at a time, in the order they were made. */
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        viewModelScope.launch {
            val boards = withContext(Dispatchers.IO) {
                assets.boards().filter { assets.dataset(it)?.referenceSet?.isNotEmpty() == true }
            }
            _state.update { it.copy(boards = boards) }
            selectBoard(boards.firstOrNull() ?: DEFAULT_BOARD)
        }
    }

    private fun navigate(block: suspend () -> Unit) {
        navJob?.cancel()
        navJob = viewModelScope.launch { block() }
    }

    /** Each board keeps its own reference round; switching boards keeps every answer. */
    fun selectBoard(selected: String) = navigate {
        _state.update { it.copy(loading = true, selectedBoard = selected) }
        val loaded = withContext(Dispatchers.IO) { assets.dataset(selected) }
        if (loaded == null || loaded.referenceSet.isEmpty()) {
            board = selected
            dataset = null
            _state.update { it.copy(loading = false, missingDataset = true) }
            return@navigate
        }
        val loadedSession = sessions[selected]
            ?: withContext(Dispatchers.IO) { store.load(selected) } ?: HoldReviewSession(selected, loaded.version)
        // Board, dataset and session change together, after every load, so nothing mixes two boards.
        board = selected
        dataset = loaded
        session = loadedSession
        sessions[selected] = loadedSession
        _state.update { it.copy(missingDataset = false) }
        val firstOpen = loaded.referenceSet.indexOfFirst { session.answerFor(it) == null }
        if (firstOpen >= 0) showBlindNow(firstOpen) else showStageAfterBlindNow()
    }

    private fun reference(): List<Int> = dataset?.referenceSet.orEmpty()

    /** The session limited to the current reference set, for every count and the decide round. */
    private fun scoped(): HoldReviewSession {
        val keys = reference().toSet()
        return session.copy(answers = session.answers.filter { it.placement in keys })
    }

    /** Disagreeing holds of the decide round, in answer order; keys this dataset cannot show are left out. */
    private fun decideHolds(): List<Int> =
        scoped().disagreements().map { it.placement }.distinct().filter { dataset?.referenceEntry(it) != null }

    private suspend fun boardFor(entry: HoldTypeEntry): HoldBoardView? = boardViews.viewFor(board, entry)

    private fun baseState(stage: ReviewStage) = _state.value.copy(
        loading = false,
        datasetVersion = dataset?.version.orEmpty(),
        stage = stage,
        referenceSize = reference().size,
        answered = reference().count { session.answerFor(it) != null },
        session = session,
        disagreementHolds = decideHolds(),
        summary = scoped().summary(),
    )

    private fun showBlind(index: Int) = navigate { showBlindNow(index) }

    private suspend fun showBlindNow(index: Int) {
        val key = reference().getOrNull(index) ?: return
        val entry = dataset?.referenceEntry(key) ?: return
        val view = boardFor(entry)
        val answer = session.answerFor(key)
        _state.value = baseState(ReviewStage.BLIND).copy(
            index = index, entry = entry, board = view, answer = answer,
            draft = answer?.let { AnswerDraft(it.type, it.direction, it.footOnly) } ?: AnswerDraft(),
            disagreementsHere = emptyList(),
        )
    }

    private fun showDecide(index: Int) = navigate { showDecideNow(index) }

    private suspend fun showDecideNow(index: Int) {
        val key = decideHolds().getOrNull(index) ?: return showResultsNow()
        val entry = dataset?.referenceEntry(key) ?: return showResultsNow()
        val view = boardFor(entry)
        _state.value = baseState(ReviewStage.DECIDE).copy(
            index = index, entry = entry, board = view, answer = session.answerFor(key),
            disagreementsHere = scoped().disagreements().filter { it.placement == key },
        )
    }

    private fun showResults() {
        navJob?.cancel()
        showResultsNow()
    }

    private fun showResultsNow() {
        _state.value = baseState(ReviewStage.RESULTS).copy(entry = null, board = null, answer = null)
    }

    private suspend fun showStageAfterBlindNow() {
        val holds = decideHolds()
        val scopedSession = scoped()
        val open = holds.indexOfFirst { p ->
            scopedSession.disagreements().any { it.placement == p && session.decisionFor(p, it.field) == null }
        }
        if (open >= 0) showDecideNow(open) else showResultsNow()
    }

    fun selectType(value: String) = editDraft { it.copy(type = value) }
    fun selectDirection(value: String) = editDraft { it.copy(direction = value) }
    fun selectFootOnly(value: String) = editDraft { it.copy(footOnly = value) }

    private fun editDraft(change: (AnswerDraft) -> AnswerDraft) {
        // A shown label is final: changing the answer afterwards would no longer be blind.
        if (_state.value.answer != null) return
        _state.update { it.copy(draft = change(it.draft)) }
    }

    /** The reference key of the hold on screen, or null when it does not belong to the board now loaded. */
    private fun currentKey(): Int? {
        val current = _state.value
        val entry = current.entry ?: return null
        val data = dataset ?: return null
        if (current.selectedBoard != board) return null
        val key = data.referenceKey(entry)
        return key.takeIf { data.referenceEntry(it) == entry && it in data.referenceSet }
    }

    /** Saves the blind answer and reveals the computed label. */
    fun submit() {
        val current = _state.value
        val entry = current.entry ?: return
        val key = currentKey() ?: return
        val draft = current.draft
        if (!draft.complete || current.answer != null) return
        val answer = BlindAnswer(
            // the reference key: the placement id, or layout and hold id on MoonBoard
            placement = key,
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
                if (nextOpen != null) showBlind(nextOpen) else navigate { showStageAfterBlindNow() }
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
        val key = currentKey() ?: return
        session = session.withDecision(ReviewDecision(key, field.wire, verdict, corrected))
        persist()
        _state.update {
            it.copy(session = session, summary = scoped().summary())
        }
    }

    fun resultsJson(): String = session.toJson()

    fun resultsFileName(): String = "hold-types-$board-review.json"

    fun startOver() {
        val cleared = board
        session = HoldReviewSession(cleared, dataset?.version.orEmpty())
        sessions[cleared] = session
        viewModelScope.launch(saveDispatcher + NonCancellable) { store.clear(cleared) }
        showBlind(0)
    }

    private fun persist() {
        val snapshot = session
        sessions[snapshot.board] = snapshot
        // A save already made must survive leaving the screen.
        viewModelScope.launch(saveDispatcher + NonCancellable) { store.save(snapshot) }
    }

    companion object {
        const val DEFAULT_BOARD = "kilter"
    }
}
