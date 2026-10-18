package com.cruxcoach.app.ui

import com.cruxcoach.app.creator.ClimbEditor
import com.cruxcoach.app.creator.ClimbEditorUiState
import com.cruxcoach.app.creator.EditorError
import com.cruxcoach.app.creator.PublishPhase
import com.cruxcoach.app.render.AuroraBoardGeometry
import com.cruxcoach.app.render.LedHoldColors
import com.cruxcoach.app.render.MoonBoardMappedGeometry
import com.cruxcoach.app.render.moonBoardImageAssetPath
import com.cruxcoach.app.render.parseMoonBoardLayoutOrNull
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.HoldRole
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.community.ClimbValidation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A hold ready to draw: [x]/[y] are normalized over the board image, origin top-left. */
class CreatorHoldUi(
    val placementId: Int,
    val x: Float,
    val y: Float,
    val argb: Long,
    /** start | hand | finish | foot */
    val roleCode: String,
)

/** One brush chip: its colour is the LED colour the board will actually light. */
class CreatorBrushUi(
    val roleId: Int,
    /** start | hand | finish | foot */
    val code: String,
    val argb: Long,
    val isActive: Boolean,
    /** How many holds currently carry this role. */
    val count: Int,
)

/** One heatmap spot: [x]/[y] normalized like [CreatorHoldUi], [intensity] 0…1. */
class CreatorHeatUi(val placementId: Int, val x: Float, val y: Float, val intensity: Float)

class CreatorDraftUi(
    val uuid: String,
    val name: String,
    val holdCount: Int,
    /** ISO-8601, or empty when the row carries no timestamp. */
    val createdAt: String,
)

/**
 * One validation problem. [code] is a stable lowercase key Swift maps to
 * localized text; [count] carries the offending number where the rule has one
 * (-1 when it has none).
 */
class CreatorIssueUi(val code: String, val count: Int, val limit: Int)

class CreatorScreenState(
    val isLoading: Boolean,
    val boardTitle: String,
    val brandWire: String,
    /** Bundle-relative image candidates, most specific first. Empty = placeholder. */
    val imagePaths: List<String>,
    /** Bundle-relative MoonBoard coordinate map Swift must load, or empty. */
    val moonLayoutPath: String,
    val boardAspect: Float,
    val holds: List<CreatorHoldUi>,
    val brushes: List<CreatorBrushUi>,
    val name: String,
    val description: String,
    val angle: Int,
    val angleOptions: List<Int>,
    val gradeId: Int,
    val gradeLabel: String,
    val minGradeId: Int,
    val maxGradeId: Int,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** True when the climb satisfies every rule and may be saved or published. */
    val isValid: Boolean,
    val issues: List<CreatorIssueUi>,
    val nameMaxLength: Int,
    val descriptionMaxLength: Int,
    /** Empty when no existing climb shares this hold layout. */
    val duplicateUuid: String,
    val duplicateName: String,
    val duplicateBlocksPublish: Boolean,
    val drafts: List<CreatorDraftUi>,
    val draftsSheetOpen: Boolean,
    val loadedDraftUuid: String,
    val isEditingExisting: Boolean,
    /** One-shot: uuid the last save wrote; empty otherwise. */
    val savedDraftUuid: String,
    /** idle | publishing | published | failed */
    val publishPhase: String,
    val publishAttempted: Int,
    val publishAccepted: Int,
    /** none | noIdentity | incompleteDraft | signingFailed | noRelayAccepted | unavailable */
    val publishFailureCode: String,
    /** True when the climb is live but did not reach every relay that was dialled. */
    val publishPartial: Boolean,
    /** none | loadFailed | saveFailed | draftsLoadFailed | draftsDeleteFailed | editLockedByKilter */
    val errorCode: String,
    // ── Auto-note and Kilter (Android ClimbEditorViewModel) ──
    /** "Also post to Nostr" for this publish. */
    val alsoPostNote: Boolean = false,
    /** The note text the publish sends (Android placeholders stay literal). */
    val autoNoteText: String = "",
    /** The climb went out, but no relay took the note (`climb_creator_auto_note_zero_relays`). */
    val autoNoteZeroRelays: Boolean = false,
    /** True when this editor can publish to Kilter (the leg is attached and the board is a Kilter). */
    val kilterPublishAvailable: Boolean = false,
    /** Kilter side of the last publish: none | synced | failed | diverged | skipped */
    val kilterOutcomeCode: String = "none",
    /** Android string key for a failed/diverged outcome, "" otherwise. */
    val kilterOutcomeMessageKey: String = "",
    /** Portal status for the `_with_code` messages, 0 otherwise. */
    val kilterOutcomeHttpCode: Int = 0,
    /** Kilter publishing is on but no account is connected (`climb_creator_kilter_connect_nudge`). */
    val kilterConnectNudge: Boolean = false,
    // ── Editor extras (Android ClimbEditorScreen) ──
    /** The board has a popular-holds heatmap (Aurora family, Quantum); MoonBoard has none. */
    val heatmapAvailable: Boolean = false,
    val heatmapEnabled: Boolean = false,
    /** Empty while the overlay is off. */
    val heat: List<CreatorHeatUi> = emptyList(),
    /** The duplicate is the user's own climb: "Update existing" / "Publish as new". */
    val duplicateIsOwn: Boolean = false,
    /** The own duplicate never went out (a draft or a failed publish). */
    val duplicateIsDraft: Boolean = false,
    /** The profile hint waits for an answer before the publish goes on. */
    val profileHintPending: Boolean = false,
    /** One-shot: open the profile editor, then call [CreatorScreenModel.acknowledgeProfileSetup]. */
    val profileSetupRequested: Boolean = false,
    /** The connected board belongs to another family than the climb's board (Android's dialog). */
    val boardMismatch: BoardMismatchUi = BoardMismatchUi.NONE,
)

