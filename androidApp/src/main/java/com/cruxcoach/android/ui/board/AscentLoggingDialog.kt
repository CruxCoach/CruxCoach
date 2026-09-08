package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign

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
    val colors = CruxCoachDesign.colors
    val spacing = CruxCoachDesign.spacing
    val shapes = CruxCoachDesign.shapes
    AlertDialog(
        modifier = Modifier.testTag("ascent_logging_dialog"),
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isEditing) R.string.board_ascent_edit_title
                    else R.string.board_ascent_log_title,
                ),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.large),
            ) {
                if (!isEditing) {
                    Text(
                        stringResource(R.string.board_ascent_type),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.small),
                    ) {
                        FilterChip(
                            selected = isSend,
                            onClick = { onIsSendChanged(true) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = spacing.minimumTouchTarget)
                                .testTag("ascent_outcome_send"),
                            label = { Text(stringResource(R.string.board_ascent_send)) },
                            leadingIcon = {
                                Icon(Icons.Default.CheckCircle, contentDescription = null)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.brandAccent,
                                selectedLabelColor = colors.onBrandAccent,
                                selectedLeadingIconColor = colors.onBrandAccent,
                            ),
                        )
                        FilterChip(
                            selected = !isSend,
                            onClick = { onIsSendChanged(false) },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = spacing.minimumTouchTarget)
                                .testTag("ascent_outcome_attempt"),
                            label = { Text(stringResource(R.string.board_ascent_attempt)) },
                            leadingIcon = {
                                Icon(Icons.Default.Close, contentDescription = null)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.brandAccent,
                                selectedLabelColor = colors.onBrandAccent,
                                selectedLeadingIconColor = colors.onBrandAccent,
                            ),
                        )
                    }
                }

                Text(
                    stringResource(R.string.board_ascent_attempts),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                val attemptCountDescription = stringResource(
                    R.string.board_ascent_attempt_count,
                    bidCount,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(spacing.xSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconButton(
                            modifier = Modifier
                                .size(spacing.minimumTouchTarget)
                                .testTag("ascent_attempt_decrease"),
                            onClick = { onBidCountChanged(bidCount - 1) },
                            enabled = bidCount > 1,
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = stringResource(R.string.cd_decrease_attempt_count),
                            )
                        }
                        Text(
                            "$bidCount",
                            modifier = Modifier.semantics {
                                stateDescription = attemptCountDescription
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        IconButton(
                            modifier = Modifier
                                .size(spacing.minimumTouchTarget)
                                .testTag("ascent_attempt_increase"),
                            onClick = { onBidCountChanged(bidCount + 1) },
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_increase_attempt_count),
                            )
                        }
                    }
                }

                if (isSend) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.board_ascent_quality),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    val qualityDescription = if (quality == 0) {
                        stringResource(R.string.board_ascent_quality_none)
                    } else {
                        stringResource(R.string.board_ascent_quality_selected, quality)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup()
                            .semantics { stateDescription = qualityDescription },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        (1..5).forEach { star ->
                            val starDescription = stringResource(R.string.cd_stars, star)
                            IconButton(
                                onClick = {
                                    onQualityChanged(if (quality == star) 0 else star)
                                },
                                modifier = Modifier
                                    .size(spacing.minimumTouchTarget)
                                    .semantics {
                                        contentDescription = starDescription
                                        selected = quality == star
                                        role = Role.RadioButton
                                    }
                                    .testTag("ascent_quality_$star"),
                            ) {
                                Icon(
                                    if (star <= quality) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = null,
                                    tint = if (star <= quality) colors.caution
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }

                    FilterChip(
                        selected = isBenchmark,
                        onClick = { onIsBenchmarkChanged(!isBenchmark) },
                        modifier = Modifier
                            .heightIn(min = spacing.minimumTouchTarget)
                            .testTag("ascent_benchmark"),
                        label = { Text(stringResource(R.string.board_ascent_benchmark_attempt)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colors.brandAccent,
                            selectedLabelColor = colors.onBrandAccent,
                        ),
                    )
                }

                OutlinedTextField(
                    value = comment,
                    onValueChange = onCommentChanged,
                    label = { Text(stringResource(R.string.board_ascent_comment)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = shapes.medium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                modifier = Modifier
                    .heightIn(min = spacing.minimumTouchTarget)
                    .testTag("ascent_save"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.brandAccent,
                    contentColor = colors.onBrandAccent,
                ),
                shape = shapes.medium,
            ) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = spacing.minimumTouchTarget)
                    .testTag("ascent_cancel"),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
