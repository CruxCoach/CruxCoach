package com.cruxcoach.android.data.shinywall

import android.content.Context
import com.cruxcoach.android.R

/** Localized one-line result of a Shiny Wall import. */
fun formatShinyWallImportSummary(context: Context, r: ShinyWallImportResult): String {
    if (r.newAscents == 0 && r.newProjects == 0 && r.duplicates == 0 && r.lists == 0 && r.deleted == 0) {
        return context.getString(R.string.shinywall_import_empty)
    }
    val res = context.resources
    // The outcome first: what came in, or that nothing new did.
    val parts = if (r.newAscents == 0 && r.newProjects == 0) {
        mutableListOf(context.getString(R.string.shinywall_import_part_nothing_new))
    } else {
        mutableListOf(
            res.getQuantityString(R.plurals.shinywall_import_part_ascents, r.newAscents, r.newAscents),
            res.getQuantityString(R.plurals.shinywall_import_part_projects, r.newProjects, r.newProjects),
        )
    }
    if (r.lists > 0) parts += res.getQuantityString(R.plurals.shinywall_import_part_lists, r.lists, r.lists)
    if (r.duplicates > 0) parts += context.getString(R.string.shinywall_import_part_known, r.duplicates)
    if (r.skipped > 0) parts += context.getString(R.string.shinywall_import_part_skipped, r.skipped)
    if (r.deleted > 0) parts += res.getQuantityString(R.plurals.shinywall_import_part_deleted, r.deleted, r.deleted)
    return parts.joinToString(" · ")
}

fun ShinyWallAuthResult.Error.localized(context: Context): String = when (reason) {
    ShinyWallAuthResult.Error.Reason.InvalidCredentials -> context.getString(R.string.shinywall_auth_invalid_credentials)
    ShinyWallAuthResult.Error.Reason.AccountDisabled -> context.getString(R.string.shinywall_auth_disabled)
    ShinyWallAuthResult.Error.Reason.Throttled -> context.getString(R.string.shinywall_auth_throttled)
    ShinyWallAuthResult.Error.Reason.NetworkError -> context.getString(R.string.shinywall_auth_network)
    ShinyWallAuthResult.Error.Reason.HttpFailure -> context.getString(R.string.shinywall_auth_http_failure, httpCode ?: -1)
    ShinyWallAuthResult.Error.Reason.UnexpectedResponse -> context.getString(R.string.shinywall_auth_unexpected)
}

/** The stored sign-in no longer works: the user has to sign in again. */
fun isShinyWallSessionExpired(error: Throwable): Boolean =
    error is ShinyWallApiException && error.reason == ShinyWallApiException.Reason.NotAuthenticated

/** Category message for a failed preview/import; never raw exception text. An expired sign-in is no message but [isShinyWallSessionExpired]. */
fun localizeShinyWallImportError(context: Context, error: Throwable): String = when {
    error is ShinyWallApiException && error.reason == ShinyWallApiException.Reason.Http && error.httpCode == 403 ->
        context.getString(R.string.shinywall_import_error_denied)
    error is java.io.IOException || (error is ShinyWallApiException && error.reason == ShinyWallApiException.Reason.Network) ->
        context.getString(R.string.shinywall_import_error_network)
    else -> context.getString(R.string.shinywall_import_error_generic)
}
