package com.cruxcoach.app.playback

import com.cruxcoach.app.ui.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Android's route-playback preferences (`route_*` keys), read when playback starts. */
data class RoutePlaybackPrefs(
    val frameSpeedSec: Float = 5f,
    val useSetterSpeed: Boolean = true,
    val countdown: Boolean = true,
    val countdownSeconds: Int = 5,
    val autoLoop: Boolean = false,
) {
    internal companion object {
        fun from(s: com.cruxcoach.app.settings.SettingsSnapshot) = RoutePlaybackPrefs(
            frameSpeedSec = s.routeFrameSpeed,
            useSetterSpeed = s.routeUseSetterSpeed,
            countdown = s.routeCountdown,
            countdownSeconds = s.routeCountdownSeconds,
            autoLoop = s.routeAutoLoop,
        )
    }
}

/** Android `PlaybackState` minus the frames themselves, which the detail presenter owns. */
data class RoutePlaybackState(
    val climbKey: String = "",
    val currentFrameIndex: Int = 0,
    val totalFrames: Int = 1,
    val isRoute: Boolean = false,
    /** Android `RoutePlaybackMode.AUTO`. */
    val auto: Boolean = false,
    val isPlaying: Boolean = false,
    val isLooping: Boolean = false,
    val speedSec: Float = 5f,
    val countdownSeconds: Int = 0,
    /** False while something else owns the wall — a running playlist, or no board. */
    val canReachBoard: Boolean = true,
)

/**
 * Port of Android `RoutePlaybackController`: play/stop, next/previous frame,
 * countdown, looping and speed for a route — a climb whose holds come in
 * sections ("frames") shown one after another on screen and on the board.
 *
 * [onFrameChanged] shows the frame and, when the board can be reached, sends
 * it: frame navigation is already an explicit user command, so every frame
 * goes to the wall regardless of the saved send mode (Android).
 * [pause] is `delay` in the app and virtual time in tests.
 */
