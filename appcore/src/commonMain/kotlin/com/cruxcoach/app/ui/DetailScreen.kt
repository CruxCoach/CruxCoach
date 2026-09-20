package com.cruxcoach.app.ui

import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.detail.ClimbDetailUiState
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
    val showLogDialog: Boolean,
    val logIsSend: Boolean,
    val logTries: Int,
    val logQuality: Int,
    val logComment: String,
    val logBenchmark: Boolean,
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
    private var lastDetail: ClimbDetailUiState? = null
    private var lastTargetKey: String = ""

    private class MoonLayout(val geometry: MoonBoardMappedGeometry, val imagePath: String)

    val currentState: DetailScreenState get() = map(detail.state.value, logger.state.value, moonLayout.value)

    fun watch(onState: (DetailScreenState) -> Unit): Subscription {
        val job = scope.launch {
            combine(detail.state, logger.state, moonLayout) { d, l, moon ->
                lastDetail = d
                syncLogTarget(d)
                map(d, l, moon)
            }.collect { onState(it) }
        }
        return Subscription { job.cancel() }
    }

    fun open(uuid: String, angle: Int) = detail.open(uuid, angle)

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

    fun sendToBoard() {
        lastDetail?.let { sender.send(it) }
    }

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

    private fun map(state: ClimbDetailUiState, log: LogAttemptState, moon: MoonLayout?): DetailScreenState {
        val data = state.data
        val climb = data?.climb
        val brand = data?.boardSize?.boardBrand
        val variant = if (climb != null && brand == null || brand == BoardBrand.MOONBOARD) {
            climb?.layoutId?.let { MoonBoardVariant.fromLayoutId(it) }
        } else {
            null
        }
        val holds = state.holds.mapNotNull { hold ->
            when {
                hold.x >= 0f && hold.y >= 0f -> DetailHoldUi(hold.placementId, hold.x, hold.y, hold.argb)
                moon != null -> moon.geometry.point(hold.placementId, 1f, 1f)
                    ?.let { DetailHoldUi(hold.placementId, it.x, it.y, hold.argb) }
                else -> null
            }
        }
        val size = data?.boardSize
        val aspect = when {
            moon != null -> moon.geometry.imageAspect
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
            showLogDialog = log.ascent.showDialog,
            logIsSend = log.ascent.isSend,
            logTries = log.ascent.bidCount,
            logQuality = log.ascent.quality,
            logComment = log.ascent.comment,
            logBenchmark = log.ascent.isBenchmark,
        )
    }

    companion object {
        /** Used when neither an image nor product-size edges are known. */
        const val DEFAULT_ASPECT = 0.65f
    }
}
