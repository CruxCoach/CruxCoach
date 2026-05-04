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
    /** Set true after a successful publish that didn't reach Kilter
     *  because the user has no connection. The screen reads it once,
     *  shows a "verbinde Kilter"-Snackbar with a [Verbinden]-Action,
     *  and clears it via [clearKilterConnectNudge]. */
    val showKilterConnectNudge: Boolean = false,
    /** Editor-visible Kilter-side publish outcome. Distinct from
     *  [showKilterConnectNudge] (which fires only when the user has no
     *  Kilter login at all): this carries the actual outcome — synced /
     *  diverged / failed / skipped — when Kilter publishing was
     *  attempted. The screen reads it as a follow-up Snackbar variant
     *  after the success terminal, then clears via
     *  [clearKilterPublishOutcome]. Pre-fix every non-Skipped(no-login)
     *  outcome was silent and the user navigated away thinking both
     *  destinations succeeded (audit health-monitoring/011). */
    val kilterPublishOutcome: com.cruxcoach.android.data.kilter.KilterClimbPublisher.Outcome? = null,
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
    /** True when publish is paused awaiting the "set up profile?" hint
     *  decision. UI shows a dialog → either navigates to the profile
     *  screen (`profileSetupRequested`) or dismisses (continues publish).
     *  Mutually exclusive with [pendingPublishConfirm] in practice — dup
     *  check runs first, profile hint second. */
    val pendingProfileHint: Boolean = false,
    /** One-shot flag: UI navigates to the profile editor and clears it
     *  via [acknowledgeProfileSetupNavigated]. */
    val profileSetupRequested: Boolean = false,
    /** Per-publish "also post a public Kind-1 note" toggle. Initialised
     *  from the global [UserPreferences.autoNoteEnabled] when the editor
     *  opens; the user can flip it for a single publish without
     *  affecting the global default. */
    val alsoPostNote: Boolean = false,
)

