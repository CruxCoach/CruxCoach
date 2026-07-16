package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.data.GradeScale
import com.cruxcoach.android.ui.theme.*
import com.cruxcoach.android.util.GradeDisplayHelper
import com.cruxcoach.util.GradeConverter

@Composable
internal fun ProfileSection(
    profile: ProfileFormState,
    gradeScale: GradeScale,
    isSaving: Boolean,
    saveSuccess: Boolean,
    error: String?,
    onNameChange: (String) -> Unit,
    onAgeChange: (String) -> Unit,
    onWeightChange: (String) -> Unit,
    onHeightChange: (String) -> Unit,
    onSessionsPerWeekChange: (Int) -> Unit,
    onGradeUp: () -> Unit,
    onGradeDown: () -> Unit,
    onSaveProfile: () -> Unit
) {
    Text(
        stringResource(R.string.settings_profile_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    OutlinedTextField(
        value = profile.name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.settings_profile_name)) },
        modifier = Modifier.fillMaxWidth().testTag("settings_name"),
        singleLine = true,
        shape = RoundedCornerShape(12.dp)
    )

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = profile.age,
            onValueChange = onAgeChange,
            label = { Text(stringResource(R.string.settings_profile_age)) },
            modifier = Modifier.weight(1f).testTag("settings_age"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        OutlinedTextField(
            value = profile.weightKg,
            onValueChange = onWeightChange,
            label = { Text(stringResource(R.string.settings_profile_weight)) },
            modifier = Modifier.weight(1f).testTag("settings_weight"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = profile.heightCm,
            onValueChange = onHeightChange,
            label = { Text(stringResource(R.string.settings_profile_height)) },
            modifier = Modifier.weight(1f).testTag("settings_height"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_profile_sessions_per_week, profile.sessionsPerWeek), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = profile.sessionsPerWeek.toFloat(),
                onValueChange = { onSessionsPerWeekChange(it.toInt()) },
                valueRange = 2f..4f,
                steps = 1,
                colors = SliderDefaults.colors(thumbColor = OrangeAccent, activeTrackColor = OrangeAccent)
            )
        }
    }

    // Grade selector
    val gradeDisplay = GradeDisplayHelper.formatByIndexWithAlt(profile.maxGradeIndex, gradeScale)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.settings_profile_max_grade), style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(
            onClick = onGradeDown,
            enabled = profile.maxGradeIndex > 0
        ) { Text("-") }
        Text(
            gradeDisplay,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = OrangeAccent,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        FilledTonalButton(
            onClick = onGradeUp,
            enabled = profile.maxGradeIndex < GradeConverter.MAX_INDEX
        ) { Text("+") }
    }

    // Save button
    Button(
        onClick = onSaveProfile,
        enabled = !isSaving && profile.name.isNotBlank(),
        modifier = Modifier.fillMaxWidth().testTag("settings_profile_save"),
        colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = DarkBackground,
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            if (saveSuccess) stringResource(R.string.settings_profile_saved) else stringResource(R.string.settings_profile_save),
            fontWeight = FontWeight.Bold
        )
    }

    if (saveSuccess) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.settings_profile_updated), style = MaterialTheme.typography.bodySmall, color = SuccessGreen)
        }
    }

    error?.let { errorText ->
        Text(errorText, style = MaterialTheme.typography.bodySmall, color = ErrorRed)
    }
}
