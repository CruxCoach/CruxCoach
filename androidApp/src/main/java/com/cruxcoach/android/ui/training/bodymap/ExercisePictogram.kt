package com.cruxcoach.android.ui.training.bodymap

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.cruxcoach.athlete.catalog.EquipmentV2
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition
import com.cruxcoach.athlete.catalog.ExerciseKind

/*
 * Movement pictograms for exercise rows and the player (owner 2026-10-09: the
 * zoomed body map read as blobs at 48 dp). A stick figure in the exercise's
 * key position with its equipment – bar, hangboard, block, rings, wall – on a
 * 24-unit grid; the far arm and leg are drawn lighter for depth.
 */

/** The positions the catalogue's exercises map to. */
enum class Pictogram {
    HANG_BOARD, HANG_BAR, HANG_ONE_ARM, PULL_UP, LOCK_OFF, FRONT_LEVER, HANGING_RAISE,
    PICKUP, PICKUP_TWO, STAND_WEIGHT, ROW, DB_ROW,
    PUSH_UP, PIKE, HANDSTAND, DIP, PRESS, BENCH_PRESS, BAND, HAND, WRIST,
    PLANK, SIDE_PLANK, HOLLOW, SUPINE, L_SIT, QUADRUPED, PRONE, CHILD,
    SQUAT, LUNGE, HINGE, BRIDGE, JUMP, CALF,
    SEATED_STRETCH, ARMS_UP, CARDIO, ARM_CIRCLES,
    CLIMB, CAMPUS, DYNO,
}

object ExercisePictograms {

    /** The pictogram for an exercise: by its slug's movement first, then by category and kind. */
    fun of(def: ExerciseDefinition): Pictogram {
        val s = def.slug.substringAfter('.')
        fun has(vararg words: String) = words.any { it in s }
        val eq = def.equipment.toSet()
        return when (def.category) {
            ExerciseCategoryV2.FINGER -> when {
                has("two_arm_pickup") || EquipmentV2.PICKUP_BLOCK_PAIR in eq -> Pictogram.PICKUP_TWO
                EquipmentV2.PICKUP_BLOCK in eq -> Pictogram.PICKUP
                has("extension", "roll") -> Pictogram.HAND
                has("one_arm", "offset") -> Pictogram.HANG_ONE_ARM
                else -> Pictogram.HANG_BOARD
            }
            ExerciseCategoryV2.PULL -> when {
                has("front_lever") -> Pictogram.FRONT_LEVER
                has("dead_hang", "scapular") -> Pictogram.HANG_BAR
                has("one_arm", "lock_off", "frenchies") -> Pictogram.LOCK_OFF
                has("dumbbell_row") -> Pictogram.DB_ROW
                has("row") -> Pictogram.ROW
                else -> Pictogram.PULL_UP
            }
            ExerciseCategoryV2.PUSH -> when {
                has("dip", "mantle") -> Pictogram.DIP
                has("handstand") -> Pictogram.HANDSTAND
                has("pike") -> Pictogram.PIKE
                has("bench_press") -> Pictogram.BENCH_PRESS
                has("overhead") -> Pictogram.PRESS
                else -> Pictogram.PUSH_UP
            }
            ExerciseCategoryV2.ANTAGONIST -> when {
                has("finger") -> Pictogram.HAND
                has("wrist", "forearm", "deviation") -> Pictogram.WRIST
                has("ytw", "iyt") -> Pictogram.PRONE
                has("push_up") -> Pictogram.PUSH_UP
                has("wall") -> Pictogram.ARMS_UP
                has("side_lying") -> Pictogram.SIDE_PLANK
                has("kettlebell", "cuban", "get_up") -> Pictogram.PRESS
                else -> Pictogram.BAND
            }
            ExerciseCategoryV2.CORE -> when {
                has("hanging", "toes_to_bar", "windshield") -> Pictogram.HANGING_RAISE
                has("side_plank") -> Pictogram.SIDE_PLANK
                has("plank", "body_saw", "ab_wheel") -> Pictogram.PLANK
                has("bird_dog") -> Pictogram.QUADRUPED
                has("superman", "back_extension") -> Pictogram.PRONE
                has("l_sit", "v_sit", "pike_compression") -> Pictogram.L_SIT
                has("pallof") -> Pictogram.BAND
                has("side_bend") -> Pictogram.STAND_WEIGHT
                has("dead_bug", "crunch") -> Pictogram.SUPINE
                else -> Pictogram.HOLLOW
            }
            ExerciseCategoryV2.LEGS -> when {
                has("jump") -> Pictogram.JUMP
                has("calf", "tibialis") -> Pictogram.CALF
                has("bridge", "hip_thrust") -> Pictogram.BRIDGE
                has("deadlift", "rdl", "nordic") -> Pictogram.HINGE
                has("lunge", "split", "step", "mantle") -> Pictogram.LUNGE
                has("copenhagen") -> Pictogram.SIDE_PLANK
                else -> Pictogram.SQUAT
            }
            ExerciseCategoryV2.MOBILITY -> when {
                has("finger", "forearm", "wrist") -> Pictogram.HAND
                has("cat_cow") -> Pictogram.QUADRUPED
                has("child") -> Pictogram.CHILD
                has("hip_flexor", "calf", "knee_to_wall", "high_step") -> Pictogram.LUNGE
                has("deep_squat") -> Pictogram.SQUAT
                has("roller", "active_leg_raise") -> Pictogram.SUPINE
                has("shoulder", "pec", "sleeper", "wall_slide", "neck", "scapular", "open_book") -> Pictogram.ARMS_UP
                else -> Pictogram.SEATED_STRETCH
            }
            ExerciseCategoryV2.WARMUP -> when {
                has("finger_ramp") -> Pictogram.HANG_BOARD
                has("hang") -> Pictogram.HANG_BAR
                has("finger", "wrist") -> Pictogram.HAND
                has("band") -> Pictogram.BAND
                has("arm_circles") -> Pictogram.ARM_CIRCLES
                has("inchworm", "down_dog") -> Pictogram.PIKE
                has("leg_swing", "greatest") -> Pictogram.LUNGE
                has("climbing") -> Pictogram.CLIMB
                has("spine") -> Pictogram.ARMS_UP
                else -> Pictogram.CARDIO
            }
            ExerciseCategoryV2.POWER -> when {
                has("pullup", "pull_up") -> Pictogram.PULL_UP
                EquipmentV2.CAMPUS_BOARD in eq -> Pictogram.CAMPUS
                has("dyno") -> Pictogram.DYNO
                else -> Pictogram.CLIMB
            }
            ExerciseCategoryV2.ENDURANCE, ExerciseCategoryV2.TECHNIQUE -> Pictogram.CLIMB
        }.let { if (def.kind == ExerciseKind.CLIMB && it !in CLIMBING) Pictogram.CLIMB else it }
    }

