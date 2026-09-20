package com.cruxcoach.app.creator

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.community.AutoNoteSpec
import com.cruxcoach.app.community.CommunityPublishResult
import com.cruxcoach.app.community.CommunityPublisher
import com.cruxcoach.app.community.PublishFailure
import com.cruxcoach.app.logbook.StateWatch
import com.cruxcoach.app.logbook.watchState
import com.cruxcoach.app.render.LedHoldColors
import com.cruxcoach.app.render.boardImageCandidatePaths
import com.cruxcoach.app.render.layoutJsonAssetPath
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.BoardSize
import com.cruxcoach.data.repository.CommunityClimbRow
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.board.MoonBoardVariant
import com.cruxcoach.domain.community.ClimbEditorState
import com.cruxcoach.domain.community.ClimbValidation
import com.cruxcoach.domain.community.paintWithBrush
import com.cruxcoach.domain.community.parseHoldsForEditor
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
import kotlin.math.abs

enum class EditorError {
    NONE,
    LOAD_FAILED,
    SAVE_FAILED,
    DRAFTS_LOAD_FAILED,
    DRAFTS_DELETE_FAILED,

    /**
     * The climb was already mirrored to the user's Kilter account ('synced') or
     * Kilter refused its update ('diverged'), so it is frozen on that side and
     * editing it here would diverge the two. Android refuses the same load and
     * points the user at Remix.
     */
    EDIT_LOCKED_BY_KILTER,
}

/** Where the publish flow currently stands. */
enum class PublishPhase { IDLE, PUBLISHING, PUBLISHED, FAILED }

/**
 * Board topology plus the editor draft. [editor] is the portable
 * [ClimbEditorState]; everything else is what the screen needs to draw it.
 */
