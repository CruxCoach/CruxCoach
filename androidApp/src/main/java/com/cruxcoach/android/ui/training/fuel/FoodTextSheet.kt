package com.cruxcoach.android.ui.training.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cruxcoach.android.R
import com.cruxcoach.android.foodvision.VisionPrompt
import com.cruxcoach.athlete.model.Meal

/**
 * "Describe a meal" (FEAT-069): the user types or dictates what they ate;
 * the same review list as for a photo turns it into food log entries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodTextSheet(
    day: String,
    initialMeal: Meal,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    viewModel: FoodPhotoViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.phase) { if (state.phase is PhotoPhase.Saved) { viewModel.onClose(); onSaved() } }
    val close = { viewModel.onClose(); onDismiss() }

    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("fuel_text_sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.trf_describe_title), style = MaterialTheme.typography.titleLarge)
            when (val phase = state.phase) {
                is PhotoPhase.Review -> ReviewPane(phase, initialMeal, viewModel, onSave = { meal -> viewModel.save(day, meal) })
                is PhotoPhase.Analyzing -> AnalyzingPane(phase, viewModel::cancel)
                is PhotoPhase.Failed -> ErrorPane(phase.error, onRetry = viewModel::dismissError)
                PhotoPhase.Saved -> Unit
                else -> {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(VisionPrompt.MAX_MEAL_CHARS) },
                        label = { Text(stringResource(R.string.trf_qa_name)) },
                        placeholder = { Text(stringResource(R.string.trf_describe_placeholder)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("fuel_text_input"),
                    )
                    Text(stringResource(R.string.trf_describe_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        onClick = { viewModel.onText(text) },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("fuel_text_go"),
                    ) { Text(stringResource(R.string.trf_describe_go)) }
                }
            }
        }
    }
}
