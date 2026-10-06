package com.cruxcoach.android.ui.training.bodymap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.model.Grip
import kotlin.math.min
import kotlin.math.roundToInt

/** How the hand holds on — the pictogram vocabulary for finger and pulling exercises. */
enum class GripType { HALF_CRIMP, OPEN_HAND, FULL_CRIMP, PINCH, SLOPER, JUG, POCKET, BAR }

fun Grip.toGripType(): GripType = when (this) {
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
fun gripFor(def: ExerciseDefinition, grip: Grip?): GripType? {
    grip?.let { return it.toGripType() }
    val s = def.slug.lowercase()
    val fingerWork = def.category == ExerciseCategoryV2.FINGER || def.kind == ExerciseKind.HANG ||
        EquipmentV2.HANGBOARD in def.equipment || EquipmentV2.PICKUP_BLOCK in def.equipment
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

/**
 * A side view of the hand on the hold, with the edge depth drawn roughly to
 * scale and labelled ("20 mm"). Our own drawing.
 */
@Composable
fun GripPictogram(grip: GripType, edgeMm: Double?, modifier: Modifier = Modifier) {
    val label = gripTypeLabel(grip) + (edgeMm?.takeIf { isEdgeGrip(grip) }?.let { " · ${it.roundToInt()} mm" } ?: "")
    // The depth label sits under the drawing, never on top of the edge.
    Column(modifier.semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
        GripCanvas(grip, edgeMm, Modifier.fillMaxWidth().weight(1f))
        if (edgeMm != null && isEdgeGrip(grip)) {
            Text(
                stringResource(R.string.trb_edge_mm, edgeMm.roundToInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

internal fun isEdgeGrip(grip: GripType) = grip == GripType.HALF_CRIMP || grip == GripType.OPEN_HAND || grip == GripType.FULL_CRIMP

/** The drawing alone, without the text label — also used by the small thumbnails. */
@Composable
internal fun GripCanvas(grip: GripType, edgeMm: Double?, modifier: Modifier = Modifier) {
    val hand = MaterialTheme.colorScheme.primary
    val hold = MaterialTheme.colorScheme.outline
    val wall = MaterialTheme.colorScheme.surfaceVariant
    val hole = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    Canvas(modifier) {
        val scale = min(size.width / BOX_W, size.height / BOX_H)
        val dx = (size.width - BOX_W * scale) / 2f
        val dy = (size.height - BOX_H * scale) / 2f
        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawHold(grip, edgeMm, wall, hold, hole)
            drawHand(pose(grip), hand)
        }
    }
}

// Drawing box in units: the wall face is at x = 72, the edge top at y = 44.
private const val BOX_W = 100f
private const val BOX_H = 70f
private const val WALL_X = 72f
private const val EDGE_TOP = 44f

/** wrist → knuckle → middle joint → last joint → fingertip, plus an optional thumb. */
private data class Pose(val chain: List<Offset>, val thumb: List<Offset>? = null)

private fun pt(x: Number, y: Number) = Offset(x.toFloat(), y.toFloat())

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

private fun DrawScope.drawHold(grip: GripType, edgeMm: Double?, wall: Color, hold: Color, hole: Color) {
    when (grip) {
        GripType.HALF_CRIMP, GripType.FULL_CRIMP, GripType.OPEN_HAND -> {
            drawRect(wall, topLeft = Offset(WALL_X, 0f), size = Size(BOX_W - WALL_X, BOX_H))
            // Edge depth roughly to scale: 20 mm ≈ 9 units, kept readable at both ends.
            val depth = ((edgeMm ?: 20.0) * 0.45).toFloat().coerceIn(3f, 18f)
            drawRoundRect(hold, topLeft = Offset(WALL_X - depth, EDGE_TOP), size = Size(depth + 1f, 6f), cornerRadius = CornerRadius(1.2f))
            drawLine(hold, Offset(WALL_X, 0f), Offset(WALL_X, BOX_H), strokeWidth = 1f)
        }
        GripType.JUG -> {
            drawRect(wall, topLeft = Offset(WALL_X, 0f), size = Size(BOX_W - WALL_X, BOX_H))
            drawRoundRect(hold, topLeft = Offset(58f, EDGE_TOP), size = Size(WALL_X - 58f + 1f, 8f), cornerRadius = CornerRadius(2f))
            drawRoundRect(hold, topLeft = Offset(58f, 36f), size = Size(4.5f, EDGE_TOP - 36f + 2f), cornerRadius = CornerRadius(2f))
        }
        GripType.SLOPER -> {
            drawRect(wall, topLeft = Offset(WALL_X, 0f), size = Size(BOX_W - WALL_X, BOX_H))
            val dome = Path().apply {
                arcTo(Rect(Offset(WALL_X, 46f), 14f), 90f, 180f, forceMoveTo = true)
                close()
            }
            drawPath(dome, hold)
        }
        GripType.PINCH -> drawRoundRect(hold, topLeft = Offset(50f, 22f), size = Size(10f, 38f), cornerRadius = CornerRadius(3f))
        GripType.POCKET -> {
            drawRect(wall, topLeft = Offset(WALL_X, 0f), size = Size(BOX_W - WALL_X, BOX_H))
            drawRoundRect(hole, topLeft = Offset(WALL_X, 38.5f), size = Size(9f, 7f), cornerRadius = CornerRadius(2f))
            drawLine(hold, Offset(WALL_X, 0f), Offset(WALL_X, BOX_H), strokeWidth = 1f)
        }
        GripType.BAR -> drawCircle(hold, radius = 6f, center = Offset(58f, 44f))
    }
}

private fun DrawScope.drawHand(pose: Pose, color: Color) {
    val c = pose.chain
    // Palm thickest, finger bones thinner towards the tip.
    val widths = listOf(11f, 7f, 6.5f, 6f)
    for (i in 0 until c.size - 1) {
        drawLine(color, c[i], c[i + 1], strokeWidth = widths.getOrElse(i) { 6f }, cap = StrokeCap.Round)
    }
    pose.thumb?.let { t ->
        for (i in 0 until t.size - 1) drawLine(color.copy(alpha = 0.85f), t[i], t[i + 1], strokeWidth = 5.5f, cap = StrokeCap.Round)
    }
}

@Composable
fun gripTypeLabel(grip: GripType): String = stringResource(when (grip) {
    GripType.HALF_CRIMP -> R.string.trb_grip_half_crimp
    GripType.OPEN_HAND -> R.string.trb_grip_open_hand
    GripType.FULL_CRIMP -> R.string.trb_grip_full_crimp
    GripType.PINCH -> R.string.trb_grip_pinch
    GripType.SLOPER -> R.string.trb_grip_sloper
    GripType.JUG -> R.string.trb_grip_jug
    GripType.POCKET -> R.string.trb_grip_pocket
    GripType.BAR -> R.string.trb_grip_bar
})
