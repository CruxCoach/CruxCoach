package com.cruxcoach.app.bodymap

import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.model.Grip

// Port of Android ui/training/bodymap/GripPictogram.kt (0e1bad0c6): which grip an exercise
// is done with, and the side view of the hand on the hold as plain shapes in Android's
// drawing order (hold first, then the hand), with Android's numbers unchanged.

/** How the hand holds on — the pictogram vocabulary for finger and pulling exercises. */
internal enum class GripType { HALF_CRIMP, OPEN_HAND, FULL_CRIMP, PINCH, SLOPER, JUG, POCKET, BAR;

    /** Android's `gripTypeLabel` string key. */
    val labelKey: String get() = "trb_grip_" + name.lowercase()
}

internal fun Grip.toGripType(): GripType = when (this) {
    Grip.HALF_CRIMP -> GripType.HALF_CRIMP
    Grip.OPEN_HAND, Grip.THREE_FINGER_DRAG -> GripType.OPEN_HAND
    Grip.FULL_CRIMP -> GripType.FULL_CRIMP
    Grip.FRONT_TWO, Grip.MIDDLE_TWO -> GripType.POCKET
    Grip.PINCH -> GripType.PINCH
    Grip.SLOPER -> GripType.SLOPER
    Grip.JUG -> GripType.JUG
}

/**
 * The grip an exercise is done with: the logged/planned [grip] when known,
 * otherwise read from the exercise (finger work on edges is half crimp
 * unless it says open hand, pinch, sloper or pockets; pulling on a bar is
 * the bar grip). Null for everything else.
 */
internal fun gripFor(def: ExerciseDefinition, grip: Grip?): GripType? {
    grip?.let { return it.toGripType() }
    val s = def.slug.lowercase()
    val fingerWork = def.category == ExerciseCategoryV2.FINGER || def.kind == ExerciseKind.HANG ||
        EquipmentV2.HANGBOARD in def.equipment || EquipmentV2.PICKUP_BLOCK in def.equipment ||
        EquipmentV2.PICKUP_BLOCK_PAIR in def.equipment
    return when {
        "pinch" in s -> GripType.PINCH
        "sloper" in s -> GripType.SLOPER
        "pocket" in s || "two_finger" in s || "mono" in s -> GripType.POCKET
        "full_crimp" in s -> GripType.FULL_CRIMP
        "open" in s || "drag" in s -> GripType.OPEN_HAND
        "jug" in s -> GripType.JUG
        fingerWork -> GripType.HALF_CRIMP
        def.category == ExerciseCategoryV2.PULL &&
            (EquipmentV2.PULL_UP_BAR in def.equipment || EquipmentV2.RINGS in def.equipment) -> GripType.BAR
        else -> null
    }
}

internal fun isEdgeGrip(grip: GripType) = grip == GripType.HALF_CRIMP || grip == GripType.OPEN_HAND || grip == GripType.FULL_CRIMP

/**
 * One drawing primitive of the body map or the grip picture, in its own units.
 * [kind]: `ellipse` (cx, cy, rx, ry), `polygon` (x0, y0, …; straight, closed), `smooth` (x0, y0, …;
 * a closed curve through the midpoints, the points as controls), `box` (left, top, right, bottom;
 * [radius] corners), `line` (x0, y0, x1, y1; flat ends), `stroke` (x0, y0, …; round ends and joins),
 * `dot` (cx, cy; [radius]). [width] is a stroke width; 0 for a filled shape.
 */
internal data class MapShape(
    val kind: String,
    val role: String,
    val points: List<Float>,
    val width: Float = 0f,
    val radius: Float = 0f,
    val filled: Boolean = true,
)

internal object GripPictograms {
    // Drawing box in units: the wall face is at x = 72, the edge top at y = 44.
    const val BOX_W = 100f
    const val BOX_H = 70f
    private const val WALL_X = 72f
    private const val EDGE_TOP = 44f

    /** wrist → knuckle → middle joint → last joint → fingertip, plus an optional thumb. */
    private class Pose(val chain: List<Pair<Float, Float>>, val thumb: List<Pair<Float, Float>>? = null)

    private fun pt(x: Number, y: Number) = x.toFloat() to y.toFloat()

