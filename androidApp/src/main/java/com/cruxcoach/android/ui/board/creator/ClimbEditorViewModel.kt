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
     *  destinations succeeded. */
    val kilterPublishOutcome: com.cruxcoach.android.data.kilter.KilterClimbPublisher.Outcome? = null,
    /** Optional Kind-1 auto-note outcome. Null = user didn't opt into
     *  auto-note; true = ≥ 1 relay accepted; false = 0 relays accepted
     *  (the climb is up but the announcement didn't go through). The
     *  screen renders a distinct snackbar when this is `false` so a
     *  silent-published outcome no longer hides relay reach loss. */
    val autoNotePublished: Boolean? = null,
    val errorMessage: String? = null,
    /** Non-error informational snackbar (e.g. "Sync läuft, Veröffentlichung
     *  startet danach"). Distinct from errorMessage so the screen can
     *  render it with a different colour and the user doesn't read it
     *  as a failure. Cleared by the screen after rendering. */
    val infoMessage: String? = null,
    /** Loaded-draft uuid — re-saving updates this row in place. */
    val loadedDraftUuid: String? = null,
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
    /** User-editable Kind-1 note text. null = field hasn't been
     *  initialised yet (the editor's auto-note toggle hasn't been
     *  flipped to ON in this session, OR the screen hasn't seeded the
     *  default template into it yet). When non-null this string is
     *  what gets sent verbatim to publishKind1Note's template renderer
     *  — placeholders like `{name}`, `{naddr}`, `{npub_cruxcoach}`,
     *  `{cruxcoach_url}` are still substituted, so the user can keep
     *  the dynamic parts and only tweak the static prose around them. */
    val autoNoteText: String? = null,
    /** True when the editor is editing an already-published climb (came
     *  in via `editUuid`). In that case "Save as draft" is hidden — a
     *  published climb is not a draft, and re-saving it as one would
     *  create UX ambiguity ("did my edits go live?"). Fork mode stays
     *  false because forking creates a fresh climb that *can* be
     *  parked as a draft. */
    val isEditingExisting: Boolean = false,
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
    private val climbNavState: com.cruxcoach.android.ui.navigation.ClimbNavigationState,
    /** Read-only access to surface a "publish waits for sync" snackbar
     *  the moment the user taps Veröffentlichen during an in-flight
     *  board sync — without it the editor just looks frozen for tens
     *  of seconds while ClimbCreatorRepository.awaitBoardSyncQuiescent
     *  blocks waiting for the importer's writer-lock to release. */
    private val boardSyncManager: com.cruxcoach.android.data.BoardSyncManager,
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
        Log.i(TAG, "handleNavigationArgs: editUuid=$editUuid forkUuid=$forkUuid")
        // getMyClimbs filters on `created_by_pubkey = :pubkey`. Pre-fix
        // we passed the literal sentinel "__none__" — `getMyClimbs` then
        // matched zero rows and we silently fell through to
        // getCommunityClimbs (`source='nostr'` only). Own-published
        // climbs stay at `source='local'` until the live-sub echo
        // upserts them, but the E5 self-filter blocks that upsert by
        // design — so the editor opened EMPTY whenever the user tapped
        // ⋮ → Edit on a climb they had just published. Resolve the
        // active pubkey lazily; null only happens during a signer-init
        // race + then both queries no-op cleanly.
        val ownPubkey = runCatching { nostrSigner.getPublicKeyHex() }.getOrNull()
        if (editUuid != null) {
            // Kilter-immutability gate: a climb that was successfully
            // mirrored to Kilter ('synced') OR whose update Kilter
            // refused ('diverged') is frozen on the Kilter side. Local
            // edits would diverge from Kilter (cron pulls Kilter →
            // Blossom-bundle is stale w.r.t. our latest Nostr
            // replaceable). Refuse to load the editor and surface a
            // localized "use Remix instead" hint. The detail-screen
            // already greys the Edit menu item; this is a defensive
            // backstop for direct deep-links / older nav state.
            val kilterState = withContext(Dispatchers.IO) {
                runCatching { boardRepository.getKilterPublishState(editUuid) }.getOrNull()
            }
            if (kilterState?.status == "synced" || kilterState?.status == "diverged") {
                Log.w(TAG, "edit refused for $editUuid — kilter_status=${kilterState.status}")
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = appContext.getString(
                            com.cruxcoach.android.R.string.climb_editor_load_failed_kilter_locked
                        ),
                    )
                }
                return
            }
            Log.i(TAG, "edit gate ok kilter_status=${kilterState?.status} → proceeding to seedFromEdit")
            val source = withContext(Dispatchers.IO) {
                (ownPubkey?.let {
                    boardRepository.getMyClimbs(it)
                        .firstOrNull { row -> row.uuid.equals(editUuid, ignoreCase = true) }
                })
                    ?: boardRepository.getCommunityClimbs()
                        .firstOrNull { it.uuid.equals(editUuid, ignoreCase = true) }
            }
            Log.i(TAG, "edit-source lookup for $editUuid → found=${source != null} (uuid=${source?.uuid}, name=${source?.name})")
            if (source != null) seedFromEdit(source)
            else Log.w(TAG, "edit-source NOT FOUND for editUuid=$editUuid — editor will open empty + publish would create a new climb")
        } else if (forkUuid != null) {
            val source = withContext(Dispatchers.IO) {
                (ownPubkey?.let {
                    boardRepository.getMyClimbs(it)
                        .firstOrNull { row -> row.uuid.equals(forkUuid, ignoreCase = true) }
                })
                    ?: boardRepository.getCommunityClimbs()
                        .firstOrNull { it.uuid.equals(forkUuid, ignoreCase = true) }
                    // Final fallback: a raw climb (Kilter source) by uuid via the existing browse query.
                    ?: boardRepository.getClimbByUuid(forkUuid, angle = 40)?.let { c ->
                        CommunityClimbRow(
                            uuid = c.uuid, name = c.name + " Remix", setterUsername = c.setterUsername,
                            description = c.description, framesText = c.frames, source = "kilter",
                            syncStatus = "synced", createdByPubkey = null, nostrEventId = null,
                            nostrDTag = null, framesHash = null, createdAt = null, moveCount = c.storedMoveCount,
                            kilterSyncedAt = null, layoutId = c.layoutId,
                        )
                    }
            }
            if (source != null) seedFromFork(source)
        }
        // Auto-restore the last autosave — only when the editor opens
        // *fresh* (no fork/edit seed). Pre-fix the only guard was
        // `selectedHolds.isEmpty()`, which silently fell back to the
        // autosave whenever seedFromEdit/seedFromFork failed (frames-
        // parse error, missing stats row, source-resolve miss). Result:
        // user tapped ⋮ → Edit on a published climb but got
        // *yesterday's draft* instead. Hard-gate the restore on "no
        // navigation args present" — if the user navigated here
        // explicitly to edit/remix something, the autosave never wins.
        // The drafts-drawer load path uses [loadDraft] directly which
        // doesn't go through this method, so it's also unaffected.
        if (editUuid == null && forkUuid == null) {
            val snapshot = withContext(Dispatchers.IO) { autosave.load() }
            if (snapshot != null && _state.value.editor.selectedHolds.isEmpty()) {
                applyEditor(snapshot.state)
            }
        }
        // Seed validationIssues against whatever final state we landed
        // on (empty editor, fork, edit, or restored autosave). Without
        // this, the publish-ready banner shows the "Valid" checkmark on
        // a fresh editor open even though the climb is empty — and the
        // Save / Publish buttons would be enabled. The state only
        // refreshed after the first user-driven applyEditor call.
        val current = _state.value.editor
        _state.update {
            it.copy(
                validationIssues = ClimbValidation.validate(
                    current.selectedHolds, current.name, current.description, current.angle,
                ),
            )
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
        val issues = ClimbValidation.validate(
            seeded.selectedHolds, seeded.name, seeded.description, seeded.angle,
        )
        _state.update {
            it.copy(
                editor = seeded,
                loadedDraftUuid = source.uuid,
                isEditingExisting = true,
                canUndo = false,
                canRedo = false,
                validationIssues = issues,
            )
        }
        viewModelScope.launch { syncLeds() }
    }

    private suspend fun loadBoardData() {
        val sizeId = userPreferences.boardProductSizeId.first()
        val layoutId = userPreferences.boardLayoutId.first()
        val defaultAngle = userPreferences.boardAngle.first()
        val (size, placements, images, ledMap) = withContext(Dispatchers.IO) {
            val size = boardRepository.getProductSize(sizeId)
            // Filter to set_ids actually rendered for this layout — see
            // BoardRepository.getPlacementsForLayout for the why. Without
            // this the editor's tap-detection snaps to placements that
            // belong to other sets and aren't visible in the photo.
            val placements = boardRepository.getPlacementsForLayout(sizeId, layoutId)
                .associateBy { it.placementId.toInt() }
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
    fun setAlsoPostNote(value: Boolean, defaultTemplate: String) {
        _state.update {
            // Seed the editor's auto-note text on first opt-in so the user
            // sees the default template immediately and can tweak it
            // before publishing. Don't overwrite an already-edited value
            // — toggling off+on must preserve the user's prose.
            val seeded = if (value && it.autoNoteText.isNullOrBlank()) defaultTemplate else it.autoNoteText
            it.copy(alsoPostNote = value, autoNoteText = seeded)
        }
    }

    fun setAutoNoteText(text: String) {
        _state.update { it.copy(autoNoteText = text) }
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
                // Cancel pending debounce before clearing — see [clearEditor].
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
                // Tell the browser its cached page is stale. Without this,
                // an in-place edit (e.g. rename) wouldn't show up on
                // back-nav because refreshBoardData only re-runs the
                // search on count changes. See ClimbNavigationState.
                climbNavState.creatorDataChanged = true
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
        // If the bulk board importer holds the writer-lock right now, the
        // saveDraft inside saveAndPublish will sit on awaitBoardSyncQuiescent
        // until isSyncing flips false. Flag that to the user immediately
        // via an info-snackbar so the spinner-while-waiting doesn't read
        // as a hang. The snackbar dismisses itself after ~4s; the publish
        // continues regardless.
        if (boardSyncManager.state.value.isSyncing) {
            _state.update {
                it.copy(infoMessage = appContext.getString(
                    com.cruxcoach.android.R.string.climb_creator_publish_waiting_for_sync
                ))
            }
        }
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
            // Cancel pending debounce before clearing — see [clearEditor].
            autosaveJob?.cancel()
            autosave.clear()
            _state.update {
                it.copy(
                    isPublishing = false,
                    publishedUuid = outcome.uuid,
                    showKilterConnectNudge = outcome.nudgeToConnectKilter,
                    kilterPublishOutcome = outcome.kilterOutcome,
                    autoNotePublished = outcome.autoNotePublished,
                )
            }
            // Browser cache is stale: a publish may have transitioned
            // the row from source='local' to source='nostr', dropped
            // the draft badge, or changed the name/description. See
            // ClimbNavigationState.creatorDataChanged.
            climbNavState.creatorDataChanged = true
        }
    }

    fun clearKilterConnectNudge() {
        _state.update { it.copy(showKilterConnectNudge = false) }
    }

    /** Screen calls this after rendering the kilter-side outcome
     *  Snackbar so it doesn't fire again on rotation/recomposition. */
    /** Screen calls this after rendering the info-snackbar so it
     *  doesn't re-fire on rotation/recomposition. */
    fun clearInfoMessage() {
        _state.update { it.copy(infoMessage = null) }
    }

    fun clearKilterPublishOutcome() {
        _state.update { it.copy(kilterPublishOutcome = null) }
    }

    /** Screen calls this after rendering the auto-note outcome snackbar
     *  so it doesn't re-fire on rotation/recomposition. */
    fun clearAutoNoteOutcome() {
        _state.update { it.copy(autoNotePublished = null) }
    }

    // ── Editor reset (toolbar trash icon) ────────────────────────

    /**
     * Wipe the editor's working state — selectedHolds, name, description,
     * setterGradeId, angle, brush — so the user can start over without
     * tapping each hold off. Also drops `loadedDraftUuid` (next save
     * creates a fresh draft instead of overwriting the previously-loaded
     * one) and clears the autosave slot (so the next editor open doesn't
     * auto-restore the just-cleared state).
     *
     * The undo stack is preserved so a single Undo brings the wiped
     * state back if the tap was a misclick — the cleared autosave slot
     * is the price of that, but the working state is recoverable until
     * the next mutation.
     */
    fun clearEditor() {
        // Cancel the in-flight 500ms debounce synchronously so it
        // doesn't write the still-current state back to DataStore
        // right after we clear it.
        autosaveJob?.cancel()
        viewModelScope.launch { autosave.clear() }
        applyEditor(ClimbEditorState())
        _state.update { it.copy(loadedDraftUuid = null) }
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
                // Recompute validation against the loaded snapshot. Without
                // this, the publish-ready banner (and Save / Publish enable
                // gates) keep whatever issues the editor had BEFORE the
                // load — e.g. "name missing" stays even though the loaded
                // draft has a name, until the next user edit triggers
                // applyEditor.
                val issues = ClimbValidation.validate(
                    seeded.selectedHolds, seeded.name, seeded.description, seeded.angle,
                )
                _state.update {
                    it.copy(
                        editor = seeded,
                        loadedDraftUuid = draft.uuid,
                        draftsSheetOpen = false,
                        canUndo = false,
                        canRedo = false,
                        validationIssues = issues,
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
                // Browser must drop the deleted row on its next refresh.
                // Count typically drops too, but the explicit flag avoids
                // the race-prone "did the count query land before
                // ON_RESUME?" question. See ClimbNavigationState.
                climbNavState.creatorDataChanged = true
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
        // Edit-mode never writes to the shared creator-mode autosave —
        // the original climb still lives on Nostr + locally, so crash
        // recovery for in-flight edits is redundant. Without this guard,
        // backing out of a partial edit leaks edit-mode holds into the
        // next "Neuer Climb" via autosave-restore.
        if (_state.value.isEditingExisting) return
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
     * One-shot push of the current hold map. Used by the Editor screen
     * the moment a board connects so the user sees their in-progress
     * climb without having to tap a new hold first. Subsequent edits
     * are mirrored automatically by [syncLeds] which fires after every
     * [applyEditor].
     *
     * Suspending so callers (notably [BleConnectionViewModel.silentQuickSend])
     * can await the actual BLE write completion before disconnecting —
     * a fire-and-forget launch would let the macro tear down GATT
     * before sendClimb even started writing.
     */
    suspend fun pushCurrentHoldsToBoard() {
        syncLeds()
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
        if (ledMap.isEmpty()) {
            Log.w(TAG, "syncLeds: placementToLed empty — board cannot light up; check loadBoardData ran")
            return
        }
        val holds = cur.editor.selectedHolds.map { (pid, role) -> BoardHold(pid, role) }
        if (holds.isEmpty()) {
            Log.d(TAG, "syncLeds: no holds selected — sending empty frame to clear board")
        }
        // Pass the user's customised hold-colour palette through. Without
        // this, sendClimb falls back to BoardPacketEncoder.roleToColor's
        // hardcoded defaults so a user who picked custom colours in
        // Settings (red starts, etc.) saw their editor preview render
        // those colours on screen but the BLE-mirrored board lit up in
        // the unchanged factory palette. Same pattern as
        // BoardSendController.kt:83.
        val roleColors = cur.ledColors.toRoleColorMap()
        val result = runCatching { bleConnection.sendClimb(holds, ledMap, roleColors) }
        result.fold(
            onSuccess = { Log.i(TAG, "syncLeds: sendClimb returned ok=$it holds=${holds.size}") },
            onFailure = { Log.w(TAG, "syncLeds: sendClimb threw holds=${holds.size}", it) },
        )
    }

    companion object {
        private const val UNDO_DEPTH = 50
        private const val AUTOSAVE_DEBOUNCE_MS = 500L
        private const val HEATMAP_DEBOUNCE_MS = 500L
    }
}
