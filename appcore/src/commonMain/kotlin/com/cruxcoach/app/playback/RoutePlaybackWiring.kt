package com.cruxcoach.app.playback

import com.cruxcoach.app.detail.ClimbDetailAssembler
import com.cruxcoach.app.detail.ClimbDetailPresenter
import com.cruxcoach.app.render.LedHoldColors
import com.cruxcoach.app.ui.DetailHoldUi
import com.cruxcoach.data.repository.brand
import com.cruxcoach.domain.board.BoardBrand
import kotlinx.coroutines.flow.MutableStateFlow
import com.cruxcoach.app.detail.ClimbDetailStatus
import com.cruxcoach.app.detail.ClimbDetailUiState
import com.cruxcoach.app.settings.SettingsSnapshot
import com.cruxcoach.app.settings.SettingsStore
import com.cruxcoach.app.ui.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Puts route playback beside a climb detail. The frames are the detail
 * presenter's: a frame change selects it there (so the screen draws it, mirror
 * applied) and, while the board can be reached, sends exactly those holds —
 * read from the presenter's state right after the selection, never from a
 * screen snapshot that may still show the previous frame.
 *
 * Android stops playback and returns to the first frame when the climb or its
 * angle changes ("stopWorkForPreviousVariant"); the presenter keeps its frame
 * index across an angle switch, so it is put back to frame 0 here.
 */
internal fun routePlaybackFor(
    presenter: ClimbDetailPresenter,
    send: (ClimbDetailUiState) -> String,
    reportSendFailure: (String) -> Unit,
    boardLinked: Flow<Boolean>,
    playlistRunning: Flow<Boolean>,
    prefs: () -> RoutePlaybackPrefs,
    saveAutoLoop: (Boolean) -> Unit,
    scope: CoroutineScope,
    pause: suspend (Long) -> Unit = { delay(it) },
    /** The detail presenter's Kilter palette (its default), for the preview. */
    kilterColors: LedHoldColors = LedHoldColors(),
): RoutePlaybackModel {
    var reachable = false
    val playback = RoutePlaybackPresenter(
        scope = scope,
        prefs = prefs,
        saveAutoLoop = saveAutoLoop,
        onFrameChanged = { index ->
            presenter.selectFrame(index)
            if (reachable) {
                val code = send(presenter.state.value)
                if (code != "started") reportSendFailure(code)
            }
        },
        pause = pause,
    )
    scope.launch {
        presenter.state.collect { s ->
            val data = s.data
            if (s.status == ClimbDetailStatus.READY && data != null) {
                val changed = playback.load("${s.uuid}|${s.angle}", data.frames.size, data.climb.framesPace.toInt())
                if (changed && s.frameIndex != 0) presenter.selectFrame(0)
            } else {
                playback.load("${s.uuid}|${s.angle}|${s.status}", 0, 0)
            }
        }
    }
    scope.launch {
        // Android: a frame can land only on a connected board no playlist owns.
        combine(boardLinked, playlistRunning) { linked, running -> linked && !running }.collect {
            reachable = it
            playback.setCanReachBoard(it)
        }
    }
    val preview = MutableStateFlow<List<DetailHoldUi>>(emptyList())
    scope.launch {
        combine(playback.state, presenter.state) { p, s -> if (p.showPreview && p.isRoute) previewHolds(s, kilterColors) else emptyList() }
            .collect { preview.value = it }
    }
    return RoutePlaybackModel(playback, scope, preview)
}

/**
 * Android `togglePreview`: every section's holds at once (mirror applied). A
 * hold that appears in several sections is drawn once, in its last role —
 * what Android's overdrawing shows.
 */
internal fun previewHolds(s: ClimbDetailUiState, kilterColors: LedHoldColors): List<DetailHoldUi> {
    val data = s.data ?: return emptyList()
    val all = data.frames.flatten()
    val shown = if (s.isMirrored) ClimbDetailAssembler.mirrorHolds(all, data.mirrorMap) else all
    val unique = shown.associateBy { it.placementId }.values.toList()
    val colors = if (data.climb.brand == BoardBrand.KILTER) kilterColors else LedHoldColors.standardFor(data.climb.brand)
    return ClimbDetailAssembler.renderHolds(data, unique, colors)
        .filter { it.x >= 0f && it.y >= 0f }
        .map { DetailHoldUi(it.placementId, it.x, it.y, it.argb) }
}

/** Android's "Route playback" settings section. */
class RoutePlaybackSettingsState(
    val useSetterSpeed: Boolean,
    val frameSpeedSeconds: Double,
    val countdown: Boolean,
    val countdownSeconds: Int,
    val autoLoop: Boolean,
    val minSpeedSeconds: Double,
    val maxSpeedSeconds: Double,
    val minCountdownSeconds: Int,
    val maxCountdownSeconds: Int,
)

/** Facade over the `route_*` keys of [SettingsStore], shared with Android's names. */
class RoutePlaybackSettingsModel(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
) {
    val currentState: RoutePlaybackSettingsState get() = map(store.snapshot)

    fun watch(onState: (RoutePlaybackSettingsState) -> Unit): Subscription {
        val job = scope.launch { store.state.collect { onState(map(it)) } }
        return Subscription { job.cancel() }
    }

    fun setUseSetterSpeed(value: Boolean) = store.setRouteUseSetterSpeed(value)

    /** Android's slider range, 1–15 s. */
    fun setFrameSpeed(seconds: Double) = store.setRouteFrameSpeed(
        seconds.toFloat().coerceIn(RoutePlaybackPresenter.MIN_SPEED_SEC, RoutePlaybackPresenter.MAX_SPEED_SEC),
    )

    fun setCountdown(value: Boolean) = store.setRouteCountdown(value)

    /** Android's slider range, 1–10 s. */
    fun setCountdownSeconds(seconds: Int) =
        store.setRouteCountdownSeconds(seconds.coerceIn(MIN_COUNTDOWN_SECONDS, MAX_COUNTDOWN_SECONDS))

    fun setAutoLoop(value: Boolean) = store.setRouteAutoLoop(value)

    fun close() = scope.cancel()

    private fun map(s: SettingsSnapshot) = RoutePlaybackSettingsState(
        useSetterSpeed = s.routeUseSetterSpeed,
        frameSpeedSeconds = s.routeFrameSpeed.toDouble(),
        countdown = s.routeCountdown,
        countdownSeconds = s.routeCountdownSeconds,
        autoLoop = s.routeAutoLoop,
        minSpeedSeconds = RoutePlaybackPresenter.MIN_SPEED_SEC.toDouble(),
        maxSpeedSeconds = RoutePlaybackPresenter.MAX_SPEED_SEC.toDouble(),
        minCountdownSeconds = MIN_COUNTDOWN_SECONDS,
        maxCountdownSeconds = MAX_COUNTDOWN_SECONDS,
    )

    private companion object {
        const val MIN_COUNTDOWN_SECONDS = 1
        const val MAX_COUNTDOWN_SECONDS = 10
    }
}
