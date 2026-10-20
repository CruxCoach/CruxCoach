package com.cruxcoach.android.ui.training.athlete.comeback

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign
import com.cruxcoach.android.ui.training.catalogLanguage
import com.cruxcoach.athlete.logic.ComebackTexts
import com.cruxcoach.athlete.logic.DayReading
import com.cruxcoach.athlete.logic.SourceKind
import com.cruxcoach.athlete.logic.Txt
import com.cruxcoach.athlete.model.ComebackSessionKind
import com.cruxcoach.athlete.model.Urgency
import com.cruxcoach.athlete.model.WarningSign

/** A shared comeback text in the app's language. */
@Composable
internal fun txt(t: Txt): String = t.of(catalogLanguage())

/** The notice on every comeback surface (owner 2026-10-20: "immer mit dem Hinweis"). */
@Composable
internal fun ComebackNotice(onSigns: (() -> Unit)?, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
        modifier = modifier.fillMaxWidth().testTag("comeback_notice"),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Info, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.cb_not_a_substitute), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onSigns != null) {
                TextButton(onClick = onSigns, modifier = Modifier.testTag("comeback_notice_signs")) { Text(stringResource(R.string.cb_warning_signs)) }
            }
        }
    }
}

/** 0–10 at the injured spot; tapping the chosen value again clears it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PainScale(label: String, value: Int?, tag: String, onChange: (Int?) -> Unit, hint: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (0..10).forEach { v ->
                FilterChip(
                    selected = value == v, onClick = { onChange(if (value == v) null else v) },
                    label = { Text(v.toString()) },
                    // The chosen value has to stand out in both themes (review shot 2026-10-20).
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CruxCoachDesign.colors.brandAccent,
                        selectedLabelColor = CruxCoachDesign.colors.onBrandAccent),
                    modifier = Modifier.testTag("${tag}_$v").semantics { contentDescription = "$label: $v" },
                )
            }
        }
    }
}

@Composable
internal fun sessionTitle(kind: ComebackSessionKind): String = stringResource(when (kind) {
    ComebackSessionKind.MOBILITY -> R.string.cb_session_mobility
    ComebackSessionKind.TENDON -> R.string.cb_session_tendon
    ComebackSessionKind.STRENGTH -> R.string.cb_session_strength
    ComebackSessionKind.REGION -> R.string.cb_session_region
    ComebackSessionKind.CLIMB -> R.string.cb_session_climb
    ComebackSessionKind.OWN -> R.string.cb_session_own
})

@Composable
internal fun sourceKindLabel(k: SourceKind): String = stringResource(when (k) {
    SourceKind.SYSTEMATIC_REVIEW -> R.string.cb_source_sr
    SourceKind.TRIAL -> R.string.cb_source_trial
    SourceKind.GUIDELINE -> R.string.cb_source_guideline
    SourceKind.COHORT -> R.string.cb_source_cohort
    SourceKind.CASE_SERIES -> R.string.cb_source_case_series
    SourceKind.REVIEW -> R.string.cb_source_review
    SourceKind.CONSENSUS -> R.string.cb_source_consensus
    SourceKind.LAB -> R.string.cb_source_lab
    SourceKind.PRACTITIONER -> R.string.cb_source_practitioner
})

/**
 * The 14-day diary as three plain lines (morning, pressure, session) on a 0–10 scale. Descriptive only: no
 * traffic-light colours (research/safety-regulation.md §5 SHOULD).
 */
