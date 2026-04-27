package com.cruxcoach.android.ui.board.creator

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.community.ClimbCreatorRepository
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.ClimbValidation
import com.cruxcoach.domain.community.cycleHoldRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "ClimbEditor"

/**
 * Pure UI state — board topology + editor draft + transient flags.
 * `boardSize` and `placements` are loaded once on init; `editor` is
 * mutated as the user taps holds + edits metadata.
 */
data class ClimbEditorUiState(
    val isLoading: Boolean = true,
    val boardSize: BoardSize? = null,
    val placements: Map<Int, BoardPlacement> = emptyMap(),
    val boardImages: List<BoardImage> = emptyList(),
    val placementToLed: Map<Int, Int> = emptyMap(),
    val editor: ClimbEditorState = ClimbEditorState(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val validationIssues: List<ClimbValidation.Issue> = emptyList(),
    val duplicateOf: CommunityClimbRow? = null,    // populated when frames_hash matches existing
    val isPublishing: Boolean = false,
    val publishedUuid: String? = null,             // success terminal — UI navigates back
    val errorMessage: String? = null,
)

@HiltViewModel
class ClimbEditorViewModel @Inject constructor(
    private val repository: ClimbCreatorRepository,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val bleConnection: BoardBleConnection,
) : ViewModel() {

    private val _state = MutableStateFlow(ClimbEditorUiState())
    val state: StateFlow<ClimbEditorUiState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<ClimbEditorState>()
    private val redoStack = ArrayDeque<ClimbEditorState>()

    init {
        viewModelScope.launch { loadBoardData() }
    }

    private suspend fun loadBoardData() {
        val sizeId = userPreferences.boardProductSizeId.first()
        val layoutId = userPreferences.boardLayoutId.first()
        val (size, placements, images, ledMap) = withContext(Dispatchers.IO) {
            val size = boardRepository.getProductSize(sizeId)
            val placements = boardRepository.getAllPlacements().associateBy { it.placementId.toInt() }
            val images = boardRepository.getBoardImages(sizeId, layoutId)
            val led = boardRepository.getPlacementLedMap(sizeId)
            BoardLoad(size, placements, images, led)
        }
        _state.update {
            it.copy(
                isLoading = false,
                boardSize = size,
                placements = placements,
                boardImages = images,
                placementToLed = ledMap,
            )
        }
    }

    private data class BoardLoad(
        val size: BoardSize?,
        val placements: Map<Int, BoardPlacement>,
        val images: List<BoardImage>,
        val ledMap: Map<Int, Int>,
    )

    /**
     * Short tap on a hold → cycle through:
     *   empty → Start → Griff → Tritt → Top → empty.
     * Pushes the previous state onto the undo stack and clears redo.
     */
    fun toggleHold(placementId: Int) {
        val cur = _state.value.editor
        val current = cur.selectedHolds[placementId]
        val next = cycleHoldRole(current)
        val newHolds = if (next == null) {
            cur.selectedHolds - placementId
        } else {
            cur.selectedHolds + (placementId to next)
        }
        push(cur.copy(selectedHolds = newHolds))
        viewModelScope.launch { syncLeds() }
    }

    /**
     * Long-press + drag → MOVE the role from one placement to another.
     * Source loses its role; target gets it. If target already had a
     * role, it's overwritten by the source's role (the moving hold
     * "wins"). Drop on the same hold is a no-op.
     */
    fun moveHold(fromPlacementId: Int, toPlacementId: Int) {
        if (fromPlacementId == toPlacementId) return
        val cur = _state.value.editor
        val role = cur.selectedHolds[fromPlacementId] ?: return
        val newHolds = (cur.selectedHolds - fromPlacementId) + (toPlacementId to role)
        push(cur.copy(selectedHolds = newHolds))
        viewModelScope.launch { syncLeds() }
    }

    fun setName(name: String) = mutate { copy(name = name) }
    fun setDescription(desc: String) = mutate { copy(description = desc) }
    fun setSetterGradeId(gradeId: Int?) = mutate { copy(setterGradeId = gradeId) }
    fun setAngle(angle: Int?) = mutate { copy(angle = angle) }

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.editor)
        applyEditor(previous)
        viewModelScope.launch { syncLeds() }
    }

    fun redo() {
        val nextState = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.editor)
        applyEditor(nextState)
        viewModelScope.launch { syncLeds() }
    }

    /** Save as draft — always succeeds locally, no Nostr roundtrip. */
    fun saveAsDraft(onSaved: (String) -> Unit) {
        val current = _state.value.editor
        val issues = ClimbValidation.validate(current.selectedHolds, current.name, current.description)
        if (issues.isNotEmpty()) {
            _state.update { it.copy(validationIssues = issues) }
            return
        }
        viewModelScope.launch {
            try {
                val uuid = withContext(Dispatchers.IO) { repository.saveDraft(current) }
                onSaved(uuid)
            } catch (e: Exception) {
                Log.w(TAG, "saveDraft failed", e)
                _state.update { it.copy(errorMessage = e.message ?: "Save failed") }
            }
        }
    }

    /**
     * Publish — saves a draft first (so the climb is durable even if the
     * relay round-trip fails) and then pushes the Kind-30078 event.
     */
    fun publish(sizeLabel: String) {
        val current = _state.value.editor
        val issues = ClimbValidation.validate(current.selectedHolds, current.name, current.description)
        if (issues.isNotEmpty()) {
            _state.update { it.copy(validationIssues = issues) }
            return
        }
        _state.update { it.copy(isPublishing = true, errorMessage = null) }
        viewModelScope.launch {
            val uuid = try {
                withContext(Dispatchers.IO) { repository.saveAndPublish(current, sizeLabel) }
            } catch (e: Exception) {
                Log.w(TAG, "publish failed", e)
                _state.update { it.copy(isPublishing = false, errorMessage = e.message ?: "Publish failed") }
                return@launch
            }
            _state.update { it.copy(isPublishing = false, publishedUuid = uuid) }
        }
    }

    fun checkForDuplicate() {
        viewModelScope.launch {
            val dup = withContext(Dispatchers.IO) { repository.findDuplicate(_state.value.editor) }
            _state.update { it.copy(duplicateOf = dup) }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    /** Mutate the editor state via a copy lambda + push to undo stack. */
    private fun mutate(transform: ClimbEditorState.() -> ClimbEditorState) {
        val cur = _state.value.editor
        push(cur.transform())
    }

    private fun push(next: ClimbEditorState) {
        if (next == _state.value.editor) return
        undoStack.addLast(_state.value.editor)
        if (undoStack.size > UNDO_DEPTH) undoStack.removeFirst()
        redoStack.clear()
        applyEditor(next)
    }

    private fun applyEditor(next: ClimbEditorState) {
        _state.update {
            it.copy(
                editor = next,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                validationIssues = emptyList(),
            )
        }
    }

    /**
     * Push the current hold map to the connected board's LEDs (best-effort —
     * if disconnected, the call is a no-op). Re-uses the existing
     * [BoardBleConnection.sendClimb] path; the editor exits SENDING back
     * to CONNECTED automatically and Quick-Send's auto-disconnect rule
     * is route-exempt so the connection is preserved across taps.
     */
    private suspend fun syncLeds() {
        val cur = _state.value
        val ledMap = cur.placementToLed
        if (ledMap.isEmpty()) return
        val holds = cur.editor.selectedHolds.map { (pid, role) -> BoardHold(pid, role) }
        runCatching { bleConnection.sendClimb(holds, ledMap) }
            .onFailure { Log.v(TAG, "LED preview skipped: ${it.message}") }
    }

    companion object {
        private const val UNDO_DEPTH = 50
    }
}
