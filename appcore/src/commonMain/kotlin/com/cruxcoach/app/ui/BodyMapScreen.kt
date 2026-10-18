package com.cruxcoach.app.ui

import com.cruxcoach.app.bodymap.BodyArea
import com.cruxcoach.app.bodymap.BodyMapGeometry
import com.cruxcoach.app.bodymap.BodyMapSide
import com.cruxcoach.app.bodymap.BodyShape
import com.cruxcoach.app.bodymap.Capsule
import com.cruxcoach.app.bodymap.ExerciseBodyAreas
import com.cruxcoach.app.bodymap.GripPictograms
import com.cruxcoach.app.bodymap.MapShape
import com.cruxcoach.app.bodymap.Outline
import com.cruxcoach.app.bodymap.Oval
import com.cruxcoach.app.bodymap.areaMix
import com.cruxcoach.app.bodymap.gripFor
import com.cruxcoach.app.bodymap.isEdgeGrip
import com.cruxcoach.app.bodymap.toInjuryRegion
import com.cruxcoach.app.training.Tx
import com.cruxcoach.athlete.catalog.ExerciseCatalog
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseKind
import com.cruxcoach.athlete.model.Side
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/*
 * Swift face of the body map (Android ui/training/bodymap): the figures as plain shapes, the
 * layout and the hit test of Android's canvas, what an exercise trains and its grip picture.
 * SwiftUI only fills and strokes what these classes list.
 */

/** One drawing primitive; see `MapShape` for [kind] and [points]. */
class BodyShapeUi(
    val kind: String,
    /** Body map: `base` or `region`. Grip: `wall`, `hold`, `hole`, `hand`, `thumb`. */
    val role: String,
    val points: List<Double>,
    /** Stroke width in the drawing's units; 0 for a fill. */
    val width: Double,
    val radius: Double,
    val filled: Boolean,
)

/** One region part of a figure; [areaCode] is a body-area code (`lats`, `fingers` …). */
class BodyRegionUi(val areaCode: String, val shape: BodyShapeUi)

/** One figure (front or back) in figure units, 100 × 215, base parts first. */
class BodyFigureUi(val sideCode: String, val base: List<BodyShapeUi>, val regions: List<BodyRegionUi>)

class BodyPlacementUi(val sideCode: String, val dx: Double, val dy: Double)

/** Where the figures go in a canvas: figure units × [scale], shifted by each placement. */
class BodyLayoutUi(val scale: Double, val figureWidth: Double, val figureHeight: Double, val placements: List<BodyPlacementUi>)

/** A tap on the map. [bodySideCode]: `left` | `right` for paired areas, "" on the midline. */
class BodyHitUi(val found: Boolean, val areaCode: String, val bodySideCode: String)

/** How much an exercise trains an area: [value] 1 main, 0.5 involved; [mix] the accent share (Android `areaMix`). */
class BodyAreaValueUi(val areaCode: String, val value: Double, val mix: Double)

/**
 * The body map's geometry for SwiftUI (Android `BodyMapCanvas`): [figures] once, [layout] and
 * [hit] per canvas size. [sideCode] is `both`, `front` or `back`; [labels] leaves room under the
 * figures for "Front"/"Back" as Android does.
 */
class BodyMapKit internal constructor() {
    val figures: List<BodyFigureUi> get() = FIGURES

    /** The colour mix of an area that has [value] (0 → idle, 0.5 → involved tint, 1 → accent). */
    fun accentMix(value: Double): Double = if (value <= 0.0) 0.0 else areaMix(value.toFloat().coerceIn(0f, 1f)).toDouble()

    fun layout(width: Double, height: Double, sideCode: String, labels: Boolean): BodyLayoutUi {
        val l = BodyMapGeometry.layout(width.toFloat(), height.toFloat(), sides(sideCode), null, labels)
        return BodyLayoutUi(l.scale.toDouble(), BodyMapGeometry.FIGURE_W.toDouble(), BodyMapGeometry.FIGURE_H.toDouble(),
            l.placements.map { BodyPlacementUi(sideName(it.side), it.dx.toDouble(), it.dy.toDouble()) })
    }

