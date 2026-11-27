package com.cruxcoach.android.ui.board

import com.cruxcoach.android.data.UserPreferences
import com.cruxcoach.domain.board.BoardHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Controls route/multi-frame playback: play/pause, next/prev frame,
 * countdown, looping, and speed.
 *
 * Plain Kotlin class (not a ViewModel). Receives a [CoroutineScope] from the
 * parent ViewModel for launching coroutines.
 *
 * While [startPlayback] is running it owns the board: [onBoardOwnershipChanged]
 * is called with true, then false when the run ends — by [stopPlayback], by the
 * last frame of a non-looping route, or by the scope being cancelled. Automatic
 * playback writes its own frame every `speedSec` seconds, which is exactly what
 * the LED animation would fight over, so the animation stands down for the
 * duration. Manual frame stepping deliberately does not claim ownership: the
 * animation animating the frame you stopped on is the point of it.
 */
internal class RoutePlaybackController(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ClimbDetailState>,
    private val userPreferences: UserPreferences,
    private val onFrameChanged: (holds: List<BoardHold>) -> Unit,
    private val onBoardOwnershipChanged: (owned: Boolean) -> Unit = {}
) {

    private var playbackJob: Job? = null

    /**
     * Bumped on every start and stop, so a superseded run's `finally` cannot
     * release ownership that a newer run has just claimed. [updateSpeed] restarts
     * playback, and the cancelled job's resumption is dispatched — without this it
     * could land after the new run's claim and leave the animation running in the
     * middle of a route.
     */
    private var ownershipGeneration = 0

    fun goToFrame(index: Int) {
        val p = state.value.playback
        if (!p.isRoute || index !in p.allFrames.indices) return
        val frameHolds = p.allFrames[index]
        state.update { it.copy(
            holds = frameHolds,
            playback = it.playback.copy(currentFrameIndex = index, showPreview = false)
        ) }
        onFrameChanged(frameHolds)
    }

    fun nextFrame() {
        val p = state.value.playback
        if (!p.isRoute) return
        val next = if (p.currentFrameIndex < p.totalFrames - 1) p.currentFrameIndex + 1
                   else if (p.isLooping) 0 else return
        goToFrame(next)
    }

    fun previousFrame() {
        val p = state.value.playback
        if (!p.isRoute) return
        val prev = if (p.currentFrameIndex > 0) p.currentFrameIndex - 1
                   else if (p.isLooping) p.totalFrames - 1 else return
        goToFrame(prev)
    }

    fun startPlayback() {
        val p = state.value.playback
        if (!p.isRoute || p.isPlaying) return
        stopPlayback()
        state.update { it.copy(playback = it.playback.copy(mode = RoutePlaybackMode.AUTO, isPlaying = true)) }

        val generation = ++ownershipGeneration
        playbackJob = scope.launch {
            // Claimed here, not before launch: a launch into an already-cancelled
            // scope never runs its block, and a claim made outside would then have
            // no `finally` left to release it — the animation would stay paused for
            // the whole process. On this Main.immediate scope the claim still lands
            // synchronously, ahead of the first countdown tick.
            onBoardOwnershipChanged(true)
            try {
                runPlayback()
            } finally {
                if (generation == ownershipGeneration) onBoardOwnershipChanged(false)
            }
        }
    }

    private suspend fun runPlayback() {
        val useCountdown = userPreferences.routeCountdown.first()
        if (useCountdown) {
            val countdownDuration = userPreferences.routeCountdownSeconds.first()
            for (i in countdownDuration downTo 1) {
                state.update { it.copy(playback = it.playback.copy(countdownSeconds = i)) }
                delay(1000L)
            }
            state.update { it.copy(playback = it.playback.copy(countdownSeconds = 0)) }
        }

        val speed = state.value.playback.speedSec
        while (state.value.playback.isPlaying) {
            delay((speed * 1000).toLong())
            val pb = state.value.playback
            if (!pb.isPlaying) break
            val next = pb.currentFrameIndex + 1
            if (next >= pb.totalFrames) {
                if (pb.isLooping) {
                    goToFrame(0)
                } else {
                    state.update { it.copy(playback = it.playback.copy(isPlaying = false, mode = RoutePlaybackMode.MANUAL)) }
                    break
                }
            } else {
                goToFrame(next)
            }
        }
    }

    fun stopPlayback() {
        // Invalidate first: the cancelled job must not hand back ownership that
        // this call is about to release (or that a restart has just taken).
        ownershipGeneration++
        playbackJob?.cancel()
        playbackJob = null
        onBoardOwnershipChanged(false)
        state.update { it.copy(playback = it.playback.copy(isPlaying = false, countdownSeconds = 0, mode = RoutePlaybackMode.MANUAL)) }
    }

    fun toggleLoop() {
        state.update { it.copy(playback = it.playback.copy(isLooping = !it.playback.isLooping)) }
        scope.launch {
            userPreferences.setRouteAutoLoop(state.value.playback.isLooping)
        }
    }

    fun togglePreview() {
        val s = state.value
        val p = s.playback
        if (!p.isRoute) return
        val newPreview = !p.showPreview
        val holds = if (newPreview) {
            p.allFrames.flatten()
        } else {
            p.allFrames.getOrElse(p.currentFrameIndex) { emptyList() }
        }
        state.update { it.copy(holds = holds, playback = it.playback.copy(showPreview = newPreview)) }
    }

    fun updateSpeed(seconds: Float) {
        state.update { it.copy(playback = it.playback.copy(speedSec = seconds)) }
        if (state.value.playback.isPlaying) {
            stopPlayback()
            startPlayback()
        }
    }
}
