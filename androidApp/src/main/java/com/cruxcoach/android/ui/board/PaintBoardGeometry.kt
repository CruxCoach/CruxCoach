package com.cruxcoach.android.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.cruxcoach.data.repository.BoardPlacement
import com.cruxcoach.domain.board.QuantumBoardModel

internal fun paintBoardPoint(placement: BoardPlacement, size: Size, left: Float, bottom: Float,
    width: Float, height: Float, quantum: QuantumBoardModel?, compact: Boolean, tablet: Boolean): Offset {
    val point = quantum?.let { quantumBoardPoint(it, placement.x, placement.y, size.width, compact, tablet) }
    return if (point != null) Offset(point.x, point.y)
    else Offset((placement.x.toFloat() - left) * size.width / width,
        size.height - (placement.y.toFloat() - bottom) * size.height / height)
}