class RoutePlaybackPresenter(
    private val scope: CoroutineScope,
    private val prefs: () -> RoutePlaybackPrefs,
    private val saveAutoLoop: (Boolean) -> Unit,
    private val onFrameChanged: (index: Int) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val _state = MutableStateFlow(
        prefs().let { RoutePlaybackState(speedSec = it.frameSpeedSec, isLooping = it.autoLoop) },
    )
    val state: StateFlow<RoutePlaybackState> = _state.asStateFlow()

    private var playbackJob: Job? = null

    /**
     * A climb (or another angle of it) was loaded. Android stops the work of
     * the variant left behind and starts the new one on its first frame;
     * the setter's pace wins when the user asked for it and one is saved,
     * otherwise the current speed carries over. Returns false when nothing changed.
     */
    fun load(climbKey: String, totalFrames: Int, framesPaceMs: Int): Boolean {
        if (climbKey == _state.value.climbKey && totalFrames.coerceAtLeast(1) == _state.value.totalFrames) return false
        stopPlayback()
        val isRoute = totalFrames > 1
        val p = prefs()
        val speedOverride = if (p.useSetterSpeed && isRoute && framesPaceMs > 0) framesPaceMs.toFloat() / 1000f else null
        _state.update {
            it.copy(
                climbKey = climbKey,
                currentFrameIndex = 0,
                totalFrames = totalFrames.coerceAtLeast(1),
                isRoute = isRoute,
                speedSec = speedOverride ?: it.speedSec,
            )
        }
        return true
    }

    fun setCanReachBoard(canReach: Boolean) = _state.update { it.copy(canReachBoard = canReach) }

    fun goToFrame(index: Int) {
        val p = _state.value
        if (!p.isRoute || index !in 0 until p.totalFrames) return
        _state.update { it.copy(currentFrameIndex = index) }
        onFrameChanged(index)
    }

    fun nextFrame() {
        val p = _state.value
        if (!p.isRoute) return
        val next = when {
            p.currentFrameIndex < p.totalFrames - 1 -> p.currentFrameIndex + 1
            p.isLooping -> 0
            else -> return
        }
        goToFrame(next)
    }

    fun previousFrame() {
        val p = _state.value
        if (!p.isRoute) return
        val prev = when {
            p.currentFrameIndex > 0 -> p.currentFrameIndex - 1
            p.isLooping -> p.totalFrames - 1
            else -> return
        }
        goToFrame(prev)
    }

    fun startPlayback() {
        val p = _state.value
        if (!p.isRoute || p.isPlaying) return
        stopPlayback()
        _state.update { it.copy(auto = true, isPlaying = true) }
        playbackJob = scope.launch {
            val settings = prefs()
            if (settings.countdown) {
                for (i in settings.countdownSeconds downTo 1) {
                    _state.update { it.copy(countdownSeconds = i) }
                    pause(1000L)
                }
                _state.update { it.copy(countdownSeconds = 0) }
            }
            val speed = _state.value.speedSec
            while (_state.value.isPlaying) {
                pause((speed * 1000).toLong())
                val pb = _state.value
                if (!pb.isPlaying) break
                val next = pb.currentFrameIndex + 1
                if (next >= pb.totalFrames) {
                    if (pb.isLooping) {
                        goToFrame(0)
                    } else {
                        _state.update { it.copy(isPlaying = false, auto = false) }
                        break
                    }
                } else {
                    goToFrame(next)
                }
            }
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _state.update { it.copy(isPlaying = false, countdownSeconds = 0, auto = false) }
    }

    fun toggleLoop() {
        _state.update { it.copy(isLooping = !it.isLooping) }
        saveAutoLoop(_state.value.isLooping)
    }

    /** Android restarts a running playback with the new speed (countdown included). */
    fun updateSpeed(seconds: Float) {
        _state.update { it.copy(speedSec = seconds) }
        if (_state.value.isPlaying) {
            stopPlayback()
            startPlayback()
        }
    }

    fun close() {
        playbackJob?.cancel()
        playbackJob = null
    }

    companion object {
        /** Android's speed slider range, in seconds per frame. */
        const val MIN_SPEED_SEC = 1f
        const val MAX_SPEED_SEC = 15f

        /** Android draws the frame dots only up to this many frames. */
        const val MAX_DOTS = 20
    }
}

/** Swift-facing route playback state (Android `RoutePlaybackControls`). */
class RoutePlaybackUiState(
    /** Only a route (more than one frame) shows the controls. */
    val isRoute: Boolean,
    /** 1-based, for `board_playback_frame`. */
    val frameNumber: Int,
    val totalFrames: Int,
    val currentFrameIndex: Int,
    val isPlaying: Boolean,
    val isLooping: Boolean,
    val speedSeconds: Double,
    /** > 0 while the start countdown runs. */
    val countdownSeconds: Int,
    /** False: `route_playback_board_busy` — playback stays on screen. */
    val canReachBoard: Boolean,
    val canFirst: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canLast: Boolean,
    /** Play needs a reachable board; stop is always allowed. */
    val canTogglePlay: Boolean,
    val showDots: Boolean,
    val minSpeedSeconds: Double,
    val maxSpeedSeconds: Double,
)

/** Facade over [RoutePlaybackPresenter] for the climb detail. */
class RoutePlaybackModel internal constructor(
    private val presenter: RoutePlaybackPresenter,
    private val scope: CoroutineScope,
) {
    val currentState: RoutePlaybackUiState get() = map(presenter.state.value)

    fun watch(onState: (RoutePlaybackUiState) -> Unit): Subscription {
        val job = scope.launch { presenter.state.collect { onState(map(it)) } }
        return Subscription { job.cancel() }
    }

    fun goToFrame(index: Int) = presenter.goToFrame(index)
    fun firstFrame() = presenter.goToFrame(0)
    fun lastFrame() = presenter.goToFrame(presenter.state.value.totalFrames - 1)
    fun nextFrame() = presenter.nextFrame()
    fun previousFrame() = presenter.previousFrame()

    fun togglePlay() {
        if (presenter.state.value.isPlaying) presenter.stopPlayback() else presenter.startPlayback()
    }

    fun stop() = presenter.stopPlayback()
    fun toggleLoop() = presenter.toggleLoop()

    fun setSpeed(seconds: Double) = presenter.updateSpeed(
        seconds.toFloat().coerceIn(RoutePlaybackPresenter.MIN_SPEED_SEC, RoutePlaybackPresenter.MAX_SPEED_SEC),
    )

    fun close() {
        presenter.close()
        scope.cancel()
    }

    private fun map(p: RoutePlaybackState): RoutePlaybackUiState {
        val idle = !p.isPlaying
        return RoutePlaybackUiState(
            isRoute = p.isRoute,
            frameNumber = p.currentFrameIndex + 1,
            totalFrames = p.totalFrames,
            currentFrameIndex = p.currentFrameIndex,
            isPlaying = p.isPlaying,
            isLooping = p.isLooping,
            speedSeconds = p.speedSec.toDouble(),
            countdownSeconds = p.countdownSeconds,
            canReachBoard = p.canReachBoard,
            canFirst = p.currentFrameIndex > 0 && idle,
            canPrevious = (p.currentFrameIndex > 0 || p.isLooping) && idle,
            canNext = (p.currentFrameIndex < p.totalFrames - 1 || p.isLooping) && idle,
            canLast = p.currentFrameIndex < p.totalFrames - 1 && idle,
            canTogglePlay = p.canReachBoard || p.isPlaying,
            showDots = p.totalFrames <= RoutePlaybackPresenter.MAX_DOTS,
            minSpeedSeconds = RoutePlaybackPresenter.MIN_SPEED_SEC.toDouble(),
            maxSpeedSeconds = RoutePlaybackPresenter.MAX_SPEED_SEC.toDouble(),
        )
    }
}

/** What the climb detail needs for route playback: the detail model and the playback beside it. */
class DetailWithPlayback(
    val detail: com.cruxcoach.app.ui.DetailScreenModel,
    val playback: RoutePlaybackModel,
)
