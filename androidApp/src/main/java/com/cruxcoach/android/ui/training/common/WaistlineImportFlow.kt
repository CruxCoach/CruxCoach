package com.cruxcoach.android.ui.training.common

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.training.body.shortLabel
import com.cruxcoach.android.ui.training.metricLabel
import com.cruxcoach.athlete.logic.WaistlineImport
import com.cruxcoach.athlete.model.BodyMetric
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate

private val IMPORT_MIME_TYPES = arrayOf(
    "application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream",
)

private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

/**
 * The Waistline import as a screen offers it: [launch] opens the file picker, and [WaistlineImportHost] takes
 * the picked file through preview and import. Nutrition, body and the training settings offer the same flow.
 */
@Stable
class WaistlineImportLauncher internal constructor(
    private val picker: ManagedActivityResultLauncher<Array<String>, Uri?>,
    private val picked: MutableState<Uri?>,
    /** The screen's scope: the result's snackbar outlives the flow, which ends with it. */
    internal val scope: CoroutineScope,
) {
    fun launch() = picker.launch(IMPORT_MIME_TYPES)
    internal val uri: Uri? get() = picked.value
    internal fun done() { picked.value = null }
}

@Composable
fun rememberWaistlineImport(): WaistlineImportLauncher {
    val picked = rememberSaveable { mutableStateOf<Uri?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picked.value = uri }
    return remember(picker, scope) { WaistlineImportLauncher(picker, picked, scope) }
}

/**
 * Preview and import of the file picked through [launcher]; nothing while none is. The import's view model is
 * created only once a file was picked, so offering the import opens nothing (and screen tests need no Hilt).
 */
@Composable
fun WaistlineImportHost(launcher: WaistlineImportLauncher, snackbar: SnackbarHostState) {
    val uri = launcher.uri ?: return
    WaistlineImportFlow(uri, launcher, snackbar)
}

@Composable
private fun WaistlineImportFlow(
    uri: Uri,
    launcher: WaistlineImportLauncher,
    snackbar: SnackbarHostState,
    viewModel: WaistlineImportViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val quickAddName = stringResource(R.string.trb_import_quick_add)
    val dayTotalName = stringResource(R.string.trb_import_day_total_name)
    val source = uri.toString()
    LaunchedEffect(source) {
        // After a rotation the view model still has this file: it is not read a second time.
        if (viewModel.state.value.source == source) return@LaunchedEffect
        when (val read = withContext(Dispatchers.IO) { readImportFile(context, uri) }) {
            is ImportFile.Text -> viewModel.preview(source, read.value, quickAddName, dayTotalName = dayTotalName)
            ImportFile.TooLarge -> viewModel.tooLarge(source)
            ImportFile.Failed -> viewModel.preview(source, null)
        }
    }
    val outcome = state.outcome
    LaunchedEffect(outcome) {
        if (outcome == null) return@LaunchedEffect
        val text = outcomeText(resources, outcome)
        launcher.scope.launch { snackbar.showSnackbar(text, duration = SnackbarDuration.Long) }
        viewModel.outcomeShown()
        launcher.done()
    }
    state.preview?.let { preview ->
        ImportPreviewDialog(
            preview = preview,
            busy = state.busy,
            onDayFirst = viewModel::setDayFirst,
            onConfirm = viewModel::confirm,
            onDismiss = { viewModel.cancel(); launcher.done() },
        )
    }
}

private fun outcomeText(res: Resources, o: WaistlineImportOutcome): String = when (o) {
    is WaistlineImportOutcome.Imported -> buildList {
        if (o.bodyWritten + o.bodyKept > 0) add(res.getQuantityString(R.plurals.trb_import_done, o.bodyWritten, o.bodyWritten))
        if (o.bodyKept > 0) add(res.getQuantityString(R.plurals.trb_import_kept, o.bodyKept, o.bodyKept))
        if (o.bodySkipped > 0) add(res.getQuantityString(R.plurals.trb_import_skipped, o.bodySkipped, o.bodySkipped))
        if (o.foodWritten + o.foodDaysKept > 0) {
            add(res.getQuantityString(if (o.dayTotals) R.plurals.trb_import_day_totals_done else R.plurals.trb_import_food_done,
                o.foodWritten, o.foodWritten))
        }
        if (o.foodDaysKept > 0) add(res.getQuantityString(R.plurals.trb_import_food_days_kept, o.foodDaysKept, o.foodDaysKept))
        if (o.foodSkipped > 0) add(res.getQuantityString(R.plurals.trb_import_food_skipped, o.foodSkipped, o.foodSkipped))
    }.ifEmpty { listOf(res.getQuantityString(R.plurals.trb_import_done, 0, 0)) }.joinToString(" · ")
    is WaistlineImportOutcome.Empty -> res.getString(if (o.csv) R.string.trb_import_csv_empty else R.string.trb_import_empty)
    WaistlineImportOutcome.Failed -> res.getString(R.string.trb_import_failed)
    WaistlineImportOutcome.TooLarge -> res.getString(R.string.trb_import_too_large)
}

private sealed interface ImportFile {
    data class Text(val value: String) : ImportFile
    data object TooLarge : ImportFile
    data object Failed : ImportFile
}

