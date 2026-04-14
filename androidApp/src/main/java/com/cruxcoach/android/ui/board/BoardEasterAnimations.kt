package com.cruxcoach.android.ui.board

import com.cruxcoach.data.repository.LedGridPoint
import com.cruxcoach.domain.board.AuroraPacketEncoder

enum class EasterAnimation { EGG }

data class AnimationFrame(val leds: List<Pair<Int, Int>>)

/**
 * Easter-themed LED animation patterns for Aurora Climbing boards.
 * Each animation returns a list of frames; each frame is a list of (ledPosition, rgb332Color) pairs.
 */
object BoardEasterAnimations {

    // Pastel color palettes (RGB332 encoded)
    private val PASTEL_PINK = AuroraPacketEncoder.encodeColor(255, 150, 180)
    private val PASTEL_YELLOW = AuroraPacketEncoder.encodeColor(255, 240, 100)
    private val PASTEL_MINT = AuroraPacketEncoder.encodeColor(130, 255, 180)
    private val PASTEL_LAVENDER = AuroraPacketEncoder.encodeColor(200, 160, 255)
    private val PASTEL_ORANGE = AuroraPacketEncoder.encodeColor(255, 190, 100)
    private val PASTEL_SKY = AuroraPacketEncoder.encodeColor(130, 200, 255)

    // Each palette defines 7 stripe colors for a richly decorated egg.
    // Colors shift between frames for the "breathing" animation effect.
    private val EGG_PALETTES = listOf(
        listOf(PASTEL_PINK, PASTEL_YELLOW, PASTEL_MINT, PASTEL_LAVENDER, PASTEL_YELLOW, PASTEL_PINK, PASTEL_MINT),
        listOf(PASTEL_YELLOW, PASTEL_MINT, PASTEL_LAVENDER, PASTEL_PINK, PASTEL_MINT, PASTEL_YELLOW, PASTEL_LAVENDER),
        listOf(PASTEL_MINT, PASTEL_LAVENDER, PASTEL_PINK, PASTEL_YELLOW, PASTEL_LAVENDER, PASTEL_MINT, PASTEL_PINK),
        listOf(PASTEL_LAVENDER, PASTEL_PINK, PASTEL_YELLOW, PASTEL_MINT, PASTEL_PINK, PASTEL_LAVENDER, PASTEL_YELLOW),
        listOf(PASTEL_ORANGE, PASTEL_SKY, PASTEL_PINK, PASTEL_MINT, PASTEL_SKY, PASTEL_ORANGE, PASTEL_PINK),
        listOf(PASTEL_SKY, PASTEL_ORANGE, PASTEL_MINT, PASTEL_PINK, PASTEL_ORANGE, PASTEL_SKY, PASTEL_MINT),
        listOf(PASTEL_PINK, PASTEL_LAVENDER, PASTEL_ORANGE, PASTEL_SKY, PASTEL_LAVENDER, PASTEL_PINK, PASTEL_ORANGE),
        listOf(PASTEL_MINT, PASTEL_YELLOW, PASTEL_SKY, PASTEL_ORANGE, PASTEL_YELLOW, PASTEL_MINT, PASTEL_SKY)
    )

    /**
     * Easter Egg: a pulsing pastel egg shape with color-shifting horizontal stripes.
     * 8 frames, designed to loop.
     *
     * Egg shape uses an asymmetric width profile: wider at the bottom, tapering toward the top.
     * Board coordinate system: Y increases upward, so y > cy = upper half = narrow end.
     */
    fun easterEgg(grid: List<LedGridPoint>): List<AnimationFrame> {
        if (grid.isEmpty()) return emptyList()

        val minX = grid.minOf { it.x }
        val maxX = grid.maxOf { it.x }
        val minY = grid.minOf { it.y }
        val maxY = grid.maxOf { it.y }
        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0
        val boardW = (maxX - minX).coerceAtLeast(1)
        val boardH = (maxY - minY).coerceAtLeast(1)

        // Egg dimensions: ~65% board width, ~80% board height
        val halfW = boardW * 0.325
        val halfH = boardH * 0.40
        val eggBottom = cy - halfH
        val eggHeight = (2.0 * halfH).coerceAtLeast(1.0)

        return EGG_PALETTES.map { palette ->
            val leds = grid.mapNotNull { point ->
                val dy = (point.y - cy) / halfH  // -1 at bottom, +1 at top
                if (dy < -1.0 || dy > 1.0) return@mapNotNull null

                // Egg profile: sqrt(1-dy²) gives rounded ends (zero at top & bottom),
                // (1 - 0.2*dy) makes bottom wider than top (classic egg asymmetry).
                val profile = kotlin.math.sqrt(1.0 - dy * dy) * (1.0 - 0.2 * dy)
                val widthAtY = halfW * profile

                val absDx = kotlin.math.abs(point.x - cx)
                if (absDx > widthAtY) return@mapNotNull null

                // Stripe position relative to egg's own height (0=bottom, 1=top)
                val normalizedY = ((point.y - eggBottom) / eggHeight).coerceIn(0.0, 0.999)
                val stripeIndex = (normalizedY * palette.size).toInt()
                point.ledPosition.toInt() to palette[stripeIndex]
            }
            AnimationFrame(leds)
        }
    }
}