    private fun pose(grip: GripType): Pose = when (grip) {
        // Last finger bone flat on the edge, middle bone upright: the classic 90° half crimp.
        GripType.HALF_CRIMP -> Pose(listOf(pt(34, 15), pt(52, 23), pt(61.5, 31), pt(62.5, 41), pt(70.5, 41)))
        // Middle joint over the edge, last joint bent backwards, thumb locked over the index finger.
        GripType.FULL_CRIMP -> Pose(listOf(pt(37, 19), pt(55, 25), pt(66, 31), pt(64.5, 41.5), pt(70.5, 41)),
            thumb = listOf(pt(52, 33), pt(62, 35)))
        // Fingers nearly straight, dragging over the edge.
        GripType.OPEN_HAND -> Pose(listOf(pt(27, 28), pt(45, 32), pt(55, 36.5), pt(63, 40), pt(70.5, 41)))
        // Fingers hooked over the lip of a deep hold.
        GripType.JUG -> Pose(listOf(pt(31, 20), pt(49, 26), pt(57, 31), pt(64, 35), pt(66, 42)))
        // Open palm laid over a rounded hold.
        GripType.SLOPER -> Pose(listOf(pt(32, 48), pt(49.5, 44), pt(55.5, 39), pt(59.5, 35.5), pt(64, 33)))
        // Fingers on one side of the block, thumb on the other.
        GripType.PINCH -> Pose(listOf(pt(55, 6), pt(63, 14), pt(65, 25), pt(64, 33), pt(61.5, 40)),
            thumb = listOf(pt(52, 12), pt(46, 22), pt(48.5, 34)))
        // Finger pushed into a hole in the wall.
        GripType.POCKET -> Pose(listOf(pt(35, 24), pt(53, 31), pt(63, 38), pt(71, 42), pt(78, 42)))
        // Hand wrapped over a bar from behind.
        GripType.BAR -> Pose(listOf(pt(40, 22), pt(50, 33), pt(59, 35), pt(66.5, 42), pt(64, 49.5)),
            thumb = listOf(pt(48, 48), pt(55, 51.5)))
    }

    /** Android's edge depth: 20 mm ≈ 9 units, kept readable at both ends. */
    fun edgeDepth(edgeMm: Double?): Float = ((edgeMm ?: 20.0) * 0.45).toFloat().coerceIn(3f, 18f)

    private fun box(role: String, l: Float, t: Float, w: Float, h: Float, r: Float) = MapShape("box", role, listOf(l, t, l + w, t + h), radius = r)
    private fun wall() = box("wall", WALL_X, 0f, BOX_W - WALL_X, BOX_H, 0f)
    private fun wallLine() = MapShape("line", "hold", listOf(WALL_X, 0f, WALL_X, BOX_H), width = 1f, filled = false)

    /** The hold, then the hand: roles `wall`, `hold`, `hole`, `hand`, `thumb` (the hand colour at 85 %). */
    fun shapes(grip: GripType, edgeMm: Double?): List<MapShape> = buildList {
        when (grip) {
            GripType.HALF_CRIMP, GripType.FULL_CRIMP, GripType.OPEN_HAND -> {
                add(wall())
                val depth = edgeDepth(edgeMm)
                add(box("hold", WALL_X - depth, EDGE_TOP, depth + 1f, 6f, 1.2f))
                add(wallLine())
            }
            GripType.JUG -> {
                add(wall())
                add(box("hold", 58f, EDGE_TOP, WALL_X - 58f + 1f, 8f, 2f))
                add(box("hold", 58f, 36f, 4.5f, EDGE_TOP - 36f + 2f, 2f))
            }
            GripType.SLOPER -> {
                add(wall())
                // Android: a 180° arc from the bottom round the left of (72, 46), radius 14, closed.
                val pts = (0..16).flatMap { k ->
                    val a = kotlin.math.PI / 2 + kotlin.math.PI * k / 16
                    listOf((WALL_X + 14 * kotlin.math.cos(a)).toFloat(), (46 + 14 * kotlin.math.sin(a)).toFloat())
                }
                add(MapShape("polygon", "hold", pts))
            }
            GripType.PINCH -> add(box("hold", 50f, 22f, 10f, 38f, 3f))
            GripType.POCKET -> {
                add(wall())
                add(box("hole", WALL_X, 38.5f, 9f, 7f, 2f))
                add(wallLine())
            }
            GripType.BAR -> add(MapShape("dot", "hold", listOf(58f, 44f), radius = 6f))
        }
        val p = pose(grip)
        // Palm thickest, finger bones thinner towards the tip.
        val widths = listOf(11f, 7f, 6.5f, 6f)
        for (i in 0 until p.chain.size - 1) {
            val (a, b) = p.chain[i] to p.chain[i + 1]
            add(MapShape("stroke", "hand", listOf(a.first, a.second, b.first, b.second), width = widths.getOrElse(i) { 6f }, filled = false))
        }
        p.thumb?.let { t ->
            for (i in 0 until t.size - 1) {
                add(MapShape("stroke", "thumb", listOf(t[i].first, t[i].second, t[i + 1].first, t[i + 1].second), width = 5.5f, filled = false))
            }
        }
    }
}
