package com.cruxcoach.android.ui.sharing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.MarmotStatus
import com.cruxcoach.android.sharing.PersonState
import com.cruxcoach.android.sharing.SharingNames
import com.cruxcoach.android.sharing.SharingPersonDetail
import com.cruxcoach.android.sharing.SharingRecord
import com.cruxcoach.android.sharing.VisibleState
import com.cruxcoach.android.ui.common.InfoButton
import com.cruxcoach.android.ui.common.InfoHeading
import com.cruxcoach.android.ui.settings.SettingsChoices
import com.cruxcoach.android.ui.settings.SettingsSectionCard
import com.cruxcoach.android.ui.settings.SettingsToggleRow
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.SharePeriod
import com.cruxcoach.domain.sharing.ShareTemplate
import com.cruxcoach.domain.sharing.TemplateId

private const val COLLAPSED_ITEMS = 10

/**
 * One person: the one switch for the own direction, template, categories and
 * period with visible exceptions, exactly what they receive (with a switch per
 * record), what they share back, and ending with a single confirmation.
 */
@Composable
internal fun PersonPage(
    detail: SharingPersonDetail,
    templates: List<ShareTemplate>,
    busy: Boolean,
    actions: SharingActions,
    onRemoved: () -> Unit,
) {
    val friend = detail.friend
    val person = friend.person
    val name = friend.displayName
    SettingsSectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(SharingLabels.state(friend.visible)), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }.testTag("sharing_person_state"))
                Text(SharingNames.shortId(friend.peer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InfoButton(stringResource(R.string.sharing_states_title), stringResource(R.string.sharing_states_info))
        }
        val explanation = when (friend.visible) {
            VisibleState.PENDING -> if (person.state == PersonState.REQUESTED || friend.native?.invitedByMe == true) {
                stringResource(R.string.sharing_pending_requested, name)
            } else stringResource(R.string.sharing_pending_connecting)
            VisibleState.ACTIVE -> null
            VisibleState.STOPPED -> stringResource(R.string.sharing_stopped_body, name)
            VisibleState.ENDED -> stringResource(R.string.sharing_ended_body)
        }
        explanation?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        friend.delivery?.let {
            Text(deliveryLabel(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("sharing_confirmed"))
        }
    }
    if (friend.visible == VisibleState.ENDED) {
        SettingsSectionCard {
            Button(onClick = { actions.requestAgain(friend.peer, person.template, person.label) }, enabled = !busy,
                modifier = Modifier.testTag("sharing_request_again")) { Text(stringResource(R.string.sharing_request_again)) }
            OutlinedButton(onClick = { actions.remove(friend.peer, onRemoved) }, enabled = !busy,
                modifier = Modifier.testTag("sharing_remove")) { Text(stringResource(R.string.sharing_remove)) }
        }
        return
    }
    SettingsSectionCard {
        SettingsToggleRow(
            title = stringResource(R.string.sharing_person_switch, name),
            description = stringResource(R.string.sharing_person_switch_info),
            checked = person.outgoing,
            enabled = !busy,
            onCheckedChange = { actions.setOutgoing(friend.peer, it) },
            modifier = Modifier.testTag("sharing_person_switch"),
        )
        Text(stringResource(R.string.sharing_template_label), style = MaterialTheme.typography.labelLarge)
        // A hidden template stays selectable for whoever already has it.
        val offered = templates.filter { !it.hidden || it.id == person.template }
        SettingsChoices(offered.map { it.id to stringResource(SharingLabels.template(it.id)) }, person.template,
            { if (!busy && it != person.template) actions.setTemplate(friend.peer, it) })
        HorizontalDivider()
        SharingLabels.categories.forEach { category ->
            val choice = detail.categories.getValue(category)
            SettingsToggleRow(
                title = stringResource(SharingLabels.category(category)),
                description = stringResource(SharingLabels.categoryInfo(category)),
                checked = choice.shared,
                enabled = !busy,
                onCheckedChange = { actions.setCategory(detail, category, it) },
                modifier = Modifier.testTag("sharing_category_${category.name}"),
            )
            if (choice.exception != null) {
                Text(stringResource(R.string.sharing_exception_marker, stringResource(SharingLabels.template(person.template))),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (detail.items.any { it.record.category.dated } || detail.categories.any { (category, choice) -> category.dated && choice.shared }) {
            Text(stringResource(R.string.sharing_period_title), style = MaterialTheme.typography.labelLarge)
            SettingsChoices(SharePeriod.CHOICES.map { it to periodLabel(it) }, detail.periodDays,
                { if (!busy && it != detail.periodDays) actions.setPeriod(detail, it) })
        }
    }
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_preview_title, name), stringResource(R.string.sharing_preview_info))
        if (person.outgoing && detail.overLimit) {
            Text(stringResource(R.string.sharing_limit_over), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("sharing_limit_over"))
        }
        if (detail.skipped > 0) {
            Text(pluralStringResource(R.plurals.sharing_limit_skipped, detail.skipped, detail.skipped),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("sharing_limit_skipped"))
        }
        when {
            !person.outgoing -> Text(stringResource(R.string.sharing_preview_stopped), style = MaterialTheme.typography.bodyMedium)
            detail.items.isEmpty() -> Text(stringResource(R.string.sharing_preview_empty), style = MaterialTheme.typography.bodyMedium)
            else -> SharingLabels.categories.forEach { category ->
                val items = detail.items.filter { it.record.category == category }
                if (items.isEmpty()) return@forEach
                CategoryHeader(category, stringResource(R.string.sharing_preview_count, items.count { it.included }, items.size))
                Collapsible(items.size) { shown ->
                    items.take(shown).forEach { item ->
                        RecordSwitch(
                            label = recordLabel(item.record),
                            exception = item.exception != null,
                            checked = item.included,
                            enabled = !busy,
                            onChange = { actions.setItem(detail, item, it) },
                        )
                    }
                }
            }
        }
    }
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_received_title, name), stringResource(R.string.sharing_received_info))
        if (friend.visible == VisibleState.ACTIVE || friend.visible == VisibleState.STOPPED) {
            Text(completenessLabel(friend.completeness, name), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sharing_received_state"))
        }
        if (detail.received.isEmpty()) {
            Text(stringResource(R.string.sharing_received_none, name), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sharing_received_none"))
        }
        SharingLabels.categories.forEach { category ->
            val records = detail.received.filter { it.category == category }.sortedByDescending { it.fields["date"].orEmpty() }
            if (records.isEmpty()) return@forEach
            CategoryHeader(category, records.size.toString())
            Collapsible(records.size) { shown -> records.take(shown).forEach { ReceivedLine(it) } }
        }
    }
    SettingsSectionCard {
        var label by rememberSaveable(friend.peer) { mutableStateOf(person.label.orEmpty()) }
        OutlinedTextField(
            value = label,
            onValueChange = { label = it.take(64) },
            label = { Text(stringResource(R.string.sharing_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("sharing_person_name"),
        )
        if (label.trim() != person.label.orEmpty()) {
            TextButton(onClick = { actions.setLabel(friend.peer, label) }, enabled = !busy) { Text(stringResource(R.string.action_save)) }
        }
    }
    SettingsSectionCard {
        var confirm by rememberSaveable(friend.peer) { mutableStateOf(false) }
        val pending = friend.visible == VisibleState.PENDING
        OutlinedButton(
            onClick = { confirm = true },
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.testTag("sharing_end"),
        ) { Text(stringResource(if (pending) R.string.sharing_withdraw else R.string.sharing_end)) }
        Text(stringResource(R.string.sharing_end_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(stringResource(if (pending) R.string.sharing_withdraw else R.string.sharing_end_confirm_title, name)) },
                text = { Text(stringResource(R.string.sharing_end_confirm_body, name)) },
                confirmButton = {
                    TextButton(onClick = { confirm = false; actions.end(friend.peer) }, modifier = Modifier.testTag("sharing_end_confirm")) {
                        Text(stringResource(if (pending) R.string.sharing_withdraw else R.string.sharing_end))
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}

@Composable
private fun CategoryHeader(category: ShareCategory, trailing: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(SharingLabels.category(category)), style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Long lists start short; one button shows the rest. */
@Composable
private fun Collapsible(total: Int, content: @Composable (Int) -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    content(if (all) total else minOf(total, COLLAPSED_ITEMS))
    if (!all && total > COLLAPSED_ITEMS) {
        TextButton(onClick = { all = true }) { Text(stringResource(R.string.sharing_show_all, total)) }
    }
}

@Composable
private fun RecordSwitch(label: String, exception: Boolean, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (exception) {
                Text(stringResource(R.string.sharing_record_exception), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ReceivedLine(record: SharingRecord) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(recordLabel(record), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (record.category == ShareCategory.CLIMBING_PROFILE) {
            record.fields["goals"]?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(R.string.sharing_record_goals, it.replace("\n", ", ")), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A template: what everyone on it receives unless they have an exception. */
@Composable
internal fun TemplatePage(templates: List<ShareTemplate>, id: TemplateId, busy: Boolean, actions: SharingActions) {
    val template = templates.firstOrNull { it.id == id } ?: return
    SettingsSectionCard {
        Text(stringResource(R.string.sharing_template_body), style = MaterialTheme.typography.bodyMedium)
        SharingLabels.categories.forEach { category ->
            SettingsToggleRow(
                title = stringResource(SharingLabels.category(category)),
                description = stringResource(SharingLabels.categoryInfo(category)),
                checked = category in template.categories,
                enabled = !busy,
                onCheckedChange = { on ->
                    actions.putTemplate(template.copy(categories = if (on) template.categories + category else template.categories - category))
                },
                modifier = Modifier.testTag("sharing_template_category_${category.name}"),
            )
        }
        Text(stringResource(R.string.sharing_period_title), style = MaterialTheme.typography.labelLarge)
        SettingsChoices(SharePeriod.CHOICES.map { it to periodLabel(it) }, template.periodDays,
            { if (!busy && it != template.periodDays) actions.putTemplate(template.copy(periodDays = it)) })
    }
    Text(stringResource(R.string.sharing_template_applies), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Who receives which category now, and why (FEAT-073 U2 overview). */
@Composable
internal fun WhoSeesPage(overview: com.cruxcoach.android.sharing.SharingOverview) {
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_whosees_title), stringResource(R.string.sharing_whosees_info))
        val receiving = overview.friends.mapNotNull { it.delivery }
        if (receiving.isNotEmpty()) {
            val waiting = receiving.mapNotNull { it.waitingSince }
            val parts = listOfNotNull(
                stringResource(R.string.sharing_delivery_summary, receiving.size - waiting.size, receiving.size),
                waiting.minOrNull()?.let { oldest -> pluralStringResource(R.plurals.sharing_delivery_waiting, waiting.size, waiting.size, dayLabel(oldest)) },
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sharing_delivery"))
        }
        overview.whoSees.forEach { group ->
            CategoryHeader(group.category, group.entries.size.toString())
            if (group.entries.isEmpty()) {
                Text(stringResource(R.string.sharing_whosees_nobody), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("sharing_whosees_nobody_${group.category.name}"))
            }
            group.entries.forEach { entry ->
                val reason = when (entry.source) {
                    com.cruxcoach.domain.sharing.ShareDecisionSource.TEMPLATE -> stringResource(R.string.sharing_reason_template, entry.name)
                    com.cruxcoach.domain.sharing.ShareDecisionSource.PERSON_ALLOW -> stringResource(R.string.sharing_reason_exception, entry.name)
                    null -> stringResource(R.string.sharing_reason_records, entry.name)
                    else -> entry.name
                }
                val line = entry.expiresAt?.let { until ->
                    stringResource(R.string.sharing_reason_until, reason,
                        java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString())
                } ?: reason
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The local history of own decisions (FEAT-073 U2); kept only on this device. */
@Composable
internal fun HistoryPage(overview: com.cruxcoach.android.sharing.SharingOverview) {
    val names = overview.friends.associate { it.peer to it.displayName }
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_history_title), stringResource(R.string.sharing_history_info))
        if (overview.history.isEmpty()) {
            Text(stringResource(R.string.sharing_history_empty), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("sharing_history_empty"))
        }
        overview.history.forEach { entry ->
            val name = entry.peer?.let { names[it] ?: SharingNames.shortId(it) }.orEmpty()
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(java.time.Instant.ofEpochMilli(entry.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(historyLabel(entry, name), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ConnectionPage(status: MarmotStatus, busy: Boolean, actions: SharingActions, mailboxes: Boolean = false) {
    SettingsSectionCard {
        InfoHeading(stringResource(R.string.sharing_connection_title),
            stringResource(if (mailboxes) R.string.sharing_connection_info_mailboxes else R.string.sharing_connection_info))
        // The relay inside the app exists only over MLS.
        if (!mailboxes) {
            RelayLine(stringResource(R.string.sharing_local_relay),
                stringResource(if (status.localRelay) R.string.sharing_relay_connected else R.string.sharing_relay_offline))
        }
        status.relays.forEach { relay ->
            val name = when (relay.url) {
                "p2p:lan" -> stringResource(R.string.sharing_way_lan)
                "p2p:ble" -> stringResource(R.string.sharing_way_ble)
                else -> relay.url.removePrefix("wss://")
            }
            RelayLine(name, relayLabel(relay))
        }
        val pending = status.local.outboundPending.toInt()
        if (pending > 0) {
            Text(pluralStringResource(if (mailboxes) R.plurals.sharing_pending_delivery else R.plurals.sharing_pending_messages, pending, pending),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("sharing_pending"))
        }
        OutlinedButton(onClick = actions.syncNow, enabled = !busy, modifier = Modifier.testTag("sharing_sync_now")) {
            Text(stringResource(R.string.sharing_sync_now))
        }
    }
}

@Composable
private fun RelayLine(name: String, state: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
