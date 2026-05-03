package com.cruxcoach.android.ui.board.creator

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R

/**
 * Locale-aware "X min ago / X h ago / X d ago" label, shared between
 * DraftsDrawer and the Climb-Creator autosave-offer card. Strings live
 * in `values/strings.xml` (English/default) + `values-de/strings.xml`,
 * so a device locale switch updates the labels without code changes.
 *
 * For an ISO-8601 timestamp older than ~7 days the function returns the
 * date prefix (`yyyy-MM-dd`) instead of an unbounded "vor 42 t".
 */
@Composable
fun relativeTimeLabel(epochMs: Long): String {
    val seconds = (System.currentTimeMillis() - epochMs) / 1000L
    return when {
        seconds < 60 -> stringResource(R.string.relative_time_just_now)
        seconds < 3600 -> stringResource(R.string.relative_time_minutes_ago, (seconds / 60).toInt())
        seconds < 86400 -> stringResource(R.string.relative_time_hours_ago, (seconds / 3600).toInt())
        else -> stringResource(R.string.relative_time_days_ago, (seconds / 86400).toInt())
    }
}

/** Convenience: takes an ISO-8601 instant; falls back to the date prefix on parse error or > 7 days. */
@Composable
fun relativeTimeLabel(iso: String): String {
    val ms: Long = try {
        java.time.Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        return iso.take(10)
    }
    val seconds = (System.currentTimeMillis() - ms) / 1000L
    return when {
        seconds < 60 -> stringResource(R.string.relative_time_just_now)
        seconds < 3600 -> stringResource(R.string.relative_time_minutes_ago, (seconds / 60).toInt())
        seconds < 86400 -> stringResource(R.string.relative_time_hours_ago, (seconds / 3600).toInt())
        seconds < 7 * 86400 -> stringResource(R.string.relative_time_days_ago, (seconds / 86400).toInt())
        else -> iso.take(10)
    }
}
