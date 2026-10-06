package com.cruxcoach.android.ui.training.bodymap

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cruxcoach.athlete.catalog.ExerciseCategoryV2
import com.cruxcoach.athlete.catalog.ExerciseDefinition

/**
 * A small picture for an exercise row or set view: finger exercises show
 * their grip on the edge, everything else the body map zoomed onto what the
 * exercise trains, on the side (front or back) where that shows. Decorative:
 * the row next to it carries the name.
 */
@Composable
fun ExerciseThumb(def: ExerciseDefinition, modifier: Modifier = Modifier) {
    val grip = remember(def.slug) { if (def.category == ExerciseCategoryV2.FINGER) gripFor(def, null) else null }
    val areas = remember(def.slug) { ExerciseBodyAreas.of(def) }
    val side = remember(def.slug) { ExerciseThumbs.sideFor(areas) }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier.size(48.dp),
    ) {
        if (grip != null) {
            GripCanvas(grip, def.defaults.edgeMm?.toDouble(), Modifier.fillMaxSize().padding(4.dp))
        } else {
            BodyMapCanvas(highlights = areas, side = side, focus = true, modifier = Modifier.fillMaxSize().padding(3.dp))
        }
    }
}

internal object ExerciseThumbs {
    /** The side that shows more of the trained areas, the main ones counting double. */
    fun sideFor(areas: Map<BodyArea, Float>): BodyMapSide {
        fun weight(side: BodyMapSide): Float {
            val visible = BodyMapGeometry.areasOn(side)
            return areas.filterKeys { it in visible }.values.sumOf { (if (it >= ExerciseBodyAreas.PRIMARY) 2.0 else 1.0) * it }.toFloat()
        }
        // A tie (pulling: lats behind, biceps in front) shows the back, where climbing strength reads best.
        return if (weight(BodyMapSide.BACK) >= weight(BodyMapSide.FRONT)) BodyMapSide.BACK else BodyMapSide.FRONT
    }
}
