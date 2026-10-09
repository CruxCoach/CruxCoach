package com.cruxcoach.android.ui.training.bodymap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.cruxcoach.athlete.catalog.ExerciseDefinition

/**
 * A small picture for an exercise row or set view: a movement pictogram – the
 * exercise's key position with its equipment – on a tile in its category's
 * colour (owner 2026-10-09: the zoomed body map read as blobs at this size;
 * the body map stays on the exercise page). Decorative: the row next to it
 * carries the name.
 */
@Composable
fun ExerciseThumb(def: ExerciseDefinition, modifier: Modifier = Modifier) {
    val pictogram = remember(def.slug) { ExercisePictograms.of(def) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = pictogramColor(def.category, dark)
    Box(
        modifier = modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (dark) 0.16f else 0.11f).compositeOver(MaterialTheme.colorScheme.surface)),
    ) {
        PictogramCanvas(pictogram, color, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            Modifier.fillMaxSize().padding(4.dp))
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
