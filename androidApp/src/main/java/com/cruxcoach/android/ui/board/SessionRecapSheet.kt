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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.contentColorOn
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
            ShareRecapButton(recap, layer)
        }
    }
}

/** Shares the card recorded in [layer] as an image through the phone's share menu. */
@Composable
internal fun ShareRecapButton(recap: SessionRecap, layer: GraphicsLayer, filled: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chooser = stringResource(R.string.board_session_recap_chooser)
    val failed = stringResource(R.string.board_session_recap_failed)
    var sharing by remember { mutableStateOf(false) }
    val onClick: () -> Unit = {
        sharing = true
        scope.launch {
            val ok = runCatching { shareRecap(context, layer, recap, chooser) }.isSuccess
            if (!ok) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
            sharing = false
        }
    }
    val content: @Composable RowScope.() -> Unit = {
        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.board_session_recap_share), fontWeight = FontWeight.Bold)
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = !sharing,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent, contentColor = CruxCoachDesign.colors.onBrandAccent),
            shape = RoundedCornerShape(12.dp),
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = !sharing,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            content = content,
        )
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
        // The brand moves below the date when both do not fit on one line.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                date,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 12.dp),
            )
            Text(
                RECAP_BRAND,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OrangeAccent,
                maxLines = 1,
            )
        }
        if (recap.boards.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recap.boards.forEach { board ->
                    BoardTag(board, colors[board] ?: MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val sends = stringResource(R.string.board_sends)
        val flashes = stringResource(R.string.board_session_flashes)
        val tries = stringResource(R.string.board_session_summary_attempts)
        val problems = stringResource(R.string.board_stats_problems)
        NumberGrid(
            perRow = 4,
            labels = listOf(sends, flashes, tries, problems),
            spacing = 8.dp,
            slack = 8.dp,
            cells = listOf<@Composable (Modifier) -> Unit>(
                { tile -> RecapTile("${recap.sends}", sends, null, tile) },
                { tile -> RecapTile("${recap.flashes}", flashes, null, tile) },
                { tile -> RecapTile("${recap.tries}", tries, null, tile) },
                { tile -> RecapTile("${recap.problems}", problems, null, tile) },
            ),
        )
        if (recap.topClimbs.isNotEmpty()) TopClimbs(recap.topClimbs, colors.takeIf { recap.boards.size > 1 })
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

/** The hardest problems of the session: grade, name, board and angle, a flash marked. */
@Composable
private fun TopClimbs(climbs: List<RecapClimb>, boardColors: Map<String, androidx.compose.ui.graphics.Color>?) {
    val flashLabel = stringResource(R.string.board_logbook_flash)
    // Large font: the flash joins the board line, so the name keeps the row's width.
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.board_session_recap_hardest),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        climbs.forEach { climb ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clearAndSetSemantics {
                        contentDescription = listOfNotNull(climb.grade, climb.name, "${climb.angle}°", flashLabel.takeIf { climb.flash })
                            .joinToString(", ")
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .widthIn(min = 48.dp)
                        .background(gradeColorForDifficulty(climb.difficulty), RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(climb.grade, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = contentColorOn(gradeColorForDifficulty(climb.difficulty)), maxLines = 1)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        climb.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (boardColors != null) {
                            Spacer(Modifier.size(6.dp).background(boardColors[climb.board] ?: MaterialTheme.colorScheme.onSurfaceVariant, androidx.compose.foundation.shape.CircleShape))
                        }
                        Text(
                            "${boardBrandLabel(climb.board)} · ${climb.angle}°",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (large && climb.flash) {
                            Text(
                                flashLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = triesRamp().first(),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (climb.flash && !large) {
                    Text(
                        flashLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CruxCoachDesign.colors.info,
                    )
                }
            }
        }
    }
}

/**
 * The sends of the session per grade as small columns, easiest on the left,
 * or as rows, easiest at the bottom, when the grades do not fit side by side.
 */
@Composable
private fun GradeStrip(grades: List<RecapGrade>) {
    val most = grades.maxOf { it.sends }.coerceAtLeast(1)
    val description = grades.joinToString(", ") { "${it.grade}: ${it.sends}" }
    val gradeStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
    val gradeWidth = widestText(grades.map { it.grade }, gradeStyle)
    BoxWithConstraints(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = description }) {
        val gap = 6.dp
        // Up to 32 dp per grade, narrower when a long session spans many grades.
        val column = minOf(32.dp, (maxWidth - gap * (grades.size - 1)) / grades.size)
        if (gradeWidth > column) {
            GradeBars(grades.asReversed(), most, gradeWidth + 8.dp, gradeStyle)
            return@BoxWithConstraints
        }
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
                        style = gradeStyle,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** [grades] as horizontal bars: the grade, its bar and its sends. */
@Composable
private fun GradeBars(grades: List<RecapGrade>, most: Int, gradeWidth: Dp, gradeStyle: TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        grades.forEach { grade ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(grade.grade, style = gradeStyle, maxLines = 1, modifier = Modifier.width(gradeWidth))
                Box(
                    Modifier
                        .weight(grade.sends.toFloat() / most)
                        .height(12.dp)
                        .background(
                            gradeColorForDifficulty(grade.difficultyInt.toDouble()),
                            RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
                        ),
                )
                if (grade.sends < most) Spacer(Modifier.weight((most - grade.sends).toFloat() / most))
                Text(
                    "${grade.sends}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/** The wordmark on the shared card: the brand, whatever the installed build is called. */
private const val RECAP_BRAND = "CruxCoach"
