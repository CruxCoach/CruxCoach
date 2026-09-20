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

    private class MoonLayout(val geometry: MoonBoardMappedGeometry, val imagePath: String)

    val currentState: CreatorScreenState get() = map(editor.state.value, moonGeometry.value)

    fun watch(onState: (CreatorScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(editor.state, moonGeometry) { state, moon -> map(state, moon) }.collect { onState(it) }
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

    fun publish(autoNoteText: String) = editor.publish(autoNoteText)
    fun confirmPublishWithDuplicate(autoNoteText: String) = editor.confirmPublishWithDuplicate(autoNoteText)
    fun cancelPublish() = editor.cancelPublish()
    fun consumePublishResult() = editor.consumePublishResult()

    fun consumeError() = editor.consumeError()

    fun close() {
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
        return AuroraBoardGeometry.of(state.boardSize)
            .nearestPlacement(x, y, state.placements.values, 1f, 1f)
    }

    private fun map(state: ClimbEditorUiState, moon: MoonLayout?): CreatorScreenState {
        val brand = BoardBrand.fromWire(state.editor.boardBrand)
        val colors = state.ledColors
        val geometry = AuroraBoardGeometry.of(state.boardSize)
        val holds = state.editor.selectedHolds.entries.mapNotNull { (placementId, roleId) ->
            val point = if (brand == BoardBrand.MOONBOARD) {
                moon?.geometry?.point(placementId, 1f, 1f)
            } else {
                state.placements[placementId]?.let { geometry.normalized(it.x, it.y) }
            } ?: return@mapNotNull null
            CreatorHoldUi(placementId, point.x, point.y, colors.argbForRole(roleId), roleCode(roleId))
        }
        val aspect = when {
            moon != null && brand == BoardBrand.MOONBOARD -> moon.geometry.imageAspect
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
