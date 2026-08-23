package com.cruxcoach.android.ui.board

import com.cruxcoach.domain.board.QuantumBoardModel
import kotlin.math.abs

/** A point in the square Quantum board image/canvas, in physical pixels. */
internal data class QuantumBoardPoint(val x: Float, val y: Float)

private data class QuantumCalibration(
    val resizerX: Float,
    val resizerY: Float,
    val horizontalOffsetRatio: Float,
    val verticalOffsetRatio: Float,
    val verticalCurve: Float,
    val topCornerOffsetRatio: Float = 0f,
    val topCornerMinX: Float = Float.NEGATIVE_INFINITY,
    val topCornerMaxX: Float = Float.POSITIVE_INFINITY,
    val topCornerMaxVerticalProgress: Float = -1f,
)

private data class QuantumPadding(
    val left: Float,
    val right: Float,
    val bottom: Float,
    val top: Float,
    val marginLeft: Float = 0f,
)

/**
 * Pixel-faithful clean-room reconstruction of eWalls 2.0.14's
 * `getBoardDiodePosition`.
 *
 * The source diode coordinates are stored in CruxCoach as milli-units. The
 * original app does not use one global affine transform: every Quantum model
 * has its own calibration, three models curve Y slightly, and Belay also has
 * local top-corner/right-middle corrections. Keeping this as a pure function
 * lets the canvas, hit testing and regression tests share exactly one mapping.
 */
internal fun quantumBoardPoint(
    model: QuantumBoardModel,
    sourceXMilli: Long,
    sourceYMilli: Long,
    boardPixels: Float,
    compactPhone: Boolean,
    tablet: Boolean,
): QuantumBoardPoint {
    val calibration = model.calibration()
    val padding = model.padding(compactPhone = compactPhone, tablet = tablet)
    val left = boardPixels * padding.left
    val right = boardPixels * padding.right
    val bottom = boardPixels * padding.bottom
    val top = boardPixels * padding.top
    val marginLeft = boardPixels * padding.marginLeft
    val usableWidth = boardPixels - left - right
    val usableHeight = boardPixels - bottom

    val sourceX = sourceXMilli / 1000f
    val sourceY = sourceYMilli / 1000f
    val invertedY = 100f - sourceY
    val verticalProgress = invertedY / 100f
    val inverseProgress = 1f - verticalProgress

    val verticalCurve = usableHeight * calibration.verticalCurve
    val curveTerm = verticalCurve * verticalProgress * inverseProgress
    val topCornerTerm = if (
        verticalProgress <= calibration.topCornerMaxVerticalProgress &&
        (sourceX <= calibration.topCornerMinX || sourceX >= calibration.topCornerMaxX)
    ) {
        boardPixels * calibration.topCornerOffsetRatio * inverseProgress * inverseProgress
    } else {
        0f
    }
    val belayAdjustment = if (model == QuantumBoardModel.BELAY) {
        val edgeProgress = ((sourceX - 50f) / 18f).coerceIn(0f, 1f)
        val centerWeight = (1f - abs(verticalProgress - 0.5f) / 0.18f).coerceIn(0f, 1f)
        val strength = edgeProgress * centerWeight * centerWeight
        QuantumBoardPoint(
            x = boardPixels * -0.0065f * strength,
            y = boardPixels * -0.0153f * strength,
        )
    } else {
        QuantumBoardPoint(0f, 0f)
    }

    return QuantumBoardPoint(
        x = sourceX * usableWidth / calibration.resizerX +
            left + boardPixels * calibration.horizontalOffsetRatio + marginLeft + belayAdjustment.x,
        y = invertedY * usableHeight / calibration.resizerY +
            boardPixels * calibration.verticalOffsetRatio + curveTerm + topCornerTerm -
            belayAdjustment.y + top,
    )
}

/** eWalls uses a 20dp diode with a 4dp ring on all five models. */
internal const val QUANTUM_DIODE_DIAMETER_DP = 20f
internal const val QUANTUM_DIODE_STROKE_DP = 4f

/** Original touch acquisition radii: Belay is intentionally much tighter. */
internal fun quantumHitRadiusDp(model: QuantumBoardModel): Float =
    if (model == QuantumBoardModel.BELAY) 18f else 50f

private fun QuantumBoardModel.calibration(): QuantumCalibration = when (this) {
    QuantumBoardModel.XL -> QuantumCalibration(106.1251f, 107.6049f, 0.06049128205128205f, -0.007924358974358975f, 0.0067664f)
    QuantumBoardModel.L -> QuantumCalibration(106.1355f, 107.4252f, 0.060180256410256414f, -0.008901794871794872f, 0.0048777f)
    QuantumBoardModel.M -> QuantumCalibration(106.1307f, 107.4211f, 0.060133589743589747f, -0.008902307692307692f, 0.0047553f)
    QuantumBoardModel.S -> QuantumCalibration(108.9741f, 108.7541f, 0.062354102564102565f, 0.0460225641025641f, 0f)
    QuantumBoardModel.BELAY -> QuantumCalibration(
        108.5258f,
        111.4584f,
        0.07430256410256411f,
        -0.02787641025641026f,
        -0.0645251f,
        topCornerOffsetRatio = -0.006f,
        topCornerMinX = 25.5f,
        topCornerMaxX = 68f,
        topCornerMaxVerticalProgress = 0.25f,
    )
}

private fun QuantumBoardModel.padding(compactPhone: Boolean, tablet: Boolean): QuantumPadding = when (this) {
    QuantumBoardModel.XL,
    QuantumBoardModel.L,
    QuantumBoardModel.M,
    -> QuantumPadding(left = if (compactPhone && !tablet) -0.015f else 0f, right = 0.012f, bottom = 0f, top = 0f)

    QuantumBoardModel.S -> QuantumPadding(left = 0.069f, right = 0.305f, bottom = 0.01f, top = 0f)
    QuantumBoardModel.BELAY -> if (tablet) {
        QuantumPadding(left = -0.08f, right = -0.087f, bottom = -0.161f, top = 0f, marginLeft = -0.001f)
    } else {
        QuantumPadding(left = -0.08f, right = -0.083f, bottom = -0.16f, top = 0.5f, marginLeft = 0.003f)
    }
}
