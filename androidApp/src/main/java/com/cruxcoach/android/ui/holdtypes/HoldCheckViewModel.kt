package com.cruxcoach.android.ui.holdtypes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.data.holdtypes.HoldCheckStore
import com.cruxcoach.android.data.holdtypes.HoldTypeAssets
import com.cruxcoach.domain.holdtypes.CheckSummary
import com.cruxcoach.domain.holdtypes.CheckVerdict
import com.cruxcoach.domain.holdtypes.ComputedSnapshot
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_NO
import com.cruxcoach.domain.holdtypes.FOOT_ONLY_YES
import com.cruxcoach.domain.holdtypes.HoldCheck
import com.cruxcoach.domain.holdtypes.HoldCheckSession
import com.cruxcoach.domain.holdtypes.HoldTypeDataset
import com.cruxcoach.domain.holdtypes.HoldTypeEntry
import com.cruxcoach.domain.holdtypes.checkQueue
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

data class HoldCheckUiState(
    val loading: Boolean = true,
    val boards: List<String> = emptyList(),
    val board: String? = null,
    val datasetVersion: String = "",
    val reviewListOnly: Boolean = true,
    val reviewListSize: Int = 0,
    val labelledSize: Int = 0,
    val queueSize: Int = 0,
    val index: Int = 0,
    val entry: HoldTypeEntry? = null,
    val view: HoldBoardView? = null,
    /** The saved check of the hold on screen, if any. */
    val check: HoldCheck? = null,
    /** Non-null while the reviewer corrects the label: the values to save. */
    val correction: AnswerDraft? = null,
    val summary: CheckSummary? = null,
    val session: HoldCheckSession? = null,
)

/**
 * Checking computed hold types board by board (#63), test builds only: confirm, correct or "can't tell".
 * Unlike the blind reference round, the proposed label is shown from the start.
 */
