package com.cruxcoach.domain.spraywall

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A hold while it is being edited. Negative ids are holds added in this session. */
data class EditableHold(val id: Long, val x: Float, val y: Float, val radius: Float)

/**
 * Pure state of the hold editor: the holds, the picked one, and undo/redo.
 * Every user action is one undo step; a drag is one step from its start to
 * its end (see [beginMove] / [moveSelected]).
 */
data class SprayHoldEditorState(
    val holds: List<EditableHold>,
    val selectedId: Long? = null,
    val lastRadius: Float = DEFAULT_RADIUS,
    val nextTempId: Long = -1,
    val undoStack: List<List<EditableHold>> = emptyList(),
    val redoStack: List<List<EditableHold>> = emptyList(),
) {
    val selected: EditableHold? get() = holds.firstOrNull { it.id == selectedId }
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val canAdd: Boolean get() = holds.size < MAX_HOLDS

    companion object {
        const val MAX_HOLDS = 1500
        const val MIN_RADIUS = 0.006f
        const val MAX_RADIUS = 0.12f
        const val DEFAULT_RADIUS = 0.022f
        private const val MAX_UNDO = 100

        fun from(holds: List<SprayHold>): SprayHoldEditorState {
            val onWall = holds.filter { it.isOnWall }.map { EditableHold(it.id, it.x, it.y, it.radius) }
            val typical = onWall.map { it.radius }.sorted().let { if (it.isEmpty()) DEFAULT_RADIUS else it[it.size / 2] }
            return SprayHoldEditorState(holds = onWall, lastRadius = typical)
        }
    }

    private fun pushUndo(): SprayHoldEditorState =
        copy(undoStack = (undoStack + listOf(holds)).takeLast(MAX_UNDO), redoStack = emptyList())

    /** Adds a hold centred on (x, y), sized like the last hold touched, and picks it. */
    fun add(x: Float, y: Float): SprayHoldEditorState {
        if (!canAdd) return this
        val hold = EditableHold(nextTempId, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), lastRadius)
        return pushUndo().copy(holds = holds + hold, selectedId = hold.id, nextTempId = nextTempId - 1)
    }

    fun select(id: Long?): SprayHoldEditorState =
        if (id == null || holds.any { it.id == id }) copy(selectedId = id) else this

    /** Call once when a drag of the picked hold starts, so the whole drag is one undo step. */
    fun beginMove(): SprayHoldEditorState = if (selected == null) this else pushUndo()

    fun moveSelected(x: Float, y: Float): SprayHoldEditorState {
        val id = selectedId ?: return this
        return copy(holds = holds.map { if (it.id == id) it.copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f)) else it })
    }

    /** Resizing from a slider: call [beginResize] when the finger goes down. */
    fun beginResize(): SprayHoldEditorState = beginMove()

    fun resizeSelected(radius: Float): SprayHoldEditorState {
        val id = selectedId ?: return this
        val r = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)
        return copy(holds = holds.map { if (it.id == id) it.copy(radius = r) else it }, lastRadius = r)
    }

    fun deleteSelected(): SprayHoldEditorState {
        val id = selectedId ?: return this
        return pushUndo().copy(holds = holds.filterNot { it.id == id }, selectedId = null)
    }

    fun undo(): SprayHoldEditorState {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(
            holds = previous,
            undoStack = undoStack.dropLast(1),
            redoStack = redoStack + listOf(holds),
            selectedId = selectedId?.takeIf { id -> previous.any { it.id == id } },
        )
    }

    fun redo(): SprayHoldEditorState {
        val next = redoStack.lastOrNull() ?: return this
        return copy(
            holds = next,
            redoStack = redoStack.dropLast(1),
            undoStack = undoStack + listOf(holds),
            selectedId = selectedId?.takeIf { id -> next.any { it.id == id } },
        )
    }
}

