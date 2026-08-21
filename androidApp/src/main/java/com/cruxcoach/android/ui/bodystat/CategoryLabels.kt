package com.cruxcoach.android.ui.bodystat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cruxcoach.android.R
import com.cruxcoach.data.CruxCoachBackup.Category

/**
 * Resolves a [Category]'s display label against the active locale.
 *
 * The shared [Category.label] field is hardcoded German (the wire
 * format lives in `commonMain` where Android's stringResource isn't
 * reachable, and we don't drag a localization framework into KMP just
 * for category names). This Compose-side wrapper picks the right
 * localized string — use it everywhere a category surface in the UI,
 * not the raw `.label` field. Pre-fix the Export/Import screens
 * rendered German labels in the English UI.
 */
@Composable
fun Category.localizedLabel(): String = when (this) {
    Category.PROFILE        -> stringResource(R.string.export_category_profile)
    Category.ASSESSMENTS    -> stringResource(R.string.export_category_assessments)
    Category.BODY_STATS     -> stringResource(R.string.export_category_body_stats)
    Category.WORKOUT_LOGS   -> stringResource(R.string.export_category_workout_logs)
    Category.CLIMB_LOGS     -> stringResource(R.string.export_category_climb_logs)
    Category.TRAINING_PLANS -> stringResource(R.string.export_category_training_plans)
    Category.BOARD_LOGBOOK  -> stringResource(R.string.export_category_board_logbook)
    Category.BOARD_SESSIONS -> stringResource(R.string.export_category_board_sessions)
    Category.CLIMB_LISTS    -> stringResource(R.string.export_category_climb_lists)
    Category.OWN_CLIMBS     -> stringResource(R.string.export_category_own_climbs)
    Category.CLIMB_NOTES    -> stringResource(R.string.export_category_climb_notes)
}