    fun hit(width: Double, height: Double, sideCode: String, labels: Boolean, x: Double, y: Double): BodyHitUi {
        if (width <= 0.0 || height <= 0.0) return NO_HIT
        val l = BodyMapGeometry.layout(width.toFloat(), height.toFloat(), sides(sideCode), null, labels)
        val (area, side) = BodyMapGeometry.hit(l, x.toFloat(), y.toFloat()) ?: return NO_HIT
        return BodyHitUi(true, area.code, when (side) { Side.LEFT -> "left"; Side.RIGHT -> "right"; null -> "" })
    }

    /**
     * The injury region a tap on [areaCode] stands for (Android `BodyArea.toInjuryRegion`): an
     * InjuryRegion name, "" for an unknown area. Forearms count as the elbow, thighs as the knee.
     */
    fun injuryRegionCode(areaCode: String): String = BodyArea.ofCode(areaCode)?.toInjuryRegion()?.name ?: ""

    /** Android `bodyAreaLabel`: the string key of an area. */
    fun areaLabelKey(areaCode: String): String = "trb_area_$areaCode"

    /** "Body map: Mainly: Lats, Biceps. Also involved: Elbow." (Android `bodyMapDescription`). */
    fun describe(areas: List<BodyAreaValueUi>): TrText = description(areas.mapNotNull { a -> BodyArea.ofCode(a.areaCode)?.let { it to a.value.toFloat() } }.toMap())

    internal companion object {
        val NO_HIT = BodyHitUi(false, "", "")

        fun sides(code: String): List<BodyMapSide> = when (code) {
            "front" -> listOf(BodyMapSide.FRONT)
            "back" -> listOf(BodyMapSide.BACK)
            else -> listOf(BodyMapSide.FRONT, BodyMapSide.BACK)
        }

        fun sideName(side: BodyMapSide) = side.name.lowercase()

        fun description(highlights: Map<BodyArea, Float>): TrText {
            fun labels(areas: List<BodyArea>) = Tx.join(areas.sortedBy { it.ordinal }.map { Tx.t("trb_area_" + it.code) }, ", ")
            val main = highlights.filterValues { it >= ExerciseBodyAreas.PRIMARY }.keys.toList()
            val involved = highlights.filterValues { it > 0f && it < ExerciseBodyAreas.PRIMARY }.keys.toList()
            val parts = buildList {
                if (main.isNotEmpty()) add(Tx.t("trb_cd_main", Tx.x(labels(main))))
                if (involved.isNotEmpty()) add(Tx.t("trb_cd_involved", Tx.x(labels(involved))))
            }
            val title = Tx.t("trb_cd_title")
            return if (parts.isEmpty()) title else Tx.join(listOf(title, Tx.join(parts, " ")), ": ")
        }

        fun shape(s: MapShape) = BodyShapeUi(s.kind, s.role, s.points.map { it.toDouble() }, s.width.toDouble(), s.radius.toDouble(), s.filled)

        fun shape(s: BodyShape, role: String): BodyShapeUi = when (s) {
            is Oval -> BodyShapeUi("ellipse", role, listOf(s.cx, s.cy, s.rx, s.ry).map { it.toDouble() }, 0.0, 0.0, true)
            is Capsule -> BodyShapeUi("polygon", role, BodyMapGeometry.capsuleOutline(s).flatMap { listOf(it.first.toDouble(), it.second.toDouble()) }, 0.0, 0.0, true)
            is Outline -> BodyShapeUi("smooth", role, s.points.flatMap { listOf(it.first.toDouble(), it.second.toDouble()) }, 0.0, 0.0, true)
        }

        val FIGURES: List<BodyFigureUi> by lazy {
            listOf(BodyMapSide.FRONT, BodyMapSide.BACK).map { side ->
                val f = BodyMapGeometry.figures.getValue(side)
                BodyFigureUi(sideName(side), f.base.map { shape(it, "base") }, f.regions.map { BodyRegionUi(it.area.code, shape(it.shape, "region")) })
            }
        }

        val KIT = BodyMapKit()
    }
}

/** The grip picture of an exercise (Android `GripPictogram`), drawn in a 100 × 70 box. */
class GripPictureUi(
    /** half_crimp | open_hand | full_crimp | pinch | sloper | jug | pocket | bar */
    val gripCode: String,
    val labelKey: String,
    /** Edge depth in mm, -1 when the exercise has none. */
    val edgeMm: Int,
    /** True when the picture carries "20 mm" under it (edge grips with a depth). */
    val showsEdge: Boolean,
    val boxWidth: Double,
    val boxHeight: Double,
    val shapes: List<BodyShapeUi>,
    /** "Half crimp · 20 mm" for screen readers. */
    val accessibilityText: TrText,
)

