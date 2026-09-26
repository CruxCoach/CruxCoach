package com.cruxcoach.android.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
        // Save in the owning composition so dialog recreation retains the
        // reading position; closing and reopening starts a fresh explanation.
        val scrollState = rememberScrollState()
        AlertDialog(
            modifier = Modifier.testTag("info_dialog"),
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(scrollState)
                        .testTag("info_dialog_content"),
                ) {
                    InfoText(text)
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

/**
 * Help copy uses blank lines between sections. Within a section, a single
 * newline separates its short heading from its body. Plain paragraphs need no
 * heading. This keeps resources readable wherever plain text is also used.
 */
@Composable
internal fun InfoText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        text.split("\n\n").filter { it.isNotBlank() }.forEach { section ->
            val headingEnd = section.indexOf('\n')
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (headingEnd > 0) {
                    Text(
                        text = section.substring(0, headingEnd),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(section.substring(headingEnd + 1), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(section, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Section heading with optional help; wraps without squeezing the info target. */
@Composable
internal fun InfoHeading(title: String, text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        InfoButton(title, text)
    }
}
