package com.cruxcoach.android.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.theme.CruxCoachSpacing

@Composable
fun BoardBrowserErrorContent(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(CruxCoachSpacing.xLarge)
            .testTag("board_browser_error"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.board_browser_error_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.board_browser_error_hint),
            modifier = Modifier.padding(top = CruxCoachSpacing.small),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .padding(top = CruxCoachSpacing.large)
                .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
                .testTag("board_browser_error_retry"),
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
fun BoardBrowserLoadMoreError(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(CruxCoachSpacing.small)
            .testTag("board_browser_load_more_error"),
        horizontalArrangement = Arrangement.spacedBy(CruxCoachSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.board_browser_load_more_error),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier
                .heightIn(min = CruxCoachSpacing.minimumTouchTarget)
                .testTag("board_browser_load_more_retry"),
        ) {
            Text(stringResource(R.string.action_retry))
        }
    }
}