class ExerciseVisualsState(
    val found: Boolean,
    val slug: String,
    /** Every area the exercise trains, in the body map's order. */
    val areas: List<BodyAreaValueUi>,
    /** The map for screen readers. */
    val mapText: TrText,
    /** Finger work only, as on Android's exercise page. */
    val hasGrip: Boolean,
    val grip: GripPictureUi?,
    val pictogram: PictogramUi?,
)

/**
 * What an exercise trains, as a picture (Android `ExerciseDetailScreen.VisualHeader`): the areas
 * for the body map, the tap text and, for finger work, the grip on the edge. Follows the catalogue,
 * so an own exercise added later shows once the catalogue has it.
 */
class ExerciseVisualsModel internal constructor(
    private val catalog: StateFlow<ExerciseCatalog>,
    private val slug: String,
    /** Loads the catalogue if no other screen has yet (Android: the service's `ensureReady`). */
    private val ensureLoaded: suspend () -> Unit,
) {
    val bodyMap: BodyMapKit = BodyMapKit.KIT

    val currentState: ExerciseVisualsState get() = map(catalog.value)

    fun watch(onState: (ExerciseVisualsState) -> Unit): Subscription {
        val scope = MainScope()
        scope.launch { runCatching { ensureLoaded() }.onFailure { println("ExerciseVisualsModel: catalogue not loaded: ${it.message}") } }
        scope.launch { catalog.collect { onState(map(it)) } }
        return Subscription { scope.cancel() }
    }

    /** The line under the map after a tap (Android `trb_selected_*`), or the hint for "". */
    fun tapText(areaCode: String): TrText {
        val area = BodyArea.ofCode(areaCode) ?: return Tx.t("trb_detail_tap")
        val value = currentState.areas.firstOrNull { it.areaCode == areaCode }?.value ?: 0.0
        val label = Tx.k("trb_area_" + area.code)
        return when {
            value >= 1.0 -> Tx.t("trb_selected_main", label)
            value > 0.0 -> Tx.t("trb_selected_involved", label)
            else -> Tx.t("trb_selected_none", label)
        }
    }

    private fun map(c: ExerciseCatalog): ExerciseVisualsState = try {
        visuals(c, slug)
    } catch (e: Exception) {
        println("ExerciseVisualsModel: not mapped: ${e.message}")
        empty(slug)
    }

    internal companion object {
        fun empty(slug: String) = ExerciseVisualsState(false, slug, emptyList(), Tx.t("trb_cd_title"), false, null, null)

        fun visuals(c: ExerciseCatalog, slug: String): ExerciseVisualsState {
            val def = c[slug] ?: return empty(slug)
            val areas = ExerciseBodyAreas.of(def)
            val values = areas.entries.sortedBy { it.key.ordinal }
                .map { (a, v) -> BodyAreaValueUi(a.code, v.toDouble(), BodyMapKit.KIT.accentMix(v.toDouble())) }
            val grip = gripFor(def, null)?.takeIf { def.category == ExerciseCategoryV2.FINGER || def.kind == ExerciseKind.HANG }
            val edge = def.defaults.edgeMm
            val picture = grip?.let { g ->
                val shows = edge != null && isEdgeGrip(g)
                GripPictureUi(
                    gripCode = g.name.lowercase(),
                    labelKey = g.labelKey,
                    edgeMm = edge ?: -1,
                    showsEdge = shows,
                    boxWidth = GripPictograms.BOX_W.toDouble(),
                    boxHeight = GripPictograms.BOX_H.toDouble(),
                    shapes = GripPictograms.shapes(g, edge?.toDouble()).map { BodyMapKit.shape(it) },
                    accessibilityText = if (shows) Tx.join(listOf(Tx.t(g.labelKey), Tx.t("trb_edge_mm", Tx.i(edge ?: 0)))) else Tx.t(g.labelKey),
                )
            }
            return ExerciseVisualsState(
                found = true,
                slug = slug,
                areas = values,
                mapText = BodyMapKit.description(areas),
                hasGrip = picture != null,
                grip = picture,
                pictogram = LibraryRows.pictogram(def),
            )
        }
    }
}