    private val CLIMBING = setOf(Pictogram.CLIMB, Pictogram.CAMPUS, Pictogram.DYNO)
}

/** Category colour of the pictogram tile, a light and a dark variant. */
fun pictogramColor(category: ExerciseCategoryV2, dark: Boolean): Color = Color(
    when (category) {
        ExerciseCategoryV2.FINGER -> if (dark) 0xFFFF9A5C else 0xFFC74300
        ExerciseCategoryV2.PULL -> if (dark) 0xFF8AB4FF else 0xFF1E63D6
        ExerciseCategoryV2.PUSH -> if (dark) 0xFFFF8FBF else 0xFFC2185B
        ExerciseCategoryV2.ANTAGONIST -> if (dark) 0xFFA9B4FF else 0xFF4352C2
        ExerciseCategoryV2.CORE -> if (dark) 0xFFCDB2FF else 0xFF7445C4
        ExerciseCategoryV2.LEGS -> if (dark) 0xFF8EE8A5 else 0xFF2E7D32
        ExerciseCategoryV2.MOBILITY -> if (dark) 0xFF6FE0D2 else 0xFF00796B
        ExerciseCategoryV2.WARMUP -> if (dark) 0xFFFFC76B else 0xFF9A6200
        ExerciseCategoryV2.POWER -> if (dark) 0xFFFF8A80 else 0xFFC62828
        ExerciseCategoryV2.ENDURANCE -> if (dark) 0xFF7FD8F5 else 0xFF00728F
        ExerciseCategoryV2.TECHNIQUE -> if (dark) 0xFFD7E07A else 0xFF6A7300
    },
)

/**
 * Draws [pictogram] filling [modifier]'s square: the figure in [color], the
 * far limbs at 55 %, equipment in [gear].
 */
