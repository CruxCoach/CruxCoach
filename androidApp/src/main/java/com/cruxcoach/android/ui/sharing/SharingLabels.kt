package com.cruxcoach.android.ui.sharing

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
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
