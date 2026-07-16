package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.OrangeAccent
import com.cruxcoach.android.ui.theme.SuccessGreen
import com.cruxcoach.android.ui.theme.WarningYellow

@Composable
internal fun AscentLoggingDialog(
    isEditing: Boolean = false,
    isSend: Boolean,
    bidCount: Int,
    quality: Int,
    comment: String,
    isBenchmark: Boolean = false,
    onIsBenchmarkChanged: (Boolean) -> Unit = {},
    onIsSendChanged: (Boolean) -> Unit,
    onBidCountChanged: (Int) -> Unit,
    onQualityChanged: (Int) -> Unit,
    onCommentChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isEditing) R.string.board_ascent_edit_title else R.string.board_ascent_log_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Send / Attempt toggle (only for new entries)
                if (!isEditing) {
                    Text(stringResource(R.string.board_ascent_type), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = isSend,
                            onClick = { onIsSendChanged(true) },
                            label = { Text(stringResource(R.string.board_ascent_send)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SuccessGreen.copy(alpha = 0.2f),
                                selectedLabelColor = SuccessGreen
                            )
                        )
                        FilterChip(
                            selected = !isSend,
                            onClick = { onIsSendChanged(false) },
                            label = { Text(stringResource(R.string.board_ascent_attempt)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                                selectedLabelColor = OrangeAccent
                            )
                        )
                    }
                }

                // Benchmark toggle
                Text(stringResource(R.string.board_ascent_benchmark_label), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = isBenchmark,
                        onClick = { onIsBenchmarkChanged(!isBenchmark) },
                        label = { Text(stringResource(R.string.board_ascent_benchmark_attempt)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = OrangeAccent.copy(alpha = 0.2f),
                            selectedLabelColor = OrangeAccent
                        )
                    )
                }

                // Bid count
                Text(stringResource(R.string.board_ascent_attempts, bidCount), style = MaterialTheme.typography.labelMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onBidCountChanged(bidCount - 1) },
                        enabled = bidCount > 1
                    ) { Text("-") }
                    Text(
                        "$bidCount",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalButton(
                        onClick = { onBidCountChanged(bidCount + 1) }
                    ) { Text("+") }
                }

                // Quality (only for sends, Kilter 1-5 stars)
                if (isSend) {
                    Text(stringResource(R.string.board_ascent_quality), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        (1..5).forEach { star ->
                            IconButton(
                                onClick = { onQualityChanged(if (quality == star) 0 else star) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    if (star <= quality) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = pluralStringResource(R.plurals.cd_stars, star, star),
                                    tint = if (star <= quality) WarningYellow
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }

                // Comment
                OutlinedTextField(
                    value = comment,
                    onValueChange = onCommentChanged,
                    label = { Text(stringResource(R.string.board_ascent_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAccent),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
