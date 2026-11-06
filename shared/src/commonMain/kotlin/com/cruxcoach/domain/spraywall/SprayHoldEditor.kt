package com.cruxcoach.domain.spraywall

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A hold while it is being edited. Negative ids are holds added in this session. */
data class EditableHold(val id: Long, val x: Float, val y: Float, val radius: Float)

/** A hold the detector proposes; the climber accepts or ignores it. Same coordinates as a hold. */
data class SpraySuggestion(val x: Float, val y: Float, val radius: Float, val confidence: Float)

/**
 * Pure state of the hold editor: the holds, the picked one, the detector's
 * open suggestions, and undo/redo. Every user action is one undo step; a drag
 * is one step from its start to its end (see [beginMove] / [moveSelected]).
 */
data class SprayHoldEditorState(
    val holds: List<EditableHold>,
    val selectedId: Long? = null,
    val lastRadius: Float = DEFAULT_RADIUS,
    val nextTempId: Long = -1,
    /** Detector suggestions not accepted yet, best first. */
    val suggestions: List<SpraySuggestion> = emptyList(),
    /** Suggestions below this confidence are hidden. */
    val threshold: Float = DEFAULT_THRESHOLD,
    val undoStack: List<Snapshot> = emptyList(),
    val redoStack: List<Snapshot> = emptyList(),
) {
    data class Snapshot(val holds: List<EditableHold>, val suggestions: List<SpraySuggestion>)

    val selected: EditableHold? get() = holds.firstOrNull { it.id == selectedId }
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val canAdd: Boolean get() = holds.size < MAX_HOLDS
    val visibleSuggestions: List<SpraySuggestion> get() = suggestions.filter { it.confidence >= threshold }

    companion object {
        const val MAX_HOLDS = 1500
        const val MIN_RADIUS = 0.006f
        const val MAX_RADIUS = 0.12f
        const val DEFAULT_RADIUS = 0.022f
        const val DEFAULT_THRESHOLD = 0.5f
        private const val MAX_UNDO = 100

        /** Starts from the holds of one version. */
        fun from(holds: List<SprayHold>): SprayHoldEditorState {
            val editable = holds.map { EditableHold(it.id, it.x, it.y, it.radius) }
            return SprayHoldEditorState(holds = editable, lastRadius = typicalRadius(editable.map { it.radius }))
        }

        private fun typicalRadius(radii: List<Float>): Float =
            radii.sorted().let { if (it.isEmpty()) DEFAULT_RADIUS else it[it.size / 2] }

        /** A suggestion counts as already marked when its centre lies inside a hold (or the hold's centre inside it). */
        fun overlaps(s: SpraySuggestion, h: EditableHold, aspect: Float): Boolean {
            val dx = s.x - h.x
            val dy = (s.y - h.y) / aspect
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            return d < maxOf(s.radius, h.radius)
        }
    }

    private fun snapshot() = Snapshot(holds, suggestions)

    private fun pushUndo(): SprayHoldEditorState =
        copy(undoStack = (undoStack + snapshot()).takeLast(MAX_UNDO), redoStack = emptyList())

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

    /**
     * New detector output. Suggestions on top of holds already marked are
     * dropped, so re-running the detector on a half-marked wall only offers
     * what is still missing. [aspect] = photo width / height.
     */
    fun withSuggestions(found: List<SpraySuggestion>, aspect: Float): SprayHoldEditorState {
        val open = found.filter { s -> holds.none { h -> overlaps(s, h, aspect) } }.sortedByDescending { it.confidence }
        return copy(suggestions = open)
    }

    fun setThreshold(value: Float): SprayHoldEditorState = copy(threshold = value.coerceIn(0f, 1f))

    /** Accepts one suggestion as a hold and picks it. */
    fun accept(suggestion: SpraySuggestion): SprayHoldEditorState {
        if (!canAdd || suggestion !in suggestions) return this
        val hold = EditableHold(nextTempId, suggestion.x, suggestion.y, suggestion.radius.coerceIn(MIN_RADIUS, MAX_RADIUS))
        return pushUndo().copy(
            holds = holds + hold,
            suggestions = suggestions - suggestion,
            selectedId = hold.id,
            nextTempId = nextTempId - 1,
        )
    }

    /** Accepts every visible suggestion (up to the hold limit) as one undo step. */
    fun acceptVisible(): SprayHoldEditorState {
        val room = MAX_HOLDS - holds.size
        val taken = visibleSuggestions.take(room.coerceAtLeast(0))
        if (taken.isEmpty()) return this
        var id = nextTempId
        val added = taken.map { EditableHold(id--, it.x, it.y, it.radius.coerceIn(MIN_RADIUS, MAX_RADIUS)) }
        val radii = (holds + added).map { it.radius }
        return pushUndo().copy(
            holds = holds + added,
            suggestions = suggestions - taken.toSet(),
            nextTempId = id,
            selectedId = null,
            lastRadius = typicalRadius(radii),
        )
    }

    fun dismissSuggestions(): SprayHoldEditorState = if (suggestions.isEmpty()) this else pushUndo().copy(suggestions = emptyList())

    fun undo(): SprayHoldEditorState {
        val previous = undoStack.lastOrNull() ?: return this
        return copy(
            holds = previous.holds,
            suggestions = previous.suggestions,
            undoStack = undoStack.dropLast(1),
            redoStack = redoStack + snapshot(),
            selectedId = selectedId?.takeIf { id -> previous.holds.any { it.id == id } },
        )
    }

    fun redo(): SprayHoldEditorState {
        val next = redoStack.lastOrNull() ?: return this
        return copy(
            holds = next.holds,
            suggestions = next.suggestions,
            redoStack = redoStack.dropLast(1),
            undoStack = undoStack + snapshot(),
            selectedId = selectedId?.takeIf { id -> next.holds.any { it.id == id } },
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
            val before = original.associateBy { it.id }
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
