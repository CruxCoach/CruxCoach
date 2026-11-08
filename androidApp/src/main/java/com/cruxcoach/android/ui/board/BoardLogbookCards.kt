package com.cruxcoach.android.ui.board

import com.cruxcoach.android.ui.onboarding.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.data.repository.AscentWithClimb
import com.cruxcoach.domain.board.BoardBrand
import com.cruxcoach.domain.board.IntensityZones
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AscentCard(
    ascent: AscentWithClimb,
    gradeScale: GradeScale,
    zones: IntensityZones? = null,
    /** True flash = first-ever contact with this climb+angle went first
     *  try (full-history check by the VM) — a first-try send AFTER
     *  earlier-session attempts shows "1st try", not "Flash". */
    isTrueFlash: Boolean = false,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    highlightEdit: Boolean = false,
    /** False under a day header that already names the day. */
    showDate: Boolean = true,
) {
    val fontLarge = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    val grade = ascent.difficultyAverage?.let {
        GradeDisplayHelper.formatDifficulty(it, gradeScale)
    } ?: "?"

    val selectLabel = stringResource(R.string.board_logbook_select_entry, ascent.climbName)

    val containerColor = if (isSelected) {
        OrangeAccent.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    }

    val gradeBox: @Composable () -> Unit = {
        Surface(
            color = zoneColorForDifficulty(ascent.difficultyAverage ?: 0.0, zones),
            shape = RoundedCornerShape(8.dp),
            // Grows with a large font instead of cutting the grade.
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    grade,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = DarkBackground,
                    maxLines = 1
                )
            }
        }
    }
    val name: @Composable () -> Unit = {
        Text(
            ascent.climbName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = if (fontLarge) 2 else 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    val meta: @Composable () -> Unit = {
        // FlowRow (not Row): the meta items overflowed the card's
        // weighted column when a mirror badge was present, squashing the
        // last child to a sliver. FlowRow wraps to a second line instead.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
            // Board badge: tells a multi-board user at a glance which
            // board this send was on (Kilter / Tension / MoonBoard …).
            // Unobtrusive — same muted colour as the meta line.
            BoardBrandBadge(BoardBrand.fromWire(ascent.boardBrand))
            // Mirror indicator — placed right after the board badge (not
            // last) so it's prioritised and never the clipped child. A
            // filled accent pill so it reads as a distinct badge.
            if (ascent.isMirror) MirrorBadge()
            Text(
                "${ascent.angle}°",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showDate) {
                Text(
                    formatDate(ascent.climbedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    val comment: @Composable () -> Unit = {
        // Optional log comment — shown beneath the meta line so the
        // note the user wrote when logging is finally visible.
        // Straight quotes on purpose: this is code, not a localized
        // resource, so locale-specific quote glyphs don't apply.
        ascent.comment?.takeIf { it.isNotBlank() }?.let { comment ->
            Text(
                "\"$comment\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    val outcomeText = when {
        !ascent.isSend && ascent.bidCount > 1L -> stringResource(R.string.board_ascent_attempts_count, ascent.bidCount)
        !ascent.isSend -> stringResource(R.string.board_ascent_open_one)
        isTrueFlash -> "Flash"
        ascent.bidCount <= 1L -> stringResource(R.string.board_ascent_first_try)
        else -> stringResource(R.string.board_ascent_tries, ascent.bidCount)
    }
    val outcomeColor = when {
        !ascent.isSend -> ErrorRed
        isTrueFlash -> SuccessGreen
        else -> OrangeAccent
    }
    val outcomeStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    val outcomeWidth = widestText(listOf(outcomeText), outcomeStyle)
    val stars: @Composable () -> Unit = {
        ascent.quality?.let {
            Text(
                "$it★",
                style = MaterialTheme.typography.labelSmall,
                color = WarningYellow
            )
        }
    }
    val outcome: @Composable (Alignment.Horizontal) -> Unit = { alignment ->
        Column(horizontalAlignment = alignment) {
            Text(outcomeText, style = outcomeStyle, color = outcomeColor)
            stars()
        }
    }
    val edit: @Composable () -> Unit = {
        IconButton(onClick = onEdit, modifier = Modifier.size(48.dp).testTag("logbook_edit")
            .then(if (highlightEdit) Modifier.tourTarget(TourTarget.EDIT) else Modifier)) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.cd_edit),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
    val checkbox: @Composable () -> Unit = {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelect() },
            colors = CheckboxDefaults.colors(checkedColor = OrangeAccent),
            modifier = Modifier.size(36.dp).semantics { contentDescription = selectLabel }
        )
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Stacked at large fonts, and whenever the result would leave the name less than 96 dp.
        val large = fontLarge || maxWidth - ROW_FIXED_WIDTH - outcomeWidth < 96.dp
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("logbook_ascent_card"),
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (large) {
                // Large font: the controls on top, the text below at full width, so the name,
                // the result and the meta line never shrink to a column of single letters.
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        checkbox()
                        Spacer(modifier = Modifier.width(8.dp))
                        gradeBox()
                        Spacer(modifier = Modifier.weight(1f))
                        edit()
                    }
                    name()
                    // The result and its stars on one line, wrapping as whole items.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(outcomeText, style = outcomeStyle, color = outcomeColor)
                        stars()
                    }
                    meta()
                    comment()
                }
            } else {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    checkbox()
                    Spacer(modifier = Modifier.width(8.dp))
                    gradeBox()
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        name()
                        meta()
                        comment()
                    }
                    outcome(Alignment.End)
                    Spacer(modifier = Modifier.width(4.dp))
                    edit()
                }
            }
        }
    }
}

