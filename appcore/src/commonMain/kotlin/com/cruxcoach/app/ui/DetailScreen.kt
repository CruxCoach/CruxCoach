package com.cruxcoach.app.ui

import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.detail.ClimbDetailUiState
import com.cruxcoach.app.detail.DetailPages
import com.cruxcoach.app.logbook.LogAttemptPresenter
import com.cruxcoach.app.logbook.LogAttemptState
import com.cruxcoach.app.logbook.LogTarget
import com.cruxcoach.app.render.MoonBoardMappedGeometry
import com.cruxcoach.app.render.layoutJsonAssetPath
import com.cruxcoach.app.render.moonBoardImageAssetPath
import com.cruxcoach.app.render.parseMoonBoardLayoutOrNull
import com.cruxcoach.app.send.BoardSender
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.MoonBoardVariant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A hold ready to draw: [x]/[y] are normalized over the board image, origin top-left. */
class DetailHoldUi(val placementId: Int, val x: Float, val y: Float, val argb: Long)

class DetailAngleUi(val angle: Int, val grade: String, val sends: Long, val isSetterAngle: Boolean)

class DetailBetaUi(val url: String, val label: String)

class DetailAscentUi(val uuid: String, val date: String, val angle: Int, val tries: Long, val isSend: Boolean)

class DetailScreenState(
    /** loading | ready | logbookOnly | notFound | failed */
    val status: String,
    val uuid: String,
    val name: String,
    val setter: String,
    val grade: String,
    val notes: String,
    val angle: Int,
    val angles: List<DetailAngleUi>,
    val holds: List<DetailHoldUi>,
    /** Bundle-relative image candidates, most specific first. Empty = draw the placeholder. */
    val imagePaths: List<String>,
    /** Bundle-relative MoonBoard coordinate map Swift must load, or empty. */
    val moonLayoutPath: String,
    val boardAspect: Float,
    val isFavourite: Boolean,
    val isIgnored: Boolean,
    val isMirrorable: Boolean,
    val isMirrored: Boolean,
    val personalNote: String,
    val noteFailed: Boolean,
    val beta: List<DetailBetaUi>,
    val ascents: List<DetailAscentUi>,
    val quickLogging: Boolean,
    val canUndoQuickLog: Boolean,
    val logFailed: Boolean,
    val browserDirty: Boolean,
    /** none | noClimb | unknownBrand | noBoardGeometry | noHolds — why a send could not even be built. */
    val sendRequestFailure: String,
    val showLogDialog: Boolean,
    val logIsSend: Boolean,
    val logTries: Int,
    val logQuality: Int,
    val logComment: String,
    val logBenchmark: Boolean,
    /** 0-based position in the list the detail was opened from (Android's swipe pager). */
    val pageIndex: Int,
    /** 1 = no paging: the climb was not in a list, or the list had only it. */
    val pageCount: Int,
    /** A Quantum climb: lit through board layers on Android, which iOS does not have yet. */
    val isQuantum: Boolean,
    /** Android's info block: a route counts frames ("Frames"), a boulder its moves ("Moves"). */
    val infoIsRoute: Boolean,
    val infoMoveCount: Int,
    /** Average star rating with one decimal; empty = Android's "–". */
    val infoQuality: String,
    val infoSends: Long,
    /** First ascent; empty name = no FA line. [faDate] is Android's dd.MM.yyyy, or empty. */
    val faName: String,
    val faDate: String,
    /** The no-match rule is known for this climb (Kilter catalogue only, never MoonBoard). */
    val matchKnown: Boolean,
    val noMatch: Boolean,
    /** MoonBoard method: none | footless | footlessKickboard | noKickboard (unknown tokens: none). */
    val methodCode: String,
    /** A catalogue benchmark (benchmark difficulty above 0). */
    val isBenchmark: Boolean,
)

/**
 * Climb detail screen: catalogue data, board rendering input, logging and the
 * explicit send to a connected board, as one state for SwiftUI.
 */