@HiltViewModel
class HoldCheckViewModel @Inject constructor(
    private val assets: HoldTypeAssets,
    private val store: HoldCheckStore,
    private val boardViews: HoldBoardViews,
) : ViewModel() {

    private val _state = MutableStateFlow(HoldCheckUiState())
    val state: StateFlow<HoldCheckUiState> = _state.asStateFlow()

    private var board: String? = null
    private var dataset: HoldTypeDataset? = null
    private var session: HoldCheckSession? = null
    /** Each board's session once loaded: switching back never rereads a file that a queued save has not reached. */
    private val sessions = mutableMapOf<String, HoldCheckSession>()
    private var queue: List<HoldTypeEntry> = emptyList()
    /** The one load that may still change the screen; a newer board, list or hold cancels it. */
    private var navJob: Job? = null
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        viewModelScope.launch {
            // Only boards with at least one typed hold: there is nothing to confirm on the others.
            val boards = withContext(Dispatchers.IO) {
                assets.boards().filter { b -> assets.dataset(b)?.holds?.any { it.type != null } == true }
            }
            _state.update { it.copy(boards = boards) }
            boards.firstOrNull()?.let { selectBoard(it) } ?: _state.update { it.copy(loading = false) }
        }
    }

    private fun navigate(block: suspend () -> Unit) {
        navJob?.cancel()
        navJob = viewModelScope.launch { block() }
    }

    fun selectBoard(selected: String) = navigate {
        _state.update { it.copy(loading = true, board = selected) }
        val loaded = withContext(Dispatchers.IO) { assets.dataset(selected) }
        val loadedSession = loaded?.let {
            sessions[selected] ?: withContext(Dispatchers.IO) { store.load(selected) } ?: HoldCheckSession(selected, it.version)
        }
        // Board, dataset and session change together, after every load, so nothing mixes two boards.
        board = selected
        dataset = loaded
        session = loadedSession
        loadedSession?.let { sessions[selected] = it }
        rebuildQueueNow(_state.value.reviewListOnly)
    }

    fun setReviewListOnly(only: Boolean) {
        if (only == _state.value.reviewListOnly) return
        navigate { rebuildQueueNow(only) }
    }

    private suspend fun rebuildQueueNow(reviewListOnly: Boolean) {
        val data = dataset
        queue = data?.checkQueue(reviewListOnly).orEmpty()
        val s = session
        // Continue where the reviewer stopped: the first hold without a check.
        val firstOpen = queue.indexOfFirst { s?.checkFor(it.layoutId, it.placement) == null }.let { if (it < 0) 0 else it }
        _state.update {
            it.copy(
                loading = false,
                datasetVersion = data?.version.orEmpty(),
                reviewListOnly = reviewListOnly,
                reviewListSize = data?.checkQueue(true)?.size ?: 0,
                labelledSize = data?.checkQueue(false)?.size ?: 0,
                queueSize = queue.size,
                summary = s?.summary(),
                session = s,
            )
        }
        showNow(firstOpen)
    }

    private fun show(index: Int) = navigate { showNow(index) }

    private suspend fun showNow(index: Int) {
        val entry = queue.getOrNull(index)
        val current = board ?: return
        val view = entry?.let { boardViews.viewFor(current, it) }
        _state.update {
            it.copy(
                index = index, entry = entry, view = view, correction = null,
                check = entry?.let { e -> session?.checkFor(e.layoutId, e.placement) },
            )
        }
    }

    fun next() {
        val i = _state.value.index
        if (i + 1 < queue.size) show(i + 1)
    }

    fun previous() {
        val i = _state.value.index
        if (i > 0) show(i - 1)
    }

    fun confirm() = record(CheckVerdict.CONFIRMED, null)

    fun cantTell() = record(CheckVerdict.CANT_TELL, null)

    /** Opens the choosers, filled with the proposed label (or the earlier correction). */
    fun startCorrection() {
        val entry = _state.value.entry ?: return
        val earlier = _state.value.check?.takeIf { it.verdict == CheckVerdict.CORRECTED.wire }
        _state.update {
            it.copy(
                correction = AnswerDraft(
                    type = earlier?.type ?: entry.type,
                    direction = earlier?.direction ?: entry.direction,
                    footOnly = earlier?.footOnly ?: if (entry.footOnly) FOOT_ONLY_YES else FOOT_ONLY_NO,
                ),
            )
        }
    }

    fun cancelCorrection() = _state.update { it.copy(correction = null) }

    fun editCorrection(change: (AnswerDraft) -> AnswerDraft) =
        _state.update { s -> s.correction?.let { s.copy(correction = change(it)) } ?: s }

    fun saveCorrection() {
        val draft = _state.value.correction ?: return
        if (!draft.complete) return
        record(CheckVerdict.CORRECTED, draft)
    }

    private fun record(verdict: CheckVerdict, draft: AnswerDraft?) {
        val entry = _state.value.entry ?: return
        val current = session ?: return
        // Only a hold of the board now loaded, as the dataset has it.
        if (_state.value.board != board || dataset?.get(entry.layoutId, entry.placement) != entry) return
        val check = HoldCheck(
            layoutId = entry.layoutId,
            placement = entry.placement,
            verdict = verdict.wire,
            type = draft?.type,
            direction = draft?.direction,
            footOnly = draft?.footOnly,
            computed = ComputedSnapshot(entry.type, entry.direction, entry.footOnly, dataset?.version.orEmpty()),
            checkedAtEpochMs = System.currentTimeMillis(),
        )
        val updated = current.withCheck(check)
        session = updated
        sessions[updated.board] = updated
        // A save already made must survive leaving the screen.
        viewModelScope.launch(saveDispatcher + NonCancellable) { store.save(updated) }
        _state.update { it.copy(check = check, correction = null, summary = updated.summary(), session = updated) }
        next()
    }

    fun resultsJson(): String = session?.toJson().orEmpty()

    fun startOver() {
        val cleared = board ?: return
        session = HoldCheckSession(cleared, dataset?.version.orEmpty()).also { sessions[cleared] = it }
        viewModelScope.launch(saveDispatcher + NonCancellable) { store.clear(cleared) }
        navigate { rebuildQueueNow(_state.value.reviewListOnly) }
    }
}
