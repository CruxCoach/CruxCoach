package com.cruxcoach.android.ui.board.creator

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cruxcoach.android.ble.BoardBleConnection
import com.cruxcoach.android.community.ClimbCreatorRepository
import com.cruxcoach.android.community.EditorAutosave
import com.cruxcoach.android.data.LedHoldColors
import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.data.repository.BoardImage
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardHold
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.ClimbValidation
import com.cruxcoach.domain.community.paintWithBrush
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
    val pendingPublishConfirm: Boolean = false,    // dup-warn dialog gate
    val isPublishing: Boolean = false,
    val publishedUuid: String? = null,             // success terminal — UI navigates back
    val errorMessage: String? = null,
    /** Loaded-draft uuid — re-saving updates this row in place. */
    val loadedDraftUuid: String? = null,
    /** Recovered autosave waiting for the user's restore decision. */
    val autosaveOffer: EditorAutosave.AutosaveSnapshot? = null,
    /** Heatmap intensities (placementId → 0..1) for "popular co-occurring holds". */
    val heatmap: Map<Int, Float> = emptyMap(),
    /** User-toggled visibility of the heatmap overlay. */
    val heatmapEnabled: Boolean = false,
    /** Drafts the user has saved locally; null = not yet loaded. */
    val drafts: List<CommunityClimbRow>? = null,
    val draftsSheetOpen: Boolean = false,
    /** User-configured LED colors — drives both the board rendering and
     *  the brush-chip tints so they always match what the LEDs show. */
    val ledColors: LedHoldColors = LedHoldColors(),
)

