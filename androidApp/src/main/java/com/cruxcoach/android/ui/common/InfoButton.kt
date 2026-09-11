package com.cruxcoach.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R

/** Optional explanation, independently clickable beside an action or setting. */
@Composable
internal fun InfoButton(title: String, text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(48.dp)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.action_show_info, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (open) {
        AlertDialog(
            modifier = Modifier.testTag("info_dialog"),
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .testTag("info_dialog_content"),
                ) {
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }
}
