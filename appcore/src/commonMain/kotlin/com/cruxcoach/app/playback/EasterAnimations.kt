package com.cruxcoach.app.playback

import com.cruxcoach.app.platform.KeyValueStore
import com.cruxcoach.app.ui.Subscription
import com.cruxcoach.data.repository.LedGridPoint
import com.cruxcoach.domain.board.BoardPacketEncoder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Port of Android `BoardEasterAnimations`: a pulsing pastel egg with
 * colour-shifting stripes, 8 frames designed to loop. Each frame is a list of
 * (LED position, RGB332 colour) pairs.
 */
internal object EasterEggFrames {
    private val PASTEL_PINK = BoardPacketEncoder.encodeColor(255, 150, 180)
    private val PASTEL_YELLOW = BoardPacketEncoder.encodeColor(255, 240, 100)
    private val PASTEL_MINT = BoardPacketEncoder.encodeColor(130, 255, 180)
    private val PASTEL_LAVENDER = BoardPacketEncoder.encodeColor(200, 160, 255)
    private val PASTEL_ORANGE = BoardPacketEncoder.encodeColor(255, 190, 100)
    private val PASTEL_SKY = BoardPacketEncoder.encodeColor(130, 200, 255)

    /** 7 stripe colours per frame; they shift between frames for the "breathing" effect. */
    private val EGG_PALETTES = listOf(
        listOf(PASTEL_PINK, PASTEL_YELLOW, PASTEL_MINT, PASTEL_LAVENDER, PASTEL_YELLOW, PASTEL_PINK, PASTEL_MINT),
        listOf(PASTEL_YELLOW, PASTEL_MINT, PASTEL_LAVENDER, PASTEL_PINK, PASTEL_MINT, PASTEL_YELLOW, PASTEL_LAVENDER),
        listOf(PASTEL_MINT, PASTEL_LAVENDER, PASTEL_PINK, PASTEL_YELLOW, PASTEL_LAVENDER, PASTEL_MINT, PASTEL_PINK),
        listOf(PASTEL_LAVENDER, PASTEL_PINK, PASTEL_YELLOW, PASTEL_MINT, PASTEL_PINK, PASTEL_LAVENDER, PASTEL_YELLOW),
        listOf(PASTEL_ORANGE, PASTEL_SKY, PASTEL_PINK, PASTEL_MINT, PASTEL_SKY, PASTEL_ORANGE, PASTEL_PINK),
        listOf(PASTEL_SKY, PASTEL_ORANGE, PASTEL_MINT, PASTEL_PINK, PASTEL_ORANGE, PASTEL_SKY, PASTEL_MINT),
        listOf(PASTEL_PINK, PASTEL_LAVENDER, PASTEL_ORANGE, PASTEL_SKY, PASTEL_LAVENDER, PASTEL_PINK, PASTEL_ORANGE),
        listOf(PASTEL_MINT, PASTEL_YELLOW, PASTEL_SKY, PASTEL_ORANGE, PASTEL_YELLOW, PASTEL_MINT, PASTEL_SKY),
    )

    /**
     * Egg about 65 % of the board wide and 80 % high, wider at the bottom. Board
     * coordinates grow upward, so above the centre is the narrow end.
     */
    fun easterEgg(grid: List<LedGridPoint>): List<List<Pair<Int, Int>>> {
        if (grid.isEmpty()) return emptyList()
        val minX = grid.minOf { it.x }
        val maxX = grid.maxOf { it.x }
        val minY = grid.minOf { it.y }
        val maxY = grid.maxOf { it.y }
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val boardW = (maxX - minX).coerceAtLeast(1)
        val boardH = (maxY - minY).coerceAtLeast(1)
        val halfW = boardW * 0.325
        val halfH = boardH * 0.40
        val eggBottom = cy - halfH
        val eggHeight = (2.0 * halfH).coerceAtLeast(1.0)

        return EGG_PALETTES.map { palette ->
            grid.mapNotNull { point ->
                val dy = (point.y - cy) / halfH // -1 at the bottom, +1 at the top
                if (dy < -1.0 || dy > 1.0) return@mapNotNull null
                // Rounded ends, and the bottom wider than the top (an egg, not an ellipse).
                val profile = sqrt(1.0 - dy * dy) * (1.0 - 0.2 * dy)
                val widthAtY = halfW * profile
                if (abs(point.x - cx) > widthAtY) return@mapNotNull null
                val normalizedY = ((point.y - eggBottom) / eggHeight).coerceIn(0.0, 0.999)
                val stripeIndex = (normalizedY * palette.size).toInt()
                point.ledPosition.toInt() to palette[stripeIndex]
            }
        }
    }

