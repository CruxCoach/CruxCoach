package com.cruxcoach.domain.spraywall

import kotlin.math.abs

/** A crop rectangle in 0..1 of the (already rotated) photo. */
data class SprayCropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val isFull: Boolean get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    companion object {
        val FULL = SprayCropRect(0f, 0f, 1f, 1f)
    }
}

/** The crop box of the photo step: which part a finger grabs and how a drag changes the box. */
object SprayCropBox {
    /** Smallest crop, as a share of each side: keeps a wall photo from being cropped to a sliver. */
    const val MIN_SIDE = 0.15f

    enum class Handle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, MOVE }

    /**
     * The handle under a touch at (x, y) in 0..1 of the photo. [reachX]/[reachY]
     * are the grab distance in the same units (they differ on a non-square photo).
     * Corners win over edges, edges over moving the box; null = outside the box.
     */
    fun handleAt(rect: SprayCropRect, x: Float, y: Float, reachX: Float, reachY: Float): Handle? {
        val nearL = abs(x - rect.left) <= reachX
        val nearR = abs(x - rect.right) <= reachX
        val nearT = abs(y - rect.top) <= reachY
        val nearB = abs(y - rect.bottom) <= reachY
        val withinX = x >= rect.left - reachX && x <= rect.right + reachX
        val withinY = y >= rect.top - reachY && y <= rect.bottom + reachY
        return when {
            nearL && nearT -> Handle.TOP_LEFT
            nearR && nearT -> Handle.TOP_RIGHT
            nearL && nearB -> Handle.BOTTOM_LEFT
            nearR && nearB -> Handle.BOTTOM_RIGHT
            nearL && withinY -> Handle.LEFT
            nearR && withinY -> Handle.RIGHT
            nearT && withinX -> Handle.TOP
            nearB && withinX -> Handle.BOTTOM
            x > rect.left && x < rect.right && y > rect.top && y < rect.bottom -> Handle.MOVE
            else -> null
        }
    }

    /** Moves [handle] by (dx, dy), keeping the box on the photo and at least [MIN_SIDE] on each side. */
    fun drag(rect: SprayCropRect, handle: Handle, dx: Float, dy: Float): SprayCropRect {
        var l = rect.left
        var t = rect.top
        var r = rect.right
        var b = rect.bottom
        when (handle) {
            Handle.MOVE -> {
                val mx = dx.coerceIn(-l, 1f - r)
                val my = dy.coerceIn(-t, 1f - b)
                return SprayCropRect(l + mx, t + my, r + mx, b + my)
            }
            Handle.TOP_LEFT -> { l += dx; t += dy }
            Handle.TOP_RIGHT -> { r += dx; t += dy }
            Handle.BOTTOM_LEFT -> { l += dx; b += dy }
            Handle.BOTTOM_RIGHT -> { r += dx; b += dy }
            Handle.LEFT -> l += dx
            Handle.RIGHT -> r += dx
            Handle.TOP -> t += dy
            Handle.BOTTOM -> b += dy
        }
        l = l.coerceIn(0f, rect.right - MIN_SIDE).let { if (handle.movesLeft()) it else rect.left }
        r = r.coerceIn(rect.left + MIN_SIDE, 1f).let { if (handle.movesRight()) it else rect.right }
        t = t.coerceIn(0f, rect.bottom - MIN_SIDE).let { if (handle.movesTop()) it else rect.top }
        b = b.coerceIn(rect.top + MIN_SIDE, 1f).let { if (handle.movesBottom()) it else rect.bottom }
        return SprayCropRect(l, t, r, b)
    }

    /** The crop after the photo is turned a quarter clockwise. */
    fun rotateClockwise(rect: SprayCropRect): SprayCropRect =
        SprayCropRect(left = 1f - rect.bottom, top = rect.left, right = 1f - rect.top, bottom = rect.right)

    private fun Handle.movesLeft() = this == Handle.LEFT || this == Handle.TOP_LEFT || this == Handle.BOTTOM_LEFT
    private fun Handle.movesRight() = this == Handle.RIGHT || this == Handle.TOP_RIGHT || this == Handle.BOTTOM_RIGHT
    private fun Handle.movesTop() = this == Handle.TOP || this == Handle.TOP_LEFT || this == Handle.TOP_RIGHT
    private fun Handle.movesBottom() = this == Handle.BOTTOM || this == Handle.BOTTOM_LEFT || this == Handle.BOTTOM_RIGHT
}