@Composable
internal fun ComebackChart(readings: List<DayReading>, modifier: Modifier = Modifier) {
    val morning = MaterialTheme.colorScheme.primary
    val pressure = MaterialTheme.colorScheme.tertiary
    val session = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        if (readings.none { it.morningPain != null || it.pressurePain != null || it.sessionPain != null }) {
            Text(stringResource(R.string.cb_chart_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Row(Modifier.fillMaxWidth().height(120.dp)) {
        Column(Modifier.fillMaxHeight().padding(end = 4.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("10", "5", "0").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight().testTag("comeback_chart")) {
            val n = (readings.size - 1).coerceAtLeast(1)
            fun x(i: Int) = size.width * i / n
            fun y(v: Int) = size.height * (1f - v / 10f)
            listOf(0, 5, 10).forEach { v -> drawLine(grid, Offset(0f, y(v)), Offset(size.width, y(v)), 1f) }
            fun series(values: List<Int?>, color: Color, dashed: Boolean) {
                var last: Offset? = null
                values.forEachIndexed { i, v ->
                    if (v == null) { last = null; return@forEachIndexed }
                    val p = Offset(x(i), y(v))
                    last?.let { drawLine(color, it, p, 4f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null) }
                    drawCircle(color, 5f, p)
                    last = p
                }
            }
            series(readings.map { it.sessionPain }, session, dashed = true)
            series(readings.map { it.pressurePain }, pressure, dashed = false)
            series(readings.map { it.morningPain }, morning, dashed = false)
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
            Legend(morning, stringResource(R.string.cb_chart_morning))
            Legend(pressure, stringResource(R.string.cb_chart_pressure))
            Legend(session, stringResource(R.string.cb_chart_session))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(color) }
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The alert for ticked warning signs. Now: "Call 112" first, "Continue anyway" only as a text button. Within days
 * and routine: "OK" / "It has been checked". Never blocks (owner rule "warn, don't lock").
 */
@Composable
internal fun SignsAlert(signs: Set<WarningSign>, urgency: Urgency, onChecked: () -> Unit, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val language = catalogLanguage()
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("comeback_signs_alert_${urgency.name.lowercase()}"),
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text(ComebackTexts.alertTitle(urgency).of(language)) },
        text = {
            Column {
                Text(ComebackTexts.alertText(urgency).of(language))
                signs.filter { it.urgency == urgency }.forEach { s ->
                    ComebackTexts.signWhy(s)?.let { Text(it.of(language), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                }
                if (urgency == Urgency.DAYS) {
                    Text(stringResource(R.string.cb_out_of_hours), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            if (urgency == Urgency.EMERGENCY) {
                Button(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, "tel:112".toUri())) } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag("comeback_call_112"),
                ) { Text(stringResource(R.string.cb_call_112)) }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("comeback_signs_ok")) { Text(stringResource(R.string.cb_ok)) }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onChecked, modifier = Modifier.testTag("comeback_signs_checked")) { Text(stringResource(R.string.cb_has_been_checked)) }
                if (urgency == Urgency.EMERGENCY) {
                    TextButton(onClick = onContinue, modifier = Modifier.testTag("comeback_signs_continue")) { Text(stringResource(R.string.cb_continue_anyway)) }
                }
            }
        },
    )
}

/** The banner while ticked signs stay (after the alert was answered). */
@Composable
internal fun SignsBanner(signs: Set<WarningSign>, acknowledged: Boolean, onOpen: () -> Unit) {
    val language = catalogLanguage()
    Card(
        colors = CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer, contentColor = CruxCoachDesign.colors.onCautionContainer),
        modifier = Modifier.fillMaxWidth().testTag("comeback_signs_banner"),
        onClick = onOpen,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (acknowledged) stringResource(R.string.cb_banner_signs_checked)
                else stringResource(R.string.cb_banner_signs, signs.joinToString(" · ") { ComebackTexts.sign(it).of(language) }),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** A plain card for a hint with up to three actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HintCard(
    title: String?, text: String, tag: String,
    primary: Pair<String, () -> Unit>? = null, secondary: Pair<String, () -> Unit>? = null, tertiary: Pair<String, () -> Unit>? = null,
    caution: Boolean = false,
) {
    Card(
        colors = if (caution) CardDefaults.cardColors(containerColor = CruxCoachDesign.colors.cautionContainer,
            contentColor = CruxCoachDesign.colors.onCautionContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Column(Modifier.padding(16.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = if (title != null) 4.dp else 0.dp))
            if (primary != null || secondary != null || tertiary != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    primary?.let { (label, action) -> Button(onClick = action, modifier = Modifier.testTag("${tag}_primary")) { Text(label) } }
                    secondary?.let { (label, action) -> OutlinedButton(onClick = action, modifier = Modifier.testTag("${tag}_secondary")) { Text(label) } }
                    tertiary?.let { (label, action) -> TextButton(onClick = action, modifier = Modifier.testTag("${tag}_tertiary")) { Text(label) } }
                }
            }
        }
    }
}
