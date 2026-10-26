package com.cruxcoach.android.ui.sharing

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.Completeness
import com.cruxcoach.android.sharing.Delivery
import com.cruxcoach.android.sharing.HistoryEntry
import com.cruxcoach.android.sharing.SharingService
import com.cruxcoach.android.sharing.HistoryEvent
import com.cruxcoach.android.sharing.MarmotRelay
import com.cruxcoach.android.sharing.SharingRecord
import com.cruxcoach.android.sharing.SharingRecords
import com.cruxcoach.android.sharing.VisibleState
import com.cruxcoach.domain.sharing.ShareCategory
import com.cruxcoach.domain.sharing.SharePeriod
import com.cruxcoach.domain.sharing.ShareTemplates
import com.cruxcoach.domain.sharing.TemplateId

/** One place for every label the sharing screens show. */
internal object SharingLabels {
    fun template(id: TemplateId): Int = when (id) {
        ShareTemplates.TRAINING_PARTNER.id -> R.string.sharing_template_training_partner
        ShareTemplates.FAMILY.id -> R.string.sharing_template_family
        ShareTemplates.COACH.id -> R.string.sharing_template_coach
        else -> R.string.sharing_template_custom
    }

    fun category(category: ShareCategory): Int = when (category) {
        ShareCategory.CLIMBING_PROFILE -> R.string.sharing_category_profile
        ShareCategory.SENDS -> R.string.sharing_category_sends
        ShareCategory.ATTEMPTS -> R.string.sharing_category_attempts
        ShareCategory.CLIMB_NOTES -> R.string.sharing_category_notes
        else -> R.string.sharing_category_other
    }

    fun categoryInfo(category: ShareCategory): Int = when (category) {
        ShareCategory.CLIMBING_PROFILE -> R.string.sharing_category_profile_info
        ShareCategory.SENDS -> R.string.sharing_category_sends_info
        ShareCategory.ATTEMPTS -> R.string.sharing_category_attempts_info
        ShareCategory.CLIMB_NOTES -> R.string.sharing_category_notes_info
        else -> R.string.sharing_category_other
    }

    /** The categories the screens offer, in catalogue order. */
    val categories: List<ShareCategory> = SharingRecords.supported.sortedBy { it.ordinal }

    fun state(state: VisibleState): Int = when (state) {
        VisibleState.PENDING -> R.string.sharing_state_pending
        VisibleState.ACTIVE -> R.string.sharing_state_active
        VisibleState.STOPPED -> R.string.sharing_state_stopped
        VisibleState.ENDED -> R.string.sharing_state_ended
    }
}

/** "today 09:12", "yesterday 18:00", "Mon 14:05" within a week, else a date. */
@Composable
internal fun momentLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val zone = java.time.ZoneId.systemDefault()
    val time = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val days = java.time.temporal.ChronoUnit.DAYS.between(time.toLocalDate(), java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate())
    val clock = time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", locale))
    return when (days) {
        0L -> stringResource(R.string.sharing_time_today, clock)
        1L -> stringResource(R.string.sharing_time_yesterday, clock)
        in 2L..6L -> time.format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", locale))
        else -> time.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
    }
}

/** "today", "yesterday", "Tue" within a week, else a date. */
@Composable
internal fun dayLabel(at: Long, now: Long = System.currentTimeMillis()): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val zone = java.time.ZoneId.systemDefault()
    val date = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    return when (java.time.temporal.ChronoUnit.DAYS.between(date, java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate())) {
        0L -> stringResource(R.string.sharing_day_today)
        1L -> stringResource(R.string.sharing_day_yesterday)
        in 2L..6L -> date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", locale))
        else -> date.format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale))
    }
}

/** What we hold of [name]'s data (VS7): never "up to date" while a gap is known. */
@Composable
internal fun completenessLabel(completeness: Completeness, name: String, now: Long = System.currentTimeMillis()): String = when (completeness) {
    is Completeness.Complete ->
        if (now - completeness.asOf <= SharingService.CURRENT_MS) stringResource(R.string.sharing_complete_current, momentLabel(completeness.asOf, now))
        else stringResource(R.string.sharing_complete_quiet, momentLabel(completeness.asOf, now), name)
    is Completeness.Filling ->
        if (completeness.since > 0 && now - completeness.since > SharingService.LONG_GAP_MS) {
            stringResource(R.string.sharing_complete_missing, dayLabel(completeness.since, now), name)
        } else stringResource(R.string.sharing_complete_filling)
}

/** Whether [name] confirmed what we share (VS8). */
@Composable
internal fun deliveryLabel(delivery: Delivery, now: Long = System.currentTimeMillis()): String = when {
    delivery.waitingSince == null -> stringResource(R.string.sharing_confirmed, momentLabel(delivery.confirmedAt, now))
    delivery.confirmedAt > 0 -> stringResource(R.string.sharing_confirmed_waiting, momentLabel(delivery.confirmedAt, now))
    else -> stringResource(R.string.sharing_confirmed_never)
}