/**
 * Swift-facing facade for the climb creator.
 *
 * Tap coordinates cross the boundary normalized (0…1 over the drawn image), so
 * the view never has to know about placement coordinate spaces: Aurora-family
 * boards resolve through [AuroraBoardGeometry.nearestPlacement] and MoonBoard
 * through [MoonBoardMappedGeometry.holdIdAt].
 */
class CreatorScreenModel(
    private val editor: ClimbEditor,
    private val grades: GradeFormatter,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val moonGeometry = MutableStateFlow<MoonLayout?>(null)
    private var mirror: com.cruxcoach.app.creator.CreatorBoardMirror? = null
    private val mirrorMismatch = MutableStateFlow<com.cruxcoach.app.setup.BoardConfigurationMismatch?>(null)
    private var mirrorWatch: kotlinx.coroutines.Job? = null

    private class MoonLayout(val geometry: MoonBoardMappedGeometry, val imagePath: String)

    val currentState: CreatorScreenState get() = map(editor.state.value, moonGeometry.value, mirrorMismatch.value)

    fun watch(onState: (CreatorScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(editor.state, moonGeometry, mirrorMismatch) { state, moon, mismatch -> map(state, moon, mismatch) }
                .collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    fun open() = editor.open()

    /** Opens an existing own climb for editing in place (the d-tag stays stable). */
    fun openForEdit(uuid: String) = editor.open(editUuid = uuid)

    /** Opens an existing climb as a remix: a new climb seeded from its holds. */
    fun openForRemix(uuid: String) = editor.open(forkUuid = uuid)

    /** Swift loads [CreatorScreenState.moonLayoutPath] from the bundle and hands the text back. */
    fun setMoonLayoutJson(jsonText: String) {
        val parsed = parseMoonBoardLayoutOrNull(jsonText) ?: return
        moonGeometry.value = MoonLayout(MoonBoardMappedGeometry(parsed), moonBoardImageAssetPath(parsed.image))
    }

    /**
     * A tap at normalized [x]/[y] over the board image. Paints the active brush
     * on the nearest hold within the tap radius; a tap that hits nothing is
     * ignored, so a miss never clears the user's work.
     */
    fun tapBoard(x: Float, y: Float) {
        val placementId = placementAt(x, y) ?: return
        editor.toggleHold(placementId)
    }

    /** A long press: advances that hold through the board's roles. */
    fun longPressBoard(x: Float, y: Float) {
        val placementId = placementAt(x, y) ?: return
        editor.cycleHoldRole(placementId)
    }

    /** A drag from one hold to another: the role moves with it. */
    fun dragBoard(fromX: Float, fromY: Float, toX: Float, toY: Float) {
        val from = placementAt(fromX, fromY) ?: return
        val to = placementAt(toX, toY) ?: return
        editor.moveHold(from, to)
    }

    /** Placement under a normalized point, or -1 when the tap hit nothing. */
    fun placementIdAt(x: Float, y: Float): Int = placementAt(x, y) ?: -1

    fun toggleBrush(roleId: Int) = editor.toggleBrush(roleId)
    fun setName(name: String) = editor.setName(name)
    fun setDescription(description: String) = editor.setDescription(description)
    fun setAngle(angle: Int) = editor.setAngle(angle)
    fun setGradeId(gradeId: Int) = editor.setSetterGradeId(gradeId)
    fun undo() = editor.undo()
    fun redo() = editor.redo()

    /** Discards the working state. The saved draft, if any, is left untouched. */
    fun discard() = editor.clearEditor()

    fun saveDraft() = editor.saveAsDraft()
    fun consumeSavedDraft() = editor.consumeSavedDraft()

    fun openDrafts() = editor.openDraftsSheet()
    fun closeDrafts() = editor.closeDraftsSheet()
    fun loadDraft(uuid: String) = editor.loadDraft(uuid)
    fun deleteDraft(uuid: String) = editor.deleteDraft(uuid)

    fun checkForDuplicate() = editor.checkForDuplicate()
    fun dismissDuplicate() = editor.dismissDuplicate()

    /**
     * [autoNoteText] blank = no note. `{npub_cruxcoach}` resolves to the project's
     * key, which the note also mentions, as Android's official build does
     * (`NostrConfig.DEV_PUBKEY`, `AUTO_NOTE_PTAG_MAINTAINER` true).
     */
    fun publish(autoNoteText: String) =
        editor.publish(autoNoteText, com.cruxcoach.app.AppConfig.MAINTAINER_PUBKEY, mentionMaintainer = true)
    fun confirmPublishWithDuplicate(autoNoteText: String) =
        editor.confirmPublishWithDuplicate(autoNoteText, com.cruxcoach.app.AppConfig.MAINTAINER_PUBKEY, mentionMaintainer = true)
    fun cancelPublish() = editor.cancelPublish()
    fun consumePublishResult() = editor.consumePublishResult()

    /** [defaultTemplate] is `auto_note_default_template`, seeded on the first switch-on. */
    fun setAlsoPostNote(value: Boolean, defaultTemplate: String) = editor.setAlsoPostNote(value, defaultTemplate)
    fun setAutoNoteText(text: String) = editor.setAutoNoteText(text)
    fun consumeKilterOutcome() = editor.consumeKilterOutcome()

    /**
     * Attaches the Kilter step and seeds the note toggle from the profile's
     * global default, as Android's editor does once on open.
     */
    internal fun attachKilter(leg: com.cruxcoach.app.kilter.CreatorKilterLeg?, autoNoteDefault: Boolean) {
        editor.kilterLeg = leg
        if (autoNoteDefault) editor.setAlsoPostNote(true, "")
    }

    fun consumeError() = editor.consumeError()

    // ── Editor extras (Android ClimbEditorScreen) ──

    /** The overflow menu's popular-holds heatmap. */
    fun toggleHeatmap() = editor.toggleHeatmap()

    /** Own duplicate: publish the editor as an edit of that climb (same uuid). */
    fun updateExistingOnDuplicate(autoNoteText: String) =
        editor.updateExistingOnDuplicate(autoNoteText, com.cruxcoach.app.AppConfig.MAINTAINER_PUBKEY, mentionMaintainer = true)

    /** Profile hint "Set up profile": no publish; [CreatorScreenState.profileSetupRequested] follows. */
    fun acceptProfileHint() = editor.acceptProfileHint()

    /** Profile hint "Skip for now" (or the dialog dismissed): the publish goes on. */
    fun skipProfileHint(autoNoteText: String) =
        editor.dismissProfileHintAndPublish(autoNoteText, com.cruxcoach.app.AppConfig.MAINTAINER_PUBKEY, mentionMaintainer = true)

    fun acknowledgeProfileSetup() = editor.acknowledgeProfileSetupNavigated()

    /** The live LED mirror runs while the editor is on screen (Android: while the screen exists). */
    fun startBoardMirror() {
        val active = mirror ?: return
        active.start()
        if (mirrorWatch == null) {
            mirrorWatch = scope.launch { active.mismatch.collect { mirrorMismatch.value = it } }
        }
    }

    fun stopBoardMirror() {
        mirror?.stop()
        mirrorWatch?.cancel()
        mirrorWatch = null
        mirrorMismatch.value = null
    }

    /** The editor's state, for the LED mirror the app builds around it. */
    internal val editorState: kotlinx.coroutines.flow.StateFlow<ClimbEditorUiState> get() = editor.state

    /** Autosave, profile hint and LED mirror, wired by the app (`AppCore`); set before [open]. */
    internal fun attachExtras(
        autosave: com.cruxcoach.app.creator.EditorAutosave?,
        profileHint: com.cruxcoach.app.creator.CreatorProfileHint?,
        boardMirror: com.cruxcoach.app.creator.CreatorBoardMirror?,
    ) {
        editor.autosave = autosave
        editor.profileHint = profileHint
        mirror = boardMirror
    }

    fun close() {
        stopBoardMirror()
        scope.cancel()
        editor.close()
    }

    private fun placementAt(x: Float, y: Float): Int? {
        val state = editor.state.value
        val moon = moonGeometry.value
        if (BoardBrand.fromWire(state.editor.boardBrand) == BoardBrand.MOONBOARD) {
            return moon?.geometry?.holdIdAt(x, y, 1f, 1f)
        }
        if (state.placements.isEmpty()) return null
        quantumModel(state)?.let { model ->
            return com.cruxcoach.app.render.QuantumRender.nearestPlacement(model, x, y, state.placements.values)
        }
        return AuroraBoardGeometry.of(state.boardSize)
            .nearestPlacement(x, y, state.placements.values, 1f, 1f)
    }

    /** Quantum holds follow the model's own mapping over the square image, not the edge box. */
    private fun quantumModel(state: ClimbEditorUiState): com.cruxcoach.domain.board.QuantumBoardModel? =
        if (BoardBrand.fromWire(state.editor.boardBrand) != BoardBrand.QUANTUM) null
        else com.cruxcoach.app.render.QuantumRender.model(state.boardSize, state.layoutId)

    private fun map(
        state: ClimbEditorUiState,
        moon: MoonLayout?,
        mismatch: com.cruxcoach.app.setup.BoardConfigurationMismatch?,
    ): CreatorScreenState {
        val brand = BoardBrand.fromWire(state.editor.boardBrand)
        val colors = state.ledColors
        val geometry = AuroraBoardGeometry.of(state.boardSize)
        val quantum = quantumModel(state)
        fun pointOf(placementId: Int) = when {
            brand == BoardBrand.MOONBOARD -> moon?.geometry?.point(placementId, 1f, 1f)
            quantum != null -> state.placements[placementId]
                ?.let { com.cruxcoach.app.render.QuantumRender.normalized(quantum, it.x, it.y) }
                ?.takeIf(com.cruxcoach.app.render.QuantumRender::isVisible)
            else -> state.placements[placementId]?.let { geometry.normalized(it.x, it.y) }
        }
        val holds = state.editor.selectedHolds.entries.mapNotNull { (placementId, roleId) ->
            val point = pointOf(placementId) ?: return@mapNotNull null
            CreatorHoldUi(placementId, point.x, point.y, colors.argbForRole(roleId), roleCode(roleId))
        }
        val heatmapAvailable = brand.hasHeatmap
        val heat = if (state.heatmapEnabled && heatmapAvailable) {
            state.heatmap.entries.mapNotNull { (placementId, intensity) ->
                // Holds already on the climb are drawn as holds, not as suggestions.
                if (placementId in state.editor.selectedHolds) return@mapNotNull null
                val point = pointOf(placementId) ?: return@mapNotNull null
                CreatorHeatUi(placementId, point.x, point.y, intensity.coerceIn(0f, 1f))
            }
        } else {
            emptyList()
        }
        val duplicate = state.duplicateOf
        val aspect = when {
            moon != null && brand == BoardBrand.MOONBOARD -> moon.geometry.imageAspect
            quantum != null -> com.cruxcoach.app.render.QuantumRender.ASPECT
            state.boardSize != null && geometry.boardWidth > 0f && geometry.boardHeight > 0f -> geometry.aspectRatio
            else -> DetailScreenModel.DEFAULT_ASPECT
        }
        val grade = state.editor.setterGradeId ?: KilterGradeMapper.DEFAULT_SETTER_GRADE_ID
        val result = state.publishResult
        return CreatorScreenState(
            isLoading = state.isLoading,
            boardTitle = state.boardSize?.name?.takeIf { it.isNotBlank() } ?: UiCodes.brandTitle(brand),
            brandWire = state.editor.boardBrand,
            imagePaths = if (brand == BoardBrand.MOONBOARD) {
                moon?.let { listOf(it.imagePath) }.orEmpty()
            } else {
                state.boardImagePaths
            },
            moonLayoutPath = if (moon == null) state.moonLayoutPath else "",
            boardAspect = aspect,
            holds = holds,
            brushes = brushes(brand, state),
            name = state.editor.name,
            description = state.editor.description,
            angle = state.editor.angle ?: 0,
            angleOptions = state.angleOptions,
            gradeId = grade,
            gradeLabel = grades.label(grade.toDouble()),
            minGradeId = MIN_SETTER_GRADE_ID,
            maxGradeId = MAX_SETTER_GRADE_ID,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            isValid = state.validationIssues.isEmpty(),
            issues = state.validationIssues.map { CreatorCodes.issue(it) },
            nameMaxLength = ClimbValidation.NAME_MAX_LENGTH,
            descriptionMaxLength = ClimbValidation.DESCRIPTION_MAX_LENGTH,
            duplicateUuid = state.duplicateOf?.uuid.orEmpty(),
            duplicateName = state.duplicateOf?.name.orEmpty(),
            duplicateBlocksPublish = state.pendingDuplicateConfirm,
            drafts = state.drafts.orEmpty().map {
                CreatorDraftUi(
                    uuid = it.uuid,
                    name = it.name,
                    holdCount = com.cruxcoach.domain.board.BoardClimbParser.parseFrames(it.framesText).size,
                    createdAt = it.createdAt.orEmpty(),
                )
            },
            draftsSheetOpen = state.draftsSheetOpen,
            loadedDraftUuid = state.loadedDraftUuid.orEmpty(),
            isEditingExisting = state.isEditingExisting,
            savedDraftUuid = state.savedDraftUuid.orEmpty(),
            publishPhase = CreatorCodes.phase(state.publishPhase),
            publishAttempted = result?.attempted ?: 0,
            publishAccepted = result?.accepted ?: 0,
            publishFailureCode = when {
                result != null -> CreatorCodes.publishFailure(result.failure)
                // The phase failed without a result: nothing could be sent at all.
                state.publishPhase == PublishPhase.FAILED -> "unavailable"
                else -> "none"
            },
            publishPartial = result?.isPartial ?: false,
            errorCode = CreatorCodes.error(state.error),
            alsoPostNote = state.alsoPostNote,
            autoNoteText = state.autoNoteText.orEmpty(),
            autoNoteZeroRelays = result != null && result.autoNoteAttempted != null && result.autoNoteAccepted == 0,
            kilterPublishAvailable = editor.kilterLeg != null && brand.supportsOfficialAppPublish,
            kilterOutcomeCode = state.kilterOutcome?.code ?: "none",
            kilterOutcomeMessageKey = state.kilterOutcome?.messageKey.orEmpty(),
            kilterOutcomeHttpCode = state.kilterOutcome?.httpCode ?: 0,
            kilterConnectNudge = state.kilterConnectNudge,
            heatmapAvailable = heatmapAvailable,
            heatmapEnabled = state.heatmapEnabled,
            heat = heat,
            duplicateIsOwn = state.duplicateIsOwn,
            duplicateIsDraft = duplicate != null && (duplicate.syncStatus == "draft" || duplicate.syncStatus == "failed"),
            profileHintPending = state.pendingProfileHint,
            profileSetupRequested = state.profileSetupRequested,
            boardMismatch = BoardMismatchUi.of(mismatch),
        )
    }

    private fun brushes(brand: BoardBrand, state: ClimbEditorUiState): List<CreatorBrushUi> {
        val colors = state.ledColors
        val roles = if (brand == BoardBrand.MOONBOARD) {
            // A MoonBoard problem has no foot role.
            listOf(HoldRole.ROUTE_START, HoldRole.ROUTE_HAND, HoldRole.ROUTE_FINISH)
        } else {
            listOf(HoldRole.START, HoldRole.HAND, HoldRole.FINISH, HoldRole.FOOT)
        }
        val counts = state.editor.selectedHolds.values.groupingBy { HoldRole.roleClass(it) }.eachCount()
        return roles.map { roleId ->
            CreatorBrushUi(
                roleId = roleId,
                code = roleCode(roleId),
                argb = colors.argbForRole(roleId),
                isActive = state.editor.activeBrush == roleId,
                count = counts[HoldRole.roleClass(roleId)] ?: 0,
            )
        }
    }

    private fun roleCode(roleId: Int): String = when (HoldRole.roleClass(roleId)) {
        HoldRole.START -> "start"
        HoldRole.HAND -> "hand"
        HoldRole.FINISH -> "finish"
        HoldRole.FOOT -> "foot"
        else -> "hand"
    }

    companion object {
        /** Setter-grade id range, matching KilterGradeMapper's table. */
        const val MIN_SETTER_GRADE_ID = 10
        const val MAX_SETTER_GRADE_ID = 34
    }
}

/** Stable lowercase codes for the creator's enums. Append only. */
internal object CreatorCodes {
    fun phase(value: PublishPhase): String = when (value) {
        PublishPhase.IDLE -> "idle"
        PublishPhase.PUBLISHING -> "publishing"
        PublishPhase.PUBLISHED -> "published"
        PublishPhase.FAILED -> "failed"
    }

    fun publishFailure(value: com.cruxcoach.app.community.PublishFailure): String = when (value) {
        com.cruxcoach.app.community.PublishFailure.NONE -> "none"
        com.cruxcoach.app.community.PublishFailure.NO_IDENTITY -> "noIdentity"
        com.cruxcoach.app.community.PublishFailure.INCOMPLETE_DRAFT -> "incompleteDraft"
        com.cruxcoach.app.community.PublishFailure.SIGNING_FAILED -> "signingFailed"
        com.cruxcoach.app.community.PublishFailure.NO_RELAY_ACCEPTED -> "noRelayAccepted"
    }

    fun error(value: EditorError): String = when (value) {
        EditorError.NONE -> "none"
        EditorError.LOAD_FAILED -> "loadFailed"
        EditorError.SAVE_FAILED -> "saveFailed"
        EditorError.DRAFTS_LOAD_FAILED -> "draftsLoadFailed"
        EditorError.DRAFTS_DELETE_FAILED -> "draftsDeleteFailed"
        EditorError.EDIT_LOCKED_BY_KILTER -> "editLockedByKilter"
    }

    /** [CreatorIssueUi.count] / [CreatorIssueUi.limit] are -1 where the rule has no number. */
    fun issue(value: ClimbValidation.Issue): CreatorIssueUi = when (value) {
        ClimbValidation.Issue.NoStartHold -> CreatorIssueUi("noStartHold", -1, -1)
        ClimbValidation.Issue.NoFinishHold -> CreatorIssueUi("noFinishHold", -1, -1)
        ClimbValidation.Issue.TooFewHolds -> CreatorIssueUi("tooFewHolds", -1, ClimbValidation.MIN_HOLDS_TOTAL)
        is ClimbValidation.Issue.TooManyHolds ->
            CreatorIssueUi("tooManyHolds", value.count, ClimbValidation.MAX_HOLDS_TOTAL)
        is ClimbValidation.Issue.TooManyStarts ->
            CreatorIssueUi("tooManyStarts", value.count, ClimbValidation.MAX_START_HOLDS)
        is ClimbValidation.Issue.TooManyFinishes ->
            CreatorIssueUi("tooManyFinishes", value.count, ClimbValidation.MAX_FINISH_HOLDS)
        ClimbValidation.Issue.NameMissing -> CreatorIssueUi("nameMissing", -1, -1)
        is ClimbValidation.Issue.NameTooLong ->
            CreatorIssueUi("nameTooLong", value.length, ClimbValidation.NAME_MAX_LENGTH)
        is ClimbValidation.Issue.DescriptionTooLong ->
            CreatorIssueUi("descriptionTooLong", value.length, ClimbValidation.DESCRIPTION_MAX_LENGTH)
        ClimbValidation.Issue.AngleMissing -> CreatorIssueUi("angleMissing", -1, -1)
        ClimbValidation.Issue.GradeMissing -> CreatorIssueUi("gradeMissing", -1, -1)
    }
}

/**
 * Builds a working creator screen from the platform services, the board
 * database and the active identity.
 *
 * [secretKeyProvider] must hand out a FRESH copy on every call (the signer
 * zeroes what it receives) and must read from the Keychain rather than a
 * retained field, so a key change takes effect without a restart — e.g.
 * `{ platform.secrets.read(LocalIdentity.NOSTR_KEY) }`. [pubkeyProvider] is the
 * active identity's hex pubkey; it decides which drafts are visible and which
 * d-tag namespace the climb is published under.
 *
 * [scope] must be the caller's main-thread scope (`MainScope()` on iOS).
 */
fun createCreatorScreenModel(
    platform: com.cruxcoach.app.platform.PlatformServices,
    boardRepository: com.cruxcoach.data.repository.BoardRepository,
    scope: CoroutineScope,
    pubkeyProvider: () -> String?,
    secretKeyProvider: () -> ByteArray?,
): CreatorScreenModel {
    val preferences = com.cruxcoach.app.browse.BrowsePreferences(platform.keyValues)
    val drafts = com.cruxcoach.app.creator.ClimbDraftStore(
        boardRepository = boardRepository,
        hashing = platform.hashing,
        clock = platform.clock,
        pubkeyProvider = pubkeyProvider,
    )
    val publisher = com.cruxcoach.app.community.CommunityPublisher(
        boardRepository = boardRepository,
        relay = com.cruxcoach.app.community.RelayCommunityRelay(
            com.cruxcoach.app.nostr.RelayClient(platform.webSockets, platform.hashing)
        ),
        signer = com.cruxcoach.app.backup.LocalEventSigner(platform.hashing, secretKeyProvider),
        hashing = platform.hashing,
        clock = platform.clock,
        pubkeyProvider = pubkeyProvider,
    )
    val editor = ClimbEditor(
        boardRepository = boardRepository,
        drafts = drafts,
        preferences = preferences,
        publisher = publisher,
        scope = scope,
    )
    return CreatorScreenModel(editor, GradeFormatter(preferences.frenchGrades()))
}