    /** Android plays the egg three times, a frame every 250 ms, then clears the board. */
    const val REPEATS = 3
    const val FRAME_MS = 250L

    /** Taps on the app-info row that unlock the animation (Android `AppInfoSection`). */
    const val UNLOCK_TAPS = 7
}

class EasterAnimationState(
    val unlocked: Boolean,
    val isAnimating: Boolean,
    /** A board is connected (Android enables the play button on that alone). */
    val boardConnected: Boolean,
)

/**
 * Android's hidden easter animation in Settings → About: seven taps on the app
 * info unlock it (persisted as `easter_animations_unlocked`), then it can be
 * projected onto a connected Kilter board. Kilter only, gated on the CONNECTED
 * board, as on Android: other boards' LED grids would light the wrong LEDs.
 */
class EasterAnimationModel internal constructor(
    private val keyValues: KeyValueStore,
    private val boardLinked: Flow<Boolean>,
    private val isBoardLinked: () -> Boolean,
    private val connectedIsKilter: () -> Boolean,
    private val loadGrid: () -> List<LedGridPoint>,
    private val sendFrame: suspend (List<Pair<Int, Int>>) -> Boolean,
    private val clearBoard: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val unlocked = MutableStateFlow(keyValues.getString(UNLOCKED_KEY) == "true")
    private val animating = MutableStateFlow(false)
    private var taps = 0
    private var job: Job? = null

    val currentState: EasterAnimationState
        get() = EasterAnimationState(unlocked.value, animating.value, isBoardLinked())

    fun watch(onState: (EasterAnimationState) -> Unit): Subscription {
        val watching = scope.launch {
            combine(unlocked, animating, boardLinked) { u, a, linked -> EasterAnimationState(u, a, linked) }
                .collect { onState(it) }
        }
        return Subscription { watching.cancel() }
    }

    /**
     * One tap on the app-info row. Returns the taps still needed when Android
     * says so (1–3), 0 when this tap unlocked the animation, -1 otherwise.
     */
    fun tapAppInfo(): Int {
        if (unlocked.value) return -1
        taps++
        val remaining = EasterEggFrames.UNLOCK_TAPS - taps
        return when {
            remaining <= 0 -> {
                keyValues.putString(UNLOCKED_KEY, "true")
                unlocked.value = true
                0
            }
            remaining in 1..3 -> remaining
            else -> -1
        }
    }

    fun play() {
        if (!unlocked.value || !isBoardLinked()) return
        job?.cancel()
        job = scope.launch {
            if (!connectedIsKilter()) return@launch
            animating.value = true
            try {
                val grid = withContext(io) { loadGrid() }
                val frames = EasterEggFrames.easterEgg(grid)
                if (frames.isEmpty() || frames.all { it.isEmpty() }) return@launch
                repeat(EasterEggFrames.REPEATS) {
                    for (frame in frames) {
                        if (!sendFrame(frame)) return@launch
                        pause(EasterEggFrames.FRAME_MS)
                    }
                }
                clearBoard()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A BLE, database or encoder failure ends the animation, never the app.
            } finally {
                animating.value = false
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        animating.value = false
        scope.launch {
            try {
                clearBoard()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to clear on a board that went away.
            }
        }
    }

    fun close() {
        job?.cancel()
        scope.cancel()
    }

    internal companion object {
        /** Android's DataStore key. */
        const val UNLOCKED_KEY = "easter_animations_unlocked"
    }
}