@HiltViewModel
class ClimbEditorViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val repository: ClimbCreatorRepository,
    private val boardRepository: BoardRepository,
    private val userPreferences: UserPreferences,
    private val bleConnection: BoardBleConnection,
    private val autosave: EditorAutosave,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val nostrSigner: com.cruxcoach.android.nostr.NostrSigner,
    private val nostrProfileManager: com.cruxcoach.android.payment.NostrProfileManager,
) : ViewModel() {

    private val _state = MutableStateFlow(ClimbEditorUiState())
    val state: StateFlow<ClimbEditorUiState> = _state.asStateFlow()

    private val undoStack = ArrayDeque<ClimbEditorState>()
    private val redoStack = ArrayDeque<ClimbEditorState>()

    /** Debounced autosave job — restarted on every editor mutation. */
    private var autosaveJob: kotlinx.coroutines.Job? = null
    /** Session-scoped counter of consecutive autosave write failures.
     *  Drives a future user-visible "autosave is broken — save manually"
     *  Snackbar; for now it lives in the log line for triage. */
    private var autosaveFailures: Int = 0

    /** Debounced heatmap-recompute job — restarted on holds change. */
    private var heatmapJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            // Without try/catch here a SQLite/DataStore read failure would
            // leave isLoading=true forever and the screen would show an
            // indefinite spinner. Flip isLoading=false + surface a generic
            // error so the user sees a Snackbar and can retry by reopening
            // the editor instead of being stuck.
            try {
                loadBoardData()
                handleNavigationArgs()
            } catch (e: Exception) {
                Log.w(TAG, "ClimbEditor init load failed", e)
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = appContext.getString(com.cruxcoach.android.R.string.climb_creator_load_failed),
                    )
                }
            }
        }
        viewModelScope.launch {
            userPreferences.ledHoldColors.collect { colors ->
                _state.update { it.copy(ledColors = colors) }
            }
        }
        // Seed the per-publish auto-note toggle from the global default
        // once on init. Subsequent global flips don't fight the user's
        // per-session override; they only matter the next time the
        // editor opens.
        viewModelScope.launch {
            runCatching { userPreferences.autoNoteEnabled.first() }
                .onFailure { Log.w(TAG, "autoNoteEnabled read failed; default=false", it) }
                .getOrNull()?.let { initial ->
                    _state.update { it.copy(alsoPostNote = initial) }
                }
        }
    }

    /**
     * Read SavedStateHandle for `forkUuid` (Remix from existing climb) /
     * `editUuid` (edit your own published climb), and surface any
     * recovered autosave to the user. Drafts navigate via the drawer
     * instead so they're not handled here.
     */
    private suspend fun handleNavigationArgs() {
        val editUuid: String? = savedStateHandle["editUuid"]
        val forkUuid: String? = savedStateHandle["forkUuid"]
        if (editUuid != null) {
            val source = withContext(Dispatchers.IO) {
                boardRepository.getMyClimbs("__none__")
                    .firstOrNull { it.uuid.equals(editUuid, ignoreCase = true) }
                    ?: boardRepository.getCommunityClimbs()
                        .firstOrNull { it.uuid.equals(editUuid, ignoreCase = true) }
            }
            if (source != null) seedFromEdit(source)
        } else if (forkUuid != null) {
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
                            kilterSyncedAt = null,
                        )
                    }
            }
            if (source != null) seedFromFork(source)
        }
        // Autosave restore offer — only when the editor opens *fresh* (no fork/edit seed).
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

    /**
     * Edit-in-place: same uuid, no "Remix" suffix. Re-publish via
     * [doPublish] re-uses [ClimbEditorUiState.loadedDraftUuid] which
     * keeps the Nostr d-tag stable so the relay replaces the original
     * Kind-30078 event instead of duplicating it.
     */
    private suspend fun seedFromEdit(source: CommunityClimbRow) {
        val holds = com.cruxcoach.domain.board.BoardClimbParser.parseFrames(source.framesText)
            .associate { it.placementId to it.roleId }
        val stats = withContext(Dispatchers.IO) { boardRepository.getClimbStatsForUuid(source.uuid) }
        val currentAngle = _state.value.editor.angle
        val seeded = ClimbEditorState(
            selectedHolds = holds,
            name = source.name,
            description = source.description,
            angle = stats?.first ?: currentAngle,
            setterGradeId = stats?.second,
        )
        undoStack.clear(); redoStack.clear()
        _state.update { it.copy(editor = seeded, loadedDraftUuid = source.uuid, canUndo = false, canRedo = false) }
        viewModelScope.launch { syncLeds() }
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
    fun setAlsoPostNote(value: Boolean) {
        _state.update { it.copy(alsoPostNote = value) }
    }

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
        val issues = ClimbValidation.validate(
            current.selectedHolds, current.name, current.description, current.angle,
        )
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
                // Cancel pending debounce before clearing — see [dismissAutosave].
                autosaveJob?.cancel()
                autosave.clear()
                // Refresh the drafts list so the drawer shows the new
                // entry immediately (was stale if the drawer was already
                // open, and required close+reopen to repopulate). Plus
                // pin loadedDraftUuid so a follow-up "Save" updates this
                // row in place instead of creating a duplicate.
                val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
                val drafts = withContext(Dispatchers.IO) {
                    boardRepository.getDraftClimbs(pubkey)
                }
                _state.update { s ->
                    s.copy(drafts = drafts, loadedDraftUuid = uuid)
                }
                onSaved(uuid)
            } catch (e: Exception) {
                Log.w(TAG, "saveDraft failed", e)
                _state.update {
                    it.copy(errorMessage = appContext.getString(com.cruxcoach.android.R.string.climb_creator_save_failed))
                }
            }
        }
    }

    /**
     * Publish — first runs duplicate detection. If a duplicate is found,
     * surface the dialog and pause; the actual publish call is gated on
     * [confirmPublishWithDuplicate]. If no duplicate, proceeds straight
     * to save + Nostr push.
     */
    fun publish(sizeLabel: String, autoNoteTemplate: String? = null) {
        val current = _state.value.editor
        val issues = ClimbValidation.validate(
            current.selectedHolds, current.name, current.description, current.angle,
        )
        if (issues.isNotEmpty()) {
            _state.update { it.copy(validationIssues = issues) }
            return
        }
        // Atomic claim: a second tap arriving while the first publish's
        // findDuplicate / shouldShowProfileHint coroutine is in flight
        // would otherwise enter publish() again before doPublish flipped
        // isPublishing — launching a parallel saveAndPublish (duplicate
        // Nostr events, duplicate Kilter API calls, race on
        // loadedDraftUuid). StateFlow.update is the CAS primitive: if
        // current.isPublishing is true the second caller leaves it as-is
        // and we bail; the first caller flips it before launching.
        var claimed = false
        _state.update { s ->
            if (s.isPublishing) {
                claimed = false; s
            } else {
                claimed = true
                s.copy(isPublishing = true, errorMessage = null)
            }
        }
        if (!claimed) {
            Log.d(TAG, "publish ignored — already in flight")
            return
        }
        viewModelScope.launch {
            // findDuplicate hits SQLDelight (frames-hash index lookup);
            // shouldShowProfileHint hits DataStore + maybe a relay.
            // Either can throw under DB lock contention / mid-migration
            // / corrupted prefs. Pre-fix the throw escaped the coroutine
            // silently — the publish button stayed in `isPublishing=true`
            // forever (CAS-claimed, never released), the UI showed an
            // indefinite spinner, no Snackbar, and the user could only
            // recover by killing the app. Catch + release the claim +
            // surface the existing localized publish-failed Snackbar.
            try {
                val dup = withContext(Dispatchers.IO) { repository.findDuplicate(current) }
                // Skip the dialog if the duplicate IS the draft we're updating.
                val ownLoaded = _state.value.loadedDraftUuid
                val isSelfReplace = dup != null && dup.uuid == ownLoaded
                if (dup != null && !isSelfReplace) {
                    _state.update { it.copy(duplicateOf = dup, pendingPublishConfirm = true) }
                    return@launch
                }
                // Profile-Hint: only on first publish without a Kind 0
                // profile and with hint not yet dismissed for this identity.
                if (shouldShowProfileHint()) {
                    _state.update { it.copy(pendingProfileHint = true) }
                    return@launch
                }
                doPublish(sizeLabel, autoNoteTemplate)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "publish pre-flight failed", e)
                _state.update {
                    it.copy(
                        isPublishing = false,
                        errorMessage = appContext.getString(com.cruxcoach.android.R.string.climb_creator_publish_failed),
                    )
                }
            }
        }
    }

    /** True when the user has no Kind 0 profile yet and hasn't dismissed
     *  the hint. Cheap check (cache-only) — never blocks the publish path
     *  on a relay round-trip. */
    private suspend fun shouldShowProfileHint(): Boolean {
        if (userPreferences.profileHintDismissed.first()) return false
        val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull() ?: return false
        // getProfile reads the local cache first; a missing profile only
        // triggers a relay fetch, which we tolerate (~few hundred ms,
        // happens at most once per identity-lifetime). If it fails, we
        // err on the side of not interrupting the publish.
        val profile = runCatching { nostrProfileManager.getProfile(pubkey) }.getOrNull()
        return profile?.displayName.isNullOrBlank()
    }

    /** User chose "set up profile" — navigate flag for the screen, no
     *  publish. Editor state is preserved; user comes back, taps publish
     *  again, and the hint won't fire (profile now set). Releases the
     *  isPublishing claim so the user can re-tap publish later.
     *
     *  setProfileHintDismissed is a DataStore write; pre-fix a write
     *  failure escaped the coroutine silently and the dialog stayed open
     *  with no path forward (the navigate flag never flipped). Catch +
     *  log + still update UI state so the user isn't trapped — worst case
     *  the hint will fire again next time, which is a recoverable UX
     *  glitch instead of a stuck dialog.
     */
    fun acceptProfileHint() {
        viewModelScope.launch {
            runCatching { userPreferences.setProfileHintDismissed(true) }
                .onFailure { Log.w(TAG, "setProfileHintDismissed failed", it) }
            _state.update {
                it.copy(
                    pendingProfileHint = false,
                    profileSetupRequested = true,
                    isPublishing = false,
                )
            }
        }
    }

    /** User chose "skip" — record dismissal so the hint doesn't fire
     *  again for this identity, then proceed with publish. Same
     *  guarantee as [acceptProfileHint]: a DataStore write failure
     *  doesn't strand the user on the dialog. */
    fun dismissProfileHintAndPublish(sizeLabel: String, autoNoteTemplate: String? = null) {
        viewModelScope.launch {
            runCatching { userPreferences.setProfileHintDismissed(true) }
                .onFailure { Log.w(TAG, "setProfileHintDismissed failed", it) }
            _state.update { it.copy(pendingProfileHint = false) }
            doPublish(sizeLabel, autoNoteTemplate)
        }
    }

    /** Screen calls this after navigating to the profile editor so the
     *  one-shot flag clears. */
    fun acknowledgeProfileSetupNavigated() {
        _state.update { it.copy(profileSetupRequested = false) }
    }

    /** User accepted the duplicate-warning dialog → continue publish. */
    fun confirmPublishWithDuplicate(sizeLabel: String, autoNoteTemplate: String? = null) {
        _state.update { it.copy(duplicateOf = null, pendingPublishConfirm = false) }
        doPublish(sizeLabel, autoNoteTemplate)
    }

    /** User declined the duplicate-warning dialog → stay in editor.
     *  Releases the isPublishing claim taken by publish(). */
    fun cancelPublishOnDuplicate() {
        _state.update {
            it.copy(duplicateOf = null, pendingPublishConfirm = false, isPublishing = false)
        }
    }

    private fun doPublish(sizeLabel: String, autoNoteTemplate: String? = null) {
        _state.update { it.copy(isPublishing = true, errorMessage = null) }
        val current = _state.value.editor
        val existingUuid = _state.value.loadedDraftUuid
        // Resolve the auto-note spec once at the publish-edge so the
        // repository + publisher don't need to know about ViewModel
        // state. Empty/null template + disabled toggle both collapse to
        // null (= skip Kind-1).
        val noteSpec = if (_state.value.alsoPostNote && !autoNoteTemplate.isNullOrBlank()) {
            com.cruxcoach.android.community.CommunityClimbPublisher.AutoNoteSpec(autoNoteTemplate)
        } else null
        viewModelScope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    repository.saveAndPublish(current, sizeLabel, existingUuid = existingUuid, autoNote = noteSpec)
                }
            } catch (e: Exception) {
                Log.w(TAG, "publish failed", e)
                // The publisher's `accepted == 0` path persisted the
                // climb as `sync_status='failed'` AND threw — so the
                // climb's bytes are durable in the DB. Schedule a
                // retry-now so the user's "I just tapped publish"
                // intent gets the next attempt before the periodic
                // 6h tick. Periodic schedule is wired in CruxCoachApp;
                // this is the immediate-retry nudge.
                runCatching {
                    com.cruxcoach.android.community.CommunityPublishRetryWorker.runOnce(appContext)
                }
                _state.update {
                    it.copy(
                        isPublishing = false,
                        errorMessage = appContext.getString(com.cruxcoach.android.R.string.climb_creator_publish_failed_will_retry),
                    )
                }
                return@launch
            }
            // Cancel pending debounce before clearing — see [dismissAutosave].
            autosaveJob?.cancel()
            autosave.clear()
            _state.update {
                it.copy(
                    isPublishing = false,
                    publishedUuid = outcome.uuid,
                    showKilterConnectNudge = outcome.nudgeToConnectKilter,
                    kilterPublishOutcome = outcome.kilterOutcome,
                )
            }
        }
    }

    fun clearKilterConnectNudge() {
        _state.update { it.copy(showKilterConnectNudge = false) }
    }

    /** Screen calls this after rendering the kilter-side outcome
     *  Snackbar so it doesn't fire again on rotation/recomposition. */
    fun clearKilterPublishOutcome() {
        _state.update { it.copy(kilterPublishOutcome = null) }
    }

    // ── Autosave restore offer ──────────────────────────────────

    fun acceptAutosave() {
        val offer = _state.value.autosaveOffer ?: return
        applyEditor(offer.state)
        _state.update { it.copy(autosaveOffer = null) }
    }

    fun dismissAutosave() {
        // Cancel the in-flight 500ms debounce synchronously: an editor
        // mutation that landed milliseconds before the dismiss would
        // otherwise re-write the autosave keys back to DataStore right
        // after `clear()` runs, silently losing the user's discard intent.
        autosaveJob?.cancel()
        viewModelScope.launch { autosave.clear() }
        _state.update { it.copy(autosaveOffer = null) }
    }

    // ── Drafts drawer ───────────────────────────────────────────

    fun openDraftsSheet() {
        viewModelScope.launch {
            try {
                val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
                val drafts = withContext(Dispatchers.IO) { boardRepository.getDraftClimbs(pubkey) }
                _state.update { it.copy(drafts = drafts, draftsSheetOpen = true) }
            } catch (e: Exception) {
                Log.w(TAG, "openDraftsSheet failed", e)
                _state.update {
                    it.copy(errorMessage = appContext.getString(com.cruxcoach.android.R.string.climb_creator_drafts_load_failed))
                }
            }
        }
    }

    fun closeDraftsSheet() {
        _state.update { it.copy(draftsSheetOpen = false) }
    }

    /**
     * Load a draft into the editor. The hold map + metadata replace the
     * current editor state; the loaded uuid is tracked so re-saving
     * updates the same row in place. Doesn't touch any other drafts.
     *
     * `angle` and `setterGradeId` are pulled from `climb_stats` (a
     * separate row from `climbs`); without them the restored draft would
     * arrive with `angle=null`, the bottom-bar would falsely report
     * "ready to publish", and the next save would crash through the
     * IllegalArgumentException path with an English message.
     */
    fun loadDraft(draft: CommunityClimbRow) {
        viewModelScope.launch {
            try {
                val holds = com.cruxcoach.domain.board.BoardClimbParser.parseFrames(draft.framesText)
                    .associate { it.placementId to it.roleId }
                val stats = withContext(Dispatchers.IO) { boardRepository.getClimbStatsForUuid(draft.uuid) }
                val currentAngle = _state.value.editor.angle
                val seeded = ClimbEditorState(
                    selectedHolds = holds,
                    name = draft.name,
                    description = draft.description,
                    angle = stats?.first ?: currentAngle,
                    setterGradeId = stats?.second,
                )
                // Reset undo stacks — we're starting from a fresh draft snapshot.
                undoStack.clear(); redoStack.clear()
                _state.update {
                    it.copy(
                        editor = seeded,
                        loadedDraftUuid = draft.uuid,
                        draftsSheetOpen = false,
                        canUndo = false,
                        canRedo = false,
                    )
                }
                syncLeds()
            } catch (e: Exception) {
                Log.w(TAG, "loadDraft failed for uuid=${draft.uuid}", e)
                _state.update {
                    it.copy(errorMessage = appContext.getString(com.cruxcoach.android.R.string.climb_creator_drafts_load_failed))
                }
            }
        }
    }

    fun deleteDraft(uuid: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wasLoaded = _state.value.loadedDraftUuid == uuid
                boardRepository.deleteLocalClimb(uuid)
                // If the deleted row was the currently-loaded draft, also
                // wipe the autosave snapshot. Without this the editor's
                // next open would offer a "Restore previous session?" for
                // the deleted draft (single-slot autosave isn't tied to
                // any particular draft uuid), and accepting would
                // re-materialise the row the user just discarded.
                if (wasLoaded) {
                    autosaveJob?.cancel()
                    autosave.clear()
                }
                // Refresh the drawer's list.
                val pubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
                val drafts = boardRepository.getDraftClimbs(pubkey)
                _state.update { s ->
                    s.copy(drafts = drafts, loadedDraftUuid = if (wasLoaded) null else s.loadedDraftUuid)
                }
            } catch (e: Exception) {
                Log.w(TAG, "deleteDraft failed for uuid=$uuid", e)
                _state.update {
                    it.copy(errorMessage = appContext.getString(com.cruxcoach.android.R.string.climb_creator_drafts_delete_failed))
                }
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
            try {
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Heatmap is a non-essential overlay — a SQL throw or
                // parser error during recompute shouldn't crash the
                // editor or fail the publish. Pre-fix the throw escaped
                // the coroutine silently and the overlay just stayed
                // stale until the next hold-change. Log for triage,
                // leave the previous heatmap visible.
                Log.w(TAG, "heatmap recompute failed", e)
            }
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
            angle = next.angle,
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

    /** Debounced write to DataStore; latest call wins.
     *
     *  Pre-fix the autosave write was un-guarded — a DataStore failure
     *  (disk-full, file corruption, permission revoked) would crash the
     *  coroutine silently. The user kept editing, no autosave was being
     *  written, and the next reopen offered no restore even though they
     *  were typing actively. Now we catch + log + bump a session-scoped
     *  failure counter; the recover UI doesn't yet surface the warning
     *  (deferred — needs UX), but the log line is enough to triage from
     *  a bug report.
     */
    private fun scheduleAutosave(next: ClimbEditorState) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(AUTOSAVE_DEBOUNCE_MS)
            try {
                withContext(Dispatchers.IO) { autosave.save(next) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                autosaveFailures++
                Log.w(TAG, "autosave write failed (session-failures=$autosaveFailures)", e)
            }
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
