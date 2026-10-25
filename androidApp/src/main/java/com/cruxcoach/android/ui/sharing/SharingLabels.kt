package com.cruxcoach.android.ui.sharing

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.android.sharing.MarmotRelay
import com.cruxcoach.android.sharing.SharingRecord
import com.cruxcoach.android.sharing.TrainingPeriod
import com.cruxcoach.android.sharing.VisibleState
import com.cruxcoach.domain.sharing.SharingCategory
import com.cruxcoach.domain.sharing.SharingCircle

/** One place for every label the sharing screens show. */
internal object SharingLabels {
    fun circle(circle: SharingCircle): Int = when (circle) {
        SharingCircle.FRIENDS -> R.string.sharing_circle_friends
        SharingCircle.ACQUAINTANCES -> R.string.sharing_circle_acquaintances
    }

    fun category(category: SharingCategory): Int = when (category) {
        SharingCategory.PROFILE_AND_GOALS -> R.string.sharing_category_profile
        SharingCategory.TRAINING_HISTORY -> R.string.sharing_category_training
        SharingCategory.PRIVATE_NOTES -> R.string.sharing_category_notes
    }

    fun state(state: VisibleState): Int = when (state) {
        VisibleState.PENDING -> R.string.sharing_state_pending
        VisibleState.ACTIVE -> R.string.sharing_state_active
        VisibleState.STOPPED -> R.string.sharing_state_stopped
        VisibleState.ENDED -> R.string.sharing_state_ended
    }

    /** Circles as the screens offer them: the closer circle first. */
    val circles = listOf(SharingCircle.FRIENDS, SharingCircle.ACQUAINTANCES)
}

@Composable
internal fun periodLabel(days: Int): String =
    if (days == TrainingPeriod.ALL) stringResource(R.string.sharing_period_all)
    else pluralStringResource(R.plurals.sharing_period_days, days, days)

@Composable
internal fun categoriesLabel(categories: Collection<SharingCategory>): String =
    if (categories.isEmpty()) stringResource(R.string.sharing_categories_none)
    else SharingCategory.entries.filter { it in categories }.map { stringResource(SharingLabels.category(it)) }.joinToString(", ")

/** One line per record; never more than the whitelisted fields. */
@Composable
internal fun recordLabel(record: SharingRecord): String {
    val f = record.fields
    return when (record.category) {
        SharingCategory.PROFILE_AND_GOALS -> stringResource(R.string.sharing_record_profile, f["name"].orEmpty(), f["boulderGrade"].orEmpty())
        SharingCategory.TRAINING_HISTORY -> {
            val date = f["date"].orEmpty().take(10)
            if (f["outcome"] == "SEND") stringResource(R.string.sharing_record_send, f["name"].orEmpty(), date)
            else {
                val attempts = f["attempts"]?.toIntOrNull() ?: 1
                pluralStringResource(R.plurals.sharing_record_attempts, attempts, f["name"].orEmpty(), date, attempts)
            }
        }
        SharingCategory.PRIVATE_NOTES -> stringResource(R.string.sharing_record_note, f["note"].orEmpty())
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