@HiltViewModel
class ClimbEditorViewModel @Inject constructor(
    private val repository: ClimbCreatorRepository,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val bleConnection: BoardBleConnection,
    private val autosave: EditorAutosave,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(ClimbEditorUiState())
    val state: StateFlow<ClimbEditorUiState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<ClimbEditorState>()
    private val redoStack = ArrayDeque<ClimbEditorState>()

    /** Debounced autosave job — restarted on every editor mutation. */
    private var autosaveJob: kotlinx.coroutines.Job? = null

    /** Debounced heatmap-recompute job — restarted on holds change. */
    private var heatmapJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            loadBoardData()
            handleNavigationArgs()
        }
        viewModelScope.launch {
            userPreferences.ledHoldColors.collect { colors ->
                _state.update { it.copy(ledColors = colors) }
            }
        }
    }

    /**
     * Read SavedStateHandle for `forkUuid` (Remix from existing climb) and
     * surface any recovered autosave to the user. Drafts navigate via the
     * drawer instead so they're not handled here.
     */
    private suspend fun handleNavigationArgs() {
        val forkUuid: String? = savedStateHandle["forkUuid"]
        if (forkUuid != null) {
            val source = withContext(Dispatchers.IO) {
                boardRepository.getMyClimbs("__none__")
                    .firstOrNull { it.uuid.equals(forkUuid, ignoreCase = true) }
                    ?: boardRepository.getCommunityClimbs()
                        .firstOrNull { it.uuid.equals(forkUuid, ignoreCase = true) }
                    // Final fallback: a raw climb (Kilter source) by uuid via the existing browse query.
                    ?: boardRepository.getClimbByUuid(forkUuid, angle = 40)?.let { c ->
                        CommunityClimbRow(
                            uuid = c.uuid, name = c.name + " Remix", setterUsername = c.setterUsername,
                            description = c.description, framesText = c.frames, source = "kilter",
                            syncStatus = "synced", createdByPubkey = null, nostrEventId = null,
                            nostrDTag = null, framesHash = null, createdAt = null, moveCount = c.storedMoveCount,
                        )
                    }
            }
            if (source != null) seedFromFork(source)
        }
        // Autosave restore offer — only when the editor opens *fresh* (no fork seed).
        val offer = withContext(Dispatchers.IO) { autosave.load() }
        if (offer != null && _state.value.editor.selectedHolds.isEmpty()) {
            _state.update { it.copy(autosaveOffer = offer) }
        }
    }

    private fun seedFromFork(source: CommunityClimbRow) {
        val holds = com.cruxcoach.domain.board.BoardClimbParser.parseFrames(source.framesText)
            .associate { it.placementId to it.roleId }
        val seeded = ClimbEditorState(
            selectedHolds = holds,
            name = source.name + if (!source.name.endsWith("Remix")) " Remix" else "",
            description = source.description,
        )
        applyEditor(seeded)
    }

    private suspend fun loadBoardData() {
        val sizeId = userPreferences.boardProductSizeId.first()
        val layoutId = userPreferences.boardLayoutId.first()
        val defaultAngle = userPreferences.boardAngle.first()
        val (size, placements, images, ledMap) = withContext(Dispatchers.IO) {
            val size = boardRepository.getProductSize(sizeId)
            val placements = boardRepository.getAllPlacements().associateBy { it.placementId.toInt() }
            val images = boardRepository.getBoardImages(sizeId, layoutId)
            val led = boardRepository.getPlacementLedMap(sizeId)
            BoardLoad(size, placements, images, led)
        }
        _state.update {
            val seedAngle = it.editor.angle ?: defaultAngle
            it.copy(
                isLoading = false,
                boardSize = size,
                placements = placements,
                boardImages = images,
                placementToLed = ledMap,
                editor = it.editor.copy(angle = seedAngle),
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
     * Short tap on a hold:
     * - **Active brush** → paint the brush role (or toggle off if already that role)
     * - **No active brush** → remove the hold (no-op on empty holds). Drag-
     *   and-drop continues to work via `moveHold`; long-press starts a drag
     *   regardless of brush state.
     */
    fun toggleHold(placementId: Int) {
        val cur = _state.value.editor
        val current = cur.selectedHolds[placementId]
        val brush = cur.activeBrush
        val next = if (brush != null) paintWithBrush(current, brush) else null
        val newHolds = if (next == null) {
            cur.selectedHolds - placementId
        } else {
            cur.selectedHolds + (placementId to next)
        }
        push(cur.copy(selectedHolds = newHolds))
        viewModelScope.launch { syncLeds() }
    }

    /**
     * Set the active brush from a chip-toolbar tap. Tapping the same
     * chip again deactivates the brush — taps then delete on hit.
     */
    fun toggleBrush(role: Int) {
        val cur = _state.value.editor
        val nextBrush = if (cur.activeBrush == role) null else role
        // Brush change isn't an undoable edit — just a UI cursor flip.
        _state.update { it.copy(editor = cur.copy(activeBrush = nextBrush)) }
        // Heatmap is brush-aware: switching brush changes which role's
        // placements are highlighted. Recompute against the cached parse,
        // so this is essentially free.
        recomputeHeatmap()
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
                val uuid = withContext(Dispatchers.IO) {
                    val existing = _state.value.loadedDraftUuid
                    if (existing != null) {
                        repository.updateDraft(existing, current)
                        existing
                    } else {
                        repository.saveDraft(current)
                    }
                }
                autosave.clear()
                onSaved(uuid)
            } catch (e: Exception) {
                Log.w(TAG, "saveDraft failed", e)
                _state.update { it.copy(errorMessage = e.message ?: "Save failed") }
            }
        }
    }

    /**
     * Publish — first runs duplicate detection. If a duplicate is found,
     * surface the dialog and pause; the actual publish call is gated on
     * [confirmPublishWithDuplicate]. If no duplicate, proceeds straight
     * to save + Nostr push.
     */
    fun publish(sizeLabel: String) {
        val current = _state.value.editor
        val issues = ClimbValidation.validate(current.selectedHolds, current.name, current.description)
        if (issues.isNotEmpty()) {
            _state.update { it.copy(validationIssues = issues) }
            return
        }
        viewModelScope.launch {
            val dup = withContext(Dispatchers.IO) { repository.findDuplicate(current) }
            // Skip the dialog if the duplicate IS the draft we're updating.
            val ownLoaded = _state.value.loadedDraftUuid
            val isSelfReplace = dup != null && dup.uuid == ownLoaded
            if (dup != null && !isSelfReplace) {
                _state.update { it.copy(duplicateOf = dup, pendingPublishConfirm = true) }
                return@launch
            }
            doPublish(sizeLabel)
        }
    }

    /** User accepted the duplicate-warning dialog → continue publish. */
    fun confirmPublishWithDuplicate(sizeLabel: String) {
        _state.update { it.copy(duplicateOf = null, pendingPublishConfirm = false) }
        doPublish(sizeLabel)
    }

    /** User declined the duplicate-warning dialog → stay in editor. */
    fun cancelPublishOnDuplicate() {
        _state.update { it.copy(duplicateOf = null, pendingPublishConfirm = false) }
    }

    private fun doPublish(sizeLabel: String) {
        _state.update { it.copy(isPublishing = true, errorMessage = null) }
        val current = _state.value.editor
        viewModelScope.launch {
            val uuid = try {
                withContext(Dispatchers.IO) { repository.saveAndPublish(current, sizeLabel) }
            } catch (e: Exception) {
                Log.w(TAG, "publish failed", e)
                _state.update { it.copy(isPublishing = false, errorMessage = e.message ?: "Publish failed") }
                return@launch
            }
            autosave.clear()
            _state.update { it.copy(isPublishing = false, publishedUuid = uuid) }
        }
    }

    // ── Autosave restore offer ──────────────────────────────────

    fun acceptAutosave() {
        val offer = _state.value.autosaveOffer ?: return
        applyEditor(offer.state)
        _state.update { it.copy(autosaveOffer = null) }
    }

    fun dismissAutosave() {
        viewModelScope.launch { autosave.clear() }
        _state.update { it.copy(autosaveOffer = null) }
    }

    // ── Drafts drawer ───────────────────────────────────────────

    fun openDraftsSheet() {
        viewModelScope.launch {
            val drafts = withContext(Dispatchers.IO) { boardRepository.getDraftClimbs() }
            _state.update { it.copy(drafts = drafts, draftsSheetOpen = true) }
        }
    }

    fun closeDraftsSheet() {
        _state.update { it.copy(draftsSheetOpen = false) }
    }

    /**
     * Load a draft into the editor. The hold map + metadata replace the
     * current editor state; the loaded uuid is tracked so re-saving
     * updates the same row in place. Doesn't touch any other drafts.
     */
    fun loadDraft(draft: CommunityClimbRow) {
        val holds = com.cruxcoach.domain.board.BoardClimbParser.parseFrames(draft.framesText)
            .associate { it.placementId to it.roleId }
        val seeded = ClimbEditorState(
            selectedHolds = holds,
            name = draft.name,
            description = draft.description,
        )
        // Reset undo stacks — we're starting from a fresh draft snapshot.
        undoStack.clear(); redoStack.clear()
        _state.update { it.copy(editor = seeded, loadedDraftUuid = draft.uuid, draftsSheetOpen = false, canUndo = false, canRedo = false) }
        viewModelScope.launch { syncLeds() }
    }

    fun deleteDraft(uuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            boardRepository.deleteLocalClimb(uuid)
            // Refresh the drawer's list.
            val drafts = boardRepository.getDraftClimbs()
            _state.update { s ->
                val resetLoaded = if (s.loadedDraftUuid == uuid) null else s.loadedDraftUuid
                s.copy(drafts = drafts, loadedDraftUuid = resetLoaded)
            }
        }
    }

    // ── Heatmap overlay ──────────────────────────────────────────

    fun toggleHeatmap() {
        val nowEnabled = !_state.value.heatmapEnabled
        _state.update { it.copy(heatmapEnabled = nowEnabled) }
        if (nowEnabled) recomputeHeatmap()
    }

    private fun recomputeHeatmap() {
        if (!_state.value.heatmapEnabled) return
        heatmapJob?.cancel()
        heatmapJob = viewModelScope.launch {
            kotlinx.coroutines.delay(HEATMAP_DEBOUNCE_MS)
            val angle = _state.value.editor.angle ?: return@launch
            val layoutId = userPreferences.boardLayoutId.first().toLong()
            val seed = _state.value.editor.selectedHolds.keys
            // Brush-aware: when the user has a brush picked, the heatmap
            // suggests popular placements *for that role* among matching
            // climbs. With no brush (eraser/review), fall back to a
            // role-agnostic popularity view so the heatmap still gives
            // useful "where does this layout live" cues.
            val targetRole = _state.value.editor.activeBrush
            // Default dispatcher — the cold path does a single SQL read
            // (cached SQLDelight prepared stmt) followed by a CPU-bound
            // parse/aggregate over the cached IntArray[]. Hot path is pure
            // CPU. Default's pool fits better than IO here.
            val map = withContext(Dispatchers.Default) {
                boardRepository.computeEditorHeatmap(layoutId, angle.toLong(), seed, targetRole)
            }
            _state.update { it.copy(heatmap = map) }
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
        val prevHolds = _state.value.editor.selectedHolds
        // Live validation — the publish-ready banner stays accurate as the
        // user types/taps instead of only updating on the publish click.
        val issues = ClimbValidation.validate(
            holds = next.selectedHolds,
            name = next.name,
            description = next.description,
        )
        _state.update {
            it.copy(
                editor = next,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                validationIssues = issues,
            )
        }
        scheduleAutosave(next)
        if (next.selectedHolds.keys != prevHolds.keys) recomputeHeatmap()
    }

    /** Debounced write to DataStore; latest call wins. */
    private fun scheduleAutosave(next: ClimbEditorState) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(AUTOSAVE_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { autosave.save(next) }
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
        private const val AUTOSAVE_DEBOUNCE_MS = 500L
        private const val HEATMAP_DEBOUNCE_MS = 500L
    }
}
