package com.cruxcoach.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R

@Composable
fun backupRestoredSummary(ascents: Int, lists: Int): String = stringResource(
    R.string.settings_backup_restored,
    pluralStringResource(R.plurals.settings_backup_restored_ascents, ascents, ascents),
    pluralStringResource(R.plurals.settings_backup_restored_lists, lists, lists),
)
