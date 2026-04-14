package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cruxcoach.android.ui.common.RestTimerBannerSlot
import com.cruxcoach.android.ui.common.SyncStatusBannerSlot
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.BleStatusArea
import com.cruxcoach.android.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssessmentScreen(
    onSave: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: AssessmentViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_assessment_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                )
                RestTimerBannerSlot()
                SyncStatusBannerSlot()
                BleStatusArea()
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.settings_assessment_strength_test),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(
                stringResource(R.string.settings_assessment_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))

            OutlinedTextField(
                value = state.maxHang20mm,
                onValueChange = { viewModel.updateMaxHang(it) },
                label = { Text(stringResource(R.string.settings_assessment_max_hang)) },
                supportingText = { Text(stringResource(R.string.settings_assessment_max_hang_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("assessment_max_hang"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            OutlinedTextField(
                value = state.weightedPullup,
                onValueChange = { viewModel.updateWeightedPullup(it) },
                label = { Text(stringResource(R.string.settings_assessment_weighted_pullup)) },
                supportingText = { Text(stringResource(R.string.settings_assessment_weighted_pullup_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("assessment_weighted_pullup"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = state.pullupMaxReps,
                    onValueChange = { viewModel.updatePullupReps(it) },
                    label = { Text(stringResource(R.string.settings_assessment_pullup_max)) },
                    modifier = Modifier.weight(1f).testTag("assessment_pullup_reps"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = state.pushupMaxReps,
                    onValueChange = { viewModel.updatePushupReps(it) },
                    label = { Text(stringResource(R.string.settings_assessment_pushup_max)) },
                    modifier = Modifier.weight(1f).testTag("assessment_pushup_reps"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            OutlinedTextField(
                value = state.coreHoldSec,
                onValueChange = { viewModel.updateCoreHold(it) },
                label = { Text(stringResource(R.string.settings_assessment_core_hold)) },
                modifier = Modifier.fillMaxWidth().testTag("assessment_core_hold"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = InfoBlue.copy(alpha = 0.1f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    stringResource(R.string.settings_assessment_tip),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = InfoBlue
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { viewModel.saveAssessment(onSave) },
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().testTag("assessment_save_button"),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = DarkBackground,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.settings_assessment_save), fontWeight = FontWeight.Bold)
            }

            state.error?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
