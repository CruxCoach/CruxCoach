package com.cruxcoach.android.ui.spraywall

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.payment.NostrProfileManager
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.gradeColorForDifficulty
import com.cruxcoach.domain.board.KilterGradeMapper
import com.cruxcoach.domain.spraywall.SprayHold
import com.cruxcoach.domain.spraywall.SprayHoldRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The size of a wall photo: the full width at the photo's [aspectRatio], but
 * at most [maxHeight] tall, so a portrait photo leaves room for what follows
 * it; the canvas letterboxes the photo in that box. (A heightIn after
 * aspectRatio does not cap anything: the aspect ratio wins.)
 */
fun Modifier.sprayPhotoSize(aspectRatio: Float, maxHeight: Dp): Modifier = fillMaxWidth().layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = min((width / aspectRatio.coerceIn(0.5f, 2f)).roundToInt(), maxHeight.roundToPx())
        .coerceIn(constraints.minHeight, constraints.maxHeight)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

/** A share of the screen height, for [sprayPhotoSize]. */
@Composable
fun screenHeightShare(share: Float): Dp = (LocalConfiguration.current.screenHeightDp * share).dp

/** A grade on the internal scale, shown in the climber's chosen scale. */
fun formatSprayGrade(difficulty: Int, scale: GradeScale): String = when (scale) {
    GradeScale.FRENCH -> KilterGradeMapper.difficultyToFont(difficulty.toDouble())
    GradeScale.V_SCALE -> KilterGradeMapper.difficultyToVScale(difficulty)
}

/**
 * The holds for a climb view: the climb's holds in their role colours, drawn
 * where they are in [holds] (the version shown) or, when they came off, dashed
 * at the place they were last seen ([lastKnown]); every other hold of [holds]
 * as a faint ring when [showOthers] is set.
 */
fun climbCanvasHolds(
    holds: List<SprayHold>,
    lastKnown: Map<Long, SprayHold>,
    climbHolds: Map<Long, SprayHoldRole>,
    showOthers: Boolean,
): List<SprayCanvasHold> {
    val shown = holds.associateBy { it.id }
    val out = ArrayList<SprayCanvasHold>(holds.size + climbHolds.size)
    for ((id, role) in climbHolds) {
        val here = shown[id]
        val h = here ?: lastKnown[id] ?: continue
        out += SprayCanvasHold(id, h.x, h.y, h.radius, if (here != null) SprayHoldLook.ROLE else SprayHoldLook.GONE, role, h.outline)
    }
    if (showOthers) holds.filter { it.id !in climbHolds }.forEach { out += SprayCanvasHold(it.id, it.x, it.y, it.radius, SprayHoldLook.FAINT, outline = it.outline) }
    return out
}

/** The name a wall's owner gave their CruxCoach account, or null (then the key's start is shown). Never throws. */
suspend fun NostrProfileManager.sprayOwnerName(pubkey: String): String? = withContext(Dispatchers.IO) {
    runCatching { getProfile(pubkey)?.displayName?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
}

/** The board browser's grade square: the grade on its difficulty colour, "?" on a plain square for a project. */
@Composable
fun SprayGradeBadge(difficulty: Int?, scale: GradeScale, modifier: Modifier = Modifier) {
    Surface(
        color = difficulty?.let { gradeColorForDifficulty(it.toDouble()) } ?: MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                difficulty?.let { formatSprayGrade(it, scale) } ?: "?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (difficulty != null) DarkBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** A screen title in the app bar: one line, so the largest font does not break it up letter by letter. */
@Composable
fun SprayBarTitle(text: String) = Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun SprayConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** Null for a dialog that only informs: then it has the one button. */
    dismissLabel: String? = stringResource(R.string.action_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("spray_confirm")) { Text(confirmLabel) }
        },
        dismissButton = dismissLabel?.let { label -> { TextButton(onClick = onDismiss) { Text(label) } } },
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
