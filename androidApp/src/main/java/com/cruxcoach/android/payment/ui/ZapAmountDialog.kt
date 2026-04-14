package com.cruxcoach.android.payment.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.ui.theme.OrangeAccent

private val PRESET_AMOUNTS = listOf(
    1_000L to "1k",
    5_000L to "5k",
    10_000L to "10k",
    21_000L to "21k",
    50_000L to "50k"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ZapAmountDialog(
    private: Boolean,
    onSend: (amountSats: Long, message: String) -> Unit,
    onDismiss: () -> Unit,
    isSending: Boolean
) {
    var selectedAmount by remember { mutableLongStateOf(5_000L) }
    var customAmount by remember { mutableStateOf("") }
    var isCustom by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    val effectiveAmount = if (isCustom) {
        customAmount.toLongOrNull() ?: 0L
    } else {
        selectedAmount
    }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { Text(if (`private`) "Lightning Spende" else "Lightning Zap") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Betrag in Sats:",
                    style = MaterialTheme.typography.bodyMedium
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PRESET_AMOUNTS.forEach { (amount, label) ->
                        FilterChip(
                            selected = !isCustom && selectedAmount == amount,
                            onClick = {
                                selectedAmount = amount
                                isCustom = false
                                customAmount = ""
                            },
                            label = { Text("$label sats") },
                            enabled = !isSending,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                selectedLabelColor = OrangeAccent
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = customAmount,
                    onValueChange = { value ->
                        customAmount = value.filter { it.isDigit() }
                        if (customAmount.isNotEmpty()) {
                            isCustom = true
                        }
                    },
                    label = { Text("Eigener Betrag (Sats)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = {
                        Text(
                            if (`private`) "Nachricht (optional, verschlüsselt)"
                            else "Nachricht (optional, öffentlich)"
                        )
                    },
                    singleLine = true,
                    enabled = !isSending,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(effectiveAmount, message) },
                enabled = !isSending && effectiveAmount > 0,
                colors = ButtonDefaults.textButtonColors(contentColor = OrangeAccent)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = OrangeAccent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Senden")
                }
            }
        },
        dismissButton = {
            if (!isSending) {
                TextButton(onClick = onDismiss) {
                    Text("Abbrechen")
                }
            }
        }
    )
}
