package com.cruxcoach.android.ui.training.bodymap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.athlete.catalog.BodyRegion
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.LoadDomain
import com.cruxcoach.athlete.model.InjuryRegion
import com.cruxcoach.athlete.model.Side
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Areas the body map can show; the visual language of exercises and injuries. */
enum class BodyArea {
    FINGERS, FOREARM_FLEXORS, FOREARM_EXTENSORS, WRIST, ELBOW, BICEPS, TRICEPS, SHOULDER_FRONT, SHOULDER_REAR,
    ROTATOR_CUFF, CHEST, UPPER_BACK, LATS, ABS, OBLIQUES, LOWER_BACK, HIPS, GLUTES, QUADS, HAMSTRINGS, ADDUCTORS,
    CALVES, NECK, KNEE, ANKLE,
}

enum class BodyMapSide { FRONT, BACK, BOTH }

/**
 * Front and back of a stylised, gender-neutral figure with every [BodyArea]
 * filled by its intensity: 1 = trained mainly (accent), 0.5 = involved
 * (lighter tint), 0 = not involved. [onAreaClick] makes the regions
 * tappable. Our own drawing — no third-party anatomy artwork.
 */
@Composable
fun BodyMap(
    highlights: Map<BodyArea, Float>,
    modifier: Modifier = Modifier,
    side: BodyMapSide = BodyMapSide.BOTH,
    onAreaClick: ((BodyArea) -> Unit)? = null,
    contentDescription: String? = null,
) {
    val description = contentDescription ?: bodyMapDescription(highlights)
    // The caller sizes the map (e.g. a height); "Vorne"/"Hinten" are drawn inside it.
    BodyMapCanvas(
        highlights = highlights,
        side = side,
        onTap = onAreaClick?.let { click -> { area: BodyArea, _: Side? -> click(area) } },
        showLabels = side == BodyMapSide.BOTH,
        modifier = modifier.semantics { this.contentDescription = description },
    )
}

/**
 * Tap the body to choose an area (injuries, pain). [onSide] reports which
 * side of the body was tapped for paired areas (null on the midline), so a
 * form can preselect left or right.
 */