/** Reads a picked file as UTF-8, refusing anything above 20 MB. */
private fun readImportFile(context: Context, uri: Uri): ImportFile = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_IMPORT_BYTES) return ImportFile.TooLarge
            buffer.write(chunk, 0, n)
        }
        ImportFile.Text(buffer.toString(Charsets.UTF_8.name()))
    } ?: ImportFile.Failed
}.getOrDefault(ImportFile.Failed)

@Composable
private fun CountRow(label: String, count: Int, tag: String) {
    Row(Modifier.fillMaxWidth().testTag(tag)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(count.toString(), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: WaistlineImportPreview,
    busy: Boolean,
    onDayFirst: (Boolean) -> Unit,
    onConfirm: (overwriteBody: Boolean, replaceFoodDays: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var overwrite by rememberSaveable { mutableStateOf(false) }
    var replaceDays by rememberSaveable { mutableStateOf(false) }
    val r = preview.result
    val food = r.food
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.testTag("body_import_dialog"),
        title = { Text(stringResource(R.string.trb_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (r.format == WaistlineImport.Format.JSON) R.string.trb_import_format_json else R.string.trb_import_format_csv),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val first = listOfNotNull(r.firstDay, food.firstDay).minOrNull()
                val last = listOfNotNull(r.lastDay, food.lastDay).maxOrNull()
                if (first != null && last != null) {
                    Text(stringResource(R.string.trb_import_range, LocalDate.parse(first).shortLabel(), LocalDate.parse(last).shortLabel()))
                }
                if (food.dayTotals && food.entries.isNotEmpty()) {
                    // A CSV: one sum per day, no single foods.
                    Text(stringResource(R.string.trb_import_day_totals), style = MaterialTheme.typography.labelLarge)
                    CountRow(stringResource(R.string.trb_import_food_days), food.entries.size, "body_import_day_totals")
                    Text(stringResource(R.string.trb_import_csv_food_hint), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("body_import_csv_food_hint"))
                } else if (food.entries.isNotEmpty()) {
                    Text(stringResource(R.string.trb_import_food_found), style = MaterialTheme.typography.labelLarge)
                    CountRow(stringResource(R.string.trb_import_food_entries), food.entries.size, "body_import_food_entries")
                    CountRow(stringResource(R.string.trb_import_food_days), food.days.size, "body_import_food_days")
                    if (food.foods.isNotEmpty()) CountRow(stringResource(R.string.trb_import_food_foods), food.foods.size, "body_import_food_foods")
                    if (food.recipeEntries > 0) CountRow(stringResource(R.string.trb_import_food_recipes), food.recipeEntries, "body_import_food_recipes")
                    if (food.skipped > 0) {
                        Text(pluralStringResource(R.plurals.trb_import_food_skipped, food.skipped, food.skipped),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (r.measurements.isNotEmpty()) {
                    Text(stringResource(R.string.trb_import_found), style = MaterialTheme.typography.labelLarge)
                    r.countsByMetric().entries
                        .sortedBy { BodyMetric.fromKey(it.key)?.ordinal ?: Int.MAX_VALUE }
                        .forEach { (key, n) ->
                            val label = r.customNames[key] ?: BodyMetric.fromKey(key)?.let { metricLabel(it) } ?: key
                            CountRow(label, n, "body_import_count_$key")
                        }
                }
                if (r.skipped > 0) {
                    Text(pluralStringResource(R.plurals.trb_import_skipped, r.skipped, r.skipped),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // Waistline 3.11.1 writes only today into the CSV (its issue #980).
                if (r.format == WaistlineImport.Format.CSV && r.csvDays <= 1) {
                    Text(stringResource(R.string.trb_import_csv_today_only), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("body_import_csv_today_only"))
                }
                if (preview.askDateOrder) {
                    Text(stringResource(R.string.trb_import_date_order), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.trb_import_date_order_hint), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = r.dayFirst != false, onClick = { onDayFirst(true) }, enabled = !busy,
                            label = { Text(stringResource(R.string.trb_import_day_month)) },
                            modifier = Modifier.testTag("body_import_day_first"))
                        FilterChip(selected = r.dayFirst == false, onClick = { onDayFirst(false) }, enabled = !busy,
                            label = { Text(stringResource(R.string.trb_import_month_day)) },
                            modifier = Modifier.testTag("body_import_month_first"))
                    }
                }
                if (preview.bodyConflicts > 0) {
                    Text(pluralStringResource(R.plurals.trb_import_conflicts, preview.bodyConflicts, preview.bodyConflicts),
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.trb_import_overwrite), modifier = Modifier.weight(1f))
                        Switch(checked = overwrite, onCheckedChange = { overwrite = it }, enabled = !busy,
                            modifier = Modifier.testTag("body_import_overwrite"))
                    }
                }
                if (preview.foodConflictDays > 0) {
                    Text(pluralStringResource(R.plurals.trb_import_food_conflicts, preview.foodConflictDays, preview.foodConflictDays),
                        style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.trb_import_food_replace), modifier = Modifier.weight(1f))
                        Switch(checked = replaceDays, onCheckedChange = { replaceDays = it }, enabled = !busy,
                            modifier = Modifier.testTag("body_import_food_replace"))
                    }
                    if (replaceDays) {
                        Text(stringResource(R.string.trb_import_food_replace_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(overwrite, replaceDays) }, enabled = !busy, modifier = Modifier.testTag("body_import_confirm")) {
                Text(stringResource(R.string.trb_import_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.tr_action_cancel)) }
        },
    )
}
