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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.Dp
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
    /** The layout, decided once for a whole list (see [logbookEntryLayout]); null decides for this card alone. */
    layout: EntryLayout? = null,
) {
    val fontLarge = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    val grade = ascent.difficultyAverage?.let {
        GradeDisplayHelper.formatDifficulty(it, gradeScale)
    } ?: "?"

    val selectLabel = stringResource(R.string.board_logbook_select_entry, ascent.climbName)

    // Opaque, so the attempts, quality and status labels keep their contrast on a selected card.
    val containerColor = if (isSelected) {
        CruxCoachDesign.colors.brandAccentContainer
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
    val outcomeText = outcomeLabel(ascent, isTrueFlash)
    val outcomeColor = when {
        !ascent.isSend -> ErrorRed
        isTrueFlash -> SuccessGreen
        isSelected -> CruxCoachDesign.colors.onBrandAccentContainer
        else -> OrangeAccent
    }
    val outcomeStyle = outcomeStyle()
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
    // The result and its stars on one line, wrapping as whole items.
    val outcomeLine: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically
        ) {
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
        val entryLayout = layout ?: entryLayout(maxWidth, outcomeWidth, fontLarge)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("logbook_ascent_card"),
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (entryLayout == EntryLayout.STACKED) {
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
                    outcomeLine()
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
                        if (entryLayout == EntryLayout.RESULT_BELOW) outcomeLine()
                        meta()
                        comment()
                    }
                    if (entryLayout == EntryLayout.RESULT_BESIDE) {
                        outcome(Alignment.End)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    edit()
                }
            }
        }
    }
}

/** How a logbook entry lays out, from the widest to the narrowest. */
internal enum class EntryLayout {
    /** One row, the result at its end. */
    RESULT_BESIDE,
    /** One row, the result under the name, when beside it the name would get too little room. */
    RESULT_BELOW,
    /** The controls on top and the text below at full width: at large fonts and on very narrow cards. */
    STACKED,
}

/** What an entry row needs beside its name and result: padding, checkbox, grade, gaps and the edit button. */
private val ROW_FIXED_WIDTH = (12 + 36 + 8 + 48 + 12 + 4 + 48 + 12).dp

/** The name keeps at least this much room in a row. */
private val MIN_NAME_WIDTH = 96.dp

private fun entryLayout(cardWidth: Dp, outcomeWidth: Dp, fontLarge: Boolean) = when {
    fontLarge -> EntryLayout.STACKED
    cardWidth - ROW_FIXED_WIDTH - outcomeWidth >= MIN_NAME_WIDTH -> EntryLayout.RESULT_BESIDE
    cardWidth - ROW_FIXED_WIDTH >= MIN_NAME_WIDTH -> EntryLayout.RESULT_BELOW
    else -> EntryLayout.STACKED
}

/**
 * The layout of a list's entries, decided once for all of them by their
 * widest result ("Offen · 12 Versuche"), so the cards of one list never mix
 * layouts.
 */
@Composable
internal fun logbookEntryLayout(entries: List<AscentWithClimb>, flashUuids: Set<String>, cardWidth: Dp): EntryLayout {
    val fontLarge = LocalDensity.current.fontScale >= LARGE_FONT_SCALE
    // A few distinct results stand for the whole list.
    val kinds = remember(entries, flashUuids) {
        entries.map { OutcomeKind(it.isSend, it.bidCount, it.uuid in flashUuids) }.distinct()
    }
    val labels = kinds.map { outcomeLabel(it.isSend, it.bidCount, it.flash) }
    val layout = entryLayout(cardWidth, widestText(labels, outcomeStyle()), fontLarge)
    // A list only ever moves to a narrower layout, so a later page with a
    // longer result re-lays the cards on screen at most once.
    var narrowest by remember(cardWidth) { mutableStateOf(layout) }
    SideEffect { if (layout > narrowest) narrowest = layout }
    return maxOf(layout, narrowest)
}

private data class OutcomeKind(val isSend: Boolean, val bidCount: Long, val flash: Boolean)

@Composable
private fun outcomeStyle() = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)

@Composable
private fun outcomeLabel(ascent: AscentWithClimb, isTrueFlash: Boolean) =
    outcomeLabel(ascent.isSend, ascent.bidCount, isTrueFlash)

/** "Flash", "Top · 3 Versuche", "Offen · 2 Versuche" …: an entry's result. */
@Composable
private fun outcomeLabel(isSend: Boolean, bidCount: Long, isTrueFlash: Boolean): String = when {
    !isSend && bidCount > 1L -> stringResource(R.string.board_ascent_attempts_count, bidCount)
    !isSend -> stringResource(R.string.board_ascent_open_one)
    isTrueFlash -> "Flash"
    bidCount <= 1L -> stringResource(R.string.board_ascent_first_try)
    else -> stringResource(R.string.board_ascent_tries, bidCount)
}

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
                    color = OrangeAccent
                )
                Text(
                    text = pluralStringResource(R.plurals.board_logbook_day_entries, count, count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