@Composable
fun BodyMapPicker(
    selected: BodyArea?,
    onSelect: (BodyArea) -> Unit,
    modifier: Modifier = Modifier,
    onSide: ((Side?) -> Unit)? = null,
) {
    val highlights = selected?.let { mapOf(it to 1f) } ?: emptyMap()
    val hint = stringResource(R.string.trb_tap_hint)
    val label = selected?.let { bodyAreaLabel(it) }
    Column(modifier.semantics { contentDescription = label ?: hint }) {
        BodyMapCanvas(
            highlights = highlights,
            side = BodyMapSide.BOTH,
            onTap = { area, tappedSide -> onSelect(area); onSide?.invoke(tappedSide) },
            showLabels = true,
            modifier = Modifier.fillMaxWidth().height(230.dp).testTag("body_map_picker"),
        )
        Text(
            label ?: hint,
            style = MaterialTheme.typography.bodyMedium,
            color = if (label == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("body_map_selected"),
        )
    }
}

/** "Hauptsächlich: Lat, Bizeps. Mit beteiligt: Ellbogen." — the map for screen readers. */
@Composable
internal fun bodyMapDescription(highlights: Map<BodyArea, Float>): String {
    val main = highlights.filterValues { it >= ExerciseBodyAreas.PRIMARY }.keys.sortedBy { it.ordinal }.map { bodyAreaLabel(it) }
    val involved = highlights.filterValues { it > 0f && it < ExerciseBodyAreas.PRIMARY }.keys.sortedBy { it.ordinal }.map { bodyAreaLabel(it) }
    val parts = buildList {
        if (main.isNotEmpty()) add(stringResource(R.string.trb_cd_main, main.joinToString(", ")))
        if (involved.isNotEmpty()) add(stringResource(R.string.trb_cd_involved, involved.joinToString(", ")))
    }
    return stringResource(R.string.trb_cd_title) + if (parts.isEmpty()) "" else ": " + parts.joinToString(" ")
}

/**
 * The drawing itself. [focus] zooms onto the highlighted areas of the shown
 * side — used by the small exercise thumbnails, where a whole figure would
 * be a matchstick.
 */
@Composable
internal fun BodyMapCanvas(
    highlights: Map<BodyArea, Float>,
    side: BodyMapSide,
    modifier: Modifier = Modifier,
    focus: Boolean = false,
    onTap: ((BodyArea, Side?) -> Unit)? = null,
    showLabels: Boolean = false,
) {
    val figures = remember { BodyMapGeometry.figures.mapValues { (_, f) -> f.toPaths() } }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val sideLabels = mapOf(BodyMapSide.FRONT to stringResource(R.string.trb_front), BodyMapSide.BACK to stringResource(R.string.trb_back))
    val base = MaterialTheme.colorScheme.surfaceVariant
    val idle = lerp(base, MaterialTheme.colorScheme.onSurfaceVariant, 0.12f)
    val accent = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val shown = if (side == BodyMapSide.BOTH) listOf(BodyMapSide.FRONT, BodyMapSide.BACK) else listOf(side)
    val tapModifier = if (onTap == null) Modifier else Modifier.pointerInput(side, focus, highlights, showLabels) {
        detectTapGestures { pos ->
            val layout = BodyMapGeometry.layout(size.width.toFloat(), size.height.toFloat(), shown, if (focus) highlights else null, showLabels)
            BodyMapGeometry.hit(layout, pos.x, pos.y)?.let { (area, tappedSide) -> onTap(area, tappedSide) }
        }
    }
    Canvas(modifier.then(tapModifier)) {
        val layout = BodyMapGeometry.layout(size.width, size.height, shown, if (focus) highlights else null, showLabels)
        layout.placements.forEach { placement ->
            if (showLabels) {
                val text = measurer.measure(sideLabels.getValue(placement.side), labelStyle)
                val cx = placement.dx + BodyMapGeometry.FIGURE_W * layout.scale / 2f
                val y = placement.dy + BodyMapGeometry.FIGURE_H * layout.scale + 2f
                drawText(text, topLeft = Offset(cx - text.size.width / 2f, y))
            }
            val paths = figures.getValue(placement.side)
            withTransform({
                translate(placement.dx, placement.dy)
                scale(layout.scale, layout.scale, pivot = Offset.Zero)
            }) {
                paths.base.forEach { drawPath(it, base) }
                paths.regions.forEach { (area, path) ->
                    val v = (highlights[area] ?: 0f).coerceIn(0f, 1f)
                    drawPath(path, if (v <= 0f) idle else lerp(idle, accent, areaMix(v)))
                    drawPath(path, outline, style = Stroke(width = 0.6f))
                }
            }
        }
    }
}

// ── Geometry (plain math, unit-testable without Android) ──────────────

/** Primitive shapes in figure units (one figure is 100 × 215). */
internal sealed interface BodyShape {
    fun contains(x: Float, y: Float): Boolean
    /** left, top, right, bottom */
    fun bounds(): FloatArray
    fun mirrored(): BodyShape
}

internal data class Oval(val cx: Float, val cy: Float, val rx: Float, val ry: Float) : BodyShape {
    override fun contains(x: Float, y: Float): Boolean {
        val nx = (x - cx) / rx
        val ny = (y - cy) / ry
        return nx * nx + ny * ny <= 1f
    }
    override fun bounds() = floatArrayOf(cx - rx, cy - ry, cx + rx, cy + ry)
    override fun mirrored() = copy(cx = 100f - cx)
}

/** A limb segment: a line with round ends of the given width. */
internal data class Capsule(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val width: Float) : BodyShape {
    override fun contains(x: Float, y: Float): Boolean {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((x - x1) * dx + (y - y1) * dy) / len2).coerceIn(0f, 1f)
        val px = x1 + t * dx - x
        val py = y1 + t * dy - y
        return sqrt(px * px + py * py) <= width / 2f
    }
    override fun bounds(): FloatArray {
        val r = width / 2f
        return floatArrayOf(min(x1, x2) - r, min(y1, y2) - r, max(x1, x2) + r, max(y1, y2) + r)
    }
    override fun mirrored() = copy(x1 = 100f - x1, x2 = 100f - x2)
}

