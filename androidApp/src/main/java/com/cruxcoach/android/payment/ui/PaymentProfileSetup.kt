package com.cruxcoach.android.payment.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent

@Composable
internal fun PaymentProfileSetup(
    suggestedLightningAddress: String,
    onSetup: (displayName: String?, lightningAddress: String?) -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    var displayName by remember { mutableStateOf("") }
    var lightningAddress by remember { mutableStateOf(suggestedLightningAddress) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.payment_profile_setup_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.payment_profile_setup_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(stringResource(R.string.payment_profile_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = lightningAddress,
                    onValueChange = { lightningAddress = it },
                    label = { Text(stringResource(R.string.payment_profile_lightning_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSetup(
                        displayName.ifBlank { null },
                        lightningAddress.ifBlank { null }
                    )
                },
                colors = ButtonDefaults.textButtonColors(contentColor = OrangeAccent)
            ) {
                Text(stringResource(R.string.payment_profile_setup_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.payment_profile_setup_skip))
            }
        }
    )
}