data class ClimbEditorUiState(
    val isLoading: Boolean = true,
    val layoutId: Long = 1L,
    val productSizeId: Int = 0,
    /** Publishable angles for the active board; never empty once loaded. */
    val angleOptions: List<Int> = CreatorAnglePicker.GENERIC,
    val boardSize: BoardSize? = null,
    val placements: Map<Int, BoardPlacement> = emptyMap(),
    /** Bundle-relative board image candidates, most specific first. */
    val boardImagePaths: List<String> = emptyList(),
    /** Bundle-relative MoonBoard coordinate map the host must load, or empty. */
    val moonLayoutPath: String = "",
    val editor: ClimbEditorState = ClimbEditorState(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val validationIssues: List<ClimbValidation.Issue> = emptyList(),
    /** Populated when the canonical frames hash already exists on this board. */
    val duplicateOf: CommunityClimbRow? = null,
    /** Loaded draft / edited climb — re-saving updates this row in place. */
    val loadedDraftUuid: String? = null,
    /** True when editing an already-published climb: "save as draft" is hidden. */
    val isEditingExisting: Boolean = false,
    /** Null until the drawer has been opened once. */
    val drafts: List<CommunityClimbRow>? = null,
    val draftsSheetOpen: Boolean = false,
    val ledColors: LedHoldColors = LedHoldColors(),
    /** One-shot: uuid of the draft the last save wrote. */
    val savedDraftUuid: String? = null,
    val publishPhase: PublishPhase = PublishPhase.IDLE,
    /** Set once a publish has finished, so the screen can report reach honestly. */
    val publishResult: CommunityPublishResult? = null,
    /** True while the duplicate warning is waiting on the user's decision. */
    val pendingDuplicateConfirm: Boolean = false,
    val error: EditorError = EditorError.NONE,
)

/**
 * Selectable publish angles (Android's `CreatorAnglePicker`, FEAT-033).
 *
 * MoonBoard is fixed-angle hardware, so it gets its variant's official configs;
 * every other board gets its real `DISTINCT climb_stats.angle` set — the same
 * source the browse angle chips read, so a published setter angle can never land
 * outside the set other devices offer.
 */
object CreatorAnglePicker {
    /** Fallback for a board that has no published climb yet. */
    val GENERIC: List<Int> = listOf(20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70)

    fun optionsFor(brand: BoardBrand, layoutId: Long, supportedAngles: List<Int>): List<Int> =
        when (brand) {
            BoardBrand.MOONBOARD -> MoonBoardVariant.fromLayoutId(layoutId)?.angles ?: listOf(40)
            else -> supportedAngles.ifEmpty { GENERIC }
        }

    /** Snaps to the nearest offered angle so the editor never opens on one the board has not got. */
    fun snapAngle(angle: Int, options: List<Int>): Int =
        if (angle in options) angle else options.minBy { abs(it - angle) }
}

/**
 * Port of Android's `ClimbEditorViewModel`: hold painting, drag-move, undo/redo,
 * live validation, duplicate detection and the drafts drawer.
 *
 * Deliberately NOT ported (each is a separate Android dependency, and a stub
 * would be worse than an absence): the debounced DataStore autosave, the
 * co-occurrence heatmap overlay, the BLE LED mirror, the Kind-0 profile hint and
 * the whole Kilter publish leg.
 */
class ClimbEditor(
    private val boardRepository: BoardRepository,
    private val drafts: ClimbDraftStore,
    private val preferences: BrowsePreferences,
    /** Null when this build cannot publish; [publish] then fails loudly rather than pretending. */
    private val publisher: CommunityPublisher? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(ClimbEditorUiState())
    val state: StateFlow<ClimbEditorUiState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<ClimbEditorState>()
    private val redoStack = ArrayDeque<ClimbEditorState>()

    fun watch(onState: (ClimbEditorUiState) -> Unit): StateWatch = scope.watchState(state, onState)

    /**
     * Loads the active board and, when given, seeds from an existing climb.
     * [editUuid] edits in place (same uuid, so the Nostr d-tag stays stable);
     * [forkUuid] remixes into a new climb.
     */
    fun open(editUuid: String? = null, forkUuid: String? = null) {
        scope.launch {
            try {
                loadBoardData()
                when {
                    !editUuid.isNullOrBlank() -> seedFromEdit(editUuid)
                    !forkUuid.isNullOrBlank() -> seedFromFork(forkUuid)
                }
                // Validate whatever we landed on: without this a freshly-opened
                // editor reports "ready to publish" on an empty climb.
                val current = _state.value.editor
                _state.update { it.copy(validationIssues = validate(current)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = EditorError.LOAD_FAILED) }
            }
        }
    }

    // ── Holds ────────────────────────────────────────────────────

    /**
     * Short tap. With an active brush the tap paints that role (or toggles it off
     * when the hold already has it); with no brush selected it removes the hold
     * and is a no-op on an empty one.
     */
    fun toggleHold(placementId: Int) {
        val current = _state.value.editor
        val brush = current.activeBrush
        val next = if (brush != null) paintWithBrush(current.selectedHolds[placementId], brush) else null
        push(
            current.copy(
                selectedHolds = if (next == null) {
                    current.selectedHolds - placementId
                } else {
                    current.selectedHolds + (placementId to next)
                }
            )
        )
    }

    /**
     * Touch affordance this app adds on top of the Android brush model: advances
     * one hold through start → hand → finish → foot → unset in the board's own
     * role numbering, so a hold can be re-roled without going back to the chips.
     * The brush is untouched and the result is an ordinary undoable edit.
     */
    fun cycleHoldRole(placementId: Int) {
        val current = _state.value.editor
        val palette = rolePalette(BoardBrand.fromWire(current.boardBrand))
        val existing = current.selectedHolds[placementId]
        val index = palette.indexOf(existing)
        val next = when {
            existing == null || index < 0 -> palette.first()
            index == palette.lastIndex -> null
            else -> palette[index + 1]
        }
        push(
            current.copy(
                selectedHolds = if (next == null) {
                    current.selectedHolds - placementId
                } else {
                    current.selectedHolds + (placementId to next)
                }
            )
        )
    }

    /** Chip tap. Re-tapping the active chip clears the brush, so taps then delete. */
    fun toggleBrush(role: Int) {
        val current = _state.value.editor
        // A brush change is a UI cursor flip, not an undoable edit.
        _state.update {
            it.copy(editor = current.copy(activeBrush = if (current.activeBrush == role) null else role))
        }
    }

    /**
     * Long-press drag. The source loses its role and the target gets it; an
     * occupied target is overwritten (the moving hold wins). Same hold is a no-op.
     */
    fun moveHold(fromPlacementId: Int, toPlacementId: Int) {
        if (fromPlacementId == toPlacementId) return
        val current = _state.value.editor
        val role = current.selectedHolds[fromPlacementId] ?: return
        push(
            current.copy(
                selectedHolds = (current.selectedHolds - fromPlacementId) + (toPlacementId to role)
            )
        )
    }

    // ── Metadata ─────────────────────────────────────────────────

    fun setName(name: String) = mutate { copy(name = name) }
    fun setDescription(description: String) = mutate { copy(description = description) }
    fun setSetterGradeId(gradeId: Int?) = mutate { copy(setterGradeId = gradeId) }
    fun setAngle(angle: Int?) = mutate { copy(angle = angle) }

    // ── History ──────────────────────────────────────────────────

    fun undo() {
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_state.value.editor)
        applyEditor(previous)
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_state.value.editor)
        applyEditor(next)
    }

    /**
     * Wipes the working state and unpins the loaded draft, so the next save
     * creates a fresh row. The undo stack survives, so one Undo brings a mistaken
     * tap back.
     */
    fun clearEditor() {
        applyEditor(ClimbEditorState(boardBrand = _state.value.editor.boardBrand, activeBrush = defaultBrushFor(_state.value.editor.boardBrand)))
        _state.update { it.copy(loadedDraftUuid = null, isEditingExisting = false) }
    }

    // ── Drafts ───────────────────────────────────────────────────

    /** Always local, never a relay round-trip. Blocked while validation has issues. */
    fun saveAsDraft() {
        val current = _state.value.editor
        val issues = validate(current)
        if (issues.isNotEmpty()) {
            _state.update { it.copy(validationIssues = issues) }
            return
        }
        val layoutId = _state.value.layoutId
        scope.launch {
            try {
                val uuid = withContext(ioDispatcher) {
                    val existing = _state.value.loadedDraftUuid
                    if (existing != null) {
                        if (drafts.updateDraft(existing, current, layoutId)) existing else null
                    } else {
                        drafts.saveDraft(current, layoutId)
                    }
                }
                if (uuid == null) {
                    _state.update { it.copy(error = EditorError.SAVE_FAILED) }
                    return@launch
                }
                val refreshed = withContext(ioDispatcher) { drafts.drafts(current.boardBrand) }
                _state.update {
                    it.copy(drafts = refreshed, loadedDraftUuid = uuid, savedDraftUuid = uuid)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = EditorError.SAVE_FAILED) }
            }
        }
    }

    fun openDraftsSheet() {
        val brand = _state.value.editor.boardBrand
        scope.launch {
            try {
                val rows = withContext(ioDispatcher) { drafts.drafts(brand) }
                _state.update { it.copy(drafts = rows, draftsSheetOpen = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = EditorError.DRAFTS_LOAD_FAILED) }
            }
        }
    }

    fun closeDraftsSheet() = _state.update { it.copy(draftsSheetOpen = false) }

    /**
     * Replaces the working state with a saved draft and pins its uuid, so the
     * next save updates that row. Angle and grade live in `climb_stats`, not on
     * the climb row; without them a restored draft would arrive angle-less and
     * the bottom bar would falsely report it publishable.
     */
    fun loadDraft(uuid: String) {
        scope.launch {
            try {
                val loaded = withContext(ioDispatcher) {
                    val row = drafts.drafts(_state.value.editor.boardBrand)
                        .firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
                    row?.let { it to boardRepository.getClimbStatsForUuid(it.uuid) }
                }
                if (loaded == null) {
                    _state.update { it.copy(error = EditorError.DRAFTS_LOAD_FAILED) }
                    return@launch
                }
                val (row, stats) = loaded
                seed(row, stats, keepName = true, editingExisting = false)
                _state.update { it.copy(draftsSheetOpen = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = EditorError.DRAFTS_LOAD_FAILED) }
            }
        }
    }

    fun deleteDraft(uuid: String) {
        scope.launch {
            try {
                val brand = _state.value.editor.boardBrand
                val remaining = withContext(ioDispatcher) {
                    drafts.deleteDraft(uuid)
                    drafts.drafts(brand)
                }
                _state.update { s ->
                    s.copy(
                        drafts = remaining,
                        loadedDraftUuid = if (s.loadedDraftUuid == uuid) null else s.loadedDraftUuid,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = EditorError.DRAFTS_DELETE_FAILED) }
            }
        }
    }

    // ── Duplicate detection ──────────────────────────────────────

    /**
     * Looks the working state up by canonical frames hash. A hit on the row we
     * are editing is not a duplicate — that is the same climb.
     */
    fun checkForDuplicate() {
        val current = _state.value.editor
        val layoutId = _state.value.layoutId
        scope.launch {
            try {
                val found = withContext(ioDispatcher) { drafts.findDuplicate(current, layoutId) }
                val loaded = _state.value.loadedDraftUuid
                _state.update { it.copy(duplicateOf = found?.takeIf { row -> row.uuid != loaded }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(duplicateOf = null) }
            }
        }
    }

    fun dismissDuplicate() = _state.update { it.copy(duplicateOf = null) }

    fun consumeSavedDraft() = _state.update { it.copy(savedDraftUuid = null) }

    fun consumeError() = _state.update { it.copy(error = EditorError.NONE) }

    // ── Publish ──────────────────────────────────────────────────

    /**
     * Saves the draft and pushes it to the relays.
     *
     * Duplicate detection runs first and pauses on a hit, exactly as Android
     * does: the user confirms through [confirmPublishWithDuplicate] or backs out
     * through [cancelPublish]. [autoNoteText] is the localized announcement
     * template the host owns; blank means no announcement note.
     */
    fun publish(autoNoteText: String = "", maintainerPubkeyHex: String = "", mentionMaintainer: Boolean = false) {
        val current = _state.value.editor
        val issues = validate(current)
        if (issues.isNotEmpty()) {
            _state.update { it.copy(validationIssues = issues) }
            return
        }
        // Atomic claim: a second tap arriving while the duplicate lookup is in
        // flight would otherwise launch a parallel publish.
        var claimed = false
        _state.update { s ->
            if (s.publishPhase == PublishPhase.PUBLISHING) {
                s
            } else {
                claimed = true
                s.copy(publishPhase = PublishPhase.PUBLISHING, publishResult = null)
            }
        }
        if (!claimed) return

        val layoutId = _state.value.layoutId
        scope.launch {
            try {
                val duplicate = withContext(ioDispatcher) { drafts.findDuplicate(current, layoutId) }
                val loaded = _state.value.loadedDraftUuid
                if (duplicate != null && duplicate.uuid != loaded) {
                    _state.update { it.copy(duplicateOf = duplicate, pendingDuplicateConfirm = true) }
                    return@launch
                }
                runPublish(autoNoteText, maintainerPubkeyHex, mentionMaintainer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(publishPhase = PublishPhase.FAILED, error = EditorError.SAVE_FAILED) }
            }
        }
    }

    fun confirmPublishWithDuplicate(
        autoNoteText: String = "",
        maintainerPubkeyHex: String = "",
        mentionMaintainer: Boolean = false,
    ) {
        _state.update { it.copy(duplicateOf = null, pendingDuplicateConfirm = false) }
        scope.launch {
            try {
                runPublish(autoNoteText, maintainerPubkeyHex, mentionMaintainer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(publishPhase = PublishPhase.FAILED, error = EditorError.SAVE_FAILED) }
            }
        }
    }

    /** Backs out of the duplicate warning and releases the publish claim. */
    fun cancelPublish() = _state.update {
        it.copy(duplicateOf = null, pendingDuplicateConfirm = false, publishPhase = PublishPhase.IDLE)
    }

    fun consumePublishResult() = _state.update {
        it.copy(publishPhase = PublishPhase.IDLE, publishResult = null)
    }

    private suspend fun runPublish(
        autoNoteText: String,
        maintainerPubkeyHex: String,
        mentionMaintainer: Boolean,
    ) {
        val publisher = this.publisher
        if (publisher == null) {
            _state.update { it.copy(publishPhase = PublishPhase.FAILED, publishResult = null) }
            return
        }
        val current = _state.value.editor
        val layoutId = _state.value.layoutId
        // The climb is always durable locally before it is sent, so a relay
        // failure leaves something to retry rather than nothing.
        val uuid = withContext(ioDispatcher) {
            val existing = _state.value.loadedDraftUuid
            if (existing != null) {
                if (drafts.updateDraft(existing, current, layoutId)) existing else null
            } else {
                drafts.saveDraft(current, layoutId)
            }
        }
        if (uuid == null) {
            _state.update { it.copy(publishPhase = PublishPhase.FAILED, error = EditorError.SAVE_FAILED) }
            return
        }
        val sizeLabel = _state.value.boardSize?.name.orEmpty()
        val result = publisher.publish(
            uuid = uuid,
            layoutId = layoutId,
            boardBrand = BoardBrand.fromWire(current.boardBrand),
            state = current,
            sizeLabel = sizeLabel,
            autoNote = autoNoteText.takeIf { it.isNotBlank() }?.let {
                AutoNoteSpec(
                    template = it,
                    maintainerPubkeyHex = maintainerPubkeyHex.takeIf { hex -> hex.isNotBlank() },
                    mentionMaintainer = mentionMaintainer,
                )
            },
        )
        val refreshed = withContext(ioDispatcher) { drafts.drafts(current.boardBrand) }
        _state.update {
            it.copy(
                publishPhase = if (result.failure == PublishFailure.NONE) PublishPhase.PUBLISHED else PublishPhase.FAILED,
                publishResult = result,
                loadedDraftUuid = uuid,
                drafts = refreshed,
            )
        }
    }

    fun close() = scope.cancel()

    // ── Internals ────────────────────────────────────────────────

    private suspend fun loadBoardData() {
        val selection = preferences.boardSelection()
        val brand = BoardBrand.fromWire(selection.boardBrand)
        val layoutId = selection.layoutId.toLong()

        if (brand == BoardBrand.MOONBOARD) {
            // No Aurora placement / LED / image rows exist: the MoonBoard renders
            // from its bundled image and measured hold map, and the editor stores
            // brand-native route roles (42/43/44).
            val options = CreatorAnglePicker.optionsFor(BoardBrand.MOONBOARD, layoutId, emptyList())
            val variant = MoonBoardVariant.fromLayoutId(layoutId)
            _state.update {
                it.copy(
                    isLoading = false,
                    layoutId = layoutId,
                    productSizeId = selection.productSizeId,
                    angleOptions = options,
                    boardImagePaths = emptyList(),
                    moonLayoutPath = variant?.layoutJsonAssetPath() ?: "",
                    editor = it.editor.copy(
                        angle = CreatorAnglePicker.snapAngle(it.editor.angle ?: selection.angle, options),
                        boardBrand = BoardBrand.MOONBOARD.wireValue,
                        activeBrush = HoldRole.ROUTE_START,
                    ),
                )
            }
            return
        }

        val brandWire = brand.wireValue
        val load = withContext(ioDispatcher) {
            BoardLoad(
                size = boardRepository.getProductSize(selection.productSizeId, brandWire),
                // Only the set ids actually rendered for this (brand, layout):
                // otherwise tap detection snaps to holds that are not in the photo.
                placements = boardRepository
                    .getPlacementsForLayout(selection.productSizeId, selection.layoutId, brandWire)
                    .associateBy { it.placementId.toInt() },
                supportedAngles = boardRepository.getSupportedAnglesForLayout(selection.layoutId, brandWire),
            )
        }
        val options = CreatorAnglePicker.optionsFor(brand, layoutId, load.supportedAngles)
        _state.update {
            it.copy(
                isLoading = false,
                layoutId = layoutId,
                productSizeId = selection.productSizeId,
                angleOptions = options,
                boardSize = load.size,
                placements = load.placements,
                boardImagePaths = boardImageCandidatePaths(brand, selection.productSizeId.toLong(), layoutId),
                moonLayoutPath = "",
                editor = it.editor.copy(
                    angle = CreatorAnglePicker.snapAngle(it.editor.angle ?: selection.angle, options),
                    boardBrand = brandWire,
                    activeBrush = it.editor.activeBrush ?: HoldRole.START,
                ),
            )
        }
    }

    private class BoardLoad(
        val size: BoardSize?,
        val placements: Map<Int, BoardPlacement>,
        val supportedAngles: List<Int>,
    )

    private suspend fun seedFromEdit(uuid: String) {
        val kilterStatus = withContext(ioDispatcher) { boardRepository.getKilterPublishState(uuid)?.status }
        if (kilterStatus == "synced" || kilterStatus == "diverged") {
            _state.update { it.copy(isLoading = false, error = EditorError.EDIT_LOCKED_BY_KILTER) }
            return
        }
        val found = withContext(ioDispatcher) { findClimb(uuid, allowCatalogue = false) } ?: return
        seed(found.first, found.second, keepName = true, editingExisting = true)
    }

    private suspend fun seedFromFork(uuid: String) {
        val found = withContext(ioDispatcher) { findClimb(uuid, allowCatalogue = true) } ?: return
        seed(found.first, found.second, keepName = false, editingExisting = false)
    }

    /**
     * Resolves the source row for an edit or a remix, in Android's order: the
     * user's own climbs first (an own climb stays `source='local'` until the
     * relay echo, which the self-filter blocks by design, so the community query
     * alone would miss it), then every community climb, then — for a remix only —
     * the raw catalogue row.
     */
    private fun findClimb(uuid: String, allowCatalogue: Boolean): Pair<CommunityClimbRow, Pair<Int, Int?>?>? {
        val own = drafts.ownPubkey()
            ?.let { pubkey ->
                boardRepository.getMyClimbs(pubkey).firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            }
        val row = own
            ?: boardRepository.getCommunityClimbs().firstOrNull { it.uuid.equals(uuid, ignoreCase = true) }
            ?: (if (allowCatalogue) catalogueRow(uuid) else null)
            ?: return null
        return row to boardRepository.getClimbStatsForUuid(row.uuid)
    }

    /** A catalogue climb rendered as a community row so a remix can seed from it. */
    private fun catalogueRow(uuid: String): CommunityClimbRow? {
        val angle = _state.value.editor.angle ?: BrowsePreferences.DEFAULT_ANGLE
        val climb = boardRepository.getClimbByUuid(uuid, angle) ?: return null
        return CommunityClimbRow(
            uuid = climb.uuid,
            name = climb.name,
            setterUsername = climb.setterUsername,
            description = climb.description,
            framesText = climb.frames,
            source = climb.source,
            syncStatus = climb.syncStatus ?: "synced",
            createdByPubkey = climb.createdByPubkey,
            nostrEventId = null,
            nostrDTag = null,
            framesHash = null,
            createdAt = null,
            moveCount = climb.storedMoveCount,
            kilterSyncedAt = null,
            layoutId = climb.layoutId,
            boardBrand = climb.boardBrand,
        )
    }

    /**
     * Seeds the working state from an existing row. Frames are folded into this
     * board's editor palette, so a remix of an Aurora catalogue climb (roles 1-4)
     * still matches the brushes, chip counters and validation.
     */
    private fun seed(
        row: CommunityClimbRow,
        stats: Pair<Int, Int?>?,
        keepName: Boolean,
        editingExisting: Boolean,
    ) {
        val brand = _state.value.editor.boardBrand
        val seeded = ClimbEditorState(
            selectedHolds = parseHoldsForEditor(row.framesText, BoardBrand.fromWire(brand)),
            boardBrand = brand,
            activeBrush = defaultBrushFor(brand),
            name = if (keepName) row.name else row.name + if (!row.name.endsWith("Remix")) " Remix" else "",
            description = row.description,
            angle = stats?.first ?: _state.value.editor.angle,
            // Legacy rows can predate the stats column; the publishable default
            // beats hitting the publish-time precondition.
            setterGradeId = stats?.second ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID,
        )
        undoStack.clear()
        redoStack.clear()
        _state.update {
            it.copy(
                editor = seeded,
                // A remix is a brand-new climb: it must not overwrite its source.
                loadedDraftUuid = if (keepName) row.uuid else null,
                isEditingExisting = editingExisting,
                canUndo = false,
                canRedo = false,
                validationIssues = validate(seeded),
            )
        }
    }

    private fun defaultBrushFor(brandWire: String): Int =
        if (BoardBrand.fromWire(brandWire) == BoardBrand.MOONBOARD) HoldRole.ROUTE_START else HoldRole.START

    /** Paint order of the brand's own role codes, used by [cycleHoldRole]. */
    private fun rolePalette(brand: BoardBrand): List<Int> =
        if (brand == BoardBrand.MOONBOARD) {
            // The MoonBoard has no foot role.
            listOf(HoldRole.ROUTE_START, HoldRole.ROUTE_HAND, HoldRole.ROUTE_FINISH)
        } else {
            listOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH, HoldRole.FOOT)
        }

    private fun validate(state: ClimbEditorState): List<ClimbValidation.Issue> = ClimbValidation.validate(
        holds = state.selectedHolds,
        name = state.name,
        description = state.description,
        angle = state.angle,
        setterGradeId = state.setterGradeId,
    )

    private fun mutate(transform: ClimbEditorState.() -> ClimbEditorState) {
        push(_state.value.editor.transform())
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
                // Live validation keeps the publish-ready banner honest as the
                // user types, instead of only updating on the publish tap.
                validationIssues = validate(next),
            )
        }
    }

    internal companion object {
        const val UNDO_DEPTH = 50
    }
}
