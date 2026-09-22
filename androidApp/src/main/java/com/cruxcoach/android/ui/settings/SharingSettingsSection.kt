package com.cruxcoach.android.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R

/**
 * FEAT-062 entry point in Settings, on the account page next to the account
 * keys — a sharing relationship is bound to the npub, so that is where people
 * look for it. Same destination-row pattern as every other settings link in
 * 0.2.3: title, info action for the summary, chevron.
 */
@Composable
internal fun SharingSettingsSection(onNavigateToSharing: () -> Unit) {
    SettingsDestinationRow(
        title = stringResource(R.string.sharing_settings_entry),
        summary = stringResource(R.string.sharing_settings_summary),
        icon = Icons.Outlined.Group,
        modifier = Modifier.testTag("settings_open_personal_sharing"),
        onClick = onNavigateToSharing,
    )
}
