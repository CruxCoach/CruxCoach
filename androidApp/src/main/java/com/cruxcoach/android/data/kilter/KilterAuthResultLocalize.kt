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
