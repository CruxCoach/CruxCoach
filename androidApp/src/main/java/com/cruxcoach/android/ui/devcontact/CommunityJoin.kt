package com.cruxcoach.android.ui.devcontact

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
            val style = MaterialTheme.typography.bodyLarge
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Centred on the first line, so a point that wraps at large font still reads as a list.
                Box(Modifier.height(with(LocalDensity.current) { style.lineHeight.toDp() }), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                }
                Text(stringResource(text), style = style)
            }
        }
        Button(onClick = onJoin, modifier = Modifier.fillMaxWidth().testTag("community_join")) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            // Centred when it wraps (German at large font), like a one-line label.
            Text(stringResource(R.string.community_join_action), textAlign = TextAlign.Center)
        }
        Text(
            stringResource(R.string.community_join_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The hint after a new report; its title confirms the report was sent, [text] fits the kind of report. */
@Composable
internal fun CommunityReportHintDialog(
    title: String,
    text: String,
    onJoin: () -> Unit,
    onAlreadyMember: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag("community_report_hint"),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            HintButtons {
                TextButton(onClick = onAlreadyMember, modifier = Modifier.testTag("community_report_hint_member")) {
                    Text(stringResource(R.string.community_report_hint_member))
                }
                TextButton(onClick = onClose, modifier = Modifier.testTag("community_report_hint_close")) {
                    Text(stringResource(R.string.community_report_hint_later))
                }
                Button(onClick = onJoin, modifier = Modifier.testTag("community_report_hint_join")) {
                    Text(stringResource(R.string.community_report_hint_join))
                }
            }
        },
    )
}

/**
 * The hint's three buttons: in one row when they fit, the rare "Already a
 * member" at the start; otherwise stacked at the end with Join on top. Never
 * split into a row of one and a row of two, where Close would move around.
 */
@Composable
private fun HintButtons(content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val buttons = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        val rowWidth = buttons.sumOf { it.width } + gap * (buttons.size - 1)
        val width = constraints.maxWidth
        if (rowWidth <= width) {
            val height = buttons.maxOf { it.height }
            layout(width, height) {
                buttons.first().placeRelative(0, (height - buttons.first().height) / 2)
                var x = width
                buttons.drop(1).reversed().forEach { x -= it.width; it.placeRelative(x, (height - it.height) / 2); x -= gap }
            }
        } else {
            val stacked = buttons.reversed()
            layout(width, stacked.sumOf { it.height }) {
                var y = 0
                stacked.forEach { it.placeRelative(width - it.width, y); y += it.height }
            }
        }
    }
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
    hintText: String,
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
            text = hintText,
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