@Composable
internal fun periodLabel(days: Int): String =
    if (days == SharePeriod.ALL) stringResource(R.string.sharing_period_all)
    else pluralStringResource(R.plurals.sharing_period_days, days, days)

@Composable
internal fun categoriesLabel(categories: Collection<ShareCategory>): String {
    val shown = SharingLabels.categories.filter { it in categories }
    return if (shown.isEmpty()) stringResource(R.string.sharing_categories_none)
    else shown.map { stringResource(SharingLabels.category(it)) }.joinToString(", ")
}

/** One line per record; never more than the whitelisted fields. */
@Composable
internal fun recordLabel(record: SharingRecord): String {
    val f = record.fields
    return when (record.category) {
        ShareCategory.CLIMBING_PROFILE -> stringResource(R.string.sharing_record_profile, f["name"].orEmpty(), f["boulderGrade"].orEmpty())
        ShareCategory.SENDS -> stringResource(R.string.sharing_record_send, f["name"].orEmpty(), f["date"].orEmpty().take(10))
        ShareCategory.ATTEMPTS -> {
            val attempts = f["attempts"]?.toIntOrNull() ?: 1
            pluralStringResource(R.plurals.sharing_record_attempts, attempts, f["name"].orEmpty(), f["date"].orEmpty().take(10), attempts)
        }
        ShareCategory.CLIMB_NOTES -> stringResource(R.string.sharing_record_note, f["note"].orEmpty())
        else -> ""
    }
}

/** One line of the local history. */
@Composable
internal fun historyLabel(entry: HistoryEntry, name: String): String {
    val detail = entry.detail.orEmpty()
    @Composable fun template(id: String) = stringResource(SharingLabels.template(TemplateId(id.ifBlank { "?" })))
    @Composable fun category(name: String): String {
        val known = ShareCategory.entries.firstOrNull { it.name == name } ?: return name
        return stringResource(SharingLabels.category(known))
    }
    return when (entry.event) {
        HistoryEvent.REQUESTED -> stringResource(R.string.sharing_history_requested, name, template(detail))
        HistoryEvent.ACCEPTED -> stringResource(
            if (detail.endsWith(":on")) R.string.sharing_history_accepted_sharing else R.string.sharing_history_accepted_receiving, name)
        HistoryEvent.TEMPLATE_CHOSEN -> stringResource(R.string.sharing_history_template, name, template(detail))
        HistoryEvent.CATEGORY_RULE, HistoryEvent.RECORD_RULE -> {
            val parts = detail.split(':')
            val what = category(parts.getOrElse(0) { "" })
            val res = when (parts.getOrElse(1) { "" }) {
                "ALLOW" -> if (entry.event == HistoryEvent.RECORD_RULE) R.string.sharing_history_record_allow else R.string.sharing_history_category_allow
                "DENY" -> if (entry.event == HistoryEvent.RECORD_RULE) R.string.sharing_history_record_deny else R.string.sharing_history_category_deny
                else -> R.string.sharing_history_follow_template
            }
            stringResource(res, name, what)
        }
        HistoryEvent.NEVER_SHARE -> stringResource(if (detail == "on") R.string.sharing_history_never_on else R.string.sharing_history_never_off)
        HistoryEvent.OUTGOING -> stringResource(if (detail == "on") R.string.sharing_history_outgoing_on else R.string.sharing_history_outgoing_off, name)
        HistoryEvent.TEMPLATE_EDITED -> stringResource(R.string.sharing_history_template_edited, template(detail))
        HistoryEvent.AGE -> stringResource(if (detail == "on") R.string.sharing_history_age_on else R.string.sharing_history_age_off)
        HistoryEvent.EXPIRED -> stringResource(R.string.sharing_history_expired, name)
        HistoryEvent.ENDED -> stringResource(R.string.sharing_history_ended, name)
        HistoryEvent.REMOVED -> stringResource(R.string.sharing_history_removed, name)
    }
}

@Composable
internal fun relayLabel(relay: MarmotRelay): String {
    val connection = stringResource(when (relay.connection) {
        "connected" -> R.string.sharing_relay_connected
        "connecting" -> R.string.sharing_relay_connecting
        "banned" -> R.string.sharing_relay_banned
        "sleeping" -> R.string.sharing_relay_sleeping
        else -> R.string.sharing_relay_offline
    })
    val result = when (relay.lastResult) {
        "accepted" -> R.string.sharing_relay_accepted
        "rejected" -> R.string.sharing_relay_rejected
        "auth_required" -> R.string.sharing_relay_auth_required
        "unavailable" -> R.string.sharing_relay_unavailable
        else -> null
    }?.let { stringResource(it) }
    return listOfNotNull(connection, result).joinToString(" · ")
}
