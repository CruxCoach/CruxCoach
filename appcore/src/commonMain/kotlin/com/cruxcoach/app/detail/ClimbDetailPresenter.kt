package com.cruxcoach.app.detail

import com.cruxcoach.app.browse.BrowsePreferences
import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.render.LedHoldColors
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.data.repository.BoardRepository
import com.cruxcoach.data.repository.PersonalBoardRepository
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ClimbDetailStatus { LOADING, READY, LOGBOOK_ONLY, NOT_FOUND, FAILED }
enum class NoteSaveStatus { IDLE, SAVING, SAVED, FAILED }

data class ClimbDetailUiState(
    val status: ClimbDetailStatus = ClimbDetailStatus.LOADING,
    val uuid: String = "",
    val angle: Int = 40,
    val data: ClimbDetailData? = null,
    /** Holds of [frameIndex] (mirrored when [isMirrored]) ready to draw. */
    val holds: List<RenderHold> = emptyList(),
    val frameIndex: Int = 0,
    val frameCount: Int = 0,
    val isMirrored: Boolean = false,
    val isFavorited: Boolean = false,
    val isIgnored: Boolean = false,
    val personalNote: String = "",
    val noteStatus: NoteSaveStatus = NoteSaveStatus.IDLE,
    val logbookAscents: List<AscentWithClimb> = emptyList(),
    /** Set when ignore/favourite/ascents changed: the browser should call refresh(). */
    val browserDirty: Boolean = false,
)

/** Swift-facing climb detail (data side only: no BLE send, logging, playback or publishing). Never throws. */
class ClimbDetailPresenter(
    boardRepository: BoardRepository,
    personalBoardRepo: PersonalBoardRepository,
    keyValues: KeyValueStore,
    /** Kilter is user-configurable on Android; other brands use their standard palette. */
    private val kilterColors: LedHoldColors = LedHoldColors(),
    main: CoroutineDispatcher = Dispatchers.Main,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    private val assembler = ClimbDetailAssembler(boardRepository, personalBoardRepo, BrowsePreferences(keyValues))
    private val scope = CoroutineScope(SupervisorJob() + main)
    private var loadJob: Job? = null
    private var noteJob: Job? = null

    private val _state = MutableStateFlow(ClimbDetailUiState())
    val state: StateFlow<ClimbDetailUiState> = _state.asStateFlow()

    fun watch(onState: (ClimbDetailUiState) -> Unit): ClimbDetailWatch =
        ClimbDetailWatch(scope.launch { _state.collect { onState(it) } })

    fun close() = scope.cancel()

    fun open(uuid: String, angle: Int) {
        _state.value = ClimbDetailUiState(uuid = uuid, angle = angle)
        load(uuid, angle)
    }

    fun selectAngle(angle: Int) {
        val s = _state.value
        if (angle == s.angle || s.uuid.isEmpty()) return
        _state.update { it.copy(status = ClimbDetailStatus.LOADING, angle = angle) }
        load(s.uuid, angle)
    }

    private fun load(uuid: String, angle: Int) {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val result = withContext(io) { assembler.load(uuid, angle) }
                _state.update { s ->
                    if (s.uuid != uuid || s.angle != angle) return@update s
                    when (result) {
                        is ClimbDetailLoad.Found -> render(
                            s.copy(
                                status = ClimbDetailStatus.READY, data = result.data,
                                frameCount = result.data.frames.size,
                                frameIndex = s.frameIndex.coerceIn(0, (result.data.frames.size - 1).coerceAtLeast(0)),
                                isMirrored = s.isMirrored && result.data.isMirrorable,
                                isFavorited = result.data.isFavorited, isIgnored = result.data.isIgnored,
                                personalNote = result.data.personalNote, logbookAscents = emptyList(),
                            ),
                        )
                        is ClimbDetailLoad.LogbookOnly -> s.copy(
                            status = ClimbDetailStatus.LOGBOOK_ONLY, data = null, holds = emptyList(), logbookAscents = result.ascents,
                        )
                        ClimbDetailLoad.NotFound -> s.copy(status = ClimbDetailStatus.NOT_FOUND, data = null, holds = emptyList())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = ClimbDetailStatus.FAILED) }
            }
        }
    }

    private fun render(s: ClimbDetailUiState): ClimbDetailUiState {
        val data = s.data ?: return s
        val frame = data.frames.getOrElse(s.frameIndex) { emptyList() }
        val holds = if (s.isMirrored) ClimbDetailAssembler.mirrorHolds(frame, data.mirrorMap) else frame
        val colors = if (data.climb.brand == BoardBrand.KILTER) kilterColors else LedHoldColors.standardFor(data.climb.brand)
        return s.copy(holds = ClimbDetailAssembler.renderHolds(data, holds, colors))
    }

    fun selectFrame(index: Int) = _state.update { s ->
        if (s.frameCount == 0) s else render(s.copy(frameIndex = index.coerceIn(0, s.frameCount - 1)))
    }

    /** No-op on layouts that are not left-right symmetric. */
    fun toggleMirror() = _state.update { s ->
        if (s.data?.isMirrorable != true) s else render(s.copy(isMirrored = !s.isMirrored))
    }

    // A failed write leaves the previous flag visible instead of flipping the icon.
    fun toggleFavourite() = write({ assembler.toggleFavourite(it) }) { s, value -> s.copy(isFavorited = value) }

    /** Ignored climbs drop out of every browse list, so the browser is flagged dirty. */
    fun setIgnored(ignored: Boolean) =
        write({ assembler.setIgnored(it, ignored) }) { s, value -> s.copy(isIgnored = value, browserDirty = true) }

    private fun write(op: (String) -> Boolean, apply: (ClimbDetailUiState, Boolean) -> ClimbDetailUiState) {
        val uuid = _state.value.data?.climb?.uuid ?: return
        scope.launch {
            try {
                val value = withContext(io) { op(uuid) }
                _state.update { if (it.data?.climb?.uuid == uuid) apply(it, value) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep the previous state
            }
        }
    }

    fun saveNote(note: String) {
        val uuid = _state.value.data?.climb?.uuid ?: return
        noteJob?.cancel()
        _state.update { it.copy(noteStatus = NoteSaveStatus.SAVING) }
        noteJob = scope.launch {
            try {
                val stored = withContext(io) { assembler.saveNote(uuid, note) }
                _state.update { if (it.data?.climb?.uuid == uuid) it.copy(personalNote = stored, noteStatus = NoteSaveStatus.SAVED) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { if (it.data?.climb?.uuid == uuid) it.copy(noteStatus = NoteSaveStatus.FAILED) else it }
            }
        }
    }

    fun consumeBrowserDirty() = _state.update { it.copy(browserDirty = false) }
}

class ClimbDetailWatch internal constructor(private val job: Job) {
    fun close() = job.cancel()
}
