package com.cruxcoach.android.ui.devcontact

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.ui.common.openUriOrToast
import kotlinx.coroutines.launch

/**
 * The one address for joining the community on Buzz. It stays while the
 * invites behind it rotate, so the app never links to an invite itself.
 */
internal const val COMMUNITY_JOIN_URL = "https://community.cruxcoach.org/join"

/** Settings → Community. Only opens the link in the browser; nothing is sent. */
@Composable
internal fun CommunityJoinSection(onJoin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.community_join_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        listOf(
            Icons.Outlined.Forum to R.string.community_point_talk,
            Icons.Outlined.Science to R.string.community_point_test,
            Icons.Outlined.HowToVote to R.string.community_point_shape,
        ).forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(onClick = onJoin, modifier = Modifier.fillMaxWidth().testTag("community_join")) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.community_join_action))
        }
        Text(
            stringResource(R.string.community_join_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The hint after a new report; its title confirms the report was sent. */
@Composable
internal fun CommunityReportHintDialog(
    title: String,
    onJoin: () -> Unit,
    onAlreadyMember: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("community_report_hint"),
        title = { Text(title) },
        text = { Text(stringResource(R.string.community_report_hint)) },
        confirmButton = {
            Button(onClick = onJoin, modifier = Modifier.testTag("community_report_hint_join")) {
                Text(stringResource(R.string.community_report_hint_join))
            }
        },
        // Two buttons in this slot: the dialog wraps all three when they do not fit in a row.
        dismissButton = {
            TextButton(onClick = onClose, modifier = Modifier.testTag("community_report_hint_close")) {
                Text(stringResource(R.string.action_close))
            }
            TextButton(onClick = onAlreadyMember, modifier = Modifier.testTag("community_report_hint_member")) {
                Text(stringResource(R.string.community_report_hint_member))
            }
        },
    )
}

/**
 * What follows a send on the bug report and feature request forms. A sent
 * report ends with the community hint, or, once the user chose "Already a
 * member", with the toast and the way back as before. Closing the hint hides
 * it for this report only. A failed send only shows its toast.
 */
@Composable
internal fun ReportSentHandler(
    sendSuccess: Boolean?,
    sentTitle: String,
    offersCommunity: suspend () -> Boolean,
    hideForGood: suspend () -> Unit,
    onResultConsumed: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val sendFailedMessage = stringResource(R.string.devcontact_send_failed)
    var showHint by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sendSuccess) {
        when (sendSuccess) {
            true -> {
                // Read before consuming: consuming restarts this effect.
                val offer = offersCommunity()
                onResultConsumed()
                if (offer) {
                    showHint = true
                } else {
                    Toast.makeText(context, sentTitle, Toast.LENGTH_SHORT).show()
                    onNavigateBack()
                }
            }
            false -> {
                Toast.makeText(context, sendFailedMessage, Toast.LENGTH_SHORT).show()
                onResultConsumed()
            }
            null -> Unit
        }
    }

    if (showHint) {
        // Each action closes the hint first, so a double tap cannot leave twice.
        val leave = { if (showHint) { showHint = false; onNavigateBack() } }
        CommunityReportHintDialog(
            title = sentTitle,
            onJoin = {
                uriHandler.openUriOrToast(context, COMMUNITY_JOIN_URL)
                leave()
            },
            onAlreadyMember = {
                if (showHint) {
                    showHint = false
                    scope.launch {
                        hideForGood()
                        onNavigateBack()
                    }
                }
            },
            onClose = leave,
        )
    }
}
