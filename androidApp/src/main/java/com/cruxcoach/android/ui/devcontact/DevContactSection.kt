package com.cruxcoach.android.ui.devcontact

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.notification.AnnouncementTagParser
import com.cruxcoach.android.ui.theme.OrangeAccent

@Composable
internal fun DevContactSection(
    unreadChat: Int,
    unreadBugs: Int,
    unreadFeatures: Int,
    unreadAnnouncements: Int = 0,
    crashReportOptIn: Boolean,
    announcementsEnabled: Boolean = true,
    categoryRelease: Boolean = true,
    categoryIssue: Boolean = true,
    categoryTip: Boolean = true,
    categoryGeneral: Boolean = true,
    queuedCount: Int = 0,
    onNavigateToChat: () -> Unit,
    onNavigateToAnnouncements: () -> Unit = {},
    onNavigateToBugReports: () -> Unit,
    onNavigateToFeatureRequests: () -> Unit,
    onNavigateToCrashReports: () -> Unit,
    onDonateClick: () -> Unit,
    onCrashReportOptInChange: (Boolean) -> Unit,
    onAnnouncementsEnabledChange: (Boolean) -> Unit = {},
    onCategoryChange: (category: String, enabled: Boolean) -> Unit = { _, _ -> },
    onDrainQueue: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DevContactRow(
            icon = Icons.AutoMirrored.Filled.Chat,
            label = stringResource(R.string.devcontact_messages),
            badge = unreadChat,
            onClick = onNavigateToChat
        )

        DevContactRow(
            icon = Icons.Filled.Campaign,
            label = stringResource(R.string.devcontact_announcements),
            badge = unreadAnnouncements,
            onClick = onNavigateToAnnouncements
        )

        DevContactRow(
            icon = Icons.Filled.BugReport,
            label = stringResource(R.string.devcontact_bug_reports),
            badge = unreadBugs,
            onClick = onNavigateToBugReports
        )

        DevContactRow(
            icon = Icons.Filled.Lightbulb,
            label = stringResource(R.string.devcontact_wishes),
            badge = unreadFeatures,
            onClick = onNavigateToFeatureRequests
        )

        DevContactRow(
            icon = Icons.Filled.ErrorOutline,
            label = stringResource(R.string.devcontact_crash_reports),
            badge = 0,
            onClick = onNavigateToCrashReports
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        DevContactRow(
            icon = Icons.Filled.Favorite,
            label = stringResource(R.string.devcontact_support_developer),
            badge = 0,
            onClick = onDonateClick
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.devcontact_send_crash_reports),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.devcontact_send_crash_reports_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = crashReportOptIn,
                onCheckedChange = onCrashReportOptInChange,
                colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.devcontact_notifications),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.devcontact_notifications_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = announcementsEnabled,
                onCheckedChange = onAnnouncementsEnabledChange,
                colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
            )
        }

        AnimatedVisibility(visible = announcementsEnabled) {
            Column(
                modifier = Modifier.padding(start = 32.dp)
            ) {
                CategoryToggle(
                    label = stringResource(R.string.announcements_badge_release),
                    description = stringResource(R.string.announcement_cat_release_desc),
                    checked = categoryRelease,
                    onCheckedChange = { onCategoryChange(AnnouncementTagParser.CATEGORY_RELEASE, it) }
                )
                CategoryToggle(
                    label = stringResource(R.string.announcements_badge_issue),
                    description = stringResource(R.string.announcement_cat_issue_desc),
                    checked = categoryIssue,
                    onCheckedChange = { onCategoryChange(AnnouncementTagParser.CATEGORY_ISSUE, it) }
                )
                CategoryToggle(
                    label = stringResource(R.string.announcements_badge_tip),
                    description = stringResource(R.string.announcement_cat_tip_desc),
                    checked = categoryTip,
                    onCheckedChange = { onCategoryChange(AnnouncementTagParser.CATEGORY_TIP, it) }
                )
                CategoryToggle(
                    label = stringResource(R.string.announcements_badge_general),
                    description = stringResource(R.string.announcement_cat_general_desc),
                    checked = categoryGeneral,
                    onCheckedChange = { onCategoryChange(AnnouncementTagParser.CATEGORY_GENERAL, it) }
                )
            }
        }

        if (queuedCount > 0) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.queue_count_label, queuedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = onDrainQueue) {
                    Text(
                        text = stringResource(R.string.queue_drain_button),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun DevContactRow(
    icon: ImageVector,
    label: String,
    badge: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = OrangeAccent,
            modifier = Modifier.size(24.dp)
        )

        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )

        if (badge > 0) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(OrangeAccent),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badge.toString(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CategoryToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = OrangeAccent)
        )
    }
}