class DetailScreenModel(
    private val detail: ClimbDetailPresenter,
    private val logger: LogAttemptPresenter,
    private val sender: BoardSender,
    private val grades: GradeFormatter,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val moonLayout = MutableStateFlow<MoonLayout?>(null)
    private val sendFailure = MutableStateFlow("none")
    private val pages = MutableStateFlow(DetailPages.single(""))
    /** Android pages with the angle the list was browsed at, not the one picked on a page. */
    private var pageAngle = 0
    private var lastDetail: ClimbDetailUiState? = null
    private var lastTargetKey: String = ""

    private class MoonLayout(val geometry: MoonBoardMappedGeometry, val imagePath: String)

    val currentState: DetailScreenState
        get() = map(detail.state.value, logger.state.value, moonLayout.value, sendFailure.value, pages.value)

    fun watch(onState: (DetailScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(detail.state, logger.state, moonLayout, sendFailure, pages) { d, l, moon, failure, p ->
                lastDetail = d
                syncLogTarget(d)
                map(d, l, moon, failure, p)
            }.collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    fun open(uuid: String, angle: Int) {
        pages.value = DetailPages.single(uuid)
        pageAngle = angle
        detail.open(uuid, angle)
    }

    /**
     * Opens [uuid] with the list it was tapped in ([pageUuids], e.g. the
     * browser's current results) so the detail can page through it.
     */
    fun openPaged(uuid: String, angle: Int, pageUuids: List<String>) {
        pages.value = DetailPages.of(pageUuids, uuid)
        pageAngle = angle
        detail.open(uuid, angle)
        detail.preload(pages.value.neighbours, angle)
    }

    /** Android's settled pager page: switch to the climb at [index]; out of range is ignored. */
    fun showPage(index: Int) {
        val next = pages.value.moveTo(index) ?: return
        // A send or failure banner belongs to the climb it was raised on.
        sendFailure.value = "none"
        pages.value = next
        detail.switchClimb(next.current, pageAngle)
        detail.preload(next.neighbours, pageAngle)
    }

    fun nextPage() = showPage(pages.value.index + 1)
    fun previousPage() = showPage(pages.value.index - 1)

    /** Swift loads [DetailScreenState.moonLayoutPath] from the bundle and hands the text back. */
    fun setMoonLayoutJson(jsonText: String) {
        val parsed = parseMoonBoardLayoutOrNull(jsonText) ?: return
        moonLayout.value = MoonLayout(MoonBoardMappedGeometry(parsed), moonBoardImageAssetPath(parsed.image))
    }

    fun selectAngle(angle: Int) = detail.selectAngle(angle)
    fun selectFrame(index: Int) = detail.selectFrame(index)
    fun toggleMirror() = detail.toggleMirror()
    fun toggleFavourite() = detail.toggleFavourite()
    fun setIgnored(ignored: Boolean) = detail.setIgnored(ignored)
    fun saveNote(note: String) = detail.saveNote(note)

    /**
     * Android's "Copy share link": `https://<host>/c/<naddr>` for a community
     * climb (the same link its creator's note carries), `/c/<uuid>` for a
     * catalogue climb. Empty while no climb is loaded.
     */
    fun shareLink(): String {
        val climb = lastDetail?.data?.climb ?: return ""
        return com.cruxcoach.app.links.ClimbLinks.shareLink(climb.createdByPubkey, climb.uuid) ?: ""
    }

    /** Explicit "light it up". The BLE outcome arrives on the connection screen. */
    fun sendToBoard() {
        val detail = lastDetail ?: return
        val code = sender.send(detail)
        sendFailure.value = if (code == "started") "none" else code
    }

    fun dismissSendFailure() { sendFailure.value = "none" }

    /** Late failure from the send's background half. Not part of the Swift API. */
    internal fun reportSendFailure(code: String) { sendFailure.value = code }

    fun showLogDialog() = logger.showDialog()
    fun dismissLogDialog() = logger.dismissDialog()
    fun setLogIsSend(isSend: Boolean) = logger.updateIsSend(isSend)
    fun setLogTries(count: Int) = logger.updateBidCount(count)
    fun setLogQuality(quality: Int) = logger.updateQuality(quality)
    fun setLogComment(comment: String) = logger.updateComment(comment)
    fun setLogBenchmark(value: Boolean) = logger.updateIsBenchmark(value)
    fun saveLog() = logger.save()
    fun quickLog(isSend: Boolean) = logger.quickLog(isSend)
    fun undoQuickLog() = logger.undoQuickLog()

    fun close() {
        scope.cancel()
        detail.close()
        logger.close()
    }

    /** Keeps the logger pointed at what the screen currently shows (climb, angle, mirror). */
    private fun syncLogTarget(state: ClimbDetailUiState) {
        val climb = state.data?.climb ?: return
        val key = "${climb.uuid}|${state.angle}|${state.isMirrored}"
        if (key == lastTargetKey) return
        lastTargetKey = key
        logger.setTarget(
            LogTarget(
                climbUuid = climb.uuid,
                climbName = climb.name,
                frames = climb.frames,
                framesCount = climb.framesCount,
                difficultyAverage = climb.difficultyAverage,
                boardBrand = state.data?.boardSize?.boardBrand?.wireValue ?: BoardBrand.KILTER.wireValue,
                layoutId = climb.layoutId,
                angle = state.angle,
                isMirrored = state.isMirrored,
            )
        )
    }

    private fun map(
        state: ClimbDetailUiState,
        log: LogAttemptState,
        moon: MoonLayout?,
        sendRequestFailure: String,
        pager: DetailPages,
    ): DetailScreenState {
        val data = state.data
        val climb = data?.climb
        val brand = data?.boardSize?.boardBrand
        val variant = if (climb != null && brand == null || brand == BoardBrand.MOONBOARD) {
            climb?.layoutId?.let { MoonBoardVariant.fromLayoutId(it) }
        } else {
            null
        }
        val quantum = if (climb != null && BoardBrand.fromWire(climb.boardBrand) == BoardBrand.QUANTUM) {
            com.cruxcoach.app.render.QuantumRender.model(data?.boardSize, climb.layoutId)
        } else null
        val holds = state.holds.mapNotNull { hold ->
            when {
                hold.x >= 0f && hold.y >= 0f -> DetailHoldUi(hold.placementId, hold.x, hold.y, hold.argb)
                quantum != null -> com.cruxcoach.app.render.QuantumRender.normalized(quantum, hold.boardX, hold.boardY)
                    .takeIf(com.cruxcoach.app.render.QuantumRender::isVisible)
                    ?.let { DetailHoldUi(hold.placementId, it.x, it.y, hold.argb) }
                moon != null -> moon.geometry.point(hold.placementId, 1f, 1f)
                    ?.let { DetailHoldUi(hold.placementId, it.x, it.y, hold.argb) }
                else -> null
            }
        }
        val size = data?.boardSize
        val aspect = when {
            moon != null -> moon.geometry.imageAspect
            quantum != null -> com.cruxcoach.app.render.QuantumRender.ASPECT
            size != null && size.edgeRight > size.edgeLeft && size.edgeTop > size.edgeBottom ->
                (size.edgeRight - size.edgeLeft).toFloat() / (size.edgeTop - size.edgeBottom).toFloat()
            else -> DEFAULT_ASPECT
        }
        return DetailScreenState(
            status = UiCodes.detailStatus(state.status),
            uuid = state.uuid,
            name = climb?.name ?: "",
            setter = climb?.setterUsername ?: "",
            grade = grades.label(climb?.difficultyAverage),
            notes = climb?.description ?: "",
            angle = state.angle,
            angles = data?.availableAngles.orEmpty().map {
                DetailAngleUi(it.angle, grades.label(it.difficultyAverage), it.ascensionistCount ?: 0L, it.isSetterAngle)
            },
            holds = holds,
            imagePaths = moon?.let { listOf(it.imagePath) } ?: data?.boardImagePaths.orEmpty(),
            moonLayoutPath = if (moon == null) variant?.layoutJsonAssetPath() ?: "" else "",
            boardAspect = aspect,
            isFavourite = state.isFavorited,
            isIgnored = state.isIgnored,
            isMirrorable = data?.isMirrorable ?: false,
            isMirrored = state.isMirrored,
            personalNote = state.personalNote,
            noteFailed = state.noteStatus == com.cruxcoach.app.detail.NoteSaveStatus.FAILED,
            beta = data?.betaLinks.orEmpty().mapNotNull { item ->
                item.link.url.takeIf { it.startsWith("https://") }?.let {
                    DetailBetaUi(it, item.link.foreignUsername ?: item.link.provider)
                }
            },
            ascents = log.userAscents.map {
                DetailAscentUi(it.uuid, it.climbedAt.take(10), it.angle.toInt(), it.bidCount, it.isSend)
            },
            quickLogging = log.isQuickLogging,
            canUndoQuickLog = log.quickLogFeedback != null,
            logFailed = log.quickLogFailed,
            browserDirty = state.browserDirty,
            sendRequestFailure = sendRequestFailure,
            showLogDialog = log.ascent.showDialog,
            logIsSend = log.ascent.isSend,
            logTries = log.ascent.bidCount,
            logQuality = log.ascent.quality,
            logComment = log.ascent.comment,
            logBenchmark = log.ascent.isBenchmark,
            pageIndex = pager.index,
            pageCount = pager.count,
            isQuantum = climb != null && BoardBrand.fromWire(climb.boardBrand) == BoardBrand.QUANTUM,
            infoIsRoute = climb?.isRoute ?: false,
            infoMoveCount = when {
                climb == null -> 0
                climb.isRoute -> if (state.frameCount > 0) state.frameCount else climb.framesCount.toInt()
                else -> climb.moveCount
            },
            infoQuality = climb?.qualityAverage?.let { grades.oneDecimal(it) } ?: "",
            infoSends = climb?.ascensionistCount ?: 0L,
            faName = climb?.faUsername?.takeIf { it.isNotBlank() } ?: "",
            faDate = climb?.faUsername?.takeIf { it.isNotBlank() }?.let { climb.faAt?.let(::ascentDate) } ?: "",
            matchKnown = climb?.isMatchStateKnown ?: false,
            noMatch = climb?.isNomatch ?: false,
            methodCode = methodCode(climb?.method),
            isBenchmark = (climb?.benchmarkDifficulty ?: 0.0) > 0.0,
        )
    }

    companion object {
        /** Used when neither an image nor product-size edges are known. */
        const val DEFAULT_ASPECT = 0.65f

        /** Android `MethodBadge`: an unknown token from a newer catalogue says nothing. */
        internal fun methodCode(method: String?): String = when (method) {
            "method_footless" -> "footless"
            "method_footless_kickboard" -> "footlessKickboard"
            "method_no_kickboard" -> "noKickboard"
            else -> "none"
        }

        /** Android `formatAscentDate`: the first ten characters as dd.MM.yyyy, or as they are. */
        internal fun ascentDate(isoDate: String): String {
            val parts = isoDate.take(10).split("-")
            return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else isoDate.take(10)
        }
    }
}

class ClimbOwnerActionsState(
    val isLoaded: Boolean,
    /** Remix forks the loaded climb into the editor (Android: any loaded climb). */
    val canRemix: Boolean,
    /** Own CruxCoach climb (origin and signing key match): Edit plus one delete. */
    val isOwn: Boolean,
    /** Own climb mirrored to Kilter, which is immutable there: Edit is shown locked. */
    val editLocked: Boolean,
    /** Own climb that never reached a relay: its delete is local only. */
    val isUnpublishedDraft: Boolean,
    val climbName: String,
    /** none | draft | publication — the open confirmation. */
    val dialogCode: String,
    /** The publication also lives in Kilter, which has no delete. */
    val dialogKilterWarning: Boolean,
    val inProgress: Boolean,
    /** none | draftDeleted | done | localTombstoneFailed | notOwner | failed */
    val resultCode: String,
    val resultAccepted: Int,
    val resultAttempted: Int,
    val resultKilterWarning: Boolean,
    /** The climb is gone: the detail screen closes, as Android navigates back. */
    val deleted: Boolean,
)

/**
 * The owner half of Android `BoardClimbDetailViewModel`: the Edit/Remix gate
 * and the two deletes — a never-published draft locally, an own publication
 * through a signed relay deletion ([com.cruxcoach.app.community.CommunityDeleter],
 * which re-checks origin and ownership itself; this gate is UX, not security).
 */
class ClimbOwnerActionsModel(
    private val loadClimb: (uuid: String) -> com.cruxcoach.data.repository.ClimbWithStats?,
    private val currentPubkey: () -> String?,
    private val deleteDraft: (uuid: String) -> Unit,
    private val deletePublication: suspend (uuid: String) -> com.cruxcoach.app.community.CommunityDeleteResult,
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private class Internal(
        val climb: com.cruxcoach.data.repository.ClimbWithStats? = null,
        val pubkey: String? = null,
        val dialog: String = "none",
        val inProgress: Boolean = false,
        val result: String = "none",
        val accepted: Int = 0,
        val attempted: Int = 0,
        val resultKilter: Boolean = false,
        val deleted: Boolean = false,
    ) {
        fun with(
            dialog: String = this.dialog,
            inProgress: Boolean = this.inProgress,
            result: String = this.result,
            accepted: Int = this.accepted,
            attempted: Int = this.attempted,
            resultKilter: Boolean = this.resultKilter,
            deleted: Boolean = this.deleted,
        ) = Internal(climb, pubkey, dialog, inProgress, result, accepted, attempted, resultKilter, deleted)
    }

    private val scope = CoroutineScope(SupervisorJob() + main)
    private val state = MutableStateFlow(Internal())

    val currentState: ClimbOwnerActionsState get() = map(state.value)

    fun watch(onState: (ClimbOwnerActionsState) -> Unit): Subscription {
        val job = scope.launch { state.collect { onState(map(it)) } }
        return Subscription { job.cancel() }
    }

    fun open(uuid: String) {
        scope.launch {
            val loaded = try {
                kotlinx.coroutines.withContext(io) { loadClimb(uuid) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            state.value = Internal(climb = loaded, pubkey = currentPubkey())
        }
    }

    /** Opens the draft or the publication confirmation, whichever applies. */
    fun requestDelete() {
        val s = state.value
        if (!isOwn(s)) return
        state.value = s.with(dialog = if (isUnpublishedDraft(s)) "draft" else "publication")
    }

    fun dismissDelete() {
        val s = state.value
        if (!s.inProgress) state.value = s.with(dialog = "none")
    }

    fun confirmDelete() {
        val s = state.value
        val climb = s.climb ?: return
        if (s.inProgress || s.dialog == "none" || !isOwn(s)) return
        val draft = s.dialog == "draft"
        // An iOS alert closes on tap: drop the dialog now, or SwiftUI shows it again
        // while the deletion runs. [inProgress] blocks a second confirmation.
        state.value = s.with(dialog = "none", inProgress = true)
        scope.launch {
            val next = if (draft) runDraftDelete(climb.uuid) else runPublicationDelete(climb.uuid)
            state.value = next
        }
    }

    fun consumeResult() {
        state.value = state.value.with(result = "none")
    }

    fun close() = scope.cancel()

    private suspend fun runDraftDelete(uuid: String): Internal {
        val ok = try {
            // The repository gate is `source='local' AND nostr_event_id IS NULL`.
            kotlinx.coroutines.withContext(io) { deleteDraft(uuid) }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return state.value.with(
            dialog = "none",
            inProgress = false,
            result = if (ok) "draftDeleted" else "failed",
            deleted = ok,
        )
    }

    private suspend fun runPublicationDelete(uuid: String): Internal {
        val outcome = try {
            deletePublication(uuid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val code = when (outcome?.outcome) {
            com.cruxcoach.app.community.DeleteOutcome.DONE -> "done"
            com.cruxcoach.app.community.DeleteOutcome.LOCAL_TOMBSTONE_FAILED -> "localTombstoneFailed"
            com.cruxcoach.app.community.DeleteOutcome.NOT_OWNER -> "notOwner"
            else -> "failed"
        }
        return state.value.with(
            dialog = "none",
            inProgress = false,
            result = code,
            accepted = outcome?.accepted ?: 0,
            attempted = outcome?.attempted ?: 0,
            resultKilter = outcome?.kilterWasPublished ?: false,
            deleted = code == "done",
        )
    }

    private fun isOwn(s: Internal): Boolean {
        val climb = s.climb ?: return false
        val signer = s.pubkey?.takeIf { it.isNotBlank() } ?: return false
        return climb.origin == "cruxcoach" && climb.createdByPubkey != null && climb.createdByPubkey == signer
    }

    /** Android: `source == "local"` and no event id, mirroring the repository's delete gate. */
    private fun isUnpublishedDraft(s: Internal): Boolean {
        val climb = s.climb ?: return false
        return climb.source == "local" && climb.nostrEventId.isNullOrBlank()
    }

    private fun map(s: Internal) = ClimbOwnerActionsState(
        isLoaded = s.climb != null,
        canRemix = s.climb != null,
        isOwn = isOwn(s),
        editLocked = s.climb?.kilterStatus == "synced" || s.climb?.kilterStatus == "diverged",
        isUnpublishedDraft = isUnpublishedDraft(s),
        climbName = s.climb?.name ?: "",
        dialogCode = s.dialog,
        dialogKilterWarning = s.climb?.kilterStatus == "synced",
        inProgress = s.inProgress,
        resultCode = s.result,
        resultAccepted = s.accepted,
        resultAttempted = s.attempted,
        resultKilterWarning = s.resultKilter,
        deleted = s.deleted,
    )
}

class RestTimerUiState(
    /** idle | running | finished */
    val phase: String,
    val secondsRemaining: Int,
    val totalSeconds: Int,
    /** 1 → 0 over the rest. */
    val progress: Float,
    /** normal | warning | critical | finished — Android's orange, yellow (≤30 s), red (≤10 s), green. */
    val tone: String,
    /** "1:05" from a minute up, "45s" below, as Android's banner prints it. */
    val countdown: String,
    /** The settings length (`rest_timer_duration_seconds`) the start sheet is pre-filled with. */
    val defaultSeconds: Int,
)

/**
 * The board rest timer of the climb detail (Android's timer button,
 * `RestTimerStartDialog` and `RestTimerBannerSlot`). It drives the app's one
 * rest timer, which the training shares; closing the screen does not stop the
 * rest, and its end reaches a locked phone through [com.cruxcoach.app.notify.RestTimerNotifier].
 */
class RestTimerModel(
    private val timer: com.cruxcoach.app.athlete.TrainingRestTimer,
    private val defaultSeconds: () -> Int,
    main: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + main)

    val minSeconds: Int = com.cruxcoach.app.resttimer.RestTimerRules.MIN_SECONDS
    val maxSeconds: Int = com.cruxcoach.app.resttimer.RestTimerRules.MAX_SECONDS

    val currentState: RestTimerUiState get() = map(timer.restTimer.value)

    fun watch(onState: (RestTimerUiState) -> Unit): Subscription {
        val job = scope.launch { timer.restTimer.collect { onState(map(it)) } }
        return Subscription { job.cancel() }
    }

    /** The start sheet's length; not persisted — the setting stays the default and the auto-start length. */
    fun start(seconds: Int) = timer.startRestTimer(com.cruxcoach.app.resttimer.RestTimerRules.clamp(seconds))

    /** The banner's close button: dismisses a finished rest, cancels a running one. */
    fun closeBanner(finished: Boolean) {
        if (finished) timer.dismissRestTimerFinished() else timer.cancelRestTimer()
    }

    fun cancel() = timer.cancelRestTimer()
    fun dismissFinished() = timer.dismissRestTimerFinished()

    /** Recomputes the remainder, e.g. when the app returns to the foreground. */
    fun refresh() = timer.tick()

    fun canStep(current: Int, delta: Int): Boolean = com.cruxcoach.app.resttimer.RestTimerRules.canStep(current, delta)
    fun step(current: Int, delta: Int): Int = com.cruxcoach.app.resttimer.RestTimerRules.step(current, delta)
    fun clamp(seconds: Int): Int = com.cruxcoach.app.resttimer.RestTimerRules.clamp(seconds)
    fun countdownText(seconds: Int): String = com.cruxcoach.app.resttimer.RestTimerRules.countdown(seconds)

    /** Stops this screen's observation only; the rest keeps running. */
    fun detach() = scope.cancel()

    private fun map(state: com.cruxcoach.app.athlete.RestTimerState): RestTimerUiState {
        val rules = com.cruxcoach.app.resttimer.RestTimerRules
        return RestTimerUiState(
            phase = rules.phase(state),
            secondsRemaining = state.secondsRemaining,
            totalSeconds = state.totalSeconds,
            progress = rules.progress(state),
            tone = rules.tone(state),
            countdown = rules.countdown(state.secondsRemaining),
            defaultSeconds = rules.clamp(defaultSeconds()),
        )
    }
}
