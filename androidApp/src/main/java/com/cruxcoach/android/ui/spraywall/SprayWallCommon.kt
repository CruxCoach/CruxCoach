package com.cruxcoach.android.ui.spraywall

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayHoldRole

/** A grade on the internal scale, shown in the climber's chosen scale. */
fun formatSprayGrade(difficulty: Int, scale: GradeScale): String = when (scale) {
    GradeScale.FRENCH -> KilterGradeMapper.difficultyToFont(difficulty.toDouble())
    GradeScale.V_SCALE -> KilterGradeMapper.difficultyToVScale(difficulty)
}

/**
 * The wall's holds for a climb view: the climb's holds in their role colours
 * (dashed when they came off the wall), every other hold on the wall as a
 * faint ring when [showOthers] is set.
 */
fun climbCanvasHolds(
    wallHolds: List<SprayHold>,
    climbHolds: Map<Long, SprayHoldRole>,
    showOthers: Boolean,
): List<SprayCanvasHold> = wallHolds.mapNotNull { h ->
    val role = climbHolds[h.id]
    when {
        role != null -> SprayCanvasHold(h.id, h.x, h.y, h.radius, if (h.isOnWall) SprayHoldLook.ROLE else SprayHoldLook.GONE, role)
        showOthers && h.isOnWall -> SprayCanvasHold(h.id, h.x, h.y, h.radius, SprayHoldLook.FAINT)
        else -> null
    }
}

@Composable
fun SprayConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = stringResource(R.string.action_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("spray_confirm")) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@Composable
fun SprayDiscardDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    SprayConfirmDialog(
        title = stringResource(R.string.spray_discard_title),
        text = stringResource(R.string.spray_discard_text),
        confirmLabel = stringResource(R.string.spray_discard_confirm),
        dismissLabel = stringResource(R.string.spray_discard_keep),
        onConfirm = onDiscard,
        onDismiss = onKeep,
    )
}