@Composable
fun PictogramCanvas(pictogram: Pictogram, color: Color, gear: Color, modifier: Modifier = Modifier) {
    val pose = remember(pictogram) { POSES.getValue(pictogram) }
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val ox = (size.width - 24f * u) / 2f
        val oy = (size.height - 24f * u) / 2f
        fun o(p: PicPt) = Offset(ox + p.x * u, oy + p.y * u)
        val gearStroke = Stroke(width = 1.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        pose.props.forEach { it.draw(this, ::o, u, gear, gearStroke) }
        val limb = Stroke(width = 2.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val far = color.copy(alpha = 0.55f)
        fun chain(vararg pts: PicPt, c: Color) {
            val path = Path().apply { moveTo(o(pts[0]).x, o(pts[0]).y); pts.drop(1).forEach { lineTo(o(it).x, o(it).y) } }
            drawPath(path, c, style = limb)
        }
        pose.figure?.let { f ->
            val sw = if (f.front) 1.7f else 0f
            val hw = if (f.front) 0.9f else 0f
            val back = if (f.front) color else far
            val shoulderNear = PicPt(f.neck.x + sw, f.neck.y); val shoulderFar = PicPt(f.neck.x - sw, f.neck.y)
            val hipNear = PicPt(f.hip.x + hw, f.hip.y); val hipFar = PicPt(f.hip.x - hw, f.hip.y)
            chain(hipFar, *f.legFar.toTypedArray(), c = back)
            chain(shoulderFar, *f.armFar.toTypedArray(), c = back)
            chain(f.neck, f.hip, c = color)
            if (f.front) { chain(shoulderFar, shoulderNear, c = color); chain(hipFar, hipNear, c = color) }
            chain(hipNear, *f.legNear.toTypedArray(), c = color)
            chain(shoulderNear, *f.armNear.toTypedArray(), c = color)
            drawCircle(color, radius = f.headR * u, center = o(f.head))
        }
        pose.subject.forEach { it.draw(this, ::o, u, color, gearStroke) }
        pose.overlay.forEach { it.draw(this, ::o, u, gear, gearStroke) }
    }
}

// ── Geometry ─────────────────────────────────────────────────────────

internal data class PicPt(val x: Float, val y: Float)

private fun p(x: Number, y: Number) = PicPt(x.toFloat(), y.toFloat())

/** A stick figure: limbs run from the neck (arms) and the hip (legs); each limb is elbow/knee, then hand/foot. */
internal class StickFigure(
    val head: PicPt, val neck: PicPt, val hip: PicPt,
    val armNear: List<PicPt>, val armFar: List<PicPt>,
    val legNear: List<PicPt>, val legFar: List<PicPt>,
    val headR: Float = 2.0f,
    /** Seen from the front: arms start at the shoulders, legs at the hips, both sides at full strength. */
    val front: Boolean = false,
)

internal sealed interface PictoProp {
    fun draw(scope: DrawScope, o: (PicPt) -> Offset, u: Float, gear: Color, stroke: Stroke)

    /** Line of equipment (bar, wall, bench), [w] in units. */
    data class Line(val a: PicPt, val b: PicPt, val w: Float = 1.5f) : PictoProp {
        override fun draw(scope: DrawScope, o: (PicPt) -> Offset, u: Float, gear: Color, stroke: Stroke) =
            scope.drawLine(gear, o(a), o(b), strokeWidth = w * u, cap = StrokeCap.Round)
    }

    /** Filled rounded rectangle (hangboard, block, box). */
    data class Box(val tl: PicPt, val br: PicPt, val r: Float = 0.8f, val filled: Boolean = true) : PictoProp {
        override fun draw(scope: DrawScope, o: (PicPt) -> Offset, u: Float, gear: Color, stroke: Stroke) {
            val a = o(tl); val b = o(br)
            if (filled) scope.drawRoundRect(gear, a, Size(b.x - a.x, b.y - a.y), CornerRadius(r * u))
            else scope.drawRoundRect(gear, a, Size(b.x - a.x, b.y - a.y), CornerRadius(r * u), style = stroke)
        }
    }

    data class Dot(val c: PicPt, val r: Float, val filled: Boolean = true) : PictoProp {
        override fun draw(scope: DrawScope, o: (PicPt) -> Offset, u: Float, gear: Color, stroke: Stroke) {
            if (filled) scope.drawCircle(gear, r * u, o(c)) else scope.drawCircle(gear, r * u, o(c), style = stroke)
        }
    }

    /** A band or motion arc: quadratic curve from [a] to [b] bent towards [ctrl]. */
    data class Curve(val a: PicPt, val ctrl: PicPt, val b: PicPt, val w: Float = 1.2f) : PictoProp {
        override fun draw(scope: DrawScope, o: (PicPt) -> Offset, u: Float, gear: Color, stroke: Stroke) {
            val path = Path().apply { moveTo(o(a).x, o(a).y); quadraticTo(o(ctrl).x, o(ctrl).y, o(b).x, o(b).y) }
            scope.drawPath(path, gear, style = Stroke(width = w * u, cap = StrokeCap.Round))
        }
    }

    /** A small dumbbell centred on [c], horizontal or vertical. */
    data class Dumbbell(val c: PicPt, val vertical: Boolean = false) : PictoProp {
        override fun draw(scope: DrawScope, o: (PicPt) -> Offset, u: Float, gear: Color, stroke: Stroke) {
            val d = if (vertical) PicPt(0f, 1.6f) else PicPt(1.6f, 0f)
            scope.drawLine(gear, o(PicPt(c.x - d.x, c.y - d.y)), o(PicPt(c.x + d.x, c.y + d.y)), strokeWidth = 1.1f * u, cap = StrokeCap.Round)
            val side = if (vertical) Size(2.6f * u, 1.1f * u) else Size(1.1f * u, 2.6f * u)
            listOf(-1f, 1f).forEach { s ->
                val at = o(PicPt(c.x + s * d.x, c.y + s * d.y))
                scope.drawRoundRect(gear, Offset(at.x - side.width / 2, at.y - side.height / 2), side, CornerRadius(0.4f * u))
            }
        }
    }
}

/** [props] behind the figure and [overlay] in front of it in the equipment colour; [subject] in the figure's colour. */
internal class PictoPose(
    val figure: StickFigure?,
    val props: List<PictoProp> = emptyList(),
    val overlay: List<PictoProp> = emptyList(),
    val subject: List<PictoProp> = emptyList(),
)

private fun ground(y: Number = 22, from: Number = 2, to: Number = 22) = PictoProp.Line(p(from, y), p(to, y), 1.2f)
private fun bar(y: Number = 2.2, from: Number = 4, to: Number = 20) = PictoProp.Line(p(from, y), p(to, y), 1.6f)
/** A hangboard: a thick board, unlike the thin pull-up bar. */
private val HANGBOARD = PictoProp.Box(p(4.6, 0.4), p(19.4, 3.6), 1.2f)

/** An open hand, palm towards the viewer: palm, four fingers, thumb, wrist. */
private val HAND_SHAPE: List<PictoProp> = listOf(
    PictoProp.Box(p(7.4, 10.6), p(16.6, 18.4), 3f),
    PictoProp.Line(p(8.6, 11.4), p(8.4, 5.4), 2f),
    PictoProp.Line(p(10.9, 11), p(10.9, 3.8), 2f),
    PictoProp.Line(p(13.2, 11), p(13.4, 4.2), 2f),
    PictoProp.Line(p(15.4, 11.6), p(15.8, 6.4), 2f),
    PictoProp.Line(p(7.8, 15), p(4.6, 11.2), 2f),
    PictoProp.Line(p(9.4, 18), p(9.4, 22.6), 1.8f),
    PictoProp.Line(p(14.6, 18), p(14.6, 22.6), 1.8f),
)

/** Forearm with a dumbbell in the hand: wrist curls and rotations. */
private val WRIST_SHAPE: List<PictoProp> = listOf(
    PictoProp.Line(p(3, 16), p(13.6, 14.4), 3.2f),
    PictoProp.Box(p(13, 11.6), p(17.4, 16.4), 2f),
    PictoProp.Dumbbell(p(17.8, 14), vertical = true),
    PictoProp.Curve(p(17.6, 7.2), p(21, 9.8), p(20.8, 14), 0.9f),
)

/** The positions, each drawn on the 24-unit grid. */
internal val POSES: Map<Pictogram, PictoPose> = mapOf(
    Pictogram.HANG_BOARD to PictoPose(
        StickFigure(head = p(12, 8.2), neck = p(12, 11), hip = p(12, 16.8),
            armNear = listOf(p(15.6, 7.6), p(16.2, 3.8)), armFar = listOf(p(8.4, 7.6), p(7.8, 3.8)),
            legNear = listOf(p(13.4, 19.8), p(13.6, 23)), legFar = listOf(p(10.6, 19.8), p(10.4, 23)), headR = 1.9f, front = true),
        props = listOf(HANGBOARD),
    ),
    Pictogram.HANG_BAR to PictoPose(
        StickFigure(head = p(12, 7.4), neck = p(12, 10.2), hip = p(12, 16.2),
            armNear = listOf(p(15.6, 6.6), p(16.2, 2.4)), armFar = listOf(p(8.4, 6.6), p(7.8, 2.4)),
            legNear = listOf(p(13.4, 19.4), p(13.6, 22.8)), legFar = listOf(p(10.6, 19.4), p(10.4, 22.8)), headR = 1.9f, front = true),
        props = listOf(bar(from = 3, to = 21)),
    ),
    Pictogram.HANG_ONE_ARM to PictoPose(
        StickFigure(head = p(10.8, 8.2), neck = p(11.4, 11), hip = p(11.2, 16.8),
            armNear = listOf(p(14.6, 7.6), p(14.6, 3.8)), armFar = listOf(p(8.2, 13.6), p(7.8, 16.4)),
            legNear = listOf(p(12.6, 19.8), p(12.8, 23)), legFar = listOf(p(9.8, 19.8), p(9.6, 23)), headR = 1.9f, front = true),
        props = listOf(HANGBOARD),
    ),
    Pictogram.PULL_UP to PictoPose(
        StickFigure(head = p(12, 2.6), neck = p(12, 6.4), hip = p(12, 13),
            armNear = listOf(p(17, 7.8), p(15.8, 4.8)), armFar = listOf(p(7, 7.8), p(8.2, 4.8)),
            legNear = listOf(p(13.4, 16.6), p(12.6, 20.2)), legFar = listOf(p(10.8, 16.8), p(11.6, 20.2)), front = true),
        props = listOf(bar(y = 4.8, from = 3, to = 21)),
    ),
    Pictogram.LOCK_OFF to PictoPose(
        StickFigure(head = p(12.2, 2.7), neck = p(12, 6.4), hip = p(11.8, 13),
            armNear = listOf(p(16.8, 7.8), p(14.6, 4.8)), armFar = listOf(p(8.4, 10.4), p(7.8, 13.4)),
            legNear = listOf(p(13.2, 16.6), p(12.4, 20.2)), legFar = listOf(p(10.6, 16.8), p(11.4, 20.2)), front = true),
        props = listOf(bar(y = 4.8, from = 3, to = 21)),
    ),
    Pictogram.FRONT_LEVER to PictoPose(
        StickFigure(head = p(5.8, 10), neck = p(8.4, 10.2), hip = p(15.4, 10.4),
            armNear = listOf(p(8.8, 7), p(9.4, 3.6)), armFar = listOf(p(8.2, 7), p(8.4, 3.6)),
            legNear = listOf(p(18.6, 10.5), p(22, 10.6)), legFar = listOf(p(18.6, 10.5), p(22, 10.6)), headR = 1.8f),
        props = listOf(bar(y = 3.6, from = 4, to = 14)),
    ),
    Pictogram.HANGING_RAISE to PictoPose(
        StickFigure(head = p(11, 6.7), neck = p(11, 9), hip = p(11, 15),
            armNear = listOf(p(12.9, 6), p(13.4, 2.4)), armFar = listOf(p(9.1, 6), p(8.6, 2.4)),
            legNear = listOf(p(15.6, 15), p(20.6, 14.8)), legFar = listOf(p(15.6, 15.6), p(20.4, 15.6)), headR = 1.8f),
        props = listOf(bar()),
    ),
    Pictogram.PICKUP to PictoPose(
        StickFigure(head = p(10.4, 3.4), neck = p(10.6, 6.2), hip = p(10.8, 13),
            armNear = listOf(p(12.6, 9.6), p(13.6, 12.8)), armFar = listOf(p(8.8, 9.4), p(8.2, 12.6)),
            legNear = listOf(p(11.8, 17.4), p(12.4, 22)), legFar = listOf(p(10, 17.4), p(9.6, 22))),
        props = listOf(ground(22.8)),
        overlay = listOf(PictoProp.Box(p(12.4, 12.4), p(15.4, 14.6), 0.5f), PictoProp.Line(p(13.9, 14.6), p(13.9, 16.4), 1f), PictoProp.Dot(p(13.9, 18.8), 2.6f)),
    ),
    Pictogram.PICKUP_TWO to PictoPose(
        StickFigure(head = p(12, 3.4), neck = p(12, 6.2), hip = p(12, 12.8),
            armNear = listOf(p(14.6, 9.4), p(13, 13)), armFar = listOf(p(9.4, 9.4), p(11, 13)),
            legNear = listOf(p(14.8, 17), p(15.4, 22)), legFar = listOf(p(9.2, 17), p(8.6, 22)), front = true),
        props = listOf(ground(22.8)),
        overlay = listOf(PictoProp.Box(p(10.2, 12.8), p(13.8, 15), 0.5f), PictoProp.Line(p(12, 15), p(12, 16.6), 1f), PictoProp.Dot(p(12, 19.2), 2.7f)),
    ),
    Pictogram.STAND_WEIGHT to PictoPose(
        StickFigure(head = p(11.4, 3.3), neck = p(11.4, 6), hip = p(11, 13),
            armNear = listOf(p(13.4, 9.6), p(14, 13.2)), armFar = listOf(p(9, 9), p(8.4, 12.2)),
            legNear = listOf(p(12, 17.4), p(12.6, 21.8)), legFar = listOf(p(10.2, 17.4), p(9.8, 21.8))),
        props = listOf(ground(22.6)),
        overlay = listOf(PictoProp.Dumbbell(p(14, 14.4))),
    ),
    Pictogram.ROW to PictoPose(
        StickFigure(head = p(7.4, 9.4), neck = p(9.6, 10.6), hip = p(15.5, 14.4),
            armNear = listOf(p(12, 8), p(8.6, 5.8)), armFar = listOf(p(11.2, 7.6), p(8, 5.8)),
            legNear = listOf(p(18.6, 16.4), p(21.6, 18.4)), legFar = listOf(p(18.6, 16.6), p(21.4, 18.6)), headR = 1.8f),
        props = listOf(PictoProp.Line(p(8.3, 0.6), p(8.3, 3.8), 0.9f), PictoProp.Dot(p(8.3, 4.8), 1.1f, filled = false), ground(19.6, 4, 23)),
    ),
    Pictogram.DB_ROW to PictoPose(
        StickFigure(head = p(5.8, 8.6), neck = p(8.2, 9.8), hip = p(15, 10.6),
            armNear = listOf(p(11.4, 8.2), p(11.4, 11.8)), armFar = listOf(p(8.2, 11.6), p(8, 13.2)),
            legNear = listOf(p(16.4, 15.8), p(17.4, 21.6)), legFar = listOf(p(14.2, 16), p(13, 21.6)), headR = 1.8f),
        props = listOf(PictoProp.Box(p(3.4, 13.2), p(10.4, 14.6), 0.5f), PictoProp.Line(p(4.4, 14.6), p(4.4, 21.6), 1f), PictoProp.Line(p(9.4, 14.6), p(9.4, 21.6), 1f), ground(22.4)),
        overlay = listOf(PictoProp.Dumbbell(p(11.4, 12.8))),
    ),
    Pictogram.PUSH_UP to PictoPose(
        StickFigure(head = p(4.8, 11), neck = p(7.2, 12.4), hip = p(14.4, 15.6),
            armNear = listOf(p(7.6, 16.4), p(7.8, 20.2)), armFar = listOf(p(6.8, 16.4), p(6.6, 20.2)),
            legNear = listOf(p(17.8, 17.6), p(21.4, 19.8)), legFar = listOf(p(17.8, 17.6), p(21.4, 19.8)), headR = 1.8f),
        props = listOf(ground(20.9)),
    ),
    Pictogram.PIKE to PictoPose(
        StickFigure(head = p(7.8, 17.2), neck = p(8.8, 15.2), hip = p(12.6, 9.4),
            armNear = listOf(p(7.9, 17.8), p(7, 20.4)), armFar = listOf(p(8.5, 17.8), p(7.9, 20.4)),
            legNear = listOf(p(15.8, 15), p(18.8, 20.4)), legFar = listOf(p(15.6, 15), p(18.2, 20.4)), headR = 1.7f),
        props = listOf(ground(21)),
    ),
    Pictogram.HANDSTAND to PictoPose(
        StickFigure(head = p(11, 18.6), neck = p(11, 16.2), hip = p(11.4, 9.4),
            armNear = listOf(p(13.2, 18.6), p(13, 21.4)), armFar = listOf(p(8.8, 18.6), p(9, 21.4)),
            legNear = listOf(p(12, 5.6), p(13.6, 2.2)), legFar = listOf(p(11.2, 5.6), p(12.6, 2)), headR = 1.7f),
        props = listOf(ground(22.2), PictoProp.Line(p(15, 1), p(15, 22.2), 1.2f)),
    ),
    Pictogram.DIP to PictoPose(
        StickFigure(head = p(12, 5.6), neck = p(12, 8.2), hip = p(12, 15),
            armNear = listOf(p(15.8, 8.8), p(14.6, 12)), armFar = listOf(p(8.2, 8.8), p(9.4, 12)),
            legNear = listOf(p(13.6, 18.4), p(12, 21)), legFar = listOf(p(11.4, 18.6), p(10, 20.8)), front = true),
        props = listOf(bar(y = 12, from = 4.5, to = 19.5), PictoProp.Line(p(5, 12), p(5, 22.4), 1.2f), PictoProp.Line(p(19, 12), p(19, 22.4), 1.2f)),
    ),
    Pictogram.PRESS to PictoPose(
        StickFigure(head = p(12, 5), neck = p(12, 7.6), hip = p(12, 14.2),
            armNear = listOf(p(15.8, 6.4), p(15.2, 2.6)), armFar = listOf(p(8.2, 6.4), p(8.8, 2.6)),
            legNear = listOf(p(13.2, 18), p(13.8, 21.8)), legFar = listOf(p(10.8, 18), p(10.2, 21.8)), front = true),
        props = listOf(ground(22.6)),
        overlay = listOf(PictoProp.Dumbbell(p(15.2, 2.2)), PictoProp.Dumbbell(p(8.8, 2.2))),
    ),
    Pictogram.BENCH_PRESS to PictoPose(
        StickFigure(head = p(5.2, 13.6), neck = p(7.6, 14.2), hip = p(14, 14.4),
            armNear = listOf(p(8.6, 11), p(8.6, 7.6)), armFar = listOf(p(7.6, 11), p(7.6, 7.6)),
            legNear = listOf(p(17.4, 16.8), p(18.6, 21.6)), legFar = listOf(p(17, 17), p(17.6, 21.6)), headR = 1.8f),
        props = listOf(PictoProp.Box(p(3.6, 15.6), p(15.6, 17), 0.5f), PictoProp.Line(p(5, 17), p(5, 21.6), 1f), PictoProp.Line(p(14.2, 17), p(14.2, 21.6), 1f), ground(22.4)),
        overlay = listOf(PictoProp.Dumbbell(p(8.2, 6.6))),
    ),
    Pictogram.BAND to PictoPose(
        StickFigure(head = p(12, 4.4), neck = p(12, 7.2), hip = p(12, 14.2),
            armNear = listOf(p(15.6, 7.6), p(19.2, 7.8)), armFar = listOf(p(8.4, 7.6), p(4.8, 7.8)),
            legNear = listOf(p(13.2, 18), p(13.8, 21.8)), legFar = listOf(p(10.8, 18), p(10.2, 21.8)), front = true),
        props = listOf(ground(22.6)),
        overlay = listOf(PictoProp.Curve(p(4.8, 7.8), p(12, 10.8), p(19.2, 7.8), 1.1f)),
    ),
    // Hand and forearm work: no figure, the hand itself in the figure's colour.
    Pictogram.HAND to PictoPose(null, subject = HAND_SHAPE),
    Pictogram.WRIST to PictoPose(null, subject = WRIST_SHAPE.dropLast(2), overlay = WRIST_SHAPE.takeLast(2)),
    Pictogram.PLANK to PictoPose(
        StickFigure(head = p(4.8, 13.6), neck = p(7.2, 14.8), hip = p(14.4, 16.4),
            armNear = listOf(p(7.6, 20.2), p(11, 20.2)), armFar = listOf(p(6.8, 20.2), p(10.2, 20.2)),
            legNear = listOf(p(17.8, 18), p(21.4, 19.8)), legFar = listOf(p(17.8, 18), p(21.4, 19.8)), headR = 1.8f),
        props = listOf(ground(20.9)),
    ),
    Pictogram.SIDE_PLANK to PictoPose(
        StickFigure(head = p(6, 10.6), neck = p(8.2, 12), hip = p(14.6, 15.8),
            armNear = listOf(p(8.6, 8.6), p(9, 5.2)), armFar = listOf(p(8.3, 16.2), p(8.4, 20.2)),
            legNear = listOf(p(18, 17.8), p(21.4, 19.9)), legFar = listOf(p(18, 18.2), p(21.2, 20.2)), headR = 1.8f),
        props = listOf(ground(20.9)),
    ),
    Pictogram.HOLLOW to PictoPose(
        StickFigure(head = p(6, 15.4), neck = p(8.2, 16.8), hip = p(12.4, 18.8),
            armNear = listOf(p(5.4, 15.4), p(2.6, 14)), armFar = listOf(p(5.6, 15.8), p(2.8, 14.6)),
            legNear = listOf(p(16.8, 17.2), p(21.2, 15.4)), legFar = listOf(p(16.8, 17.6), p(21, 16)), headR = 1.8f),
        props = listOf(ground(20.8)),
    ),
    Pictogram.SUPINE to PictoPose(
        StickFigure(head = p(4.6, 18.2), neck = p(6.8, 18.8), hip = p(13, 19),
            armNear = listOf(p(7.2, 15.2), p(7.4, 11.6)), armFar = listOf(p(6.6, 15.4), p(6.2, 11.8)),
            legNear = listOf(p(13.8, 13.8), p(18.2, 13.8)), legFar = listOf(p(17.2, 17.4), p(21.4, 16.2)), headR = 1.8f),
        props = listOf(ground(21)),
    ),
    Pictogram.L_SIT to PictoPose(
        StickFigure(head = p(11.2, 6.4), neck = p(11.2, 9), hip = p(11.6, 16),
            armNear = listOf(p(10.6, 15), p(10.2, 20.8)), armFar = listOf(p(10.2, 15), p(9.4, 20.8)),
            legNear = listOf(p(16.2, 16), p(21, 15.8)), legFar = listOf(p(16.2, 16.4), p(20.8, 16.4)), headR = 1.8f),
        props = listOf(ground(21.4)),
    ),
    Pictogram.QUADRUPED to PictoPose(
        StickFigure(head = p(5.4, 12.4), neck = p(7.8, 13.4), hip = p(15, 13.6),
            armNear = listOf(p(5, 13), p(2.2, 12.6)), armFar = listOf(p(7.8, 17), p(7.6, 20.4)),
            legNear = listOf(p(18.4, 13.4), p(21.8, 13.2)), legFar = listOf(p(15.2, 20.4), p(19.4, 20.4)), headR = 1.8f),
        props = listOf(ground(21)),
    ),
    Pictogram.PRONE to PictoPose(
        StickFigure(head = p(4.6, 16.8), neck = p(6.8, 18), hip = p(13.2, 18.8),
            armNear = listOf(p(4.4, 16.4), p(1.8, 15.4)), armFar = listOf(p(4.6, 16.8), p(2, 16.2)),
            legNear = listOf(p(17.2, 18.4), p(21.4, 17)), legFar = listOf(p(17.2, 18.8), p(21.2, 17.8)), headR = 1.8f),
        props = listOf(ground(21)),
    ),
    Pictogram.CHILD to PictoPose(
        StickFigure(head = p(8.2, 18.6), neck = p(10.6, 17.6), hip = p(17, 16.4),
            armNear = listOf(p(7, 19.6), p(3, 20)), armFar = listOf(p(7.2, 19.4), p(3.2, 19.6)),
            legNear = listOf(p(13.6, 20.2), p(19.6, 20.2)), legFar = listOf(p(13.6, 20.2), p(19.6, 20.2)), headR = 1.8f),
        props = listOf(ground(21)),
    ),
    Pictogram.SQUAT to PictoPose(
        StickFigure(head = p(12.6, 6.8), neck = p(11.8, 9.4), hip = p(9, 16.4),
            armNear = listOf(p(14.8, 10.6), p(17.8, 10.8)), armFar = listOf(p(14.4, 11), p(17.4, 11.6)),
            legNear = listOf(p(14.4, 15.6), p(12.8, 21.6)), legFar = listOf(p(13.8, 16.2), p(12, 21.6))),
        props = listOf(ground(22.4)),
    ),
    Pictogram.LUNGE to PictoPose(
        StickFigure(head = p(11.8, 5.8), neck = p(11.8, 8.4), hip = p(11.8, 15.4),
            armNear = listOf(p(12.6, 11.8), p(12.4, 15)), armFar = listOf(p(11.2, 11.8), p(11, 15)),
            legNear = listOf(p(17, 16), p(17.2, 21.6)), legFar = listOf(p(8.6, 20.2), p(4.6, 21))),
        props = listOf(ground(22.4)),
    ),
    Pictogram.HINGE to PictoPose(
        StickFigure(head = p(5.8, 9), neck = p(8.2, 10.2), hip = p(14.8, 12.4),
            armNear = listOf(p(8.8, 13.6), p(9, 17)), armFar = listOf(p(8, 13.6), p(8, 17)),
            legNear = listOf(p(14.2, 17), p(14.2, 21.6)), legFar = listOf(p(14.8, 17), p(15.4, 21.6)), headR = 1.8f),
        props = listOf(ground(22.4)),
        overlay = listOf(PictoProp.Dumbbell(p(8.6, 17.4))),
    ),
    Pictogram.BRIDGE to PictoPose(
        StickFigure(head = p(4.4, 18.8), neck = p(6.8, 19.2), hip = p(13.2, 14.6),
            armNear = listOf(p(9, 20), p(11.6, 20.2)), armFar = listOf(p(8.6, 20.2), p(11.2, 20.4)),
            legNear = listOf(p(17.6, 14.6), p(18.6, 20.4)), legFar = listOf(p(17.2, 15), p(17.8, 20.4)), headR = 1.8f),
        props = listOf(ground(21)),
    ),
    Pictogram.JUMP to PictoPose(
        StickFigure(head = p(12, 4), neck = p(12, 6.6), hip = p(12, 12.8),
            armNear = listOf(p(15, 4.8), p(16.4, 1.8)), armFar = listOf(p(9, 4.8), p(7.6, 1.8)),
            legNear = listOf(p(14.6, 15.4), p(13.2, 18.6)), legFar = listOf(p(9.4, 15.4), p(10.8, 18.6)), front = true),
        props = listOf(ground(22.6, 4, 20)),
        overlay = listOf(PictoProp.Line(p(7.2, 20.4), p(8, 21.4), 0.9f), PictoProp.Line(p(16.8, 20.4), p(16, 21.4), 0.9f)),
    ),
    Pictogram.CALF to PictoPose(
        StickFigure(head = p(12, 2.8), neck = p(12, 5.4), hip = p(12, 12.4),
            armNear = listOf(p(13.4, 9), p(13.8, 12.4)), armFar = listOf(p(10.6, 9), p(10.2, 12.4)),
            legNear = listOf(p(12.8, 16.6), p(13, 20.2)), legFar = listOf(p(11.2, 16.6), p(11, 20.2)), front = true),
        props = listOf(PictoProp.Box(p(8.6, 20.4), p(15.4, 22.6), 0.5f)),
    ),
    Pictogram.SEATED_STRETCH to PictoPose(
        StickFigure(head = p(15.8, 15.6), neck = p(13.6, 14.6), hip = p(8, 20),
            armNear = listOf(p(16.2, 17.2), p(18.8, 18.6)), armFar = listOf(p(15.8, 17.6), p(18.2, 19.2)),
            legNear = listOf(p(13.4, 20.2), p(19, 20.2)), legFar = listOf(p(13.4, 20.2), p(19, 20.2)), headR = 1.8f),
        props = listOf(ground(21)),
    ),
    Pictogram.ARMS_UP to PictoPose(
        StickFigure(head = p(12, 5.4), neck = p(12, 8), hip = p(12, 14.6),
            armNear = listOf(p(15, 5.6), p(17, 2.2)), armFar = listOf(p(9, 5.6), p(7, 2.2)),
            legNear = listOf(p(13, 18.2), p(13.4, 21.8)), legFar = listOf(p(11, 18.2), p(10.6, 21.8)), front = true),
        props = listOf(ground(22.6)),
    ),
    Pictogram.CARDIO to PictoPose(
        StickFigure(head = p(12, 5.2), neck = p(12, 7.8), hip = p(12, 14),
            armNear = listOf(p(15.8, 5.8), p(17.8, 2.8)), armFar = listOf(p(8.2, 5.8), p(6.2, 2.8)),
            legNear = listOf(p(14.4, 17.8), p(16.6, 21.6)), legFar = listOf(p(9.6, 17.8), p(7.4, 21.6)), front = true),
        props = listOf(ground(22.4)),
    ),
    Pictogram.ARM_CIRCLES to PictoPose(
        StickFigure(head = p(12, 4.6), neck = p(12, 7.4), hip = p(12, 14.4),
            armNear = listOf(p(15.4, 7.6), p(18.6, 7.6)), armFar = listOf(p(8.6, 7.6), p(5.4, 7.6)),
            legNear = listOf(p(13, 18.2), p(13.4, 21.8)), legFar = listOf(p(11, 18.2), p(10.6, 21.8)), front = true),
        props = listOf(ground(22.6)),
        overlay = listOf(PictoProp.Dot(p(19.6, 7.6), 2.2f, filled = false), PictoProp.Dot(p(4.4, 7.6), 2.2f, filled = false)),
    ),
    Pictogram.CLIMB to PictoPose(
        StickFigure(head = p(11.2, 6), neck = p(12.2, 8.6), hip = p(11.4, 15),
            armNear = listOf(p(14.6, 6), p(16.4, 2.8)), armFar = listOf(p(13.4, 11.8), p(16.4, 11.6)),
            legNear = listOf(p(15.2, 14.2), p(16.4, 17.6)), legFar = listOf(p(11.6, 19.2), p(16.2, 21.4))),
        props = listOf(PictoProp.Line(p(18, 1), p(18, 23), 1.3f)),
        overlay = listOf(PictoProp.Dot(p(17.1, 2.8), 0.9f), PictoProp.Dot(p(17.1, 11.6), 0.9f), PictoProp.Dot(p(17.1, 17.8), 0.9f), PictoProp.Dot(p(17.1, 21.6), 0.9f)),
    ),
    Pictogram.CAMPUS to PictoPose(
        StickFigure(head = p(13, 6.6), neck = p(14.2, 8.8), hip = p(14, 15.4),
            armNear = listOf(p(16.4, 5.8), p(17.6, 2.8)), armFar = listOf(p(16.4, 10.6), p(18.2, 8.6)),
            legNear = listOf(p(14.8, 18.8), p(14.2, 22.2)), legFar = listOf(p(13.4, 18.8), p(12.6, 22.2))),
        props = listOf(PictoProp.Line(p(19, 0.6), p(21.4, 23), 1.3f)) +
            listOf(2.8f, 5.8f, 8.8f, 11.8f, 14.8f).map { y -> val x = 19f + (y - 0.6f) / 22.4f * 2.4f; PictoProp.Line(PicPt(x - 1.6f, y), PicPt(x, y), 1.1f) },
    ),
    Pictogram.DYNO to PictoPose(
        StickFigure(head = p(12, 6.2), neck = p(12.8, 8.6), hip = p(11.8, 14.8),
            armNear = listOf(p(14.4, 5.4), p(16, 2.4)), armFar = listOf(p(13.8, 5.8), p(15.4, 2.8)),
            legNear = listOf(p(12.8, 18.4), p(13.6, 21.8)), legFar = listOf(p(11, 18.4), p(11.4, 21.8))),
        props = listOf(PictoProp.Line(p(17.6, 1), p(17.6, 23), 1.3f)),
        overlay = listOf(PictoProp.Dot(p(16.8, 2.2), 0.9f), PictoProp.Curve(p(6.4, 18), p(5, 12), p(8, 6.6), 0.9f), PictoProp.Curve(p(8.6, 19.2), p(7.6, 14.6), p(9.6, 10.6), 0.9f)),
    ),
)
