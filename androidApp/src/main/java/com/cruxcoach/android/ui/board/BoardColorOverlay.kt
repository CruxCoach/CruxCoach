package com.cruxcoach.android.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Shared by creator/logbook heatmaps and PaintBox: keep the board photo visible. */
internal fun DrawScope.drawBoardColorOverlay(
    center: Offset,
    markerScale: Float,
    color: Color,
    intensity: Float = 1f,
) {
    drawCircle(color.copy(alpha = 0.18f + 0.20f * intensity), markerScale * 4.5f, center)
    drawCircle(color.copy(alpha = 0.55f + 0.30f * intensity), markerScale * 1.8f, center)
}
