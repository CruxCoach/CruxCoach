package com.cruxcoach.android.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/**
 * FEAT-062 entry point in Settings, next to the account keys — a sharing
 * relationship is bound to the npub, so that is where people look for it.
 */
@Composable
internal fun SharingSettingsSection(onNavigateToSharing: () -> Unit) {
    val openLabel = stringResource(R.string.sharing_cd_open)
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            stringResource(R.string.sharing_settings_entry),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.sharing_settings_summary),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = onNavigateToSharing,
            modifier = Modifier.semantics {
                contentDescription = openLabel
            },
        ) {
            Text(stringResource(R.string.sharing_title))
        }
    }
}
