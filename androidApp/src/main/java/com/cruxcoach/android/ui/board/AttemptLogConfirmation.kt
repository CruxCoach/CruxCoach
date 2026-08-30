package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachDesign

/** Isolated durable-result candidate; production currently confirms with a Snackbar. */
@Composable
internal fun AttemptLogConfirmation(
    climbName: String,
    gradeLabel: String,
    angle: Int,
    isSend: Boolean,
    onViewLogbook: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = CruxCoachDesign.spacing
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(spacing.large)
            .testTag("attempt_log_confirmation"),
        color = CruxCoachDesign.colors.positiveContainer,
        contentColor = CruxCoachDesign.colors.onPositiveContainer,
        shape = CruxCoachDesign.shapes.large,
    ) {
        Column(
            modifier = Modifier.padding(spacing.large),
            verticalArrangement = Arrangement.spacedBy(spacing.medium),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = CruxCoachDesign.colors.positive,
                )
                Text(
                    text = stringResource(
                        if (isSend) R.string.board_ascent_send_logged
                        else R.string.board_ascent_attempt_logged,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(
                    R.string.board_ascent_success_body,
                    climbName,
                    gradeLabel,
                    angle,
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = onViewLogbook,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.minimumTouchTarget)
                    .testTag("attempt_log_view_logbook"),
            ) {
                Text(stringResource(R.string.board_ascent_view_logbook))
            }
        }
    }
}