/** A closed outline through the points, drawn with smoothed corners. */
internal data class Outline(val points: List<Pair<Float, Float>>) : BodyShape {
    override fun contains(x: Float, y: Float): Boolean {
        var inside = false
        var j = points.lastIndex
        for (i in points.indices) {
            val (xi, yi) = points[i]
            val (xj, yj) = points[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }
    override fun bounds() = floatArrayOf(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
    override fun mirrored() = Outline(points.map { (x, y) -> 100f - x to y }.reversed())
}

/** One region part; [viewerLeft] is null on the midline. */
internal data class RegionShape(val area: BodyArea, val shape: BodyShape, val viewerLeft: Boolean?)

internal data class Figure(val base: List<BodyShape>, val regions: List<RegionShape>)

internal data class FigurePaths(val base: List<Path>, val regions: List<Pair<BodyArea, Path>>)

internal data class Placement(val side: BodyMapSide, val dx: Float, val dy: Float, val unitsLeft: Float, val unitsTop: Float)

internal data class MapLayout(val scale: Float, val placements: List<Placement>)

internal object BodyMapGeometry {

    const val FIGURE_W = 100f
    const val FIGURE_H = 215f
    private const val GAP = 10f

    private fun o(cx: Number, cy: Number, rx: Number, ry: Number) = Oval(cx.toFloat(), cy.toFloat(), rx.toFloat(), ry.toFloat())
    private fun c(x1: Number, y1: Number, x2: Number, y2: Number, w: Number) =
        Capsule(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), w.toFloat())
    private fun p(vararg xy: Number) = Outline(xy.toList().chunked(2) { (x, y) -> x.toFloat() to y.toFloat() })

    /** Left-of-viewer parts are listed once and mirrored; midline parts stay single. */
    private fun pair(area: BodyArea, shape: BodyShape) =
        listOf(RegionShape(area, shape, viewerLeft = true), RegionShape(area, shape.mirrored(), viewerLeft = false))
    private fun mid(area: BodyArea, shape: BodyShape) = listOf(RegionShape(area, shape, viewerLeft = null))
    private fun both(shape: BodyShape) = listOf(shape, shape.mirrored())

    private val torso = p(
        38, 35, 30, 38, 25, 43, 24, 50, 28, 58, 31, 70, 33, 82, 34, 92, 33, 100, 35, 108, 42, 114, 50, 116,
        58, 114, 65, 108, 67, 100, 66, 92, 67, 82, 69, 70, 72, 58, 76, 50, 75, 43, 70, 38, 62, 35,
    )

    private val baseParts: List<BodyShape> = listOf(o(50, 17, 10.5, 12.5), c(50, 26, 50, 37, 9.5), torso) +
        both(c(25.5, 47, 21, 76, 9.5)) +   // upper arms
        both(c(21, 80, 17.5, 106, 8)) +    // forearms
        both(o(16.5, 116, 5, 7.5)) +       // hands
        both(c(42, 108, 41.5, 150, 14.5)) + // thighs
        both(c(41.5, 156, 42.5, 196, 10)) + // lower legs
        both(o(43, 204, 6.5, 3.5))          // feet

    private val front = Figure(
        base = baseParts,
        regions = mid(BodyArea.NECK, c(50, 29, 50, 36, 7)) +
            pair(BodyArea.SHOULDER_FRONT, o(29, 45, 6.5, 6)) +
            pair(BodyArea.CHEST, p(38, 42, 48, 42, 49, 55, 43, 58, 35, 55, 33, 48)) +
            pair(BodyArea.OBLIQUES, p(35, 62, 42, 60, 42, 92, 36, 94, 34, 80)) +
            mid(BodyArea.ABS, p(44, 60, 56, 60, 57, 72, 57, 88, 55, 96, 45, 96, 43, 88, 43, 72)) +
            pair(BodyArea.BICEPS, c(25.5, 51, 22.2, 72, 7)) +
            pair(BodyArea.ELBOW, o(21.2, 78, 3.6, 3.6)) +
            pair(BodyArea.FOREARM_FLEXORS, c(20.6, 83, 18, 102, 6.5)) +
            pair(BodyArea.WRIST, o(17.6, 108.5, 3.4, 2.4)) +
            pair(BodyArea.FINGERS, o(16.5, 117, 4.4, 6.5)) +
            pair(BodyArea.HIPS, o(41, 104, 6, 5)) +
            pair(BodyArea.QUADS, c(42.5, 114, 42, 145, 11)) +
            pair(BodyArea.ADDUCTORS, c(47, 113, 46.2, 134, 4.5)) +
            pair(BodyArea.KNEE, o(41.8, 152, 4.8, 4.4)) +
            pair(BodyArea.ANKLE, o(42.6, 199, 3.6, 2.6)),
    )

    private val back = Figure(
        base = baseParts,
        regions = mid(BodyArea.NECK, c(50, 29, 50, 36, 7)) +
            mid(BodyArea.UPPER_BACK, p(44, 34, 56, 34, 66, 42, 62, 56, 56, 64, 44, 64, 38, 56, 34, 42)) +
            pair(BodyArea.SHOULDER_REAR, o(29, 45, 6.5, 6)) +
            pair(BodyArea.ROTATOR_CUFF, o(40, 50, 5.5, 5)) +
            pair(BodyArea.LATS, p(33, 53, 39, 58, 45, 66, 46, 84, 40, 88, 35, 78, 32, 64)) +
            mid(BodyArea.LOWER_BACK, p(44, 82, 56, 82, 57, 96, 50, 99, 43, 96)) +
            pair(BodyArea.TRICEPS, c(25.5, 51, 22.2, 72, 7)) +
            pair(BodyArea.ELBOW, o(21.2, 78, 3.6, 3.6)) +
            pair(BodyArea.FOREARM_EXTENSORS, c(20.6, 83, 18, 102, 6.5)) +
            pair(BodyArea.WRIST, o(17.6, 108.5, 3.4, 2.4)) +
            pair(BodyArea.FINGERS, o(16.5, 117, 4.4, 6.5)) +
            pair(BodyArea.GLUTES, o(43.5, 106, 8, 7.5)) +
            pair(BodyArea.HAMSTRINGS, c(42.3, 118, 42, 146, 10.5)) +
            pair(BodyArea.KNEE, o(41.8, 152, 4.6, 4)) +
            pair(BodyArea.CALVES, c(42, 160, 42.4, 184, 9)) +
            pair(BodyArea.ANKLE, o(42.6, 199, 3.6, 2.6)),
    )

    val figures: Map<BodyMapSide, Figure> = mapOf(BodyMapSide.FRONT to front, BodyMapSide.BACK to back)

    /** Areas that can be seen on a side (the hands show on both). */
    fun areasOn(side: BodyMapSide): Set<BodyArea> = figures.getValue(side).regions.map { it.area }.toSet()

    /** Where the figures go in a canvas of [width] × [height]; [focus] zooms onto those areas. */
    /** Room under the figures for the side labels, in figure units. */
    const val LABEL_H = 16f

    fun layout(width: Float, height: Float, sides: List<BodyMapSide>, focus: Map<BodyArea, Float>?, labels: Boolean = false): MapLayout {
        if (focus != null && sides.size == 1) {
            val box = focusBox(sides.first(), focus)
            val scale = min(width / (box[2] - box[0]), height / (box[3] - box[1]))
            val cx = (box[0] + box[2]) / 2f
            val cy = (box[1] + box[3]) / 2f
            return MapLayout(scale, listOf(Placement(sides.first(), width / 2f - cx * scale, height / 2f - cy * scale, 0f, 0f)))
        }
        val totalW = FIGURE_W * sides.size + GAP * (sides.size - 1)
        val totalH = FIGURE_H + if (labels) LABEL_H else 0f
        val scale = min(width / totalW, height / totalH)
        val left = (width - totalW * scale) / 2f
        val top = (height - totalH * scale) / 2f
        return MapLayout(scale, sides.mapIndexed { i, s ->
            val ux = i * (FIGURE_W + GAP)
            Placement(s, left + ux * scale, top, ux, 0f)
        })
    }

    private fun focusBox(side: BodyMapSide, focus: Map<BodyArea, Float>): FloatArray {
        val shapes = figures.getValue(side).regions.filter { (focus[it.area] ?: 0f) > 0f }.map { it.shape.bounds() }
        val raw = if (shapes.isEmpty()) floatArrayOf(0f, 0f, FIGURE_W, FIGURE_H) else floatArrayOf(
            shapes.minOf { it[0] }, shapes.minOf { it[1] }, shapes.maxOf { it[2] }, shapes.maxOf { it[3] },
        )
        val pad = 10f
        var l = raw[0] - pad; var t = raw[1] - pad; var r = raw[2] + pad; var b = raw[3] + pad
        // Square-ish, so a hand and a whole leg both read in the same thumbnail box.
        val w = r - l
        val h = b - t
        if (w < h) { val grow = (h - w) / 2f; l -= grow; r += grow } else { val grow = (w - h) / 2f; t -= grow; b += grow }
        return floatArrayOf(l, t, r, b)
    }

    /** The area under a canvas point and the body side it belongs to. */
    fun hit(layout: MapLayout, x: Float, y: Float): Pair<BodyArea, Side?>? {
        layout.placements.forEach { p ->
            val ux = (x - p.dx) / layout.scale
            val uy = (y - p.dy) / layout.scale
            regionAt(p.side, ux, uy)?.let { return it }
        }
        return null
    }

    /** Topmost region at figure units on [side]; front: viewer's left is the athlete's right, back: the left. */
    fun regionAt(side: BodyMapSide, ux: Float, uy: Float): Pair<BodyArea, Side?>? {
        val region = figures.getValue(side).regions.lastOrNull { it.shape.contains(ux, uy) } ?: return null
        val bodySide = region.viewerLeft?.let { left ->
            if (side == BodyMapSide.FRONT) (if (left) Side.RIGHT else Side.LEFT) else (if (left) Side.LEFT else Side.RIGHT)
        }
        return region.area to bodySide
    }
}

internal fun Figure.toPaths() = FigurePaths(base.map { it.toPath() }, regions.map { it.area to it.shape.toPath() })

internal fun BodyShape.toPath(): Path = Path().also { path ->
    when (this) {
        is Oval -> path.addOval(Rect(cx - rx, cy - ry, cx + rx, cy + ry))
        is Capsule -> {
            val r = width / 2f
            val angle = Math.toDegrees(atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()
            // Cap around the start, then around the end, joined by the straight sides.
            path.arcTo(Rect(Offset(x1, y1), r), angle + 90f, 180f, forceMoveTo = true)
            path.arcTo(Rect(Offset(x2, y2), r), angle - 90f, 180f, forceMoveTo = false)
            path.close()
        }
        is Outline -> {
            // Smoothed polygon: curve through the midpoints, the points act as control points.
            val pts = points
            fun midpoint(i: Int): Pair<Float, Float> {
                val a = pts[i % pts.size]
                val b = pts[(i + 1) % pts.size]
                return (a.first + b.first) / 2f to (a.second + b.second) / 2f
            }
            val start = midpoint(0)
            path.moveTo(start.first, start.second)
            for (i in 1..pts.size) {
                val ctrl = pts[i % pts.size]
                val end = midpoint(i)
                path.quadraticTo(ctrl.first, ctrl.second, end.first, end.second)
            }
            path.close()
        }
    }
}

// ── Exercise → areas ─────────────────────────────────────────────────

/**
 * Which areas an exercise trains: the catalogue's primary/secondary muscles
 * first, joints it loads as "involved", and a slug/category fallback for
 * custom exercises without muscle data.
 */
object ExerciseBodyAreas {

    const val PRIMARY = 1f
    const val SECONDARY = 0.5f

    private val MUSCLES: Map<String, List<BodyArea>> = mapOf(
        "finger_flexors" to listOf(BodyArea.FINGERS, BodyArea.FOREARM_FLEXORS),
        "forearm_flexors" to listOf(BodyArea.FOREARM_FLEXORS),
        "forearm_extensors" to listOf(BodyArea.FOREARM_EXTENSORS),
        "forearms" to listOf(BodyArea.FOREARM_FLEXORS, BodyArea.FOREARM_EXTENSORS),
        "wrist_stabilizers" to listOf(BodyArea.WRIST),
        "biceps" to listOf(BodyArea.BICEPS),
        "brachialis" to listOf(BodyArea.BICEPS),
        "triceps" to listOf(BodyArea.TRICEPS),
        "front_delts" to listOf(BodyArea.SHOULDER_FRONT),
        "side_delts" to listOf(BodyArea.SHOULDER_FRONT, BodyArea.SHOULDER_REAR),
        "rear_delts" to listOf(BodyArea.SHOULDER_REAR),
        "shoulders" to listOf(BodyArea.SHOULDER_FRONT, BodyArea.SHOULDER_REAR),
        "rotator_cuff" to listOf(BodyArea.ROTATOR_CUFF),
        "chest" to listOf(BodyArea.CHEST),
        // The serratus sits on the side of the chest, under the armpit.
        "serratus" to listOf(BodyArea.CHEST),
        "upper_back" to listOf(BodyArea.UPPER_BACK),
        "lower_traps" to listOf(BodyArea.UPPER_BACK),
        "traps" to listOf(BodyArea.UPPER_BACK),
        "lats" to listOf(BodyArea.LATS),
        "abs" to listOf(BodyArea.ABS),
        "core" to listOf(BodyArea.ABS, BodyArea.OBLIQUES),
        "obliques" to listOf(BodyArea.OBLIQUES),
        "lower_back" to listOf(BodyArea.LOWER_BACK),
        "hip_flexors" to listOf(BodyArea.HIPS),
        "glutes" to listOf(BodyArea.GLUTES),
        "quads" to listOf(BodyArea.QUADS),
        "hamstrings" to listOf(BodyArea.HAMSTRINGS),
        "adductors" to listOf(BodyArea.ADDUCTORS),
        "calves" to listOf(BodyArea.CALVES),
        "neck" to listOf(BodyArea.NECK),
    )

    /** True if the catalogue muscle name has a place on the body. */
    internal fun knows(muscle: String): Boolean = muscle.lowercase() in MUSCLES

    fun of(def: ExerciseDefinition): Map<BodyArea, Float> {
        val out = mutableMapOf<BodyArea, Float>()
        fun put(area: BodyArea, value: Float) { out[area] = max(out[area] ?: 0f, value) }
        def.muscles.primary.forEach { m -> MUSCLES[m.lowercase()]?.forEach { put(it, PRIMARY) } }
        def.muscles.secondary.forEach { m -> MUSCLES[m.lowercase()]?.forEach { put(it, SECONDARY) } }
        if (out.values.none { it >= PRIMARY }) fallback(def).forEach { put(it, PRIMARY) }
        // Joints carry load without a muscle sitting there: shown as involved.
        def.domains.forEach { d ->
            when (d) {
                LoadDomain.FINGER, LoadDomain.SKIN -> put(BodyArea.FINGERS, SECONDARY)
                LoadDomain.WRIST -> put(BodyArea.WRIST, SECONDARY)
                LoadDomain.ELBOW -> put(BodyArea.ELBOW, SECONDARY)
                LoadDomain.BACK -> put(BodyArea.LOWER_BACK, SECONDARY)
                LoadDomain.SHOULDER, LoadDomain.LOWER_BODY, LoadDomain.SYSTEMIC -> Unit
            }
        }
        def.contraindications.forEach { r ->
            when (r) {
                BodyRegion.KNEE -> put(BodyArea.KNEE, SECONDARY)
                BodyRegion.ANKLE -> put(BodyArea.ANKLE, SECONDARY)
                BodyRegion.HIP -> put(BodyArea.HIPS, SECONDARY)
                else -> Unit
            }
        }
        return out
    }

    /** For exercises without (known) muscle data: slug keywords first, then the category. */
    internal fun fallback(def: ExerciseDefinition): List<BodyArea> {
        val s = def.slug.lowercase()
        val byKeyword = KEYWORDS.firstOrNull { (keys, _) -> keys.any { it in s } }?.second
        return byKeyword ?: when (def.category) {
            ExerciseCategoryV2.FINGER, ExerciseCategoryV2.ENDURANCE -> listOf(BodyArea.FINGERS, BodyArea.FOREARM_FLEXORS)
            ExerciseCategoryV2.PULL -> listOf(BodyArea.LATS, BodyArea.BICEPS)
            ExerciseCategoryV2.PUSH -> listOf(BodyArea.CHEST, BodyArea.TRICEPS, BodyArea.SHOULDER_FRONT)
            ExerciseCategoryV2.ANTAGONIST -> listOf(BodyArea.ROTATOR_CUFF, BodyArea.SHOULDER_REAR, BodyArea.FOREARM_EXTENSORS)
            ExerciseCategoryV2.CORE -> listOf(BodyArea.ABS, BodyArea.OBLIQUES)
            ExerciseCategoryV2.LEGS -> listOf(BodyArea.QUADS, BodyArea.GLUTES, BodyArea.HAMSTRINGS)
            ExerciseCategoryV2.MOBILITY -> listOf(BodyArea.HIPS, BodyArea.HAMSTRINGS)
            ExerciseCategoryV2.WARMUP -> listOf(BodyArea.SHOULDER_FRONT, BodyArea.SHOULDER_REAR, BodyArea.FOREARM_FLEXORS)
            ExerciseCategoryV2.POWER -> listOf(BodyArea.LATS, BodyArea.FINGERS, BodyArea.ABS)
            ExerciseCategoryV2.TECHNIQUE -> listOf(BodyArea.FINGERS, BodyArea.HIPS)
        }
    }

    private val KEYWORDS: List<Pair<List<String>, List<BodyArea>>> = listOf(
        listOf("finger_roll", "wrist_curl") to listOf(BodyArea.FOREARM_FLEXORS, BodyArea.WRIST),
        listOf("wrist") to listOf(BodyArea.WRIST, BodyArea.FOREARM_EXTENSORS),
        listOf("hang", "pickup", "pick_up", "campus", "crimp", "edge", "pinch") to listOf(BodyArea.FINGERS, BodyArea.FOREARM_FLEXORS),
        listOf("external_rotation", "face_pull", "ytw", "y_raise") to listOf(BodyArea.ROTATOR_CUFF, BodyArea.SHOULDER_REAR),
        listOf("front_lever") to listOf(BodyArea.LATS, BodyArea.ABS),
        listOf("lock_off", "pull_up", "pullup", "chin") to listOf(BodyArea.LATS, BodyArea.BICEPS),
        listOf("row") to listOf(BodyArea.UPPER_BACK, BodyArea.LATS),
        listOf("push_up", "pushup", "dip", "bench", "press") to listOf(BodyArea.CHEST, BodyArea.TRICEPS),
        listOf("deadlift", "hinge", "good_morning") to listOf(BodyArea.HAMSTRINGS, BodyArea.GLUTES, BodyArea.LOWER_BACK),
        listOf("squat", "lunge", "step_up", "pistol") to listOf(BodyArea.QUADS, BodyArea.GLUTES),
        listOf("calf") to listOf(BodyArea.CALVES),
        listOf("l_sit", "hollow", "plank", "crunch", "leg_raise", "dead_bug") to listOf(BodyArea.ABS),
        listOf("side_plank", "oblique", "rotation") to listOf(BodyArea.OBLIQUES),
        listOf("bridge", "hip_thrust") to listOf(BodyArea.GLUTES),
        listOf("hip", "frog", "pigeon") to listOf(BodyArea.HIPS),
    )
}

// ── Injuries ↔ areas ─────────────────────────────────────────────────

/** The injury region a tap on the body stands for; forearms count as elbow (where the tendons start). */
fun BodyArea.toInjuryRegion(): InjuryRegion? = when (this) {
    BodyArea.FINGERS -> InjuryRegion.FINGER
    BodyArea.WRIST -> InjuryRegion.WRIST
    BodyArea.FOREARM_FLEXORS, BodyArea.FOREARM_EXTENSORS, BodyArea.ELBOW, BodyArea.BICEPS, BodyArea.TRICEPS -> InjuryRegion.ELBOW
    BodyArea.SHOULDER_FRONT, BodyArea.SHOULDER_REAR, BodyArea.ROTATOR_CUFF -> InjuryRegion.SHOULDER
    BodyArea.UPPER_BACK, BodyArea.LATS, BodyArea.LOWER_BACK -> InjuryRegion.BACK
    BodyArea.HIPS, BodyArea.GLUTES, BodyArea.ADDUCTORS -> InjuryRegion.HIP
    // Leg load is what the injury filter cares about; hamstring tendons (heel hooks) sit at the knee.
    BodyArea.QUADS, BodyArea.HAMSTRINGS, BodyArea.KNEE -> InjuryRegion.KNEE
    BodyArea.CALVES, BodyArea.ANKLE -> InjuryRegion.ANKLE
    BodyArea.CHEST, BodyArea.ABS, BodyArea.OBLIQUES, BodyArea.NECK -> InjuryRegion.OTHER
}

fun InjuryRegion.bodyAreas(): Set<BodyArea> = when (this) {
    InjuryRegion.FINGER -> setOf(BodyArea.FINGERS, BodyArea.FOREARM_FLEXORS)
    InjuryRegion.WRIST -> setOf(BodyArea.WRIST)
    InjuryRegion.ELBOW -> setOf(BodyArea.ELBOW)
    InjuryRegion.SHOULDER -> setOf(BodyArea.SHOULDER_FRONT, BodyArea.SHOULDER_REAR, BodyArea.ROTATOR_CUFF)
    InjuryRegion.BACK -> setOf(BodyArea.UPPER_BACK, BodyArea.LOWER_BACK)
    InjuryRegion.HIP -> setOf(BodyArea.HIPS, BodyArea.GLUTES)
    InjuryRegion.KNEE -> setOf(BodyArea.KNEE)
    InjuryRegion.ANKLE -> setOf(BodyArea.ANKLE)
    InjuryRegion.SKIN -> setOf(BodyArea.FINGERS)
    InjuryRegion.OTHER -> emptySet()
}

@Composable
fun bodyAreaLabel(area: BodyArea): String = stringResource(when (area) {
    BodyArea.FINGERS -> R.string.trb_area_fingers
    BodyArea.FOREARM_FLEXORS -> R.string.trb_area_forearm_flexors
    BodyArea.FOREARM_EXTENSORS -> R.string.trb_area_forearm_extensors
    BodyArea.WRIST -> R.string.trb_area_wrist
    BodyArea.ELBOW -> R.string.trb_area_elbow
    BodyArea.BICEPS -> R.string.trb_area_biceps
    BodyArea.TRICEPS -> R.string.trb_area_triceps
    BodyArea.SHOULDER_FRONT -> R.string.trb_area_shoulder_front
    BodyArea.SHOULDER_REAR -> R.string.trb_area_shoulder_rear
    BodyArea.ROTATOR_CUFF -> R.string.trb_area_rotator_cuff
    BodyArea.CHEST -> R.string.trb_area_chest
    BodyArea.UPPER_BACK -> R.string.trb_area_upper_back
    BodyArea.LATS -> R.string.trb_area_lats
    BodyArea.ABS -> R.string.trb_area_abs
    BodyArea.OBLIQUES -> R.string.trb_area_obliques
    BodyArea.LOWER_BACK -> R.string.trb_area_lower_back
    BodyArea.HIPS -> R.string.trb_area_hips
    BodyArea.GLUTES -> R.string.trb_area_glutes
    BodyArea.QUADS -> R.string.trb_area_quads
    BodyArea.HAMSTRINGS -> R.string.trb_area_hamstrings
    BodyArea.ADDUCTORS -> R.string.trb_area_adductors
    BodyArea.CALVES -> R.string.trb_area_calves
    BodyArea.NECK -> R.string.trb_area_neck
    BodyArea.KNEE -> R.string.trb_area_knee
    BodyArea.ANKLE -> R.string.trb_area_ankle
})

/** Legend swatch colours, so screens can explain the map with the same tints. */
@Composable
internal fun bodyMapColor(intensity: Float): Color {
    val idle = lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, 0.12f)
    return if (intensity <= 0f) idle else lerp(idle, MaterialTheme.colorScheme.primary, areaMix(intensity))
}

/**
 * Accent share of a highlighted area: main areas get the full accent, involved
 * ones a clearly weaker tint, so the two read as different at a glance.
 */
internal fun areaMix(intensity: Float): Float = if (intensity >= 0.99f) 1f else 0.2f + 0.3f * intensity.coerceIn(0f, 1f)
