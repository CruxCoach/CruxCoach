package com.cruxcoach.android.ui.spraywall

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cruxcoach.android.R
import com.cruxcoach.domain.spraywall.SprayCropBox
import com.cruxcoach.domain.spraywall.SprayCropRect
import com.cruxcoach.domain.spraywall.SprayWallGeometry
import kotlin.math.roundToInt

/**
 * Full-screen crop and rotate for the wall photo. Works on a preview bitmap;
 * [onDone] hands back the quarter turns and the crop (0..1 of the turned photo)
 * so the stored photo can be rendered once at full size.
 */
@Composable
fun SprayPhotoAdjustDialog(
    preview: Bitmap,
    onDone: (quarterTurns: Int, crop: SprayCropRect) -> Unit,
    onCancel: () -> Unit,
) {
    var turns by remember { mutableIntStateOf(0) }
    var crop by remember { mutableStateOf(SprayCropRect.FULL) }
    val turned = remember(preview, turns) {
        if (turns == 0) preview else Bitmap.createBitmap(preview, 0, 0, preview.width, preview.height, Matrix().apply { postRotate(90f * turns) }, true)
    }
    val image = remember(turned) { turned.asImageBitmap() }
    val handlePx = with(LocalDensity.current) { 28.dp.toPx() }
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Text(
                stringResource(R.string.spray_adjust_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .testTag("spray_adjust_canvas")
                        .pointerInput(turned) {
                            var handle: SprayCropBox.Handle? = null
                            detectDragGestures(
                                onDragStart = { p ->
                                    val fit = SprayWallGeometry.fit(this.size.width.toFloat(), this.size.height.toFloat(), turned.width, turned.height)
                                    val x = (p.x - fit.left) / fit.width
                                    val y = (p.y - fit.top) / fit.height
                                    handle = SprayCropBox.handleAt(crop, x, y, handlePx / fit.width, handlePx / fit.height)
                                },
                                onDrag = { change, amount ->
                                    val h = handle ?: return@detectDragGestures
                                    val fit = SprayWallGeometry.fit(this.size.width.toFloat(), this.size.height.toFloat(), turned.width, turned.height)
                                    crop = SprayCropBox.drag(crop, h, amount.x / fit.width, amount.y / fit.height)
                                    change.consume()
                                },
                                onDragEnd = { handle = null },
                            )
                        },
                ) {
                    val fit = SprayWallGeometry.fit(this.size.width, this.size.height, turned.width, turned.height)
                    drawImage(
                        image,
                        dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
                        dstSize = IntSize(fit.width.roundToInt(), fit.height.roundToInt()),
                    )
                    val l = fit.left + crop.left * fit.width
                    val t = fit.top + crop.top * fit.height
                    val r = fit.left + crop.right * fit.width
                    val b = fit.top + crop.bottom * fit.height
                    val shade = Color.Black.copy(alpha = 0.55f)
                    // Darken what the crop leaves out.
                    drawRect(shade, Offset(fit.left, fit.top), Size(fit.width, t - fit.top))
                    drawRect(shade, Offset(fit.left, b), Size(fit.width, fit.top + fit.height - b))
                    drawRect(shade, Offset(fit.left, t), Size(l - fit.left, b - t))
                    drawRect(shade, Offset(r, t), Size(fit.left + fit.width - r, b - t))
                    drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(strokePx))
                    val c = handlePx / 2.2f
                    listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b)).forEach { drawCircle(Color.White, c / 2f, it) }
                }
            }
            Text(
                stringResource(R.string.spray_adjust_hint),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { turns = (turns + 1) % 4; crop = SprayCropBox.rotateClockwise(crop) }, modifier = Modifier.testTag("spray_adjust_rotate")) {
                    Icon(Icons.Default.Rotate90DegreesCw, contentDescription = null)
                    Text(stringResource(R.string.spray_adjust_rotate), modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = { turns = 0; crop = SprayCropRect.FULL }) { Text(stringResource(R.string.action_reset), color = Color.White) }
                Box(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = Color.White) }
                Button(onClick = { onDone(turns, crop) }, modifier = Modifier.testTag("spray_adjust_done")) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}