/** What an entry row needs beside its name and result: padding, checkbox, grade, gaps and the edit button. */
private val ROW_FIXED_WIDTH = (12 + 36 + 8 + 48 + 12 + 4 + 48 + 12).dp

/** Tiny, muted pill naming the board family a logbook entry was logged on.
 *  Uses [BoardBrand.displayName] (proper noun, not localized) so a newly
 *  promoted board needs no per-board string. Sits on the card's meta line
 *  next to angle/date. */
@Composable
internal fun BoardBrandBadge(brand: BoardBrand, layoutId: Long? = null) {
    // FEAT-023: Kilter Original and Homewall share the 'kilter' brand, so a
    // plain brand label can't tell them apart in a cross-board list. When the
    // caller supplies the climb's layout, split Homewall out explicitly.
    val label = if (
        brand == BoardBrand.KILTER &&
        layoutId == com.cruxcoach.android.data.BoardConstants.KILTER_HOMEWALL_LAYOUT.toLong()
    ) {
        stringResource(R.string.board_category_kilter_homewall)
    } else {
        brand.displayName
    }
    Surface(
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/** Filled accent pill marking a logbook entry as logged on the mirrored
 *  variant. Sits on the meta line next to the board badge; the solid tint
 *  makes the otherwise-thin SwapHoriz glyph clearly visible. */
@Composable
private fun MirrorBadge() {
    Surface(
        color = OrangeAccent.copy(alpha = 0.18f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Icon(
            Icons.Default.SwapHoriz,
            contentDescription = stringResource(R.string.cd_mirrored),
            tint = OrangeAccent,
            modifier = Modifier
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .size(18.dp)
        )
    }
}

@Composable
internal fun EmptyLogbookMessage() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.board_logbook_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.board_logbook_empty_message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A logbook day's date and entry count; [onRecap] adds the button that opens its recap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DayHeader(dateKey: String, count: Int, onRecap: (() -> Unit)? = null) {
    val dayNames = arrayOf(
        stringResource(R.string.day_mo), stringResource(R.string.day_tu),
        stringResource(R.string.day_we), stringResource(R.string.day_th),
        stringResource(R.string.day_fr), stringResource(R.string.day_sa),
        stringResource(R.string.day_su)
    )
    val label = try {
        val date = LocalDate.parse(dateKey)
        val dayName = dayNames[date.dayOfWeek.value - 1]
        val formatted = "${dateKey.substring(8, 10)}.${dateKey.substring(5, 7)}.${dateKey.substring(0, 4)}"
        "$dayName, $formatted"
    } catch (_: Exception) {
        formatDate(dateKey)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = OrangeAccent.copy(alpha = 0.25f),
            thickness = 1.dp
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The count moves below the date when both do not fit, instead of breaking.
            FlowRow(
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = OrangeAccent.copy(alpha = 0.85f)
                )
                Text(
                    text = pluralStringResource(R.plurals.board_logbook_day_entries, count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            if (onRecap != null) {
                IconButton(onClick = onRecap, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Outlined.Summarize,
                        contentDescription = stringResource(R.string.board_session_recap_open) + ", " + label,
                        tint = OrangeAccent.copy(alpha = 0.85f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

internal fun formatDate(isoDate: String): String {
    return try {
        // Locale-aware date instead of a hardcoded German dd.MM.yyyy. java.text.*
        // works on every API level (no java.time core-library desugaring needed).
        val parsed = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .parse(isoDate.take(10))!!
        java.text.DateFormat
            .getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
            .format(parsed)
    } catch (_: Exception) {
        isoDate.take(10)
    }
}