/** What saving the editor changes on the wall. */
data class SprayHoldDiff(
    val added: List<EditableHold>,
    val changed: List<EditableHold>,
    val removedIds: Set<Long>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && changed.isEmpty() && removedIds.isEmpty()

    companion object {
        fun between(original: List<SprayHold>, edited: List<EditableHold>): SprayHoldDiff {
            val before = original.filter { it.isOnWall }.associateBy { it.id }
            val keptIds = edited.filter { it.id >= 0 }.map { it.id }.toSet()
            val changed = edited.filter { e ->
                val o = before[e.id] ?: return@filter false
                o.x != e.x || o.y != e.y || o.radius != e.radius
            }
            return SprayHoldDiff(
                added = edited.filter { it.id < 0 || it.id !in before },
                changed = changed,
                removedIds = before.keys - keptIds,
            )
        }
    }
}

/** Where the photo sits inside a view, and the maths between screen points and photo coordinates. */
object SprayWallGeometry {
    /** Placement of a photo fitted (letterboxed) into a container, in container pixels. */
    data class Fit(val left: Float, val top: Float, val width: Float, val height: Float)

    fun fit(containerWidth: Float, containerHeight: Float, photoWidth: Int, photoHeight: Int): Fit {
        if (containerWidth <= 0f || containerHeight <= 0f || photoWidth <= 0 || photoHeight <= 0) {
            return Fit(0f, 0f, max(containerWidth, 0f), max(containerHeight, 0f))
        }
        val scale = min(containerWidth / photoWidth, containerHeight / photoHeight)
        val w = photoWidth * scale
        val h = photoHeight * scale
        return Fit((containerWidth - w) / 2f, (containerHeight - h) / 2f, w, h)
    }

    /**
     * A point on screen to photo coordinates (0..1), for a view zoomed by
     * [zoom] around the top-left corner and then shifted by ([panX], [panY]).
     * Null when the point is off the photo.
     */
    fun toPhoto(sx: Float, sy: Float, fit: Fit, zoom: Float, panX: Float, panY: Float): Pair<Float, Float>? {
        val cx = (sx - panX) / zoom
        val cy = (sy - panY) / zoom
        val nx = (cx - fit.left) / fit.width
        val ny = (cy - fit.top) / fit.height
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return nx to ny
    }

    /** Like [toPhoto] but pinned to the photo's edge, for a drag that leaves the photo. */
    fun toPhotoClamped(sx: Float, sy: Float, fit: Fit, zoom: Float, panX: Float, panY: Float): Pair<Float, Float> {
        val cx = (sx - panX) / zoom
        val cy = (sy - panY) / zoom
        return ((cx - fit.left) / fit.width).coerceIn(0f, 1f) to ((cy - fit.top) / fit.height).coerceIn(0f, 1f)
    }

    /**
     * The hold under a touch at photo coordinates (nx, ny): the one whose
     * centre is nearest, among holds whose circle (plus [slopPx] of fitted
     * view pixels at zoom 1) contains the touch. The slop shrinks as the view
     * zooms in so close holds stay separable.
     */
    fun <T> holdAt(
        items: List<T>,
        nx: Float,
        ny: Float,
        fit: Fit,
        zoom: Float,
        slopPx: Float,
        x: (T) -> Float,
        y: (T) -> Float,
        radius: (T) -> Float,
    ): T? {
        var best: T? = null
        var bestDistance = Float.MAX_VALUE
        val slop = slopPx / zoom
        for (item in items) {
            val dx = (x(item) - nx) * fit.width
            val dy = (y(item) - ny) * fit.height
            val d = sqrt(dx * dx + dy * dy)
            val reach = radius(item) * fit.width + slop
            if (d <= reach && d < bestDistance) {
                best = item
                bestDistance = d
            }
        }
        return best
    }

    /** Keeps a zoomed view from sliding the photo out of sight. */
    fun clampPan(panX: Float, panY: Float, zoom: Float, containerWidth: Float, containerHeight: Float): Pair<Float, Float> {
        val minX = containerWidth - containerWidth * zoom
        val minY = containerHeight - containerHeight * zoom
        return panX.coerceIn(min(minX, 0f), 0f) to panY.coerceIn(min(minY, 0f), 0f)
    }

    /** Pixel size of an imported photo, shrunk so the long side is at most [maxLongSide]. */
    fun importSize(width: Int, height: Int, maxLongSide: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val long = max(width, height)
        if (long <= maxLongSide) return width to height
        val scale = maxLongSide.toDouble() / long
        return max(1, (width * scale + 0.5).toInt()) to max(1, (height * scale + 0.5).toInt())
    }
}
