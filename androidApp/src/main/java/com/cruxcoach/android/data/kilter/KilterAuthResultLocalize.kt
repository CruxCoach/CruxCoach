package com.cruxcoach.android.data.kilter

import android.content.Context
import com.cruxcoach.android.R

/**
 * Map a typed [KilterAuthResult.Error] to the localized user-visible
 * string that the auth-UI should show.
 *
 * Lives in the data-layer package next to the error type so future
 * additions to [KilterAuthResult.Error.Reason] surface as compile
 * errors here (the `when` is exhaustive over the sealed interface)
 * — the audit's intent was that adding a reason without giving it a
 * localized string should be a compile-time miss, not a runtime one.
 */
fun KilterAuthResult.Error.localized(context: Context): String = when (reason) {
    is KilterAuthResult.Error.Reason.InvalidCredentials ->
        context.getString(R.string.kilter_auth_invalid_credentials)
    is KilterAuthResult.Error.Reason.EmptyResponse ->
        context.getString(R.string.kilter_auth_empty_response)
    is KilterAuthResult.Error.Reason.NetworkError ->
        context.getString(R.string.kilter_auth_network_error)
    is KilterAuthResult.Error.Reason.Throttled -> {
        val sec = throttleSec
        if (sec != null) context.getString(R.string.kilter_auth_throttled_with_seconds, sec.toInt())
        else context.getString(R.string.kilter_auth_throttled)
    }
    is KilterAuthResult.Error.Reason.NotAuthenticated ->
        context.getString(R.string.kilter_auth_not_authenticated)
    is KilterAuthResult.Error.Reason.InvalidJwt ->
        context.getString(R.string.kilter_auth_invalid_jwt)
    is KilterAuthResult.Error.Reason.HttpFailure ->
        context.getString(R.string.kilter_auth_http_failure, httpCode ?: -1)
}

/**
 * Map an import/sync [Throwable] to a localized, user-safe message.
 *
 * Mirrors [com.cruxcoach.android.data.BoardSyncManager]'s policy: NEVER
 * surface raw exception text (SQLite / IO messages, cache paths, class
 * names) in the UI — only a category string. The raw cause is logged by
 * the caller. Distinguishes a lost/expired Kilter session and a network
 * problem from an otherwise-unclassified failure so the message can guide
 * the user (re-login vs. check connection vs. just retry).
 */
fun localizeKilterImportError(context: Context, error: Throwable): String = when {
    error is KilterApiException &&
        error.reason == KilterAuthResult.Error.Reason.NotAuthenticated ->
        context.getString(R.string.kilter_import_error_auth)
    error is java.net.UnknownHostException ||
        error is java.net.SocketTimeoutException ||
        (error is java.io.IOException && error !is java.io.FileNotFoundException) ->
        context.getString(R.string.kilter_import_error_network)
    else ->
        context.getString(R.string.kilter_import_error_generic)
}
