package com.cruxcoach.android.ui.board

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.DarkBackground
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.gradeColorForDifficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The recap of one logbook day: the card that is shared as an image, and the
 * share button below it. The card is drawn into a graphics layer as it is
 * shown, so the image is exactly what the climber sees.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionRecapSheet(recap: SessionRecap, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val layer = rememberGraphicsLayer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooser = stringResource(R.string.board_session_recap_chooser)
    val failed = stringResource(R.string.board_session_recap_failed)
    var sharing by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SessionRecapImage(recap, layer)
            Button(
                onClick = {
                    sharing = true
                    scope.launch {
                        val ok = runCatching { shareRecap(context, layer, recap, chooser) }.isSuccess
                        if (!ok) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                        sharing = false
                    }
                },
                enabled = !sharing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = DarkBackground),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.board_session_recap_share), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** The recap card on a solid frame, recorded into [layer] for sharing. */
@Composable
internal fun SessionRecapImage(recap: SessionRecap, layer: GraphicsLayer? = null) {
    val frame = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .fillMaxWidth()
            .then(
                if (layer != null) {
                    Modifier.drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    }
                } else {
                    Modifier
                },
            )
            .background(frame)
            .padding(12.dp),
    ) {
        SessionRecapCard(recap)
    }
}

private suspend fun shareRecap(context: Context, layer: GraphicsLayer, recap: SessionRecap, chooser: String) {
    val image = layer.toImageBitmap().asAndroidBitmap()
    val file = withContext(Dispatchers.IO) {
        // A hardware bitmap cannot be written out directly.
        val bitmap = if (image.config == Bitmap.Config.HARDWARE) image.copy(Bitmap.Config.ARGB_8888, false) else image
        File(context.cacheDir, "recap").apply { mkdirs() }
            .resolve("cruxcoach-session-${recap.date}.png")
            .also { out -> out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Lets the share sheet show the image as its preview.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, chooser))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionRecapCard(recap: SessionRecap) {
    val colors = boardSeriesColors(recap.loggedBoards + recap.boards)
    val card = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        .compositeOver(MaterialTheme.colorScheme.surface)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val date = recap.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    Column(
        Modifier
            .fillMaxWidth()
            .background(card, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                date,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent,
            )
        }
        if (recap.boards.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recap.boards.forEach { board ->
                    BoardTag(board, colors[board] ?: MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val tile = Modifier.weight(1f).fillMaxHeight()
            RecapTile("${recap.sends}", stringResource(R.string.board_sends), null, tile)
            RecapTile("${recap.flashes}", stringResource(R.string.board_session_flashes), null, tile)
            RecapTile("${recap.tries}", stringResource(R.string.board_session_summary_attempts), null, tile)
            RecapTile(
                recap.hardest?.grade ?: "–",
                stringResource(R.string.board_stats_glance_hardest),
                recap.hardest?.let { gradeColorForDifficulty(it.difficulty) },
                tile,
            )
        }
        if (recap.grades.isNotEmpty()) GradeStrip(recap.grades)
        if (recap.milestones.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recap.milestones.forEach { milestone ->
                    MilestoneBadge(milestone, colors.takeIf { recap.boards.size > 1 }, showDate = false)
                }
            }
        }
    }
}

@Composable
private fun RecapTile(value: String, label: String, tint: androidx.compose.ui.graphics.Color?, modifier: Modifier) {
    Column(
        modifier
            .background(
                (tint ?: MaterialTheme.colorScheme.onSurface).copy(alpha = if (tint != null) 0.18f else 0.05f),
                RoundedCornerShape(12.dp),
            )
            .padding(vertical = 10.dp, horizontal = 4.dp)
            .clearAndSetSemantics { contentDescription = "$label: $value" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The sends of the session per grade as small columns, easiest on the left. */
@Composable
private fun GradeStrip(grades: List<RecapGrade>) {
    val most = grades.maxOf { it.sends }.coerceAtLeast(1)
    val description = grades.joinToString(", ") { "${it.grade}: ${it.sends}" }
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        val gap = 6.dp
        // Up to 32 dp per grade, narrower when a long session spans many grades.
        val column = minOf(32.dp, (maxWidth - gap * (grades.size - 1)) / grades.size)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            grades.forEach { grade ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(column)) {
                    Text(
                        "${grade.sends}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .width(minOf(18.dp, column))
                            .height((6 + 42f * grade.sends / most).dp)
                            .background(
                                gradeColorForDifficulty(grade.difficultyInt.toDouble()),
                                RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                            ),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        grade.grade,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
